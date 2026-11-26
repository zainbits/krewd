"""Alerts to the owner's phone, their levels, and the "Needs you" items.

Levels (fixed rules here decide; an agent can only suggest one, so alarms stay rare):

- info: a card in the app only, no notification and no push.
- action: a card (where there is one) and a normal notification.
- urgent: a heads-up notification that repeats until the owner opens it.
- critical: a full-screen alarm that rings until "Got it". It follows the phone's Do Not Disturb (the owner's
  choice: Do Not Disturb means not now), so during it the alarm waits silently as a notification that cannot be
  swiped away. Only Krewd's own rule
  gives it: a failed run of a routine the owner marked "Alarm if a run fails" (a time-bound job such as a check-in),
  and the owner's own test alert.

Every alert is stored (`alerts`, kept 14 days) and pushed to every registered phone as a data message carrying only
`{"k": "alert", "id": ..., "l": level}`; the phone fetches the title and text from Krewd itself (push.py).
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import asyncpg

from .push import Fcm

log = logging.getLogger("krewd.alerts")

LEVELS = ("info", "action", "urgent", "critical")
AGENT_MAX = "urgent"  # the most an agent's suggestion can get
REMIND_BEFORE = timedelta(minutes=15)
KEEP_ALERTS = timedelta(days=14)
KEEP_CLOSED_NEEDS = timedelta(days=30)
TOKEN_STALE = timedelta(days=60)
TTL = {"action": 24 * 3600, "urgent": 6 * 3600, "critical": 2 * 3600}
MAX_TOKENS = 10


class Invalid(ValueError):
    pass


def rank(level: str) -> int:
    return LEVELS.index(level)


def capped(level: str, cap: str) -> str:
    return level if rank(level) <= rank(cap) else cap


def agent_level(suggested: str | None) -> str:
    """What an agent's suggested level becomes: its own pick up to Urgent (default Action), never Critical."""
    if suggested is not None and suggested not in LEVELS:
        raise Invalid(f"level must be one of {', '.join(LEVELS)}")
    return capped(suggested or "action", AGENT_MAX)


def run_level(status: str, alarm_on_fail: bool) -> str | None:
    """A routine run's end: done or waiting is Action, a failure Urgent (Critical when the owner asked for an
    alarm on that routine). A cancelled or skipped run says nothing."""
    if status in ("done", "waiting"):
        return "action"
    if status in ("failed", "error"):
        return "critical" if alarm_on_fail else "urgent"
    return None


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def need_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "key": row["key"],
        "title": row["title"],
        "source": row["source"],
        "link": row["link"],
        "due_at": _iso(row["due_at"]),
        "level": row["level"],
        "suggested_level": row["suggested_level"],
        "status": row["status"],
        "snoozed_until": _iso(row["snoozed_until"]),
        "session_id": str(row["session_id"]) if row["session_id"] else None,
        "routine_id": str(row["routine_id"]) if row["routine_id"] else None,
        "created_by": row["created_by"],
        "seen_count": row["seen_count"],
        "created_at": _iso(row["created_at"]),
        "updated_at": _iso(row["updated_at"]),
        "closed_at": _iso(row["closed_at"]),
    }


def alert_json(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": str(row["id"]),
        "kind": row["kind"],
        "level": row["level"],
        "title": row["title"],
        "body": row["body"],
        "session_id": str(row["session_id"]) if row["session_id"] else None,
        "needs_id": str(row["needs_id"]) if row["needs_id"] else None,
        "ref_id": row["ref_id"],
        "created_at": _iso(row["created_at"]),
        "acked_at": _iso(row["acked_at"]),
    }


Emit = Callable[[uuid.UUID | None, str, dict[str, Any]], Awaitable[int]]


