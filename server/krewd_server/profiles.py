"""Profiles: run an agent on a gateway (OpenRouter, a company proxy...) instead of its own subscription login.

The agent's own login needs no profile and stays the default. A profile only names a gateway and its key;
the models always come from the gateway, whose model list the key's own limits (an OpenRouter guardrail, say)
already narrow. So nothing here lists model names, and nothing needs editing when models change.

- Claude Code: `ANTHROPIC_BASE_URL` and the key; its own gateway model discovery lists the gateway's models whose
  ids contain "claude" or "anthropic" (OpenRouter names them all `anthropic/<id>[1m]` for it), from the second
  session of a process on.
- Codex (codex-acp): a model provider passed in `MODEL_PROVIDER` / `CODEX_CONFIG`, whose `model_catalog_url`
  points back at Krewd (`/api/codex-catalog/...`). Krewd builds that catalog from the gateway's model list, in
  Codex's own format, using Codex's bundled catalog as the template so new Codex versions bring their fields.
"""

from __future__ import annotations

import asyncio
import copy
import json
import os
import re
import secrets
import shutil
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .config import Engine, Profile

MODELS_TTL = 60.0  # seconds a gateway's model list is reused
HTTP_TIMEOUT = 15.0
REASONING_LEVELS = [
    {"effort": "low", "description": "Faster, lighter reasoning"},
    {"effort": "medium", "description": "Balanced speed and depth"},
    {"effort": "high", "description": "Deeper reasoning"},
]


class ProfileError(Exception):
    """A profile cannot be used right now (no key, gateway unreachable, no Codex to read a template from)."""


# Gateways the app offers ready-made: only the key is asked for. Anything else is a custom profile.
PRESETS = [
    {"id": "openrouter", "name": "OpenRouter", "key": "OPENROUTER_API_KEY",
     "openai": "https://openrouter.ai/api/v1", "anthropic": "https://openrouter.ai/api"},
]
PROFILES_HEADER = (
    "# Krewd profiles: gateways an agent can run on instead of its own login (the default).\n"
    "# Written by Krewd (app: Settings > Profiles); editing by hand works too, then restart Krewd.\n"
    "# One entry per gateway; the models come from the gateway. Keys are never here (see key_file, default keys.env).\n"
)


def _write_private(path: Path, text: str) -> None:
    """Replace a file atomically, readable only by the owner."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def write_profiles(path: Path, profiles: dict[str, Profile], agents: dict[str, str] | None = None) -> None:
    """Save profiles.toml. A key_file is written only when it is not the default keys.env beside the file."""
    def q(value: str) -> str:  # a TOML basic string; JSON's escapes are a subset of TOML's
        return json.dumps(value, ensure_ascii=False)

    parts = [PROFILES_HEADER]
    for profile in profiles.values():
        lines = [f"[profiles.{profile.id}]", f"name = {q(profile.name)}", f"key = {q(profile.key)}"]
        if profile.key_file != path.parent / "keys.env":
            home = str(Path.home())
            shown = str(profile.key_file)
            lines.append(f"key_file = {q('~' + shown[len(home):] if shown.startswith(home + os.sep) else shown)}")
        if profile.openai:
            lines.append(f"openai = {q(profile.openai)}")
        if profile.anthropic:
            lines.append(f"anthropic = {q(profile.anthropic)}")
        parts.append("\n".join(lines) + "\n")
    if agents:
        parts.append("# The profile each agent runs on (an agent not listed: its own sign-in account).\n[agents]\n"
                     + "".join(f"{agent} = {q(profile)}\n" for agent, profile in agents.items()))
    _write_private(path, "\n".join(parts))


def set_key(path: Path, name: str, value: str | None) -> None:
    """Set (or with None, remove) NAME=value in a KEY=value file, keeping its other lines and an `export` prefix."""
    lines = path.read_text().splitlines() if path.is_file() else []
    out, done = [], False
    for line in lines:
        bare = line.strip()
        prefix = "export " if bare.startswith("export ") else ""
        if bare[len(prefix):].partition("=")[0].strip() == name:
            if value is not None and not done:
                out.append(f"{prefix}{name}={value}")
            done = True
            continue
        out.append(line)
    if value is not None and not done:
        out.append(f"{name}={value}")
    _write_private(path, "\n".join(out) + ("\n" if out else ""))


def slug(name: str, taken: set[str]) -> str:
    """A profile id from its name: lowercase letters, digits and dashes, unique."""
    base = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:24] or "profile"
    if not base[0].isalnum():
        base = "p" + base
    candidate, n = base, 2
    while candidate in taken:
        candidate, n = f"{base}-{n}", n + 1
    return candidate


def check_gateway(openai: str | None, anthropic: str | None, key: str) -> dict[str, int]:
    """Ask each URL for the key's models before saving a profile. Raises ProfileError with what went wrong."""
    found = {}
    if openai:
        found["openai"] = len(fetch_models(openai, key))
    if anthropic:
        found["anthropic"] = len(claude_listed(fetch_models(f"{anthropic}/v1", key, anthropic=True)))
    return found


