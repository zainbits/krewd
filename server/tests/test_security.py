"""The owner's security key, phones, sessions, fingerprint checks, app settings and sign-in requests."""

from __future__ import annotations

import base64
import dataclasses
import json
import os
import sys
import uuid
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import krewd_server.api as api_module
import krewd_server.browsers as browsers_module
from krewd_server import db, passkeys, tls
from krewd_server.__main__ import tls_marked
from krewd_server.api import create_app
from krewd_server.config import Engine, Settings, load_env_file
from krewd_server.hub import Hub
from krewd_server.security import (APP_PARAM, challenge_param, device_message, key_digest, pair_payload, presence_message,
                                   register_challenge)

load_env_file()
DATABASE_URL = os.environ.get("KREWD_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="KREWD_TEST_DATABASE_URL is not set")
FAKE = Path(__file__).with_name("fake_agent.py")
SETUP = "setup-token"


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


class FakeKey:
    """A U2F security key: what the phone relays to the server after a tap."""

    def __init__(self) -> None:
        self.private = ec.generate_private_key(ec.SECP256R1())
        self.public = self.private.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        self.handle = os.urandom(48)
        self.counter = 0

    def registration(self) -> bytes:
        return b"\x05" + self.public + bytes([len(self.handle)]) + self.handle + b"<attestation cert and signature>"

    def sign(self, challenge: bytes) -> bytes:
        self.counter += 1
        head = b"\x01" + self.counter.to_bytes(4, "big")
        return head + self.private.sign(APP_PARAM + head + challenge, ec.ECDSA(hashes.SHA256()))


class FakePhone:
    def __init__(self) -> None:
        self.id = str(uuid.uuid4())
        self.device = ec.generate_private_key(ec.SECP256R1())
        self.presence = ec.generate_private_key(ec.SECP256R1())

    @staticmethod
    def spki(key) -> bytes:
        return key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)

    def sign(self, key, message: bytes) -> str:
        return b64(key.sign(message, ec.ECDSA(hashes.SHA256())))


class FakeBrowser:
    def __init__(self, port: int) -> None:
        self.port = port
        self.url = "https://login.example.com/signin"
        self.filled: list = []

    async def tabs(self):
        return [{"id": "tab1", "url": self.url, "title": "Sign in"}]

    async def page_url(self, target_id: str) -> str:
        return self.url

    async def fill_secret(self, target_id, fields, submit):
        self.filled.append((fields, submit))
        self.url = "https://login.example.com/home"

    async def close(self):
        pass

    async def watch(self, on_activity, on_connect=None, on_closed=None):
        pass


@pytest.fixture
async def hub(tmp_path, monkeypatch):
    monkeypatch.setattr(browsers_module, "Browser", FakeBrowser)
    monkeypatch.setattr(api_module.asyncio, "sleep", _no_sleep)
    pool = await db.connect(DATABASE_URL)
    await pool.execute(
        "drop table if exists alerts, needs_items, push_tokens, browser_tabs, proposals, session_agents, routine_triggers, routine_runs, routine_versions, routines, messages, events,"
        " approvals, sessions, engines, devices, app_prefs, schema_version cascade"
    )
    cert, key = tmp_path / "tls.crt", tmp_path / "tls.key"
    tls.ensure_certificate(cert, key, ["127.0.0.1"])
    (tmp_path / "token").write_text(SETUP + "\n")
    settings = Settings(
        database_url=DATABASE_URL, host="127.0.0.1", port=0, workdir=tmp_path, token_file=tmp_path / "token",
        state_dir=tmp_path / "state", engines={"fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)])},
        default_engine="fake", data_dir=tmp_path / "data", max_upload_mb=1, tls_cert=cert, tls_key=key,
    )
    hub = Hub(settings, pool)
    await hub.start()
    yield hub
    await hub.close()
    await pool.close()


_real_sleep = api_module.asyncio.sleep


async def _no_sleep(seconds):
    await _real_sleep(0)


@pytest.fixture
def app(hub):
    return create_app(hub, SETUP, lifespan_hub=False)


def lan(app, auth: str | None = None) -> httpx.AsyncClient:
    """The phone over Krewd's own HTTPS on the home network."""
    headers = {"Authorization": f"Bearer {auth}"} if auth else {}
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=tls_marked(app), client=("192.168.0.20", 5000)),
                             base_url="https://krewd", headers=headers)


