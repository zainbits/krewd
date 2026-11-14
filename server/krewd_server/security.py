"""Who may use Krewd: the owner's security key, the owner's phones, and short sessions.

- The owner's security key (any FIDO U2F/FIDO2 key, tapped on the phone over NFC) is registered once. Its public
  key lives in `owner.json` in the config folder, never in the database, so someone who can only change the
  database cannot swap it.
- Each phone makes two keys in its own secure hardware: a device key (proves the phone; used without asking)
  and a presence key (each use needs the owner's fingerprint or screen lock). A phone is added only with a tap
  of the security key, whose signature covers the phone's keys and is checked again on every session, so a
  phone row written straight into the database is refused.
- A phone gets a session (12 hours, held in memory only) by signing a fresh nonce with its device key.
- The channel is part of every signature: the phone signs the pin of the certificate it saw. Over Krewd's own
  HTTPS that must be Krewd's pin, so a man in the middle (another certificate) is refused even on a first
  pairing, when the phone does not know the pin yet.
- The token from `./master.sh token` only sets Krewd up: it pairs the first phone, then is replaced by a new one
  that stays on the server, for the `krewd` command and local tools (accepted from loopback only).
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import asyncpg
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .config import Settings, new_token

APP_PARAM = hashlib.sha256(b"krewd").digest()  # the U2F "application" every Krewd credential belongs to
SESSION_SECONDS = 12 * 3600
NONCE_SECONDS = 180
MAX_NONCES = 500
PURPOSES = ("pair", "session", "presence", "revoke")


class SecurityError(Exception):
    """A request that fails a security check: 401/403 to the caller, with no detail of what was wrong."""

    def __init__(self, message: str, status: int = 403) -> None:
        super().__init__(message)
        self.status = status


@dataclass
class Principal:
    kind: str  # "local": the token, from this machine; "setup": the token before any phone is paired; "device"
    device_id: uuid.UUID | None = None


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def unb64(text: str | None) -> bytes:
    if not text:
        raise SecurityError("missing field", 422)
    try:
        return base64.b64decode(text, validate=True)
    except ValueError:
        raise SecurityError("bad base64", 422) from None


def digest(data: bytes) -> str:
    return b64(hashlib.sha256(data).digest())


def challenge_param(purpose: str, nonce: str, payload: str) -> bytes:
    """What the security key signs (the U2F challenge parameter): purpose, nonce and payload, hashed."""
    return hashlib.sha256(f"krewd-u2f-v1\n{purpose}\n{nonce}\n{payload}".encode()).digest()


def pair_payload(device_id: str, device_key: bytes, presence_key: bytes, pin_seen: str) -> str:
    return f"{device_id}|{digest(device_key)}|{digest(presence_key)}|{pin_seen}"


def device_message(purpose: str, nonce: str, pin_seen: str) -> bytes:
    return f"krewd-device-v1\n{purpose}\n{nonce}\n{pin_seen}".encode()


def presence_message(nonce: str, method: str, path: str, body: bytes) -> bytes:
    return f"krewd-presence-v1\n{nonce}\n{method.upper()}\n{path}\n{digest(body)}".encode()


def verify_p256(spki: bytes, signature: bytes, message: bytes) -> bool:
    try:
        key = serialization.load_der_public_key(spki)
        if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
            return False
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, TypeError):
        return False


def parse_registration(data: bytes) -> tuple[bytes, bytes]:
    """A U2F register response: 0x05, public key (65 bytes), key handle length and key handle, certificate, signature."""
    if len(data) < 67 or data[0] != 0x05 or data[1] != 0x04:
        raise SecurityError("not a security key registration", 422)
    public, length = data[1:66], data[66]
    handle = data[67:67 + length]
    if len(handle) != length or length == 0:
        raise SecurityError("not a security key registration", 422)
    return public, handle


def verify_assertion(public: bytes, assertion: bytes, challenge: bytes) -> tuple[int, int]:
    """A U2F authenticate response: user presence flags, counter (4 bytes), signature. Returns (flags, counter)."""
    if len(assertion) < 6:
        raise SecurityError("not a security key signature", 422)
    flags, counter, signature = assertion[0], int.from_bytes(assertion[1:5], "big"), assertion[5:]
    if not flags & 0x01:
        raise SecurityError("the security key was not touched")
    try:
        key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), public)
        key.verify(signature, APP_PARAM + assertion[:5] + challenge, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        raise SecurityError("the security key's signature does not match") from None
    return flags, counter


class Security:
    def __init__(self, settings: Settings, pool: asyncpg.Pool, token: str, tls_pin: str | None) -> None:
        self.settings = settings
        self.pool = pool
        self.token = token
        self.tls_pin = tls_pin
        self.owner_file = settings.token_file.parent / "owner.json"
        self.owner: dict[str, Any] | None = self._read_owner()
        self.nonces: dict[str, tuple[str, float]] = {}
        self.sessions: dict[str, tuple[uuid.UUID, float]] = {}  # sha256(session) -> (device, expiry)

    # owner key (security key)

    def _read_owner(self) -> dict[str, Any] | None:
        try:
            data = json.loads(self.owner_file.read_text())
            return data if data.get("public_key") and data.get("key_handle") else None
        except (OSError, ValueError):
            return None

    def _write_owner(self, owner: dict[str, Any]) -> None:
        tmp = self.owner_file.with_name("owner.json.tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(owner, f)
        os.replace(tmp, self.owner_file)
        self.owner = owner

    @property
    def paired(self) -> bool:
        return self.owner is not None

    def _rotate_token(self) -> None:
        """A new token that only this machine has: the one the phone was set up with stops working."""
        token = new_token()
        tmp = self.settings.token_file.with_name(self.settings.token_file.name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(token + "\n")
        os.replace(tmp, self.settings.token_file)
        self.token = token

    def _owner_check(self, purpose: str, nonce: str, payload: str, assertion: bytes, advance: bool) -> dict[str, Any]:
        """Verify a security key signature; with [advance], it must be new (higher counter) and the counter is saved."""
        owner = self.owner
        if owner is None:
            raise SecurityError("no security key registered yet")
        flags, counter = verify_assertion(unb64(owner["public_key"]), assertion, challenge_param(purpose, nonce, payload))
        if advance:
            if counter <= int(owner.get("counter", 0)) and not (counter == 0 and int(owner.get("counter", 0)) == 0):
                raise SecurityError("the security key's counter went back: is it a copy?")
            self._write_owner({**owner, "counter": counter})
        return {"flags": flags, "counter": counter}

    # nonces

    def nonce(self, purpose: str) -> dict[str, Any]:
        if purpose not in PURPOSES:
            raise SecurityError("unknown purpose", 422)
        now = time.monotonic()
        for key in [k for k, (_, exp) in self.nonces.items() if exp < now]:
            del self.nonces[key]
        while len(self.nonces) >= MAX_NONCES:
            del self.nonces[next(iter(self.nonces))]
        value = secrets.token_urlsafe(24)
        self.nonces[value] = (purpose, now + NONCE_SECONDS)
        answer: dict[str, Any] = {"nonce": value, "expires_in": NONCE_SECONDS, "paired": self.paired, "app_param": b64(APP_PARAM)}
        if self.owner and purpose in ("pair", "revoke"):
            answer["key_handle"] = self.owner["key_handle"]
        return answer

    def _use_nonce(self, value: str, purpose: str) -> None:
        entry = self.nonces.pop(value or "", None)
        if entry is None or entry[0] != purpose or entry[1] < time.monotonic():
            raise SecurityError("that request expired: try again")

    def channel_ok(self, krewd_tls: bool, pin_seen: str) -> None:
        """Over Krewd's own HTTPS the phone must have seen Krewd's certificate; anything else is a man in the middle."""
        if krewd_tls and not (self.tls_pin and hmac.compare_digest(pin_seen or "", self.tls_pin)):
            raise SecurityError("the phone saw a different server certificate: someone may be in between")

    # phones

    async def pair(self, body: dict[str, Any], principal: Principal | None, krewd_tls: bool) -> dict[str, Any]:
        """Add a phone. The first one comes with the setup token and registers the security key in the same tap."""
        nonce, pin_seen = str(body.get("nonce") or ""), str(body.get("pin_seen") or "")
        self._use_nonce(nonce, "pair")
        self.channel_ok(krewd_tls, pin_seen)
        try:
            device_id = uuid.UUID(str(body.get("device_id")))
        except ValueError:
            raise SecurityError("bad device id", 422) from None
        name = str(body.get("name") or "Phone")[:60]
        device_key, presence_key = unb64(body.get("device_key")), unb64(body.get("presence_key"))
        if not verify_p256(device_key, unb64(body.get("device_sig")), device_message("pair", nonce, pin_seen)):
            raise SecurityError("the phone's key does not match its signature")
        if serialization.load_der_public_key(presence_key) is None:
            raise SecurityError("bad presence key", 422)
        payload = pair_payload(str(device_id), device_key, presence_key, pin_seen)
        assertion = unb64(body.get("assertion"))
        first = not self.paired
        if first:
            if principal is None or principal.kind not in ("setup", "local"):
                raise SecurityError("the first phone needs the setup token", 401)
            public, handle = parse_registration(unb64(body.get("registration")))
            flags, counter = verify_assertion(public, assertion, challenge_param("pair", nonce, payload))
            self._write_owner({"public_key": b64(public), "key_handle": b64(handle), "counter": counter,
                               "created": time.time()})
            proof = {"flags": flags, "counter": counter}
        else:
            proof = self._owner_check("pair", nonce, payload, assertion, advance=True)
        proof.update({"nonce": nonce, "pin_seen": pin_seen, "assertion": b64(assertion)})
        await self.pool.execute(
            """insert into devices (id, name, device_key, presence_key, proof, last_seen) values ($1, $2, $3, $4, $5, now())
               on conflict (id) do update set name = $2, device_key = $3, presence_key = $4, proof = $5,
               last_seen = now(), revoked_at = null""",
            device_id, name, device_key, presence_key, proof,
        )
        if first:
            self._rotate_token()
        return {"device_id": str(device_id), **self._new_session(device_id)}

    async def _device(self, device_id: uuid.UUID) -> asyncpg.Record:
        row = await self.pool.fetchrow("select * from devices where id = $1 and revoked_at is null", device_id)
        if row is None:
            raise SecurityError("this phone is not (or no longer) allowed", 401)
        proof = row["proof"]
        payload = pair_payload(str(row["id"]), bytes(row["device_key"]), bytes(row["presence_key"]), proof.get("pin_seen", ""))
        # The security key's signature over this phone's keys, checked every time: a row added or changed straight
        # in the database (without the key) is refused.
        self._owner_check("pair", proof.get("nonce", ""), payload, unb64(proof.get("assertion")), advance=False)
        return row

    def _new_session(self, device_id: uuid.UUID) -> dict[str, Any]:
        now = time.monotonic()
        for key in [k for k, (_, exp) in self.sessions.items() if exp < now]:
            del self.sessions[key]
        session = secrets.token_urlsafe(32)
        self.sessions[hashlib.sha256(session.encode()).hexdigest()] = (device_id, now + SESSION_SECONDS)
        return {"session": session, "expires_in": SESSION_SECONDS}

    async def session(self, body: dict[str, Any], krewd_tls: bool) -> dict[str, Any]:
        nonce, pin_seen = str(body.get("nonce") or ""), str(body.get("pin_seen") or "")
        self._use_nonce(nonce, "session")
        self.channel_ok(krewd_tls, pin_seen)
        try:
            device_id = uuid.UUID(str(body.get("device_id")))
        except ValueError:
            raise SecurityError("bad device id", 422) from None
        row = await self._device(device_id)
        if not verify_p256(bytes(row["device_key"]), unb64(body.get("sig")), device_message("session", nonce, pin_seen)):
            raise SecurityError("this phone's signature does not match", 401)
        await self.pool.execute("update devices set last_seen = now() where id = $1", device_id)
        return self._new_session(device_id)

    def authenticate(self, value: str, client_host: str | None, krewd_tls: bool) -> Principal:
        value = value.strip()
        if value and hmac.compare_digest(value.encode(), self.token.encode()):
            if not self.paired:
                return Principal("setup")
            if not krewd_tls and client_host in ("127.0.0.1", "::1"):
                return Principal("local")
            raise SecurityError("this token only works on the Krewd machine now; the phone uses its own keys", 401)
        entry = self.sessions.get(hashlib.sha256(value.encode()).hexdigest()) if value else None
        if entry is None or entry[1] < time.monotonic():
            raise SecurityError("missing or wrong token", 401)
        return Principal("device", entry[0])

    async def check_presence(self, principal: Principal, header: str, method: str, path: str, body: bytes) -> None:
        """The owner's fingerprint (or screen lock) just now, on a paired phone, for this exact request."""
        if principal.kind == "local":
            return
        if principal.kind != "device" or principal.device_id is None:
            raise SecurityError("this needs a phone set up with the security key")
        nonce, _, sig = (header or "").partition(".")
        self._use_nonce(nonce, "presence")
        row = await self._device(principal.device_id)
        if not verify_p256(bytes(row["presence_key"]), unb64(sig), presence_message(nonce, method, path, body)):
            raise SecurityError("the fingerprint check did not match this request")

    # managing phones

    async def devices(self, current: uuid.UUID | None) -> list[dict[str, Any]]:
        rows = await self.pool.fetch("select * from devices where revoked_at is null order by created_at")
        return [{"id": str(r["id"]), "name": r["name"], "created_at": r["created_at"].isoformat(),
                 "last_seen": r["last_seen"].isoformat() if r["last_seen"] else None, "current": r["id"] == current}
                for r in rows]

    async def revoke(self, device_id: uuid.UUID, body: dict[str, Any] | None, principal: Principal) -> None:
        """Cut a phone off: with a security key tap from a phone, or by the `krewd` command on this machine."""
        if principal.kind != "local":
            nonce = str((body or {}).get("nonce") or "")
            self._use_nonce(nonce, "revoke")
            self._owner_check("revoke", nonce, str(device_id), unb64((body or {}).get("assertion")), advance=True)
        await self.pool.execute("update devices set revoked_at = now() where id = $1 and revoked_at is null", device_id)
        for key in [k for k, (d, _) in self.sessions.items() if d == device_id]:
            del self.sessions[key]

    async def reset(self) -> None:
        """Back to setup (lost phone and key): no security key, no phones, a new setup token."""
        await self.pool.execute("update devices set revoked_at = now() where revoked_at is null")
        self.sessions.clear()
        self.nonces.clear()
        if self.owner_file.exists():
            self.owner_file.rename(self.owner_file.with_name(f"owner.json.reset-{int(time.time())}"))
        self.owner = None
        self._rotate_token()
