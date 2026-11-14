"""Krewd's demo agent: an ACP agent that plays scripted scenes, for screenshots, videos and talks.

It speaks the same Agent Client Protocol as Claude Code or Codex, so the demo server, the app and the
recordings go through exactly the code paths real agents use. What it says and does comes from the
scenes in `demo/scenes/*.toml`; the first prompt of a chat picks the scene whose `match` words fit best,
and a routine run picks the scene that names that routine. Browser steps drive the demo Brave over
DevTools on real websites, sign-ins go through `krewd secret request`, routines through `krewd routines`,
and files are written for real into the shared folder.

Persona (`DEMO_PERSONA=claude|codex`) sets the agent's name, model list, modes and commands, taken from
`demo/personas/<persona>.json`. Nothing here talks to a model or needs a key.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import random
import re
import shlex
import sys
import tomllib
import urllib.request
import uuid
from pathlib import Path

import websockets

HERE = Path(__file__).resolve().parent
PERSONA_NAME = os.environ.get("DEMO_PERSONA", "claude")
PERSONA = json.loads((HERE / "personas" / f"{PERSONA_NAME}.json").read_text())
CDP_PORT = int(os.environ.get("KREWD_CDP_PORT", "9223"))
SPEED = float(os.environ.get("DEMO_SPEED", "1"))  # 2 plays twice as fast
SHARE_DIR = Path(os.path.expanduser(os.environ.get("DEMO_SHARE_DIR", "~/outputs")))
KREWD = os.environ.get("DEMO_KREWD_CLI", str(HERE.parent / "deploy" / "krewd-cli.sh"))

HANDOVER_HINTS = ("compacting this chat", "write a handover", "handover for the agent")


def load_scenes() -> list[dict]:
    scenes = []
    for path in sorted((HERE / "scenes").glob("*.toml")):
        scene = tomllib.loads(path.read_text())
        scene.setdefault("id", path.stem)
        scenes.append(scene)
    return scenes


SCENES = load_scenes()

# ---------------------------------------------------------------- ACP plumbing

pending: dict[int, asyncio.Future] = {}
next_id = 5000
cancelled: set[str] = set()
steered: dict[str, list[str]] = {}
sessions: dict[str, dict] = {}  # sid -> {"scene": dict | None, "config": {...}, "mode": str, "tab": str | None, "turns": int}


def send(message: dict) -> None:
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def update(sid: str, body: dict) -> None:
    send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": sid, "update": body}})


async def ask(method: str, params: dict) -> dict:
    global next_id
    next_id += 1
    future = asyncio.get_running_loop().create_future()
    pending[next_id] = future
    send({"jsonrpc": "2.0", "id": next_id, "method": method, "params": params})
    return await future


class Cancelled(Exception):
    pass


async def pause(ms: float, sid: str) -> None:
    left = ms / 1000 / SPEED
    while left > 0:
        if sid in cancelled:
            raise Cancelled
        step = min(left, 0.05)
        await asyncio.sleep(step)
        left -= step


def session_state(sid: str) -> dict:
    return sessions.setdefault(sid, {"scene": None, "config": {}, "mode": PERSONA.get("mode", "default"),
                                     "tab": None, "turns": 0})


def config_options(sid: str) -> list[dict]:
    values = session_state(sid)["config"]
    options = json.loads(json.dumps(PERSONA["config_options"]))
    for option in options:
        if option["id"] in values:
            option["currentValue"] = values[option["id"]]
    return options


# ---------------------------------------------------------------- browser (the demo Brave over DevTools)


def cdp_http(path: str, method: str = "GET") -> dict | list:
    request = urllib.request.Request(f"http://127.0.0.1:{CDP_PORT}{path}", method=method)
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read())


class Page:
    def __init__(self, target_id: str):
        self.target_id = target_id
        self.ws = None
        self.ids = 0
        self.waiters: dict[int, asyncio.Future] = {}
        self.events: asyncio.Queue = asyncio.Queue()
        self.reader = None

    async def open(self) -> "Page":
        self.ws = await websockets.connect(f"ws://127.0.0.1:{CDP_PORT}/devtools/page/{self.target_id}", max_size=None)
        self.reader = asyncio.create_task(self._read())
        await self.call("Page.enable")
        return self

    async def _read(self) -> None:
        async for raw in self.ws:
            message = json.loads(raw)
            if "id" in message and message["id"] in self.waiters:
                self.waiters.pop(message["id"]).set_result(message.get("result") or {})
            elif message.get("method"):
                await self.events.put(message)

    async def call(self, method: str, params: dict | None = None) -> dict:
        self.ids += 1
        future = asyncio.get_running_loop().create_future()
        self.waiters[self.ids] = future
        await self.ws.send(json.dumps({"id": self.ids, "method": method, "params": params or {}}))
        return await asyncio.wait_for(future, 30)

    async def goto(self, url: str, settle_ms: int = 1500) -> None:
        while not self.events.empty():
            self.events.get_nowait()
        await self.call("Page.navigate", {"url": url})
        try:
            async with asyncio.timeout(20):
                while (await self.events.get()).get("method") != "Page.loadEventFired":
                    pass
        except TimeoutError:
            pass
        await asyncio.sleep(settle_ms / 1000)

    async def js(self, expression: str):
        result = await self.call("Runtime.evaluate", {"expression": expression, "awaitPromise": True, "returnByValue": True})
        return (result.get("result") or {}).get("value")

    async def screenshot(self) -> str:
        shot = await self.call("Page.captureScreenshot", {"format": "png"})
        return shot["data"]

    async def close(self) -> None:
        if self.reader:
            self.reader.cancel()
        if self.ws:
            await self.ws.close()


async def page_for(sid: str, new: bool = False) -> Page:
    state = session_state(sid)
    tabs = {t["id"] for t in cdp_http("/json/list") if t.get("type") == "page"}
    if new or state["tab"] not in tabs:
        target = cdp_http("/json/new?about:blank", method="PUT")
        state["tab"] = target["id"]
    return await Page(state["tab"]).open()


# ---------------------------------------------------------------- steps


def fill(sid: str, text: str) -> str:
    """Put values a `js` step saved (`{name}` or `{name.key}`) into a scene's text."""
    values = session_state(sid).setdefault("vars", {})

    def one(match):
        value = values
        for part in match.group(1).split("."):
            value = value.get(part) if isinstance(value, dict) else None
            if value is None:
                return match.group(0)
        return str(value)

    return re.sub(r"\{([a-z_][a-z0-9_.]*)\}", one, text)


