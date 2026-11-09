"""End-to-end tests of the hub and API against a fake ACP agent and a real PostgreSQL.

Needs CREW_TEST_DATABASE_URL (a throwaway database; its tables are dropped first).
"""

from __future__ import annotations

import asyncio
import os
import sys
import uuid
from pathlib import Path

import httpx
import pytest

from crew_server import db
from crew_server.api import create_app
from crew_server.config import Engine, Settings, load_env_file
from crew_server.hub import Hub, NotFound

load_env_file()
DATABASE_URL = os.environ.get("CREW_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="CREW_TEST_DATABASE_URL is not set")

FAKE = Path(__file__).with_name("fake_agent.py")
TOKEN = "test-token"


@pytest.fixture
async def hub(tmp_path):
    pool = await db.connect(DATABASE_URL)
    await pool.execute("drop table if exists messages, events, approvals, sessions, engines, schema_version cascade")
    settings = Settings(
        database_url=DATABASE_URL,
        host="127.0.0.1",
        port=0,
        workdir=tmp_path,
        token_file=tmp_path / "token",
        state_dir=tmp_path / "state",
        engines={"fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)])},
        default_engine="fake",
        data_dir=tmp_path / "data",
        max_upload_mb=1,
    )
    hub = Hub(settings, pool)
    await hub.start()
    yield hub
    await hub.close()
    await pool.close()


async def wait_for(hub: Hub, session_id, kind: str, check=lambda p: True, timeout: float = 10.0) -> dict:
    after = 0
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        for event in await hub.events(after, session_id):
            after = event["seq"]
            if event["kind"] == kind and check(event["payload"]):
                return event["payload"]
        await asyncio.sleep(0.05)
    raise AssertionError(f"no {kind} event")


async def test_turn_streams_text_and_updates_session(hub):
    session = await hub.create_session("Say hello")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    events = await hub.events(0, sid)
    kinds = [e["kind"] for e in events]
    assert kinds.index("user") < kinds.index("turn_start") < kinds.index("turn_end")
    texts = [e["payload"] for e in events if e["kind"] == "text"]
    assert "".join(t["text"] for t in texts if t["role"] == "assistant") == "Hello world"
    assert any(t["role"] == "thought" for t in texts)
    final = await hub.get_session(sid)
    assert final["status"] == "idle"
    assert final["last_text"] == "Hello world"
    assert final["title"] == "Fake title"
    assert final["usage"] == {"used": 10, "size": 100}


async def test_permission_waits_for_the_user(hub):
    session = await hub.create_session("perm please")
    sid = uuid.UUID(session["id"])
    approval = await wait_for(hub, sid, "approval", lambda p: p["status"] == "pending")
    assert (await hub.get_session(sid))["status"] == "waiting"
    assert approval["tool"]["title"] == "echo hi"
    await hub.decide(uuid.UUID(approval["id"]), "allow")
    tool = await wait_for(hub, sid, "tool", lambda p: p.get("status") == "completed")
    assert tool["output"] == "hi"
    await wait_for(hub, sid, "turn_end")
    assert (await hub.approvals("approved"))[0]["decided_by"] == "user"


async def test_auto_approve_answers_without_waiting(hub):
    session = await hub.create_session("perm please", auto_approve=True)
    sid = uuid.UUID(session["id"])
    approval = await wait_for(hub, sid, "approval")
    assert approval["status"] == "approved" and approval["decided_by"] == "auto"
    await wait_for(hub, sid, "turn_end")


async def test_cancel_stops_the_turn(hub):
    session = await hub.create_session("slow task")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_start")
    await asyncio.sleep(0.2)
    await hub.cancel(sid)
    end = await wait_for(hub, sid, "turn_end")
    assert end["stop_reason"] == "cancelled"


async def test_session_survives_agent_restart(hub):
    session = await hub.create_session("first")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    await hub.procs["fake"].stop()
    await asyncio.sleep(0.2)
    await hub.send(sid, "second")
    ends = []
    for _ in range(100):
        ends = [e for e in await hub.events(0, sid) if e["kind"] == "turn_end"]
        if len(ends) == 2:
            break
        await asyncio.sleep(0.05)
    assert [e["payload"]["stop_reason"] for e in ends] == ["end_turn", "end_turn"]


async def test_mode_change_reaches_the_agent(hub):
    session = await hub.create_session("first", mode="auto")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    assert (await hub.get_session(sid))["mode"] == "auto"
    await hub.update_session(sid, {"mode": "default"})
    await wait_for(hub, sid, "session", lambda p: p["mode"] == "default")


async def test_api_requires_token_and_long_polls(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        assert (await client.get("/api/health")).status_code == 200
        assert (await client.get("/api/sessions")).status_code == 401
        headers = {"Authorization": f"Bearer {TOKEN}"}
        state = (await client.get("/api/state", headers=headers)).json()
        created = await client.post("/api/sessions", json={"text": "hi"}, headers=headers)
        assert created.status_code == 201
        polled = (await client.get(f"/api/events?after={state['last_seq']}&wait=5", headers=headers)).json()
        assert polled["events"] and polled["last_seq"] > state["last_seq"]
        sid = uuid.UUID(created.json()["id"])
        await wait_for(hub, sid, "turn_end")
        listing = (await client.get("/api/sessions", headers=headers)).json()
        assert listing[0]["id"] == str(sid)
        bad = await client.post("/api/approvals/00000000-0000-0000-0000-000000000000", json={"option_id": "x"}, headers=headers)
        assert bad.status_code == 404


async def test_config_picks_reach_the_agent(hub):
    session = await hub.create_session("first calls", config={"model": "big"})
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    final = await hub.get_session(sid)
    assert final["agent"]["version"] == "9.9.9"
    current = {o["id"]: o["current"] for o in final["config_options"]}
    assert current == {"model": "big", "effort": "default"}
    assert final["model_used"] == "fake-big-1"
    effort = next(o for o in final["config_options"] if o["id"] == "effort")
    assert [c["value"] for c in effort["options"]] == ["default", "high"]  # groups are flattened
    await hub.update_session(sid, {"config": {"effort": "high"}})
    await wait_for(hub, sid, "session", lambda p: {o["id"]: o["current"] for o in p["config_options"]}.get("effort") == "high")
    assert (await hub.get_session(sid))["config"] == {"model": "big", "effort": "high"}


async def test_config_picks_return_after_agent_restart(hub):
    session = await hub.create_session("first", config={"model": "big"})
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    await hub.procs["fake"].stop()
    await asyncio.sleep(0.2)
    await hub.send(sid, "second calls")
    text = await wait_for(hub, sid, "text", lambda p: "calls=" in p["text"])
    # The new agent process got the pick again for the reopened session.
    assert "model:big" in text["text"]


async def test_engines_are_probed_for_their_options(hub):
    listing = hub.engines()
    assert listing[0]["id"] == "fake" and listing[0]["config_options"] == []
    for _ in range(100):
        listing = hub.engines()
        if listing[0]["config_options"]:
            break
        await asyncio.sleep(0.05)
    assert [o["id"] for o in listing[0]["config_options"]] == ["model", "effort"]
    assert listing[0]["agent"]["title"] == "Fake Agent"
    rows = await hub.pool.fetch("select * from engines")
    assert rows[0]["id"] == "fake" and rows[0]["config_options"]


async def test_attachments_are_stored_and_reach_the_agent(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    transport = httpx.ASGITransport(app=app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        up = await client.post("/api/uploads?name=../Résumé 2026.pdf", content=b"%PDF-1.7 hello",
                               headers={**headers, "Content-Type": "application/pdf"})
        assert up.status_code == 201
        meta = up.json()
        assert meta["name"] == "Résumé 2026.pdf" and meta["size"] == 14 and meta["mime"] == "application/pdf"
        second = (await client.post("/api/uploads?name=Résumé 2026.pdf", content=b"again", headers=headers)).json()
        created = await client.post("/api/sessions", json={"text": "", "attachments": [meta["id"], second["id"]]}, headers=headers)
        assert created.status_code == 201, created.text
        session = created.json()
        assert session["title"] == "File: Résumé 2026.pdf"
        sid = uuid.UUID(session["id"])
        user = await wait_for(hub, sid, "user")
        paths = [Path(f["path"]) for f in user["attachments"]]
        prefix = str(sid)[:8]
        assert [p.name for p in paths] == [f"{prefix}-Résumé 2026.pdf", f"{prefix}-Résumé 2026-2.pdf"]
        assert paths[0].parent == hub.settings.data_dir / "common" / "attachments"
        assert paths[0].read_bytes() == b"%PDF-1.7 hello"
        text = await wait_for(hub, sid, "text", lambda p: "links=" in p["text"])
        assert "links=Résumé 2026.pdf,Résumé 2026.pdf" in text["text"]
        # An id can be used once, unknown ids are rejected, and empty messages are refused.
        again = await client.post(f"/api/sessions/{sid}/messages", json={"attachments": [meta["id"]]}, headers=headers)
        assert again.status_code == 404
        empty = await client.post(f"/api/sessions/{sid}/messages", json={"text": " "}, headers=headers)
        assert empty.status_code == 422
        big = await client.post("/api/uploads?name=big.bin", content=b"x" * (1024 * 1024 + 1), headers=headers)
        assert big.status_code == 413
        assert not [p for p in hub.attachments.incoming.iterdir() if not p.name.endswith(".json")]


def assistant_text(events, after_seq: int = 0) -> str:
    return "".join(e["payload"]["text"] for e in events
                   if e["kind"] == "text" and e["payload"]["role"] == "assistant" and e["seq"] > after_seq)


async def test_steer_goes_into_the_running_turn(hub):
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "text", lambda p: p["role"] == "thought")  # the agent is up and in the turn
    assert hub.engines()[0]["steering"] is True
    message = await hub.send(sid, "go faster")
    assert message["delivery"] == "steer"
    delivered = await wait_for(hub, sid, "message", lambda p: p["id"] == message["id"])
    assert delivered["state"] == "delivered" and delivered["delivered_how"] == "steered"
    await wait_for(hub, sid, "turn_end")
    await asyncio.sleep(0.3)
    events = await hub.events(0, sid)
    assert [e["kind"] for e in events].count("turn_end") == 1
    assert "steered=go faster" in assistant_text(events)


async def test_queue_waits_for_the_turn_and_resends_are_idempotent(hub):
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_start")
    first = await hub.send(sid, "second", delivery="queue", client_id="c-1")
    again = await hub.send(sid, "second", delivery="queue", client_id="c-1")
    assert first["id"] == again["id"] and first["state"] == "queued"
    delivered = await wait_for(hub, sid, "message", lambda p: p["id"] == first["id"], timeout=20)
    assert delivered["delivered_how"] == "started"
    await wait_for(hub, sid, "turn_end", lambda p: True)
    events = await hub.events(0, sid)
    users = [e for e in events if e["kind"] == "user" and e["payload"].get("client_id") == "c-1"]
    assert len(users) == 1
    for _ in range(100):
        events = await hub.events(0, sid)
        if [e["kind"] for e in events].count("turn_end") == 2:
            break
        await asyncio.sleep(0.05)
    assert [e["kind"] for e in events].count("turn_end") == 2


async def test_interrupt_stops_the_turn_then_sends(hub):
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_start")
    await hub.send(sid, "instead", delivery="interrupt")
    first_end = await wait_for(hub, sid, "turn_end")
    assert first_end["stop_reason"] == "cancelled"
    for _ in range(100):
        events = await hub.events(0, sid)
        ends = [e for e in events if e["kind"] == "turn_end"]
        if len(ends) == 2:
            break
        await asyncio.sleep(0.05)
    assert ends[1]["payload"]["stop_reason"] == "end_turn"


async def test_stop_drops_queued_messages(hub):
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_start")
    queued = await hub.send(sid, "later", delivery="queue")
    await hub.cancel(sid)
    dropped = await wait_for(hub, sid, "message", lambda p: p["id"] == queued["id"])
    assert dropped["state"] == "cancelled"
    await wait_for(hub, sid, "turn_end")
    await asyncio.sleep(0.5)
    events = await hub.events(0, sid)
    assert [e["kind"] for e in events].count("turn_start") == 1


async def test_tabs_reach_the_agent_and_browser_activity_links_the_session(hub):
    session = await hub.create_session("slow", tabs=[{"id": "T1", "title": "Flights", "url": "https://example.com/f"}])
    sid = uuid.UUID(session["id"])
    user = await wait_for(hub, sid, "user")
    assert user["tabs"][0]["id"] == "T1"
    await wait_for(hub, sid, "turn_start")
    owner = await hub.browser_activity({"id": "T1", "url": "https://example.com/g", "title": "G"})
    assert owner == sid
    seen = await wait_for(hub, sid, "browser")
    assert seen["url"] == "https://example.com/g"
    assert (await hub.get_session(sid))["browser_tab"]["id"] == "T1"
    await hub.cancel(sid)
    await wait_for(hub, sid, "turn_end")
    assert await hub.browser_activity({"id": "T2", "url": "https://x", "title": "x"}) is None


async def test_queued_messages_survive_a_restart(hub):
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_start")
    queued = await hub.send(sid, "after restart", delivery="queue")
    await hub.close()
    second = Hub(hub.settings, hub.pool)
    await second.start()
    try:
        delivered = await wait_for(second, sid, "message", lambda p: p["id"] == queued["id"] and p["state"] == "delivered")
        assert delivered["delivered_how"] == "started"
        await wait_for(second, sid, "turn_end", lambda p: p["stop_reason"] == "end_turn")
    finally:
        await second.close()
        hub.closing = True


async def test_delete_stops_the_turn_and_removes_the_task(hub):
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "text", lambda p: p["role"] == "thought")
    await hub.send(sid, "later", delivery="queue")
    await hub.delete_session(sid)
    assert sid not in hub.runtimes
    assert await hub.pool.fetchval("select count(*) from events where session_id = $1", sid) == 0
    assert await hub.pool.fetchval("select count(*) from messages where session_id = $1", sid) == 0
    assert all(s["id"] != str(sid) for s in await hub.list_sessions())
    try:
        await hub.get_session(sid)
        raise AssertionError("still there")
    except NotFound:
        pass
    other = await hub.create_session("Say hello")  # the engine keeps working for other tasks
    await wait_for(hub, uuid.UUID(other["id"]), "turn_end")