def valid_url(url: str) -> bool:
    return bool(re.match(r"^https?://[^\s/]+(/\S*)?$", url))


def fetch_models(url: str, key: str, anthropic: bool = False, timeout: float = HTTP_TIMEOUT) -> list[dict[str, Any]]:
    """GET <url>/models. OpenRouter answers with only what the key may use.

    `anthropic` asks the way Claude Code's discovery does; OpenRouter then names every model `anthropic/<id>[1m]`
    so Claude Code keeps them all, which is not what Codex needs.
    """
    headers = {"Authorization": f"Bearer {key}"}
    if anthropic:
        headers["anthropic-version"] = "2023-06-01"
    request = urllib.request.Request(f"{url}/models", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read())
    except urllib.error.HTTPError as exc:
        raise ProfileError(f"{url}/models answered HTTP {exc.code}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise ProfileError(f"cannot reach {url}: {getattr(exc, 'reason', exc)}") from None
    except ValueError:
        raise ProfileError(f"{url}/models did not answer JSON") from None
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, list):
        raise ProfileError(f"{url}/models has no data list")
    return [m for m in data if isinstance(m, dict) and isinstance(m.get("id"), str) and m["id"]]


def _generic_template(bundled: dict[str, Any]) -> dict[str, Any]:
    """A plain entry from Codex's catalog for models Codex does not know: ordinary tools, no OpenAI-only extras."""
    listed = [m for m in bundled.get("models", []) if m.get("visibility") == "list"] or list(bundled.get("models", []))
    if not listed:
        raise ProfileError("Codex's bundled model catalog is empty")
    plain = [m for m in listed if not m.get("tool_mode") and not m.get("use_responses_lite")]
    template = copy.deepcopy((plain or listed)[-1])
    for name, value in (("availability_nux", None), ("upgrade", None), ("prefer_websockets", False),
                        ("use_responses_lite", False), ("tool_mode", None)):
        if name in template:
            template[name] = value
    return template


def codex_catalog(models: list[dict[str, Any]], bundled: dict[str, Any]) -> dict[str, Any]:
    """Codex's model catalog for a gateway.

    A gateway that uses Codex's own model names (an OpenAI proxy lists hundreds, speech and images too) gets the
    Codex models it serves, unchanged. Any other gateway gets its whole list, described from the gateway's own
    metadata (context length, image input, reasoning) on a plain template.
    """
    known = {m["slug"]: m for m in bundled.get("models", []) if m.get("slug")}
    ids = list(dict.fromkeys(m["id"] for m in models))
    exact = [i for i in ids if i in known]
    if exact:
        return {"models": [copy.deepcopy(known[i]) for i in sorted(exact, key=lambda i: known[i].get("priority", 1 << 30))]}
    template = _generic_template(bundled)
    by_id = {m["id"]: m for m in models}
    out = []
    for i, model_id in enumerate(ids):
        meta = by_id[model_id]
        entry = copy.deepcopy(template)
        entry["slug"] = model_id
        entry["display_name"] = meta.get("name") or model_id
        entry["description"] = (meta.get("description") or "").strip().split("\n")[0][:200]
        if isinstance(meta.get("context_length"), int) and meta["context_length"] > 0:
            window = int(meta["context_length"] * 0.95)  # headroom for the reply
            entry["context_window"] = window
            if "max_context_window" in entry:
                entry["max_context_window"] = window
        inputs = (meta.get("architecture") or {}).get("input_modalities")
        if isinstance(inputs, list):
            entry["input_modalities"] = [x for x in ("text", "image") if x in inputs] or ["text"]
        params = meta.get("supported_parameters")
        reasons = not isinstance(params, list) or "reasoning" in params
        entry["supported_reasoning_levels"] = copy.deepcopy(REASONING_LEVELS) if reasons else []
        entry["default_reasoning_level"] = "medium" if reasons else None
        if isinstance(params, list):
            entry["supports_parallel_tool_calls"] = "parallel_tool_calls" in params
        entry["priority"] = i + 1
        entry["visibility"] = "list"
        out.append(entry)
    return {"models": out}


def claude_listed(models: list[dict[str, Any]]) -> list[str]:
    """What Claude Code's gateway discovery keeps: ids with "claude" or "anthropic" in them."""
    return [m["id"] for m in models if "claude" in m["id"].lower() or "anthropic" in m["id"].lower()]