def words(text: str):
    for piece in re.findall(r"\S+\s*|\s+", text):
        yield piece


async def say(sid: str, text: str, ms_per_word: float = 28) -> None:
    text = fill(sid, text)
    message_id = uuid.uuid4().hex[:8]
    for piece in words(text.strip() + "\n"):
        update(sid, {"sessionUpdate": "agent_message_chunk", "messageId": message_id, "content": {"type": "text", "text": piece}})
        await pause(ms_per_word * random.uniform(0.6, 1.4), sid)


async def think(sid: str, text: str) -> None:
    for piece in words(fill(sid, text)):
        update(sid, {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": piece}})
        await pause(12, sid)


def tool_title(step: dict) -> str:
    if step.get("title"):
        return step["title"]
    kind = step["do"]
    if kind == "run":
        return step["command"].splitlines()[0][:80]
    if kind == "read":
        return f"Read {step['path']}"
    if kind == "edit":
        return f"Edit {step['path']}"
    return kind


async def tool(sid: str, step: dict, kind: str, raw_input: dict, output: str | None = None,
               image: str | None = None, content: list | None = None, ms: float = 900) -> None:
    tid = "toolu_" + uuid.uuid4().hex[:20]
    title = tool_title(step)
    update(sid, {"sessionUpdate": "tool_call", "toolCallId": tid, "title": title, "kind": kind,
                 "status": "pending", "rawInput": raw_input})
    await pause(150, sid)
    update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": tid, "status": "in_progress"})
    await pause(ms, sid)
    blocks = list(content or [])
    if output is not None:
        blocks.append({"type": "content", "content": {"type": "text", "text": output.rstrip("\n")}})
    if image:
        blocks.append({"type": "content", "content": {"type": "image", "data": image, "mimeType": "image/png"}})
    update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": tid, "status": step.get("status", "completed"),
                 "content": blocks})


def plan_update(sid: str, entries: list[str], done: int) -> None:
    update(sid, {"sessionUpdate": "plan", "entries": [
        {"content": text, "priority": "medium",
         "status": "completed" if i < done else ("in_progress" if i == done else "pending")}
        for i, text in enumerate(entries)]})


async def shell(command: list[str], env: dict | None = None) -> tuple[int, str]:
    process = await asyncio.create_subprocess_exec(*command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
                                                   env={**os.environ, **(env or {})})
    out, _ = await process.communicate()
    return process.returncode, out.decode(errors="replace")


