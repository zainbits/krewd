"""End-to-end tests of the hub and API against a fake ACP agent and a real PostgreSQL.

Needs KREWD_TEST_DATABASE_URL (a throwaway database; its tables are dropped first).
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
import uuid
from pathlib import Path

import httpx
import pytest

from krewd_server import db
from krewd_server.api import create_app
from krewd_server.config import Engine, Settings, load_env_file
from krewd_server.hub import Hub, NotFound

load_env_file()
DATABASE_URL = os.environ.get("KREWD_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="KREWD_TEST_DATABASE_URL is not set")

FAKE = Path(__file__).with_name("fake_agent.py")
TOKEN = "test-token"


@pytest.fixture
async def hub(tmp_path):
    pool = await db.connect(DATABASE_URL)
    await pool.execute(
        "drop table if exists browser_tabs, proposals, session_agents, routine_triggers, routine_runs, routine_versions, routines, messages, events, approvals, sessions,"
        " engines, devices, app_prefs, browser_locations, schema_version cascade"
    )
    settings = Settings(
        database_url=DATABASE_URL,
        host="127.0.0.1",
        port=0,
        workdir=tmp_path,
        token_file=tmp_path / "token",
        state_dir=tmp_path / "state",
        engines={
            "fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)]),
            "native": Engine(id="native", name="Fake with /compact", command=[sys.executable, str(FAKE)],
                             env={"FAKE_COMMANDS": "compact,review"}),
        },
        default_engine="fake",
        data_dir=tmp_path / "data",
        max_upload_mb=1,
        claude_dir=tmp_path / "claude",
        codex_dir=tmp_path / "codex",
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


async def test_owner_renames_a_chat_and_the_agent_no_longer_does(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        session = await hub.create_session("Say hello")
        sid = uuid.UUID(session["id"])
        await wait_for(hub, sid, "turn_end")
        assert (await hub.get_session(sid))["title"] == "Fake title"
        assert (await client.patch(f"/api/sessions/{sid}", headers=headers, json={"title": "  \n "})).status_code == 422
        renamed = await client.patch(f"/api/sessions/{sid}", headers=headers, json={"title": "  Trip\n plans  "})
        assert renamed.status_code == 200 and renamed.json()["title"] == "Trip plans"
        await hub.send(sid, "Again")
        for _ in range(200):
            if sum(e["kind"] == "turn_end" for e in await hub.events(0, sid)) == 2:
                break
            await asyncio.sleep(0.05)
        else:
            raise AssertionError("no second turn_end")
        assert (await hub.get_session(sid))["title"] == "Trip plans"


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
        health = await client.get("/api/health")
        version = (Path(__file__).resolve().parents[2] / "VERSION").read_text().strip()
        assert health.status_code == 200 and health.json()["version"] == version
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


async def test_config_pick_shows_while_agent_is_down(hub):
    session = await hub.create_session("first", config={"model": "big"})
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    await hub.procs["fake"].stop()
    await asyncio.sleep(0.2)
    # No live agent session: the pick shows at once and reaches the agent when the session reopens.
    updated = await hub.update_session(sid, {"config": {"effort": "high"}})
    assert {o["id"]: o["current"] for o in updated["config_options"]}["effort"] == "high"
    await hub.send(sid, "second calls")
    text = await wait_for(hub, sid, "text", lambda p: "calls=" in p["text"])
    assert "effort:high" in text["text"]


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
    rows = {r["id"]: r for r in await hub.pool.fetch("select * from engines")}
    assert rows["fake"]["config_options"]


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
        # The app can read a sent file back by its name, and nothing else in or outside the folder.
        back = await client.get(f"/api/attachments/{paths[0].name}", headers=headers)
        assert back.status_code == 200 and back.content == b"%PDF-1.7 hello"
        assert back.headers["content-type"] == "application/pdf"
        assert (await client.get(f"/api/attachments/{paths[0].name}")).status_code == 401
        for bad in ("..%2Ftoken", ".incoming", f"{meta['id']}", "nope.pdf"):
            assert (await client.get(f"/api/attachments/{bad}", headers=headers)).status_code == 404
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


async def test_each_turn_announces_its_first_browser_use_once(hub):
    """The app shows the picture-in-picture on a `browser` event: once per turn that uses the browser, even
    for the tab the chat had before, and not again for the same tab in the same turn."""
    session = await hub.create_session("slow")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_start")
    tab = {"id": "T9", "url": "https://example.com/a", "title": "A"}
    for _ in range(3):
        assert await hub.browser_activity(dict(tab)) == sid

    async def count() -> int:
        return [e["kind"] for e in await hub.events(0, sid)].count("browser")

    await asyncio.sleep(0.2)
    assert await count() == 1
    assert (await hub.get_session(sid))["browser_in_turn"] is True
    await hub.cancel(sid)
    await wait_for(hub, sid, "turn_end")
    assert (await hub.get_session(sid))["browser_in_turn"] is False
    await hub.send(sid, "slow")
    deadline = asyncio.get_running_loop().time() + 5
    while not hub._busy(sid) and asyncio.get_running_loop().time() < deadline:
        await asyncio.sleep(0.05)
    assert await hub.browser_activity(dict(tab)) == sid
    await asyncio.sleep(0.2)
    assert await count() == 2
    await hub.cancel(sid)


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
    assert hub.tab_busy("T1") == sid  # view only on the phone while this turn runs
    app = create_app(hub, TOKEN, lifespan_hub=False)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        for path, body in (("input", {"type": "key", "key": "Enter"}), ("view", {"mode": "vertical", "width": 400, "height": 800, "scale": 2})):
            assert (await client.post(f"/api/browser/tabs/T1/{path}", json=body, headers=headers)).status_code == 409
        assert (await client.delete("/api/browser/tabs/T1", headers=headers)).status_code == 409
    await hub.cancel(sid)
    await wait_for(hub, sid, "turn_end")
    assert hub.tab_busy("T1") is None
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


async def test_files_the_agent_names_are_handed_back(hub):
    shared = hub.settings.workdir / "outputs"
    shared.mkdir()
    (shared / "report.pdf").write_bytes(b"%PDF-1.4 test")
    (hub.settings.workdir / "secret.txt").write_text("not shared")
    (shared / "escape.txt").symlink_to(hub.settings.workdir / "secret.txt")
    named = f"{shared}/report.pdf {shared}/escape.txt {hub.settings.workdir}/secret.txt {shared}/missing.pdf"
    session = await hub.create_session("hand " + named)
    sid = uuid.UUID(session["id"])
    end = await wait_for(hub, sid, "turn_end")
    assert end["files"] == [{"path": str((shared / "report.pdf").resolve()), "name": "report.pdf",
                             "size": 13, "mime": "application/pdf"}]
    app = create_app(hub, TOKEN, lifespan_hub=False)
    transport = httpx.ASGITransport(app=app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        assert (await client.get("/api/files", params={"path": f"{shared}/report.pdf"})).status_code == 401
        got = await client.get("/api/files", params={"path": f"{shared}/report.pdf"}, headers=headers)
        assert got.status_code == 200 and got.content == b"%PDF-1.4 test"
        assert "report.pdf" in got.headers["content-disposition"]
        # The app resumes a broken-off download with a Range request for the rest.
        rest = await client.get("/api/files", params={"path": f"{shared}/report.pdf"}, headers={**headers, "Range": "bytes=4-"})
        assert rest.status_code == 206 and rest.content == b"-1.4 test"
        for outside in (f"{shared}/escape.txt", f"{hub.settings.workdir}/secret.txt", f"{shared}/../secret.txt", "secret.txt"):
            assert (await client.get("/api/files", params={"path": outside}, headers=headers)).status_code == 404


async def test_images_in_tool_results_become_files_not_text(hub):
    session = await hub.create_session("screenshot please")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    tools = [e["payload"] for e in await hub.events(0, sid) if e["kind"] == "tool"]
    final = tools[-1]
    assert final["status"] == "completed"
    assert len(final["images"]) == 1  # the same picture from raw output and content is kept once
    assert all("iVBOR" not in (t.get("output") or "") for t in tools)
    name = final["images"][0]
    assert name.startswith(str(sid)[:8]) and name.endswith(".png")
    app = create_app(hub, TOKEN, lifespan_hub=False)
    transport = httpx.ASGITransport(app=app)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        assert (await client.get(f"/api/tool-images/{name}")).status_code == 401
        got = await client.get(f"/api/tool-images/{name}", headers=headers)
        assert got.status_code == 200 and got.headers["content-type"] == "image/png"
        assert got.content.startswith(b"\x89PNG")
        for bad in ("../token", "x.png", name.replace(".png", ".txt")):
            assert (await client.get(f"/api/tool-images/{bad}", headers=headers)).status_code == 404


async def test_a_message_to_an_idle_task_is_queued_not_steered(hub):
    session = await hub.create_session("hi")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    message = await hub.send(sid, "and again")
    assert message["delivery"] == "queue"


async def test_repeated_session_states_are_sent_once(hub):
    session = await hub.create_session("hi")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    await hub.emit_session(sid)
    await hub.emit_session(sid)
    states = [e["payload"] for e in await hub.events(0, sid) if e["kind"] == "session"]
    plain = [{k: v for k, v in p.items() if k != "updated_at"} for p in states]
    assert all(a != b for a, b in zip(plain, plain[1:]))


# routines and compaction

from datetime import UTC, datetime, timedelta  # noqa: E402


async def wait_run(hub: Hub, routine_id, status: str, timeout: float = 10.0) -> dict:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        runs = await hub.routine_runs(routine_id)
        if runs and runs[0]["status"] == status:
            return runs[0]
        await asyncio.sleep(0.05)
    raise AssertionError(f"no run with status {status}: {runs}")


async def test_every_routine_run_has_its_own_chat_and_the_next_run_deletes_the_last(hub):
    routine = await hub.create_routine(
        "Timesheet", "Fill the timesheet on Fridays.", "0 19 * * 1-5", timezone="Asia/Kolkata"
    )
    rid, sid = uuid.UUID(routine["id"]), uuid.UUID(routine["session_id"])
    assert routine["schedule_text"] == "Weekdays at 19:00" and routine["version"] == 1
    assert len(routine["next_runs"]) == 3 and routine["next_run_at"]
    chat = await hub.get_session(sid)
    assert chat["routine_id"] == routine["id"] and chat["run_chat"] and chat["status"] == "idle"

    # The first run uses the chat made with the routine, named after the day.
    run = await hub.run_routine(rid)
    assert run["trigger"] == "manual" and run["session_id"] == str(sid)
    done = await wait_run(hub, rid, "done")
    assert "routine-ran" in done["summary"] and done["started_at"] and done["seq"]
    user = next(e for e in await hub.events(0, sid) if e["kind"] == "user")
    assert user["payload"]["purpose"] == "routine" and user["payload"]["meta"]["run_id"] == run["id"]
    text = user["payload"]["text"]
    assert "Fill the timesheet on Fridays." in text and "This month ends on" in text
    assert "its own new chat" in text and "krewd routines result" in text and "Never note what this run did" in text
    assert (await hub.get_session(sid))["title"].startswith("Timesheet · ")

    # The next run, with the edited instructions as a new version, gets a new chat and deletes the last one;
    # the last run's summary stays in the routine.
    edited = await hub.update_routine(rid, {"instructions": "Fill it and ask me to review."}, note="review first")
    assert edited["version"] == 2
    second = await hub.run_routine(rid)
    sid2 = uuid.UUID(second["session_id"])
    assert sid2 != sid
    await wait_run(hub, rid, "done")
    with pytest.raises(Exception, match="not found"):
        await hub.get_session(sid)
    assert any(e["kind"] == "chat_deleted" and e["payload"]["id"] == str(sid) for e in await hub.events(0, sid2))
    users = [e for e in await hub.events(0, sid2) if e["kind"] == "user"]
    assert len(users) == 1 and "ask me to review" in users[0]["payload"]["text"]
    runs = await hub.routine_runs(rid)
    assert runs[1]["session_id"] is None and "routine-ran" in runs[1]["summary"]
    assert [s["id"] for s in await hub.list_sessions()] == [str(sid2)]
    assert (await hub.get_session(sid2))["routine_id"] == routine["id"]
    versions = await hub.routine_versions(rid)
    assert [v["version"] for v in versions] == [2, 1] and versions[0]["note"] == "review first"

    # A chat the owner wrote in is kept, and so is the chat of a run that reports a failure.
    await hub.send(sid2, "thanks")
    for _ in range(200):
        if sum(e["kind"] == "turn_end" for e in await hub.events(0, sid2)) == 2:
            break
        await asyncio.sleep(0.05)
    await hub.update_routine(rid, {"instructions": "pause, then fail."})
    third = await hub.run_routine(rid)
    sid3 = uuid.UUID(third["session_id"])
    await wait_run(hub, rid, "running")
    marked = await hub.run_result(rid, "failed", "Check-out did not record")
    assert marked["id"] == third["id"]
    failed = await wait_run(hub, rid, "failed")
    assert failed["result_reason"] == "Check-out did not record"
    with pytest.raises(Exception, match="during a run"):
        await hub.run_result(rid, "failed", "too late")
    fourth = await hub.run_routine(rid)
    await wait_run(hub, rid, "done")
    ids = {s["id"] for s in await hub.list_sessions()}
    assert {str(sid2), str(sid3), fourth["session_id"]} <= ids
    # The fourth run's chat goes at the next run; the kept ones stay.
    await hub.run_routine(rid)
    await wait_run(hub, rid, "done")
    ids = {s["id"] for s in await hub.list_sessions()}
    assert fourth["session_id"] not in ids and {str(sid2), str(sid3)} <= ids


async def test_a_follow_up_attempt_continues_in_the_same_chat_and_session(hub):
    routine = await hub.create_routine("Out", "pause, then check out.", triggers=[
        {"name": "check-out", "schedule": "0 19 * * *", "max_attempts": 2}], timezone="UTC")
    rid = uuid.UUID(routine["id"])
    first = await hub.run_routine(rid)
    sid = uuid.UUID(first["session_id"])
    await wait_run(hub, rid, "running")
    await hub.follow_up(rid, minutes=5, reason="counter at 8:50")
    await wait_run(hub, rid, "done")
    agent = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    await hub.pool.execute("update routine_triggers set next_run_at = now() - interval '1 second' "
                           "where routine_id = $1 and kind = 'once'", rid)
    assert await hub.run_due() == 1
    second = await wait_run(hub, rid, "done")
    assert second["attempt"] == 2 and second["session_id"] == str(sid)
    assert await hub.pool.fetchval("select agent_session from sessions where id = $1", sid) == agent
    users = [e for e in await hub.events(0, sid) if e["kind"] == "user"]
    assert len(users) == 2 and "continues in the chat and session of the last one" in users[1]["payload"]["text"]
    assert "counter at 8:50" in users[1]["payload"]["text"]


async def test_scheduler_runs_due_routines_once_and_skips_stale_ones(hub):
    routine = await hub.create_routine("Daily", "Check out.", "*/5 * * * *", timezone="UTC")
    rid = uuid.UUID(routine["id"])
    now = datetime.now(UTC)
    await hub.pool.execute("update routine_triggers set next_run_at = $2 where routine_id = $1", rid, now - timedelta(minutes=1))
    assert await hub.run_due(now) == 1
    assert await hub.run_due(now) == 0  # claimed: next_run_at moved on
    nxt = await hub.pool.fetchval("select next_run_at from routine_triggers where routine_id = $1", rid)
    assert nxt > now
    await wait_run(hub, rid, "done")

    # Missed by more than the catch-up window (Krewd was down): recorded as skipped, not run.
    # (A yearly schedule, so no later slot falls inside the window either.)
    yearly = await hub.create_routine("Yearly", "Renew.", "0 0 1 1 *", timezone="UTC")
    yid = uuid.UUID(yearly["id"])
    await hub.pool.execute("update routine_triggers set next_run_at = $2 where routine_id = $1", yid, now - timedelta(hours=9))
    assert await hub.run_due(now) == 0
    runs = await hub.routine_runs(yid)
    assert runs[0]["status"] == "skipped" and "not running" in runs[0]["summary"]


async def test_skip_runs_skips_the_next_firings_and_today(hub):
    routine = await hub.create_routine(
        "Attendance", "Check in or out.", timezone="UTC",
        triggers=[{"name": "check-in", "schedule": "*/5 * * * *"},
                  {"name": "check-out", "schedule": "0 0 1 1 *", "max_attempts": 3}],
    )
    rid = uuid.UUID(routine["id"])
    skipped = await hub.skip_runs(rid, "check-in", runs=2)
    by = {t["name"]: t for t in skipped["triggers"]}
    assert by["check-in"]["skip_runs"] == 2 and by["check-out"]["skip_runs"] == 0
    marks = [u["skipped"] for u in skipped["upcoming"] if u["trigger"] == "check-in"]
    assert marks[:3] == [True, True, False]
    assert skipped["next_live_at"] > skipped["next_run_at"]

    now = datetime.now(UTC)
    await hub.pool.execute("update routine_triggers set next_run_at = $2 where routine_id = $1 and name = 'check-in'",
                           rid, now - timedelta(seconds=30))
    assert await hub.run_due(now) == 0  # skipped, not run
    runs = await hub.routine_runs(rid)
    assert runs[0]["status"] == "skipped" and "as you asked" in runs[0]["summary"] and "1 more" in runs[0]["summary"]
    assert await hub.pool.fetchval("select skip_runs from routine_triggers where routine_id = $1 and name = 'check-in'",
                                   rid) == 1

    # Clearing it lets the next firing run.
    await hub.skip_runs(rid, "check-in", runs=0)
    later = now + timedelta(minutes=6)
    await hub.pool.execute("update routine_triggers set next_run_at = $2 where routine_id = $1 and name = 'check-in'",
                           rid, later - timedelta(seconds=30))
    assert await hub.run_due(later) == 1
    await wait_run(hub, rid, "done")

    # Today: every trigger, as many firings as are left today; a yearly trigger has none today (usually 0).
    today = await hub.skip_runs(rid, today=True)
    by = {t["name"]: t for t in today["triggers"]}
    assert by["check-in"]["skip_runs"] >= 1
    with pytest.raises(Exception, match="no trigger"):
        await hub.skip_runs(rid, "nope", runs=1)


async def test_routine_run_skips_while_the_last_one_runs(hub):
    routine = await hub.create_routine("Slow", "slow task", "0 9 * * *", timezone="UTC")
    rid = uuid.UUID(routine["id"])
    await hub.run_routine(rid)
    await wait_run(hub, rid, "running")
    with pytest.raises(Exception, match="not finished"):
        await hub.run_routine(rid)
    await hub.cancel(uuid.UUID(routine["session_id"]))
    await wait_run(hub, rid, "cancelled")


async def test_routine_chat_comes_back_after_delete_and_pause_clears_next_run(hub):
    routine = await hub.create_routine("Again", "Do it.", "0 9 * * *", timezone="UTC")
    rid = uuid.UUID(routine["id"])
    await hub.delete_session(uuid.UUID(routine["session_id"]))
    paused = await hub.update_routine(rid, {"enabled": False})
    assert paused["next_run_at"] is None and paused["session_id"] is None and paused["next_runs"] == []
    run = await hub.run_routine(rid)
    assert run["session_id"] and run["session_id"] != routine["session_id"]
    await wait_run(hub, rid, "done")
    await hub.delete_routine(rid)
    chat = await hub.get_session(uuid.UUID(run["session_id"]))
    assert chat["routine_id"] is None


async def test_bad_schedules_are_refused(hub):
    from krewd_server.routines import Invalid
    with pytest.raises(Invalid):
        await hub.create_routine("Bad", "x", "every friday")
    with pytest.raises(Invalid):
        await hub.create_routine("Bad", "x", "0 9 * * *", timezone="Mars/Olympus")
    assert hub.preview_schedule("0 18 * * 5", "Asia/Kolkata")["schedule_text"] == "Fridays at 18:00"


async def test_routines_api(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        h = {"Authorization": f"Bearer {TOKEN}"}
        r = await client.post("/api/routines", headers=h, json={
            "title": "API", "instructions": "Do it.", "schedule": "0 9 * * 1", "timezone": "UTC", "by": "agent"})
        assert r.status_code == 201 and r.json()["created_by"] == "agent"
        rid = r.json()["id"]
        assert (await client.post("/api/routines", headers=h, json={
            "title": "x", "instructions": "y", "schedule": "nonsense here"})).status_code == 422
        r = await client.patch(f"/api/routines/{rid}", headers=h, json={"schedule": "0 10 * * 1"})
        assert r.json()["version"] == 2 and r.json()["schedule_text"] == "Mondays at 10:00"
        assert len((await client.get("/api/routines", headers=h)).json()) == 1
        assert (await client.post(f"/api/routines/{rid}/run", headers=h)).status_code == 202
        await wait_run(hub, uuid.UUID(rid), "done")
        detail = (await client.get(f"/api/routines/{rid}", headers=h)).json()
        assert detail["runs"][0]["status"] == "done" and detail["last_run"]["status"] == "done"
        assert (await client.delete(f"/api/routines/{rid}", headers=h)).status_code == 200
        assert (await client.get(f"/api/routines/{rid}", headers=h)).status_code == 404


async def test_compact_uses_the_agents_own_command(hub):
    session = await hub.create_session("first", engine="native")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    for _ in range(100):
        if (await hub.get_session(sid))["compact"] == "native":
            break
        await asyncio.sleep(0.05)
    assert (await hub.get_session(sid))["compact"] == "native"
    # The composer's suggestions: the agent's own commands with their descriptions, for the chat and the engine.
    expected = [{"name": "compact", "description": "compact", "hint": None},
                {"name": "review", "description": "review", "hint": None}]
    assert (await hub.get_session(sid))["commands"] == expected
    assert next(e for e in hub.engines() if e["id"] == "native")["commands"] == expected
    agent_session = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    await hub.compact(sid)
    event = await wait_for(hub, sid, "compact")
    assert event == {"how": "native", "ok": True}
    texts = [e["payload"]["text"] for e in await hub.events(0, sid) if e["kind"] == "text"]
    assert "Compacted." in texts
    assert await hub.pool.fetchval("select agent_session from sessions where id = $1", sid) == agent_session


async def test_compact_hands_over_where_the_agent_cannot(hub):
    with pytest.raises(Exception, match="Nothing to compact"):
        await hub.compact(uuid.UUID((await hub.create_routine("R", "x", "0 9 * * *", timezone="UTC"))["session_id"]))
    session = await hub.create_session("first")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    assert (await hub.get_session(sid))["compact"] == "handover"
    old = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    await hub.compact(sid)
    assert await wait_for(hub, sid, "compact") == {"how": "handover", "ok": True}
    row = await hub.pool.fetchrow("select agent_session, carry from sessions where id = $1", sid)
    assert row["agent_session"] is None and "MK-42" in row["carry"]
    await hub.send(sid, "next please")
    await wait_for(hub, sid, "text", lambda p: "carried=MK-42" in p["text"])
    await wait_for(hub, sid, "turn_end", lambda p: True)
    row = await hub.pool.fetchrow("select agent_session, carry from sessions where id = $1", sid)
    assert row["agent_session"] and row["agent_session"] != old and row["carry"] is None


async def test_pin_puts_a_chat_first(hub):
    a = await hub.create_session("first")
    b = await hub.create_session("second")
    await wait_for(hub, uuid.UUID(b["id"]), "turn_end")
    await hub.update_session(uuid.UUID(a["id"]), {"pinned": True})
    listing = await hub.list_sessions()
    assert listing[0]["id"] == a["id"] and listing[0]["pinned"]


# triggers, follow-ups and notes


async def test_one_routine_with_several_triggers(hub):
    routine = await hub.create_routine(
        "Attendance", "On check-in: check in. On check-out: check out.", timezone="Asia/Kolkata",
        triggers=[{"name": "check-in", "schedule": "55 9 * * 1-5"},
                  {"name": "check-out", "schedule": "0 19 * * 1-5", "max_attempts": 3}],
        notes="Counter is top right.",
    )
    rid = uuid.UUID(routine["id"])
    assert [t["name"] for t in routine["triggers"]] == ["check-in", "check-out"]
    assert routine["schedule_text"] == "check-in: Weekdays at 09:55 · check-out: Weekdays at 19:00"
    assert routine["upcoming"][0]["trigger"] in ("check-in", "check-out") and len(routine["next_runs"]) == 3
    now = datetime.now(UTC)
    await hub.pool.execute(
        "update routine_triggers set next_run_at = $2 where routine_id = $1 and name = 'check-out'",
        rid, now - timedelta(seconds=30),
    )
    assert await hub.run_due(now) == 1
    run = await wait_run(hub, rid, "done")
    assert run["triggers"] == ["check-out"] and run["attempt"] == 1 and run["max_attempts"] == 3
    user = [e for e in await hub.events(0, uuid.UUID(routine["session_id"])) if e["kind"] == "user"][-1]["payload"]
    assert "Trigger: check-out (Weekdays at 19:00). Attempt 1 of 3." in user["text"]
    assert "Counter is top right." in user["text"] and "krewd routines later" in user["text"]
    # Both due at once: one run that names both.
    await hub.pool.execute("update routine_triggers set next_run_at = $2 where routine_id = $1", rid, now)
    assert await hub.run_due(now + timedelta(seconds=1)) == 1
    run = await wait_run(hub, rid, "done")
    assert sorted(run["triggers"]) == ["check-in", "check-out"]
    # Changing one trigger by name keeps the other; a new version records the triggers.
    edited = await hub.update_routine(rid, {"triggers": [
        {"name": "check-in", "schedule": "50 9 * * 1-5"}, {"name": "check-out", "schedule": "0 19 * * 1-5", "max_attempts": 3}]})
    assert edited["version"] == 2 and edited["triggers"][0]["schedule"] == "50 9 * * 1-5"
    assert (await hub.routine_versions(rid))[0]["triggers"][0]["schedule"] == "50 9 * * 1-5"
    runs = await hub.run_routine(rid, "check-in")
    assert runs["triggers"] == ["check-in"]
    await wait_run(hub, rid, "done")
    with pytest.raises(NotFound):
        await hub.run_routine(rid, "lunch")


async def test_follow_ups_are_bounded_by_the_triggers_attempts(hub):
    routine = await hub.create_routine(
        "Out", "slow task", timezone="UTC", triggers=[{"name": "check-out", "schedule": "0 19 * * *", "max_attempts": 2}],
    )
    rid = uuid.UUID(routine["id"])
    with pytest.raises(Exception, match="only be set during a run"):
        await hub.follow_up(rid, minutes=5, reason="x")
    await hub.run_routine(rid, "check-out")
    await wait_run(hub, rid, "running")
    later = await hub.follow_up(rid, minutes=5, reason="counter 8:56")
    assert later["kind"] == "once" and later["attempt"] == 2 and later["parent"] == "check-out"
    sid = uuid.UUID(routine["session_id"])
    await hub.cancel(sid)
    await wait_run(hub, rid, "cancelled")
    # The follow-up fires as attempt 2 of 2, with the reason; then no attempts are left.
    await hub.pool.execute("update routine_triggers set next_run_at = now() - interval '1 second' where kind = 'once'")
    assert await hub.run_due() == 1
    await wait_run(hub, rid, "running")
    runs = await hub.routine_runs(rid)
    assert runs[0]["attempt"] == 2 and runs[0]["max_attempts"] == 2
    user = [e for e in await hub.events(0, sid) if e["kind"] == "user"][-1]["payload"]["text"]
    assert "Attempt 2 of 2" in user and "counter 8:56" in user and "No attempts are left" in user
    with pytest.raises(Exception, match="no attempts left"):
        await hub.follow_up(rid, minutes=5, reason="again")
    await hub.cancel(sid)
    assert not await hub.pool.fetchval("select count(*) from routine_triggers where kind = 'once'")


async def test_follow_up_waits_while_the_last_run_goes_and_pause_drops_it(hub):
    routine = await hub.create_routine("W", "slow task", timezone="UTC",
                                       triggers=[{"name": "a", "schedule": "0 19 * * *", "max_attempts": 3}])
    rid = uuid.UUID(routine["id"])
    await hub.run_routine(rid)
    await wait_run(hub, rid, "running")
    await hub.follow_up(rid, minutes=1, reason="not yet")
    await hub.pool.execute("update routine_triggers set next_run_at = now() - interval '1 second' where kind = 'once'")
    assert await hub.run_due() == 0  # still running: it waits a minute instead of being lost
    assert await hub.pool.fetchval("select next_run_at > now() from routine_triggers where kind = 'once'")
    await hub.update_routine(rid, {"enabled": False})
    assert not await hub.pool.fetchval("select count(*) from routine_triggers where kind = 'once'")
    await hub.cancel(uuid.UUID(routine["session_id"]))


async def test_notes_and_the_month_end_trigger(hub):
    routine = await hub.create_routine("T", "Fill.", timezone="Asia/Kolkata",
                                       triggers=[{"name": "weekly", "schedule": "0 18 * * 5"},
                                                 {"name": "month-end", "schedule": "0 18 LW * *"}])
    rid = uuid.UUID(routine["id"])
    assert "month-end: 0 18 LW * *" in routine["schedule_text"] or "month-end:" in routine["schedule_text"]
    r = await hub.set_routine_notes(rid, "MK entry is 'MK-Delivery'.")
    r = await hub.set_routine_notes(rid, "Week 46 filled.", append=True)
    assert r["notes"] == "MK entry is 'MK-Delivery'.\nWeek 46 filled."
    from krewd_server.routines import Invalid
    with pytest.raises(Invalid):
        await hub.update_routine(rid, {"schedule": "0 9 * * *"})  # several triggers: use triggers
    with pytest.raises(Invalid):
        await hub.update_routine(rid, {"triggers": [{"name": "a", "schedule": "0 9 * * *"}, {"name": "a", "schedule": "0 9 * * *"}]})


async def test_existing_routines_become_a_main_trigger(hub):
    routine = await hub.create_routine("Old", "x", "0 9 * * *", timezone="UTC")
    assert [(t["name"], t["schedule"]) for t in routine["triggers"]] == [("main", "0 9 * * *")]
    edited = await hub.update_routine(uuid.UUID(routine["id"]), {"schedule": "0 10 * * *"})
    assert edited["triggers"][0]["schedule"] == "0 10 * * *" and edited["schedule"] == "0 10 * * *"


async def test_trigger_api_later_and_notes(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        h = {"Authorization": f"Bearer {TOKEN}"}
        r = await client.post("/api/routines", headers=h, json={
            "title": "API", "instructions": "slow task", "timezone": "UTC",
            "triggers": [{"name": "in", "schedule": "55 9 * * 1-5"}, {"name": "out", "schedule": "0 19 * * 1-5", "max_attempts": 3}]})
        assert r.status_code == 201, r.text
        rid = r.json()["id"]
        assert (await client.post(f"/api/routines/{rid}/run", headers=h, json={"trigger": "out"})).status_code == 202
        await wait_run(hub, uuid.UUID(rid), "running")
        r = await client.post(f"/api/routines/{rid}/later", headers=h, json={"minutes": 5, "reason": "8:56"})
        assert r.status_code == 201 and r.json()["attempt"] == 2
        tid = r.json()["id"]
        detail = (await client.get(f"/api/routines/{rid}", headers=h)).json()
        assert any(t["kind"] == "once" for t in detail["triggers"])
        assert (await client.delete(f"/api/routines/{rid}/later/{tid}", headers=h)).status_code == 200
        r = await client.put(f"/api/routines/{rid}/notes", headers=h, json={"notes": "learned"})
        assert r.json()["notes"] == "learned"
        await hub.cancel(uuid.UUID(detail["session_id"]))


# one chat, several agents


async def wait_engine(hub: Hub, sid, engine: str, timeout: float = 10.0) -> None:
    deadline = asyncio.get_running_loop().time() + timeout
    while asyncio.get_running_loop().time() < deadline:
        if await hub.pool.fetchval("select engine from sessions where id = $1", sid) == engine:
            return
        await asyncio.sleep(0.05)
    raise AssertionError(f"chat did not move to {engine}")


async def test_switching_agents_keeps_one_chat_and_each_agents_session(hub):
    session = await hub.create_session("first on fake")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    fake_session = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    await hub.switch_engine(sid, "native")
    switch = await wait_for(hub, sid, "switch")
    assert switch["from"] == "fake" and switch["to"] == "native" and switch["summary"]  # the fake's handover
    await wait_engine(hub, sid, "native")
    row = await hub.pool.fetchrow("select engine, agent_session, carry from sessions where id = $1", sid)
    assert row["engine"] == "native" and row["agent_session"] is None
    assert "You are joining this chat" in row["carry"] and str(sid) in row["carry"] and fake_session in row["carry"]
    after = (await hub.events(0, sid))[-1]["seq"]
    await hub.send(sid, "hello native")
    await wait_for(hub, sid, "text", lambda p: "briefed" in p["text"])
    await wait_for(hub, sid, "turn_end", lambda p: True)
    native_session = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    assert native_session and native_session != fake_session
    assert (await hub.get_session(sid))["engine"] == "native"
    # Back to the fake: its own session again, briefed only with the native agent's handover.
    await hub.switch_engine(sid, "fake")
    await wait_engine(hub, sid, "fake")
    row = await hub.pool.fetchrow("select engine, agent_session, carry from sessions where id = $1", sid)
    assert row["engine"] == "fake" and row["agent_session"] == fake_session
    assert "You handled this chat before" in row["carry"] and native_session in row["carry"]
    assert row["carry"].count("From ") == 1
    await hub.send(sid, "back on fake")
    await wait_for(hub, sid, "text", lambda p: "briefed" in p["text"] and True)
    assert len(await hub.list_sessions()) == 1
    transcript = await hub.transcript(sid)
    froms = [m["from"] for m in transcript]
    assert "owner" in froms and "Fake" in froms and "Fake with /compact" in froms
    assert any("Switched from Fake to Fake with /compact" in m["text"] for m in transcript)


async def test_a_routine_on_another_agent_runs_there_in_a_clean_chat(hub):
    routine = await hub.create_routine("R", "Do it.", "0 9 * * *", timezone="UTC")
    rid, sid = uuid.UUID(routine["id"]), uuid.UUID(routine["session_id"])
    await hub.run_routine(rid)
    await wait_run(hub, rid, "done")
    # A chat's own switch is the chat's business: the routine keeps its agent.
    await hub.switch_engine(sid, "native")
    await wait_engine(hub, sid, "native")
    assert (await hub.get_routine(rid))["engine"] == "fake"
    # A changed routine takes effect at its next run, in a new chat on the new agent with its settings,
    # without any handover from earlier runs.
    await hub.update_routine(rid, {"engine": "native", "config": {"model": "big"}})
    assert await hub.pool.fetchval("select engine from sessions where id = $1", sid) == "native"
    run = await hub.run_routine(rid)
    new = uuid.UUID(run["session_id"])
    await wait_run(hub, rid, "done")
    row = await hub.pool.fetchrow("select engine, config_wanted, carry from sessions where id = $1", new)
    assert row["engine"] == "native" and row["config_wanted"] == {"model": "big"} and row["carry"] is None
    texts = "".join(e["payload"].get("text", "") for e in await hub.events(0, new) if e["kind"] == "text")
    assert "routine-ran" in texts and "briefed" not in texts


async def test_switch_api_and_transcript(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        h = {"Authorization": f"Bearer {TOKEN}"}
        session = await hub.create_session("hi")
        sid = session["id"]
        await wait_for(hub, uuid.UUID(sid), "turn_end")
        assert (await client.post(f"/api/sessions/{sid}/engine", headers=h, json={"engine": "nope"})).status_code == 404
        assert (await client.post(f"/api/sessions/{sid}/engine", headers=h, json={"engine": "native"})).status_code == 202
        await wait_for(hub, uuid.UUID(sid), "switch")
        r = await client.get(f"/api/sessions/{sid}/transcript?last=2", headers=h)
        assert r.status_code == 200 and len(r.json()) == 2 and r.json()[-1]["from"] == "Krewd"


async def test_margin_notes_reach_the_agent_and_the_transcript(hub):
    app = create_app(hub, TOKEN, lifespan_hub=False)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        h = {"Authorization": f"Bearer {TOKEN}"}
        session = await hub.create_session("hi")
        sid = session["id"]
        await wait_for(hub, uuid.UUID(sid), "turn_end")
        notes = [{"quote": "Scroll reset: back rebuilds the list", "note": "fix only this", "ref": "text-m1#b2"},
                 {"quote": "Long code blocks\noverflow", "note": ""}]
        # Notes alone are a message; an empty quote is not a note.
        r = await client.post(f"/api/sessions/{sid}/messages", headers=h, json={"text": "", "notes": notes})
        assert r.status_code == 202, r.text
        assert (await client.post(f"/api/sessions/{sid}/messages", headers=h,
                                  json={"text": "", "notes": [{"quote": ""}]})).status_code == 422
        user = await wait_for(hub, uuid.UUID(sid), "user", lambda p: bool(p.get("notes")))
        assert user["notes"][0] == {"quote": notes[0]["quote"], "note": "fix only this", "ref": "text-m1#b2"}
        assert user["notes"][1]["ref"] is None
        reply = await wait_for(hub, uuid.UUID(sid), "text", lambda p: "noted=" in p["text"])
        assert "noted=Scroll reset: back rebuilds the list|Long code blocks|overflow" in reply["text"]
        transcript = (await client.get(f"/api/sessions/{sid}/transcript", headers=h)).json()
        owner = [m for m in transcript if m["from"] == "owner"][-1]["text"]
        assert "[1] On this part:\n> Scroll reset" in owner and "Note: fix only this" in owner
        assert "[2] On this part:\n> Long code blocks\n> overflow\n(No note of its own" in owner


def test_command_list_reads_acp_entries_and_old_names():
    raw = [{"name": "review", "description": "Review code", "input": {"hint": "branch"}}, "compact",
           {"name": "__internal"}, {"name": ""}, {"description": "no name"}, {"name": "Agents", "description": None}]
    assert db.command_list(raw) == [
        {"name": "Agents", "description": "", "hint": None},
        {"name": "compact", "description": "", "hint": None},
        {"name": "review", "description": "Review code", "hint": "branch"},
    ]
    assert db.command_names(None) == set()


def _transcript(hub: Hub, agent_session: str, entrypoint: str, age_hours: float = 48) -> Path:
    folder = hub.settings.claude_dir / "projects" / "-work"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"{agent_session}.jsonl"
    path.write_text('{"type":"queue-operation"}\n' + json.dumps(
        {"type": "user", "entrypoint": entrypoint, "cwd": str(hub.settings.workdir)}) + "\n" + "x" * 1000 + "\n")
    old = time.time() - age_hours * 3600
    os.utime(path, (old, old))
    return path


async def test_storage_report_and_clean_keep_live_chats_resumable(hub):
    session = await hub.create_session("screenshot please")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    agent_session = (await hub.pool.fetchrow("select agent_session from sessions where id = $1", sid))["agent_session"]
    linked = _transcript(hub, agent_session, "sdk-ts")
    left = _transcript(hub, str(uuid.uuid4()), "sdk-ts")  # a deleted chat's agent session
    fresh = _transcript(hub, str(uuid.uuid4()), "sdk-ts", age_hours=1)  # maybe still in use
    mine = _transcript(hub, str(uuid.uuid4()), "cli")  # the owner's own terminal session
    memory = hub.settings.claude_dir / "projects" / "-work" / "memory"
    memory.mkdir()
    loose = hub.tool_images.root / "deadbeef-00000000000000000000.png"
    loose.write_bytes(b"x" * 50)
    os.utime(loose, (time.time() - 7200, time.time() - 7200))
    new_file = hub.attachments.root / "0badc0de-just-uploaded.txt"  # a chat being created right now
    new_file.write_text("new")
    # Older snapshots of the chat are dropped as new ones are written.
    assert await hub.pool.fetchval("select count(*) from events where session_id = $1 and kind = 'session'", sid) == 1
    shown_before = [(e["kind"], e["payload"].get("tool_call_id")) for e in await hub.events(0, sid) if e["kind"] != "tool"]
    tools_before = [e for e in await hub.events(0, sid) if e["kind"] == "tool"]
    assert len(tools_before) > 1

    app = create_app(hub, TOKEN, lifespan_hub=False)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        report = (await client.get("/api/storage", headers=headers)).json()
        chat = next(c for c in report["chats"] if c["id"] == str(sid))
        assert chat["tool_images"]["count"] == 1 and chat["transcript"]["count"] == 1
        assert chat["history"]["repeats"] == len(tools_before) - 1
        assert report["reclaimable"]["loose_files"] == {"bytes": 50, "count": 1}
        assert report["reclaimable"]["loose_transcripts"]["count"] == 1
        freed = (await client.post("/api/storage/clean", headers=headers)).json()
    assert freed["files_bytes"] == 50 and freed["transcripts_bytes"] > 0 and freed["repeats_bytes"] > 0
    assert not loose.exists() and not left.exists() and new_file.exists()
    assert linked.exists() and fresh.exists() and mine.exists() and memory.exists()
    events = await hub.events(0, sid)
    tools = [e for e in events if e["kind"] == "tool"]
    assert len(tools) == 1 and tools[0]["seq"] == tools_before[0]["seq"]  # in its place, with its last state
    assert tools[0]["payload"] == tools_before[-1]["payload"]
    assert [(e["kind"], e["payload"].get("tool_call_id")) for e in events if e["kind"] != "tool"] == shown_before
    # The chat goes on in the same agent session.
    last = events[-1]["seq"]
    await hub.send(sid, "Say hello again")
    for _ in range(200):
        if any(e["kind"] == "turn_end" for e in await hub.events(last, sid)):
            break
        await asyncio.sleep(0.05)
    else:
        raise AssertionError("the chat did not go on")
    assert (await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)) == agent_session


async def test_trim_and_delete_free_a_chats_space(hub):
    session = await hub.create_session("screenshot please")
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    agent_session = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    transcript = _transcript(hub, agent_session, "sdk-ts")
    image = next(hub.tool_images.root.glob(f"{str(sid)[:8]}-*"))
    attachment = hub.attachments.root / f"{str(sid)[:8]}-notes.txt"
    attachment.write_text("notes")
    trimmed = await hub.trim_session(sid)
    assert trimmed["images_bytes"] > 0 and not image.exists()
    assert all("images" not in e["payload"] for e in await hub.events(0, sid) if e["kind"] == "tool")
    assert attachment.exists() and transcript.exists()  # the agent may read these again
    await hub.delete_session(sid)
    assert not attachment.exists() and not transcript.exists()


async def test_delete_chats_by_age_keeps_pinned_and_working_ones(hub):
    ids = {}
    for name in ("old", "old pinned", "recent", "old working"):
        session = await hub.create_session("slow" if name == "old working" else "Say hello")
        ids[name] = uuid.UUID(session["id"])
        if name != "old working":
            await wait_for(hub, ids[name], "turn_end")
    await wait_for(hub, ids["old working"], "text", lambda p: p["role"] == "thought")
    await hub.update_session(ids["old pinned"], {"pinned": True})
    for name in ("old", "old pinned", "old working"):
        await hub.pool.execute("update sessions set updated_at = now() - interval '10 days' where id = $1", ids[name])
    attachment = hub.attachments.root / f"{str(ids['old'])[:8]}-notes.txt"
    attachment.write_text("notes")

    app = create_app(hub, TOKEN, lifespan_hub=False)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test", headers=headers) as client:
        older = (await client.get("/api/sessions/old", params={"days": 7, "scope": "older"})).json()
        assert [c["id"] for c in older["chats"]] == [str(ids["old"])]
        assert older["kept"] == {"pinned": 1, "working": 1}
        recent = (await client.get("/api/sessions/old", params={"days": 7, "scope": "recent"})).json()
        assert [c["id"] for c in recent["chats"]] == [str(ids["recent"])]
        assert (await client.get("/api/sessions/old", params={"days": 0})).status_code == 422

        # Only what the preview showed and still matches goes: the recent chat is out of range, the pinned one stays.
        body = {"days": 7, "scope": "older", "ids": [str(ids["old"]), str(ids["recent"]), str(ids["old pinned"])]}
        done = (await client.post("/api/sessions/old/delete", json=body)).json()
        assert done == {"deleted": [str(ids["old"])], "count": 1, "skipped": 2}
    left = {s["id"] for s in await hub.list_sessions()}
    assert str(ids["old"]) not in left
    assert {str(ids["old pinned"]), str(ids["recent"]), str(ids["old working"])} <= left
    assert not attachment.exists()
    await hub.cancel(ids["old working"])


# chats that work for each other: side sessions, sub-agents, prompts to another chat


async def results(hub: Hub, sid) -> list[dict]:
    return [e["payload"] for e in await hub.events(0, sid) if e["kind"] == "user" and e["payload"].get("purpose") == "result"]


async def test_a_sub_agents_reply_is_steered_back_into_its_parent(hub):
    parent = await hub.create_session("slow")
    pid = uuid.UUID(parent["id"])
    await wait_for(hub, pid, "text", lambda p: p["role"] == "thought")
    child = await hub.create_session("quick job", parent_id=pid, returns=True)
    cid = uuid.UUID(child["id"])
    assert child["kind"] == "subagent" and child["parent_id"] == str(pid) and child["parent_title"]
    link = await wait_for(hub, pid, "link")
    assert link == {"kind": "subagent", "chat_id": str(cid), "title": "quick job", "returns": True}
    first = await wait_for(hub, cid, "user")
    assert first["chats"][0]["id"] == str(pid) and first["chats"][0]["relation"] == "this chat's parent"
    assert first["return_to"] == str(pid)
    result = await wait_for(hub, pid, "user", lambda p: p.get("purpose") == "result")
    assert "Hello world" in result["text"] and result["meta"]["from"] == str(cid) and result["meta"]["ok"]
    assert result["delivery"] == "steer"
    end = await wait_for(hub, pid, "turn_end")
    assert "steered=Sub-agent" in assistant_text(await hub.events(0, pid))
    assert end["stop_reason"] == "end_turn"
    assert (await hub.get_session(pid))["waiting_on"] == []
    links = await hub.links(pid)
    assert [c["id"] for c in links["children"]] == [str(cid)]
    assert (await hub.links(cid))["parent"]["id"] == str(pid)


async def test_a_side_session_sends_nothing_back_but_its_parent_hears_of_it(hub):
    parent = await hub.create_session("hi")
    pid = uuid.UUID(parent["id"])
    await wait_for(hub, pid, "turn_end")
    child = await hub.create_session("look around", parent_id=pid)
    cid = uuid.UUID(child["id"])
    assert child["kind"] == "side" and child["waiting_on"] == []
    await wait_for(hub, cid, "turn_end")
    await asyncio.sleep(0.4)
    assert await results(hub, pid) == []
    carry = await hub.pool.fetchval("select carry from sessions where id = $1", pid)
    assert "side session" in carry and str(cid) in carry
    # Turning it into a sub-agent later: its next reply goes back.
    await hub.update_session(cid, {"returns": True})
    await hub.send(cid, "and now?")
    result = await wait_for(hub, pid, "user", lambda p: p.get("purpose") == "result")
    assert result["meta"]["from"] == str(cid)


async def test_proposals_start_only_when_the_owner_sends_them(hub):
    idle = await hub.create_session("hi")
    iid = uuid.UUID(idle["id"])
    await wait_for(hub, iid, "turn_end")
    parent = await hub.create_session("slow")
    pid = uuid.UUID(parent["id"])
    await wait_for(hub, pid, "turn_start")
    with pytest.raises(Exception, match="running turn"):
        await hub.propose(iid, "new", "not asked")
    proposal = await hub.propose(None, "new", "Check the fares to Pune", title="Fares", returns=True)
    assert proposal["status"] == "pending" and proposal["session_id"] == str(pid)
    assert (await wait_for(hub, pid, "proposal"))["id"] == proposal["id"]
    assert [p["id"] for p in await hub.list_proposals()] == [proposal["id"]]
    sent = await hub.send_proposal(uuid.UUID(proposal["id"]), text="Check the fares to Pune, Friday", returns=False)
    assert sent["status"] == "sent"
    child = await hub.get_session(uuid.UUID(sent["result_id"]))
    assert child["title"] == "Fares" and child["parent_id"] == str(pid) and child["kind"] == "side"
    with pytest.raises(Exception, match="sent"):
        await hub.send_proposal(uuid.UUID(proposal["id"]))
    other = await hub.propose(pid, "new", "Something else")
    dismissed = await hub.dismiss_proposal(uuid.UUID(other["id"]))
    assert dismissed["status"] == "dismissed" and await hub.list_proposals() == []
    carry = await hub.pool.fetchval("select carry from sessions where id = $1", pid)
    assert "started your proposed side session" in carry and "dismissed" in carry


async def test_a_prompt_goes_only_to_an_attached_chat_and_its_reply_comes_back(hub):
    b = await hub.create_session("hi")
    bid = uuid.UUID(b["id"])
    stranger = await hub.create_session("hi there")
    await wait_for(hub, bid, "turn_end")
    a = await hub.create_session("slow", chats=[{"id": str(bid)}])
    aid = uuid.UUID(a["id"])
    await wait_for(hub, aid, "turn_start")
    with pytest.raises(Exception, match="attached"):
        await hub.propose(aid, "prompt", "psst", target_id=uuid.UUID(stranger["id"]))
    proposal = await hub.propose(aid, "prompt", "What did you find?", target_id=bid, returns=True)
    assert proposal["target_title"]
    await hub.send_proposal(uuid.UUID(proposal["id"]))
    asked = await wait_for(hub, bid, "user", lambda p: p.get("purpose") == "prompt")
    assert asked["text"].startswith('Message from Krewd chat "') and asked["return_to"] == str(aid)
    assert asked["delivery"] == "queue"
    result = await wait_for(hub, aid, "user", lambda p: p.get("purpose") == "result")
    assert result["meta"]["kind"] == "chat" and "Hello world" in result["text"]
    assert (await hub.get_session(aid))["waiting_on"] == []
    who = [m["from"] for m in await hub.transcript(bid)]
    assert any(w.startswith('Krewd chat "') for w in who)


async def test_stop_keeps_replies_from_other_chats(hub):
    parent = await hub.create_session("slow")
    pid = uuid.UUID(parent["id"])
    await wait_for(hub, pid, "turn_start")
    result = await hub.send(pid, "a reply from elsewhere", delivery="queue", purpose="result", meta={"from": "x"})
    mine = await hub.send(pid, "later", delivery="queue")
    await hub.cancel(pid)
    assert (await wait_for(hub, pid, "message", lambda p: p["id"] == mine["id"]))["state"] == "cancelled"
    started = await wait_for(hub, pid, "message", lambda p: p["id"] == result["id"], timeout=15)
    assert started["state"] == "delivered"


async def test_a_deleted_or_interrupted_sub_agent_tells_its_parent(hub):
    parent = await hub.create_session("hi")
    pid = uuid.UUID(parent["id"])
    await wait_for(hub, pid, "turn_end")
    gone = await hub.create_session("slow", parent_id=pid, returns=True)
    await wait_for(hub, uuid.UUID(gone["id"]), "turn_start")
    await hub.delete_session(uuid.UUID(gone["id"]))
    note = await wait_for(hub, pid, "user", lambda p: p.get("purpose") == "result")
    assert not note["meta"]["ok"] and "No reply comes back" in note["text"]
    cut = await hub.create_session("slow", parent_id=pid, returns=True)
    cid = uuid.UUID(cut["id"])
    await wait_for(hub, cid, "turn_start")
    assert (await hub.get_session(pid))["waiting_on"] == [str(cid)]
    await hub._recover()  # what a restart does to the turns it cut off
    note = await wait_for(hub, pid, "user", lambda p: p.get("purpose") == "result" and p["meta"]["from"] == str(cid))
    assert "interrupted by a Krewd restart" in note["text"]
    assert (await hub.get_session(pid))["waiting_on"] == []


async def test_agents_claim_their_tabs_and_other_chats_are_refused(hub, monkeypatch):
    from krewd_server import harness
    from krewd_server.hub import TabTaken

    a = uuid.UUID((await hub.create_session("slow"))["id"])
    b = uuid.UUID((await hub.create_session("slow"))["id"])
    await wait_for(hub, a, "turn_start")
    await wait_for(hub, b, "turn_start")
    for _ in range(100):  # the agent session is made once the turn starts
        agent_a, agent_b = hub.runtimes[a].agent_session, hub.runtimes[b].agent_session
        if agent_a and agent_b and hub.runtimes[a].running and hub.runtimes[b].running:
            break
        await asyncio.sleep(0.05)
    page = {"id": "T1", "url": "https://mail.example.com/draft", "title": "Draft"}
    # No navigation needed: browser-harness claims the tab before it clicks or types in it.
    assert (await hub.claim_tab(agent_a, "T1", page)) == {"session_id": str(a), "locked": True}
    assert hub.tab_busy("T1") == a
    assert (await hub.get_session(a))["browser_tab"]["url"] == "https://mail.example.com/draft"
    with pytest.raises(TabTaken):
        await hub.claim_tab(agent_b, "T1", page)
    # The guess from navigation does not hand a held tab to another running chat.
    assert await hub.browser_activity({"id": "T1", "url": "https://mail.example.com/sent", "title": "Sent"}) == a
    with pytest.raises(NotFound):
        await hub.claim_tab("not-a-krewd-agent", "T1", page)
    app = create_app(hub, TOKEN, lifespan_hub=False)
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        taken = await client.post("/api/browser/claim", json={"agent_session": agent_b, "tab_id": "T1"}, headers=headers)
        assert taken.status_code == 409 and "open your own with new_tab()" in taken.json()["detail"]
        assert (await client.post("/api/browser/claim", json={"agent_session": "x", "tab_id": "T1"},
                                  headers=headers)).status_code == 404
        mine = await client.post("/api/browser/claim", json={"agent_session": agent_b, "tab_id": "T2"}, headers=headers)
        assert mine.status_code == 200 and mine.json()["locked"] is True
        assert (await client.post("/api/browser/tabs/T1/input", json={"type": "key", "key": "Enter"},
                                  headers=headers)).status_code == 409
    # The lock lasts the turn: once A's turn ends, B may take the tab.
    await hub.cancel(a)
    await wait_for(hub, a, "turn_end")
    assert hub.tab_busy("T1") is None
    assert (await hub.claim_tab(agent_b, "T1", None))["session_id"] == str(b)
    # Owners are kept across a restart, for every tab, and forgotten when the tab closes.
    await hub.cancel(b)
    await wait_for(hub, b, "turn_end")
    again = Hub(hub.settings, hub.pool)
    await again.start()
    try:
        assert again.tab_owner["T1"] == b and again.tab_owner["T2"] == b
        await again.forget_tabs({"T2"})
        assert "T1" not in again.tab_owner
        await again.forget_tabs(closed="T2")
        assert await hub.pool.fetchval("select count(*) from browser_tabs") == 0
    finally:
        await again.close()
    # The agents load the hook from Krewd's browser-harness workspace.
    link = hub.harness_workspace / "agent_helpers.py"
    assert link.is_symlink() and link.resolve() == harness.HOOK


async def test_quiet_chats_lose_their_browser_harness_daemon(hub, monkeypatch):
    from krewd_server import harness

    sid = uuid.UUID((await hub.create_session("hello"))["id"])
    await wait_for(hub, sid, "turn_end")
    agent = await hub.pool.fetchval("select agent_session from sessions where id = $1", sid)
    mine, gone = harness.daemon_name(agent), "krewd-deleted-chat"
    monkeypatch.setattr(harness, "krewd_daemons", lambda: {mine, gone})
    stopped: list[str] = []

    async def stop(name):
        stopped.append(name)
        return True

    monkeypatch.setattr(harness, "stop_daemon", stop)
    assert await hub.sweep_harness() == [gone]  # this chat was active just now
    await hub.pool.execute("update sessions set updated_at = now() - interval '2 hours' where id = $1", sid)
    assert sorted(await hub.sweep_harness()) == sorted([gone, mine])
    stopped.clear()
    await hub.delete_session(sid)
    await asyncio.sleep(0.1)
    assert stopped == [mine]
