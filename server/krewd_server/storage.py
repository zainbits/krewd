"""Storage: what each chat takes on this machine, and freeing it without breaking an agent's session.

A chat's space is in four places:

- its timeline in PostgreSQL (`events`). Two kinds of rows there only repeat themselves: every change to a
  chat stores a full `session` snapshot, and every update of a tool call stores the whole tool call again.
  The app only ever uses the newest snapshot and the newest copy of each tool call (in the place of the
  first), so the older copies can go without changing what any phone shows.
- the files the owner attached (`<data dir>/common/attachments/<id prefix>-*`),
- the images tools returned (`<data dir>/common/tool-images/<id prefix>-*`),
- the agent's own transcript of its ACP session (Claude Code: `~/.claude/projects/*/<id>.jsonl` and
  `<id>/`; Codex: `~/.codex/sessions/**/rollout-*-<id>.jsonl`). The agent resumes the chat from it.

Freeing space keeps every live chat resumable: transcripts of chats that still exist are never touched,
and neither are attachments (the agent may read them again). Only transcripts Krewd's agents wrote (Claude
Code started through the SDK in Krewd's working folder, Codex started through codex-acp) that no chat
uses any more, and that nothing wrote to for a day, count as leftovers.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
CODEX_RE = re.compile(r"^rollout-.*-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$")
CODEX_ACP = "@agentclientprotocol/codex-acp"
QUIET_SECONDS = 24 * 3600  # a leftover transcript must have been left alone this long
FILE_QUIET_SECONDS = 3600  # and a leftover file this long (a chat being created right now may own it)
HEAD_BYTES = 64 * 1024
TRIM_CLIP = 600  # what Trim keeps of a tool call's input and output


@dataclass
class Transcript:
    agent_session: str
    agent: str  # "claude" | "codex"
    paths: list[Path] = field(default_factory=list)
    bytes: int = 0
    mtime: float = 0.0
    krewd: bool = False  # written by an agent Krewd started

    def json(self) -> dict[str, Any]:
        return {"agent_session": self.agent_session, "agent": self.agent, "bytes": self.bytes,
                "modified": self.mtime, "files": [str(p) for p in self.paths]}


def _size(path: Path) -> tuple[int, float]:
    """Bytes and newest modification time of a file or a folder tree (links are not followed)."""
    try:
        st = path.lstat()
    except OSError:
        return 0, 0.0
    if not path.is_dir() or path.is_symlink():
        return st.st_size, st.st_mtime
    total, newest = 0, st.st_mtime
    for root, _, files in os.walk(path):
        for name in files:
            try:
                fst = os.lstat(os.path.join(root, name))
            except OSError:
                continue
            total += fst.st_size
            newest = max(newest, fst.st_mtime)
    return total, newest


def _head(path: Path) -> str:
    try:
        with path.open("rb") as f:
            return f.read(HEAD_BYTES).decode("utf-8", "replace")
    except OSError:
        return ""


def _claude_by_krewd(head: str, workdir: Path) -> bool:
    """Started through the Agent SDK (as the ACP adapter does) in Krewd's working folder."""
    for line in head.splitlines():
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        if isinstance(entry, dict) and "entrypoint" in entry:
            return entry.get("entrypoint") == "sdk-ts" and entry.get("cwd") == str(workdir)
    return False


def _codex_by_krewd(head: str) -> bool:
    first = head.split("\n", 1)[0]
    try:
        meta = (json.loads(first) or {}).get("payload") or {}
    except (ValueError, AttributeError):
        return False
    return meta.get("originator") == CODEX_ACP


class Transcripts:
    """Finds agent transcripts on disk by ACP session id."""

    def __init__(self, workdir: Path, claude_dir: Path | None = None, codex_dir: Path | None = None):
        self.workdir = workdir
        self.claude_dir = claude_dir or Path(os.environ.get("CLAUDE_CONFIG_DIR") or Path.home() / ".claude")
        self.codex_dir = codex_dir or Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex")

    def scan(self) -> dict[str, Transcript]:
        found: dict[str, Transcript] = {}
        projects = self.claude_dir / "projects"
        if projects.is_dir():
            for project in projects.iterdir():
                if not project.is_dir() or project.is_symlink():
                    continue
                for path in project.iterdir():
                    if path.suffix != ".jsonl" or not UUID_RE.match(path.stem) or not path.is_file():
                        continue
                    t = found.setdefault(path.stem, Transcript(path.stem, "claude"))
                    t.krewd = t.krewd or _claude_by_krewd(_head(path), self.workdir)
                    for part in (path, project / path.stem):  # the folder holds its subagents and tool results
                        if part.exists() and not (part.is_dir() and part.is_symlink()):
                            size, mtime = _size(part)
                            t.paths.append(part)
                            t.bytes += size
                            t.mtime = max(t.mtime, mtime)
        sessions = self.codex_dir / "sessions"
        if sessions.is_dir():
            for root, _, files in os.walk(sessions):
                for name in files:
                    m = CODEX_RE.match(name)
                    if not m:
                        continue
                    path = Path(root) / name
                    t = found.setdefault(m.group(1), Transcript(m.group(1), "codex"))
                    size, mtime = _size(path)
                    t.paths.append(path)
                    t.bytes += size
                    t.mtime = max(t.mtime, mtime)
                    t.krewd = t.krewd or _codex_by_krewd(_head(path))
        return found

    def remove(self, t: Transcript) -> int:
        freed = 0
        roots = [self.claude_dir / "projects", self.codex_dir / "sessions"]
        for path in t.paths:
            if not any(path.is_relative_to(r) for r in roots):  # only ever inside the agents' own folders
                continue
            size, _ = _size(path)
            try:
                if path.is_dir() and not path.is_symlink():
                    shutil.rmtree(path)
                else:
                    path.unlink()
                freed += size
            except FileNotFoundError:
                continue
        return freed


def files_by_prefix(folder: Path, settled: bool = False) -> dict[str, list[tuple[Path, int]]]:
    """Krewd's files named `<8 hex>-...`, grouped by that prefix (the chat id's first 8 characters).

    `settled`: only files nothing wrote to for FILE_QUIET_SECONDS."""
    out: dict[str, list[tuple[Path, int]]] = {}
    if not folder.is_dir():
        return out
    cutoff = time.time() - FILE_QUIET_SECONDS
    for path in folder.iterdir():
        if not re.match(r"^[0-9a-f]{8}-", path.name) or path.is_symlink() or not path.is_file():
            continue
        try:
            st = path.stat()
        except OSError:
            continue
        if settled and st.st_mtime > cutoff:
            continue
        out.setdefault(path.name[:8], []).append((path, st.st_size))
    return out


def quiet(t: Transcript, now: float | None = None) -> bool:
    return (now or time.time()) - t.mtime > QUIET_SECONDS
