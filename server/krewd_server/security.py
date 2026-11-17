"""Who may use Krewd: the owner's security key, the owner's phones, and short sessions.

- The owner's keys: a FIDO security key (U2F, over USB-C or NFC on the phone) and/or phone passkeys (WebAuthn,
  synced by the phone's password manager; see passkeys.py). Either kind sets Krewd up; once set up, adding or
  removing an owner key needs a signature from one already registered, and the last one cannot be removed.
  Their public keys live in `owner.json` in the config folder, never in the database, so someone who can only
  change the database cannot swap them.
- Each phone makes two keys in its own secure hardware: a device key (proves the phone; used without asking)
  and a presence key (each use needs the owner's fingerprint or screen lock). A phone is added only with a tap
  of an owner key, whose signature covers the phone's keys and is checked again on every session, so a
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

from . import passkeys
from .config import Settings, new_token

APP_PARAM = hashlib.sha256(b"krewd").digest()  # the U2F "application" every Krewd credential belongs to
SESSION_SECONDS = 12 * 3600
NONCE_SECONDS = 180
MAX_NONCES = 500
PURPOSES = ("pair", "session", "presence", "revoke", "add-key", "remove-key")
KINDS = ("u2f", "passkey")


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


def key_digest(registration: bytes) -> str:
    """How a new owner key is named in what an existing owner key signs to add it: its exact registration
    (the U2F register response, or the passkey's attestation object), which holds its public key."""
    return digest(registration)


def register_challenge(nonce: str) -> bytes:
    """The challenge a new owner key is made with (U2F register, WebAuthn create)."""
    return challenge_param("register", nonce, "")


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
        self.keys: list[dict[str, Any]] = self._read_owner()
        self.nonces: dict[str, tuple[str, float]] = {}
        self.sessions: dict[str, tuple[uuid.UUID, float]] = {}  # sha256(session) -> (device, expiry)

    # owner keys (security key, phone passkeys)

    def _read_owner(self) -> list[dict[str, Any]]:
        try:
            data = json.loads(self.owner_file.read_text())
        except (OSError, ValueError):
            return []
        if isinstance(data.get("keys"), list):
            return [k for k in data["keys"] if isinstance(k, dict) and k.get("kind") in KINDS and k.get("public_key")]
        if data.get("public_key") and data.get("key_handle"):  # before passkeys: one security key
            return [{"id": "key-1", "kind": "u2f", "name": "Security key", "public_key": data["public_key"],
                     "key_handle": data["key_handle"], "counter": data.get("counter", 0), "created": data.get("created")}]
        return []

    def _write_owner(self, keys: list[dict[str, Any]]) -> None:
        tmp = self.owner_file.with_name("owner.json.tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"version": 2, "keys": keys}, f)
        os.replace(tmp, self.owner_file)
        self.keys = keys

    @property
    def paired(self) -> bool:
        return bool(self.keys)

    @property
    def owner(self) -> dict[str, Any] | None:
        """The security key, if one is registered."""
        return next((k for k in self.keys if k["kind"] == "u2f"), None)

    @property
    def passkeys_on(self) -> bool:
        return bool(self.settings.passkey_rp_id and self.settings.passkey_certs)

    def _origins(self) -> tuple[str, ...]:
        return tuple(passkeys.apk_origin(c) for c in self.settings.passkey_certs)

    def public_keys(self) -> list[dict[str, Any]]:
        """The owner keys as the app sees them: what to ask the key or the passkey for, no secrets (there are none)."""
        out = []
        for k in self.keys:
            item = {"id": k["id"], "kind": k["kind"], "name": k.get("name") or k["kind"], "created": k.get("created")}
            if k["kind"] == "u2f":
                item["key_handle"] = k["key_handle"]
            else:
                item["credential_id"] = k["credential_id"]
            out.append(item)
        return out

    def _rotate_token(self) -> None:
        """A new token that only this machine has: the one the phone was set up with stops working."""
        token = new_token()
        tmp = self.settings.token_file.with_name(self.settings.token_file.name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(token + "\n")
        os.replace(tmp, self.settings.token_file)
        self.token = token

    def _verify(self, key: dict[str, Any], challenge: bytes, proof: dict[str, Any], fresh: bool) -> tuple[int, int]:
        """One owner key's signature over challenge. fresh: just made, so the passkey's app origin is checked too."""
        if key["kind"] == "u2f":
            return verify_assertion(unb64(key["public_key"]), unb64(proof.get("assertion")), challenge)
        p = proof.get("passkey")
        if not isinstance(p, dict) or not self.settings.passkey_rp_id:
            raise SecurityError("passkeys are not set up on this server")
        try:
            return passkeys.verify_assertion(
                unb64(key["public_key"]), unb64(p.get("client_data")), unb64(p.get("authenticator_data")),
                unb64(p.get("signature")), challenge, key.get("rp_id") or self.settings.passkey_rp_id,
                self._origins() if fresh else None)
        except passkeys.PasskeyError as e:
            raise SecurityError(str(e)) from None

    def _key_for(self, proof: dict[str, Any], keys: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        """Which owner key made this signature: a passkey by its credential id, else the security key."""
        keys = self.keys if keys is None else keys
        p = proof.get("passkey")
        if isinstance(p, dict):
            cred = str(p.get("credential_id") or "")
            key = next((k for k in keys if k["kind"] == "passkey" and k["credential_id"] == cred), None)
            if key is None:
                raise SecurityError("that passkey is not one of Krewd's owner keys")
            return key
        key = next((k for k in keys if k["kind"] == "u2f" and (not proof.get("key") or k["id"] == proof["key"])), None)
        if key is None:
            raise SecurityError("no security key registered" if self.keys else "no owner key registered yet")
        return key

    def _owner_check(self, purpose: str, nonce: str, payload: str, proof: dict[str, Any], advance: bool,
                     keys: list[dict[str, Any]] | None = None) -> dict[str, Any]:
        """Verify an owner key's signature; with [advance] it is fresh: higher counter (saved), passkey from this app."""
        key = self._key_for(proof, keys)
        flags, counter = self._verify(key, challenge_param(purpose, nonce, payload), proof, fresh=advance)
        if advance:
            last = int(key.get("counter", 0))
            if counter <= last and not (counter == 0 and last == 0):
                raise SecurityError("the key's counter went back: is it a copy?")
            self._write_owner([{**k, "counter": counter} if k["id"] == key["id"] else k for k in self.keys])
        return {"key": key["id"], "flags": flags, "counter": counter}

    @staticmethod
    def _proof_fields(body: dict[str, Any]) -> dict[str, Any]:
        """The owner key's signature from a request: a security key assertion or a passkey's answer."""
        if isinstance(body.get("passkey"), dict):
            p = body["passkey"]
            return {"passkey": {f: str(p.get(f) or "") for f in ("credential_id", "client_data", "authenticator_data", "signature")}}
        return {"assertion": str(body.get("assertion") or "")}

    def _new_key(self, body: dict[str, Any], nonce: str, name: str) -> dict[str, Any]:
        """A new owner key from its registration: U2F register response or a WebAuthn create answer."""
        kind = str(body.get("kind") or ("passkey" if body.get("passkey_registration") else "u2f"))
        key: dict[str, Any] = {"id": "key-" + secrets.token_hex(4), "kind": kind, "created": time.time()}
        if kind == "u2f":
            if self.owner is not None:
                raise SecurityError("a security key is already registered: remove it first", 409)
            public, handle = parse_registration(unb64(body.get("registration")))
            key.update(name=name or "Security key", public_key=b64(public), key_handle=b64(handle), counter=0)
        elif kind == "passkey":
            if not self.passkeys_on:
                raise SecurityError("passkeys are not set up on this server (KREWD_PASSKEY_RP_ID, KREWD_PASSKEY_CERTS)", 409)
            reg = body.get("passkey_registration")
            if not isinstance(reg, dict):
                raise SecurityError("missing passkey", 422)
            try:
                r = passkeys.parse_registration(unb64(reg.get("client_data")), unb64(reg.get("attestation_object")),
                                                register_challenge(nonce), self.settings.passkey_rp_id or "", self._origins())
            except passkeys.PasskeyError as e:
                raise SecurityError(str(e)) from None
            cred = passkeys.b64url(r.credential_id)
            if any(k.get("credential_id") == cred for k in self.keys):
                raise SecurityError("that passkey is already registered", 409)
            key.update(name=name or "Phone passkey", public_key=b64(r.public_key), credential_id=cred,
                       rp_id=self.settings.passkey_rp_id, counter=r.counter)
        else:
            raise SecurityError("unknown key kind", 422)
        return key

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
        answer: dict[str, Any] = {"nonce": value, "expires_in": NONCE_SECONDS, "paired": self.paired, "app_param": b64(APP_PARAM),
                                  "passkey_rp_id": self.settings.passkey_rp_id if self.passkeys_on else None}
        if purpose in ("pair", "revoke", "add-key", "remove-key"):
            answer["keys"] = self.public_keys()
            if self.owner:
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
        """Add a phone. The first comes with the setup token and registers the first owner key in the same step."""
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
        proof_in = self._proof_fields(body)
        first = not self.paired
        if first:
            if principal is None or principal.kind not in ("setup", "local"):
                raise SecurityError("the first phone needs the setup token", 401)
            if body.get("passkey_registration"):
                key = self._new_key({**body, "kind": "passkey"}, nonce, str(body.get("key_name") or "")[:60])
            else:
                key = self._new_key({**body, "kind": "u2f"}, nonce, str(body.get("key_name") or "")[:60])
            # Checked against the new key before anything is saved; then the key's counter starts from this tap.
            proof = self._owner_check("pair", nonce, payload, proof_in, advance=False, keys=[key])
            key["counter"] = proof["counter"]
            self._write_owner([key])
        else:
            proof = self._owner_check("pair", nonce, payload, proof_in, advance=True)
        proof.update({"nonce": nonce, "pin_seen": pin_seen, **proof_in})
        await self.pool.execute(
            """insert into devices (id, name, device_key, presence_key, proof, last_seen) values ($1, $2, $3, $4, $5, now())
               on conflict (id) do update set name = $2, device_key = $3, presence_key = $4, proof = $5,
               last_seen = now(), revoked_at = null""",
            device_id, name, device_key, presence_key, proof,
        )
        if first:
            self._rotate_token()
        return {"device_id": str(device_id), **self._new_session(device_id)}

    def _device_key(self, row: asyncpg.Record, keys: list[dict[str, Any]] | None = None) -> str:
        """The owner key whose signature over this phone's keys still checks out (raises if none)."""
        proof = row["proof"]
        payload = pair_payload(str(row["id"]), bytes(row["device_key"]), bytes(row["presence_key"]), proof.get("pin_seen", ""))
        return self._owner_check("pair", proof.get("nonce", ""), payload, proof, advance=False, keys=keys)["key"]

    async def _device(self, device_id: uuid.UUID) -> asyncpg.Record:
        row = await self.pool.fetchrow("select * from devices where id = $1 and revoked_at is null", device_id)
        if row is None:
            raise SecurityError("this phone is not (or no longer) allowed", 401)
        # The owner key's signature over this phone's keys, checked every time: a row added or changed straight
        # in the database (without the key) is refused.
        self._device_key(row)
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
            raise SecurityError("this needs a phone set up with an owner key")
        nonce, _, sig = (header or "").partition(".")
        self._use_nonce(nonce, "presence")
        row = await self._device(principal.device_id)
        if not verify_p256(bytes(row["presence_key"]), unb64(sig), presence_message(nonce, method, path, body)):
            raise SecurityError("the fingerprint check did not match this request")

    # managing phones

    async def devices(self, current: uuid.UUID | None) -> list[dict[str, Any]]:
        rows = await self.pool.fetch("select * from devices where revoked_at is null order by created_at")
        out = []
        for r in rows:
            try:
                key = self._device_key(r)
            except SecurityError:
                key = None
            out.append({"id": str(r["id"]), "name": r["name"], "created_at": r["created_at"].isoformat(),
                        "last_seen": r["last_seen"].isoformat() if r["last_seen"] else None, "current": r["id"] == current,
                        "key": key})
        return out

    async def revoke(self, device_id: uuid.UUID, body: dict[str, Any] | None, principal: Principal) -> None:
        """Cut a phone off: with an owner key from a phone, or by the `krewd` command on this machine."""
        if principal.kind != "local":
            nonce = str((body or {}).get("nonce") or "")
            self._use_nonce(nonce, "revoke")
            self._owner_check("revoke", nonce, str(device_id), self._proof_fields(body or {}), advance=True)
        await self._revoke_rows([device_id])

    async def _revoke_rows(self, ids: list[uuid.UUID]) -> None:
        if not ids:
            return
        await self.pool.execute("update devices set revoked_at = now() where id = any($1::uuid[]) and revoked_at is null", ids)
        for key in [k for k, (d, _) in self.sessions.items() if d in ids]:
            del self.sessions[key]

    # managing owner keys

    async def add_key(self, body: dict[str, Any], principal: Principal) -> dict[str, Any]:
        """A new owner key (a phone passkey, or a security key when there is none), signed for by one already registered."""
        if not self.paired:
            raise SecurityError("set Krewd up first", 409)
        nonce = str(body.get("nonce") or "")
        self._use_nonce(nonce, "add-key")
        key = self._new_key(body, nonce, str(body.get("name") or "")[:60])
        if principal.kind != "local":
            reg = body.get("passkey_registration") if key["kind"] == "passkey" else None
            blob = unb64(reg.get("attestation_object")) if isinstance(reg, dict) else unb64(body.get("registration"))
            self._owner_check("add-key", nonce, key_digest(blob), self._proof_fields(body), advance=True)
        self._write_owner([*self.keys, key])
        return next(k for k in self.public_keys() if k["id"] == key["id"])

    async def remove_key(self, key_id: str, body: dict[str, Any] | None, principal: Principal) -> dict[str, Any]:
        """Remove an owner key, signed for by any owner key (it may be the same one). The last one stays.
        Phones added with it lose their proof and are removed too."""
        key = next((k for k in self.keys if k["id"] == key_id), None)
        if key is None:
            raise SecurityError("no such key", 404)
        if len(self.keys) == 1:
            raise SecurityError("that is the only owner key: add another first (or krewd security reset)", 409)
        if principal.kind != "local":
            nonce = str((body or {}).get("nonce") or "")
            self._use_nonce(nonce, "remove-key")
            self._owner_check("remove-key", nonce, key_id, self._proof_fields(body or {}), advance=True)
        rest = [k for k in self.keys if k["id"] != key_id]
        lost = []
        for row in await self.pool.fetch("select * from devices where revoked_at is null"):
            try:
                self._device_key(row, keys=rest)
            except SecurityError:
                lost.append(row["id"])
        self._write_owner(rest)
        await self._revoke_rows(lost)
        return {"removed": key_id, "phones_removed": [str(i) for i in lost]}

    async def reset(self) -> None:
        """Back to setup (lost phone and keys): no owner keys, no phones, a new setup token."""
        await self.pool.execute("update devices set revoked_at = now() where revoked_at is null")
        self.sessions.clear()
        self.nonces.clear()
        if self.owner_file.exists():
            self.owner_file.rename(self.owner_file.with_name(f"owner.json.reset-{int(time.time())}"))
        self.keys = []
        self._rotate_token()

    def assetlinks(self) -> list[dict[str, Any]]:
        """The Digital Asset Links statement the passkey domain must serve at /.well-known/assetlinks.json."""
        return [{"relation": ["delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"],
                 "target": {"namespace": "android_app", "package_name": "app.krewd",
                            "sha256_cert_fingerprints": [passkeys.cert_fingerprint(c) for c in self.settings.passkey_certs]}}]
