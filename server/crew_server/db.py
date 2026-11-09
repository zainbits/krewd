"""PostgreSQL access (asyncpg) and schema migration."""

from __future__ import annotations

import json
from importlib import resources
from typing import Any

import asyncpg

SCHEMA_VERSION = 2
MIGRATIONS = {1: "schema.sql", 2: "schema_v2.sql"}


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
    " agent, config_options, config_wanted, model_used"
)


def session_json(row: asyncpg.Record, pending_approvals: int = 0) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "engine": row["engine"],
        "title": row["title"],
        "status": row["status"],
        "mode": row["mode"],
        "modes": row["modes"],
        "auto_approve": row["auto_approve"],
        "usage": row["usage"],
        "last_text": row["last_text"],
        "pinned": row["pinned"],
        "archived": row["archived"],
        "agent": row["agent"],
        "config_options": row["config_options"] or [],
        "config": row["config_wanted"] or {},
        "model_used": row["model_used"],
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
