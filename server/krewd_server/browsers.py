"""The agents' Brave plus one Brave per person, each with its own profile, logins and DevTools port.

- "main" is the agents' usual Brave (krewd-browser.service, KREWD_CDP_PORT). It always runs.
- A person's Brave (`krewd browser add NAME`) is `config/browsers/NAME.env` (its port and profile folder) and
  the unit `krewd-browser@NAME.service`, which runs it in tmux `agent-brave-NAME`. It runs only when needed:
  the server starts it when a chat that uses it calls browser-harness, and stops it after it has been idle for
  KREWD_BROWSER_IDLE_MINUTES (default 30; 0: never), or on `krewd browser stop NAME`. Its profile stays on
  disk, so its logins survive.
- A chat uses main until `krewd browser use NAME` (column sessions.browser); its browser-harness hook asks the
  server which browser that is (GET /api/browser/session) before every call and points its daemon at it.
- Tab ids are unique across browsers, so the phone's tab list holds every running browser's tabs (each with its
  `browser`), and every tab call (stream, input, view, sign-in fill) goes to the browser that has the tab.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
import shutil
import socket
import time
from pathlib import Path
from typing import Any

from .browser import Browser, BrowserUnavailable, TabGone

log = logging.getLogger("krewd.browsers")

MAIN = "main"
NAME_RE = re.compile(r"^[a-z][a-z0-9-]{0,31}$")
PORT_SPAN = 50  # people's ports: the first free one above the main port, within this many
START_SECONDS = 40


class BrowserError(Exception):
    """A browser that cannot be added, started or used (the message says why)."""


def valid_name(name: str) -> str:
    name = (name or "").strip().lower()
    if not NAME_RE.match(name):
        raise BrowserError("a browser name is a short lowercase name: a letter, then letters, digits or '-' (max 32)")
    return name


def port_free(port: int) -> bool:
    """Nothing listens on this loopback port."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", port)) != 0


def unit_name(name: str) -> str:
    return f"krewd-browser@{name}.service"