def local(app, auth: str) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 5000)), base_url="http://krewd",
                             headers={"Authorization": f"Bearer {auth}"})


async def nonce(c: httpx.AsyncClient, purpose: str) -> dict:
    r = await c.post("/api/security/nonce", json={"purpose": purpose})
    assert r.status_code == 200, r.text
    return r.json()


async def pair(c, phone: FakePhone, key: FakeKey, pin: str, first: bool, name: str = "Phone") -> httpx.Response:
    n = (await nonce(c, "pair"))["nonce"]
    payload = pair_payload(phone.id, phone.spki(phone.device), phone.spki(phone.presence), pin)
    body = {
        "nonce": n, "device_id": phone.id, "name": name, "pin_seen": pin,
        "device_key": b64(phone.spki(phone.device)), "presence_key": b64(phone.spki(phone.presence)),
        "device_sig": phone.sign(phone.device, device_message("pair", n, pin)),
        "assertion": b64(key.sign(challenge_param("pair", n, payload))),
    }
    if first:
        body["registration"] = b64(key.registration())
    return await c.post("/api/security/pair", json=body)


async def session(c, phone: FakePhone, pin: str) -> httpx.Response:
    n = (await nonce(c, "session"))["nonce"]
    return await c.post("/api/security/session", json={
        "device_id": phone.id, "nonce": n, "pin_seen": pin, "sig": phone.sign(phone.device, device_message("session", n, pin)),
    })


async def presence(c, phone: FakePhone, method: str, path: str, body: bytes) -> str:
    n = (await nonce(c, "presence"))["nonce"]
    return n + "." + phone.sign(phone.presence, presence_message(n, method, path, body))


async def test_first_phone_pairs_with_the_setup_token_which_then_stays_on_the_machine(hub, app):
    pin = tls.pin(hub.settings.tls_cert)
    key, phone = FakeKey(), FakePhone()
    async with lan(app) as anon:
        assert (await pair(anon, phone, key, pin, first=True)).status_code == 401  # no setup token
    async with lan(app, SETUP) as c:
        assert (await c.get("/api/state")).status_code == 200  # setup: the token works anywhere
        wrong = await pair(c, phone, key, "sha256/someone-in-between=", first=True)
        assert wrong.status_code == 403 and "certificate" in wrong.json()["detail"]
        made = await pair(c, phone, key, pin, first=True)
        assert made.status_code == 201, made.text
        assert (await c.get("/api/state")).status_code == 401  # the setup token is gone
    owner = hub.settings.token_file.parent / "owner.json"
    assert owner.stat().st_mode & 0o777 == 0o600 and json.loads(owner.read_text())["keys"][0]["counter"] == key.counter
    new_token = hub.settings.token_file.read_text().strip()
    assert new_token != SETUP
    async with local(app, new_token) as c:  # the krewd command on the machine itself
        assert (await c.get("/api/state")).status_code == 200
    async with lan(app, new_token) as c:  # but never from the network
        assert (await c.get("/api/state")).status_code == 401
    async with lan(app, made.json()["session"]) as c:
        assert (await c.get("/api/state")).status_code == 200
        state = (await c.get("/api/security")).json()
        assert state["paired"] and state["devices"][0]["current"] and state["you"] == "device"
    async with lan(app) as c:
        again = await session(c, phone, pin)
        assert again.status_code == 200
        assert (await session(c, phone, "sha256/other=")).status_code == 403
    async with lan(app, again.json()["session"]) as c:
        assert (await c.get("/api/state")).status_code == 200
    # The connect code no longer carries the token.
    from krewd_server.__main__ import connect_link
    assert "&t=" not in connect_link(hub.settings, new_token)


