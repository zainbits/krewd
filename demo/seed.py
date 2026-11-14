"""Fill the demo server with made-up chats and routines by playing the demo scenes through the real API.

Run by `./master.sh demo seed` (demo server up, its config in KREWD_CONFIG_DIR). It approves what the
scenes ask, lets the agent create one routine itself, runs every routine once and then moves some chats
back in time so the app shows Today, Yesterday and Earlier. Nothing here is real data.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
from datetime import timedelta
import urllib.error
import urllib.request
from pathlib import Path

import asyncpg

CONFIG = Path(os.environ["KREWD_CONFIG_DIR"])


def env(key: str, default: str = "") -> str:
    for line in (CONFIG / "krewd.env").read_text().splitlines():
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return default


BASE = f"http://127.0.0.1:{env('KREWD_PORT', '8810')}"
TOKEN = (CONFIG / "token").read_text().strip()

SAVED_PROMPTS = [
    "Find me the cheapest Mumbai to Dubai flight, 20 to 27 Dec",
    "The checkout build is failing. Fix it and open a PR",
    "Check our competitors' pricing pages and tell me what changed",
    "Log in to the practice portal and open my account page",
    "Read this week's arXiv papers on AI agents and give me a brief",
    "Triage today's new issues on the storefront repo",
]


def api(method: str, path: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{method} {path}: {error.code} {error.read().decode()[:300]}")


def turns_ended(session_id: str) -> int:
    events = api("GET", f"/api/sessions/{session_id}/events")["events"]
    return sum(1 for e in events if e["kind"] == "turn_end")


def wait_turn(session_id: str, ended_before: int, timeout: float = 300) -> None:
    """Wait until the chat has one more finished turn than before, answering its approvals with Yes."""
    end = time.time() + timeout
    while time.time() < end:
        for approval in api("GET", "/api/approvals?status=pending") or []:
            if approval.get("session_id") == session_id:
                api("POST", f"/api/approvals/{approval['id']}", {"option_id": "allow"})
        if turns_ended(session_id) > ended_before:
            return
        time.sleep(1.5)
    raise SystemExit(f"timed out waiting for {session_id}")


def task(text: str, engine: str, follow_up: str | None = None) -> str:
    session = api("POST", "/api/sessions", {"text": text, "engine": engine})
    print(f"  {engine:6} {text[:60]}")
    wait_turn(session["id"], 0)
    if follow_up:
        before = turns_ended(session["id"])
        api("POST", f"/api/sessions/{session['id']}/messages", {"text": follow_up})
        wait_turn(session["id"], before)
    return session["id"]


ROUTINES = [
    {"title": "Nightly repo doctor", "engine": "codex", "by": "owner",
     "triggers": [{"name": "nightly", "schedule": "0 2 * * *"}],
     "instructions": "Pull acme/storefront, install, run every test suite and the audit. Only tell me when something "
                     "broke or an update is waiting; open a PR for patch updates only after I say yes."},
    {"title": "Competitor pricing watch", "engine": "claude", "by": "agent",
     "triggers": [{"name": "weekly", "schedule": "0 9 * * 1"}],
     "instructions": "Open the Linear, Vercel and Supabase pricing pages and read every plan's price. Compare with the "
                     "notes from last week and tell me only what changed. Keep the latest prices in the notes."},
    {"title": "Weekly AI papers brief", "engine": "codex", "by": "owner",
     "triggers": [{"name": "sunday", "schedule": "0 8 * * 0"}],
     "instructions": "Read the newest cs.AI papers on arXiv, pick the ones about agents and write me a five-minute "
                     "brief in ~/outputs/agents-brief.md."},
    {"title": "Issue triage", "engine": "codex", "by": "owner",
     "triggers": [{"name": "evening", "schedule": "0 18 * * 1-5", "max_attempts": 3}],
     "instructions": "Wait for CI on main to finish, then list today's new issues on acme/storefront, reproduce the "
                     "bugs, label them and draft fixes on branches. If CI is still running, try again in 10 minutes."},
]


async def backdate(chats: dict[str, float]) -> None:
    """Move chats (and their events) back by the given number of hours."""
    pool = await asyncpg.create_pool(env("KREWD_DATABASE_URL"), min_size=1, max_size=2)
    async with pool.acquire() as conn:
        for session_id, hours in chats.items():
            shift = timedelta(hours=hours)
            await conn.execute("update sessions set created_at = created_at - $2::interval, updated_at = updated_at - $2::interval "
                               "where id = $1::uuid", session_id, shift)
            await conn.execute("update events set created_at = created_at - $2::interval where session_id = $1::uuid",
                               session_id, shift)
    await pool.close()


def main() -> None:
    if api("GET", "/api/sessions"):
        raise SystemExit("The demo already has chats. Run ./master.sh demo reset first for a clean seed.")
    print("Saved prompts and app settings")
    api("PUT", "/api/prefs/saved_prompts", {"value": SAVED_PROMPTS})
    api("PUT", "/api/prefs/last_engine", {"value": "claude"})

    print("Chats (the demo agents play their scenes)")
    papers = task(SAVED_PROMPTS[4], "codex")
    competitors = task(SAVED_PROMPTS[2], "claude")
    build = task(SAVED_PROMPTS[1], "codex")
    flights = task(SAVED_PROMPTS[0], "claude", follow_up="Yes, watch it every morning and tell me if it drops")

    print("Routines")
    made = {}
    for routine in ROUTINES:
        body = {k: v for k, v in routine.items() if k != "engine"} | {"engine": routine["engine"], "timezone": "Asia/Kolkata"}
        made[routine["title"]] = api("POST", "/api/routines", body)["id"]
        print(f"  {routine['title']}")
    for item in api("GET", "/api/routines") or []:
        made.setdefault(item["title"], item["id"])

    print("One run of each routine")
    for title, routine_id in made.items():
        chat = api("GET", f"/api/routines/{routine_id}").get("session_id")
        before = turns_ended(chat) if chat else 0
        run = api("POST", f"/api/routines/{routine_id}/run", {})
        print(f"  {title}")
        chat = run.get("session_id") or api("GET", f"/api/routines/{routine_id}").get("session_id")
        if chat:
            wait_turn(chat, before)

    api("PATCH", f"/api/sessions/{build}", {"pinned": True})
    asyncio.run(backdate({papers: 24 * 5 + 3, competitors: 24 * 2 + 5, build: 20, flights: 2}))
    print("Done. Open the demo app: the chats, routines and a pinned chat are there.")


if __name__ == "__main__":
    sys.exit(main())
