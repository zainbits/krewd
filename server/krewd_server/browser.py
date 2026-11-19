"""The agents' Brave, for the owner: list its tabs, watch one live, and tap, scroll or type into it.

Uses the Chrome DevTools Protocol on the loopback port the agents' browser listens on. A tab is
streamed with `Page.startScreencast` (JPEG frames of the visible viewport). Input goes in with
`Input.dispatchMouseEvent`, `Input.dispatchKeyEvent` and `Input.insertText`.

The "vertical" view sizes the page to the phone with `Emulation.setDeviceMetricsOverride`. That
override belongs to Krewd's DevTools connection, which Krewd keeps open while a tab is vertical (also
when nobody watches it), so the tab keeps the last view it was given: an agent that starts using it
gets that layout, and switches it itself (`krewd browser view`) when it needs the other one.

Typed text goes straight to the page. It is never logged, stored or shown to the agent as text, so
passwords and one-time codes can be entered from the phone without passing through the chat.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import itertools
import json
import logging
import time
import urllib.parse
import urllib.request
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import websockets

log = logging.getLogger("krewd.browser")

# Full 1080p in both, so text stays sharp when the phone zooms in. Standard: lighter JPEG, at most 30 fps
# per viewer; high: near-lossless JPEG at Brave's full 60. Brave always sends every frame (everyNthFrame 2
# would drop the only frame a still page paints); the cap is applied per viewer, newest frame first.
SCREENCAST = {
    "standard": {"format": "jpeg", "quality": 50, "maxWidth": 1920, "maxHeight": 1920, "everyNthFrame": 1},
    "high": {"format": "jpeg", "quality": 85, "maxWidth": 1920, "maxHeight": 1920, "everyNthFrame": 1},
}
MAX_FPS = {"standard": 30, "high": 60}
KEEPALIVE_SECONDS = 10  # resend the last frame so idle streams stay open and dead ones are noticed
IDLE_CLOSE_SECONDS = 30
# The vertical view before the phone has asked for one (a common phone screen in CSS pixels).
DEFAULT_PHONE_VIEW = {"width": 412, "height": 915, "scale": 2.625}
OWNER_GRACE_SECONDS = 4  # a tab the owner just used is not taken for an agent's
# The Krewd location extension (deploy/fixed-location). Its manifest carries a fixed public key, so its id is the
# same on every machine. Its set.html page reads or changes the location it answers pages with.
LOCATION_EXTENSION = "jkjeibbbekignlhegjpgjaiocpdpdgkb"
LOCATION_PAGE = f"chrome-extension://{LOCATION_EXTENSION}/set.html"
LOCATION_PAGE_SECONDS = 6
BUTTONS = {"left": 1, "right": 2, "middle": 4}
FOCUS_JS = """(() => {
  let e = document.activeElement;
  try { while (e && e.tagName === 'IFRAME' && e.contentDocument) e = e.contentDocument.activeElement; } catch (_) {}
  while (e && e.shadowRoot && e.shadowRoot.activeElement) e = e.shadowRoot.activeElement;
  if (!e) return {editable: false, password: false};
  const tag = (e.tagName || '').toLowerCase(), type = (e.type || '').toLowerCase();
  const plain = ['button','checkbox','radio','submit','reset','file','image','range','color','hidden'];
  const editable = e.isContentEditable || tag === 'textarea' || (tag === 'input' && !plain.includes(type));
  return {editable: !!editable, password: type === 'password'};
})()"""
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
    "Delete": ("Delete", 46, None),
    "Home": ("Home", 36, None),
    "End": ("End", 35, None),
    "Space": ("Space", 32, " "),
}
MODIFIERS = {"alt": 1, "ctrl": 2, "meta": 4, "shift": 8}  # CDP modifier bits


def key_info(key: str) -> tuple[str, int, str | None]:
    """(code, windowsVirtualKeyCode, text) for a named key or a single letter or digit (for shortcuts)."""
    if key in KEYS:
        return KEYS[key]
    if len(key) == 1 and key.isascii() and key.isalnum():
        upper = key.upper()
        return (f"Key{upper}" if key.isalpha() else f"Digit{key}", ord(upper), key)
    raise ValueError(f"unknown key {key}")


# Finds the field for a secret: the agent's selector, or the visible field of that kind (a username field is the
# text or email field just before the password field). Returns the element, or null.
FIND_FIELD = r"""(kind, selector) => {
  const visible = (el) => { const r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden' && !el.disabled && !el.readOnly; };
  if (selector) return document.querySelector(selector);
  const inputs = [...document.querySelectorAll('input')].filter(visible);
  const password = inputs.find(i => i.type === 'password');
  if (kind === 'password') return password || null;
  if (kind === 'otp') return inputs.find(i => i.autocomplete === 'one-time-code') || inputs.find(i => /otp|code|token|pin/i.test(i.name + ' ' + i.id + ' ' + (i.placeholder || ''))) || inputs.find(i => ['text', 'tel', 'number'].includes(i.type)) || null;
  const texty = inputs.filter(i => ['text', 'email', 'tel', ''].includes(i.type));
  const named = texty.find(i => /user|email|login|mobile|phone/i.test(i.autocomplete + ' ' + i.name + ' ' + i.id + ' ' + i.type));
  if (password) { const before = texty.filter(i => i.compareDocumentPosition(password) & Node.DOCUMENT_POSITION_FOLLOWING); if (named && before.includes(named)) return named; if (before.length) return before[before.length - 1]; }
  return named || texty[0] || null;
}"""
# Focuses a field and empties it, so the typed value replaces what was there.
PREPARE_FIELD = r"""function () {
  this.scrollIntoView({block: 'center'}); this.focus();
  if (this.value) { const set = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(this), 'value'); set && set.set ? set.set.call(this, '') : (this.value = ''); this.dispatchEvent(new Event('input', {bubbles: true})); }
}"""


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
    emulated: bool = False  # true while the phone-sized (vertical) view is on
    scroll_y: float = 0  # the page's scroll position and height in CSS pixels, for the phone's scroll bar
    content_height: float = 0


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
    casting: str | None = None  # the SCREENCAST profile in use
    closed: bool = False
    idle_task: asyncio.Task | None = None
    emulated: bool = False
    scroll_y: float = 0
    content_height: float = 0
    metrics_at: float = 0

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
                                       meta.get("deviceHeight", 0), number, self.emulated,
                                       meta.get("scrollOffsetY", self.scroll_y) or 0, self.content_height)
                    self.scroll_y = self.frame.scroll_y
                    if time.monotonic() - self.metrics_at > 1:
                        self.metrics_at = time.monotonic()
                        asyncio.create_task(self._metrics())
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

    async def _metrics(self) -> None:
        try:
            metrics = await self.call("Page.getLayoutMetrics", timeout=3)
            self.content_height = (metrics.get("cssContentSize") or metrics.get("contentSize") or {}).get("height", 0)
        except Exception:
            pass

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
        self.touched: dict[str, float] = {}  # tab id -> when the owner last sent it input
        # The vertical view per tab ({width, height, scale}), set from the phone or by an agent. Kept until
        # someone switches the tab back to horizontal (or it closes), and across reconnects.
        self.views: dict[str, dict[str, float]] = {}
        # The phone's screen from its last vertical request: what an agent's "vertical" uses.
        self.phone_view: dict[str, float] = dict(DEFAULT_PHONE_VIEW)
        self.location_lock = asyncio.Lock()
        self.tasks: set[asyncio.Task] = set()

    def _http(self, path: str, method: str = "GET") -> Any:
        try:
            request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method)
            with urllib.request.urlopen(request, timeout=3) as r:
                body = r.read()
        except OSError as exc:
            raise BrowserUnavailable(f"the agents' browser is not running (DevTools port {self.port})") from exc
        return json.loads(body) if body.strip().startswith((b"{", b"[")) else body.decode()

    async def tabs(self) -> list[dict[str, Any]]:
        targets = await asyncio.to_thread(self._http, "/json/list")
        return [
            {"id": t["id"], "title": t.get("title") or t.get("url", ""), "url": t.get("url", ""),
             "view": "vertical" if t["id"] in self.views else "horizontal"}
            for t in targets
            if t.get("type") == "page" and not hidden_url(t.get("url", ""))
        ]

    async def new_tab(self, url: str | None = None) -> dict[str, Any]:
        quoted = urllib.parse.quote(url or "about:blank", safe=":/?&=#%+@,;~")
        target = await asyncio.to_thread(self._http, f"/json/new?{quoted}", "PUT")
        self.touched[target["id"]] = time.monotonic()
        return {"id": target["id"], "title": target.get("title") or target.get("url", ""), "url": target.get("url", "")}

    async def close_tab(self, target_id: str) -> None:
        if target_id not in {t["id"] for t in await self.tabs()}:
            raise TabGone(target_id)
        await asyncio.to_thread(self._http, f"/json/close/{target_id}")

    async def set_view(self, target_id: str, mode: str, width: int = 0, height: int = 0, scale: float = 0) -> None:
        """"vertical": lay the page out for the phone's screen (CSS pixels; none given: the phone's last size);
        "horizontal": the normal window."""
        if mode == "vertical":
            if not (width or height or scale):
                view = dict(self.phone_view)
            elif 200 <= width <= 1500 and 200 <= height <= 3000 and 0.5 <= scale <= 5:
                view = {"width": int(width), "height": int(height), "scale": scale}
                self.phone_view = dict(view)
            else:
                raise ValueError("vertical needs width 200..1500, height 200..3000 and scale 0.5..5")
            self.views[target_id] = view
            await self._emulate(await self.link(target_id))
        elif mode == "horizontal":
            self.views.pop(target_id, None)
            await self._emulate(await self.link(target_id))
        else:
            raise ValueError("mode is vertical or horizontal")
        self.touched[target_id] = time.monotonic()

    async def _emulate(self, link: TabLink) -> None:
        """Make the tab's layout match the owner's choice in self.views."""
        view = self.views.get(link.target_id)
        if view:
            await link.call("Emulation.setDeviceMetricsOverride",
                            {"width": view["width"], "height": view["height"], "deviceScaleFactor": view["scale"], "mobile": True})
            link.emulated = True
        elif link.emulated:
            await link.call("Emulation.clearDeviceMetricsOverride")
            link.emulated = False

    async def watch(self, on_activity, on_connect=None, on_closed=None) -> None:
        """Follow the browser's tabs; report a tab that opens or navigates while the owner is not using it.

        `on_activity({id, url, title})` returns the session it was linked to, or None. The tab keeps its view
        (the agent switches it when it needs to). `on_connect()` runs
        each time the browser is reached (at start and after Brave restarts), without holding up the watch.
        `on_closed(tab_id)` runs when a tab closes.
        """
        while True:
            try:
                known = {t["id"]: t["url"] for t in await self.tabs()}
                version = await asyncio.to_thread(self._http, "/json/version")
                async with websockets.connect(version["webSocketDebuggerUrl"], max_size=None, open_timeout=5) as ws:
                    await ws.send(json.dumps({"id": 1, "method": "Target.setDiscoverTargets", "params": {"discover": True}}))
                    if on_connect:
                        task = asyncio.create_task(on_connect())
                        self.tasks.add(task)
                        task.add_done_callback(self.tasks.discard)
                    async for raw in ws:
                        message = json.loads(raw)
                        method = message.get("method")
                        params = message.get("params") or {}
                        if method == "Target.targetDestroyed":
                            known.pop(params.get("targetId"), None)
                            self.views.pop(params.get("targetId"), None)
                            if on_closed and params.get("targetId"):
                                with contextlib.suppress(Exception):
                                    await on_closed(params["targetId"])
                            continue
                        if method not in ("Target.targetCreated", "Target.targetInfoChanged"):
                            continue
                        info = params.get("targetInfo") or {}
                        url = info.get("url", "")
                        if info.get("type") != "page" or hidden_url(url):
                            continue
                        tab_id = info["targetId"]
                        if known.get(tab_id) == url:
                            continue
                        known[tab_id] = url
                        if time.monotonic() - self.touched.get(tab_id, 0) < OWNER_GRACE_SECONDS:
                            continue
                        await on_activity({"id": tab_id, "url": url, "title": info.get("title") or url})
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                log.debug("browser watch: %s", exc)
            await asyncio.sleep(5)

    async def location(self, value: dict[str, float] | None | str = "read") -> dict[str, float] | None:
        """Read ("read") or set ({lat, lon, accuracy}, or None to clear) the location extension's location.

        Opens the extension's page in a background tab (never brought to the front), reads its answer from the
        page title and closes the tab. Returns the location the extension now holds.
        """
        fragment = "read" if value == "read" else json.dumps(value)
        url = f"{LOCATION_PAGE}#{urllib.parse.quote(fragment, safe='')}"
        async with self.location_lock:
            version = await asyncio.to_thread(self._http, "/json/version")
            async with websockets.connect(version["webSocketDebuggerUrl"], max_size=None, open_timeout=5) as ws:
                ids = itertools.count(1)

                async def call(method: str, params: dict[str, Any]) -> dict[str, Any]:
                    msg_id = next(ids)
                    await ws.send(json.dumps({"id": msg_id, "method": method, "params": params}))
                    while True:
                        message = json.loads(await asyncio.wait_for(ws.recv(), 5))
                        if message.get("id") == msg_id:
                            if "error" in message:
                                raise RuntimeError(message["error"].get("message", "DevTools error"))
                            return message.get("result", {})

                target = (await call("Target.createTarget", {"url": url, "background": True}))["targetId"]
                self.touched[target] = time.monotonic()  # not an agent's tab
                try:
                    deadline = time.monotonic() + LOCATION_PAGE_SECONDS
                    while time.monotonic() < deadline:
                        title = (await call("Target.getTargetInfo", {"targetId": target}))["targetInfo"].get("title", "")
                        if title.startswith("krewd-location:"):
                            answer = json.loads(title.removeprefix("krewd-location:"))
                            if not answer.get("ok"):
                                raise RuntimeError("the location extension refused the location")
                            return answer.get("location")
                        await asyncio.sleep(0.1)
                    raise RuntimeError("the location extension did not answer (is it loaded? see run-browser.sh)")
                finally:
                    with contextlib.suppress(Exception):
                        await call("Target.closeTarget", {"targetId": target})

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

    async def stream(self, target_id: str, quality: str = "standard") -> AsyncIterator[Frame]:
        """Frames of a tab. Viewers of one tab share its screencast: the newest viewer's quality applies."""
        await self.activate(target_id)
        link = await self.link(target_id)
        link.viewers += 1
        if link.idle_task:
            link.idle_task.cancel()
            link.idle_task = None
        try:
            if self.views.get(target_id) and not link.emulated:
                await self._emulate(link)  # the owner's vertical view, after a reconnect
            if link.casting != quality:
                if link.casting:
                    await link.call("Page.stopScreencast")
                await link.call("Page.startScreencast", SCREENCAST[quality])
                link.casting = quality
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
                    sent = time.monotonic()
                    yield link.frame
                    # The cap: wait out the rest of the frame interval; the newest frame goes next.
                    await asyncio.sleep(max(0.0, 1 / MAX_FPS[quality] - (time.monotonic() - sent)))
        finally:
            link.viewers -= 1
            if link.viewers == 0 and not link.closed:
                link.idle_task = asyncio.create_task(self._close_when_idle(link))

    async def snapshot(self, target_id: str, width: int = 360) -> bytes:
        """A small JPEG of a tab for the phone's task cards: one screenshot of the visible viewport, scaled to
        [width] pixels, without bringing the tab to the front. The connection closes once snapshots stop."""
        link = await self.link(target_id)
        try:
            metrics = await link.call("Page.getLayoutMetrics")
            view = metrics.get("cssVisualViewport") or metrics.get("visualViewport") or {}
            w = float(view.get("clientWidth") or 1280)
            h = float(view.get("clientHeight") or 720)
            clip = {
                "x": float(view.get("pageX") or 0),
                "y": float(view.get("pageY") or 0),
                "width": w,
                "height": h,
                "scale": max(0.05, min(1.0, width / w)),
            }
            shot = await link.call(
                "Page.captureScreenshot",
                {"format": "jpeg", "quality": 55, "optimizeForSpeed": True, "clip": clip},
                timeout=8,
            )
        finally:
            if link.viewers == 0 and not link.closed:
                if link.idle_task:
                    link.idle_task.cancel()
                link.idle_task = asyncio.create_task(self._close_when_idle(link))
        return base64.b64decode(shot["data"])

    async def _close_when_idle(self, link: TabLink) -> None:
        await asyncio.sleep(IDLE_CLOSE_SECONDS)
        if link.viewers:
            return
        try:
            if link.casting:
                await link.call("Page.stopScreencast")
        except Exception:
            pass
        link.casting = None
        # A vertical tab keeps its connection: the view is this connection's override and would end with it.
        if self.views.get(link.target_id) and link.emulated:
            return
        await link.ws.close()

    async def input(self, target_id: str, event: dict[str, Any]) -> dict[str, Any]:
        """Returns {editable, password} after a click, so the phone can open its keyboard for a text field."""
        link = await self.link(target_id)
        self.touched[target_id] = time.monotonic()
        kind = event.get("type")
        size = link.frame or Frame(b"", 0, 0, 0)
        if kind in ("tap", "scroll", "mouse") and not (size.width and size.height):
            # No frame yet: ask for the viewport size.
            metrics = await link.call("Page.getLayoutMetrics")
            view = metrics.get("cssVisualViewport") or metrics.get("layoutViewport") or {}
            size = Frame(b"", view.get("clientWidth", 0), view.get("clientHeight", 0), 0)
        if kind == "mouse":
            return await self._mouse(link, event, float(event["x"]) * size.width, float(event["y"]) * size.height)
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
            code, vk, text = key_info(key)
            unknown = set(event.get("modifiers") or []) - MODIFIERS.keys()
            if unknown:
                raise ValueError(f"unknown modifier {sorted(unknown)[0]}")
            mask = sum(MODIFIERS[m] for m in set(event.get("modifiers") or []))
            if mask & ~MODIFIERS["shift"]:
                text = None  # Ctrl, Alt or Meta make it a shortcut, not typing
            base = {"key": key, "code": code, "windowsVirtualKeyCode": vk, "nativeVirtualKeyCode": vk, "modifiers": mask}
            down = {"type": "keyDown" if text else "rawKeyDown", **base}
            if text:
                down["text"] = text
            await link.call("Input.dispatchKeyEvent", down)
            await link.call("Input.dispatchKeyEvent", {"type": "keyUp", **base})
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
        return {}

    async def page_url(self, target_id: str) -> str:
        link = await self.link(target_id)
        result = await link.call("Runtime.evaluate", {"expression": "location.href", "returnByValue": True})
        return str(result.get("result", {}).get("value", ""))

    async def fill_secret(self, target_id: str, fields: list[tuple[str, str | None, str]], submit: str) -> None:
        """Type secret values into the page's fields and submit: [(kind, selector or None, value)].

        Only selectors and fixed scripts go through Runtime; each value goes in as typed text (Input.insertText),
        so it is never part of a script, a log line or an error message.
        """
        link = await self.link(target_id)
        self.touched[target_id] = time.monotonic()
        last = None
        for kind, selector, value in fields:
            found = await link.call("Runtime.evaluate", {
                "expression": f"({FIND_FIELD})({json.dumps(kind)}, {json.dumps(selector)})", "returnByValue": False,
            })
            object_id = found.get("result", {}).get("objectId")
            if not object_id or found.get("result", {}).get("subtype") == "null":
                raise LookupError(f"no {kind} field found on the page" + (f" for {selector}" if selector else ""))
            await link.call("Runtime.callFunctionOn", {"objectId": object_id, "functionDeclaration": PREPARE_FIELD})
            await link.call("Input.insertText", {"text": value})
            last = object_id
        if submit == "enter" and last:
            code, vk, text = key_info("Enter")
            base = {"key": "Enter", "code": code, "windowsVirtualKeyCode": vk, "nativeVirtualKeyCode": vk}
            await link.call("Input.dispatchKeyEvent", {"type": "keyDown", "text": text, **base})
            await link.call("Input.dispatchKeyEvent", {"type": "keyUp", **base})
        elif submit not in ("enter", "none"):
            clicked = await link.call("Runtime.evaluate", {
                "expression": f"(() => {{ const b = document.querySelector({json.dumps(submit)}); if (!b) return false; b.click(); return true; }})()",
                "returnByValue": True,
            })
            if not clicked.get("result", {}).get("value"):
                raise LookupError(f"no submit button found for {submit}")

    async def _mouse(self, link: TabLink, event: dict[str, Any], x: float, y: float) -> dict[str, Any]:
        """move (with held set while a button is down: drags and selections), down, up, click (clicks 1 or 2)."""
        action = event.get("action")
        button = event.get("button") or "left"
        if button not in BUTTONS:
            raise ValueError(f"unknown button {button}")
        mask = BUTTONS[button]
        mouse = partial_mouse(link)
        if action == "move":
            params: dict[str, Any] = {"type": "mouseMoved", "x": x, "y": y}
            if event.get("held"):
                params.update(button=button, buttons=mask)
            await mouse(params)
            return {}
        count = 2 if event.get("clicks") == 2 else 1  # 2 on down and up: the second press of a double-click
        if action == "down":
            await mouse({"type": "mousePressed", "x": x, "y": y, "button": button, "buttons": mask, "clickCount": count})
            return {}
        if action == "up":
            await mouse({"type": "mouseReleased", "x": x, "y": y, "button": button, "buttons": 0, "clickCount": count})
        elif action == "click":
            clicks = 2 if event.get("clicks") == 2 else 1
            await mouse({"type": "mouseMoved", "x": x, "y": y})
            for count in range(1, clicks + 1):
                await mouse({"type": "mousePressed", "x": x, "y": y, "button": button, "buttons": mask, "clickCount": count})
                await mouse({"type": "mouseReleased", "x": x, "y": y, "button": button, "buttons": 0, "clickCount": count})
        else:
            raise ValueError("mouse action is move, down, up or click")
        if button != "left":
            return {}
        try:
            reply = await link.call("Runtime.evaluate", {"expression": FOCUS_JS, "returnByValue": True}, timeout=3)
            return (reply.get("result") or {}).get("value") or {}
        except Exception:
            return {}

    async def close(self) -> None:
        for link in list(self.links.values()):
            if not link.closed:
                await link.ws.close()


def hidden_url(url: str) -> bool:
    """Pages the owner never sees as tabs: DevTools, and the location extension's own page."""
    return url.startswith(("devtools://", LOCATION_PAGE))


def partial_mouse(link: TabLink):
    async def send(params: dict[str, Any]) -> None:
        await link.call("Input.dispatchMouseEvent", params)
    return send