def claude_defaults(listed: list[str]) -> dict[str, str]:
    """The models a Claude profile starts on, as environment variables.

    A gateway that uses Claude Code's own model names (`claude-...`, an Anthropic proxy) keeps Claude Code's own
    default: such a gateway may list models it then refuses. Any other gateway (OpenRouter, whose list is what the
    key may use) starts on its first model, since Claude Code's default would be blocked; its background model
    (Haiku by default, for titles and summaries) too, unless the gateway offers a Haiku.
    """
    if not listed or any(m.startswith("claude-") for m in listed):
        return {}
    env = {"ANTHROPIC_MODEL": listed[0]}
    if not any("haiku" in m.lower() for m in listed):
        env["ANTHROPIC_SMALL_FAST_MODEL"] = listed[0]
    return env


def default_model(catalog: dict[str, Any]) -> str | None:
    listed = [m for m in catalog.get("models", []) if m.get("visibility", "list") == "list"]
    return listed[0]["slug"] if listed else None


def codex_path(engine: Engine) -> str | None:
    """The Codex the engine runs: CODEX_PATH (codex-acp runs it too), else `codex` on the engine's PATH."""
    path = engine.env.get("CODEX_PATH")
    if path:
        path = os.path.expanduser(path)
        return path if os.access(path, os.X_OK) else None
    return shutil.which("codex", path=engine.env.get("PATH") or os.environ.get("PATH"))


class Gateways:
    """Model lists, Codex catalogs and agent environments for profile engines."""

    def __init__(self, port: int) -> None:
        self.port = port
        # Codex fetches its catalog from Krewd without Krewd's token; this unguessable path segment stands in
        # for it (Tailscale Serve forwards to 127.0.0.1 too, so the caller's address proves nothing).
        self.secret = secrets.token_urlsafe(18)
        # (profile, URL, Anthropic form) -> (when, models): OpenRouter answers the same URL in two forms.
        self._models: dict[tuple[str, str, bool], tuple[float, list[dict[str, Any]]]] = {}
        self._bundled: dict[tuple[str, float], dict[str, Any]] = {}

    def forget(self, profile_id: str) -> None:
        """Drop a profile's cached model lists (after its key or URLs change)."""
        for cache_key in [k for k in self._models if k[0] == profile_id]:
            del self._models[cache_key]

    def catalog_url(self, engine_id: str) -> str:
        return f"http://127.0.0.1:{self.port}/api/codex-catalog/{self.secret}/{engine_id}"

    def key(self, profile: Profile) -> str:
        key = profile.read_key()
        if not key:
            raise ProfileError(f"no key for {profile.name}: set {profile.key} in {profile.key_file}")
        return key

    async def models(self, engine: Engine, profile: Profile) -> list[dict[str, Any]]:
        url = profile.url_for(engine.gateway)
        if not url:
            raise ProfileError(f"{profile.name} has no URL for {engine.gateway}")
        cached = self._models.get((profile.id, url, False))
        if cached and time.monotonic() - cached[0] < MODELS_TTL:
            return cached[1]
        models = await asyncio.to_thread(fetch_models, url, self.key(profile))
        self._models[(profile.id, url, False)] = (time.monotonic(), models)
        return models

    async def bundled(self, engine: Engine) -> dict[str, Any]:
        """Codex's own catalog (`codex debug models --bundled`), read again when the Codex binary changes."""
        path = codex_path(engine)
        if not path:
            raise ProfileError("Codex not found: set CODEX_PATH in the engine's env (engines.toml)")
        cache_key = (path, os.stat(path).st_mtime)
        if cache_key not in self._bundled:
            env = {**os.environ, **engine.env}
            # npm's `codex` is a Node script; its own folder has that Node (nvm, global npm bins).
            env["PATH"] = os.pathsep.join([os.path.dirname(path), env.get("PATH", "")])
            proc = await asyncio.create_subprocess_exec(
                path, "debug", "models", "--bundled",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=env,
            )
            out, err = await asyncio.wait_for(proc.communicate(), timeout=60)
            try:
                catalog = json.loads(out)
            except ValueError:
                raise ProfileError(f"`codex debug models --bundled` failed: {err.decode(errors='replace')[:200]}") from None
            self._bundled = {cache_key: catalog}
        return self._bundled[cache_key]

    async def codex_catalog(self, engine: Engine, profile: Profile) -> dict[str, Any]:
        return codex_catalog(await self.models(engine, profile), await self.bundled(engine))

    async def claude_models(self, engine: Engine, profile: Profile) -> list[str]:
        """The model ids Claude Code's gateway discovery will list for this key, in the gateway's order."""
        url = f"{profile.url_for(engine.gateway)}/v1"
        cached = self._models.get((profile.id, url, True))
        if cached and time.monotonic() - cached[0] < MODELS_TTL:
            models = cached[1]
        else:
            models = await asyncio.to_thread(fetch_models, url, self.key(profile), True)
            self._models[(profile.id, url, True)] = (time.monotonic(), models)
        return claude_listed(models)

    async def engine_env(self, engine: Engine, profile: Profile) -> dict[str, str]:
        """Environment that points the engine at a profile's gateway (added to the engine's own env)."""
        key = self.key(profile)
        url = profile.url_for(engine.gateway)
        if engine.gateway == "claude":
            env = {
                "ANTHROPIC_BASE_URL": url or "",
                "ANTHROPIC_AUTH_TOKEN": key,
                "ANTHROPIC_API_KEY": "",
                "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY": "1",
            }
            try:
                models = await self.claude_models(engine, profile)
            except ProfileError:  # the gateway may not list models; Claude Code's own aliases still apply
                models = []
            return env | claude_defaults(models)
        if engine.gateway == "codex":
            catalog = await self.codex_catalog(engine, profile)
            if not catalog["models"]:
                raise ProfileError(f"{profile.name} offers no models to this key")
            provider = f"krewd-{profile.id}"
            config = json.loads(engine.env.get("CODEX_CONFIG") or "{}")
            config.setdefault("model_providers", {})[provider] = {
                "name": profile.name,
                "base_url": url,
                "env_key": profile.key,
                "wire_api": "responses",
                "model_catalog_url": self.catalog_url(engine.id),
            }
            model = default_model(catalog)
            if model:
                config["model"] = model
            return {profile.key: key, "MODEL_PROVIDER": provider, "CODEX_CONFIG": json.dumps(config)}
        raise ProfileError(f"engine {engine.id} cannot use gateways")

    def problem(self, profile: Profile) -> str | None:
        """Why a profile engine cannot start, as far as Krewd can tell without starting it."""
        if not profile.read_key():
            return f"No key for {profile.name}: set it under Settings > Profiles"
        return None


