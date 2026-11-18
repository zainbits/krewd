"""browser-harness for Krewd's agents: one daemon per chat, and tab claims.

Krewd starts its agents with `BH_AGENT_WORKSPACE` pointing at a workspace whose `agent_helpers.py` is
`deploy/browser-harness/agent_helpers.py` (browser-harness loads that file on every call). That hook:

- names the call's daemon after the agent session (`krewd-<id>`, from `CLAUDE_CODE_SESSION_ID` or
  `CODEX_THREAD_ID`), so two chats never share the daemon's attached tab;
- claims every tab the call touches (`POST /api/browser/claim`) before it touches it. While the chat's turn
  runs, the phone only watches that tab and other chats are refused it.

Daemons do not end by themselves, so the hub stops the ones whose chat has been quiet for a while
(`stop_daemon`, through `browser-harness --reload`, which checks the daemon's identity before it signals).
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import shutil
from pathlib import Path

log = logging.getLogger(__name__)

HOOK = Path(__file__).resolve().parents[2] / "deploy" / "browser-harness" / "agent_helpers.py"
PREFIX = "krewd-"
_UNSAFE = re.compile(r"[^A-Za-z0-9_-]")


def daemon_name(agent_session: str) -> str:
    """The daemon name the hook uses for an agent session (browser-harness allows [A-Za-z0-9_-]{1,64})."""
    return (PREFIX + _UNSAFE.sub("", agent_session))[:64]


def prepare_workspace(path: Path) -> Path:
    """Make the agents' browser-harness workspace, with the hook linked from the repo."""
    path.mkdir(parents=True, exist_ok=True)
    os.chmod(path, 0o700)
    link = path / "agent_helpers.py"
    if not (link.is_symlink() and link.resolve() == HOOK):
        if link.exists() or link.is_symlink():
            link.unlink()
        link.symlink_to(HOOK)
    return path


def runtime_dir() -> Path:
    """Where browser-harness keeps daemon sockets and pid files (its paths.runtime_dir)."""
    if os.environ.get("BH_RUNTIME_DIR"):
        return Path(os.environ["BH_RUNTIME_DIR"]).expanduser()
    home = os.environ.get("BH_HOME") or os.environ.get("BROWSER_HARNESS_HOME")
    if home:
        return Path(home).expanduser() / "runtime"
    base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "browser-harness" / "runtime"


def krewd_daemons() -> set[str]:
    """Names of the Krewd chat daemons that have a pid or socket file."""
    try:
        names = {p.stem.removeprefix("bu-") for p in runtime_dir().glob(f"bu-{PREFIX}*") if p.suffix in (".pid", ".sock")}
    except OSError:
        return set()
    return {n for n in names if n.startswith(PREFIX)}


async def stop_daemon(name: str) -> bool:
    """Stop one chat's daemon. Its next browser-harness call starts a fresh one (on the tab it then picks)."""
    exe = shutil.which("browser-harness")
    if exe is None or not name.startswith(PREFIX):
        return False
    env = {k: v for k, v in os.environ.items() if k != "BH_AGENT_WORKSPACE"}
    env["BU_NAME"] = name
    try:
        proc = await asyncio.create_subprocess_exec(exe, "--reload", env=env, stdout=asyncio.subprocess.DEVNULL,
                                                    stderr=asyncio.subprocess.PIPE)
        _, err = await asyncio.wait_for(proc.communicate(), timeout=30)
    except (OSError, TimeoutError) as exc:
        log.warning("could not stop browser-harness daemon %s: %s", name, exc)
        return False
    if proc.returncode:
        log.warning("browser-harness --reload for %s: %s", name, err.decode(errors="replace").strip()[-300:])
        return False
    return True
