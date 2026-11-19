"""The vertical view: the phone's last size for an agent's "vertical", and it outlives an idle connection."""

from __future__ import annotations

import asyncio

from krewd_server import browser as browser_mod
from krewd_server.browser import DEFAULT_PHONE_VIEW, Browser


class FakeLink:
    def __init__(self, target_id: str):
        self.target_id = target_id
        self.emulated = False
        self.viewers = 0
        self.casting = None
        self.calls: list[tuple[str, dict]] = []
        self.ws = self
        self.closed = False

    async def call(self, method, params=None, timeout=None):
        self.calls.append((method, params or {}))
        return {}

    async def close(self):
        self.closed = True


def make(monkeypatch) -> tuple[Browser, FakeLink]:
    b = Browser(1)
    link = FakeLink("T1")

    async def fake_link(target_id):
        return link

    monkeypatch.setattr(b, "link", fake_link)
    monkeypatch.setattr(browser_mod, "IDLE_CLOSE_SECONDS", 0)
    return b, link


async def test_agent_vertical_uses_the_phones_last_size(monkeypatch):
    b, link = make(monkeypatch)
    await b.set_view("T1", "vertical")
    assert b.views["T1"] == DEFAULT_PHONE_VIEW
    await b.set_view("T1", "vertical", 390, 844, 3)
    await b.set_view("T1", "horizontal")
    assert "T1" not in b.views and not link.emulated
    await b.set_view("T1", "vertical")  # an agent: no size given
    assert b.views["T1"] == {"width": 390, "height": 844, "scale": 3}
    assert link.calls[-1][1]["width"] == 390 and link.emulated


async def test_vertical_tab_keeps_its_connection_when_nobody_watches(monkeypatch):
    b, link = make(monkeypatch)
    await b.set_view("T1", "vertical", 400, 800, 2)
    await b._close_when_idle(link)
    assert not link.closed  # the override lives on this connection
    await b.set_view("T1", "horizontal")
    await b._close_when_idle(link)
    assert link.closed
    await asyncio.sleep(0)