def _read_env(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text().splitlines():
        key, sep, value = line.strip().partition("=")
        if sep and not key.startswith("#"):
            out[key.strip()] = value.strip()
    return out


class Fleet:
    def __init__(self, main_port: int, config_dir: Path, main_profile: Path, idle_minutes: float = 30):
        self.main = Browser(main_port)
        self.main_port = main_port
        self.dir = config_dir / "browsers"
        self.config_dir = config_dir
        self.main_profile = main_profile
        self.idle_seconds = max(0.0, idle_minutes) * 60
        self.extra: dict[str, Browser] = {}
        self.profiles: dict[str, Path] = {}
        self.where: dict[str, str] = {}  # tab id -> browser name, from the last tab lists
        self.used: dict[str, float] = {}  # browser name -> when a chat or the owner last used it
        self.starting: dict[str, asyncio.Lock] = {}
        self.load()

    # ---------------------------------------------------------------- registry

    def load(self) -> None:
        """Read config/browsers/*.env (the people's browsers); keeps the Browser objects of known names."""
        found: dict[str, tuple[int, Path]] = {}
        if self.dir.is_dir():
            for path in sorted(self.dir.glob("*.env")):
                try:
                    env = _read_env(path)
                    name = valid_name(path.stem)
                    found[name] = (int(env["KREWD_CDP_PORT"]), Path(env["KREWD_BROWSER_PROFILE"]))
                except (OSError, KeyError, ValueError, BrowserError) as exc:
                    log.warning("ignoring browser file %s: %s", path, exc)
        for name in list(self.extra):
            if name not in found or self.extra[name].port != found[name][0]:
                self.extra.pop(name)
        for name, (port, profile) in found.items():
            self.extra.setdefault(name, Browser(port))
            self.profiles[name] = profile

    def names(self) -> list[str]:
        return [MAIN, *sorted(self.extra)]

    def get(self, name: str | None) -> Browser:
        if not name or name == MAIN:
            return self.main
        browser = self.extra.get(name)
        if browser is None:
            raise BrowserError(f"no browser named {name!r} (krewd browser list; add one with krewd browser add {name})")
        return browser

    def known(self, name: str | None) -> bool:
        return not name or name == MAIN or name in self.extra

    def profile_dir(self, name: str) -> Path:
        """Beside the main profile: ~/.local/share/krewd/agent-brave -> ~/.local/share/krewd/agent-brave-NAME."""
        return self.main_profile.with_name(f"{self.main_profile.name}-{name}")

    def _free_port(self) -> int:
        taken = {self.main_port, *(b.port for b in self.extra.values())}
        for port in range(self.main_port + 1, self.main_port + 1 + PORT_SPAN):
            if port in taken:
                continue
            if port_free(port):
                return port
        raise BrowserError(f"no free DevTools port in {self.main_port + 1}..{self.main_port + PORT_SPAN}")

    def add(self, name: str) -> dict[str, Any]:
        name = valid_name(name)
        if name == MAIN:
            raise BrowserError("main is the agents' usual browser; pick another name")
        if name in self.extra:
            return self.info(name)
        port = self._free_port()
        profile = self.profile_dir(name)
        profile.mkdir(parents=True, exist_ok=True)
        os.chmod(profile, 0o700)
        self.dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)
        tmp = self.dir / f".{name}.env.tmp"
        tmp.write_text(f"KREWD_BROWSER_NAME={name}\nKREWD_CDP_PORT={port}\nKREWD_BROWSER_PROFILE={profile}\n")
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.dir / f"{name}.env")
        self.load()
        log.info("added browser %s (port %s, profile %s)", name, port, profile)
        return self.info(name)

    def info(self, name: str) -> dict[str, Any]:
        browser = self.get(name)
        return {"name": name if name in self.extra else MAIN, "port": browser.port,
                "cdp_url": f"http://127.0.0.1:{browser.port}",
                "profile": str(self.profiles.get(name, self.main_profile)),
                "tmux": f"agent-brave-{name}" if name in self.extra else "agent-brave"}

    # ---------------------------------------------------------------- lifecycle

    async def running(self, name: str) -> bool:
        try:
            await asyncio.to_thread(self.get(name)._http, "/json/version")
            return True
        except BrowserUnavailable:
            return False

    async def _systemctl(self, *args: str) -> tuple[int, str]:
        exe = shutil.which("systemctl") or "/usr/bin/systemctl"
        proc = await asyncio.create_subprocess_exec(exe, "--user", *args, stdout=asyncio.subprocess.PIPE,
                                                    stderr=asyncio.subprocess.STDOUT)
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=60)
        return proc.returncode or 0, out.decode(errors="replace").strip()

    async def ensure(self, name: str) -> dict[str, Any]:
        """The browser, running: a person's Brave is started (and waited for) if it is not."""
        info = self.info(name)
        if info["name"] == MAIN:
            return info
        self.touch(name)
        lock = self.starting.setdefault(name, asyncio.Lock())
        async with lock:
            if await self.running(name):
                return info
            await self._systemctl("reset-failed", unit_name(name))
            code, out = await self._systemctl("start", unit_name(name))
            if code:
                raise BrowserError(f"could not start {unit_name(name)}: {out[-300:] or code} (./master.sh setup installs it)")
            deadline = time.monotonic() + START_SECONDS
            while time.monotonic() < deadline:
                if await self.running(name):
                    log.info("browser %s started", name)
                    self.touch(name)
                    return info
                await asyncio.sleep(0.5)
        raise BrowserError(f"browser {name} did not open DevTools on port {info['port']} within {START_SECONDS} s "
                           f"(tmux attach -t agent-brave-{name}; journalctl --user -u {unit_name(name)})")

    async def stop(self, name: str) -> None:
        if self.info(name)["name"] == MAIN:
            raise BrowserError("main always runs; stop only a person's browser")
        code, out = await self._systemctl("stop", unit_name(name))
        if code:
            raise BrowserError(f"could not stop {unit_name(name)}: {out[-300:] or code}")
        await self.get(name).close()
        self.used.pop(name, None)
        log.info("browser %s stopped", name)

    def touch(self, name: str | None) -> None:
        if name and name != MAIN:
            self.used[name] = time.monotonic()

    async def idle(self, busy) -> list[str]:
        """People's browsers that run but no chat's turn holds a tab of and nobody used for the idle time."""
        if not self.idle_seconds:
            return []
        out = []
        for name in sorted(self.extra):
            if not await self.running(name):
                continue
            used = self.used.setdefault(name, time.monotonic())  # running since before this server: count from now
            tabs = [t for t, n in self.where.items() if n == name]
            if any(busy(t) for t in tabs) or time.monotonic() - used < self.idle_seconds:
                continue
            out.append(name)
        return out

    async def describe(self) -> list[dict[str, Any]]:
        out = []
        for name in self.names():
            info = self.info(name)
            info["running"] = await self.running(name)
            out.append(info)
        return out

    # ---------------------------------------------------------------- tabs, routed by id

    async def tabs(self) -> list[dict[str, Any]]:
        """Every running browser's tabs, each with its `browser`. Raises BrowserUnavailable only when none runs."""
        out: list[dict[str, Any]] = []
        down: BrowserUnavailable | None = None
        where: dict[str, str] = {}
        for name in self.names():
            browser = self.get(name)
            try:
                tabs = await browser.tabs()
            except BrowserUnavailable as exc:
                if name == MAIN:
                    down = exc
                continue
            for tab in tabs:
                tab["browser"] = name
                where[tab["id"]] = name
            out.extend(tabs)
        self.where = where
        if down and not out:
            raise down
        return out

    async def browser_of(self, tab_id: str) -> Browser:
        name = self.where.get(tab_id)
        if name is None or not self.known(name):
            await self.tabs()
            name = self.where.get(tab_id)
        if name is None:
            if not self.extra:
                return self.main  # one browser: let it answer (TabGone, BrowserUnavailable) itself
            raise TabGone(tab_id)
        self.touch(name)
        return self.get(name)

    async def name_of(self, tab_id: str) -> str | None:
        await self.browser_of(tab_id)
        return self.where.get(tab_id, MAIN)

    async def new_tab(self, url: str | None = None, browser: str | None = None) -> dict[str, Any]:
        if browser and browser != MAIN:
            await self.ensure(browser)
        tab = await self.get(browser).new_tab(url)
        name = browser or MAIN
        tab["browser"] = name
        self.where[tab["id"]] = name
        return tab

    async def close(self) -> None:
        for name in self.names():
            await self.get(name).close()
