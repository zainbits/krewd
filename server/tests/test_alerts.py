"""Alerts, their levels, push to FCM, the Needs you items, and chats named in replies (alerts.py, push.py)."""

from __future__ import annotations

import asyncio
import json
import uuid
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from krewd_server.alerts import agent_level, run_level
from krewd_server.api import create_app
from krewd_server.push import Fcm, signed_assertion

from test_flow import DATABASE_URL, TOKEN, hub, wait_for, wait_run  # noqa: F401 (fixtures)

pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="KREWD_TEST_DATABASE_URL is not set")


def test_levels_follow_fixed_rules():
    assert agent_level(None) == "action"
    assert agent_level("info") == "info"
    assert agent_level("urgent") == "urgent"
    assert agent_level("critical") == "urgent"  # agents only suggest: no alarms from them
    assert run_level("done", True) == "action" and run_level("waiting", False) == "action"
    assert run_level("failed", False) == "urgent" and run_level("error", True) == "critical"
    assert run_level("cancelled", True) is None


class FakeFcm:
    """Google's token endpoint and FCM send, recording what was sent."""

    def __init__(self, tmp_path, gone: set[str] | None = None):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.public = key.public_key()
        pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
        self.path = tmp_path / "fcm.json"
        self.path.write_text(json.dumps({"type": "service_account", "project_id": "krewd-test",
                                         "client_email": "push@krewd-test.iam.gserviceaccount.com",
                                         "private_key": pem, "token_uri": "https://oauth2.example/token"}))
        self.sent: list[dict] = []
        self.tokens = 0
        self.gone = gone or set()

    def handler(self, request: httpx.Request) -> httpx.Response:
        if request.url.host == "oauth2.example":
            self.tokens += 1
            form = dict(x.split("=", 1) for x in request.content.decode().split("&"))
            head, claims, sig = form["assertion"].split(".")
            import base64

            def unb(s):
                return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
            self.public.verify(unb(sig), f"{head}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256())
            assert json.loads(unb(claims))["scope"].endswith("firebase.messaging")
            return httpx.Response(200, json={"access_token": "at-1", "expires_in": 3600})
        assert request.url.path == "/v1/projects/krewd-test/messages:send"
        assert request.headers["authorization"] == "Bearer at-1"
        body = json.loads(request.content)["message"]
        if body["token"] in self.gone:
            return httpx.Response(404, json={"error": {"status": "NOT_FOUND", "details": [{"errorCode": "UNREGISTERED"}]}})
        self.sent.append(body)
        return httpx.Response(200, json={"name": "projects/krewd-test/messages/1"})

    def install(self, hub) -> None:
        hub.alerts.fcm = Fcm(self.path, httpx.AsyncClient(transport=httpx.MockTransport(self.handler)))


def test_signed_assertion_is_rs256():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption()).decode()
    jwt = signed_assertion({"client_email": "a@b", "private_key": pem}, now=1000)
    assert jwt.count(".") == 2


async def test_push_carries_only_an_id_and_drops_gone_tokens(hub, tmp_path):
    fake = FakeFcm(tmp_path, gone={"old-phone"})
    fake.install(hub)
    await hub.alerts.register("phone-1", None, "0.37.0")
    await hub.alerts.register("old-phone", None, "0.30.0")
    alert = await hub.alerts.notify("test", "critical", "Test: Critical alert", "secret details")
    await hub.alerts.drain()
    assert len(fake.sent) == 1 and fake.tokens == 1
    msg = fake.sent[0]
    assert msg["token"] == "phone-1" and msg["android"]["priority"] == "HIGH"
    assert msg["data"] == {"k": "alert", "id": alert["id"], "l": "critical"}  # no title, no text
    assert await hub.pool.fetchval("select count(*) from push_tokens") == 1
    got = await hub.alerts.get(uuid.UUID(alert["id"]))
    assert got["title"] == "Test: Critical alert" and got["open"]
    # Info is a card only: no push.
    await hub.alerts.notify("test", "info", "quiet")
    await hub.alerts.drain()
    assert len(fake.sent) == 1


async def test_approval_and_finished_turns_alert(hub, tmp_path):
    fake = FakeFcm(tmp_path)
    fake.install(hub)
    await hub.alerts.register("phone-1", None, None)
    session = await hub.create_session("perm please")
    sid = uuid.UUID(session["id"])
    approval = await wait_for(hub, sid, "approval", lambda p: p["status"] == "pending")
    alert = await wait_for(hub, sid, "alert", lambda p: p["kind"] == "approval")
    assert alert["level"] == "action" and alert["ref_id"] == approval["id"]
    await hub.decide(uuid.UUID(approval["id"]), "allow")
    assert not (await hub.alerts.get(uuid.UUID(alert["id"])))["open"]  # decided: the phone shows nothing
    await wait_for(hub, sid, "turn_end")
    done = await wait_for(hub, sid, "alert", lambda p: p["kind"] == "finished")
    assert done["level"] == "action" and done["session_id"] == str(sid)
    await hub.alerts.drain()
    assert len(fake.sent) == 2