async def test_new_phones_need_the_security_key_and_forged_rows_are_refused(hub, app):
    pin = tls.pin(hub.settings.tls_cert)
    key, first, second = FakeKey(), FakePhone(), FakePhone()
    async with lan(app, SETUP) as c:
        assert (await pair(c, first, key, pin, first=True)).status_code == 201
    async with lan(app) as c:
        assert (await pair(c, second, FakeKey(), pin, first=False)).status_code == 403  # another key
        assert (await pair(c, second, key, pin, first=False, name="New phone")).status_code == 201
        assert (await session(c, second, pin)).status_code == 200
        # A copied key whose counter goes back.
        key.counter = 0
        assert "counter" in (await pair(c, FakePhone(), key, pin, first=False)).json()["detail"]
        # A phone written straight into the database, without the key's signature.
        forged = FakePhone()
        await hub.pool.execute(
            "insert into devices (id, name, device_key, presence_key, proof) values ($1, 'x', $2, $3, $4)",
            uuid.UUID(forged.id), forged.spki(forged.device), forged.spki(forged.presence),
            {"nonce": "n", "pin_seen": pin, "assertion": b64(b"\x01\x00\x00\x00\x09" + b"0" * 70)},
        )
        assert (await session(c, forged, pin)).status_code == 403


async def test_keys_and_phone_removal_need_the_fingerprint_or_the_key(hub, app):
    pin = tls.pin(hub.settings.tls_cert)
    key, phone, other = FakeKey(), FakePhone(), FakePhone()
    async with lan(app, SETUP) as c:
        sess = (await pair(c, phone, key, pin, first=True)).json()["session"]
    async with lan(app) as c:
        assert (await pair(c, other, key, pin, first=False)).status_code == 201
    async with lan(app, sess) as c:
        body = json.dumps({"name": "GW", "key": "secret-key-123"}).encode()
        headers = {"Content-Type": "application/json"}
        assert (await c.post("/api/profiles", content=body, headers=headers)).status_code == 403
        bad = await presence(c, phone, "POST", "/api/profiles", b"another body")
        assert (await c.post("/api/profiles", content=body, headers={**headers, "X-Krewd-Presence": bad})).status_code == 403
        good = await presence(c, phone, "POST", "/api/profiles", body)
        # Past the fingerprint check (what follows is the profile's own answer: not managed here, or incomplete).
        assert (await c.post("/api/profiles", content=body, headers={**headers, "X-Krewd-Presence": good})).status_code not in (401, 403)
        n = (await nonce(c, "revoke"))["nonce"]
        assert (await c.post(f"/api/security/devices/{other.id}/revoke", json={"nonce": n, "assertion": b64(FakeKey().sign(challenge_param("revoke", n, other.id)))})).status_code == 403
        n = (await nonce(c, "revoke"))["nonce"]
        done = await c.post(f"/api/security/devices/{other.id}/revoke", json={"nonce": n, "assertion": b64(key.sign(challenge_param("revoke", n, other.id)))})
        assert done.status_code == 200
    async with lan(app) as c:
        assert (await session(c, other, pin)).status_code == 401


async def test_reset_from_the_machine_goes_back_to_setup(hub, app):
    pin = tls.pin(hub.settings.tls_cert)
    phone = FakePhone()
    async with lan(app, SETUP) as c:
        assert (await pair(c, phone, FakeKey(), pin, first=True)).status_code == 201
    token = hub.settings.token_file.read_text().strip()
    async with lan(app, token) as c:
        assert (await c.post("/api/security/reset")).status_code == 401
    async with local(app, token) as c:
        assert (await c.post("/api/security/reset")).status_code == 200
    fresh = hub.settings.token_file.read_text().strip()
    assert fresh != token
    async with lan(app) as c:
        assert (await session(c, phone, pin)).status_code == 401
    async with lan(app, fresh) as c:
        assert (await c.get("/api/security")).json()["paired"] is False
        assert (await pair(c, FakePhone(), FakeKey(), pin, first=True)).status_code == 201


async def test_app_settings_are_kept_on_the_server(hub, app):
    async with local(app, SETUP) as c:
        assert (await c.put("/api/prefs/engine.claude", json={"value": {"config": {"model": "x"}}})).status_code == 200
        assert (await c.put("/api/prefs/browser_high_quality", json={"value": True})).status_code == 200
        assert (await c.get("/api/prefs")).json() == {"engine.claude": {"config": {"model": "x"}}, "browser_high_quality": True}
        assert (await c.put("/api/prefs/browser_high_quality", json={"value": None})).status_code == 200
        assert "browser_high_quality" not in (await c.get("/api/prefs")).json()
        assert (await c.put("/api/prefs/Bad Key", json={"value": 1})).status_code == 422


