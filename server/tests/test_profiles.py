"""Profiles and agents: config, keys, Codex catalogs, agent environments, and (with a database) the hub and API."""

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

from krewd_server import db
from krewd_server.api import create_app
from krewd_server.config import Engine, Profile, Settings, load_agent_profiles, load_engines, load_env_file, load_profiles
from krewd_server.hub import Hub
from krewd_server.profiles import Gateways, ProfileError, codex_catalog, default_model

load_env_file()
DATABASE_URL = os.environ.get("KREWD_TEST_DATABASE_URL")
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


def test_profiles_and_the_agents_table_load(tmp_path):
    path = write(tmp_path / "profiles.toml", """
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
[agents]
codex = "router"
claude = ""
""")
    profiles = load_profiles(path)
    assert profiles["router"].openai == "https://router.example/api/v1"  # trailing slash dropped
    assert profiles["router"].key_file == tmp_path / "keys.env"
    assert profiles["proxy"].key_file == Path.home() / "proxy.env"
    assert profiles["proxy"].url_for("claude") is None and profiles["router"].url_for("claude") == "https://router.example/api"
    assert load_agent_profiles(path) == {"codex": "router"}  # empty: the sign-in account


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


def setup(tmp_path, gateway: str, codex: str, claude: str | None = None, key: str | None = KEY):
    """Two agents (a Claude-style and a Codex-style fake) and one gateway profile."""
    keys = write(tmp_path / "keys.env", f"GW_KEY={key}\n" if key else "")
    profile = Profile(id="gw", name="Gateway", key="GW_KEY", key_file=keys, openai=f"{gateway}/v1", anthropic=gateway)
    engines = {
        "fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)], gateway="claude",
                       env={"CLAUDE_PATH": claude or str(tmp_path / "no-claude")}),  # never the machine's real claude
        "cx": Engine(id="cx", name="Codex", command=[sys.executable, str(FAKE)], gateway="codex",
                     env={"CODEX_PATH": codex, "CODEX_CONFIG": '{"approval_policy": "never"}'}),
    }
    return engines, profile


async def test_claude_gets_the_gateway_key_and_a_model_the_key_may_use(tmp_path, gateway, fake_codex):
    engines, profile = setup(tmp_path, gateway, fake_codex)
    env = await Gateways(port=8800).engine_env(engines["fake"], profile)
    first = "anthropic/vendor/reasoner[1m]"  # the gateway's first model, as Claude Code's discovery sees it
    assert env == {"ANTHROPIC_BASE_URL": gateway, "ANTHROPIC_AUTH_TOKEN": KEY, "ANTHROPIC_API_KEY": "",
                   "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY": "1", "ANTHROPIC_MODEL": first,
                   "ANTHROPIC_SMALL_FAST_MODEL": first}


def test_claude_profiles_start_on_a_listed_model_only_off_claude_names():
    from krewd_server.profiles import claude_defaults

    # an Anthropic-style proxy (Claude Code's own names) may list models it refuses: Claude Code's default stays
    assert claude_defaults(["claude-fable-9", "claude-haiku-9"]) == {}
    assert claude_defaults([]) == {}
    # OpenRouter-style ids: the first listed model, and the background model when there is no Haiku
    assert claude_defaults(["anthropic/claude-sonnet-9[1m]", "anthropic/claude-haiku-9[1m]"]) == {
        "ANTHROPIC_MODEL": "anthropic/claude-sonnet-9[1m]"}
    assert claude_defaults(["anthropic/glm[1m]"]) == {"ANTHROPIC_MODEL": "anthropic/glm[1m]",
                                                       "ANTHROPIC_SMALL_FAST_MODEL": "anthropic/glm[1m]"}


def test_claude_lists_what_its_discovery_keeps():
    from krewd_server.profiles import claude_listed

    models = [{"id": "claude-haiku-9"}, {"id": "gpt-x"}, {"id": "anthropic/glm[1m]"}]
    assert claude_listed(models) == ["claude-haiku-9", "anthropic/glm[1m]"]


async def test_codex_gets_a_provider_with_a_catalog_from_krewd(tmp_path, gateway, fake_codex):
    engines, profile = setup(tmp_path, gateway, fake_codex)
    gateways = Gateways(port=8800)
    env = await gateways.engine_env(engines["cx"], profile)
    assert env["GW_KEY"] == KEY and env["MODEL_PROVIDER"] == "krewd-gw"
    config = json.loads(env["CODEX_CONFIG"])
    assert config["approval_policy"] == "never"  # the engine's own CODEX_CONFIG is kept
    assert config["model"] == "vendor/reasoner"
    assert config["model_providers"]["krewd-gw"] == {
        "name": "Gateway", "base_url": f"{gateway}/v1", "env_key": "GW_KEY", "wire_api": "responses",
        "model_catalog_url": f"http://127.0.0.1:8800/api/codex-catalog/{gateways.secret}/cx",
    }
    assert [m["slug"] for m in (await gateways.codex_catalog(engines["cx"], profile))["models"]] == ["vendor/reasoner", "vendor/plain"]
    # the Anthropic form of the same list (for Claude Code) never leaks into Codex's catalog
    await gateways.claude_models(engines["fake"], profile)
    assert [m["slug"] for m in (await gateways.codex_catalog(engines["cx"], profile))["models"]] == ["vendor/reasoner", "vendor/plain"]


