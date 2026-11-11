"""Profiles: config, keys, Codex catalogs, agent environments, and (with a database) the hub and API."""

from __future__ import annotations

import asyncio
import json
import os
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
import pytest

from crew_server import db
from crew_server.api import create_app
from crew_server.config import Engine, Profile, Settings, load_engines, load_env_file, load_profiles, with_profiles
from crew_server.hub import Hub
from crew_server.profiles import Gateways, ProfileError, codex_catalog, default_model

load_env_file()
DATABASE_URL = os.environ.get("CREW_TEST_DATABASE_URL")
FAKE = Path(__file__).with_name("fake_agent.py")
KEY = "k-test-0123456789"

BUNDLED = {"models": [
    {"slug": "gpt-new", "display_name": "GPT New", "visibility": "list", "priority": 1, "tool_mode": "code_mode_only",
     "use_responses_lite": True, "prefer_websockets": True, "context_window": 272000, "supported_reasoning_levels": [],
     "input_modalities": ["text", "image"], "base_instructions": "You are Codex."},
    {"slug": "gpt-old", "display_name": "GPT Old", "visibility": "list", "priority": 2, "tool_mode": None,
     "use_responses_lite": False, "prefer_websockets": True, "context_window": 128000, "max_context_window": 128000,
     "supported_reasoning_levels": [{"effort": "xhigh", "description": ""}], "default_reasoning_level": "xhigh",
     "input_modalities": ["text"], "base_instructions": "You are Codex.", "availability_nux": {"message": "hi"}},
    {"slug": "gpt-hidden", "display_name": "Hidden", "visibility": "hide", "priority": 3, "base_instructions": "x"},
]}

ROUTER_MODELS = [
    {"id": "vendor/reasoner", "name": "Vendor: Reasoner", "description": "Thinks.\nMore text.", "context_length": 1000000,
     "architecture": {"input_modalities": ["text", "image"]}, "supported_parameters": ["tools", "reasoning", "parallel_tool_calls"]},
    {"id": "vendor/plain", "name": "Vendor: Plain", "context_length": 200000,
     "architecture": {"input_modalities": ["text"]}, "supported_parameters": ["tools"]},
]


def write(path: Path, text: str) -> Path:
    path.write_text(text)
    return path


# config


def test_profiles_add_one_engine_per_gateway_they_serve(tmp_path):
    engines, _ = load_engines(write(tmp_path / "engines.toml", """
default = "claude"
[engines.claude]
name = "Claude Code"
command = ["claude-agent-acp"]
gateway = "claude"
[engines.codex]
name = "Codex"
command = ["codex-acp"]
gateway = "codex"
[engines.other]
command = ["other-acp"]
"""))
    profiles = load_profiles(write(tmp_path / "profiles.toml", """
[profiles.router]
name = "Router"
key = "ROUTER_KEY"
openai = "https://router.example/api/v1/"
anthropic = "https://router.example/api"
[profiles.proxy]
name = "Proxy"
key = "PROXY_KEY"
key_file = "~/proxy.env"
openai = "http://proxy.example/v1"
"""))
    assert profiles["router"].openai == "https://router.example/api/v1"  # trailing slash dropped
    assert profiles["router"].key_file == tmp_path / "keys.env"
    assert profiles["proxy"].key_file == Path.home() / "proxy.env"
    all_engines = with_profiles(engines, profiles)
    assert list(all_engines) == ["claude", "claude@router", "codex", "codex@router", "codex@proxy", "other"]
    codex_router = all_engines["codex@router"]
    assert (codex_router.name, codex_router.base, codex_router.profile.id) == ("Codex · Router", "codex", "router")
    assert codex_router.command == ["codex-acp"]
    assert all_engines["claude"].base is None and all_engines["claude"].profile is None


