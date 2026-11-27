"""People's own browsers (browsers.py): the registry, tab routing across browsers, and a chat's choice."""

from __future__ import annotations

import uuid

import pytest

import krewd_server.browsers as browsers_module
from krewd_server.browser import BrowserUnavailable, TabGone
from krewd_server.browsers import MAIN, BrowserError, Fleet, valid_name

from tests.test_security import SETUP, app, hub, local  # noqa: F401 - fixtures


class PortBrowser:
    """A browser on one port: tab ids are made from the port; port 9300 is never running."""

    def __init__(self, port: int) -> None:
        self.port = port
        self.opened: list[str] = []
        self.closed = False

    def _http(self, path: str, method: str = "GET"):
        if self.port == 9300:
            raise BrowserUnavailable("not running")
        return {}

    async def tabs(self):
        self._http("/json/list")
        return [{"id": f"tab-{self.port}", "title": "t", "url": "https://example.com", "view": "horizontal"}]

    async def new_tab(self, url=None):
        self.opened.append(url)
        return {"id": f"new-{self.port}", "title": url, "url": url}

    async def page_url(self, target_id):
        return "https://example.com"

    async def close(self):
        self.closed = True


@pytest.fixture
def fleet(tmp_path, monkeypatch):
    monkeypatch.setattr(browsers_module, "Browser", PortBrowser)
    monkeypatch.setattr(browsers_module, "port_free", lambda port: True)
    return Fleet(9222, tmp_path, tmp_path / "agent-brave", idle_minutes=30)


def test_names_are_short_lowercase():
    assert valid_name(" Alice ") == "alice"
    for bad in ("", "1abc", "a/b", "../x", "a" * 33, "with space"):
        with pytest.raises(BrowserError):
            valid_name(bad)


def test_add_writes_its_own_profile_and_port(fleet, tmp_path):
    info = fleet.add("alice")
    assert info["name"] == "alice" and info["port"] == 9223 and info["tmux"] == "agent-brave-alice"
    assert info["profile"] == str(tmp_path / "agent-brave-alice") and (tmp_path / "agent-brave-alice").is_dir()
    env = (tmp_path / "browsers" / "alice.env").read_text()
    assert "KREWD_CDP_PORT=9223" in env and "KREWD_BROWSER_NAME=alice" in env
    assert oct((tmp_path / "browsers" / "alice.env").stat().st_mode & 0o777) == "0o600"
    assert fleet.add("alice")["port"] == 9223  # adding again changes nothing
    assert fleet.add("bob")["port"] == 9224
    assert fleet.names() == [MAIN, "alice", "bob"]
    with pytest.raises(BrowserError):
        fleet.add("main")
    # A new server reads the same registry.
    again = Fleet(9222, tmp_path, tmp_path / "agent-brave")
    assert again.names() == [MAIN, "alice", "bob"] and again.get("bob").port == 9224
    with pytest.raises(BrowserError):
        again.get("nobody")


async def test_tabs_from_every_running_browser_and_calls_go_to_the_tab_s_browser(fleet):
    fleet.add("alice")
    tabs = await fleet.tabs()
    assert {(t["id"], t["browser"]) for t in tabs} == {("tab-9222", MAIN), ("tab-9223", "alice")}
    assert (await fleet.browser_of("tab-9223")).port == 9223
    assert (await fleet.browser_of("tab-9222")) is fleet.main
    with pytest.raises(TabGone):
        await fleet.browser_of("no-such-tab")
    assert "alice" in fleet.used  # routing a call to a person's browser counts as using it


async def test_a_stopped_person_s_browser_is_left_out_and_main_down_alone_is_an_error(fleet, monkeypatch):
    fleet.add("alice")
    fleet.extra["alice"].port = 9300  # not running
    assert [t["browser"] for t in await fleet.tabs()] == [MAIN]
    fleet.main.port = 9300
    with pytest.raises(BrowserUnavailable):
        await fleet.tabs()


