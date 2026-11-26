"""Push to the owner's phones with Firebase Cloud Messaging (HTTP v1 API).

The push carries only a wake-up signal and an alert id (`{"k": "alert", "id": ...}`); the phone fetches what it is
about from Krewd over its own connection (home network or Tailscale), so Google never sees a title or a chat.

The service-account key file (`KREWD_FCM_KEY`, default `~/.config/krewd/fcm-service-account.json`, mode 600) lives
only on this machine and never goes into git. Without it push is off and the app shows alerts only while it is open.
It is read on each token refresh, so dropping it in place needs no restart.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

from .config import CONFIG_DIR

log = logging.getLogger("krewd.push")

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
SEND_URL = "https://fcm.googleapis.com/v1/projects/{project}/messages:send"
# A cap on sends, so a runaway loop cannot hammer FCM (it is free, but the phone would ring all day).
MAX_PER_MINUTE = 30
TIMEOUT = httpx.Timeout(10.0, connect=5.0)


def key_path() -> Path:
    return Path(os.path.expanduser(os.environ.get("KREWD_FCM_KEY", str(CONFIG_DIR / "fcm-service-account.json"))))


class PushError(Exception):
    pass


@dataclass
class Sent:
    ok: bool
    gone: bool = False  # FCM no longer knows the token: drop it
    error: str | None = None


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def read_key(path: Path) -> dict[str, Any]:
    """The service account's project, e-mail and private key; never logged."""
    try:
        info = json.loads(path.read_text())
    except (OSError, ValueError) as exc:
        raise PushError(f"cannot read the FCM key file {path}: {exc.__class__.__name__}") from None
    for field in ("project_id", "client_email", "private_key"):
        if not info.get(field):
            raise PushError(f"the FCM key file {path} has no {field}: download a service-account key (JSON)")
    return info


def signed_assertion(info: dict[str, Any], now: int | None = None) -> str:
    """A JWT the service account signs (RS256), traded for an access token at Google's token endpoint."""
    now = int(time.time()) if now is None else now
    header = {"alg": "RS256", "typ": "JWT"}
    if info.get("private_key_id"):
        header["kid"] = info["private_key_id"]
    claims = {"iss": info["client_email"], "scope": SCOPE, "aud": info.get("token_uri") or DEFAULT_TOKEN_URI,
              "iat": now, "exp": now + 3600}
    signing_input = _b64(json.dumps(header, separators=(",", ":")).encode()) + "." + \
        _b64(json.dumps(claims, separators=(",", ":")).encode())
    key = serialization.load_pem_private_key(info["private_key"].encode(), password=None)
    signature = key.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())  # type: ignore[union-attr]
    return signing_input + "." + _b64(signature)


class Fcm:
    def __init__(self, path: Path | None = None, client: httpx.AsyncClient | None = None) -> None:
        self.path = path or key_path()
        self._client = client
        self._token: str | None = None
        self._token_until = 0.0
        self._project: str | None = None
        self._lock = asyncio.Lock()
        self._sent: list[float] = []

    @property
    def configured(self) -> bool:
        return self.path.is_file()

    def status(self) -> dict[str, Any]:
        if not self.configured:
            return {"configured": False, "project": None}
        try:
            return {"configured": True, "project": read_key(self.path)["project_id"]}
        except PushError as exc:
            return {"configured": False, "project": None, "error": str(exc)}

    def client(self) -> httpx.AsyncClient:
        # One client (connection pool, keep-alive) for every token refresh and send.
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=TIMEOUT)
        return self._client

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def _access(self) -> tuple[str, str]:
        async with self._lock:
            if self._token and time.monotonic() < self._token_until:
                return self._token, self._project or ""
            info = read_key(self.path)
            resp = await self.client().post(
                info.get("token_uri") or DEFAULT_TOKEN_URI,
                data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": signed_assertion(info)},
            )
            if resp.status_code != 200:
                raise PushError(f"Google refused the FCM key (HTTP {resp.status_code}): {resp.text[:200]}")
            body = resp.json()
            self._token = body["access_token"]
            self._token_until = time.monotonic() + max(60, int(body.get("expires_in", 3600)) - 120)
            self._project = info["project_id"]
            return self._token, self._project

    def _allow(self) -> bool:
        now = time.monotonic()
        self._sent = [t for t in self._sent if now - t < 60]
        if len(self._sent) >= MAX_PER_MINUTE:
            return False
        self._sent.append(now)
        return True

    async def send(self, token: str, data: dict[str, str], ttl_seconds: int = 3600) -> Sent:
        """One data message, high priority (it must always end in a notification on the phone)."""
        if not self._allow():
            return Sent(False, error="more than %d pushes in a minute; held back" % MAX_PER_MINUTE)
        try:
            access, project = await self._access()
        except (PushError, httpx.HTTPError) as exc:
            return Sent(False, error=str(exc) if isinstance(exc, PushError) else f"token request failed: {exc!r}")
        message = {"message": {"token": token, "data": data,
                               "android": {"priority": "HIGH", "ttl": f"{ttl_seconds}s"}}}
        for attempt in range(2):
            try:
                resp = await self.client().post(SEND_URL.format(project=project), json=message,
                                                headers={"Authorization": f"Bearer {access}"})
            except httpx.HTTPError as exc:
                if attempt == 0:
                    await asyncio.sleep(1.5)
                    continue
                return Sent(False, error=f"FCM did not answer: {exc!r}")
            if resp.status_code == 200:
                return Sent(True)
            text = resp.text[:300]
            if resp.status_code == 404 or "UNREGISTERED" in text:
                return Sent(False, gone=True, error="token unregistered")
            if resp.status_code == 401:
                self._token = None  # expired early: one fresh token, then give up
                if attempt == 0:
                    access, project = await self._access()
                    continue
            if resp.status_code in (500, 502, 503) and attempt == 0:
                await asyncio.sleep(1.5)
                continue
            return Sent(False, error=f"FCM said {resp.status_code}: {text}")
        return Sent(False, error="FCM send failed")


def check() -> int:
    """`python -m krewd_server.push check` for doctor: the key file is there, private and readable."""
    path = key_path()
    if not path.is_file():
        print(f"WARN  push: no FCM key ({path}); alerts reach the phone only while the app is open")
        return 0
    mode = path.stat().st_mode & 0o777
    if mode & 0o077:
        print(f"FAIL  push: {path} is readable by others (mode {mode:o}); chmod 600 {path}")
        return 1
    try:
        info = read_key(path)
        signed_assertion(info)
    except Exception as exc:  # a broken key file: say so without printing it
        print(f"FAIL  push: {exc}")
        return 1
    print(f"OK    push: FCM key for project {info['project_id']}")
    return 0


if __name__ == "__main__":
    import sys

    if sys.argv[1:] == ["check"]:
        sys.exit(check())
    print("usage: python -m krewd_server.push check")
    sys.exit(2)