def test_bad_profiles_and_gateways_are_reported(tmp_path):
    with pytest.raises(ValueError, match="gateway"):
        load_engines(write(tmp_path / "e.toml", '[engines.x]\ncommand = ["x"]\ngateway = "gemini"\n'))
    with pytest.raises(ValueError, match="key"):
        load_profiles(write(tmp_path / "p1.toml", '[profiles.x]\nopenai = "http://x"\n'))
    with pytest.raises(ValueError, match="URL"):
        load_profiles(write(tmp_path / "p2.toml", '[profiles.x]\nkey = "K"\n'))
    with pytest.raises(ValueError, match="profile id"):
        load_profiles(write(tmp_path / "p3.toml", '[profiles."Bad Id"]\nkey = "K"\nopenai = "http://x"\n'))
    assert load_profiles(tmp_path / "missing.toml") == {}


def test_keys_come_from_the_key_file_then_the_environment(tmp_path, monkeypatch):
    keys = write(tmp_path / "keys.env", "# keys\nOTHER=1\nexport ROUTER_KEY='sk-file'\n")
    profile = Profile(id="r", name="R", key="ROUTER_KEY", key_file=keys, openai="http://x")
    assert profile.read_key() == "sk-file"
    monkeypatch.setenv("ENV_KEY", "sk-env")
    assert Profile(id="r", name="R", key="ENV_KEY", key_file=keys, openai="http://x").read_key() == "sk-env"
    monkeypatch.delenv("ENV_KEY")
    assert Profile(id="r", name="R", key="ENV_KEY", key_file=tmp_path / "none", openai="http://x").read_key() is None


# Codex catalogs


def test_a_gateway_with_codex_names_gets_the_codex_models_it_serves():
    served = [{"id": "tts-1"}, {"id": "gpt-old"}, {"id": "gpt-new"}, {"id": "whisper-1"}]
    catalog = codex_catalog(served, BUNDLED)
    assert [m["slug"] for m in catalog["models"]] == ["gpt-new", "gpt-old"]  # Codex's order, nothing else
    assert catalog["models"][0] == BUNDLED["models"][0]  # unchanged
    assert default_model(catalog) == "gpt-new"


def test_other_gateways_get_their_own_list_on_a_plain_template():
    catalog = codex_catalog(ROUTER_MODELS, BUNDLED)
    reasoner, plain = catalog["models"]
    assert [reasoner["slug"], plain["slug"]] == ["vendor/reasoner", "vendor/plain"]
    # the plain template is Codex's ordinary-tools entry, with OpenAI-only extras off
    assert reasoner["tool_mode"] is None and reasoner["use_responses_lite"] is False
    assert reasoner["prefer_websockets"] is False and reasoner["availability_nux"] is None
    assert reasoner["base_instructions"] == "You are Codex."
    assert reasoner["display_name"] == "Vendor: Reasoner" and reasoner["description"] == "Thinks."
    assert reasoner["context_window"] == reasoner["max_context_window"] == 950000
    assert reasoner["input_modalities"] == ["text", "image"] and reasoner["supports_parallel_tool_calls"] is True
    assert [lvl["effort"] for lvl in reasoner["supported_reasoning_levels"]] == ["low", "medium", "high"]
    assert plain["supported_reasoning_levels"] == [] and plain["default_reasoning_level"] is None
    assert plain["input_modalities"] == ["text"] and plain["supports_parallel_tool_calls"] is False
    assert [reasoner["priority"], plain["priority"]] == [1, 2]
    assert default_model(catalog) == "vendor/reasoner"


def test_a_gateway_without_metadata_still_gets_entries():
    catalog = codex_catalog([{"id": "local-model"}], BUNDLED)
    entry = catalog["models"][0]
    assert entry["slug"] == entry["display_name"] == "local-model"
    assert entry["context_window"] == 128000  # the template's
    assert [lvl["effort"] for lvl in entry["supported_reasoning_levels"]] == ["low", "medium", "high"]


# agent environments, against a fake gateway and a fake Codex


