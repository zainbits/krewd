"""Sign-ins: an agent's own subscription login, started and finished from the app.

Each agent with a gateway kind has its own command-line login, which Krewd runs and relays to the phone:

- Claude Code: `claude auth login --claudeai` prints a sign-in link, then waits for the code that page shows;
  the owner opens the link on the phone and pastes the code back in the app.
- Codex: `codex login --device-auth` prints a link and a short code; the owner opens the link, enters the code,
  and the command finishes by itself.

Status comes from `claude auth status` / `codex login status`, cached for a minute. The CLIs are the engine's
own: Codex from CODEX_PATH (see profiles.py), Claude from CLAUDE_PATH, else the `claude` beside the engine's
command (the standalone claude-agent-acp ships one), else `claude` on PATH.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import time
from dataclasses import dataclass, field
from typing import Any

from .config import Engine
from .profiles import codex_path

STATUS_TTL = 60.0
FLOW_TIMEOUT = 15 * 60  # Codex's device codes last 15 minutes; Claude's page code is pasted well within that
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07")
URL = re.compile(r"https://\S+")
DEVICE_CODE = re.compile(r"\b[A-Z0-9]{4}-[A-Z0-9]{4,6}\b")
GATEWAY_VARS = ("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_MODEL",
                "MODEL_PROVIDER", "CODEX_CONFIG", "OPENAI_API_KEY", "CODEX_API_KEY")


class LoginError(Exception):
    """A sign-in cannot start or finish (no CLI, no flow, wrong code)."""


@dataclass
class Flow:
    engine: str
    proc: asyncio.subprocess.Process
    started: float = field(default_factory=time.monotonic)
    url: str | None = None
    code: str | None = None  # a code the owner types on the sign-in page (Codex)
    paste: bool = False  # the owner pastes a code from the page back here (Claude Code)
    state: str = "starting"  # starting, waiting, finishing, done, failed, cancelled
    error: str | None = None
    output: str = ""
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    task: asyncio.Task | None = None

    def view(self) -> dict[str, Any]:
        return {"state": self.state, "url": self.url, "code": self.code, "paste": self.paste, "error": self.error,
                "expires_in": max(0, int(FLOW_TIMEOUT - (time.monotonic() - self.started)))}


def claude_cli(engine: Engine) -> str | None:
    path = engine.env.get("CLAUDE_PATH")
    if path:
        path = os.path.expanduser(path)
        return path if os.access(path, os.X_OK) else None
    beside = os.path.join(os.path.dirname(os.path.realpath(engine.command[0])), "claude")
    if os.access(beside, os.X_OK) and os.path.isfile(beside):
        return beside
    return shutil.which("claude", path=engine.env.get("PATH") or os.environ.get("PATH"))


def cli_env(engine: Engine, cli: str) -> dict[str, str]:
    """The engine's environment without any gateway settings, and with nothing that opens a browser on this machine."""
    env = {k: v for k, v in {**os.environ, **engine.env}.items() if k not in GATEWAY_VARS}
    env["PATH"] = os.pathsep.join([os.path.dirname(cli), env.get("PATH", "")])  # npm's codex is a Node script
    env["BROWSER"] = "true"
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
    env["NO_COLOR"] = "1"
    return env