async def run_steps(sid: str, steps: list[dict], scene: dict) -> str:
    """Play steps; returns 'rejected' when the owner said no to an approval that ends the scene."""
    state = session_state(sid)
    plan = scene.get("plan") or []
    acknowledged = 0
    for step in steps:
        # A message the owner steered into the running turn: answer it at the next step, as a real agent does.
        waiting = steered.get(sid, [])
        if len(waiting) > acknowledged:
            for message in waiting[acknowledged:]:
                await say(sid, scene.get("on_steer", 'Got it: "{message}". Adding that to this change.').replace("{message}", message.strip()))
            acknowledged = len(waiting)
        do = step["do"]
        ms = step.get("ms", 900)
        if do == "think":
            await think(sid, step["text"])
        elif do == "title":
            update(sid, {"sessionUpdate": "session_info_update", "title": step["text"]})
        elif do == "plan":
            plan_update(sid, plan, step.get("done", 0))
        elif do == "say":
            await say(sid, step["text"], step.get("ms_per_word", 28))
        elif do == "wait":
            await pause(ms, sid)
        elif do == "usage":
            update(sid, {"sessionUpdate": "usage_update", "used": step["used"], "size": step.get("size", 200000),
                         "_meta": {"_demo/model": PERSONA.get("model_used", "")}})
        elif do == "run":
            raw = {"command": step["command"], "description": step.get("description", "")}
            if PERSONA_NAME == "codex":
                raw = {"command": ["bash", "-lc", step["command"]], "cwd": step.get("cwd", "~")}
            await tool(sid, step, "execute", raw, step.get("output", ""), ms=ms)
        elif do == "read":
            await tool(sid, step, "read", {"file_path": step["path"]}, step.get("output", ""), ms=ms)
        elif do == "edit":
            diff = {"type": "diff", "path": step["path"], "oldText": step.get("old", ""), "newText": step.get("new", "")}
            await tool(sid, step, "edit", {"file_path": step["path"], "old_string": step.get("old", ""),
                                           "new_string": step.get("new", "")}, step.get("output"), content=[diff], ms=ms)
        elif do == "search":
            await tool(sid, step, "search", {"pattern": step.get("pattern", "")}, step.get("output", ""), ms=ms)
        elif do == "browse":
            page = await page_for(sid, new=step.get("new_tab", False))
            try:
                code = f'goto_url("{step["url"]}")'
                tid_step = {**step, "title": step.get("title") or f"browser-harness: {code}"}
                task = asyncio.create_task(page.goto(step["url"], step.get("settle_ms", 2500)))
                await tool(sid, tid_step, "execute", {"command": f"browser-harness <<'PY'\n{code}\nprint(page_info())\nPY"},
                           step.get("output") or f"{{'url': '{step['url']}', 'title': ...}}", ms=ms)
                await task
            finally:
                await page.close()
        elif do == "js":
            page = await page_for(sid)
            try:
                value = await page.js(step["expr"])
                if step.get("save"):
                    saved = value if value not in (None, "", [], {}) else step.get("default")
                    state.setdefault("vars", {})[step["save"]] = saved
                if step.get("show", True):
                    await tool(sid, {**step, "title": step.get("title") or "browser-harness: js(...)"}, "execute",
                               {"command": f"browser-harness <<'PY'\n{step.get('code', 'js(' + json.dumps(step['expr'][:60]) + ')')}\nPY"},
                               fill(sid, step["output"]) if step.get("output") else (json.dumps(value, ensure_ascii=False)[:600] if value is not None else "ok"), ms=ms)
                await pause(step.get("after_ms", 600), sid)
            finally:
                await page.close()
        elif do == "shot":
            page = await page_for(sid)
            try:
                image = await page.screenshot()
            finally:
                await page.close()
            await tool(sid, {**step, "title": step.get("title") or "browser-harness: capture_screenshot()"}, "execute",
                       {"command": "browser-harness <<'PY'\nprint(capture_screenshot('/tmp/shot.png'))\nPY"},
                       step.get("output", "/tmp/shot.png"), image=image, ms=ms)
        elif do == "secret":
            args = [KREWD, "secret", "request", "--reason", step["reason"], "--tab", state["tab"] or ""]
            for field in step.get("fields", []):
                args += ["--field", field]
            if step.get("submit"):
                args += ["--submit", step["submit"]]
            tid = "toolu_" + uuid.uuid4().hex[:20]
            shown = "krewd secret request --reason " + shlex.quote(step["reason"])
            update(sid, {"sessionUpdate": "tool_call", "toolCallId": tid, "title": shown, "kind": "execute",
                         "status": "in_progress", "rawInput": {"command": shown}})
            code, out = await shell(args)
            update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": tid,
                         "status": "completed" if code == 0 else "failed",
                         "content": [{"type": "content", "content": {"type": "text", "text": out.strip()}}]})
            if code != 0:
                await say(sid, step.get("declined", "The sign-in did not go through, so I stopped here."))
                return "rejected"
        elif do == "krewd":
            if step.get("unless"):  # e.g. a routine of that name exists already: say so instead, like a real agent
                _, listed = await shell([KREWD, "routines", "list"])
                if step["unless"].lower() in listed.lower():
                    await say(sid, step.get("exists_say", "That already exists, so I left it as it is."))
                    return "done"
            args = shlex.split(fill(sid, step["args"]))
            code, out = await shell([KREWD, *args])
            shown = "krewd " + fill(sid, step.get("shown", step["args"]))
            await tool(sid, {**step, "title": step.get("title") or shown[:80]}, "execute", {"command": shown},
                       step.get("output") or out, ms=ms)
        elif do == "file":
            path = SHARE_DIR / step["name"]
            path.parent.mkdir(parents=True, exist_ok=True)
            source = step.get("from")
            if source:
                path.write_bytes((HERE / source).read_bytes())
            else:
                path.write_text(fill(sid, step["content"]))
            await tool(sid, {**step, "title": step.get("title") or f"Write ~/outputs/{step['name']}"}, "edit",
                       {"file_path": f"~/outputs/{step['name']}"}, step.get("output", f"Wrote ~/outputs/{step['name']}"), ms=ms)
        elif do == "ask":
            tid = "toolu_" + uuid.uuid4().hex[:20]
            kind = step.get("kind", "execute")
            raw = {"command": step["command"]} if step.get("command") else {}
            update(sid, {"sessionUpdate": "tool_call", "toolCallId": tid, "title": step["title"], "kind": kind,
                         "status": "pending", "rawInput": raw})
            reply = await ask("session/request_permission", {
                "sessionId": sid,
                "toolCall": {"toolCallId": tid, "title": step["title"], "kind": kind, "rawInput": raw},
                "options": [
                    {"optionId": "allow", "name": "Yes", "kind": "allow_once"},
                    {"optionId": "always", "name": "Yes, and don't ask again", "kind": "allow_always"},
                    {"optionId": "reject", "name": "No", "kind": "reject_once"},
                ],
            })
            outcome = reply.get("outcome", {})
            allowed = outcome.get("outcome") == "selected" and outcome.get("optionId") in ("allow", "always")
            update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": tid, "status": "in_progress" if allowed else "failed"})
            if allowed:
                await pause(ms, sid)
                update(sid, {"sessionUpdate": "tool_call_update", "toolCallId": tid, "status": "completed",
                             "content": [{"type": "content", "content": {"type": "text", "text": step.get("output", "")}}]})
            else:
                await say(sid, step.get("rejected", "Okay, I did not do that. Everything else is ready when you want it."))
                return "rejected"
        elif do == "steered":  # kept for older scenes; steering is now answered at every step
            pass
        else:
            await say(sid, f"(demo: unknown step {do})")
    return "done"