@pytest.fixture
def gateway():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.headers.get("Authorization") != f"Bearer {KEY}":
                self.send_response(401)
                self.end_headers()
                return
            models = ROUTER_MODELS
            if self.headers.get("anthropic-version"):  # like OpenRouter: every model named for Claude Code
                models = [{"id": f"anthropic/{m['id']}[1m]", "display_name": m["name"]} for m in ROUTER_MODELS]
            body = json.dumps({"data": models}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


@pytest.fixture
def fake_codex(tmp_path):
    catalog = write(tmp_path / "bundled.json", json.dumps(BUNDLED))
    mark = tmp_path / "codex-signed-in"
    script = write(tmp_path / "codex", f"""#!/bin/sh
case "$1 $2 $3" in
  "debug models --bundled") exec cat {catalog} ;;
  "login status ") if [ -f {mark} ]; then echo "Logged in using ChatGPT"; else echo "Not logged in"; exit 1; fi ;;
  "login --device-auth ") echo "1. Open this link in your browser and sign in to your account"
    echo "   https://auth.example.com/codex/device"
    echo "2. Enter this one-time code (expires in 15 minutes)"
    echo "   ABCD-EFG12"
    sleep 1; touch {mark}; echo "Successfully logged in"; exit 0 ;;
  "logout  ") rm -f {mark} ;;
  *) exit 2 ;;
esac
""")
    script.chmod(0o755)
    return str(script)


@pytest.fixture
def fake_claude(tmp_path):
    mark = tmp_path / "claude-signed-in"
    script = write(tmp_path / "claude", f"""#!/bin/sh
case "$1 $2" in
  "auth status") if [ -f {mark} ]; then echo '{{"loggedIn": true, "email": "owner@example.com", "subscriptionType": "max"}}'
    else echo '{{"loggedIn": false, "authMethod": "none"}}'; fi ;;
  "auth login") echo "Opening browser to sign in..."
    echo "If the browser didn't open, visit: https://claude.example.com/oauth/authorize?code=true&state=x"
    printf "Paste code here if prompted > "
    read code
    if [ "$code" = "good-code" ]; then touch {mark}; echo "Login successful."; exit 0; fi
    echo "Invalid code"; exit 1 ;;
  "auth logout") rm -f {mark} ;;
esac
""")
    script.chmod(0o755)
    return str(script)


def profile_engines(tmp_path, gateway: str, codex: str, key: str | None = KEY) -> dict[str, Engine]:
    keys = write(tmp_path / "keys.env", f"GW_KEY={key}\n" if key else "")
    profile = Profile(id="gw", name="Gateway", key="GW_KEY", key_file=keys, openai=f"{gateway}/v1", anthropic=gateway)
    engines = {
        "fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)], gateway="claude",
                       env={"CLAUDE_PATH": str(tmp_path / "no-claude")}),  # never the machine's real claude
        "cx": Engine(id="cx", name="Codex", command=[sys.executable, str(FAKE)], gateway="codex",
                     env={"CODEX_PATH": codex, "CODEX_CONFIG": '{"approval_policy": "never"}'}),
    }
    return with_profiles(engines, {"gw": profile})


async def test_claude_engines_get_the_gateway_key_and_a_model_the_key_may_use(tmp_path, gateway, fake_codex):
    engine = profile_engines(tmp_path, gateway, fake_codex)["fake@gw"]
    env = await Gateways(port=8800).engine_env(engine)
    first = "anthropic/vendor/reasoner[1m]"  # the gateway's first model, as Claude Code's discovery sees it
    assert env == {"ANTHROPIC_BASE_URL": gateway, "ANTHROPIC_AUTH_TOKEN": KEY, "ANTHROPIC_API_KEY": "",
                   "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY": "1", "ANTHROPIC_MODEL": first,
                   "ANTHROPIC_SMALL_FAST_MODEL": first}


