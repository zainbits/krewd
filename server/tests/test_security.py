"""The owner's security key, phones, sessions, fingerprint checks, app settings and sign-in requests."""

from __future__ import annotations

import base64
import json
import os
import sys
import uuid
from pathlib import Path

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import crew_server.api as api_module
from crew_server import db, tls
from crew_server.__main__ import tls_marked
from crew_server.api import create_app
from crew_server.config import Engine, Settings, load_env_file
from crew_server.hub import Hub
from crew_server.security import APP_PARAM, challenge_param, device_message, pair_payload, presence_message

load_env_file()
DATABASE_URL = os.environ.get("CREW_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="CREW_TEST_DATABASE_URL is not set")
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


@pytest.fixture
async def hub(tmp_path, monkeypatch):
    monkeypatch.setattr(api_module, "Browser", FakeBrowser)
    monkeypatch.setattr(api_module.asyncio, "sleep", _no_sleep)
    pool = await db.connect(DATABASE_URL)
    await pool.execute(
        "drop table if exists session_agents, routine_triggers, routine_runs, routine_versions, routines, messages, events,"
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
    """The phone over Crew's own HTTPS on the home network."""
    headers = {"Authorization": f"Bearer {auth}"} if auth else {}
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=tls_marked(app), client=("192.168.0.20", 5000)),
                             base_url="https://crew", headers=headers)


def local(app, auth: str) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 5000)), base_url="http://crew",
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
    assert owner.stat().st_mode & 0o777 == 0o600 and json.loads(owner.read_text())["counter"] == key.counter
    new_token = hub.settings.token_file.read_text().strip()
    assert new_token != SETUP
    async with local(app, new_token) as c:  # the crew command on the machine itself
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
    from crew_server.__main__ import connect_link
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
        assert (await c.post("/api/profiles", content=body, headers={**headers, "X-Crew-Presence": bad})).status_code == 403
        good = await presence(c, phone, "POST", "/api/profiles", body)
        # Past the fingerprint check (what follows is the profile's own answer: not managed here, or incomplete).
        assert (await c.post("/api/profiles", content=body, headers={**headers, "X-Crew-Presence": good})).status_code not in (401, 403)
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
        filled = await c.post(path, content=body, headers={"Content-Type": "application/json", "X-Crew-Presence": finger})
        assert filled.status_code == 200, filled.text
        assert filled.json()["status"] == "filled" and filled.json()["url"] == "https://login.example.com/home"
        again = await presence(c, phone, "POST", path, body)
        assert (await c.post(path, content=body, headers={"Content-Type": "application/json", "X-Crew-Presence": again})).status_code == 409
    browser = app.state.security  # noqa: F841 - the fake browser saw the values; nothing else did
    rows = await hub.pool.fetch("select payload::text as p from events where kind = 'secret'")
    assert rows and not any(secret in r["p"] or "me@example.com" in r["p"] for r in rows)
    async with local(app, token) as agent:
        done = (await agent.get(f"/api/secrets/{req['id']}/wait?timeout=1")).json()
        assert done["status"] == "filled" and secret not in json.dumps(done)
