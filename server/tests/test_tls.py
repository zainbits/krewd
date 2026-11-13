"""Crew's own certificate, its pin, HTTPS beside plain loopback, and the connect link."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import socket
import ssl
from dataclasses import replace
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import uvicorn
from cryptography import x509
from cryptography.hazmat.primitives import serialization

from crew_server import tls
from crew_server.__main__ import _Server, connect_link, https_addresses, tls_marked
from crew_server.config import Settings


def settings(tmp_path: Path, **changes) -> Settings:
    base = Settings(
        database_url="postgresql://unused", host="127.0.0.1,192.0.2.7", port=8800, workdir=tmp_path,
        token_file=tmp_path / "token", state_dir=tmp_path, engines={}, default_engine="x",
        tls_cert=tmp_path / "tls.crt", tls_key=tmp_path / "tls.key",
    )
    return replace(base, **changes)


def test_certificate_is_made_once_private_and_pinned(tmp_path):
    cert, key = tmp_path / "c" / "tls.crt", tmp_path / "c" / "tls.key"
    tls.ensure_certificate(cert, key, ["192.0.2.7"])
    assert key.stat().st_mode & 0o777 == 0o600 and cert.stat().st_mode & 0o777 == 0o600
    first = tls.pin(cert)
    tls.ensure_certificate(cert, key, ["192.0.2.8"])  # exists: kept, so the app's pin stays valid
    assert tls.pin(cert) == first and first.startswith("sha256/")
    parsed = x509.load_pem_x509_certificate(cert.read_bytes())
    spki = parsed.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    assert first == "sha256/" + base64.b64encode(hashlib.sha256(spki).digest()).decode()
    ips = [str(i) for i in parsed.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.IPAddress)]
    assert "192.0.2.7" in ips and "127.0.0.1" in ips


def test_https_goes_on_every_address_but_loopback(tmp_path):
    s = settings(tmp_path)
    assert https_addresses(s, ["127.0.0.1", "192.0.2.7"]) == ["192.0.2.7"]
    assert https_addresses(s, ["127.0.0.1", "::1"]) == []
    assert https_addresses(s, ["0.0.0.0"]) == ["0.0.0.0"]
    assert https_addresses(replace(s, tls="off"), ["127.0.0.1", "192.0.2.7"]) == []


def test_connect_link_has_the_https_addresses_extra_urls_token_and_pin(tmp_path):
    s = settings(tmp_path, urls=("https://crew.example.org",))
    link = urlparse(connect_link(s, "abcd-efgh"))
    assert link.scheme == "crew" and link.netloc == "connect"
    q = parse_qs(link.query)
    assert q["a"] == ["https://192.0.2.7:8800", "https://crew.example.org"]
    assert q["t"] == ["abcd-efgh"] and q["p"] == [tls.pin(s.tls_cert)]
    off = parse_qs(urlparse(connect_link(replace(s, tls="off"), "t")).query)
    assert off["a"][0] == "http://192.0.2.7:8800" and "p" not in off


async def _app(scope, receive, send):
    assert scope["type"] == "http"
    body = b"tls" if scope.get("crew_tls") else b"plain"
    await send({"type": "http.response.start", "status": 200, "headers": [(b"content-length", str(len(body)).encode())]})
    await send({"type": "http.response.body", "body": body})


def _listen() -> socket.socket:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    sock.set_inheritable(True)
    return sock


def _get(port: int, context: ssl.SSLContext | None) -> bytes:
    with socket.create_connection(("127.0.0.1", port), timeout=5) as raw:
        conn = context.wrap_socket(raw, server_hostname="192.0.2.7") if context else raw
        with conn:
            conn.sendall(b"GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
            data = b""
            while chunk := conn.recv(4096):
                data += chunk
            if context:
                der = conn.getpeercert(binary_form=True)
                cert = x509.load_der_x509_certificate(der)
                spki = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
                data += b"\npin=" + base64.b64encode(hashlib.sha256(spki).digest())
            return data


async def test_https_and_plain_servers_run_side_by_side(tmp_path):
    cert, key = tmp_path / "tls.crt", tmp_path / "tls.key"
    tls.ensure_certificate(cert, key, ["127.0.0.1"])
    plain_sock, tls_sock = _listen(), _listen()
    plain = _Server(uvicorn.Config(_app, lifespan="off", log_level="warning"))
    secure = _Server(uvicorn.Config(tls_marked(_app), lifespan="off", log_level="warning",
                                    ssl_certfile=str(cert), ssl_keyfile=str(key)))
    tasks = [asyncio.create_task(plain.serve(sockets=[plain_sock])), asyncio.create_task(secure.serve(sockets=[tls_sock]))]
    try:
        while not (plain.started and secure.started):
            await asyncio.sleep(0.02)
        loop = asyncio.get_running_loop()
        assert (await loop.run_in_executor(None, _get, plain_sock.getsockname()[1], None)).endswith(b"plain")
        # The app does what this context does: no CA check, then it compares the key's pin.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        answer = await loop.run_in_executor(None, _get, tls_sock.getsockname()[1], context)
        body, _, seen = answer.rpartition(b"\npin=")
        assert body.endswith(b"tls") and "sha256/" + seen.decode() == tls.pin(cert)
    finally:
        plain.should_exit = secure.should_exit = True
        await asyncio.gather(*tasks)