def test_claude_profiles_start_on_a_listed_model_only_off_claude_names():
    from crew_server.profiles import claude_defaults

    # an Anthropic-style proxy (Claude Code's own names) may list models it refuses: Claude Code's default stays
    assert claude_defaults(["claude-fable-9", "claude-haiku-9"]) == {}
    assert claude_defaults([]) == {}
    # OpenRouter-style ids: the first listed model, and the background model when there is no Haiku
    assert claude_defaults(["anthropic/claude-sonnet-9[1m]", "anthropic/claude-haiku-9[1m]"]) == {
        "ANTHROPIC_MODEL": "anthropic/claude-sonnet-9[1m]"}
    assert claude_defaults(["anthropic/glm[1m]"]) == {"ANTHROPIC_MODEL": "anthropic/glm[1m]",
                                                       "ANTHROPIC_SMALL_FAST_MODEL": "anthropic/glm[1m]"}


def test_claude_lists_what_its_discovery_keeps():
    from crew_server.profiles import claude_listed

    models = [{"id": "claude-haiku-9"}, {"id": "gpt-x"}, {"id": "anthropic/glm[1m]"}]
    assert claude_listed(models) == ["claude-haiku-9", "anthropic/glm[1m]"]


async def test_codex_engines_get_a_provider_with_a_catalog_from_crew(tmp_path, gateway, fake_codex):
    engine = profile_engines(tmp_path, gateway, fake_codex)["cx@gw"]
    gateways = Gateways(port=8800)
    env = await gateways.engine_env(engine)
    assert env["GW_KEY"] == KEY and env["MODEL_PROVIDER"] == "crew-gw"
    config = json.loads(env["CODEX_CONFIG"])
    assert config["approval_policy"] == "never"  # the engine's own CODEX_CONFIG is kept
    assert config["model"] == "vendor/reasoner"
    assert config["model_providers"]["crew-gw"] == {
        "name": "Gateway", "base_url": f"{gateway}/v1", "env_key": "GW_KEY", "wire_api": "responses",
        "model_catalog_url": f"http://127.0.0.1:8800/api/codex-catalog/{gateways.secret}/cx@gw",
    }
    catalog = await gateways.codex_catalog(engine)
    assert [m["slug"] for m in catalog["models"]] == ["vendor/reasoner", "vendor/plain"]
    # the Anthropic form of the same list (for Claude Code) never leaks into Codex's catalog
    claude = profile_engines(tmp_path, gateway, fake_codex)["fake@gw"]
    claude = Engine(id=claude.id, name=claude.name, command=claude.command, gateway="claude", base="fake",
                    profile=engine.profile)  # same profile object, same gateway URL family
    await gateways.claude_models(claude)
    assert [m["slug"] for m in (await gateways.codex_catalog(engine))["models"]] == ["vendor/reasoner", "vendor/plain"]


async def test_missing_key_or_wrong_key_is_a_clear_error(tmp_path, gateway, fake_codex):
    engine = profile_engines(tmp_path, gateway, fake_codex, key=None)["cx@gw"]
    gateways = Gateways(port=8800)
    assert "No key" in gateways.problem(engine)
    with pytest.raises(ProfileError, match="no key for Gateway"):
        await gateways.engine_env(engine)
    engine = profile_engines(tmp_path, gateway, fake_codex, key="wrong")["cx@gw"]
    assert gateways.problem(engine) is None
    with pytest.raises(ProfileError, match="HTTP 401"):
        await gateways.engine_env(engine)


# hub and API (need a database)


@pytest.fixture
async def hub(tmp_path, gateway, fake_codex):
    if not DATABASE_URL:
        pytest.skip("CREW_TEST_DATABASE_URL is not set")
    pool = await db.connect(DATABASE_URL)
    await pool.execute(
        "drop table if exists session_agents, routine_triggers, routine_runs, routine_versions, routines, messages, events, approvals, sessions,"
        " engines, schema_version cascade"
    )
    settings = Settings(
        database_url=DATABASE_URL, host="127.0.0.1", port=0, workdir=tmp_path, token_file=tmp_path / "token",
        state_dir=tmp_path / "state", engines=profile_engines(tmp_path, gateway, fake_codex), default_engine="fake",
        data_dir=tmp_path / "data", max_upload_mb=1,
    )
    hub = Hub(settings, pool)
    await hub.start()
    yield hub
    await hub.close()
    await pool.close()