async def test_missing_key_or_wrong_key_is_a_clear_error(tmp_path, gateway, fake_codex):
    engines, profile = setup(tmp_path, gateway, fake_codex, key=None)
    gateways = Gateways(port=8800)
    assert "No key" in gateways.problem(profile)
    with pytest.raises(ProfileError, match="no key for Gateway"):
        await gateways.engine_env(engines["cx"], profile)
    engines, profile = setup(tmp_path, gateway, fake_codex, key="wrong")
    assert gateways.problem(profile) is None
    with pytest.raises(ProfileError, match="HTTP 401"):
        await gateways.engine_env(engines["cx"], profile)


# the hub and API (need a database)


@pytest.fixture
async def managed(tmp_path, gateway, fake_codex, fake_claude):
    if not DATABASE_URL:
        pytest.skip("KREWD_TEST_DATABASE_URL is not set")
    pool = await db.connect(DATABASE_URL)
    await pool.execute(
        "drop table if exists session_agents, routine_triggers, routine_runs, routine_versions, routines, messages, events, approvals, sessions,"
        " engines, devices, app_prefs, schema_version cascade"
    )
    engines, _ = setup(tmp_path, gateway, fake_codex, claude=fake_claude)
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


def client(hub: Hub, host: str = "127.0.0.1", https: bool = False) -> httpx.AsyncClient:
    app = create_app(hub, "test-token", lifespan_hub=False)
    if https:
        from krewd_server.__main__ import tls_marked
        app = tls_marked(app)
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app, client=(host, 5000)), base_url="http://test",
                             headers={"Authorization": "Bearer test-token"})


async def reply_to(hub: Hub, engine: str, text: str, session: dict | None = None) -> tuple[str, dict]:
    """Send text in a new chat (or a given one); return the agent's reply and the chat."""
    if session is None:
        session, after = await hub.create_session(text, engine), 0
    else:
        after = (await hub.events(0, uuid.UUID(session["id"])))[-1]["seq"]
        await hub.send(uuid.UUID(session["id"]), text)
    sid = uuid.UUID(session["id"])
    for _ in range(400):
        events = await hub.events(after, sid)
        if any(e["kind"] == "turn_end" for e in events):
            texts = [e["payload"]["text"] for e in events if e["kind"] == "text" and e["payload"].get("role") == "assistant"]
            return "".join(texts), session
        await asyncio.sleep(0.05)
    raise AssertionError("no reply")


async def add_gw(c: httpx.AsyncClient, gateway: str) -> dict:
    made = await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "anthropic": gateway, "key": KEY})
    assert made.status_code == 201, made.text
    return made.json()


async def test_an_agent_runs_on_the_profile_picked_for_it_and_chats_follow(managed, gateway):
    async with client(managed) as c:
        await add_gw(c, gateway)
        assert [e["id"] for e in managed.engines()] == ["fake", "cx"]  # no extra engines per profile
        agent = (await c.put("/api/agents/fake/profile", json={"profile": "gw"})).json()
        assert agent["profile"] == "gw" and agent["profile_choices"] == ["gw"]
        assert "[agents]" in managed.settings.profiles_file.read_text()
        assert next(e for e in managed.engines() if e["id"] == "fake")["profile"] == "gw"

        reply, chat = await reply_to(managed, "fake", "env ANTHROPIC_BASE_URL")
        assert reply == gateway
        # the same chat follows the agent to its sign-in account: the chat itself knows nothing of profiles
        assert (await c.put("/api/agents/fake/profile", json={"profile": None})).json()["profile"] is None
        reply, _ = await reply_to(managed, "fake", "env ANTHROPIC_BASE_URL", session=chat)
        assert reply == os.environ.get("ANTHROPIC_BASE_URL", "<unset>")

        await c.put("/api/agents/cx/profile", json={"profile": "gw"})
        assert (await reply_to(managed, "cx", "env MODEL_PROVIDER"))[0] == "krewd-gw"
        assert (await c.put("/api/agents/cx/profile", json={"profile": "nope"})).status_code == 422

        # model lists are asked again whenever they were listed on another profile, also after a restart
        assert managed.listed_on["cx"] == "gw"
        managed.listed_on.clear()
        managed.engines()
        assert "cx" in managed.probing or managed.listed_on.get("cx") == "gw"


