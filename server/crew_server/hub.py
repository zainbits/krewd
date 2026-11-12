"""The core: sessions, durable events, turns and approvals, on top of any ACP agent.

Each session belongs to one engine (an ACP agent command). One agent process per engine serves
all of its sessions. Turns run in a per-session worker, independent of any connected client;
everything a client needs is written to the events table first and then announced to waiters.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import signal
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from functools import partial
from typing import Any

import asyncpg

from . import db
from . import files as shared_files
from . import routines as rtn
from .acp import AcpError, AcpProcess, MethodNotFound
from .tool_images import ToolImages, is_empty, split as split_images
from .attachments import Attachments, UnknownUpload, prompt_blocks
from .config import Profile, Settings
from .logins import LoginError, Logins
from .profiles import PRESETS, Gateways, ProfileError, check_gateway, set_key, slug, valid_url, write_profiles
from .routines import Invalid

log = logging.getLogger("crew.hub")

TEXT_FLUSH_SECONDS = 0.3
CLIP = 4000
TITLE_MAX = 80
PREVIEW_MAX = 280
SCHEDULER_TICK = 15  # seconds between looks for due routines
PROBE_RETRY = 60.0  # seconds before probing an engine again after a failed probe
RUN_SUMMARY_MAX = 2000
NOTES_MAX = 8000
TRIGGER_NAME = re.compile(r"^[a-z0-9][a-z0-9 _-]{0,39}$")
HANDOVER_PROMPT = (
    "Crew is compacting this chat: the owner asked to start a fresh agent session that keeps only what "
    "matters. Write a handover for that next session, and nothing else: the goal, the decisions and facts "
    "established so far (with exact names, paths, ids and numbers), the owner's preferences and rules from "
    "this chat, what was done and its result, and what is still open or next. Be complete but brief; use "
    "short Markdown sections. Do not run tools for this."
)
SWITCH_PROMPT = (
    "Crew is moving this chat to another agent, {to}, at the owner's request. Write a handover for it, and "
    "nothing else: the goal, the decisions and facts established (with exact names, paths, ids and numbers), the "
    "owner's preferences and rules from this chat, what was done while you handled it and its result, and what "
    "is still open or next. Be complete but brief; use short Markdown sections. Do not run tools for this."
)


class NotFound(Exception):
    pass


class Conflict(Exception):
    pass


class _Cancelled(Exception):
    """Stop arrived while the turn was still opening the agent session."""


@dataclass
class Runtime:
    id: uuid.UUID
    engine: str
    agent_session: str | None
    generation: int = 0
    wake: asyncio.Event = field(default_factory=asyncio.Event)
    running: bool = False
    cancel_requested: bool = False  # Stop came before the agent session was ready
    browser_tab: dict[str, Any] | None = None
    worker: asyncio.Task | None = None
    text: dict[str, Any] | None = None
    flush_task: asyncio.Task | None = None
    tools: dict[str, dict[str, Any]] = field(default_factory=dict)
    run_counter: int = 0
    last_role: str | None = None
    assistant_run: str | None = None
    assistant_text: str = ""
    usage: dict[str, Any] | None = None


def _clip(value: Any, limit: int = CLIP) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return text if len(text) <= limit else text[:limit] + f"\n... ({len(text) - limit} more characters)"


def _content_text(content: list[dict[str, Any]] | None) -> tuple[str | None, list[str]]:
    """Text and changed file paths from ACP tool-call content."""
    texts: list[str] = []
    paths: list[str] = []
    for item in content or []:
        kind = item.get("type")
        if kind == "content":
            block = item.get("content") or {}
            if block.get("type") == "text":
                texts.append(block.get("text", ""))
        elif kind == "diff" and item.get("path"):
            paths.append(item["path"])
    return ("\n".join(texts) if texts else None), paths


def _plain(text: str) -> str:
    """Drop Markdown emphasis and code marks for one-line previews."""
    return re.sub(r"(\*\*|__|`+|^#+\s*)", "", text, flags=re.MULTILINE)


def normalise_config(raw: Any) -> list[dict[str, Any]]:
    """ACP configOptions as a flat, agent-agnostic list: id, name, category, current value, choices.

    Grouped select options are flattened (the group name goes into each choice's group field).
    """
    found: list[dict[str, Any]] = []
    for item in raw or []:
        if not isinstance(item, dict) or not item.get("id"):
            continue
        choices: list[dict[str, Any]] = []
        for choice in item.get("options") or []:
            if "options" in choice and "value" not in choice:
                for sub in choice.get("options") or []:
                    choices.append({"value": sub.get("value"), "name": sub.get("name") or sub.get("value"),
                                    "description": sub.get("description"), "group": choice.get("name")})
            else:
                choices.append({"value": choice.get("value"), "name": choice.get("name") or choice.get("value"),
                                "description": choice.get("description")})
        found.append({
            "id": item["id"],
            "name": item.get("name") or item["id"],
            "description": item.get("description"),
            "category": item.get("category"),
            "type": item.get("type", "select"),
            "current": item.get("currentValue"),
            "options": choices,
        })
    return found


def _reported_model(meta: Any) -> str | None:
    """The model an agent says it used, from usage metadata such as {"_claude/model": "..."}."""
    if not isinstance(meta, dict):
        return None
    for key, value in meta.items():
        if isinstance(value, str) and value and (key == "model" or key.endswith("/model")):
            return value
    return None


def _with_tabs(text: str, tabs: list[dict[str, Any]] | None) -> str:
    """The owner's message plus the browser tabs they added to the chat with "Add to chat"."""
    if not tabs:
        return text
    lines = ["The owner added these tabs of the agents' browser to this chat; use them "
             "(browser-harness can switch to a tab by its DevTools target id):"]
    lines += [f"- {t.get('title') or t.get('url')}: {t.get('url')} (target id {t.get('id')})" for t in tabs]
    return (text + "\n\n" if text else "") + "\n".join(lines)


def _first_line(text: str, limit: int) -> str:
    line = text.strip().splitlines()[0] if text.strip() else ""
    return line if len(line) <= limit else line[: limit - 1] + "…"


class Hub:
    def __init__(self, settings: Settings, pool: asyncpg.Pool):
        self.settings = settings
        self.pool = pool
        self.procs: dict[str, AcpProcess] = {}
        self.proc_locks: dict[str, asyncio.Lock] = {}
        self.runtimes: dict[uuid.UUID, Runtime] = {}
        self.by_agent_session: dict[tuple[str, str], uuid.UUID] = {}
        self.suppressed: set[str] = set()
        self.waiters: dict[uuid.UUID, tuple[uuid.UUID, asyncio.Future]] = {}
        self.cond = asyncio.Condition()
        self.emit_lock = asyncio.Lock()
        self.last_seq = 0
        self.closing = False
        self.engine_info: dict[str, dict[str, Any]] = {}
        self.attachments = Attachments(settings.data_dir / "common" / "attachments", settings.max_upload_mb * 1024 * 1024)
        self.tool_images = ToolImages(settings.data_dir / "common" / "tool-images")
        self.probing: set[str] = set()
        self.tab_owner: dict[str, uuid.UUID] = {}  # browser tab id -> session that last used it
        self.last_sent: dict[uuid.UUID, dict[str, Any]] = {}  # last session state emitted, to skip repeats
        self.default_timezone = rtn.local_timezone()
        self.scheduler: asyncio.Task | None = None
        self.gateways = Gateways(settings.port)
        self.probe_failed: dict[str, float] = {}  # engine id -> when its last probe failed
        self.restart_after_turn: set[str] = set()  # agents whose profile changed during a turn
        self.restarted: dict[str, float] = {}  # agent -> when its process was last stopped on purpose
        self.listed_on: dict[str, str] = {}  # agent -> the profile ("" its sign-in account) its options were listed on
        self.profiles_lock = asyncio.Lock()  # one profile change at a time
        self.logins = Logins()
        self.checking: set[str] = set()  # agents whose sign-in status is being checked

    # lifecycle

    async def start(self) -> None:
        await db.migrate(self.pool)
        self.attachments.prepare()
        self.tool_images.prepare()
        self.last_seq = await self.pool.fetchval("select coalesce(max(seq), 0) from events")
        for row in await self.pool.fetch("select * from engines"):
            self.engine_info[row["id"]] = {
                "agent": row["agent"], "config_options": row["config_options"] or [], "modes": row["modes"],
                "steering": row["steering"], "commands": row["commands"],
            }
        await self._recover()
        # Messages stored before a restart still run, in order.
        for row in await self.pool.fetch("select distinct session_id from messages where state in ('queued', 'steering')"):
            await self.pool.execute("update messages set state = 'queued' where session_id = $1 and state = 'steering'",
                                    row["session_id"])
            self._kick(await self._runtime(row["session_id"]))
        for row in await self.pool.fetch("select id, browser_tab from sessions where browser_tab is not null"):
            self.tab_owner.setdefault(row["browser_tab"]["id"], row["id"])
        self.scheduler = asyncio.create_task(self._schedule_loop())

    async def close(self) -> None:
        self.closing = True
        await self.logins.close()
        if self.scheduler:
            self.scheduler.cancel()
            await asyncio.gather(self.scheduler, return_exceptions=True)
        workers = [rt.worker for rt in self.runtimes.values() if rt.worker]
        for worker in workers:
            worker.cancel()
        await asyncio.gather(*workers, return_exceptions=True)
        for proc in list(self.procs.values()):
            await proc.stop()

    async def _recover(self) -> None:
        """A restart kills the agent processes, so in-flight turns and approvals cannot continue."""
        interrupted = await self.pool.fetch(
            "update sessions set status = 'error' where status in ('running', 'waiting') returning id"
        )
        await self.pool.execute(
            "update approvals set status = 'expired', decided_by = 'system', decided_at = now() where status = 'pending'"
        )
        await self.pool.execute(
            """update routine_runs set status = 'error', finished_at = now(),
                   summary = coalesce(summary, 'Crew restarted during this run.') where status = 'running'"""
        )
        for row in interrupted:
            await self.emit(row["id"], "error", {"message": "Crew restarted during this turn. Send a message to continue."})
            await self.emit(row["id"], "turn_end", {"stop_reason": "error"})

    # engines

    def engines(self) -> list[dict[str, Any]]:
        """Each engine with what it last advertised. Starts a background probe for engines never seen."""
        listing = []
        for engine in self.settings.engines.values():
            info = self.engine_info.get(engine.id) or {}
            unknown = not info.get("config_options") or info.get("steering") is None
            profile = self._profile_of(engine)
            if engine.gateway and self.listed_on.get(engine.id) != (profile.id if profile else ""):
                unknown = True  # its models were listed on another profile (or before this start): ask again
            retry = time.monotonic() - self.probe_failed.get(engine.id, -PROBE_RETRY) >= PROBE_RETRY
            problem = self._problem(engine)
            if unknown and retry and engine.id not in self.probing and not self.closing and not problem:
                self.probing.add(engine.id)
                asyncio.create_task(self._probe(engine.id))
            listing.append({
                "id": engine.id,
                "name": engine.name,
                "default": engine.id == self.settings.default_engine,
                "agent": info.get("agent"),
                "config_options": info.get("config_options") or [],
                "modes": info.get("modes"),
                "steering": bool(info.get("steering")),
                "commands": db.command_list(info.get("commands")),
                "profile": profile.id if profile else None,  # None: its own sign-in account (or no gateway kind)
                "problem": problem,
            })
        return listing

    def can_steer(self, engine_id: str) -> bool:
        proc = self.procs.get(engine_id)
        if proc and proc.alive:
            return proc.steering
        return bool((self.engine_info.get(engine_id) or {}).get("steering"))

    async def _remember_engine(self, engine_id: str, **info: Any) -> None:
        engine = self.settings.engines.get(engine_id)
        if info.get("config_options") is not None and engine and engine.gateway:
            profile = self._profile_of(engine)
            self.listed_on[engine_id] = profile.id if profile else ""
        current = self.engine_info.setdefault(engine_id, {})
        changes = {k: v for k, v in info.items() if v is not None and current.get(k) != v}
        if not changes:
            return
        current.update(changes)
        await self.pool.execute(
            """insert into engines (id, agent, config_options, modes, steering, commands)
               values ($1, $2, $3, $4, $5, $6)
               on conflict (id) do update set agent = excluded.agent, config_options = excluded.config_options,
                   modes = excluded.modes, steering = excluded.steering, commands = excluded.commands,
                   updated_at = now()""",
            engine_id,
            current.get("agent"),
            current.get("config_options"),
            current.get("modes"),
            current.get("steering"),
            current.get("commands"),
        )

    async def _probe(self, engine_id: str) -> None:
        """Open and drop a throwaway session to learn an engine's models and options."""
        on_gateway = self._profile_of(self.settings.engines[engine_id]) is not None
        try:
            proc = await self._proc(engine_id)
            self.settings.state_dir.mkdir(parents=True, exist_ok=True)
            # A gateway's model list can arrive after the first session (Claude Code's discovery), so an agent on a
            # profile is asked twice.
            for attempt in range(2 if on_gateway else 1):
                if attempt:
                    await asyncio.sleep(3)
                result = await proc.request(
                    "session/new", {"cwd": str(self.settings.state_dir), "mcpServers": []}, timeout=120
                )
                await self._remember_engine(
                    engine_id, config_options=normalise_config(result.get("configOptions")), modes=result.get("modes")
                )
                # Some agents (OpenCode) list their commands and skills only after answering session/new.
                for _ in range(30):
                    if self.closing or (self.engine_info.get(engine_id) or {}).get("commands") is not None:
                        break
                    await asyncio.sleep(0.1)
                if self.closing:
                    return
                caps = proc.capabilities.get("sessionCapabilities") or {}
                for method in ("delete", "close"):
                    if method in caps:
                        try:
                            await proc.request(f"session/{method}", {"sessionId": result["sessionId"]}, timeout=30)
                            break
                        except AcpError:
                            continue
        except Exception as exc:  # a probe is best effort; real tasks report their own errors
            if time.monotonic() - self.restarted.get(engine_id, -60.0) > 10:  # not just stopped on purpose
                self.probe_failed[engine_id] = time.monotonic()
            log.warning("could not probe %s: %s", engine_id, exc)
        finally:
            self.probing.discard(engine_id)

    # agents: which profile each runs on, and its own sign-in (the app: Settings > Agents)

    def _profile_of(self, engine: Any) -> Profile | None:
        """The gateway profile an agent runs on; None for its own sign-in account."""
        if not engine.gateway:
            return None
        profile = self.settings.profiles.get(self.settings.agent_profiles.get(engine.id, ""))
        return profile if profile and profile.url_for(engine.gateway) else None

    def _problem(self, engine: Any) -> str | None:
        """Why an agent cannot run as set up now: its profile has no key, or its sign-in account is not signed in."""
        profile = self._profile_of(engine)
        if profile:
            return self.gateways.problem(profile)
        if self._signed_in(engine) is False:
            return "Not signed in: sign in, or pick a profile, under Settings > Agents"
        return None

    def _signed_in(self, engine: Any) -> bool | None:
        """Cached sign-in state of an agent with a gateway kind; refreshed in the background when old."""
        if not engine.gateway:
            return None
        if self.logins.stale(engine.id) and engine.id not in self.checking and not self.closing:
            self.checking.add(engine.id)

            async def check() -> None:
                try:
                    await self.logins.check(engine)
                finally:
                    self.checking.discard(engine.id)

            asyncio.create_task(check())
        return self.logins.known(engine.id)

    def agents(self) -> dict[str, Any]:
        agents = []
        for engine in self.settings.engines.values():
            if not engine.gateway:
                continue
            status = self.logins.status.get(engine.id) or {}
            self._signed_in(engine)
            profile = self._profile_of(engine)
            agents.append({
                "engine": engine.id,
                "name": engine.name,
                "gateway": engine.gateway,
                "logged_in": status.get("logged_in"),
                "account": status.get("account"),
                "error": status.get("error"),
                "profile": profile.id if profile else None,
                "profile_choices": [p.id for p in self.settings.profiles.values() if p.url_for(engine.gateway)],
                "flow": self.logins.flow(engine.id),
            })
        return {"agents": agents}

    def _agent(self, engine_id: str) -> Any:
        engine = self.settings.engines.get(engine_id)
        if engine is None or not engine.gateway:
            raise NotFound("no such agent with a sign-in")
        return engine

    async def start_login(self, engine_id: str) -> dict[str, Any]:
        engine = self._agent(engine_id)
        try:
            return await self.logins.start(engine, self._signed_in_changed)
        except LoginError as exc:
            raise Invalid(str(exc)) from None

    async def submit_login(self, engine_id: str, code: str) -> dict[str, Any]:
        self._agent(engine_id)
        try:
            return await self.logins.submit(engine_id, code)
        except LoginError as exc:
            raise Invalid(str(exc)) from None

    async def cancel_login(self, engine_id: str) -> None:
        self._agent(engine_id)
        await self.logins.cancel(engine_id)

    async def logout(self, engine_id: str) -> dict[str, Any]:
        engine = self._agent(engine_id)
        try:
            status = await self.logins.logout(engine)
        except LoginError as exc:
            raise Invalid(str(exc)) from None
        await self._signed_in_changed(engine_id)
        return status

    async def set_profile(self, engine_id: str, profile_id: str | None) -> dict[str, Any]:
        """Run an agent on a profile (None: its own sign-in account). Every chat and routine on it follows."""
        engine = self._agent(engine_id)
        path = self.settings.profiles_file
        if path is None:
            raise Conflict("Profiles are not managed from the app on this server")
        profile = self.settings.profiles.get(profile_id) if profile_id else None
        if profile_id and not (profile and profile.url_for(engine.gateway)):
            raise Invalid(f"{profile_id} is not a profile {engine.name} can use")
        async with self.profiles_lock:
            if profile_id:
                self.settings.agent_profiles[engine_id] = profile_id
            else:
                self.settings.agent_profiles.pop(engine_id, None)
            write_profiles(path, self.settings.profiles, self.settings.agent_profiles)
        await self._restart_agent(engine_id)
        return next(a for a in self.agents()["agents"] if a["engine"] == engine_id)

    async def _signed_in_changed(self, engine_id: str) -> None:
        """After a sign-in or sign-out: an agent on its sign-in account starts again on it."""
        engine = self.settings.engines.get(engine_id)
        if engine and self._profile_of(engine) is None:
            await self._restart_agent(engine_id)

    async def _restart_agent(self, engine_id: str) -> None:
        """Stop the agent's idle process (it starts again on its current profile at the next turn) and relist its
        options. A running turn finishes first on the old settings; the process restarts after it."""
        # Probed again: the models differ between gateways and sign-ins. Until then the app shows no stale choices.
        self.listed_on.pop(engine_id, None)
        info = self.engine_info.get(engine_id)
        if info is not None:
            info["config_options"] = []
        # Under the engine's lock: a process still starting (a probe, a chat) is stopped too, once it is up.
        async with self.proc_locks.setdefault(engine_id, asyncio.Lock()):
            self.restarted[engine_id] = time.monotonic()
            self.probe_failed.pop(engine_id, None)
            proc = self.procs.get(engine_id)
            if proc and proc.alive:
                if any(rt.engine == engine_id and rt.running for rt in self.runtimes.values()):
                    self.restart_after_turn.add(engine_id)
                    return
                await proc.stop()

    # profiles (the app: Settings > Profiles)

    def profiles(self) -> dict[str, Any]:
        """The profiles (never their keys: only whether one is set and its last 4 characters) and the presets."""
        listing = []
        for profile in self.settings.profiles.values():
            key = profile.read_key()
            listing.append({
                "id": profile.id,
                "name": profile.name,
                "openai": profile.openai,
                "anthropic": profile.anthropic,
                "key_set": bool(key),
                "key_hint": key[-4:] if key and len(key) >= 12 else None,
                "agents": [a for a, p in self.settings.agent_profiles.items() if p == profile.id],
            })
        presets = [{k: v for k, v in p.items() if k != "key"} for p in PRESETS]
        return {"profiles": listing, "presets": presets, "managed": self.settings.profiles_file is not None}

    async def save_profile(self, profile_id: str | None, fields: dict[str, Any], key: str | None) -> dict[str, Any]:
        """Create (profile_id None) or change a profile; the gateway is asked for the key's models first."""
        path = self.settings.profiles_file
        if path is None:
            raise Conflict("Profiles are not managed from the app on this server")
        async with self.profiles_lock:
            profiles = dict(self.settings.profiles)
            current = profiles.get(profile_id) if profile_id else None
            if profile_id and current is None:
                raise NotFound("no such profile")
            preset = next((p for p in PRESETS if p["id"] == fields.get("preset")), None) if not current else None
            name = (fields.get("name") or (current.name if current else None) or (preset or {}).get("name") or "").strip()
            if not name:
                raise Invalid("Give the profile a name")
            urls = {}
            for kind in ("openai", "anthropic"):
                value = fields.get(kind)
                if value is None:
                    value = getattr(current, kind) if current else (preset or {}).get(kind)
                value = (value or "").strip().rstrip("/") or None
                if value and not valid_url(value):
                    raise Invalid(f"{value} is not an http(s) address")
                urls[kind] = value
            if not (urls["openai"] or urls["anthropic"]):
                raise Invalid("A profile needs an OpenAI (for Codex) or Anthropic (for Claude Code) address")
            new_id = current.id if current else slug(name, set(profiles))
            key_name = current.key if current else ((preset or {}).get("key") or f"{new_id.upper().replace('-', '_')}_API_KEY")
            key_file = current.key_file if current else path.parent / "keys.env"
            profile = Profile(id=new_id, name=name, key=key_name, key_file=key_file, **urls)
            key = (key or "").strip() or None
            usable_key = key or profile.read_key()
            if not usable_key:
                raise Invalid("Paste the gateway's API key")
            if key or not current or (current.openai, current.anthropic) != (profile.openai, profile.anthropic):
                try:
                    await asyncio.to_thread(check_gateway, profile.openai, profile.anthropic, usable_key)
                except ProfileError as exc:
                    raise Invalid(f"{name}: {exc}") from None
            for agent, used in self.settings.agent_profiles.items():
                engine = self.settings.engines.get(agent)
                if used == new_id and engine and not profile.url_for(engine.gateway):
                    raise Conflict(f"{engine.name} runs on it: keep its address, or move {engine.name} to another profile first")
            profiles[new_id] = profile
            write_profiles(path, profiles, self.settings.agent_profiles)
            if key:
                set_key(profile.key_file, profile.key, key)
            self.settings.profiles.clear()
            self.settings.profiles.update(profiles)
            self.gateways.forget(new_id)
        for agent, used in list(self.settings.agent_profiles.items()):
            if used == new_id:
                await self._restart_agent(agent)
        return next(p for p in self.profiles()["profiles"] if p["id"] == new_id)

    async def delete_profile(self, profile_id: str) -> None:
        path = self.settings.profiles_file
        if path is None:
            raise Conflict("Profiles are not managed from the app on this server")
        async with self.profiles_lock:
            profiles = dict(self.settings.profiles)
            profile = profiles.pop(profile_id, None)
            if profile is None:
                raise NotFound("no such profile")
            users = [self._engine_name(a) for a, p in self.settings.agent_profiles.items() if p == profile_id]
            if users:
                raise Conflict(f"{' and '.join(users)} run on it: move them to another profile first (Settings > Agents)")
            write_profiles(path, profiles, self.settings.agent_profiles)
            if profile.key_file == path.parent / "keys.env" and not any(p.key == profile.key for p in profiles.values()):
                set_key(profile.key_file, profile.key, None)  # a shared key file (key_file) is left alone
            self.settings.profiles.clear()
            self.settings.profiles.update(profiles)
            self.gateways.forget(profile_id)

    # events

    async def emit(self, session_id: uuid.UUID, kind: str, payload: dict[str, Any]) -> int:
        # Serialised so seq values commit in order and long-pollers never skip an event.
        async with self.emit_lock:
            seq = await self.pool.fetchval(
                "insert into events (session_id, kind, payload) values ($1, $2, $3) returning seq",
                session_id,
                kind,
                payload,
            )
        async with self.cond:
            self.last_seq = max(self.last_seq, seq)
            self.cond.notify_all()
        return seq

    async def emit_session(self, session_id: uuid.UUID) -> None:
        """Send the session's state, unless it is the same as the last one sent (the time stamp aside)."""
        try:
            session = await self.get_session(session_id)
        except NotFound:
            return
        same = {k: v for k, v in session.items() if k != "updated_at"}
        if self.last_sent.get(session_id) == same:
            return
        self.last_sent[session_id] = same
        await self.emit(session_id, "session", session)

    async def events(self, after: int, session_id: uuid.UUID | None = None, limit: int = 1000) -> list[dict[str, Any]]:
        if session_id is None:
            rows = await self.pool.fetch("select * from events where seq > $1 order by seq limit $2", after, limit)
        else:
            rows = await self.pool.fetch(
                "select * from events where session_id = $1 and seq > $2 order by seq limit $3", session_id, after, limit
            )
        return [db.event_json(r) for r in rows]

    async def wait_events(self, after: int, timeout: float, session_id: uuid.UUID | None = None) -> list[dict[str, Any]]:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while True:
            watermark = self.last_seq
            found = await self.events(after, session_id)
            remaining = deadline - loop.time()
            if found or remaining <= 0:
                return found
            async with self.cond:
                try:
                    await asyncio.wait_for(self.cond.wait_for(lambda: self.last_seq > watermark), remaining)
                except TimeoutError:
                    return []

    # sessions

    async def list_sessions(self, archived: bool = False) -> list[dict[str, Any]]:
        rows = await self.pool.fetch(
            f"""select {db.SESSION_COLUMNS},
                   (select count(*) from approvals a where a.session_id = s.id and a.status = 'pending') as pending
                from sessions s where archived = $1
                order by pinned desc, updated_at desc""",
            archived,
        )
        return [db.session_json(r, r["pending"]) for r in rows]

    async def get_session(self, session_id: uuid.UUID) -> dict[str, Any]:
        row = await self.pool.fetchrow(
            f"""select {db.SESSION_COLUMNS},
                   (select count(*) from approvals a where a.session_id = s.id and a.status = 'pending') as pending
                from sessions s where id = $1""",
            session_id,
        )
        if row is None:
            raise NotFound("session not found")
        return db.session_json(row, row["pending"])

    async def create_session(
        self,
        text: str,
        engine: str | None = None,
        mode: str | None = None,
        auto_approve: bool = False,
        config: dict[str, str] | None = None,
        attachments: list[str] | None = None,
        tabs: list[dict[str, Any]] | None = None,
        client_id: str | None = None,
    ) -> dict[str, Any]:
        if attachments:
            for upload_id in attachments:
                if not (self.attachments.incoming / f"{upload_id}.json").is_file():
                    raise NotFound(f"unknown upload {upload_id}")
        session_id = await self._insert_session(
            _first_line(text, TITLE_MAX) or self._upload_name(attachments) or "New task", engine, mode, auto_approve, config
        )
        await self.send(session_id, text, attachments, tabs=tabs, client_id=client_id)
        return await self.get_session(session_id)

    async def _insert_session(
        self, title: str, engine: str | None, mode: str | None, auto_approve: bool, config: dict[str, str] | None,
        title_locked: bool = False,
    ) -> uuid.UUID:
        engine = engine or self.settings.default_engine
        if engine not in self.settings.engines:
            raise NotFound(f"unknown engine {engine}")
        session_id = uuid.uuid4()
        await self.pool.execute(
            """insert into sessions (id, engine, title, title_locked, mode, auto_approve, config_wanted, agent,
                   config_options, commands)
               values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)""",
            session_id,
            engine,
            title,
            title_locked,
            mode,
            auto_approve,
            config or {},
            (self.engine_info.get(engine) or {}).get("agent"),
            (self.engine_info.get(engine) or {}).get("config_options"),
            (self.engine_info.get(engine) or {}).get("commands"),
        )
        return session_id

    def _upload_name(self, upload_ids: list[str] | None) -> str | None:
        for upload_id in upload_ids or []:
            meta = self.attachments.incoming / f"{upload_id}.json"
            if meta.is_file():
                return "File: " + json.loads(meta.read_text())["name"]
        return None

    async def update_session(self, session_id: uuid.UUID, changes: dict[str, Any]) -> dict[str, Any]:
        await self.get_session(session_id)
        if "title" in changes:
            await self.pool.execute(
                "update sessions set title = $2, title_locked = true where id = $1", session_id, changes["title"]
            )
        for column in ("auto_approve", "pinned", "archived"):
            if column in changes:
                await self.pool.execute(f"update sessions set {column} = $2 where id = $1", session_id, changes[column])
        if "mode" in changes:
            # A mode picked through the older modes list replaces any mode pick made as a config option.
            await self.pool.execute(
                """update sessions set mode = $2, config_wanted = config_wanted - coalesce(
                       (select array_agg(o->>'id') from jsonb_array_elements(config_options) o
                        where o->>'category' = 'mode'), '{}'::text[]) where id = $1""",
                session_id,
                changes["mode"],
            )
            rt = self.runtimes.get(session_id)
            proc = self.procs.get(rt.engine) if rt else None
            if rt and proc and proc.alive and rt.agent_session and rt.generation == proc.generation:
                await self._apply_mode(rt, proc, changes["mode"])
        if changes.get("config"):
            await self.pool.execute(
                "update sessions set config_wanted = config_wanted || $2 where id = $1", session_id, changes["config"]
            )
            rt = self.runtimes.get(session_id)
            proc = self.procs.get(rt.engine) if rt else None
            if rt and proc and proc.alive and rt.agent_session and rt.generation == proc.generation:
                for config_id, value in changes["config"].items():
                    await self._apply_config(rt, proc, config_id, value)
        if changes.get("auto_approve"):
            # Answer what is already waiting, the same way new requests will be answered.
            for approval_id, (sid, _) in list(self.waiters.items()):
                if sid == session_id:
                    row = await self.pool.fetchrow("select options from approvals where id = $1", approval_id)
                    option = self._auto_option(row["options"])
                    if option:
                        await self.decide(approval_id, option, decided_by="auto")
        await self.emit_session(session_id)
        return await self.get_session(session_id)

    async def delete_session(self, session_id: uuid.UUID) -> None:
        """Delete a task for good: stop its turn, forget its agent session, drop its history and messages."""
        await self.get_session(session_id)
        rt = self.runtimes.pop(session_id, None)
        if rt:
            await self._stop_turn(rt)
            if rt.worker:
                rt.worker.cancel()
                await asyncio.gather(rt.worker, return_exceptions=True)
            if rt.agent_session:
                self.by_agent_session.pop((rt.engine, rt.agent_session), None)
                proc = self.procs.get(rt.engine)
                caps = (proc.capabilities.get("sessionCapabilities") or {}) if proc and proc.alive else {}
                for method in ("delete", "close"):
                    if method in caps:
                        try:
                            await proc.request(f"session/{method}", {"sessionId": rt.agent_session}, timeout=30)
                            break
                        except AcpError:
                            continue
        for tab, owner in list(self.tab_owner.items()):
            if owner == session_id:
                del self.tab_owner[tab]
        # Events, approvals and messages go with it (on delete cascade).
        await self.pool.execute("delete from sessions where id = $1", session_id)
        self.last_sent.pop(session_id, None)

    async def send(
        self,
        session_id: uuid.UUID,
        text: str,
        attachments: list[str] | None = None,
        tabs: list[dict[str, Any]] | None = None,
        delivery: str | None = None,
        client_id: str | None = None,
        purpose: str | None = None,
        meta: dict[str, Any] | None = None,
        message_id: uuid.UUID | None = None,
        preview: str | None = None,
    ) -> dict[str, Any]:
        """Store a message, then deliver it: steer it into the running turn, queue it, or interrupt and send.

        Without a delivery choice it steers where the engine supports steering, else it queues.
        A repeated client_id returns the stored message instead of sending it twice.
        """
        rt = await self._runtime(session_id)
        if client_id:
            row = await self.pool.fetchrow(
                "select * from messages where session_id = $1 and client_id = $2", session_id, client_id
            )
            if row:
                return db.message_json(row)
        try:
            files = self.attachments.claim(session_id, attachments or [])
        except UnknownUpload as exc:
            raise NotFound(f"unknown upload {exc}") from None
        tabs = [{k: str(t.get(k) or "") for k in ("id", "title", "url")} for t in tabs or []]
        wanted = delivery or ("steer" if self.can_steer(rt.engine) else "queue")
        if wanted == "steer" and not rt.running:
            wanted = "queue"  # nothing to steer into: it simply starts the next turn
        message_id = message_id or uuid.uuid4()
        try:
            await self.pool.execute(
                """insert into messages (id, session_id, client_id, text, attachments, tabs, delivery, priority,
                       purpose, meta)
                   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)""",
                message_id, session_id, client_id, text, files, tabs, wanted, 0 if wanted == "interrupt" else 1,
                purpose, meta,
            )
        except asyncpg.UniqueViolationError:
            row = await self.pool.fetchrow(
                "select * from messages where session_id = $1 and client_id = $2", session_id, client_id
            )
            return db.message_json(row)
        payload: dict[str, Any] = {"text": text, "message_id": str(message_id), "delivery": wanted}
        if files:
            payload["attachments"] = files
        if tabs:
            payload["tabs"] = tabs
        if client_id:
            payload["client_id"] = client_id
        if purpose:
            payload["purpose"] = purpose
            payload["meta"] = meta or {}
        seq = await self.emit(session_id, "user", payload)
        await self.pool.execute("update messages set event_seq = $2 where id = $1", message_id, seq)
        preview = preview or text or ", ".join(f["name"] for f in files) or ", ".join(t["title"] or t["url"] for t in tabs)
        await self.pool.execute(
            "update sessions set last_text = $2, updated_at = now(), archived = false where id = $1",
            session_id,
            _first_line(preview, PREVIEW_MAX),
        )
        if wanted == "interrupt" and rt.running:
            await self._stop_turn(rt)
        elif wanted == "steer" and rt.running and self.can_steer(rt.engine):
            await self._steer(rt, message_id)
        self._kick(rt)
        return db.message_json(await self.pool.fetchrow("select * from messages where id = $1", message_id))

    async def _steer(self, rt: Runtime, message_id: uuid.UUID) -> None:
        """Inject a stored message into the running turn. If no turn is running it stays queued."""
        proc = self.procs.get(rt.engine)
        if not (proc and proc.alive and rt.agent_session and rt.generation == proc.generation):
            return
        row = await self.pool.fetchrow(
            "update messages set state = 'steering' where id = $1 and state = 'queued' returning *", message_id
        )
        if row is None:
            return
        outcome = None
        try:
            result = await proc.request(
                "_session/steering",
                {
                    "sessionId": rt.agent_session,
                    "prompt": prompt_blocks(_with_tabs(row["text"], row["tabs"]), row["attachments"] or []),
                    "_meta": {"steering": {"idleBehavior": "promptRequired"}},
                },
                timeout=30,
            )
            outcome = result.get("outcome")
        except (AcpError, TimeoutError) as exc:
            log.warning("steering failed for %s, queueing instead: %s", rt.id, exc)
        if outcome == "injected":
            await self._delivered(message_id, "steered", "steering")
        else:  # promptRequired (the turn just ended) or an error: run it as the next prompt
            await self.pool.execute("update messages set state = 'queued' where id = $1 and state = 'steering'", message_id)

    async def _delivered(self, message_id: uuid.UUID, how: str, from_state: str = "queued") -> asyncpg.Record | None:
        row = await self.pool.fetchrow(
            """update messages set state = 'delivered', delivered_how = $2, delivered_at = now()
               where id = $1 and state = $3 returning *""",
            message_id, how, from_state,
        )
        if row:
            await self.emit(row["session_id"], "message", db.message_json(row))
        return row

    def _kick(self, rt: Runtime) -> None:
        rt.wake.set()
        if rt.worker is None or rt.worker.done():
            rt.worker = asyncio.create_task(self._worker(rt))

    async def cancel(self, session_id: uuid.UUID) -> None:
        """Stop: end the running turn and drop the messages still waiting behind it."""
        rt = await self._runtime(session_id)
        for row in await self.pool.fetch(
            "update messages set state = 'cancelled' where session_id = $1 and state = 'queued' returning *", session_id
        ):
            await self.emit(session_id, "message", db.message_json(row))
            await self._run_finished(row["id"], "cancelled", "Stopped before it started.")
        await self._stop_turn(rt)

    async def _stop_turn(self, rt: Runtime) -> None:
        if rt.running:
            rt.cancel_requested = True
        for approval_id, (sid, _) in list(self.waiters.items()):
            if sid == rt.id:
                await self._resolve(approval_id, None, "cancelled", "user")
        proc = self.procs.get(rt.engine)
        if proc and proc.alive and rt.agent_session:
            await proc.notify("session/cancel", {"sessionId": rt.agent_session})

    def restart(self) -> None:
        """Emergency restart from the app: stop every agent process group, then exit.

        systemd starts the server again; startup marks the interrupted turns as errors. The browser is
        a separate unit and is left alone.
        """
        async def go() -> None:
            await asyncio.sleep(0.5)  # let the HTTP answer go out
            log.warning("restart requested from the app: stopping agents and exiting")
            groups = [p.pid for p in self.procs.values() if p.pid]
            for sig in (signal.SIGTERM, signal.SIGKILL):
                for pid in groups:
                    try:
                        os.killpg(pid, sig)
                    except (ProcessLookupError, PermissionError):
                        pass
                await asyncio.sleep(2)
            os._exit(3)

        asyncio.create_task(go())

    def tab_busy(self, tab_id: str) -> uuid.UUID | None:
        """The session whose agent is using this tab right now: it last drove the tab and a turn is running."""
        owner = self.tab_owner.get(tab_id)
        rt = self.runtimes.get(owner) if owner else None
        return owner if rt and rt.running else None

    async def browser_activity(self, tab: dict[str, Any]) -> uuid.UUID | None:
        """A tab opened or navigated without the owner touching it: link it to the session doing that.

        Detected from the browser side, so it works for any engine: the one session with a running turn
        (or, if several run, the one in the middle of a tool call) is taken to be the one browsing.
        """
        running = [rt for rt in self.runtimes.values() if rt.running]
        if len(running) > 1:
            running = [rt for rt in running
                       if any(t.get("status") in (None, "pending", "in_progress") for t in rt.tools.values())]
        if len(running) != 1:
            return None
        rt = running[0]
        info = {"id": tab["id"], "url": tab.get("url", ""), "title": tab.get("title") or tab.get("url", "")}
        self.tab_owner[info["id"]] = rt.id
        if rt.browser_tab and rt.browser_tab["id"] == info["id"] and rt.browser_tab["url"] == info["url"]:
            return rt.id
        rt.browser_tab = info
        await self.pool.execute("update sessions set browser_tab = $2 where id = $1", rt.id, info)
        await self.emit(rt.id, "browser", info)
        await self.emit_session(rt.id)
        return rt.id

    # approvals

    async def approvals(self, status: str | None = "pending") -> list[dict[str, Any]]:
        if status:
            rows = await self.pool.fetch("select * from approvals where status = $1 order by created_at", status)
        else:
            rows = await self.pool.fetch("select * from approvals order by created_at desc limit 200")
        return [db.approval_json(r) for r in rows]

    async def decide(self, approval_id: uuid.UUID, option_id: str, decided_by: str = "user") -> dict[str, Any]:
        row = await self.pool.fetchrow("select * from approvals where id = $1", approval_id)
        if row is None:
            raise NotFound("approval not found")
        if row["status"] != "pending" or approval_id not in self.waiters:
            raise Conflict(f"approval is {row['status'] if row['status'] != 'pending' else 'no longer waiting'}")
        option = next((o for o in row["options"] if o.get("optionId") == option_id), None)
        if option is None:
            raise NotFound("unknown option")
        status = "approved" if str(option.get("kind", "")).startswith("allow") else "denied"
        return await self._resolve(approval_id, option_id, status, decided_by)

    async def _resolve(self, approval_id: uuid.UUID, option_id: str | None, status: str, decided_by: str) -> dict[str, Any]:
        row = await self.pool.fetchrow(
            """update approvals set status = $2, option_id = $3, decided_by = $4, decided_at = now()
               where id = $1 returning *""",
            approval_id,
            status,
            option_id,
            decided_by,
        )
        approval = db.approval_json(row)
        await self.emit(row["session_id"], "approval", approval)
        waiter = self.waiters.pop(approval_id, None)
        if waiter and not waiter[1].done():
            waiter[1].set_result(option_id)
        return approval

    @staticmethod
    def _auto_option(options: list[dict[str, Any]]) -> str | None:
        for wanted in ("allow_once", "allow_always"):
            for option in options:
                if option.get("kind") == wanted:
                    return option.get("optionId")
        return None

    # agent processes

    async def _proc(self, engine_id: str) -> AcpProcess:
        lock = self.proc_locks.setdefault(engine_id, asyncio.Lock())
        async with lock:
            proc = self.procs.get(engine_id)
            if proc and proc.alive:
                return proc
            engine = self.settings.engines[engine_id]
            env = dict(engine.env)
            profile = self._profile_of(engine)
            try:
                if profile:
                    env.update(await self.gateways.engine_env(engine, profile))
            except ProfileError as exc:
                raise AcpError(f"Could not start {engine.name}: {exc}") from exc
            proc = AcpProcess(
                engine_id=engine_id,
                command=engine.command,
                env=env,
                cwd=self.settings.workdir,
                log_dir=self.settings.state_dir,
                on_notification=partial(self._on_notification, engine_id),
                on_request=partial(self._on_request, engine_id),
                on_exit=self._on_exit,
            )
            try:
                await proc.start()
            except (OSError, AcpError, TimeoutError) as exc:
                await proc.stop()
                raise AcpError(f"Could not start {engine.name}: {exc}") from exc
            except BaseException:  # cancelled while starting: do not leave the process behind
                await asyncio.shield(proc.stop())
                raise
            self.procs[engine_id] = proc
            log.info("started %s (generation %s)", engine_id, proc.generation)
            await self._remember_engine(engine_id, agent=proc.init_result.get("agentInfo"), steering=proc.steering)
            return proc

    async def _on_exit(self, proc: AcpProcess) -> None:
        if self.procs.get(proc.engine_id) is proc:
            del self.procs[proc.engine_id]
        if self.closing:
            return
        log.warning("%s agent exited", proc.engine_id)
        for approval_id, (sid, _) in list(self.waiters.items()):
            rt = self.runtimes.get(sid)
            if rt and rt.engine == proc.engine_id:
                await self._resolve(approval_id, None, "expired", "system")

    async def _runtime(self, session_id: uuid.UUID) -> Runtime:
        rt = self.runtimes.get(session_id)
        if rt:
            return rt
        row = await self.pool.fetchrow("select engine, agent_session from sessions where id = $1", session_id)
        if row is None:
            raise NotFound("session not found")
        rt = Runtime(id=session_id, engine=row["engine"], agent_session=row["agent_session"])
        self.runtimes[session_id] = rt
        if rt.agent_session:
            self.by_agent_session[(rt.engine, rt.agent_session)] = session_id
        return rt

    async def _ensure_loaded(self, rt: Runtime) -> AcpProcess:
        proc = await self._proc(rt.engine)
        cwd = str(self.settings.workdir)
        reopened = False
        if rt.agent_session is None:
            result = await proc.request("session/new", {"cwd": cwd, "mcpServers": []}, timeout=120)
            rt.agent_session = result["sessionId"]
            rt.generation = proc.generation
            self.by_agent_session[(rt.engine, rt.agent_session)] = rt.id
            modes = result.get("modes") or {}
            options = normalise_config(result.get("configOptions"))
            await self.pool.execute(
                """update sessions set agent_session = $2, modes = $3, mode = coalesce(mode, $4),
                       config_options = $5, agent = $6 where id = $1""",
                rt.id,
                rt.agent_session,
                modes or None,
                modes.get("currentModeId"),
                options,
                proc.init_result.get("agentInfo"),
            )
            await self._remember_engine(rt.engine, config_options=options or None, modes=modes or None)
        elif rt.generation != proc.generation:
            params = {"sessionId": rt.agent_session, "cwd": cwd, "mcpServers": []}
            caps = proc.capabilities
            if "resume" in (caps.get("sessionCapabilities") or {}):
                try:
                    await proc.request("session/resume", params, timeout=120)
                    reopened = True
                except AcpError as exc:
                    log.warning("session/resume failed, trying session/load: %s", exc)
            if not reopened and caps.get("loadSession"):
                self.suppressed.add(rt.agent_session)
                try:
                    await proc.request("session/load", params, timeout=300)
                    reopened = True
                finally:
                    self.suppressed.discard(rt.agent_session)
            if not reopened:
                raise AcpError("This agent cannot reopen the session after a restart. Start a new task.")
            rt.generation = proc.generation
        else:
            return proc
        row = await self.pool.fetchrow("select mode, config_wanted, config_options from sessions where id = $1", rt.id)
        if row["mode"]:
            await self._apply_mode(rt, proc, row["mode"])
        current = {o["id"]: o.get("current") for o in row["config_options"] or []}
        for config_id, value in (row["config_wanted"] or {}).items():
            # A reopened session may not remember the pick, so it is sent again; a new one only if it differs.
            if reopened or current.get(config_id) != value:
                await self._apply_config(rt, proc, config_id, value, emit=False)
        await self.emit_session(rt.id)
        return proc

    async def _apply_mode(self, rt: Runtime, proc: AcpProcess, mode: str) -> None:
        try:
            await proc.request("session/set_mode", {"sessionId": rt.agent_session, "modeId": mode}, timeout=30)
        except AcpError as exc:
            await self.emit(rt.id, "error", {"message": f"Could not switch to mode {mode}: {exc}"})

    async def _apply_config(self, rt: Runtime, proc: AcpProcess, config_id: str, value: Any, emit: bool = True) -> None:
        try:
            result = await proc.request(
                "session/set_config_option",
                {"sessionId": rt.agent_session, "configId": config_id, "value": value},
                timeout=30,
            )
        except AcpError as exc:
            await self.emit(rt.id, "error", {"message": f"Could not set {config_id} to {value}: {exc}"})
            return
        if result.get("configOptions"):
            options = normalise_config(result["configOptions"])
            await self.pool.execute("update sessions set config_options = $2 where id = $1", rt.id, options)
        else:
            await self.pool.execute(
                """update sessions set config_options = (
                       select jsonb_agg(case when o->>'id' = $2 then o || jsonb_build_object('current', $3::jsonb)
                                        else o end)
                       from jsonb_array_elements(config_options) o) where id = $1 and config_options is not null""",
                rt.id,
                config_id,
                value,
            )
        if emit:
            await self.emit_session(rt.id)

    # turns

    async def _worker(self, rt: Runtime) -> None:
        """Runs a session's stored messages one turn at a time, oldest first ("interrupt" ones first)."""
        while True:
            rt.wake.clear()
            row = await self.pool.fetchrow(
                """select id from messages where session_id = $1 and state = 'queued'
                   order by priority, created_at limit 1""",
                rt.id,
            )
            if row is None:
                await rt.wake.wait()
                continue
            message = await self._delivered(row["id"], "started")
            if message is None:
                continue
            if message["purpose"] == "switch" and not rt.agent_session:
                # Nothing to hand over yet: change agents at once, without a turn.
                try:
                    await self._switched(rt, message["meta"] or {}, None)
                except Exception:
                    log.exception("switch failed for %s", rt.id)
                continue
            try:
                await self._run_turn(
                    rt, message["text"], message["attachments"], message["tabs"], message["id"],
                    purpose=message["purpose"], meta=message["meta"],
                )
            except Exception:
                log.exception("turn failed for %s", rt.id)

    async def _set_status(self, rt: Runtime, status: str) -> None:
        await self.pool.execute("update sessions set status = $2, updated_at = now() where id = $1", rt.id, status)
        await self.emit_session(rt.id)

    async def _run_turn(
        self,
        rt: Runtime,
        text: str,
        files: list[dict[str, Any]] | None = None,
        tabs: list[dict[str, Any]] | None = None,
        message_id: uuid.UUID | None = None,
        purpose: str | None = None,
        meta: dict[str, Any] | None = None,
    ) -> None:
        rt.assistant_run, rt.assistant_text = None, ""
        rt.running, rt.cancel_requested = True, False
        await self._set_status(rt, "running")
        await self.emit(rt.id, "turn_start", {"message_id": str(message_id) if message_id else None})
        if message_id and purpose == "routine":
            await self._run_started(message_id)
        stop_reason = "error"
        usage = None
        carry = None
        try:
            if purpose == "routine" and rt.agent_session:
                # Every run starts in a fresh agent session: a routine's context does not grow run after run.
                # The last run's session stays until here, so the owner can follow up on it in the chat.
                # What runs learn lives in the routine's notes, which every run's prompt carries.
                await self._drop_agent_session(rt)
                await self.emit_session(rt.id)
            proc = await self._ensure_loaded(rt)
            if rt.cancel_requested:
                raise _Cancelled()
            prompt_text = _with_tabs(text, tabs)
            if purpose == "compact" and (meta or {}).get("how") == "native":
                prompt_text = "/compact"
            else:
                carry = await self.pool.fetchval("select carry from sessions where id = $1", rt.id)
                if carry:
                    prompt_text = carry + "\n\n---\n\nThe next message in the chat:\n\n" + prompt_text
            result = await proc.request(
                "session/prompt",
                {"sessionId": rt.agent_session, "prompt": prompt_blocks(prompt_text, files or [])},
            )
            if carry:
                await self.pool.execute("update sessions set carry = null where id = $1", rt.id)
            stop_reason = result.get("stopReason", "end_turn")
            usage = result.get("usage")
        except _Cancelled:
            stop_reason = "cancelled"
        except AcpError as exc:
            await self._flush_text(rt)
            await self.emit(rt.id, "error", {"message": str(exc)})
        finally:
            rt.running = False
        if rt.engine in self.restart_after_turn and not any(r.engine == rt.engine and r.running for r in self.runtimes.values()):
            self.restart_after_turn.discard(rt.engine)
            await self._restart_agent(rt.engine)  # its profile changed during the turn
        await self._flush_text(rt)
        preview = _first_line(_plain(rt.assistant_text), PREVIEW_MAX) if rt.assistant_text else None
        await self.pool.execute(
            """update sessions set status = $2, usage = coalesce($3, usage),
                   last_text = coalesce($4, last_text), updated_at = now() where id = $1""",
            rt.id,
            "error" if stop_reason == "error" else "idle",
            rt.usage,
            preview,
        )
        end: dict[str, Any] = {"stop_reason": stop_reason, "usage": usage, "preview": preview}
        handed = shared_files.mentioned(rt.assistant_text, self.settings.shared)
        if handed:
            end["files"] = handed
        await self.emit(rt.id, "turn_end", end)
        if purpose == "compact":
            await self._compacted(rt, stop_reason, (meta or {}).get("how"))
        if purpose == "switch":
            await self._switched(rt, meta or {}, rt.assistant_text.strip() if stop_reason == "end_turn" else None)
        if message_id and purpose == "routine":
            status = {"end_turn": "done", "cancelled": "cancelled", "error": "error"}.get(stop_reason, "done")
            await self._run_finished(message_id, status, _clip(rt.assistant_text.strip(), RUN_SUMMARY_MAX) or None)
        await self.emit_session(rt.id)

    # agent -> client

    def _session_for(self, engine_id: str, agent_session: str | None) -> Runtime | None:
        if not agent_session or agent_session in self.suppressed:
            return None
        session_id = self.by_agent_session.get((engine_id, agent_session))
        return self.runtimes.get(session_id) if session_id else None

    async def _on_notification(self, engine_id: str, method: str, params: dict[str, Any]) -> None:
        if method != "session/update":
            return
        rt = self._session_for(engine_id, params.get("sessionId"))
        if rt is None:
            update = params.get("update") or {}
            if update.get("sessionUpdate") == "available_commands_update":
                # From a probe or another session Crew does not track: still the engine's current list.
                await self._remember_engine(engine_id, commands=db.command_list(update.get("availableCommands")))
            return
        update = params.get("update") or {}
        kind = update.get("sessionUpdate")
        if kind in ("agent_message_chunk", "agent_thought_chunk"):
            block = update.get("content") or {}
            if block.get("type") == "text" and block.get("text"):
                role = "assistant" if kind == "agent_message_chunk" else "thought"
                await self._text(rt, role, update.get("messageId"), block["text"])
        elif kind in ("tool_call", "tool_call_update"):
            await self._flush_text(rt)
            await self._tool(rt, update)
        elif kind == "plan":
            await self._flush_text(rt)
            await self.emit(rt.id, "plan", {"entries": update.get("entries", [])})
        elif kind == "session_info_update" and update.get("title"):
            await self.pool.execute(
                "update sessions set title = $2 where id = $1 and not title_locked",
                rt.id,
                _first_line(update["title"], TITLE_MAX),
            )
            await self.emit_session(rt.id)
        elif kind == "usage_update":
            usage = {key: update[key] for key in ("used", "size", "cost") if key in update}
            if usage != rt.usage:
                # Saved and sent at once, so the app's context ring moves during a turn, not only at its end.
                rt.usage = usage
                await self.pool.execute("update sessions set usage = $2 where id = $1", rt.id, usage)
                await self.emit_session(rt.id)
            model = _reported_model(update.get("_meta"))
            if model:
                changed = await self.pool.fetchval(
                    "update sessions set model_used = $2 where id = $1 and model_used is distinct from $2 returning id",
                    rt.id,
                    model,
                )
                if changed:
                    await self.emit_session(rt.id)
        elif kind == "available_commands_update":
            commands = db.command_list(update.get("availableCommands"))
            changed = await self.pool.fetchval(
                "update sessions set commands = $2 where id = $1 and commands is distinct from $2 returning id",
                rt.id,
                commands,
            )
            await self._remember_engine(engine_id, commands=commands)
            if changed:
                await self.emit_session(rt.id)
        elif kind == "config_option_update" and update.get("configOptions") is not None:
            options = normalise_config(update["configOptions"])
            await self.pool.execute("update sessions set config_options = $2 where id = $1", rt.id, options)
            await self.emit_session(rt.id)
        elif kind == "current_mode_update" and update.get("currentModeId"):
            await self.pool.execute(
                """update sessions set mode = $2, config_options = (
                       select jsonb_agg(case when o->>'category' = 'mode'
                                        then o || jsonb_build_object('current', $2::text) else o end)
                       from jsonb_array_elements(config_options) o) where id = $1""",
                rt.id,
                update["currentModeId"],
            )
            await self.emit_session(rt.id)

    async def _on_request(self, engine_id: str, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if method != "session/request_permission":
            raise MethodNotFound(method)
        rt = self._session_for(engine_id, params.get("sessionId"))
        if rt is None:
            update = params.get("update") or {}
            if update.get("sessionUpdate") == "available_commands_update":
                # From a probe or another session Crew does not track: still the engine's current list.
                await self._remember_engine(engine_id, commands=db.command_list(update.get("availableCommands")))
            return {"outcome": {"outcome": "cancelled"}}
        await self._flush_text(rt)
        call = params.get("toolCall") or {}
        known = rt.tools.get(call.get("toolCallId", ""), {})
        tool = {
            "tool_call_id": call.get("toolCallId"),
            "title": call.get("title") or known.get("title") or "Tool",
            "kind": call.get("kind") or known.get("kind"),
            "input": _clip(call["rawInput"]) if call.get("rawInput") else known.get("input"),
        }
        options = [
            {"optionId": o.get("optionId"), "name": o.get("name"), "kind": o.get("kind")}
            for o in params.get("options", [])
        ]
        approval_id = uuid.uuid4()
        await self.pool.execute(
            "insert into approvals (id, session_id, tool, options) values ($1, $2, $3, $4)",
            approval_id,
            rt.id,
            tool,
            options,
        )
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self.waiters[approval_id] = (rt.id, future)
        auto = await self.pool.fetchval("select auto_approve from sessions where id = $1", rt.id)
        auto_option = self._auto_option(options) if auto else None
        if auto_option:
            await self._resolve(approval_id, auto_option, "approved", "auto")
        else:
            await self._set_status(rt, "waiting")
            row = await self.pool.fetchrow("select * from approvals where id = $1", approval_id)
            await self.emit(rt.id, "approval", db.approval_json(row))
        option_id = await future
        if not auto_option:
            await self._set_status(rt, "running")
        if option_id is None:
            return {"outcome": {"outcome": "cancelled"}}
        return {"outcome": {"outcome": "selected", "optionId": option_id}}

    async def _text(self, rt: Runtime, role: str, message_id: str | None, text: str) -> None:
        if role != rt.last_role:
            rt.run_counter += 1
            rt.last_role = role
        # Each contiguous run gets its own id, so thought -> answer -> thought keeps its order.
        run_id = f"{message_id or 'run'}:{role}:{rt.run_counter}"
        current = rt.text
        if current and current["message_id"] != run_id:
            await self._flush_text(rt)
            current = None
        if current is None:
            rt.text = {"message_id": run_id, "role": role, "text": ""}
        rt.text["text"] += text
        if role == "assistant":
            if run_id != rt.assistant_run:
                rt.assistant_run, rt.assistant_text = run_id, ""
            rt.assistant_text += text
        if rt.flush_task is None:
            rt.flush_task = asyncio.create_task(self._delayed_flush(rt))

    async def _delayed_flush(self, rt: Runtime) -> None:
        await asyncio.sleep(TEXT_FLUSH_SECONDS)
        rt.flush_task = None
        await self._flush_text(rt)

    async def _flush_text(self, rt: Runtime) -> None:
        task, rt.flush_task = rt.flush_task, None
        if task and task is not asyncio.current_task():
            task.cancel()
        current, rt.text = rt.text, None
        if current and current["text"]:
            await self.emit(rt.id, "text", current)

    async def _tool(self, rt: Runtime, update: dict[str, Any]) -> None:
        tool_id = update.get("toolCallId")
        if not tool_id:
            return
        rt.last_role = None
        before = dict(rt.tools.get(tool_id, {}))
        tool = rt.tools.setdefault(tool_id, {"tool_call_id": tool_id})
        for key in ("title", "kind", "status"):
            if update.get(key):
                tool[key] = update[key]
        if update.get("rawInput"):
            tool["input"] = _clip(update["rawInput"])
        content, images = split_images(update.get("content"))
        raw, raw_images = split_images(update.get("rawOutput"))
        text, paths = _content_text(content)
        if text:
            tool["output"] = _clip(text)
        elif not is_empty(raw) and "output" not in tool:
            tool["output"] = _clip(raw)
        if paths:
            tool["paths"] = paths
        # Images come back as base64 (content blocks, or raw output): saved as files, never shown as text.
        names = self.tool_images.save(rt.id, images or raw_images)
        if names:
            tool["images"] = list(dict.fromkeys(tool.get("images", []) + names))
        if tool != before:
            await self.emit(rt.id, "tool", dict(tool))

    # compaction

    async def compact(self, session_id: uuid.UUID) -> dict[str, Any]:
        """Compact a chat's agent context, after the running turn if there is one.

        Where the agent advertises a `compact` slash command (Claude Code, Codex) Crew sends it. Otherwise
        the agent writes a handover, Crew opens a fresh agent session in the same chat and gives it the
        handover with the next message. The chat history in Crew stays as it is either way.
        """
        row = await self.pool.fetchrow("select agent_session, commands from sessions where id = $1", session_id)
        if row is None:
            raise NotFound("session not found")
        if not row["agent_session"]:
            raise Conflict("Nothing to compact yet: this chat has no agent context")
        waiting = await self.pool.fetchrow(
            "select * from messages where session_id = $1 and purpose = 'compact' and state in ('queued', 'steering')",
            session_id,
        )
        if waiting:
            return db.message_json(waiting)
        how = "native" if "compact" in db.command_names(row["commands"]) else "handover"
        text = "/compact" if how == "native" else HANDOVER_PROMPT
        return await self.send(
            session_id, text, delivery="queue", purpose="compact", meta={"how": how}, preview="Compacting the context"
        )

    async def _compacted(self, rt: Runtime, stop_reason: str, how: str | None) -> None:
        if stop_reason != "end_turn":
            await self.emit(rt.id, "compact", {"how": how, "ok": False})
            return
        if how == "handover":
            summary = rt.assistant_text.strip()
            if not summary:
                await self.emit(rt.id, "compact", {"how": how, "ok": False})
                return
            carry = ("Handover from the earlier part of this chat, which was compacted (written by the agent before "
                     "the reset):\n\n" + summary)
            await self._drop_agent_session(rt, carry)
        await self.emit(rt.id, "compact", {"how": how, "ok": True})

    async def _drop_agent_session(self, rt: Runtime, carry: str | None = None) -> None:
        """End the chat's agent session, so its next turn starts a fresh one (empty context).

        The chat and its history stay; only the agent forgets. `carry` is put in front of the next prompt.
        """
        old = rt.agent_session
        proc = self.procs.get(rt.engine)
        if old:
            self.by_agent_session.pop((rt.engine, old), None)
            caps = (proc.capabilities.get("sessionCapabilities") or {}) if proc and proc.alive else {}
            for method in ("close", "delete"):
                if method in caps:
                    try:
                        await proc.request(f"session/{method}", {"sessionId": old}, timeout=30)
                        break
                    except AcpError:
                        continue
        rt.agent_session = None
        rt.tools.clear()
        await self.pool.execute(
            "update sessions set agent_session = null, carry = $2, usage = null where id = $1", rt.id, carry
        )
        rt.usage = None

    # switching agents in one chat

    def _engine_name(self, engine_id: str) -> str:
        engine = self.settings.engines.get(engine_id)
        return engine.name if engine else engine_id

    async def switch_engine(
        self, session_id: uuid.UUID, engine: str, config: dict[str, str] | None = None, mode: str | None = None
    ) -> dict[str, Any]:
        """Move a chat to another agent, after the running turn if there is one.

        The current agent writes a handover first (when it has context). The chat stays one chat: each agent
        keeps its own session, and an agent that comes back resumes its own session and gets only the
        handovers written since it left. `config`/`mode` are picks for the new agent.
        """
        if engine not in self.settings.engines:
            raise NotFound(f"unknown engine {engine}")
        row = await self.pool.fetchrow("select engine, agent_session from sessions where id = $1", session_id)
        if row is None:
            raise NotFound("session not found")
        meta = {"from": row["engine"], "to": engine, "config": config or {}, "mode": mode}
        waiting = await self.pool.fetchrow(
            "select * from messages where session_id = $1 and purpose = 'switch' and state in ('queued', 'steering')",
            session_id,
        )
        if waiting:  # a newer pick replaces the target of a switch still waiting
            await self.pool.execute(
                "update messages set meta = meta || $2, text = $3 where id = $1",
                waiting["id"], {"to": engine, "config": config or {}, "mode": mode},
                SWITCH_PROMPT.format(to=self._engine_name(engine)),
            )
            return db.message_json(await self.pool.fetchrow("select * from messages where id = $1", waiting["id"]))
        if engine == row["engine"]:
            if config or mode:
                await self.update_session(session_id, {k: v for k, v in (("config", config), ("mode", mode)) if v})
            return {"switched": False}
        return await self.send(
            session_id, SWITCH_PROMPT.format(to=self._engine_name(engine)), delivery="queue", purpose="switch",
            meta=meta, preview=f"Switching to {self._engine_name(engine)}",
        )

    async def _switched(self, rt: Runtime, meta: dict[str, Any], summary: str | None) -> None:
        """Park the current agent's session and settings, bring in the target agent's, and brief it."""
        to = meta.get("to")
        if not to or to not in self.settings.engines:
            return
        cur = await self.pool.fetchrow(
            """select engine, agent_session, mode, config_wanted, config_options, modes, agent, commands, carry
               from sessions where id = $1""",
            rt.id,
        )
        if cur["engine"] == to:
            return
        # The switch event itself is the last thing the parked agent saw (its own handover is not news to it).
        seen = await self.emit(rt.id, "switch", {
            "from": cur["engine"], "to": to, "from_session": cur["agent_session"], "summary": summary,
            "from_name": self._engine_name(cur["engine"]), "to_name": self._engine_name(to),
        })
        await self.pool.execute(
            """insert into session_agents (session_id, engine, agent_session, mode, config_wanted, config_options,
                   modes, agent, commands, last_seq) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
               on conflict (session_id, engine) do update set agent_session = excluded.agent_session,
                   mode = excluded.mode, config_wanted = excluded.config_wanted,
                   config_options = excluded.config_options, modes = excluded.modes, agent = excluded.agent,
                   commands = excluded.commands, last_seq = excluded.last_seq, updated_at = now()""",
            rt.id, cur["engine"], cur["agent_session"], cur["mode"], cur["config_wanted"] or {}, cur["config_options"],
            cur["modes"], cur["agent"], cur["commands"], seen,
        )
        target = await self.pool.fetchrow(
            "select * from session_agents where session_id = $1 and engine = $2", rt.id, to
        )
        since = target["last_seq"] if target else 0
        handovers = await self.pool.fetch(
            "select payload from events where session_id = $1 and kind = 'switch' and seq > $2 order by seq",
            rt.id, since,
        )
        lines = [f"This Crew chat (chat id {rt.id}) moves between agents; you are {self._engine_name(to)}."]
        if target and target["agent_session"]:
            lines.append("You handled this chat before. Since you left, other agents continued it; their handovers, "
                         "oldest first:")
        else:
            lines.append("You are joining this chat. Handovers from the agents before you, oldest first:")
        for h in handovers:
            p = h["payload"]
            if p.get("to") == to and p.get("from") == to:
                continue
            ref = f" (its own session id {p['from_session']})" if p.get("from_session") else ""
            lines += ["", f"From {p.get('from_name') or p.get('from')}{ref}:",
                      (p.get("summary") or "(it wrote no handover)").strip()]
        lines += ["", f"For exact earlier messages, run `crew chat history {str(rt.id)[:8]} --last 30` (Crew keeps "
                      "the whole chat; `--help` for more)."]
        carry = "\n".join(lines)
        if cur["carry"]:
            carry = cur["carry"] + "\n\n" + carry
        # A routine's chat and its routine use the same agent.
        await self.pool.execute("update routines set engine = $2 where session_id = $1 and engine <> $2", rt.id, to)
        info = self.engine_info.get(to) or {}
        config_wanted = dict((target["config_wanted"] if target else None) or {})
        config_wanted.update(meta.get("config") or {})
        await self.pool.execute(
            """update sessions set engine = $2, agent_session = $3, mode = $4, config_wanted = $5,
                   config_options = $6, modes = $7, agent = $8, commands = $9, carry = $10, usage = null,
                   model_used = null, updated_at = now() where id = $1""",
            rt.id, to,
            target["agent_session"] if target else None,
            meta.get("mode") or (target["mode"] if target else None),
            config_wanted,
            (target["config_options"] if target else None) or info.get("config_options"),
            (target["modes"] if target else None) or info.get("modes"),
            (target["agent"] if target else None) or info.get("agent"),
            (target["commands"] if target else None) or info.get("commands"),
            carry,
        )
        if cur["agent_session"]:
            self.by_agent_session.pop((cur["engine"], cur["agent_session"]), None)
        rt.engine = to
        rt.agent_session = target["agent_session"] if target else None
        rt.generation = 0  # a returning agent's session is resumed on its next turn
        rt.tools.clear()
        rt.usage = None
        if rt.agent_session:
            self.by_agent_session[(to, rt.agent_session)] = rt.id
        self.last_sent.pop(rt.id, None)
        await self.emit_session(rt.id)

    async def transcript(self, session_id: uuid.UUID, after: int = 0, last: int | None = None) -> list[dict[str, Any]]:
        """The chat as messages (owner, agents, switches), without tool calls or thinking."""
        await self.get_session(session_id)
        rows = await self.pool.fetch(
            """select seq, kind, payload, created_at from events where session_id = $1 and seq > $2
               and kind in ('user', 'text', 'switch', 'compact') order by seq""",
            session_id, after,
        )
        engine = await self.pool.fetchval(
            """select coalesce((select payload->>'from' from events where session_id = $1 and kind = 'switch'
                                order by seq limit 1), (select engine from sessions where id = $1))""",
            session_id,
        )
        out: list[dict[str, Any]] = []
        for r in rows:
            p = r["payload"]
            if r["kind"] == "user":
                who = {"routine": "Crew (routine run)", "compact": "Crew", "switch": "Crew"}.get(p.get("purpose"), "owner")
                if p.get("purpose") in ("compact", "switch"):
                    continue
                out.append({"seq": r["seq"], "from": who, "text": p.get("text", "")})
            elif r["kind"] == "text":
                if p.get("role") != "assistant":
                    continue
                if out and out[-1].get("message_id") == p.get("message_id") and out[-1]["from"] != "owner":
                    out[-1]["text"] += p.get("text", "")
                else:
                    out.append({"seq": r["seq"], "from": self._engine_name(engine), "text": p.get("text", ""),
                                "message_id": p.get("message_id")})
            elif r["kind"] == "switch":
                engine = p.get("to") or engine
                out.append({"seq": r["seq"], "from": "Crew",
                            "text": f"Switched from {p.get('from_name')} to {p.get('to_name')}."})
            elif r["kind"] == "compact" and p.get("ok"):
                out.append({"seq": r["seq"], "from": "Crew", "text": "Context compacted."})
        for item in out:
            item.pop("message_id", None)
        return out[-last:] if last else out

    # routines

    async def _routine_row(self, routine_id: uuid.UUID) -> asyncpg.Record:
        row = await self.pool.fetchrow("select * from routines where id = $1", routine_id)
        if row is None:
            raise NotFound("routine not found")
        return row

    async def _triggers(self, routine_id: uuid.UUID) -> list[asyncpg.Record]:
        return await self.pool.fetch(
            """select * from routine_triggers where routine_id = $1
               order by kind, position, created_at""",
            routine_id,
        )

    def _norm_triggers(self, triggers: list[dict[str, Any]], timezone: str) -> list[dict[str, Any]]:
        """Checked cron triggers: unique lower-case names, valid schedules, 1 to 10 attempts."""
        found: list[dict[str, Any]] = []
        for t in triggers:
            name = str(t.get("name") or "main").strip().lower()
            if not TRIGGER_NAME.match(name):
                raise rtn.Invalid(f"trigger name {name!r}: use 1 to 40 letters, digits, spaces, - or _")
            if any(f["name"] == name for f in found):
                raise rtn.Invalid(f"two triggers are called {name!r}")
            attempts = int(t.get("max_attempts") or 1)
            if not 1 <= attempts <= 10:
                raise rtn.Invalid(f"trigger {name!r}: attempts must be 1 to 10")
            found.append({
                "name": name,
                "schedule": rtn.check(str(t.get("schedule") or ""), timezone),
                "max_attempts": attempts,
                "enabled": t.get("enabled") is not False,
            })
        if not found:
            raise rtn.Invalid("a routine needs at least one trigger")
        if len(found) > 10:
            raise rtn.Invalid("at most 10 triggers per routine")
        return found

    async def _sync_routine(self, routine_id: uuid.UUID) -> None:
        """Keep routines.next_run_at (the soonest trigger) and routines.schedule (the first one) current."""
        await self.pool.execute(
            """update routines r set
                   next_run_at = case when r.enabled then (select min(t.next_run_at) from routine_triggers t
                                  where t.routine_id = r.id and t.enabled) end,
                   schedule = coalesce((select t.schedule from routine_triggers t where t.routine_id = r.id
                                        and t.kind = 'cron' order by t.position, t.created_at limit 1), r.schedule)
               where r.id = $1""",
            routine_id,
        )

    @staticmethod
    def _trigger_json(t: asyncpg.Record, parent_name: str | None = None) -> dict[str, Any]:
        return {
            "id": str(t["id"]),
            "name": t["name"],
            "kind": t["kind"],
            "schedule": t["schedule"],
            "schedule_text": rtn.describe(t["schedule"]) if t["schedule"] else None,
            "enabled": t["enabled"],
            "max_attempts": t["max_attempts"],
            "next_run_at": t["next_run_at"].isoformat() if t["next_run_at"] else None,
            "attempt": t["attempt"],
            "reason": t["reason"],
            "parent": parent_name,
        }

    async def _routine_full(self, row: asyncpg.Record, runs: int = 0) -> dict[str, Any]:
        routine = db.routine_json(row)
        triggers = await self._triggers(row["id"])
        names = {t["id"]: t["name"] for t in triggers}
        routine["triggers"] = [self._trigger_json(t, names.get(t["parent_id"])) for t in triggers]
        cron = [t for t in triggers if t["kind"] == "cron"]
        if len(cron) == 1 and cron[0]["name"] == "main":
            routine["schedule_text"] = rtn.describe(cron[0]["schedule"])
        else:
            routine["schedule_text"] = " · ".join(f"{t['name']}: {rtn.describe(t['schedule'])}" for t in cron)
        upcoming: list[dict[str, Any]] = []
        if row["enabled"]:
            now = datetime.now(UTC)
            for t in triggers:
                if not t["enabled"]:
                    continue
                if t["kind"] == "once":
                    if t["next_run_at"]:
                        upcoming.append({"at": t["next_run_at"], "trigger": t["name"], "attempt": t["attempt"]})
                else:
                    for when in rtn.next_runs(t["schedule"], row["timezone"], now, 3):
                        upcoming.append({"at": when, "trigger": t["name"], "attempt": None})
            upcoming.sort(key=lambda u: u["at"])
        routine["upcoming"] = [{**u, "at": u["at"].isoformat()} for u in upcoming[:5]]
        routine["next_runs"] = [u["at"] for u in routine["upcoming"][:3]]
        last = await self.pool.fetchrow(
            """select r.*, m.event_seq from routine_runs r left join messages m on m.id = r.message_id
               where r.routine_id = $1 order by r.created_at desc limit 1""",
            row["id"],
        )
        routine["last_run"] = db.run_json(last) if last else None
        status = None
        if row["session_id"]:
            status = await self.pool.fetchval("select status from sessions where id = $1", row["session_id"])
        routine["session_status"] = status
        if runs:
            routine["runs"] = await self.routine_runs(row["id"], runs)
        return routine

    async def list_routines(self) -> list[dict[str, Any]]:
        rows = await self.pool.fetch("select * from routines order by enabled desc, next_run_at nulls last, title")
        return [await self._routine_full(r) for r in rows]

    async def get_routine(self, routine_id: uuid.UUID) -> dict[str, Any]:
        return await self._routine_full(await self._routine_row(routine_id), runs=50)

    async def routine_runs(self, routine_id: uuid.UUID, limit: int = 50) -> list[dict[str, Any]]:
        rows = await self.pool.fetch(
            """select r.*, m.event_seq from routine_runs r left join messages m on m.id = r.message_id
               where r.routine_id = $1 order by r.created_at desc limit $2""",
            routine_id,
            limit,
        )
        return [db.run_json(r) for r in rows]

    async def routine_versions(self, routine_id: uuid.UUID) -> list[dict[str, Any]]:
        await self._routine_row(routine_id)
        rows = await self.pool.fetch(
            "select * from routine_versions where routine_id = $1 order by version desc", routine_id
        )
        return [db.version_json(r) for r in rows]

    def preview_schedule(self, schedule: str, timezone: str | None = None) -> dict[str, Any]:
        timezone = timezone or self.default_timezone
        expr = rtn.check(schedule, timezone)
        return {
            "schedule": expr,
            "timezone": timezone,
            "schedule_text": rtn.describe(expr),
            "next_runs": [t.isoformat() for t in rtn.next_runs(expr, timezone, datetime.now(UTC), 5)],
        }

    async def create_routine(
        self,
        title: str,
        instructions: str,
        schedule: str | None = None,
        timezone: str | None = None,
        engine: str | None = None,
        mode: str | None = None,
        config: dict[str, str] | None = None,
        auto_approve: bool = False,
        enabled: bool = True,
        created_by: str = "owner",
        note: str | None = None,
        triggers: list[dict[str, Any]] | None = None,
        notes: str = "",
    ) -> dict[str, Any]:
        timezone = timezone or self.default_timezone
        rtn.zone(timezone)
        if not triggers:
            if not schedule:
                raise rtn.Invalid("give a schedule or triggers")
            triggers = [{"name": "main", "schedule": schedule}]
        found = self._norm_triggers(triggers, timezone)
        engine = engine or self.settings.default_engine
        if engine not in self.settings.engines:
            raise NotFound(f"unknown engine {engine}")
        title, instructions = title.strip(), instructions.strip()
        if not title or not instructions:
            raise rtn.Invalid("a routine needs a title and instructions")
        routine_id = uuid.uuid4()
        # The routine's own chat exists from the start, so it can be opened (and pinned) before the first run.
        session_id = await self._insert_session(title, engine, mode, auto_approve, config, title_locked=True)
        now = datetime.now(UTC)
        async with self.pool.acquire() as conn, conn.transaction():
            await conn.execute(
                """insert into routines (id, title, instructions, schedule, timezone, session_id, engine, mode,
                       config, auto_approve, enabled, created_by, notes)
                   values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)""",
                routine_id, title, instructions, found[0]["schedule"], timezone, session_id, engine, mode,
                config or {}, auto_approve, enabled, created_by, notes or "",
            )
            for position, t in enumerate(found):
                nxt = rtn.next_runs(t["schedule"], timezone, now)[0] if t["enabled"] else None
                await conn.execute(
                    """insert into routine_triggers (id, routine_id, name, kind, schedule, enabled, max_attempts,
                           next_run_at, position) values ($1, $2, $3, 'cron', $4, $5, $6, $7, $8)""",
                    uuid.uuid4(), routine_id, t["name"], t["schedule"], t["enabled"], t["max_attempts"], nxt, position,
                )
            await conn.execute(
                """insert into routine_versions (routine_id, version, title, instructions, schedule, timezone,
                       changed_by, note, triggers) values ($1, 1, $2, $3, $4, $5, $6, $7, $8)""",
                routine_id, title, instructions, found[0]["schedule"], timezone, created_by, note, found,
            )
        await self._sync_routine(routine_id)
        row = await self._routine_row(routine_id)
        text = (await self._routine_full(row))["schedule_text"]
        await self.pool.execute(
            "update sessions set last_text = $2 where id = $1",
            session_id, _first_line(f"Routine: {text}" + ("" if enabled else " (paused)"), PREVIEW_MAX),
        )
        return await self._routine_changed(routine_id)

    async def update_routine(
        self, routine_id: uuid.UUID, changes: dict[str, Any], changed_by: str = "owner", note: str | None = None
    ) -> dict[str, Any]:
        row = await self._routine_row(routine_id)
        existing = [t for t in await self._triggers(routine_id) if t["kind"] == "cron"]
        old_triggers = [{"name": t["name"], "schedule": t["schedule"], "max_attempts": t["max_attempts"],
                         "enabled": t["enabled"]} for t in existing]
        new = {k: row[k] for k in ("title", "instructions", "timezone", "engine", "mode", "auto_approve", "enabled",
                                   "notes")}
        new["config"] = row["config"] or {}
        for key, value in changes.items():
            if key in new and value is not None:
                new[key] = value.strip() if isinstance(value, str) and key in ("title", "instructions") else value
        if not new["title"] or not new["instructions"]:
            raise rtn.Invalid("a routine needs a title and instructions")
        if new["engine"] != row["engine"] and "config" not in changes:
            new["config"], new["mode"] = {}, None  # settings are per agent: the new agent starts from its own
        rtn.zone(new["timezone"])
        if changes.get("triggers") is not None:
            wanted = changes["triggers"]
        elif changes.get("schedule"):
            if len(existing) != 1:
                raise rtn.Invalid("this routine has several triggers: change them with triggers, not schedule")
            wanted = [{**old_triggers[0], "schedule": changes["schedule"]}]
        else:
            wanted = old_triggers
        found = self._norm_triggers(wanted, new["timezone"])
        if new["engine"] not in self.settings.engines:
            raise NotFound(f"unknown engine {new['engine']}")
        versioned = (any(new[k] != row[k] for k in ("title", "instructions", "timezone")) or found != old_triggers)
        version = row["version"] + 1 if versioned else row["version"]
        session_id = row["session_id"]
        switching = new["engine"] != row["engine"]
        now = datetime.now(UTC)
        by_name = {t["name"]: t for t in existing}
        resumed = new["enabled"] and not row["enabled"]
        async with self.pool.acquire() as conn, conn.transaction():
            await conn.execute(
                """update routines set title = $2, instructions = $3, timezone = $4, engine = $5, mode = $6,
                       config = $7, auto_approve = $8, enabled = $9, version = $10, session_id = $11, notes = $12,
                       updated_at = now() where id = $1""",
                routine_id, new["title"], new["instructions"], new["timezone"], new["engine"], new["mode"],
                new["config"], new["auto_approve"], new["enabled"], version, session_id, new["notes"],
            )
            for position, t in enumerate(found):
                old = by_name.get(t["name"])
                retime = (old is None or old["schedule"] != t["schedule"] or old["enabled"] != t["enabled"]
                          or new["timezone"] != row["timezone"] or resumed or old["next_run_at"] is None)
                nxt = (rtn.next_runs(t["schedule"], new["timezone"], now)[0] if t["enabled"] else None) if retime \
                    else old["next_run_at"]
                if old is None:
                    await conn.execute(
                        """insert into routine_triggers (id, routine_id, name, kind, schedule, enabled, max_attempts,
                               next_run_at, position) values ($1, $2, $3, 'cron', $4, $5, $6, $7, $8)""",
                        uuid.uuid4(), routine_id, t["name"], t["schedule"], t["enabled"], t["max_attempts"], nxt,
                        position,
                    )
                else:
                    await conn.execute(
                        """update routine_triggers set schedule = $2, enabled = $3, max_attempts = $4,
                               next_run_at = $5, position = $6 where id = $1""",
                        old["id"], t["schedule"], t["enabled"], t["max_attempts"], nxt, position,
                    )
            gone = [t["id"] for t in existing if t["name"] not in {f["name"] for f in found}]
            if gone:
                await conn.execute("delete from routine_triggers where id = any($1::uuid[])", gone)
            if not new["enabled"]:
                # Paused: pending follow-ups are dropped, so nothing fires late after a resume.
                await conn.execute("delete from routine_triggers where routine_id = $1 and kind = 'once'", routine_id)
            if versioned:
                await conn.execute(
                    """insert into routine_versions (routine_id, version, title, instructions, schedule, timezone,
                           changed_by, note, triggers) values ($1, $2, $3, $4, $5, $6, $7, $8, $9)""",
                    routine_id, version, new["title"], new["instructions"], found[0]["schedule"], new["timezone"],
                    changed_by, note, found,
                )
        await self._sync_routine(routine_id)
        if session_id and await self.pool.fetchval("select 1 from sessions where id = $1", session_id):
            chat: dict[str, Any] = {}
            if new["title"] != row["title"]:
                chat["title"] = new["title"]
            if new["auto_approve"] != row["auto_approve"]:
                chat["auto_approve"] = new["auto_approve"]
            if switching:
                # One chat for the routine: its agent changes with a handover, after any running turn, and the
                # routine's settings go to the new agent.
                await self.switch_engine(session_id, new["engine"], config=new["config"], mode=new["mode"])
            else:
                if new["mode"] != row["mode"] and new["mode"]:
                    chat["mode"] = new["mode"]
                if new["config"] != (row["config"] or {}):
                    chat["config"] = new["config"]
            if chat:
                await self.update_session(session_id, chat)
        return await self._routine_changed(routine_id)

    async def set_routine_notes(self, routine_id: uuid.UUID, notes: str, append: bool = False) -> dict[str, Any]:
        """The routine's notes: what its agent learned and keeps between runs. Not versioned."""
        row = await self._routine_row(routine_id)
        text = ((row["notes"].rstrip() + "\n") if append and row["notes"].strip() else "") + notes.strip()
        if len(text) > NOTES_MAX:
            raise rtn.Invalid(f"notes are limited to {NOTES_MAX} characters; tidy them up instead of appending")
        await self.pool.execute("update routines set notes = $2, updated_at = now() where id = $1", routine_id, text)
        return await self._routine_changed(routine_id)

    async def delete_routine(self, routine_id: uuid.UUID) -> None:
        """Delete a routine and its run history. Its chat stays, as an ordinary task."""
        row = await self._routine_row(routine_id)
        await self.pool.execute("delete from routines where id = $1", routine_id)
        if row["session_id"]:
            await self.emit(row["session_id"], "routine", {"id": str(routine_id), "deleted": True})
            self.last_sent.pop(row["session_id"], None)
            await self.emit_session(row["session_id"])

    async def run_routine(self, routine_id: uuid.UUID, trigger: str | None = None) -> dict[str, Any]:
        """Run now, on top of the schedule, as one trigger (the first one by default)."""
        row = await self._routine_row(routine_id)
        cron = [t for t in await self._triggers(routine_id) if t["kind"] == "cron"]
        pick = next((t for t in cron if t["name"] == (trigger or "").strip().lower()), None) if trigger else cron[0]
        if pick is None:
            raise NotFound(f"no trigger called {trigger!r}; this routine has: {', '.join(t['name'] for t in cron)}")
        return await self._start_run(row, "manual", [{"trigger": pick, "attempt": 1, "max": pick["max_attempts"],
                                                       "reason": None}], None)

    async def follow_up(
        self, routine_id: uuid.UUID, minutes: float | None = None, at: datetime | None = None, reason: str = ""
    ) -> dict[str, Any]:
        """A run asks to be tried again later, as the next attempt of the trigger it runs for.

        Only during a run of the routine. Crew counts the attempts and refuses past the trigger's limit, so an
        agent cannot keep retrying. A new follow-up replaces a pending one for the same trigger.
        """
        await self._routine_row(routine_id)
        run = await self.pool.fetchrow(
            "select * from routine_runs where routine_id = $1 and status = 'running' order by created_at desc limit 1",
            routine_id,
        )
        if run is None:
            raise Conflict("a follow-up can only be set during a run of this routine")
        names = run["trigger_names"] or []
        parent = await self.pool.fetchrow(
            "select * from routine_triggers where routine_id = $1 and kind = 'cron' and name = $2",
            routine_id, names[0] if names else "",
        )
        if parent is None:
            raise Conflict("this run's trigger no longer exists")
        attempt, limit = run["attempt"] + 1, run["max_attempts"]
        if attempt > limit:
            raise Conflict(f"no attempts left ({run['attempt']} of {limit}): stop and tell the owner it could not be done")
        now = datetime.now(UTC)
        if at is None:
            if minutes is None:
                raise rtn.Invalid("give minutes or a time")
            at = now + timedelta(minutes=minutes)
        if not now + timedelta(seconds=30) <= at <= now + timedelta(hours=12):
            raise rtn.Invalid("a follow-up must be between 1 minute and 12 hours from now")
        async with self.pool.acquire() as conn, conn.transaction():
            await conn.execute("delete from routine_triggers where parent_id = $1 and kind = 'once'", parent["id"])
            trigger_id = uuid.uuid4()
            await conn.execute(
                """insert into routine_triggers (id, routine_id, name, kind, enabled, max_attempts, next_run_at,
                       parent_id, attempt, reason) values ($1, $2, $3, 'once', true, $4, $5, $6, $7, $8)""",
                trigger_id, routine_id, parent["name"], limit, at, parent["id"], attempt, reason.strip()[:300] or None,
            )
        await self._sync_routine(routine_id)
        await self._routine_changed(routine_id)
        t = await self.pool.fetchrow("select * from routine_triggers where id = $1", trigger_id)
        return self._trigger_json(t, parent["name"])

    async def cancel_follow_up(self, routine_id: uuid.UUID, trigger_id: uuid.UUID) -> None:
        done = await self.pool.fetchval(
            "delete from routine_triggers where id = $1 and routine_id = $2 and kind = 'once' returning id",
            trigger_id, routine_id,
        )
        if not done:
            raise NotFound("no such follow-up")
        await self._sync_routine(routine_id)
        await self._routine_changed(routine_id)

    async def _routine_changed(self, routine_id: uuid.UUID) -> dict[str, Any]:
        """Tell the apps: a `routine` event on the routine's chat, and the chat's own state."""
        routine = await self._routine_full(await self._routine_row(routine_id))
        if routine["session_id"]:
            sid = uuid.UUID(routine["session_id"])
            await self.emit(sid, "routine", routine)
            await self.emit_session(sid)
        return routine

    async def _routine_chat(self, row: asyncpg.Record) -> uuid.UUID:
        """The routine's chat, made again if it was deleted (or the routine moved to another agent)."""
        if row["session_id"]:
            exists = await self.pool.fetchval("select 1 from sessions where id = $1", row["session_id"])
            if exists:
                return row["session_id"]
        session_id = await self._insert_session(
            row["title"], row["engine"], row["mode"], row["auto_approve"], row["config"], title_locked=True
        )
        await self.pool.execute("update routines set session_id = $2 where id = $1", row["id"], session_id)
        return session_id

    async def _start_run(
        self,
        row: asyncpg.Record,
        trigger: str,
        fired: list[dict[str, Any]],
        scheduled_for: datetime | None,
        skip_reason: str | None = None,
    ) -> dict[str, Any]:
        """One run for the triggers that fired together: [{trigger, attempt, max, reason}], the first leads."""
        run_id = uuid.uuid4()
        lead = fired[0]
        names = list(dict.fromkeys(f["trigger"]["name"] for f in fired))
        busy = await self.pool.fetchval(
            "select 1 from routine_runs where routine_id = $1 and status in ('queued', 'running') limit 1", row["id"]
        )
        if busy and trigger == "manual":
            raise Conflict("This routine's last run has not finished yet")
        session_id = await self._routine_chat(row)
        if busy or skip_reason:
            await self.pool.execute(
                """insert into routine_runs (id, routine_id, session_id, trigger, version, scheduled_for, status,
                       summary, finished_at, trigger_names, attempt, max_attempts)
                   values ($1, $2, $3, $4, $5, $6, 'skipped', $7, now(), $8, $9, $10)""",
                run_id, row["id"], session_id, trigger, row["version"], scheduled_for,
                skip_reason or "Skipped: the previous run had not finished.", names, lead["attempt"], lead["max"],
            )
            await self._routine_changed(row["id"])
            return await self._run_json(run_id)
        message_id = uuid.uuid4()
        all_cron = [t for t in await self._triggers(row["id"]) if t["kind"] == "cron"]
        routine = db.routine_json(row)
        text = rtn.run_prompt(
            routine, str(run_id), trigger, datetime.now(UTC),
            triggers=[(f["trigger"]["name"], rtn.describe(f["trigger"]["schedule"] or "") if f["trigger"]["schedule"]
                       else None) for f in fired if f["trigger"]["name"] in names],
            attempt=lead["attempt"], max_attempts=lead["max"], reason=lead["reason"], notes=row["notes"],
            several=len(all_cron) > 1,
        )
        await self.pool.execute(
            """insert into routine_runs (id, routine_id, session_id, message_id, trigger, version, scheduled_for,
                   trigger_names, attempt, max_attempts) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)""",
            run_id, row["id"], session_id, message_id, trigger, row["version"], scheduled_for, names,
            lead["attempt"], lead["max"],
        )
        await self.pool.execute("update routines set last_run_at = now() where id = $1", row["id"])
        label = ", ".join(names) if names != ["main"] else ""
        await self.send(
            session_id, text, delivery="queue", purpose="routine", message_id=message_id,
            meta={"routine_id": str(row["id"]), "run_id": str(run_id), "title": row["title"], "trigger": trigger,
                  "version": row["version"], "triggers": names, "attempt": lead["attempt"], "max_attempts": lead["max"]},
            preview=("Scheduled run" if trigger == "schedule" else "Run now") + (f" ({label})" if label else "")
                    + f": {row['title']}",
        )
        await self._routine_changed(row["id"])
        return await self._run_json(run_id)

    async def _run_json(self, run_id: uuid.UUID) -> dict[str, Any]:
        row = await self.pool.fetchrow(
            """select r.*, m.event_seq from routine_runs r left join messages m on m.id = r.message_id
               where r.id = $1""",
            run_id,
        )
        return db.run_json(row)

    async def _run_started(self, message_id: uuid.UUID) -> None:
        routine_id = await self.pool.fetchval(
            """update routine_runs set status = 'running', started_at = now()
               where message_id = $1 and status = 'queued' returning routine_id""",
            message_id,
        )
        if routine_id:
            await self._routine_changed(routine_id)

    async def _run_finished(self, message_id: uuid.UUID, status: str, summary: str | None) -> None:
        routine_id = await self.pool.fetchval(
            """update routine_runs set status = $2, summary = coalesce($3, summary), finished_at = now()
               where message_id = $1 and status in ('queued', 'running') returning routine_id""",
            message_id,
            status,
            summary,
        )
        if routine_id:
            await self._routine_changed(routine_id)

    async def _schedule_loop(self) -> None:
        while not self.closing:
            try:
                await self.run_due()
            except Exception:
                log.exception("scheduler pass failed")
            await asyncio.sleep(SCHEDULER_TICK)

    async def run_due(self, now: datetime | None = None) -> int:
        """Start every routine with a due trigger; triggers due together make one run.

        A cron trigger is claimed by moving its next_run_at first, so it fires once. A follow-up (once trigger)
        is deleted when it fires; while the routine's previous run is still going it waits a minute instead.
        """
        now = now or datetime.now(UTC)
        started = 0
        rows = await self.pool.fetch(
            """select t.* from routine_triggers t join routines r on r.id = t.routine_id
               where r.enabled and t.enabled and t.next_run_at <= $1 order by t.next_run_at""",
            now,
        )
        groups: dict[uuid.UUID, list[asyncpg.Record]] = {}
        for t in rows:
            groups.setdefault(t["routine_id"], []).append(t)
        for routine_id, due_triggers in groups.items():
            row = await self._routine_row(routine_id)
            busy = await self.pool.fetchval(
                "select 1 from routine_runs where routine_id = $1 and status in ('queued', 'running') limit 1",
                routine_id,
            )
            fired: list[dict[str, Any]] = []
            when: datetime | None = None
            for t in due_triggers:
                if t["kind"] == "once":
                    if busy:
                        await self.pool.execute(
                            "update routine_triggers set next_run_at = $2 where id = $1",
                            t["id"], now + timedelta(minutes=1),
                        )
                        continue
                    parent = await self.pool.fetchrow("select * from routine_triggers where id = $1", t["parent_id"])
                    claimed = await self.pool.fetchval(
                        "delete from routine_triggers where id = $1 returning id", t["id"]
                    )
                    if claimed and parent:
                        fired.append({"trigger": parent, "attempt": t["attempt"] or 2, "max": t["max_attempts"],
                                      "reason": t["reason"]})
                        when = when or t["next_run_at"]
                    continue
                due = rtn.latest_due(t["schedule"], row["timezone"], t["next_run_at"], now)
                following = rtn.next_runs(t["schedule"], row["timezone"], now)[0]
                claimed = await self.pool.fetchval(
                    "update routine_triggers set next_run_at = $3 where id = $1 and next_run_at = $2 returning id",
                    t["id"], t["next_run_at"], following,
                )
                if not claimed:
                    continue
                if now - due > rtn.CATCH_UP:
                    local = due.astimezone(rtn.zone(row["timezone"]))
                    await self._start_run(
                        row, "schedule", [{"trigger": t, "attempt": 1, "max": t["max_attempts"], "reason": None}], due,
                        skip_reason=f"Skipped: Crew was not running at {local:%a %d %b %H:%M}.",
                    )
                    continue
                # A new firing of this trigger replaces a follow-up still pending from the last one.
                await self.pool.execute(
                    "delete from routine_triggers where parent_id = $1 and kind = 'once'", t["id"]
                )
                fired.append({"trigger": t, "attempt": 1, "max": t["max_attempts"], "reason": None})
                when = when or due
            await self._sync_routine(routine_id)
            if not fired:
                await self._routine_changed(routine_id)
                continue
            # Follow-ups lead (they carry the attempt count), then cron triggers in their order.
            fired.sort(key=lambda f: (f["attempt"] == 1, f["trigger"]["position"]))
            try:
                run = await self._start_run(row, "schedule", fired, when)
                started += 1 if run["status"] != "skipped" else 0
            except Exception:
                log.exception("could not start routine %s", routine_id)
        return started