async def test_ensure_starts_the_unit_and_stop_stops_it(fleet, monkeypatch):
    fleet.add("alice")
    calls = []
    state = {"up": False}

    async def systemctl(*args):
        calls.append(args)
        if args[0] == "start":
            state["up"] = True
        if args[0] == "stop":
            state["up"] = False
        return 0, ""

    async def running(name):
        return name == MAIN or state["up"]

    monkeypatch.setattr(fleet, "_systemctl", systemctl)
    monkeypatch.setattr(fleet, "running", running)
    assert (await fleet.ensure(MAIN))["port"] == 9222 and calls == []
    info = await fleet.ensure("alice")
    assert info["cdp_url"] == "http://127.0.0.1:9223"
    assert ("start", "krewd-browser@alice.service") in calls
    calls.clear()
    await fleet.ensure("alice")  # already running: nothing started
    assert calls == []
    await fleet.stop("alice")
    assert ("stop", "krewd-browser@alice.service") in calls
    with pytest.raises(BrowserError):
        await fleet.stop(MAIN)


async def test_idle_browsers_are_the_ones_nobody_used_and_no_turn_holds(fleet, monkeypatch):
    fleet.add("alice")

    async def running(name):
        return True

    monkeypatch.setattr(fleet, "running", running)
    await fleet.tabs()
    fleet.used["alice"] = 0  # long ago
    assert await fleet.idle(lambda tab: None) == ["alice"]
    assert await fleet.idle(lambda tab: "chat" if tab == "tab-9223" else None) == []  # a turn holds its tab
    fleet.touch("alice")
    assert await fleet.idle(lambda tab: None) == []
    fleet.idle_seconds = 0  # KREWD_BROWSER_IDLE_MINUTES=0: never stopped
    fleet.used["alice"] = 0
    assert await fleet.idle(lambda tab: None) == []


async def test_a_chat_uses_a_person_s_browser_until_it_switches_back(hub, app, monkeypatch):
    async def ensure(self, name):
        return self.info(name)

    async def running(self, name):
        return True

    monkeypatch.setattr(Fleet, "ensure", ensure)
    monkeypatch.setattr(Fleet, "running", running)
    monkeypatch.setattr(browsers_module, "port_free", lambda port: True)
    token = hub.settings.token_file.read_text().strip()
    chat = await hub.create_session("for alice", "fake")
    agent_session = "agent-" + uuid.uuid4().hex
    await hub.pool.execute("update sessions set agent_session = $2 where id = $1", uuid.UUID(chat["id"]), agent_session)
    async with local(app, token) as c:
        # Not added yet: refused, and the chat stays on main.
        r = await c.post("/api/browser/use", json={"agent_session": agent_session, "browser": "alice"})
        assert r.status_code == 404, r.text
        assert (await c.get(f"/api/browser/session?agent_session={agent_session}")).json()["name"] == MAIN
        added = await c.post("/api/browsers", json={"name": "alice"})
        assert added.status_code == 201 and added.json()["port"] == 9223
        assert (await c.post("/api/browsers", json={"name": "../etc"})).status_code == 422
        r = await c.post("/api/browser/use", json={"agent_session": agent_session, "browser": "alice"})
        assert r.status_code == 200 and r.json()["session_id"] == chat["id"], r.text
        got = (await c.get(f"/api/browser/session?agent_session={agent_session}")).json()
        assert got["name"] == "alice" and got["cdp_url"] == "http://127.0.0.1:9223"
        # An agent session Krewd does not know gets main.
        assert (await c.get("/api/browser/session?agent_session=someone-else")).json()["name"] == MAIN
        listed = (await c.get("/api/browsers")).json()["browsers"]
        assert [b["name"] for b in listed] == [MAIN, "alice"]
        r = await c.post("/api/browser/use", json={"session_id": chat["id"], "browser": "main"})
        assert r.status_code == 200
        assert (await c.get(f"/api/browser/session?agent_session={agent_session}")).json()["name"] == MAIN