class Alerts:
    def __init__(self, pool: asyncpg.Pool, emit: Emit, fcm: Fcm | None = None) -> None:
        self.pool = pool
        self.emit = emit
        self.fcm = fcm or Fcm()
        self.tasks: set[asyncio.Task[None]] = set()
        self.last_clean = datetime.min.replace(tzinfo=UTC)

    async def close(self) -> None:
        await self.drain()
        await self.fcm.close()

    async def drain(self) -> None:
        """Wait for pushes in flight (tests, shutdown)."""
        while self.tasks:
            await asyncio.gather(*list(self.tasks), return_exceptions=True)

    # alerts

    async def notify(
        self, kind: str, level: str, title: str, body: str = "", *, session_id: uuid.UUID | None = None,
        needs_id: uuid.UUID | None = None, ref_id: str | None = None,
    ) -> dict[str, Any]:
        if level not in LEVELS:
            raise Invalid(f"level must be one of {', '.join(LEVELS)}")
        row = await self.pool.fetchrow(
            """insert into alerts (id, kind, level, title, body, session_id, needs_id, ref_id)
               values ($1, $2, $3, $4, $5, $6, $7, $8) returning *""",
            uuid.uuid4(), kind, level, title.strip()[:300] or "Krewd", body.strip()[:2000], session_id, needs_id,
            ref_id,
        )
        alert = alert_json(row)
        await self.emit(session_id, "alert", alert)
        if level != "info":
            task = asyncio.create_task(self._push(row["id"], level))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
        return alert

    async def _push(self, alert_id: uuid.UUID, level: str) -> None:
        try:
            if not self.fcm.configured:
                return
            tokens = [r["token"] for r in await self.pool.fetch(
                "select token from push_tokens order by last_seen_at desc limit $1", MAX_TOKENS)]
            ok, errors = 0, []
            for token in tokens:
                sent = await self.fcm.send(token, {"k": "alert", "id": str(alert_id), "l": level}, TTL.get(level, 3600))
                if sent.ok:
                    ok += 1
                    await self.pool.execute("update push_tokens set last_error = null where token = $1", token)
                elif sent.gone:
                    await self.pool.execute("delete from push_tokens where token = $1", token)
                else:
                    errors.append(sent.error or "failed")
                    await self.pool.execute("update push_tokens set last_error = $2 where token = $1", token,
                                            (sent.error or "")[:300])
            await self.pool.execute("update alerts set pushed = $2, push_error = $3 where id = $1", alert_id, ok,
                                    "; ".join(errors)[:500] or None)
            if errors:
                log.warning("push of alert %s: %s", alert_id, "; ".join(errors))
        except Exception:
            log.exception("push of alert %s failed", alert_id)

    async def get(self, alert_id: uuid.UUID) -> dict[str, Any] | None:
        row = await self.pool.fetchrow("select * from alerts where id = $1", alert_id)
        if row is None:
            return None
        alert = alert_json(row)
        alert["open"] = await self._still_open(row)
        if row["needs_id"]:
            need = await self.pool.fetchrow("select * from needs_items where id = $1", row["needs_id"])
            alert["needs"] = need_json(need) if need else None
        return alert

    async def _still_open(self, row: asyncpg.Record) -> bool:
        """Whether the alert still asks something of the owner (the phone skips the notification otherwise)."""
        if row["acked_at"]:
            return False
        if row["kind"] == "approval" and row["ref_id"]:
            status = await self.pool.fetchval("select status from approvals where id = $1::uuid", row["ref_id"])
            return status == "pending"
        if row["needs_id"]:
            status = await self.pool.fetchval("select status from needs_items where id = $1", row["needs_id"])
            return status == "open"
        return True

    async def recent(self, limit: int = 50) -> list[dict[str, Any]]:
        rows = await self.pool.fetch("select * from alerts order by created_at desc limit $1", min(limit, 200))
        return [alert_json(r) for r in rows]

    async def ack(self, alert_id: uuid.UUID) -> None:
        row = await self.pool.fetchrow(
            "update alerts set acked_at = coalesce(acked_at, now()) where id = $1 returning *", alert_id)
        if row:
            await self.emit(row["session_id"], "alert", alert_json(row))

    # push tokens

    async def register(self, token: str, device_id: uuid.UUID | None, app_version: str | None) -> None:
        if not token or len(token) > 4096:
            raise Invalid("a push token is 1 to 4096 characters")
        await self.pool.execute(
            """insert into push_tokens (token, device_id, app_version) values ($1, $2, $3)
               on conflict (token) do update set device_id = coalesce($2, push_tokens.device_id),
                   app_version = $3, last_seen_at = now()""",
            token, device_id, app_version,
        )

    async def unregister(self, token: str) -> None:
        await self.pool.execute("delete from push_tokens where token = $1", token)

    async def status(self) -> dict[str, Any]:
        rows = await self.pool.fetch("select last_seen_at, last_error from push_tokens order by last_seen_at desc")
        return {
            **self.fcm.status(),
            "phones": len(rows),
            "errors": [r["last_error"] for r in rows if r["last_error"]],
        }

    # needs items

    async def add_need(
        self, *, key: str, title: str, source: str = "", link: str | None = None, due_at: datetime | None = None,
        level: str | None = None, session_id: uuid.UUID | None = None, routine_id: uuid.UUID | None = None,
        created_by: str = "agent", again: bool = False, fixed_level: str | None = None,
    ) -> dict[str, Any]:
        """Add a card, or update the one with this key. Returns the item plus what happened (`outcome`: added,
        updated, reopened or closed, when the owner already closed it and `again` is not set)."""
        key, title = key.strip(), title.strip()
        if not key or len(key) > 200:
            raise Invalid("give a stable --key of 1 to 200 characters")
        if not title:
            raise Invalid("give a title")
        if due_at is not None and due_at.tzinfo is None:
            raise Invalid("a due time needs its timezone")
        final = fixed_level or agent_level(level)
        now = datetime.now(UTC)
        async with self.pool.acquire() as conn, conn.transaction():
            old = await conn.fetchrow("select * from needs_items where key = $1 for update", key)
            if old is not None and old["status"] != "open" and not again:
                await conn.execute("update needs_items set seen_count = seen_count + 1 where id = $1", old["id"])
                return {**need_json(old), "outcome": "closed"}
            reminded = now if due_at is not None and due_at - REMIND_BEFORE <= now else None
            if old is None:
                row = await conn.fetchrow(
                    """insert into needs_items (id, key, title, source, link, due_at, level, suggested_level,
                           session_id, routine_id, created_by, reminded_at)
                       values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12) returning *""",
                    uuid.uuid4(), key, title[:300], source.strip()[:200], link, due_at, final, level, session_id,
                    routine_id, created_by, reminded,
                )
                outcome = "added"
            else:
                reopen = old["status"] != "open"
                due_changed = due_at is not None and due_at != old["due_at"]
                row = await conn.fetchrow(
                    """update needs_items set title = $2, source = coalesce(nullif($3, ''), source),
                           link = coalesce($4, link), due_at = coalesce($5, due_at), level = $6,
                           suggested_level = coalesce($7, suggested_level),
                           session_id = coalesce($8, session_id), routine_id = coalesce($9, routine_id),
                           seen_count = seen_count + 1, updated_at = now(),
                           status = 'open', closed_at = null,
                           snoozed_until = case when $10 then null else snoozed_until end,
                           reminded_at = case when $11 then $12 else reminded_at end
                       where id = $1 returning *""",
                    old["id"], title[:300], source.strip()[:200], link, due_at,
                    final if rank(final) >= rank(old["level"]) or reopen else old["level"], level, session_id,
                    routine_id, reopen, due_changed or reopen, reminded,
                )
                outcome = "reopened" if reopen else "updated"
        item = need_json(row)
        await self.emit(row["session_id"], "needs", item)
        raised = old is not None and rank(row["level"]) > rank(old["level"])
        if outcome in ("added", "reopened") or raised:
            await self.notify("needs", row["level"], row["title"], self._need_body(row), session_id=row["session_id"],
                              needs_id=row["id"])
        return {**item, "outcome": outcome}

    @staticmethod
    def _need_body(row: asyncpg.Record) -> str:
        parts = [row["source"]] if row["source"] else []
        if row["due_at"]:
            parts.append("due " + row["due_at"].astimezone().strftime("%a %H:%M"))
        return " · ".join(parts)

    async def needs(self, closed: bool = False, limit: int = 100) -> list[dict[str, Any]]:
        if closed:
            rows = await self.pool.fetch("select * from needs_items order by updated_at desc limit $1", min(limit, 500))
        else:
            rows = await self.pool.fetch(
                """select * from needs_items where status = 'open'
                   order by due_at asc nulls last, created_at desc limit $1""", min(limit, 500))
        return [need_json(r) for r in rows]

    async def need(self, need_id: uuid.UUID) -> dict[str, Any] | None:
        row = await self.pool.fetchrow("select * from needs_items where id = $1", need_id)
        return need_json(row) if row else None

    async def set_need(self, need_id: uuid.UUID | None, action: str, until: datetime | None = None,
                       key: str | None = None) -> dict[str, Any] | None:
        where, arg = ("id = $1", need_id) if need_id is not None else ("key = $1", key)
        if action in ("done", "dismissed"):
            row = await self.pool.fetchrow(
                f"""update needs_items set status = $2, closed_at = now(), updated_at = now(), snoozed_until = null
                    where {where} returning *""", arg, action)
        elif action == "snooze":
            if until is None or until.tzinfo is None:
                raise Invalid("snooze needs a time with its timezone")
            row = await self.pool.fetchrow(
                f"""update needs_items set snoozed_until = $2, snooze_pushed = false, updated_at = now()
                    where {where} and status = 'open' returning *""", arg, until)
        elif action == "open":
            row = await self.pool.fetchrow(
                f"""update needs_items set status = 'open', closed_at = null, snoozed_until = null, updated_at = now()
                    where {where} returning *""", arg)
        else:
            raise Invalid("action is done, dismissed, snooze or open")
        if row is None:
            return None
        if action != "snooze" and action != "open":
            await self.pool.execute("update alerts set acked_at = coalesce(acked_at, now()) where needs_id = $1",
                                    row["id"])
        item = need_json(row)
        await self.emit(row["session_id"], "needs", item)
        return item

    async def close_routine_items(self, routine_id: uuid.UUID) -> None:
        """A routine run went well: Krewd's own cards about its earlier failed or waiting runs are done."""
        rows = await self.pool.fetch(
            """update needs_items set status = 'done', closed_at = now(), updated_at = now()
               where routine_id = $1 and created_by = 'krewd' and status = 'open' returning *""", routine_id)
        for row in rows:
            await self.emit(row["session_id"], "needs", need_json(row))

    # the scheduler's minute tick

    async def tick(self, now: datetime | None = None) -> int:
        """Pushes for snoozes that ended and items due soon; once an hour, old alerts, items and tokens go."""
        now = now or datetime.now(UTC)
        sent = 0
        for row in await self.pool.fetch(
            """update needs_items set snooze_pushed = true, snoozed_until = null, updated_at = now()
               where status = 'open' and not snooze_pushed and snoozed_until <= $1 returning *""", now):
            await self.emit(row["session_id"], "needs", need_json(row))
            await self.notify("needs", row["level"], row["title"], self._need_body(row), session_id=row["session_id"],
                              needs_id=row["id"])
            sent += 1
        for row in await self.pool.fetch(
            """update needs_items set reminded_at = $1
               where status = 'open' and reminded_at is null and due_at is not null and due_at - $2::interval <= $1
                     and due_at > $1 - interval '1 hour' and (snoozed_until is null or snoozed_until <= $1)
               returning *""", now, REMIND_BEFORE):
            minutes = max(0, int((row["due_at"] - now).total_seconds() // 60))
            await self.notify("needs", row["level"], f"Due in {minutes} min: {row['title']}", self._need_body(row),
                              session_id=row["session_id"], needs_id=row["id"])
            sent += 1
        if now - self.last_clean > timedelta(hours=1):
            self.last_clean = now
            await self.pool.execute("delete from alerts where created_at < $1", now - KEEP_ALERTS)
            await self.pool.execute("delete from needs_items where status <> 'open' and closed_at < $1",
                                    now - KEEP_CLOSED_NEEDS)
            await self.pool.execute("delete from push_tokens where last_seen_at < $1", now - TOKEN_STALE)
            await self.pool.execute(
                "delete from events where session_id is null and kind in ('alert', 'needs') and created_at < $1",
                now - KEEP_ALERTS)
        return sent