async def reply_to(hub: Hub, engine: str, text: str) -> str:
    from test_flow import wait_for

    session = await hub.create_session(text, engine)
    sid = uuid.UUID(session["id"])
    await wait_for(hub, sid, "turn_end")
    return "".join(e["payload"]["text"] for e in await hub.events(0, sid)
                   if e["kind"] == "text" and e["payload"].get("role") == "assistant")


async def test_profile_engines_are_listed_and_start_on_their_gateway(hub, gateway):
    listing = {e["id"]: e for e in hub.engines()}
    assert listing["fake"]["base"] is None and listing["fake"]["profile"] is None
    assert (listing["fake@gw"]["base"], listing["fake@gw"]["profile"], listing["fake@gw"]["profile_name"]) == ("fake", "gw", "Gateway")
    assert listing["fake@gw"]["name"] == "Fake · Gateway" and listing["fake@gw"]["problem"] is None
    # the agent's own login stays untouched; the profile engine gets the gateway
    assert await reply_to(hub, "fake", "env ANTHROPIC_BASE_URL") == os.environ.get("ANTHROPIC_BASE_URL", "<unset>")
    assert await reply_to(hub, "fake@gw", "env ANTHROPIC_BASE_URL") == gateway
    assert await reply_to(hub, "cx@gw", "env MODEL_PROVIDER") == "crew-gw"


async def test_codex_catalog_route_needs_the_secret(hub):
    app = create_app(hub, "test-token", lifespan_hub=False)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/api/codex-catalog/wrong/cx@gw")).status_code == 404
        assert (await client.get(f"/api/codex-catalog/{hub.gateways.secret}/fake@gw")).status_code == 404  # not Codex
        ok = await client.get(f"/api/codex-catalog/{hub.gateways.secret}/cx@gw", params={"client_version": "1"})
        assert ok.status_code == 200
        assert [m["slug"] for m in ok.json()["models"]] == ["vendor/reasoner", "vendor/plain"]


# profiles managed from the app (need a database)


@pytest.fixture
async def managed(tmp_path, fake_codex, fake_claude):
    if not DATABASE_URL:
        pytest.skip("CREW_TEST_DATABASE_URL is not set")
    pool = await db.connect(DATABASE_URL)
    await pool.execute(
        "drop table if exists session_agents, routine_triggers, routine_runs, routine_versions, routines, messages, events, approvals, sessions,"
        " engines, schema_version cascade"
    )
    engines = {
        "fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)], gateway="claude",
                       env={"CLAUDE_PATH": fake_claude}),
        "cx": Engine(id="cx", name="Codex", command=[sys.executable, str(FAKE)], gateway="codex", env={"CODEX_PATH": fake_codex}),
    }
    settings = Settings(
        database_url=DATABASE_URL, host="127.0.0.1", port=0, workdir=tmp_path, token_file=tmp_path / "token",
        state_dir=tmp_path / "state", engines=engines, default_engine="fake", data_dir=tmp_path / "data",
        max_upload_mb=1, profiles={}, profiles_file=tmp_path / "conf" / "profiles.toml",
    )
    hub = Hub(settings, pool)
    await hub.start()
    yield hub
    await hub.close()
    await pool.close()


def client(hub: Hub, host: str = "127.0.0.1") -> httpx.AsyncClient:
    app = create_app(hub, "test-token", lifespan_hub=False)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=(host, 5000)), base_url="http://test",
                             headers={"Authorization": "Bearer test-token"})


