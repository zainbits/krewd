"""Where the agents' Brave says it is, set by hand from the app.

A home server has no GPS, so the location extension in the agents' Brave (deploy/fixed-location) answers pages
with the location the owner applies here: typed in, read once from the phone's own GPS, or one of their saved
places. Nothing changes until the owner applies a location; Krewd never follows the phone by itself.

- Applying a location makes it current and puts it at the top of the recent ones (the current one plus the
  last MAX_PREVIOUS before it). Applying one that is already there moves it up instead of adding it again.
- Favorites are named places the owner saved.
- Reapply sends the current location to Brave again, for a page that did not pick it up.
- Until anything is applied, KREWD_BROWSER_LOCATION (from krewd.env) is the current location.
- When Brave starts (or restarts), the server checks that the extension still holds the current location and
  puts it back if the profile lost it.
- Pages get it at once: Brave loads a copy of the extension from the config folder (run-browser.sh), and the
  server keeps the current location in that copy's current.js, which Brave reads when it starts, so a page never
  waits for the extension's storage. A location applied while Brave runs reaches pages through the storage
  (open ones at once, new ones about 0.3 s after they start) until Brave's next start. Reloading the extension
  instead would turn it off: Brave does that to extensions loaded from the command line.
"""

from __future__ import annotations

import json
import logging
import os
import uuid
from pathlib import Path
from typing import Any

import asyncpg

from .browser import Browser, BrowserUnavailable
from .config import CONFIG_DIR

log = logging.getLogger("krewd.locations")

MAX_PREVIOUS = 3  # recent locations kept besides the current one
SAME_DEGREES = 1e-6  # about 0.1 m: the same place
MAX_FAVORITES = 50
# The extension copy Brave loads (run-browser.sh makes it from deploy/fixed-location): its current.js.
LOCATION_SCRIPT = CONFIG_DIR / "location-extension" / "current.js"
CURRENT_JS_HEAD = (
    "// Written by the Krewd server (locations.py): the location the owner applied in the app, for location.js.\n"
)


class Invalid(ValueError):
    pass


def check(lat: float, lon: float, accuracy: float) -> tuple[float, float, float]:
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        raise Invalid("latitude is -90..90 and longitude -180..180")
    if not (1 <= accuracy <= 100_000):
        raise Invalid("accuracy is 1..100000 metres")
    return float(lat), float(lon), float(accuracy)


def parse_config(value: str) -> dict[str, float] | None:
    """KREWD_BROWSER_LOCATION: "LAT,LON[,ACCURACY_M]" in decimal degrees and metres; None if unset or wrong."""
    if not value:
        return None
    parts = [p.strip() for p in value.split(",")]
    try:
        lat, lon = float(parts[0]), float(parts[1])
        accuracy = float(parts[2]) if len(parts) > 2 and parts[2] else 50.0
        lat, lon, accuracy = check(lat, lon, accuracy)
    except (IndexError, ValueError):
        log.warning("KREWD_BROWSER_LOCATION must be LAT,LON[,ACCURACY_M]; ignoring it")
        return None
    return {"lat": lat, "lon": lon, "accuracy": accuracy}


def same(a: dict[str, Any] | None, b: dict[str, Any] | None) -> bool:
    if not a or not b:
        return a is None and b is None
    return abs(a["lat"] - b["lat"]) < SAME_DEGREES and abs(a["lon"] - b["lon"]) < SAME_DEGREES


def _row(r: asyncpg.Record) -> dict[str, Any]:
    out = {"id": str(r["id"]), "name": r["name"], "lat": r["lat"], "lon": r["lon"], "accuracy": r["accuracy"],
           "source": r["source"]}
    when = r["applied_at"] if r["kind"] == "recent" else r["created_at"]
    out["applied_at" if r["kind"] == "recent" else "created_at"] = when.isoformat() if when else None
    return out