async def test_codex_catalog_route_needs_the_secret_and_a_profile(managed, gateway):
    async with client(managed) as c:
        await add_gw(c, gateway)
        secret = managed.gateways.secret
        assert (await c.get(f"/api/codex-catalog/{secret}/cx")).status_code == 404  # on its sign-in account
        await c.put("/api/agents/cx/profile", json={"profile": "gw"})
        assert (await c.get("/api/codex-catalog/wrong/cx")).status_code == 404
        assert (await c.get(f"/api/codex-catalog/{secret}/fake")).status_code == 404  # not Codex
        ok = await c.get(f"/api/codex-catalog/{secret}/cx", params={"client_version": "1"})
        assert ok.status_code == 200 and [m["slug"] for m in ok.json()["models"]] == ["vendor/reasoner", "vendor/plain"]


async def test_profiles_are_added_from_the_app_and_keys_stay_on_the_server(managed, gateway, tmp_path):
    conf = tmp_path / "conf"
    async with client(managed) as c:
        listing = (await c.get("/api/profiles")).json()
        assert listing["profiles"] == [] and listing["managed"] is True
        assert [p["id"] for p in listing["presets"]] == ["openrouter"] and "key" not in listing["presets"][0]
        made = await c.post("/api/profiles", json={"name": "My Gateway", "openai": f"{gateway}/v1/", "anthropic": gateway, "key": KEY})
        assert made.status_code == 201, made.text
        profile = made.json()
        assert profile["id"] == "my-gateway" and profile["openai"] == f"{gateway}/v1"
        assert profile["key_set"] is True and profile["key_hint"] == KEY[-4:] and profile["agents"] == []
        assert KEY not in (await c.get("/api/profiles")).text  # never sent back
    toml = (conf / "profiles.toml").read_text()
    assert "[profiles.my-gateway]" in toml and KEY not in toml and 'key = "MY_GATEWAY_API_KEY"' in toml
    assert (conf / "keys.env").read_text() == f"MY_GATEWAY_API_KEY={KEY}\n"
    assert (conf / "keys.env").stat().st_mode & 0o777 == 0o600
    assert load_profiles(conf / "profiles.toml")["my-gateway"].read_key() == KEY