async def test_sign_in_requests_go_from_the_phone_into_the_page_and_nowhere_else(hub, app):
    pin = tls.pin(hub.settings.tls_cert)
    key, phone = FakeKey(), FakePhone()
    async with lan(app, SETUP) as c:
        sess = (await pair(c, phone, key, pin, first=True)).json()["session"]
    token = hub.settings.token_file.read_text().strip()
    chat = await hub.create_session("sign in for me", "fake")
    hub.tab_owner["tab1"] = uuid.UUID(chat["id"])
    async with local(app, token) as agent:
        asked = await agent.post("/api/secrets", json={"tab_id": "tab1", "reason": "Sign in to Example",
                                                        "fields": [{"kind": "email"}, {"kind": "password"}]})
        assert asked.status_code == 201, asked.text
        req = asked.json()
        assert req["site"] == "login.example.com" and [f["name"] for f in req["fields"]] == ["email", "password"]
        assert (await agent.get(f"/api/secrets/{req['id']}/wait?timeout=0")).json()["status"] == "pending"
        # The agent (local token) cannot fill it: only a paired phone with the fingerprint.
        assert (await agent.post(f"/api/secrets/{req['id']}/fill", json={"values": {}})).status_code == 403
    secret = "hunter2-very-secret"
    body = json.dumps({"values": {"email": "me@example.com", "password": secret}}).encode()
    path = f"/api/secrets/{req['id']}/fill"
    async with lan(app, sess) as c:
        assert [r["id"] for r in (await c.get("/api/secrets")).json()] == [req["id"]]
        no_finger = await c.post(path, content=body, headers={"Content-Type": "application/json"})
        assert no_finger.status_code == 403
        finger = await presence(c, phone, "POST", path, body)
        filled = await c.post(path, content=body, headers={"Content-Type": "application/json", "X-Krewd-Presence": finger})
        assert filled.status_code == 200, filled.text
        assert filled.json()["status"] == "filled" and filled.json()["url"] == "https://login.example.com/home"
        again = await presence(c, phone, "POST", path, body)
        assert (await c.post(path, content=body, headers={"Content-Type": "application/json", "X-Krewd-Presence": again})).status_code == 409
    browser = app.state.security  # noqa: F841 - the fake browser saw the values; nothing else did
    rows = await hub.pool.fetch("select payload::text as p from events where kind = 'secret'")
    assert rows and not any(secret in r["p"] or "me@example.com" in r["p"] for r in rows)
    async with local(app, token) as agent:
        done = (await agent.get(f"/api/secrets/{req['id']}/wait?timeout=1")).json()
        assert done["status"] == "filled" and secret not in json.dumps(done)


# phone passkeys

RP_ID = "owner.example.com"
CERT = "7d4f9ce39a3ba7d14e972ffd7483192f0182dfb5ec6782522d913ba9ee44ddac"


def _cbor(v) -> bytes:
    def head(major: int, n: int) -> bytes:
        if n < 24:
            return bytes([major << 5 | n])
        for info, size in ((24, 1), (25, 2), (26, 4)):
            if n < 1 << (8 * size):
                return bytes([major << 5 | info]) + n.to_bytes(size, "big")
        raise ValueError
    if isinstance(v, int):
        return head(0, v) if v >= 0 else head(1, -1 - v)
    if isinstance(v, bytes):
        return head(2, len(v)) + v
    if isinstance(v, str):
        return head(3, len(v.encode())) + v.encode()
    if isinstance(v, dict):
        return head(5, len(v)) + b"".join(_cbor(k) + _cbor(x) for k, x in v.items())
    raise TypeError(v)