async def test_profiles_are_added_from_the_app_and_used_at_once(managed, gateway, tmp_path):
    conf = tmp_path / "conf"
    async with client(managed) as c:
        listing = (await c.get("/api/profiles")).json()
        assert listing["profiles"] == [] and listing["managed"] is True
        assert [p["id"] for p in listing["presets"]] == ["openrouter"] and "key" not in listing["presets"][0]

        made = await c.post("/api/profiles", json={"name": "My Gateway", "openai": f"{gateway}/v1/", "anthropic": gateway, "key": KEY})
        assert made.status_code == 201, made.text
        profile = made.json()
        assert profile["id"] == "my-gateway" and profile["openai"] == f"{gateway}/v1"
        assert profile["key_set"] is True and profile["key_hint"] == KEY[-4:]
        assert sorted(profile["engines"]) == ["cx@my-gateway", "fake@my-gateway"]
        assert KEY not in (await c.get("/api/profiles")).text  # never sent back

    # saved: profiles.toml without the key, the key in keys.env (owner-only)
    toml = (conf / "profiles.toml").read_text()
    assert "[profiles.my-gateway]" in toml and KEY not in toml and 'key = "MY_GATEWAY_API_KEY"' in toml
    assert (conf / "keys.env").read_text() == f"MY_GATEWAY_API_KEY={KEY}\n"
    assert (conf / "keys.env").stat().st_mode & 0o777 == 0o600
    assert load_profiles(conf / "profiles.toml")["my-gateway"].read_key() == KEY

    # the new engines are listed and run on the gateway straight away
    names = {e["id"]: e["name"] for e in managed.engines()}
    assert names["fake@my-gateway"] == "Fake · My Gateway"
    assert await reply_to(managed, "fake@my-gateway", "env ANTHROPIC_BASE_URL") == gateway


async def test_bad_keys_and_keys_over_plain_wifi_are_refused(managed, gateway):
    async with client(managed) as c:
        wrong = await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "key": "wrong-key-123456"})
        assert wrong.status_code == 422 and "HTTP 401" in wrong.json()["detail"]
        assert (await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1"})).status_code == 422  # no key
        assert (await c.post("/api/profiles", json={"name": "GW", "key": KEY})).status_code == 422  # no address
        assert (await c.post("/api/profiles", json={"name": "GW", "openai": "ftp://x", "key": KEY})).status_code == 422
    async with client(managed, host="192.168.0.20") as lan:
        refused = await lan.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "key": KEY})
        assert refused.status_code == 403 and "Tailscale" in refused.json()["detail"]
    assert managed.settings.profiles == {}


async def test_profiles_are_renamed_and_removed_only_when_unused(managed, gateway, tmp_path):
    async with client(managed) as c:
        await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "anthropic": gateway, "key": KEY})
    async with client(managed, host="192.168.0.20") as lan:  # no key in it: fine over the home network
        renamed = await lan.patch("/api/profiles/gw", json={"name": "Gateway Two"})
        assert renamed.status_code == 200 and renamed.json()["name"] == "Gateway Two"
        assert {e["id"]: e["name"] for e in managed.engines()}["cx@gw"] == "Codex · Gateway Two"

        reply = await reply_to(managed, "cx@gw", "env MODEL_PROVIDER")  # a chat now uses Codex on it
        assert reply == "crew-gw"
        blocked = await lan.delete("/api/profiles/gw")
        assert blocked.status_code == 409 and "1 chat" in blocked.json()["detail"]
        no_openai = await lan.patch("/api/profiles/gw", json={"openai": ""})
        assert no_openai.status_code == 409  # Codex would lose it
        assert (await lan.patch("/api/profiles/gw", json={"anthropic": ""})).status_code == 200  # Claude has no chat on it
        assert "fake@gw" not in managed.settings.engines

        for session in await managed.list_sessions(False):
            await managed.update_session(uuid.UUID(session["id"]), {"archived": True})
        assert (await lan.delete("/api/profiles/gw")).status_code == 200
        assert (await lan.delete("/api/profiles/gw")).status_code == 404
    assert "cx@gw" not in managed.settings.engines and managed.settings.profiles == {}
    assert "GW_API_KEY" not in (tmp_path / "conf" / "keys.env").read_text()


# sign-ins (need a database)