async def test_bad_keys_and_keys_over_plain_wifi_are_refused(managed, gateway):
    async with client(managed) as c:
        wrong = await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "key": "wrong-key-123456"})
        assert wrong.status_code == 422 and "HTTP 401" in wrong.json()["detail"]
        assert (await c.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1"})).status_code == 422  # no key
        assert (await c.post("/api/profiles", json={"name": "GW", "key": KEY})).status_code == 422  # no address
        assert (await c.post("/api/profiles", json={"name": "GW", "openai": "ftp://x", "key": KEY})).status_code == 422
    async with client(managed, host="192.168.0.20") as lan:
        refused = await lan.post("/api/profiles", json={"name": "GW", "openai": f"{gateway}/v1", "key": KEY})
        assert refused.status_code == 403 and "HTTPS" in refused.json()["detail"]
    assert managed.settings.profiles == {}
    async with client(managed, host="192.168.0.20", https=True) as lan:  # Krewd's own HTTPS: accepted
        await add_gw(lan, gateway)
    assert "gw" in managed.settings.profiles


async def test_profiles_in_use_by_an_agent_are_kept(managed, gateway, tmp_path):
    async with client(managed) as c:
        await add_gw(c, gateway)
    async with client(managed, host="192.168.0.20") as lan:  # no key in it: fine over the home network
        renamed = await lan.patch("/api/profiles/gw", json={"name": "Gateway Two"})
        assert renamed.status_code == 200 and renamed.json()["name"] == "Gateway Two"
        await lan.put("/api/agents/cx/profile", json={"profile": "gw"})
        assert (await lan.get("/api/profiles")).json()["profiles"][0]["agents"] == ["cx"]
        blocked = await lan.delete("/api/profiles/gw")
        assert blocked.status_code == 409 and "Codex" in blocked.json()["detail"]
        assert (await lan.patch("/api/profiles/gw", json={"openai": ""})).status_code == 409  # Codex needs it
        assert (await lan.patch("/api/profiles/gw", json={"anthropic": ""})).status_code == 200  # nobody needs it
        await lan.put("/api/agents/cx/profile", json={"profile": None})
        assert (await lan.delete("/api/profiles/gw")).status_code == 200
        assert (await lan.delete("/api/profiles/gw")).status_code == 404
    assert managed.settings.profiles == {}
    assert "GW_API_KEY" not in (tmp_path / "conf" / "keys.env").read_text()


async def test_the_sign_in_account_is_one_choice_among_the_profiles(managed, gateway):
    async with client(managed) as c:
        await add_gw(c, gateway)
        await c.put("/api/agents/cx/profile", json={"profile": "gw"})
        await managed.logins.check(managed.settings.engines["cx"])
        assert next(e for e in managed.engines() if e["id"] == "cx")["problem"] is None  # on a profile: fine
        await c.put("/api/agents/cx/profile", json={"profile": None})
        assert next(e for e in managed.engines() if e["id"] == "cx")["problem"].startswith("Not signed in")
        await c.put("/api/agents/cx/profile", json={"profile": "gw"})
        await c.post("/api/agents/cx/login")
        for _ in range(50):
            if managed.logins.known("cx"):
                break
            await asyncio.sleep(0.1)
        assert managed.logins.known("cx") is True
        # signing in changes nothing about what the agent runs on
        assert (await c.get("/api/agents")).json()["agents"][1]["profile"] == "gw"
        assert (await reply_to(managed, "cx", "env MODEL_PROVIDER"))[0] == "krewd-gw"


# sign-ins (need a database)


async def test_claude_signs_in_with_the_code_pasted_in_the_app(managed):
    async with client(managed, host="192.168.0.20") as c:  # no key involved: fine over the home network
        assert (await managed.logins.check(managed.settings.engines["fake"]))["logged_in"] is False
        agents = {a["engine"]: a for a in (await c.get("/api/agents")).json()["agents"]}
        assert set(agents) == {"fake", "cx"} and agents["fake"]["logged_in"] is False

        flow = (await c.post("/api/agents/fake/login")).json()
        assert flow["state"] == "waiting" and flow["paste"] is True and flow["code"] is None
        assert flow["url"].startswith("https://claude.example.com/oauth/authorize")
        failed = (await c.post("/api/agents/fake/login/code", json={"code": "bad-code"})).json()
        assert failed["state"] == "failed" and "Invalid code" in failed["error"]
        assert (await c.post("/api/agents/fake/login/code", json={"code": "good-code"})).status_code == 422  # flow over

        await c.post("/api/agents/fake/login")
        done = (await c.post("/api/agents/fake/login/code", json={"code": "good-code"})).json()
        assert done["state"] == "done"
        agent = next(a for a in (await c.get("/api/agents")).json()["agents"] if a["engine"] == "fake")
        assert agent["logged_in"] is True and agent["account"] == "owner@example.com (max)"

        assert (await c.post("/api/agents/fake/logout")).json()["logged_in"] is False
        assert (await c.post("/api/agents/nope/login")).status_code == 404


async def test_codex_signs_in_with_a_device_code(managed):
    async with client(managed) as c:
        flow = (await c.post("/api/agents/cx/login")).json()
        assert flow == {**flow, "state": "waiting", "paste": False, "code": "ABCD-EFG12",
                        "url": "https://auth.example.com/codex/device"}
        for _ in range(50):
            agent = next(a for a in (await c.get("/api/agents")).json()["agents"] if a["engine"] == "cx")
            if agent["flow"]["state"] == "done":
                break
            await asyncio.sleep(0.1)
        assert agent["flow"]["state"] == "done" and agent["logged_in"] is True




async def test_chats_take_the_new_model_list_when_their_agent_switches_gateway(managed, gateway):
    """A chat keeps its agent's options; after a switch of profile they are the new gateway's, also without a turn."""
    async with client(managed) as c:
        await add_gw(c, gateway)
        _, chat = await reply_to(managed, "fake", "hello")
        sid = uuid.UUID(chat["id"])
        await managed.update_session(sid, {"config": {"model": "big", "effort": "high"}})
        models = lambda s: [o for o in s["config_options"] if o["id"] == "model"][0]  # noqa: E731
        assert [ch["value"] for ch in models(await managed.get_session(sid))["options"]] == ["small", "big"]

        await c.put("/api/agents/fake/profile", json={"profile": "gw"})
        for _ in range(200):  # the app reads the engines again until the agent has listed its new models
            engine = next(e for e in managed.engines() if e["id"] == "fake")
            if engine["config_options"]:
                break
            await asyncio.sleep(0.05)
        session = await managed.get_session(sid)
        assert [ch["value"] for ch in models(session)["options"]] == ["gw-small", "gw-big"]
        assert models(session)["current"] == "gw-small"
        # a model the new gateway does not have is not asked for again; the other pick stays
        assert session["config"] == {"effort": "high"}
        assert [o["current"] for o in session["config_options"] if o["id"] == "effort"] == ["high"]

        # the chat continues on the gateway, its picker still the gateway's
        reply, _ = await reply_to(managed, "fake", "env ANTHROPIC_BASE_URL", session=chat)
        assert reply == gateway
        session = await managed.get_session(sid)
        assert [ch["value"] for ch in models(session)["options"]] == ["gw-small", "gw-big"]
