"""The agents' Brave, for the owner: list its tabs, watch one live, and tap, scroll or type into it.

Uses the Chrome DevTools Protocol on the loopback port the agents' browser listens on. A tab is
streamed with `Page.startScreencast` (JPEG frames of the visible viewport). Input goes in with
`Input.dispatchMouseEvent`, `Input.dispatchKeyEvent` and `Input.insertText`.

Typed text goes straight to the page. It is never logged, stored or shown to the agent as text, so
passwords and one-time codes can be entered from the phone without passing through the chat.
"""

from __future__ import annotations

import asyncio
import base64
import itertools
import json
import logging
import urllib.request
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import websockets

log = logging.getLogger("crew.browser")

SCREENCAST = {"format": "jpeg", "quality": 60, "maxWidth": 1080, "maxHeight": 1920, "everyNthFrame": 1}
KEEPALIVE_SECONDS = 10  # resend the last frame so idle streams stay open and dead ones are noticed
IDLE_CLOSE_SECONDS = 30
KEYS = {
    # key: (code, windowsVirtualKeyCode, text)
    "Enter": ("Enter", 13, "\r"),
    "Backspace": ("Backspace", 8, None),
    "Tab": ("Tab", 9, None),
    "Escape": ("Escape", 27, None),
    "ArrowUp": ("ArrowUp", 38, None),
    "ArrowDown": ("ArrowDown", 40, None),
    "ArrowLeft": ("ArrowLeft", 37, None),
    "ArrowRight": ("ArrowRight", 39, None),
    "PageDown": ("PageDown", 34, None),
    "PageUp": ("PageUp", 33, None),
}


class BrowserUnavailable(Exception):
    pass


class TabGone(Exception):
    pass


@dataclass
class Frame:
    jpeg: bytes
    width: float  # viewport size in CSS pixels, for mapping taps
    height: float
    number: int


@dataclass
class TabLink:
    """One DevTools connection to one tab, shared by its viewers and its input."""

    target_id: str
    ws: Any
    reader: asyncio.Task | None = None
    ids: itertools.count = field(default_factory=lambda: itertools.count(1))
    pending: dict[int, asyncio.Future] = field(default_factory=dict)
    frame: Frame | None = None
    changed: asyncio.Condition = field(default_factory=asyncio.Condition)
    viewers: int = 0
    casting: bool = False
    closed: bool = False
    idle_task: asyncio.Task | None = None

    async def call(self, method: str, params: dict[str, Any] | None = None, timeout: float = 10) -> dict[str, Any]:
        if self.closed:
            raise TabGone(self.target_id)
        msg_id = next(self.ids)
        future = asyncio.get_running_loop().create_future()
        self.pending[msg_id] = future
        await self.ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
        try:
            reply = await asyncio.wait_for(future, timeout)
        finally:
            self.pending.pop(msg_id, None)
        if "error" in reply:
            raise RuntimeError(reply["error"].get("message", "DevTools error"))
        return reply.get("result", {})

    async def read_loop(self) -> None:
        try:
            async for raw in self.ws:
                message = json.loads(raw)
                if "id" in message:
                    future = self.pending.get(message["id"])
                    if future and not future.done():
                        future.set_result(message)
                elif message.get("method") == "Page.screencastFrame":
                    params = message["params"]
                    meta = params.get("metadata") or {}
                    number = (self.frame.number + 1) if self.frame else 1
                    self.frame = Frame(base64.b64decode(params["data"]), meta.get("deviceWidth", 0),
                                       meta.get("deviceHeight", 0), number)
                    # Ack at once; viewers always take the newest frame, so a slow phone just skips frames.
                    asyncio.create_task(self._ack(params["sessionId"]))
                    async with self.changed:
                        self.changed.notify_all()
                elif message.get("method") in ("Inspector.detached", "Target.targetDestroyed"):
                    break
        except websockets.ConnectionClosed:
            pass
        finally:
            self.closed = True
            for future in self.pending.values():
                if not future.done():
                    future.set_exception(TabGone(self.target_id))
            async with self.changed:
                self.changed.notify_all()

    async def _ack(self, session_id: int) -> None:
        try:
            await self.call("Page.screencastFrameAck", {"sessionId": session_id})
        except Exception:
            pass