async def test_failed_routine_run_is_a_card_and_an_alarm_only_when_the_owner_asked(hub, tmp_path):
    fake = FakeFcm(tmp_path)
    fake.install(hub)
    await hub.alerts.register("phone-1", None, None)
    routine = await hub.create_routine("Check-in", "pause, then fail.", "55 9 * * 1-5", timezone="UTC")
    rid = uuid.UUID(routine["id"])
    await hub.run_routine(rid)
    await wait_run(hub, rid, "running")
    await hub.run_result(rid, "failed", "The check-in button was missing")
    await wait_run(hub, rid, "failed")
    for _ in range(100):
        cards = await hub.alerts.needs()
        if cards:
            break
        await asyncio.sleep(0.05)
    assert len(cards) == 1 and cards[0]["level"] == "urgent" and cards[0]["created_by"] == "krewd"
    assert cards[0]["link"].startswith("krewd://chat/") and "missing" in cards[0]["source"]

    # The owner marks the routine time-bound: its next failure rings.
    changed = await hub.update_routine(rid, {"alarm_on_fail": True})
    assert changed["alarm_on_fail"] and changed["version"] == routine["version"]
    await hub.run_routine(rid)
    await wait_run(hub, rid, "running")
    await hub.run_result(rid, "failed", "Too late")
    await wait_run(hub, rid, "failed")
    for _ in range(100):
        cards = await hub.alerts.needs()
        if len(cards) == 2:
            break
        await asyncio.sleep(0.05)
    assert sorted(c["level"] for c in cards) == ["critical", "urgent"]

    # A run that goes well closes Krewd's own cards about that routine.
    await hub.update_routine(rid, {"instructions": "Check in."})
    await hub.run_routine(rid)
    await wait_run(hub, rid, "done")
    for _ in range(100):
        if not await hub.alerts.needs():
            break
        await asyncio.sleep(0.05)
    assert await hub.alerts.needs() == []
    await hub.alerts.drain()
    levels = [m["data"]["l"] for m in fake.sent]
    assert levels == ["urgent", "critical", "action"]


async def test_needs_items_keep_one_card_per_key(hub):
    due = datetime.now(UTC) + timedelta(hours=3)
    first = await hub.alerts.add_need(key="zoho-popup", title="Answer the Zoho survey", source="Zoho", due_at=due,
                                      level="critical")
    assert first["outcome"] == "added" and first["level"] == "urgent" and first["suggested_level"] == "critical"
    again = await hub.alerts.add_need(key="zoho-popup", title="Answer the Zoho survey", source="Zoho", level="info")
    assert again["outcome"] == "updated" and again["id"] == first["id"] and again["seen_count"] == 2
    assert again["level"] == "urgent"  # a lower suggestion never lowers an open card
    assert len(await hub.alerts.needs()) == 1
    alerts = await hub.pool.fetchval("select count(*) from alerts where needs_id = $1", uuid.UUID(first["id"]))
    assert alerts == 1  # a repeat is not a new notification

    await hub.alerts.set_need(uuid.UUID(first["id"]), "dismissed")
    closed = await hub.alerts.add_need(key="zoho-popup", title="Answer the Zoho survey")
    assert closed["outcome"] == "closed" and await hub.alerts.needs() == []
    back = await hub.alerts.add_need(key="zoho-popup", title="Answer the Zoho survey", again=True)
    assert back["outcome"] == "reopened" and back["status"] == "open"


async def test_snooze_and_due_soon_push_again(hub):
    now = datetime.now(UTC)
    item = await hub.alerts.add_need(key="form", title="Send the form", due_at=now + timedelta(hours=2))
    nid = uuid.UUID(item["id"])
    await hub.alerts.set_need(nid, "snooze", now + timedelta(minutes=30))
    assert await hub.alerts.tick(now + timedelta(minutes=10)) == 0
    assert await hub.alerts.tick(now + timedelta(minutes=31)) == 1  # the snooze ended
    assert (await hub.alerts.need(nid))["snoozed_until"] is None
    assert await hub.alerts.tick(now + timedelta(minutes=106)) == 1  # due in 14 minutes
    assert await hub.alerts.tick(now + timedelta(minutes=110)) == 0  # once
    titles = [r["title"] for r in await hub.pool.fetch("select title from alerts where needs_id = $1 order by created_at", nid)]
    assert titles[-1].startswith("Due in 14 min")


async def test_replies_name_chats_by_title(hub, tmp_path):
    earlier = await hub.create_session("Say hello", title="Zoho check-in fix")
    eid = uuid.UUID(earlier["id"])
    await wait_for(hub, eid, "turn_end")
    gone = uuid.uuid4()
    session = await hub.create_session(f"hand krewd://chat/{eid} and {gone} and {eid}")
    sid = uuid.UUID(session["id"])
    end = await wait_for(hub, sid, "turn_end")
    assert end["chats"] == [{"id": str(eid), "title": "Zoho check-in fix", "archived": False}]


async def test_api_push_needs_and_test_alert(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 1)),
                                 base_url="http://test", headers=headers) as client:
        status = (await client.post("/api/push/register", json={"token": "tok", "app_version": "0.37.0"})).json()
        assert status["phones"] == 1 and status["configured"] is False
        due = (datetime.now(UTC) + timedelta(hours=1)).isoformat()
        r = await client.post("/api/needs", json={"key": "k1", "title": "Pay rent", "source": "Bank", "due_at": due,
                                                  "level": "critical", "link": "https://bank.example"})
        assert r.status_code == 200 and r.json()["level"] == "urgent"
        bad = await client.post("/api/needs", json={"key": "k2", "title": "x", "link": "javascript:alert(1)"})
        assert bad.status_code == 422
        item = (await client.get("/api/needs")).json()[0]
        snoozed = await client.post(f"/api/needs/{item['id']}", json={"action": "snooze",
                                                                       "until": (datetime.now(UTC) + timedelta(hours=2)).isoformat()})
        assert snoozed.json()["snoozed_until"]
        assert (await client.post("/api/needs/key/k1", json={"action": "done"})).json()["status"] == "done"
        test = (await client.post("/api/alerts/test", json={"level": "critical"})).json()
        assert test["level"] == "critical"
        assert (await client.get(f"/api/alerts/{test['id']}")).json()["open"]
        await client.post(f"/api/alerts/{test['id']}/ack")
        assert not (await client.get(f"/api/alerts/{test['id']}")).json()["open"]
