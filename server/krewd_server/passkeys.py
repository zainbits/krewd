"""Phone passkeys (WebAuthn, made with Android's Credential Manager) as owner keys, next to the FIDO security key.

Only what Krewd needs: ES256 (P-256) credentials, attestation ignored ("none"), the app's origin
(`android:apk-key-hash:<base64url SHA-256 of the signing certificate>`) and the relying party id from the config.
The challenge is the same 32-byte value the security key signs (security.challenge_param), so both kinds of key
sign exactly the same thing.
"""

from __future__ import annotations

import base64
import hashlib
import json
import struct
from dataclasses import dataclass
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

FLAG_UP = 0x01  # user present
FLAG_UV = 0x04  # user verified (fingerprint or screen lock)
FLAG_AT = 0x40  # attested credential data included


class PasskeyError(ValueError):
    pass


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64url(text: str) -> bytes:
    try:
        return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))
    except (ValueError, TypeError):
        raise PasskeyError("bad base64url") from None


def apk_origin(cert_sha256_hex: str) -> str:
    """The origin Android puts in clientDataJSON for an app signed with this certificate."""
    return "android:apk-key-hash:" + b64url(bytes.fromhex(cert_sha256_hex.replace(":", "")))


def cert_fingerprint(cert_sha256_hex: str) -> str:
    """The same certificate hash as assetlinks.json writes it: AA:BB:..."""
    raw = bytes.fromhex(cert_sha256_hex.replace(":", ""))
    return ":".join(f"{b:02X}" for b in raw)


# CBOR, just enough for attestation objects and COSE keys

def _cbor(data: bytes, i: int) -> tuple[Any, int]:
    if i >= len(data):
        raise PasskeyError("CBOR cut off")
    major, info = data[i] >> 5, data[i] & 0x1F
    i += 1
    if info < 24:
        n = info
    elif info in (24, 25, 26, 27):
        size = 1 << (info - 24)
        if i + size > len(data):
            raise PasskeyError("CBOR cut off")
        n = int.from_bytes(data[i:i + size], "big")
        i += size
    else:
        raise PasskeyError("CBOR form not supported")
    if major == 0:
        return n, i
    if major == 1:
        return -1 - n, i
    if major in (2, 3):
        if i + n > len(data):
            raise PasskeyError("CBOR cut off")
        raw = data[i:i + n]
        return (raw if major == 2 else raw.decode()), i + n
    if major == 4:
        items = []
        for _ in range(n):
            item, i = _cbor(data, i)
            items.append(item)
        return items, i
    if major == 5:
        out = {}
        for _ in range(n):
            k, i = _cbor(data, i)
            v, i = _cbor(data, i)
            out[k] = v
        return out, i
    if major == 7 and info in (20, 21, 22):
        return {20: False, 21: True, 22: None}[info], i
    raise PasskeyError("CBOR type not supported")


def cbor_first(data: bytes) -> tuple[Any, int]:
    """The first CBOR item in data and where it ends."""
    return _cbor(data, 0)


def cose_p256(key: dict) -> bytes:
    """A COSE EC2 P-256 ES256 key as an uncompressed point (65 bytes)."""
    if key.get(1) != 2 or key.get(3) != -7 or key.get(-1) != 1:
        raise PasskeyError("only P-256 (ES256) passkeys are supported")
    x, y = key.get(-2), key.get(-3)
    if not (isinstance(x, bytes) and isinstance(y, bytes) and len(x) == 32 and len(y) == 32):
        raise PasskeyError("bad passkey public key")
    return b"\x04" + x + y


@dataclass
class Registration:
    credential_id: bytes
    public_key: bytes  # uncompressed P-256 point
    counter: int


def _client_data(raw: bytes, kind: str, challenge: bytes, origins: tuple[str, ...] | None) -> None:
    try:
        cd = json.loads(raw)
    except ValueError:
        raise PasskeyError("bad clientDataJSON") from None
    if cd.get("type") != kind:
        raise PasskeyError("wrong WebAuthn type")
    if cd.get("challenge") != b64url(challenge):
        raise PasskeyError("the passkey signed a different request")
    if origins is not None and cd.get("origin") not in origins:
        raise PasskeyError("the passkey was used by another app")


def _auth_data(data: bytes, rp_id: str) -> tuple[int, int]:
    if len(data) < 37:
        raise PasskeyError("authenticator data cut off")
    if data[:32] != hashlib.sha256(rp_id.encode()).digest():
        raise PasskeyError("the passkey belongs to another site")
    flags, counter = data[32], struct.unpack(">I", data[33:37])[0]
    if not flags & FLAG_UP or not flags & FLAG_UV:
        raise PasskeyError("the passkey was used without your fingerprint or screen lock")
    return flags, counter


def parse_registration(client_data: bytes, attestation_object: bytes, challenge: bytes, rp_id: str,
                       origins: tuple[str, ...]) -> Registration:
    """A new passkey (navigator.credentials.create / CreatePublicKeyCredentialRequest). Attestation is not checked."""
    _client_data(client_data, "webauthn.create", challenge, origins)
    att, _ = cbor_first(attestation_object)
    if not isinstance(att, dict) or not isinstance(att.get("authData"), bytes):
        raise PasskeyError("bad attestation object")
    data = att["authData"]
    flags, counter = _auth_data(data, rp_id)
    if not flags & FLAG_AT or len(data) < 55:
        raise PasskeyError("no credential in the passkey's answer")
    length = int.from_bytes(data[53:55], "big")
    credential_id = data[55:55 + length]
    if len(credential_id) != length or length == 0:
        raise PasskeyError("credential id cut off")
    key, _ = cbor_first(data[55 + length:])
    if not isinstance(key, dict):
        raise PasskeyError("bad passkey public key")
    return Registration(credential_id, cose_p256(key), counter)


def verify_assertion(public_key: bytes, client_data: bytes, auth_data: bytes, signature: bytes, challenge: bytes,
                     rp_id: str, origins: tuple[str, ...] | None) -> tuple[int, int]:
    """A passkey signature (GetCredentialRequest). origins None: a stored proof, checked again later. -> (flags, counter)"""
    _client_data(client_data, "webauthn.get", challenge, origins)
    flags, counter = _auth_data(auth_data, rp_id)
    try:
        key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), public_key)
        key.verify(signature, auth_data + hashlib.sha256(client_data).digest(), ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        raise PasskeyError("the passkey's signature does not match") from None
    return flags, counter