class FakePasskey:
    """A phone passkey as Android's Credential Manager answers for the Krewd app (origin = its signing certificate)."""

    def __init__(self, rp_id: str = RP_ID, cert: str = CERT) -> None:
        self.private = ec.generate_private_key(ec.SECP256R1())
        self.id = os.urandom(16)
        self.rp_hash = __import__("hashlib").sha256(rp_id.encode()).digest()
        self.origin = passkeys.apk_origin(cert)

    def client_data(self, kind: str, challenge: bytes) -> bytes:
        return json.dumps({"type": kind, "challenge": passkeys.b64url(challenge), "origin": self.origin}).encode()

    def registration(self, nonce: str) -> dict:
        nums = self.private.public_key().public_numbers()
        cose = _cbor({1: 2, 3: -7, -1: 1, -2: nums.x.to_bytes(32, "big"), -3: nums.y.to_bytes(32, "big")})
        auth = self.rp_hash + bytes([0x45]) + (0).to_bytes(4, "big") + bytes(16) + len(self.id).to_bytes(2, "big") + self.id + cose
        att = _cbor({"fmt": "none", "attStmt": {}, "authData": auth})
        return {"client_data": b64(self.client_data("webauthn.create", register_challenge(nonce))), "attestation_object": b64(att)}

    def sign(self, challenge: bytes) -> dict:
        auth = self.rp_hash + bytes([0x05]) + (0).to_bytes(4, "big")  # Google Password Manager keeps the counter at 0
        cd = self.client_data("webauthn.get", challenge)
        sig = self.private.sign(auth + __import__("hashlib").sha256(cd).digest(), ec.ECDSA(hashes.SHA256()))
        return {"credential_id": passkeys.b64url(self.id), "client_data": b64(cd), "authenticator_data": b64(auth), "signature": b64(sig)}


@pytest.fixture
def pk_app(hub):
    hub.settings = dataclasses.replace(hub.settings, passkey_rp_id=RP_ID, passkey_certs=(CERT,))
    return create_app(hub, SETUP, lifespan_hub=False)


async def pair_pk(c, phone: FakePhone, pk: FakePasskey, pin: str, first: bool) -> httpx.Response:
    n = (await nonce(c, "pair"))["nonce"]
    payload = pair_payload(phone.id, phone.spki(phone.device), phone.spki(phone.presence), pin)
    body = {
        "nonce": n, "device_id": phone.id, "name": "Pixel", "pin_seen": pin,
        "device_key": b64(phone.spki(phone.device)), "presence_key": b64(phone.spki(phone.presence)),
        "device_sig": phone.sign(phone.device, device_message("pair", n, pin)),
        "passkey": pk.sign(challenge_param("pair", n, payload)),
    }
    if first:
        body["passkey_registration"] = pk.registration(n)
    return await c.post("/api/security/pair", json=body)


async def test_a_phone_passkey_sets_krewd_up_and_pairs_phones(hub, pk_app):
    pin = tls.pin(hub.settings.tls_cert)
    pk, phone, second = FakePasskey(), FakePhone(), FakePhone()
    async with lan(pk_app, SETUP) as c:
        n = await nonce(c, "pair")
        assert n["passkey_rp_id"] == RP_ID and n["keys"] == []
        assert (await pair_pk(c, phone, FakePasskey(cert="00" * 32), pin, first=True)).status_code == 403  # another app
        assert (await pair_pk(c, phone, FakePasskey(rp_id="evil.example"), pin, first=True)).status_code == 403
        assert (await pair_pk(c, phone, pk, pin, first=True)).status_code == 201
    async with lan(pk_app) as c:
        assert (await pair_pk(c, second, FakePasskey(), pin, first=False)).status_code == 403  # not an owner key
        assert (await pair_pk(c, second, pk, pin, first=False)).status_code == 201  # synced passkey, counter stays 0
        assert (await session(c, second, pin)).status_code == 200
    async with local(pk_app, hub.settings.token_file.read_text().strip()) as c:
        state = (await c.get("/api/security")).json()
        assert [k["kind"] for k in state["keys"]] == ["passkey"]
        assert {d["key"] for d in state["devices"]} == {state["keys"][0]["id"]}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=pk_app), base_url="http://krewd") as c:
        links = (await c.get("/.well-known/assetlinks.json")).json()
        assert links[0]["target"]["package_name"] == "app.krewd"
        assert links[0]["target"]["sha256_cert_fingerprints"][0].startswith("7D:4F:9C")


