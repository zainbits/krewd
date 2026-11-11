"""Profiles: config, keys, Codex catalogs, agent environments, and (with a database) the hub and API."""

from __future__ import annotations

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
KEY = "k-test"

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
    script = write(tmp_path / "codex", f"#!/bin/sh\n[ \"$1 $2 $3\" = 'debug models --bundled' ] && exec cat {catalog}\nexit 2\n")
    script.chmod(0o755)
    return str(script)


def profile_engines(tmp_path, gateway: str, codex: str, key: str | None = KEY) -> dict[str, Engine]:
    keys = write(tmp_path / "keys.env", f"GW_KEY={key}\n" if key else "")
    profile = Profile(id="gw", name="Gateway", key="GW_KEY", key_file=keys, openai=f"{gateway}/v1", anthropic=gateway)
    engines = {
        "fake": Engine(id="fake", name="Fake", command=[sys.executable, str(FAKE)], gateway="claude"),
        "cx": Engine(id="cx", name="Codex", command=[sys.executable, str(FAKE)], gateway="codex",
                     env={"CODEX_PATH": codex, "CODEX_CONFIG": '{"approval_policy": "never"}'}),
    }
    return with_profiles(engines, {"gw": profile})


async def test_claude_engines_get_the_gateway_and_key(tmp_path, gateway, fake_codex):
    engine = profile_engines(tmp_path, gateway, fake_codex)["fake@gw"]
    env = await Gateways(port=8800).engine_env(engine)
    assert env == {"ANTHROPIC_BASE_URL": gateway, "ANTHROPIC_AUTH_TOKEN": KEY, "ANTHROPIC_API_KEY": "",
                   "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY": "1"}


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
