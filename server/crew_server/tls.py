"""Crew's own TLS certificate: HTTPS on every non-loopback address, verified by the app's pin.

The server makes a self-signed certificate on first start. The app does not trust it through a certificate
authority: it pins the certificate's public key (the SPKI SHA-256 "pin"), learned over a channel it already
trusts (the connect link or QR code, or a CA-verified address such as Tailscale). Any other certificate is
refused, so someone on the same network cannot read or change the traffic.

A certificate from a real authority (CREW_TLS_CERT and CREW_TLS_KEY) works too; the app then trusts it the
normal way and the pin is only a hint.
"""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import ipaddress
import os
import socket
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

VALID_DAYS = 20 * 365  # the app checks the pin, not the dates; renewing would change nothing but add risk


def is_loopback(host: str) -> bool:
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def ensure_certificate(cert_path: Path, key_path: Path, addresses: list[str]) -> None:
    """Make a self-signed certificate and key (mode 600) unless both exist already."""
    if cert_path.is_file() and key_path.is_file():
        return
    key = ec.generate_private_key(ec.SECP256R1())
    host = socket.gethostname()
    names: list[x509.GeneralName] = [x509.DNSName("localhost")]
    for name in dict.fromkeys([host, f"{host}.local"]):
        if name and name != "localhost":
            names.append(x509.DNSName(name))
    for address in dict.fromkeys(addresses + ["127.0.0.1"]):
        try:
            names.append(x509.IPAddress(ipaddress.ip_address(address)))
        except ValueError:
            pass
    now = dt.datetime.now(dt.timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Crew")])
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=VALID_DAYS))
        .add_extension(x509.SubjectAlternativeName(names), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    key_path.parent.mkdir(parents=True, exist_ok=True)
    cert_path.parent.mkdir(parents=True, exist_ok=True)
    _write_private(key_path, key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ))
    _write_private(cert_path, cert.public_bytes(serialization.Encoding.PEM))


def _write_private(path: Path, data: bytes) -> None:
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def pin(cert_path: Path) -> str:
    """The certificate's public-key pin: "sha256/" + base64 of the SHA-256 of its SubjectPublicKeyInfo."""
    cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
    spki = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return "sha256/" + base64.b64encode(hashlib.sha256(spki).digest()).decode()