async def test_passkey_next_to_the_security_key_added_and_removed_with_an_owner_key(hub, pk_app):
    pin = tls.pin(hub.settings.tls_cert)
    key, pk, phone, other = FakeKey(), FakePasskey(), FakePhone(), FakePhone()
    async with lan(pk_app, SETUP) as c:
        sess = (await pair(c, phone, key, pin, first=True)).json()["session"]
    async with lan(pk_app, sess) as c:
        # Adding a passkey needs a signature from a key already registered.
        n = (await nonce(c, "add-key"))["nonce"]
        reg = pk.registration(n)
        blob = base64.b64decode(reg["attestation_object"])
        wrong = {"nonce": n, "kind": "passkey", "name": "Pixel passkey", "passkey_registration": reg,
                 "assertion": b64(FakeKey().sign(challenge_param("add-key", n, key_digest(blob))))}
        assert (await c.post("/api/security/keys", json=wrong)).status_code == 403
        n = (await nonce(c, "add-key"))["nonce"]
        reg = pk.registration(n)
        body = {"nonce": n, "kind": "passkey", "name": "Pixel passkey", "passkey_registration": reg,
                "assertion": b64(key.sign(challenge_param("add-key", n, key_digest(base64.b64decode(reg["attestation_object"])))))}
        added = await c.post("/api/security/keys", json=body)
        assert added.status_code == 201, added.text
        pk_id = added.json()["id"]
        assert [k["kind"] for k in (await c.get("/api/security")).json()["keys"]] == ["u2f", "passkey"]
    async with lan(pk_app) as c:
        # Both keys pair phones now.
        assert (await pair_pk(c, other, pk, pin, first=False)).status_code == 201
        assert (await pair(c, FakePhone(), key, pin, first=False)).status_code == 201
    async with lan(pk_app, sess) as c:
        # Revoking a phone with the passkey.
        third = FakePhone()
        async with lan(pk_app) as c2:
            assert (await pair(c2, third, key, pin, first=False)).status_code == 201
        n = (await nonce(c, "revoke"))["nonce"]
        r = await c.post(f"/api/security/devices/{third.id}/revoke", json={"nonce": n, "passkey": pk.sign(challenge_param("revoke", n, third.id))})
        assert r.status_code == 200
        # Removing the passkey (signed by the security key): the phone added with it goes too.
        n = (await nonce(c, "remove-key"))["nonce"]
        r = await c.post(f"/api/security/keys/{pk_id}/remove", json={"nonce": n, "assertion": b64(key.sign(challenge_param("remove-key", n, pk_id)))})
        assert r.status_code == 200, r.text
        assert r.json()["phones_removed"] == [other.id]
        state = (await c.get("/api/security")).json()
        assert [k["kind"] for k in state["keys"]] == ["u2f"]
        # The last key stays.
        u2f_id = state["keys"][0]["id"]
        n = (await nonce(c, "remove-key"))["nonce"]
        r = await c.post(f"/api/security/keys/{u2f_id}/remove", json={"nonce": n, "assertion": b64(key.sign(challenge_param("remove-key", n, u2f_id)))})
        assert r.status_code == 409
    async with lan(pk_app) as c:
        assert (await session(c, other, pin)).status_code == 401
        assert (await session(c, phone, pin)).status_code == 200


async def test_owner_json_from_before_passkeys_still_works(hub, app):
    pin = tls.pin(hub.settings.tls_cert)
    key, phone = FakeKey(), FakePhone()
    async with lan(app, SETUP) as c:
        assert (await pair(c, phone, key, pin, first=True)).status_code == 201
    owner = hub.settings.token_file.parent / "owner.json"
    k = json.loads(owner.read_text())["keys"][0]
    owner.write_text(json.dumps({"public_key": k["public_key"], "key_handle": k["key_handle"], "counter": k["counter"]}))
    # Phones paired before passkeys have no "key" in their proof.
    row = await hub.pool.fetchrow("select proof from devices")
    proof = {f: v for f, v in row["proof"].items() if f != "key"}
    await hub.pool.execute("update devices set proof = $1", proof)
    fresh = create_app(hub, hub.settings.token_file.read_text().strip(), lifespan_hub=False)
    async with lan(fresh) as c:
        assert (await session(c, phone, pin)).status_code == 200
        assert (await pair(c, FakePhone(), key, pin, first=False)).status_code == 201