async def test_claude_signs_in_with_the_code_pasted_in_the_app(managed):
    async with client(managed, host="192.168.0.20") as c:  # no key involved: fine over the home network
        assert (await managed.logins.check(managed.settings.engines["fake"]))["logged_in"] is False
        agents = {a["engine"]: a for a in (await c.get("/api/logins")).json()["agents"]}
        assert set(agents) == {"fake", "cx"} and agents["fake"]["logged_in"] is False

        flow = (await c.post("/api/logins/fake")).json()
        assert flow["state"] == "waiting" and flow["paste"] is True and flow["code"] is None
        assert flow["url"].startswith("https://claude.example.com/oauth/authorize")
        failed = (await c.post("/api/logins/fake/code", json={"code": "bad-code"})).json()
        assert failed["state"] == "failed" and "Invalid code" in failed["error"]
        assert (await c.post("/api/logins/fake/code", json={"code": "good-code"})).status_code == 422  # flow over

        await c.post("/api/logins/fake")
        done = (await c.post("/api/logins/fake/code", json={"code": "good-code"})).json()
        assert done["state"] == "done"
        agent = next(a for a in (await c.get("/api/logins")).json()["agents"] if a["engine"] == "fake")
        assert agent["logged_in"] is True and agent["account"] == "owner@example.com (max)"

        assert (await c.post("/api/logins/fake/logout")).json()["logged_in"] is False
        assert (await c.post("/api/logins/fake@nope")).status_code == 404


async def test_codex_signs_in_with_a_device_code(managed):
    async with client(managed) as c:
        flow = (await c.post("/api/logins/cx")).json()
        assert flow == {**flow, "state": "waiting", "paste": False, "code": "ABCD-EFG12",
                        "url": "https://auth.example.com/codex/device"}
        for _ in range(50):
            agent = next(a for a in (await c.get("/api/logins")).json()["agents"] if a["engine"] == "cx")
            if agent["flow"]["state"] == "done":
                break
            await asyncio.sleep(0.1)
        assert agent["flow"]["state"] == "done" and agent["logged_in"] is True


async def test_an_agents_default_profile_is_where_its_new_tasks_start(managed, gateway):
    async with client(managed) as c:
        await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "anthropic": gateway, "key": KEY})
        assert (await c.put("/api/logins/cx/default", json={"profile": "nope"})).status_code == 422
        agent = (await c.put("/api/logins/cx/default", json={"profile": "gw"})).json()
        assert agent["default"] == "gw" and agent["default_choices"] == ["gw"]
        assert "[defaults]" in managed.settings.profiles_file.read_text()
        preferred = {e["id"]: e["preferred"] for e in managed.engines()}
        assert preferred["cx@gw"] and not preferred["cx"]  # new Codex tasks start on the gateway
        assert preferred["fake"] and not preferred["fake@gw"]  # no default: the sign-in account
        assert "pick another default" in (await c.delete("/api/profiles/gw")).json()["detail"]

        # the agent's own engine is its sign-in account whatever the default: never a gateway in disguise
        await managed.logins.check(managed.settings.engines["cx"])
        assert next(e for e in managed.engines() if e["id"] == "cx")["problem"].startswith("Not signed in")
        assert await reply_to(managed, "cx", "env MODEL_PROVIDER") == "<unset>"
        await c.post("/api/logins/cx")
        for _ in range(50):
            if managed.logins.known("cx"):
                break
            await asyncio.sleep(0.1)
        assert managed.logins.known("cx") is True
        assert next(e for e in managed.engines() if e["id"] == "cx")["problem"] is None
        assert {e["id"]: e["preferred"] for e in managed.engines()}["cx@gw"]  # signing in changes nothing here
        assert await reply_to(managed, "cx", "env MODEL_PROVIDER") == "<unset>"

        assert (await c.put("/api/logins/cx/default", json={"profile": None})).json()["default"] is None
        assert "[defaults]" not in managed.settings.profiles_file.read_text()
