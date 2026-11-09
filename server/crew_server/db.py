"""PostgreSQL access (asyncpg) and schema migration."""

from __future__ import annotations

import json
from importlib import resources
from typing import Any

import asyncpg

SCHEMA_VERSION = 5
MIGRATIONS = {1: "schema.sql", 2: "schema_v2.sql", 3: "schema_v3.sql", 4: "schema_v4.sql", 5: "schema_v5.sql"}


async def _init_connection(conn: asyncpg.Connection) -> None:
    await conn.set_type_codec("jsonb", encoder=json.dumps, decoder=json.loads, schema="pg_catalog")


async def connect(url: str) -> asyncpg.Pool:
    return await asyncpg.create_pool(url, min_size=1, max_size=8, init=_init_connection)


async def migrate(pool: asyncpg.Pool) -> None:
    async with pool.acquire() as conn, conn.transaction():
        await conn.execute("select pg_advisory_xact_lock(4711)")
        await conn.execute("create table if not exists schema_version (version int not null)")
        current = await conn.fetchval("select max(version) from schema_version") or 0
        for version, name in sorted(MIGRATIONS.items()):
            if current < version:
                await conn.execute(resources.files("crew_server").joinpath(name).read_text())
                await conn.execute("insert into schema_version values ($1)", version)


SESSION_COLUMNS = (
    "id, engine, title, status, mode, modes, auto_approve, usage, last_text, pinned, archived, created_at, updated_at,"
    " agent, config_options, config_wanted, model_used, browser_tab, commands,"
    " (select r.id from routines r where r.session_id = s.id order by r.created_at limit 1) as routine_id"
)


def session_json(row: asyncpg.Record, pending_approvals: int = 0) -> dict[str, Any]:
    # One answer for the current mode: the agent's mode config option when it has one, else the mode column.
    options = row["config_options"] or []
    option = next((o for o in options if o.get("category") == "mode" and o.get("current")), None)
    mode = option["current"] if option else row["mode"]
    modes = row["modes"]
    if modes and mode:
        modes = {**modes, "currentModeId": mode}
    return {
        "id": str(row["id"]),
        "engine": row["engine"],
        "title": row["title"],
        "status": row["status"],
        "mode": mode,
        "modes": modes,
        "auto_approve": row["auto_approve"],
        "usage": row["usage"],
        "last_text": row["last_text"],
        "pinned": row["pinned"],
        "archived": row["archived"],
        "agent": row["agent"],
        "config_options": options,
        "config": row["config_wanted"] or {},
        "model_used": row["model_used"],
        "browser_tab": row["browser_tab"],
        "routine_id": str(row["routine_id"]) if row["routine_id"] else None,
        # How Compact works here: the agent's own /compact, or Crew's handover to a fresh agent session.
        "compact": "native" if "compact" in (row["commands"] or []) else "handover",
        "pending_approvals": pending_approvals,
        "created_at": row["created_at"].isoformat(),
        "updated_at": row["updated_at"].isoformat(),
    }


def event_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "seq": row["seq"],
        "session_id": str(row["session_id"]),
        "kind": row["kind"],
        "payload": row["payload"],
        "created_at": row["created_at"].isoformat(),
    }


def message_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "session_id": str(row["session_id"]),
        "client_id": row["client_id"],
        "seq": row["event_seq"],
        "delivery": row["delivery"],
        "state": row["state"],
        "delivered_how": row["delivered_how"],
        "purpose": row["purpose"],
        "meta": row["meta"],
        "created_at": row["created_at"].isoformat(),
        "delivered_at": row["delivered_at"].isoformat() if row["delivered_at"] else None,
    }


def approval_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "session_id": str(row["session_id"]),
        "tool": row["tool"],
        "options": row["options"],
        "status": row["status"],
        "option_id": row["option_id"],
        "decided_by": row["decided_by"],
        "created_at": row["created_at"].isoformat(),
        "decided_at": row["decided_at"].isoformat() if row["decided_at"] else None,
    }


def _iso(value: Any) -> str | None:
    return value.isoformat() if value else None


def routine_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "title": row["title"],
        "instructions": row["instructions"],
        "schedule": row["schedule"],
        "timezone": row["timezone"],
        "session_id": str(row["session_id"]) if row["session_id"] else None,
        "engine": row["engine"],
        "mode": row["mode"],
        "config": row["config"] or {},
        "auto_approve": row["auto_approve"],
        "enabled": row["enabled"],
        "version": row["version"],
        "next_run_at": _iso(row["next_run_at"]),
        "last_run_at": _iso(row["last_run_at"]),
        "created_by": row["created_by"],
        "notes": row["notes"] if "notes" in row.keys() else "",
        "created_at": _iso(row["created_at"]),
        "updated_at": _iso(row["updated_at"]),
    }


def run_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "routine_id": str(row["routine_id"]),
        "session_id": str(row["session_id"]) if row["session_id"] else None,
        "message_id": str(row["message_id"]) if row["message_id"] else None,
        "seq": row["event_seq"] if "event_seq" in row.keys() else None,  # the run's message in the chat timeline
        "trigger": row["trigger"],
        "triggers": list(row["trigger_names"] or []),
        "attempt": row["attempt"],
        "max_attempts": row["max_attempts"],
        "version": row["version"],
        "status": row["status"],
        "summary": row["summary"],
        "scheduled_for": _iso(row["scheduled_for"]),
        "started_at": _iso(row["started_at"]),
        "finished_at": _iso(row["finished_at"]),
        "created_at": _iso(row["created_at"]),
    }


def version_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "version": row["version"],
        "title": row["title"],
        "instructions": row["instructions"],
        "schedule": row["schedule"],
        "timezone": row["timezone"],
        "changed_by": row["changed_by"],
        "note": row["note"],
        "triggers": row["triggers"],
        "created_at": _iso(row["created_at"]),
    }