async def check(engines: dict[str, Engine], profiles: dict[str, Profile],
                agent_profiles: dict[str, str]) -> list[tuple[str, str, str]]:
    """(status, label, detail) for each agent (what it runs on, its sign-in) and each profile it could use."""
    from .logins import Logins

    gateways = Gateways(port=0)
    logins = Logins()
    rows = []
    for engine in engines.values():
        if not engine.gateway:
            continue
        status = await logins.check(engine)
        signed = "signed in" + (f" ({status['account']})" if status["account"] else "") if status["logged_in"] else "not signed in"
        profile = profiles.get(agent_profiles.get(engine.id, ""))
        if profile and profile.url_for(engine.gateway):
            rows.append(("ok", engine.id, f"runs on {profile.name}; sign-in account: {signed}"))
        elif status["logged_in"]:
            rows.append(("ok", engine.id, f"runs on its sign-in account, {signed}"))
        else:
            rows.append(("warn", engine.id, "runs on its sign-in account, which is not signed in: sign in or pick a profile (app: Settings > Agents)"))
    for profile in profiles.values():
        for engine in engines.values():
            if not engine.gateway or not profile.url_for(engine.gateway):
                continue
            label = f"{profile.id} for {engine.id}"
            try:
                gateways.key(profile)
                if engine.gateway == "codex":
                    catalog = await gateways.codex_catalog(engine, profile)
                    names = [m["slug"] for m in catalog["models"] if m.get("visibility", "list") == "list"]
                    if not names:
                        raise ProfileError("the gateway offers no models to this key")
                    rows.append(("ok", label, f"{len(names)} models: {', '.join(names[:4])}{' ...' if len(names) > 4 else ''}"))
                else:
                    kept = await gateways.claude_models(engine, profile)
                    if kept:
                        rows.append(("ok", label, f"{len(kept)} models: {', '.join(kept[:4])}{' ...' if len(kept) > 4 else ''}"))
                    else:
                        rows.append(("warn", label, "no models Claude Code can list for this key (it shows only its aliases)"))
            except ProfileError as exc:
                rows.append(("bad", label, str(exc)))
    return rows


def main() -> None:
    """`python -m krewd_server.profiles check`: one tab-separated line per profile engine, for doctor."""
    from pathlib import Path

    from .config import CONFIG_DIR, load_agent_profiles, load_engines, load_env_file, load_profiles

    load_env_file()
    engines, _ = load_engines(Path(os.environ.get("KREWD_ENGINES_FILE", CONFIG_DIR / "engines.toml")))
    path = Path(os.environ.get("KREWD_PROFILES_FILE", CONFIG_DIR / "profiles.toml"))
    profiles = load_profiles(path)
    if len(sys.argv) < 2 or sys.argv[1] != "check":
        sys.exit("usage: python -m krewd_server.profiles check")
    for status, engine_id, detail in asyncio.run(check(engines, profiles, load_agent_profiles(path))):
        print(status, engine_id, detail, sep="\t")


if __name__ == "__main__":
    main()