class Browser:
    def __init__(self, cdp_port: int):
        self.port = cdp_port
        self.links: dict[str, TabLink] = {}
        self.lock = asyncio.Lock()

    def _http(self, path: str) -> Any:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=3) as r:
                body = r.read()
        except OSError as exc:
            raise BrowserUnavailable(f"the agents' browser is not running (DevTools port {self.port})") from exc
        return json.loads(body) if body.strip().startswith((b"{", b"[")) else body.decode()

    async def tabs(self) -> list[dict[str, Any]]:
        targets = await asyncio.to_thread(self._http, "/json/list")
        return [
            {"id": t["id"], "title": t.get("title") or t.get("url", ""), "url": t.get("url", "")}
            for t in targets
            if t.get("type") == "page" and not t.get("url", "").startswith("devtools://")
        ]

    async def activate(self, target_id: str) -> None:
        """Bring the tab to the front, so it paints (background tabs send no screencast frames)."""
        await asyncio.to_thread(self._http, f"/json/activate/{target_id}")

    async def link(self, target_id: str) -> TabLink:
        async with self.lock:
            current = self.links.get(target_id)
            if current and not current.closed:
                return current
            if target_id not in {t["id"] for t in await self.tabs()}:
                raise TabGone(target_id)
            ws = await websockets.connect(
                f"ws://127.0.0.1:{self.port}/devtools/page/{target_id}", max_size=None, open_timeout=5
            )
            link = TabLink(target_id, ws)
            link.reader = asyncio.create_task(link.read_loop())
            self.links[target_id] = link
            return link

    async def stream(self, target_id: str) -> AsyncIterator[Frame]:
        await self.activate(target_id)
        link = await self.link(target_id)
        link.viewers += 1
        if link.idle_task:
            link.idle_task.cancel()
            link.idle_task = None
        try:
            if not link.casting:
                await link.call("Page.startScreencast", SCREENCAST)
                link.casting = True
            last = 0
            while not link.closed:
                async with link.changed:
                    try:
                        await asyncio.wait_for(
                            link.changed.wait_for(lambda: link.closed or (link.frame and link.frame.number != last)),
                            KEEPALIVE_SECONDS,
                        )
                    except TimeoutError:
                        pass
                if link.closed:
                    break
                if link.frame:
                    last = link.frame.number
                    yield link.frame
        finally:
            link.viewers -= 1
            if link.viewers == 0 and not link.closed:
                link.idle_task = asyncio.create_task(self._close_when_idle(link))

    async def _close_when_idle(self, link: TabLink) -> None:
        await asyncio.sleep(IDLE_CLOSE_SECONDS)
        if link.viewers:
            return
        try:
            if link.casting:
                await link.call("Page.stopScreencast")
        except Exception:
            pass
        link.casting = False
        await link.ws.close()

    async def input(self, target_id: str, event: dict[str, Any]) -> None:
        link = await self.link(target_id)
        kind = event.get("type")
        size = link.frame or Frame(b"", 0, 0, 0)
        if kind in ("tap", "scroll") and not (size.width and size.height):
            # No frame yet: ask for the viewport size.
            metrics = await link.call("Page.getLayoutMetrics")
            view = metrics.get("cssVisualViewport") or metrics.get("layoutViewport") or {}
            size = Frame(b"", view.get("clientWidth", 0), view.get("clientHeight", 0), 0)
        if kind == "tap":
            x, y = float(event["x"]) * size.width, float(event["y"]) * size.height
            await link.call("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": x, "y": y})
            for phase in ("mousePressed", "mouseReleased"):
                await link.call("Input.dispatchMouseEvent",
                                {"type": phase, "x": x, "y": y, "button": "left", "buttons": 1, "clickCount": 1})
        elif kind == "scroll":
            x, y = float(event.get("x", 0.5)) * size.width, float(event.get("y", 0.5)) * size.height
            await link.call("Input.dispatchMouseEvent", {
                "type": "mouseWheel", "x": x, "y": y,
                "deltaX": float(event.get("dx", 0)) * size.width, "deltaY": float(event.get("dy", 0)) * size.height,
            })
        elif kind == "text":
            await link.call("Input.insertText", {"text": str(event.get("text", ""))})
        elif kind == "key":
            key = str(event.get("key"))
            if key not in KEYS:
                raise ValueError(f"unknown key {key}")
            code, vk, text = KEYS[key]
            down = {"type": "keyDown" if text else "rawKeyDown", "key": key, "code": code,
                    "windowsVirtualKeyCode": vk, "nativeVirtualKeyCode": vk}
            if text:
                down["text"] = text
            await link.call("Input.dispatchKeyEvent", down)
            await link.call("Input.dispatchKeyEvent", {"type": "keyUp", "key": key, "code": code,
                                                       "windowsVirtualKeyCode": vk, "nativeVirtualKeyCode": vk})
        elif kind == "navigate":
            action = event.get("action")
            if action == "reload":
                await link.call("Page.reload")
            elif action in ("back", "forward"):
                history = await link.call("Page.getNavigationHistory")
                index = history["currentIndex"] + (-1 if action == "back" else 1)
                entries = history.get("entries", [])
                if 0 <= index < len(entries):
                    await link.call("Page.navigateToHistoryEntry", {"entryId": entries[index]["id"]})
            elif event.get("url"):
                await link.call("Page.navigate", {"url": str(event["url"])})
            else:
                raise ValueError("navigate needs action back, forward, reload, or a url")
        else:
            raise ValueError(f"unknown input type {kind}")

    async def close(self) -> None:
        for link in list(self.links.values()):
            if not link.closed:
                await link.ws.close()