# ---------------------------------------------------------------- picking a scene


def score(scene: dict, text: str) -> int:
    lowered = text.lower()
    return sum(1 for word in scene.get("match", []) if word.lower() in lowered)


def is_run(text: str) -> bool:
    return "of the Krewd routine" in text


def pick_scene(text: str) -> dict | None:
    if is_run(text):
        attempt = re.search(r"Attempt (\d+) of (\d+)", text)
        later = attempt is not None and int(attempt.group(1)) > 1
        for scene in SCENES:
            if scene.get("routine") and f'"{scene["routine"].lower()}"' in text.lower():
                steps = scene.get("routine_retry_steps") if later and scene.get("routine_retry_steps") else None
                return {**scene, "steps": steps or scene.get("routine_steps", scene.get("steps", []))}
    best = max(SCENES, key=lambda s: score(s, text), default=None)
    return best if best is not None and score(best, text) > 0 else None


async def handle(rid: int, sid: str, text: str) -> None:
    state = session_state(sid)
    state["turns"] += 1
    steered[sid] = []
    found = re.search(r"routine id ([0-9a-f-]{36})", text)
    if found:
        state.setdefault("vars", {})["routine_id"] = found.group(1)[:8]
    try:
        lowered = text.lower()
        if text.strip() == "/compact":
            await say(sid, "Compacted: kept the goal, the decisions so far and the open steps.")
        elif any(h in lowered for h in HANDOVER_HINTS) or "moves to another agent" in lowered:
            scene = state["scene"] or {}
            await say(sid, scene.get("handover", "## Handover\nGoal and progress so far are in the chat above; nothing is pending."))
        else:
            scene = None
            if state["scene"] is not None:
                followups = state["scene"].get("followups", [])
                best = max(followups, key=lambda f: score(f, text), default=None)
                if best is not None and score(best, text) > 0:
                    scene = {**state["scene"], "steps": best["steps"]}
            if scene is None:
                picked = pick_scene(text)
                if picked is not None and (state["scene"] is None or is_run(text)):
                    state["scene"] = picked
                    scene = picked
            if scene is None:
                await think(sid, "Reading the request and checking what I can do from here.")
                await say(sid, state["scene"].get("fallback") if state["scene"] else
                          "On it. (This is Krewd's demo agent: it plays scripted scenes. Try one of the saved prompts.)")
            else:
                if scene.get("title") and state["turns"] == 1:
                    update(sid, {"sessionUpdate": "session_info_update", "title": scene["title"]})
                await run_steps(sid, scene.get("steps", []), scene)
        send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "end_turn",
                                                      "_meta": {"model": PERSONA.get("model_used", "")}}})
    except Cancelled:
        cancelled.discard(sid)
        send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "cancelled"}})
    except Exception as error:  # a broken scene must not take the agent down mid-recording
        print(f"demo agent: {error!r}", file=sys.stderr)
        await say(sid, f"(demo agent error: {error})")
        send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "end_turn"}})
    finally:
        steered.pop(sid, None)


