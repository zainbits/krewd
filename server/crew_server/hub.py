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
import uuid
from dataclasses import dataclass, field
from functools import partial
from typing import Any

import asyncpg

from . import db
from .acp import AcpError, AcpProcess, MethodNotFound
from .attachments import Attachments, UnknownUpload, prompt_blocks
from .config import Settings

log = logging.getLogger("crew.hub")

TEXT_FLUSH_SECONDS = 0.3
CLIP = 4000
TITLE_MAX = 80
PREVIEW_MAX = 280


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
        self.probing: set[str] = set()
        self.tab_owner: dict[str, uuid.UUID] = {}  # browser tab id -> session that last used it

    # lifecycle

    async def start(self) -> None:
        await db.migrate(self.pool)
        self.attachments.prepare()
        self.last_seq = await self.pool.fetchval("select coalesce(max(seq), 0) from events")
        for row in await self.pool.fetch("select * from engines"):
            self.engine_info[row["id"]] = {
                "agent": row["agent"], "config_options": row["config_options"] or [], "modes": row["modes"],
                "steering": row["steering"],
            }
        await self._recover()
        # Messages stored before a restart still run, in order.
        for row in await self.pool.fetch("select distinct session_id from messages where state in ('queued', 'steering')"):
            await self.pool.execute("update messages set state = 'queued' where session_id = $1 and state = 'steering'",
                                    row["session_id"])
            self._kick(await self._runtime(row["session_id"]))
        for row in await self.pool.fetch("select id, browser_tab from sessions where browser_tab is not null"):
            self.tab_owner.setdefault(row["browser_tab"]["id"], row["id"])

    async def close(self) -> None:
        self.closing = True
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
            if unknown and engine.id not in self.probing and not self.closing:
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
            })
        return listing

    def can_steer(self, engine_id: str) -> bool:
        proc = self.procs.get(engine_id)
        if proc and proc.alive:
            return proc.steering
        return bool((self.engine_info.get(engine_id) or {}).get("steering"))

    async def _remember_engine(self, engine_id: str, **info: Any) -> None:
        current = self.engine_info.setdefault(engine_id, {})
        changes = {k: v for k, v in info.items() if v is not None and current.get(k) != v}
        if not changes:
            return
        current.update(changes)
        await self.pool.execute(
            """insert into engines (id, agent, config_options, modes, steering) values ($1, $2, $3, $4, $5)
               on conflict (id) do update set agent = excluded.agent, config_options = excluded.config_options,
                   modes = excluded.modes, steering = excluded.steering, updated_at = now()""",
            engine_id,
            current.get("agent"),
            current.get("config_options"),
            current.get("modes"),
            current.get("steering"),
        )

    async def _probe(self, engine_id: str) -> None:
        """Open and drop a throwaway session to learn an engine's models and options."""
        try:
            proc = await self._proc(engine_id)
            self.settings.state_dir.mkdir(parents=True, exist_ok=True)
            result = await proc.request(
                "session/new", {"cwd": str(self.settings.state_dir), "mcpServers": []}, timeout=120
            )
            await self._remember_engine(
                engine_id, config_options=normalise_config(result.get("configOptions")), modes=result.get("modes")
            )
            caps = proc.capabilities.get("sessionCapabilities") or {}
            for method in ("delete", "close"):
                if method in caps:
                    try:
                        await proc.request(f"session/{method}", {"sessionId": result["sessionId"]}, timeout=30)
                        break
                    except AcpError:
                        continue
        except Exception as exc:  # a probe is best effort; real tasks report their own errors
            log.warning("could not probe %s: %s", engine_id, exc)
        finally:
            self.probing.discard(engine_id)

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
        await self.emit(session_id, "session", await self.get_session(session_id))

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
        engine = engine or self.settings.default_engine
        if engine not in self.settings.engines:
            raise NotFound(f"unknown engine {engine}")
        session_id = uuid.uuid4()
        if attachments:
            for upload_id in attachments:
                if not (self.attachments.incoming / f"{upload_id}.json").is_file():
                    raise NotFound(f"unknown upload {upload_id}")
        await self.pool.execute(
            """insert into sessions (id, engine, title, mode, auto_approve, config_wanted, agent, config_options)
               values ($1, $2, $3, $4, $5, $6, $7, $8)""",
            session_id,
            engine,
            _first_line(text, TITLE_MAX) or self._upload_name(attachments) or "New task",
            mode,
            auto_approve,
            config or {},
            (self.engine_info.get(engine) or {}).get("agent"),
            (self.engine_info.get(engine) or {}).get("config_options"),
        )
        await self.send(session_id, text, attachments, tabs=tabs, client_id=client_id)
        return await self.get_session(session_id)

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

    async def send(
        self,
        session_id: uuid.UUID,
        text: str,
        attachments: list[str] | None = None,
        tabs: list[dict[str, Any]] | None = None,
        delivery: str | None = None,
        client_id: str | None = None,
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
        message_id = uuid.uuid4()
        try:
            await self.pool.execute(
                """insert into messages (id, session_id, client_id, text, attachments, tabs, delivery, priority)
                   values ($1, $2, $3, $4, $5, $6, $7, $8)""",
                message_id, session_id, client_id, text, files, tabs, wanted, 0 if wanted == "interrupt" else 1,
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
        seq = await self.emit(session_id, "user", payload)
        await self.pool.execute("update messages set event_seq = $2 where id = $1", message_id, seq)
        preview = text or ", ".join(f["name"] for f in files) or ", ".join(t["title"] or t["url"] for t in tabs)
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
            proc = AcpProcess(
                engine_id=engine_id,
                command=engine.command,
                env=engine.env,
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
                await self._apply_config(rt, proc, config_id, value)
        await self.emit_session(rt.id)
        return proc

    async def _apply_mode(self, rt: Runtime, proc: AcpProcess, mode: str) -> None:
        try:
            await proc.request("session/set_mode", {"sessionId": rt.agent_session, "modeId": mode}, timeout=30)
        except AcpError as exc:
            await self.emit(rt.id, "error", {"message": f"Could not switch to mode {mode}: {exc}"})

    async def _apply_config(self, rt: Runtime, proc: AcpProcess, config_id: str, value: Any) -> None:
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
            try:
                await self._run_turn(rt, message["text"], message["attachments"], message["tabs"], message["id"])
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
    ) -> None:
        rt.assistant_run, rt.assistant_text = None, ""
        rt.running, rt.cancel_requested = True, False
        await self._set_status(rt, "running")
        await self.emit(rt.id, "turn_start", {"message_id": str(message_id) if message_id else None})
        stop_reason = "error"
        usage = None
        try:
            proc = await self._ensure_loaded(rt)
            if rt.cancel_requested:
                raise _Cancelled()
            result = await proc.request(
                "session/prompt",
                {"sessionId": rt.agent_session, "prompt": prompt_blocks(_with_tabs(text, tabs), files or [])},
            )
            stop_reason = result.get("stopReason", "end_turn")
            usage = result.get("usage")
        except _Cancelled:
            stop_reason = "cancelled"
        except AcpError as exc:
            await self._flush_text(rt)
            await self.emit(rt.id, "error", {"message": str(exc)})
        finally:
            rt.running = False
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
        await self.emit(rt.id, "turn_end", {"stop_reason": stop_reason, "usage": usage, "preview": preview})
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
            rt.usage = {key: update[key] for key in ("used", "size", "cost") if key in update}
            model = _reported_model(update.get("_meta"))
            if model:
                changed = await self.pool.fetchval(
                    "update sessions set model_used = $2 where id = $1 and model_used is distinct from $2 returning id",
                    rt.id,
                    model,
                )
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
        text, paths = _content_text(update.get("content"))
        if text:
            tool["output"] = _clip(text)
        elif update.get("rawOutput") and "output" not in tool:
            tool["output"] = _clip(update["rawOutput"])
        if paths:
            tool["paths"] = paths
        if tool != before:
            await self.emit(rt.id, "tool", dict(tool))
