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
from crew_server.hub import Hub

load_env_file()
DATABASE_URL = os.environ.get("CREW_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="CREW_TEST_DATABASE_URL is not set")

FAKE = Path(__file__).with_name("fake_agent.py")
TOKEN = "test-token"


@pytest.fixture
async def hub(tmp_path):
    pool = await db.connect(DATABASE_URL)
    await pool.execute("drop table if exists events, approvals, sessions, engines, schema_version cascade")
    settings = Settings(
        database_url=DATABASE_URL,
        host="127.0.0.1",
        port=0,
        workdir=tmp_path,
        token_file=tmp_path / "token",
        state_dir=tmp_path / "state",
        engines={"fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)])},
        default_engine="fake",
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