async def advertise(sid: str) -> None:
    await asyncio.sleep(0.05)
    update(sid, {"sessionUpdate": "available_commands_update", "availableCommands": PERSONA.get("commands", [])})


async def main() -> None:
    reader = asyncio.StreamReader(limit=64 * 1024 * 1024)
    await asyncio.get_running_loop().connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    tasks = set()

    def spawn(coro) -> None:
        task = asyncio.ensure_future(coro)
        tasks.add(task)
        task.add_done_callback(tasks.discard)

    while line := await reader.readline():
        message = json.loads(line)
        method = message.get("method")
        if method is None:
            future = pending.pop(message.get("id"), None)
            if future and not future.done():
                future.set_result(message.get("result") or {})
            continue
        params = message.get("params") or {}
        rid = message.get("id")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": 1,
                "agentCapabilities": {"loadSession": True, "promptCapabilities": {"image": True, "embeddedContext": True},
                                      "sessionCapabilities": {"resume": {}, "delete": {}}},
                "agentInfo": PERSONA["agent"],
                "authMethods": [],
                "_meta": {"steering": {"supported": True}},
            }})
        elif method == "session/new":
            sid = str(uuid.uuid4())
            session_state(sid)
            send({"jsonrpc": "2.0", "id": rid, "result": {"sessionId": sid, "configOptions": config_options(sid),
                                                          "modes": PERSONA.get("modes")}})
            spawn(advertise(sid))
        elif method in ("session/resume", "session/load"):
            sid = params["sessionId"]
            session_state(sid)
            send({"jsonrpc": "2.0", "id": rid, "result": {"configOptions": config_options(sid), "modes": PERSONA.get("modes")}})
            spawn(advertise(sid))
        elif method == "session/set_config_option":
            sid = params["sessionId"]
            session_state(sid)["config"][params["configId"]] = params["value"]
            send({"jsonrpc": "2.0", "id": rid, "result": {"configOptions": config_options(sid)}})
        elif method == "session/set_mode":
            sid = params["sessionId"]
            session_state(sid)["mode"] = params["modeId"]
            update(sid, {"sessionUpdate": "current_mode_update", "currentModeId": params["modeId"]})
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif method == "session/delete":
            sessions.pop(params.get("sessionId"), None)
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif method == "session/prompt":
            sid = params["sessionId"]
            text = " ".join(block.get("text", "") for block in params.get("prompt", []) if block.get("type") == "text")
            spawn(handle(rid, sid, text))
        elif method == "_session/steering":
            sid = params["sessionId"]
            if sid in steered:
                steered[sid].append(" ".join(b.get("text", "") for b in params.get("prompt", [])))
                send({"jsonrpc": "2.0", "id": rid, "result": {"outcome": "injected"}})
            else:
                send({"jsonrpc": "2.0", "id": rid, "result": {"outcome": "promptRequired", "reason": "noRunningTurn"}})
        elif method == "session/cancel":
            cancelled.add(params["sessionId"])
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})


if __name__ == "__main__":
    asyncio.run(main())
