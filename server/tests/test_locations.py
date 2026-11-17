"""The agents' Brave location: applied by hand from the app, with recents and favorites.

Uses a stand-in for the browser (the real extension page needs Brave); needs KREWD_TEST_DATABASE_URL.
"""

from __future__ import annotations

import os
import uuid

import httpx
import pytest

from krewd_server import db
from krewd_server.browser import BrowserUnavailable
from krewd_server.config import load_env_file
from krewd_server.locations import MAX_PREVIOUS, Invalid, Locations, parse_config

load_env_file()
DATABASE_URL = os.environ.get("KREWD_TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not DATABASE_URL, reason="KREWD_TEST_DATABASE_URL is not set")


class FakeBrowser:
    """Holds what the extension would hold; `down` makes Brave unreachable."""

    def __init__(self):
        self.held = None
        self.down = False
        self.writes = 0
        self.reloads = 0

    async def location(self, value="read"):
        if self.down:
            raise BrowserUnavailable("not running")
        if value == "reload":
            self.reloads += 1
            return None
        if value != "read":
            self.writes += 1
            self.held = value
        return self.held


@pytest.fixture
async def pool():
    pool = await db.connect(DATABASE_URL)
    await pool.execute("drop table if exists browser_locations cascade")
    if await pool.fetchval("select to_regclass('schema_version')"):
        await pool.execute("delete from schema_version where version >= 8")  # so migrate makes the table again
    await db.migrate(pool)
    yield pool
    await pool.close()


async def test_config_is_current_until_one_is_applied(pool):
    browser = FakeBrowser()
    loc = Locations(pool, browser, "48.85,2.35,35")
    state = await loc.state()
    assert state["current"]["source"] == "config" and state["current"]["lat"] == 48.85
    await loc.sync()  # Brave started: the extension gets the config location
    assert browser.held == {"lat": 48.85, "lon": 2.35, "accuracy": 35.0}
    assert (await loc.state())["browser"] == "applied"
    writes = browser.writes
    await loc.sync()  # it already holds it: nothing is written again
    assert browser.writes == writes


async def test_apply_keeps_the_last_ones_without_repeats(pool):
    browser = FakeBrowser()
    loc = Locations(pool, browser)
    for i in range(6):
        await loc.apply(10 + i, 20, 30, source="manual")
    state = await loc.state()
    assert state["current"]["lat"] == 15 and state["browser"] == "applied"
    assert [r["lat"] for r in state["recent"]] == [14, 13, 12][:MAX_PREVIOUS]
    # Applying one that is already there moves it to the top instead of adding it again.
    state = await loc.apply(13, 20, 30, source="recent")
    assert state["current"]["lat"] == 13
    assert [r["lat"] for r in state["recent"]] == [15, 14, 12]
    # Applying the current one again (reapply by hand) changes nothing in the list.
    state = await loc.apply(13, 20, 30)
    assert [r["lat"] for r in state["recent"]] == [15, 14, 12]
    assert browser.held == {"lat": 13.0, "lon": 20.0, "accuracy": 30.0}


async def test_favorites_and_their_name_in_recents(pool):
    loc = Locations(pool, FakeBrowser())
    state = await loc.add_favorite("Office", 51.5, -0.12, 35)
    office = state["favorites"][0]
    assert office["name"] == "Office"
    state = await loc.apply(office["lat"], office["lon"], office["accuracy"], name="Office", source="favorite")
    assert state["current"]["name"] == "Office"
    state = await loc.rename_favorite(office["id"], "Work")
    assert state["favorites"][0]["name"] == "Work"
    with pytest.raises(Invalid):
        await loc.add_favorite("  ", 1, 1, 10)
    state = await loc.delete(office["id"])
    assert state["favorites"] == [] and state["current"]["name"] == "Office"  # applied places stay as they were


async def test_the_current_location_cannot_be_deleted(pool):
    loc = Locations(pool, FakeBrowser())
    await loc.apply(1, 2, 10)
    state = await loc.apply(3, 4, 10)
    with pytest.raises(Invalid):
        await loc.delete(uuid.UUID(state["current"]["id"]))
    state = await loc.delete(uuid.UUID(state["recent"][0]["id"]))
    assert state["recent"] == []


async def test_brave_down_waits_then_gets_it_when_it_starts(pool):
    browser = FakeBrowser()
    browser.down = True
    loc = Locations(pool, browser)
    state = await loc.apply(5, 6, 20, source="phone")
    assert state["browser"] == "waiting" and state["current"]["source"] == "phone"
    browser.down = False
    await loc.sync()
    assert browser.held == {"lat": 5.0, "lon": 6.0, "accuracy": 20.0}
    assert (await loc.state())["browser"] == "applied"


async def test_bad_values_are_refused(pool):
    loc = Locations(pool, FakeBrowser())
    with pytest.raises(Invalid):
        await loc.apply(91, 0, 10)
    with pytest.raises(Invalid):
        await loc.apply(0, 0, 0)
    assert parse_config("nonsense") is None
    assert parse_config("1.5,2.5") == {"lat": 1.5, "lon": 2.5, "accuracy": 50.0}


async def test_api_routes(pool, tmp_path):
    from krewd_server.api import create_app
    from krewd_server.config import Settings
    from krewd_server.hub import Hub

    settings = Settings(database_url=DATABASE_URL, host="127.0.0.1", port=0, workdir=tmp_path,
                        token_file=tmp_path / "token", state_dir=tmp_path / "state", engines={}, default_engine="",
                        cdp_port=1, data_dir=tmp_path / "data", tls="off")
    app = create_app(Hub(settings, pool), "test-token", lifespan_hub=False)
    headers = {"Authorization": "Bearer test-token"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=("127.0.0.1", 1)),
                                 base_url="http://test", headers=headers) as client:
        r = await client.get("/api/browser/location")
        assert r.status_code == 200 and r.json()["current"] is None
        r = await client.post("/api/browser/location", json={"lat": 40.71, "lon": -74.0, "accuracy": 12, "source": "phone"})
        body = r.json()
        assert r.status_code == 200 and body["current"]["lat"] == 40.71
        assert body["browser"] == "waiting"  # no Brave on port 1
        r = await client.post("/api/browser/location/favorites", json={"name": "Home", "lat": 40.71, "lon": -74.0})
        assert r.status_code == 201 and r.json()["favorites"][0]["name"] == "Home"
        fav = r.json()["favorites"][0]["id"]
        r = await client.patch(f"/api/browser/location/favorites/{fav}", json={"name": "Flat"})
        assert r.json()["favorites"][0]["name"] == "Flat"
        r = await client.post("/api/browser/location", json={"lat": 100, "lon": 0})
        assert r.status_code == 422
        r = await client.post("/api/browser/location/reapply")
        assert r.status_code == 200 and r.json()["browser"] == "waiting"
        r = await client.delete(f"/api/browser/location/{fav}")
        assert r.status_code == 200 and r.json()["favorites"] == []
        assert (await client.get("/api/browser/location", headers={"Authorization": "Bearer nope"})).status_code == 401


async def test_pages_get_it_at_once_from_current_js(pool, tmp_path):
    # The extension copy Brave loads has current.js, which Brave reads when it starts: syncing and applying keep
    # the current location there, so a page never waits for the extension's storage. Never a reload.
    (tmp_path / "ext").mkdir()
    script = tmp_path / "ext" / "current.js"
    browser = FakeBrowser()
    loc = Locations(pool, browser, "48.85,2.35,35", script=script)
    await loc.sync()
    assert '"lat": 48.85' in script.read_text()
    await loc.sync()
    await loc.apply(40.71, -74.0, 15, source="phone")
    text = script.read_text()
    assert "globalThis.__krewdLocation = " in text and '"lat": 40.71' in text and browser.reloads == 0
    assert oct(script.stat().st_mode & 0o777) == "0o600"
    # Brave down: the file is still written, so Brave has it the moment it starts.
    browser.down = True
    await loc.apply(5, 6, 20)
    assert '"lat": 5.0' in script.read_text()
    # No extension copy (Brave loads the repo folder): nothing is written.
    other = Locations(pool, FakeBrowser(), script=tmp_path / "missing" / "current.js")
    await other.sync()
    assert not (tmp_path / "missing").exists()