class Locations:
    def __init__(self, pool: asyncpg.Pool, browser: Browser, config_value: str = "", script: Path | None = None):
        self.pool = pool
        self.browser = browser
        self.script = script  # current.js in the extension copy Brave loads (None: do not write one)
        self.config = parse_config(config_value)
        # What the extension last reported, and how the last attempt to reach it went.
        self.status = "unknown"  # applied, waiting (Brave is not running), error, unknown (not checked yet)
        self.detail: str | None = None
        self.held: dict[str, float] | None = None

    async def _recents(self) -> list[asyncpg.Record]:
        return await self.pool.fetch(
            "select * from browser_locations where kind = 'recent' order by applied_at desc")

    async def current(self) -> dict[str, Any] | None:
        rows = await self._recents()
        if rows:
            return _row(rows[0])
        if self.config:
            return {"id": None, "name": None, **self.config, "source": "config", "applied_at": None}
        return None

    async def state(self) -> dict[str, Any]:
        recents = [_row(r) for r in await self._recents()]
        favorites = [_row(r) for r in await self.pool.fetch(
            "select * from browser_locations where kind = 'favorite' order by lower(name), created_at")]
        current = await self.current()
        status = self.status
        if status == "applied" and not same(self.held, current):
            status = "unknown"
        return {
            "current": current,
            "browser": status,
            "detail": self.detail,
            "recent": recents[1:] if recents else [],  # the ones before the current location
            "favorites": favorites,
            "config": self.config,
        }

    async def apply(self, lat: float, lon: float, accuracy: float, name: str | None = None,
                    source: str = "manual") -> dict[str, Any]:
        """Make this the current location, then send it to Brave. Returns the new state."""
        lat, lon, accuracy = check(lat, lon, accuracy)
        name = (name or "").strip()[:80] or None
        async with self.pool.acquire() as conn, conn.transaction():
            await conn.execute("lock table browser_locations in share row exclusive mode")
            rows = await conn.fetch("select * from browser_locations where kind = 'recent' order by applied_at desc")
            match = next((r for r in rows if same(dict(r), {"lat": lat, "lon": lon})), None)
            if match:
                await conn.execute(
                    """update browser_locations set applied_at = now(), lat = $2, lon = $3, accuracy = $4,
                       name = coalesce($5, name), source = $6 where id = $1""",
                    match["id"], lat, lon, accuracy, name, source)
            else:
                await conn.execute(
                    """insert into browser_locations (id, kind, name, lat, lon, accuracy, source, applied_at)
                       values ($1, 'recent', $2, $3, $4, $5, $6, now())""",
                    uuid.uuid4(), name, lat, lon, accuracy, source)
            await conn.execute(
                """delete from browser_locations where kind = 'recent' and id not in
                   (select id from browser_locations where kind = 'recent' order by applied_at desc limit $1)""",
                MAX_PREVIOUS + 1)
        await self.push()
        return await self.state()

    def _write_script(self, value: dict[str, float] | None) -> bool:
        """Put the location into the extension's current.js, for Brave's next start. True when the file changed."""
        if self.script is None or not self.script.parent.is_dir():
            return False
        text = (CURRENT_JS_HEAD + "globalThis.__krewdLocation = " + json.dumps(value) + ";\n")
        try:
            if self.script.read_text() == text:
                return False
        except OSError:
            pass
        tmp = self.script.with_suffix(".tmp")
        tmp.write_text(text)
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.script)
        return True

    async def push(self) -> None:
        """Send the current location to the extension and note how it went."""
        current = await self.current()
        value = {k: current[k] for k in ("lat", "lon", "accuracy")} if current else None
        self._write_script(value)
        try:
            self.held = await self.browser.location(value)
            self.status, self.detail = ("applied", None) if same(self.held, value) else ("error", "Brave kept another location")
        except BrowserUnavailable:
            self.status, self.detail = "waiting", "The agents' Brave is not running; it gets this location when it starts"
        except Exception as exc:  # the extension missing or not answering
            self.status, self.detail = "error", str(exc) or type(exc).__name__
            log.warning("location not applied: %s", self.detail)

    async def sync(self) -> None:
        """Brave was reached: make sure the extension holds the current location (no change if it already does)."""
        try:
            current = await self.current()
            self.held = await self.browser.location("read")
            value = {k: current[k] for k in ("lat", "lon", "accuracy")} if current else None
            if same(self.held, value) and (not value or abs(self.held["accuracy"] - value["accuracy"]) < 0.5):
                self.status, self.detail = "applied", None
                self._write_script(value)
            else:
                await self.push()
        except BrowserUnavailable:
            self.status, self.detail = "waiting", "The agents' Brave is not running; it gets this location when it starts"
        except Exception as exc:
            self.status, self.detail = "error", str(exc) or type(exc).__name__
            log.warning("location check failed: %s", self.detail)

    async def add_favorite(self, name: str, lat: float, lon: float, accuracy: float) -> dict[str, Any]:
        lat, lon, accuracy = check(lat, lon, accuracy)
        name = name.strip()[:80]
        if not name:
            raise Invalid("a favorite needs a name")
        count = await self.pool.fetchval("select count(*) from browser_locations where kind = 'favorite'")
        if count >= MAX_FAVORITES:
            raise Invalid(f"at most {MAX_FAVORITES} favorites")
        await self.pool.execute(
            """insert into browser_locations (id, kind, name, lat, lon, accuracy, source)
               values ($1, 'favorite', $2, $3, $4, $5, 'manual')""", uuid.uuid4(), name, lat, lon, accuracy)
        return await self.state()

    async def rename_favorite(self, location_id: uuid.UUID, name: str) -> dict[str, Any]:
        name = name.strip()[:80]
        if not name:
            raise Invalid("a favorite needs a name")
        done = await self.pool.execute(
            "update browser_locations set name = $2 where id = $1 and kind = 'favorite'", location_id, name)
        if done.endswith(" 0"):
            raise LookupError("no such favorite")
        return await self.state()

    async def delete(self, location_id: uuid.UUID) -> dict[str, Any]:
        """Forget a favorite or a recent location. The current one stays until another is applied."""
        rows = await self._recents()
        if rows and rows[0]["id"] == location_id:
            raise Invalid("this is the current location; apply another one first")
        done = await self.pool.execute("delete from browser_locations where id = $1", location_id)
        if done.endswith(" 0"):
            raise LookupError("no such location")
        return await self.state()


def _check(port: int) -> None:
    """For ./master.sh doctor: prints "set" or "none" (whether the extension holds a location), never the place."""
    import asyncio
    import sys

    try:
        held = asyncio.run(Browser(port).location("read"))
    except Exception as exc:
        sys.exit(f"no answer: {exc}")
    print("set" if held else "none")


if __name__ == "__main__":
    import sys

    if sys.argv[1:2] != ["check"]:
        sys.exit("usage: python -m krewd_server.locations check [CDP_PORT]")
    _check(int(sys.argv[2]) if len(sys.argv) > 2 else 9222)