class Logins:
    def __init__(self) -> None:
        self.status: dict[str, dict[str, Any]] = {}  # engine id -> {logged_in, account, checked}
        self.flows: dict[str, Flow] = {}

    def cli(self, engine: Engine) -> list[str] | None:
        if engine.gateway == "claude":
            path = claude_cli(engine)
            return [path] if path else None
        if engine.gateway == "codex":
            path = codex_path(engine)
            return [path] if path else None
        return None

    def known(self, engine_id: str) -> bool | None:
        """Cached: is the agent signed in? None when not checked yet."""
        found = self.status.get(engine_id)
        return found["logged_in"] if found else None

    def stale(self, engine_id: str) -> bool:
        found = self.status.get(engine_id)
        return not found or time.monotonic() - found["checked"] >= STATUS_TTL

    async def check(self, engine: Engine) -> dict[str, Any]:
        """Ask the agent's CLI whether it is signed in (to its subscription), and as whom."""
        cli = self.cli(engine)
        result: dict[str, Any] = {"logged_in": False, "account": None, "error": None}
        if not cli:
            result["error"] = "its command-line login was not found"
        else:
            args = ["auth", "status"] if engine.gateway == "claude" else ["login", "status"]
            try:
                proc = await asyncio.create_subprocess_exec(
                    *cli, *args, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.STDOUT, env=cli_env(engine, cli[0]),
                )
                out, _ = await asyncio.wait_for(proc.communicate(), timeout=30)
                text = ANSI.sub("", out.decode(errors="replace")).strip()
                if engine.gateway == "claude":
                    data = json.loads(text[text.find("{"):]) if "{" in text else {}
                    result["logged_in"] = bool(data.get("loggedIn"))
                    result["account"] = data.get("email") or data.get("account") or data.get("orgName")
                    plan = data.get("subscriptionType")
                    if result["account"] and plan:
                        result["account"] = f"{result['account']} ({plan})"
                else:
                    result["logged_in"] = proc.returncode == 0 and "not logged in" not in text.lower()
                    if result["logged_in"]:
                        result["account"] = text.splitlines()[-1][:120] if text else None
            except (OSError, ValueError, TimeoutError) as exc:
                result["error"] = f"status check failed: {exc}"
        result["checked"] = time.monotonic()
        self.status[engine.id] = result
        return result

    async def start(self, engine: Engine, on_done) -> dict[str, Any]:
        """Start the agent's sign-in and return what the owner needs: the link, and a code to type if any."""
        cli = self.cli(engine)
        if not cli:
            raise LoginError(f"{engine.name}: its command-line login was not found")
        old = self.flows.get(engine.id)
        if old and old.state in ("starting", "waiting", "finishing"):
            return old.view()
        args = ["auth", "login", "--claudeai"] if engine.gateway == "claude" else ["login", "--device-auth"]
        proc = await asyncio.create_subprocess_exec(
            *cli, *args, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT, env=cli_env(engine, cli[0]), start_new_session=True,
        )
        flow = Flow(engine=engine.id, proc=proc, paste=engine.gateway == "claude")
        self.flows[engine.id] = flow
        flow.task = asyncio.create_task(self._follow(engine, flow, on_done))
        try:
            await asyncio.wait_for(flow.ready.wait(), timeout=30)
        except TimeoutError:
            await self.cancel(engine.id)
            raise LoginError(f"{engine.name}: the sign-in did not show a link") from None
        if flow.state == "failed":
            raise LoginError(f"{engine.name}: {flow.error}")
        return flow.view()

    async def _follow(self, engine: Engine, flow: Flow, on_done) -> None:
        """Read the CLI's output until it shows the link (and code), then wait for it to finish."""
        assert flow.proc.stdout is not None
        try:
            async with asyncio.timeout(FLOW_TIMEOUT):
                while True:
                    chunk = await flow.proc.stdout.read(4096)
                    if not chunk:
                        break
                    flow.output = (flow.output + ANSI.sub("", chunk.decode(errors="replace")))[-8000:]
                    if flow.url is None:
                        urls = [u.rstrip(".,)") for u in URL.findall(flow.output)]
                        flow.url = next((u for u in urls if "oauth" in u or "device" in u or "auth" in u), None)
                    if flow.code is None and not flow.paste:
                        found = DEVICE_CODE.search(flow.output.split(flow.url or "\x00")[-1] if flow.url else "")
                        flow.code = found.group(0) if found else None
                    if flow.url and (flow.paste or flow.code) and flow.state == "starting":
                        flow.state = "waiting"
                        flow.ready.set()
                code = await flow.proc.wait()
        except TimeoutError:
            flow.proc.kill()
            code = -1
            flow.error = "the sign-in timed out; start it again"
        if flow.state == "cancelled":
            flow.ready.set()
            return
        if code == 0:
            status = await self.check(engine)
            flow.state = "done" if status["logged_in"] else "failed"
            if not status["logged_in"]:
                flow.error = "the sign-in finished but the agent still reports no login"
        else:
            flow.state = "failed"
            last = [line.strip() for line in flow.output.splitlines() if line.strip()]
            flow.error = flow.error or (last[-1][:200] if last else f"exited with {code}")
        flow.ready.set()
        if flow.state == "done":
            await on_done(engine.id)

    async def submit(self, engine_id: str, code: str) -> dict[str, Any]:
        """Claude Code: pass the code from the sign-in page to the waiting CLI and wait for it to finish."""
        flow = self.flows.get(engine_id)
        if not flow or flow.state != "waiting" or not flow.paste:
            raise LoginError("no sign-in is waiting for a code; start it again")
        assert flow.proc.stdin is not None
        flow.state = "finishing"
        flow.proc.stdin.write(code.strip().encode() + b"\n")
        await flow.proc.stdin.drain()
        if flow.task:
            try:
                await asyncio.wait_for(asyncio.shield(flow.task), timeout=90)
            except TimeoutError:
                pass
        return flow.view()

    async def cancel(self, engine_id: str) -> None:
        flow = self.flows.get(engine_id)
        if flow and flow.state in ("starting", "waiting", "finishing"):
            flow.state = "cancelled"
            if flow.proc.returncode is None:
                flow.proc.kill()

    async def logout(self, engine: Engine) -> dict[str, Any]:
        cli = self.cli(engine)
        if not cli:
            raise LoginError(f"{engine.name}: its command-line login was not found")
        args = ["auth", "logout"] if engine.gateway == "claude" else ["logout"]
        proc = await asyncio.create_subprocess_exec(
            *cli, *args, stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL, env=cli_env(engine, cli[0]),
        )
        await asyncio.wait_for(proc.wait(), timeout=30)
        return await self.check(engine)

    def flow(self, engine_id: str) -> dict[str, Any] | None:
        flow = self.flows.get(engine_id)
        return flow.view() if flow else None

    async def close(self) -> None:
        for engine_id in list(self.flows):
            await self.cancel(engine_id)
