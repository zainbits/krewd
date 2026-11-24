"""Settings from the environment and ~/.config/krewd/: database URL, token, engines, profiles."""

from __future__ import annotations

import os
import re
import secrets
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("KREWD_CONFIG_DIR", Path.home() / ".config" / "krewd"))
ENV_FILE = CONFIG_DIR / "krewd.env"

# 24 lowercase base32 characters (120 bits), grouped by 4 so it can be typed on a phone.
TOKEN_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"


# How an engine is pointed at a gateway: "codex" (OpenAI Responses API, Codex settings) or "claude"
# (Anthropic Messages API, Claude Code settings). Engines without one only run on their own login.
GATEWAYS = ("codex", "claude")
PROFILE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,30}$")


@dataclass(frozen=True)
class Profile:
    """A gateway (OpenRouter, a company proxy...) an agent can use instead of its own subscription login.

    It names the gateway and where its key is, nothing more: the models come from the gateway itself.
    """

    id: str
    name: str
    key: str  # the environment variable that holds the gateway key
    key_file: Path  # where that variable is set (KEY=value lines); the process environment is the fallback
    openai: str | None = None  # Responses API base URL, for engines with gateway = "codex"
    anthropic: str | None = None  # Messages API base URL, for engines with gateway = "claude"

    def url_for(self, gateway: str | None) -> str | None:
        return {"codex": self.openai, "claude": self.anthropic}.get(gateway or "")

    def read_key(self) -> str | None:
        if self.key_file.is_file():
            for line in self.key_file.read_text().splitlines():
                line = line.strip()
                if line.startswith("export "):
                    line = line[len("export "):].lstrip()
                name, sep, value = line.partition("=")
                if sep and name.strip() == self.key:
                    return value.strip().strip("'\"") or None
        return os.environ.get(self.key) or None


@dataclass(frozen=True)
class Engine:
    id: str
    name: str
    command: list[str]
    env: dict[str, str] = field(default_factory=dict)
    gateway: str | None = None  # see GATEWAYS


@dataclass(frozen=True)
class VoiceSettings:
    """Voice input (see voice.py). Without a transcription URL, voice input is off and the app says why."""

    stt_url: str = ""  # an OpenAI-compatible API base, e.g. http://127.0.0.1:8780/v1 (POST {stt_url}/audio/transcriptions)
    stt_model: str = ""
    stt_fields: tuple[tuple[str, str], ...] = ()  # extra form fields for that API, e.g. store=false
    models_dir: Path = Path.home() / ".krewd" / "voice"  # the speaker model (`python -m krewd_server.voice fetch`)
    voiceprint_file: Path = CONFIG_DIR / "voiceprint.json"


@dataclass(frozen=True)
class Settings:
    database_url: str
    host: str
    port: int
    workdir: Path
    token_file: Path
    state_dir: Path
    engines: dict[str, Engine]
    default_engine: str
    profiles: dict[str, Profile] = field(default_factory=dict)
    profiles_file: Path | None = None  # where the app's profile changes are saved; None: not managed from the app
    # Agent -> the profile it runs on; an agent not listed runs on its own sign-in account.
    agent_profiles: dict[str, str] = field(default_factory=dict)
    cdp_port: int = 9222
    # KREWD_BROWSER_LOCATION ("LAT,LON[,ACCURACY_M]"): the agents' Brave location until one is applied in the app.
    browser_location: str = ""
    data_dir: Path = Path.home() / ".krewd"
    max_upload_mb: int = 1024
    share_dirs: tuple[Path, ...] = ()  # folders the agents hand files back from; empty means workdir/outputs
    # HTTPS on every non-loopback address with Krewd's own certificate ("auto"), or plain HTTP everywhere ("off").
    tls: str = "auto"
    tls_cert: Path = CONFIG_DIR / "tls.crt"
    tls_key: Path = CONFIG_DIR / "tls.key"
    mdns: bool = True  # announce the HTTPS address on the local network, so the app finds it by itself
    # Other addresses the app should know (a Tailscale or public HTTPS name), for the connect link.
    urls: tuple[str, ...] = ()
    # Phone passkeys as owner keys: the relying party id (a public domain whose /.well-known/assetlinks.json names
    # the app) and the SHA-256 of the app's signing certificate(s). No rp id: passkeys are off.
    passkey_rp_id: str | None = None
    passkey_certs: tuple[str, ...] = ()
    # Where the agents keep their session transcripts (storage report and cleanup). None: their defaults.
    claude_dir: Path | None = None
    codex_dir: Path | None = None
    voice: "VoiceSettings" = field(default_factory=lambda: VoiceSettings())

    @property
    def shared(self) -> list[Path]:
        return list(self.share_dirs) or [self.workdir / "outputs"]


def load_env_file(path: Path = ENV_FILE) -> None:
    """Load KEY=VALUE lines into os.environ without overriding values already set."""
    if not path.is_file():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def _default_engines() -> dict[str, Engine]:
    return {
        "claude": Engine(id="claude", name="Claude Code", command=["claude-agent-acp"]),
    }


def load_engines(path: Path) -> tuple[dict[str, Engine], str]:
    if not path.is_file():
        engines = _default_engines()
        return engines, "claude"
    data = tomllib.loads(path.read_text())
    engines: dict[str, Engine] = {}
    default = data.get("default")
    for engine_id, spec in data.get("engines", {}).items():
        command = spec["command"]
        if isinstance(command, str):
            command = [command]
        gateway = spec.get("gateway")
        if gateway is not None and gateway not in GATEWAYS:
            raise ValueError(f"{path}: engine {engine_id} has gateway {gateway!r}; use one of {', '.join(GATEWAYS)}")
        engines[engine_id] = Engine(
            id=engine_id,
            name=spec.get("name", engine_id),
            command=[os.path.expanduser(part) for part in command],
            env={k: str(v) for k, v in spec.get("env", {}).items()},
            gateway=gateway,
        )
    if not engines:
        raise ValueError(f"{path} defines no engines")
    if default not in engines:
        default = next(iter(engines))
    return engines, default


def load_profiles(path: Path) -> dict[str, Profile]:
    """Profiles from profiles.toml. None at all is fine: every agent then runs on its own login."""
    if not path.is_file():
        return {}
    data = tomllib.loads(path.read_text())
    profiles: dict[str, Profile] = {}
    for profile_id, spec in data.get("profiles", {}).items():
        if not PROFILE_ID.match(profile_id):
            raise ValueError(f"{path}: profile id {profile_id!r} must be lowercase letters, digits, - or _")
        if not spec.get("key"):
            raise ValueError(f"{path}: profile {profile_id} needs key (the variable that holds its key)")
        if not (spec.get("openai") or spec.get("anthropic")):
            raise ValueError(f"{path}: profile {profile_id} needs an openai or anthropic URL")
        profiles[profile_id] = Profile(
            id=profile_id,
            name=spec.get("name", profile_id),
            key=spec["key"],
            key_file=Path(os.path.expanduser(spec.get("key_file", str(path.parent / "keys.env")))),
            openai=(spec.get("openai") or "").rstrip("/") or None,
            anthropic=(spec.get("anthropic") or "").rstrip("/") or None,
        )
    return profiles


def load_agent_profiles(path: Path) -> dict[str, str]:
    """The `[agents]` table of profiles.toml: agent id -> the profile it runs on."""
    if not path.is_file():
        return {}
    return {k: str(v) for k, v in tomllib.loads(path.read_text()).get("agents", {}).items() if v}


def load_settings() -> Settings:
    load_env_file()
    database_url = os.environ.get("KREWD_DATABASE_URL")
    if not database_url:
        raise SystemExit(f"KREWD_DATABASE_URL is not set (put it in {ENV_FILE})")
    engines, default_engine = load_engines(Path(os.environ.get("KREWD_ENGINES_FILE", CONFIG_DIR / "engines.toml")))
    profiles_file = Path(os.environ.get("KREWD_PROFILES_FILE", CONFIG_DIR / "profiles.toml"))
    profiles = load_profiles(profiles_file)
    return Settings(
        database_url=database_url,
        host=os.environ.get("KREWD_HOST", "127.0.0.1,lan"),
        port=int(os.environ.get("KREWD_PORT", "8800")),
        workdir=Path(os.path.expanduser(os.environ.get("KREWD_WORKDIR", "~"))),
        token_file=Path(os.environ.get("KREWD_TOKEN_FILE", CONFIG_DIR / "token")),
        state_dir=Path(os.environ.get("KREWD_STATE_DIR", Path.home() / ".local" / "state" / "krewd")),
        engines=engines,
        default_engine=default_engine,
        profiles=profiles,
        profiles_file=profiles_file,
        agent_profiles=load_agent_profiles(profiles_file),
        cdp_port=int(os.environ.get("KREWD_CDP_PORT", "9222")),
        browser_location=os.environ.get("KREWD_BROWSER_LOCATION", "").strip(),
        data_dir=Path(os.path.expanduser(os.environ.get("KREWD_DATA_DIR", "~/.krewd"))),
        max_upload_mb=int(os.environ.get("KREWD_MAX_UPLOAD_MB", "1024")),
        share_dirs=tuple(
            Path(os.path.expanduser(d)) for d in os.environ.get("KREWD_SHARE_DIRS", "").split(os.pathsep) if d.strip()
        ),
        tls=_choice("KREWD_TLS", "auto", ("auto", "off")),
        tls_cert=Path(os.path.expanduser(os.environ.get("KREWD_TLS_CERT", str(CONFIG_DIR / "tls.crt")))),
        tls_key=Path(os.path.expanduser(os.environ.get("KREWD_TLS_KEY", str(CONFIG_DIR / "tls.key")))),
        mdns=os.environ.get("KREWD_MDNS", "1").strip().lower() not in ("0", "off", "no", "false"),
        urls=tuple(u.strip().rstrip("/") for u in os.environ.get("KREWD_URLS", "").split(",") if u.strip()),
        passkey_rp_id=os.environ.get("KREWD_PASSKEY_RP_ID", "").strip().lower() or None,
        passkey_certs=tuple(c.strip().replace(":", "").lower() for c in os.environ.get("KREWD_PASSKEY_CERTS", "").split(",")
                            if c.strip()),
        voice=load_voice_settings(Path(os.path.expanduser(os.environ.get("KREWD_DATA_DIR", "~/.krewd")))),
    )


def load_voice_settings(data_dir: Path) -> VoiceSettings:
    fields = []
    for part in os.environ.get("KREWD_VOICE_STT_FIELDS", "").split(","):
        name, sep, value = part.partition("=")
        if sep and name.strip():
            fields.append((name.strip(), value.strip()))
    return VoiceSettings(
        stt_url=os.environ.get("KREWD_VOICE_STT_URL", "").strip().rstrip("/"),
        stt_model=os.environ.get("KREWD_VOICE_STT_MODEL", "").strip(),
        stt_fields=tuple(fields),
        models_dir=Path(os.path.expanduser(os.environ.get("KREWD_VOICE_MODELS", str(data_dir / "voice")))),
    )


def _choice(name: str, default: str, allowed: tuple[str, ...]) -> str:
    value = os.environ.get(name, default).strip().lower() or default
    if value not in allowed:
        raise SystemExit(f"{name}={value!r}: use one of {', '.join(allowed)}")
    return value


def new_token() -> str:
    raw = "".join(secrets.choice(TOKEN_ALPHABET) for _ in range(24))
    return "-".join(raw[i : i + 4] for i in range(0, 24, 4))


def read_or_create_token(path: Path) -> str:
    if path.is_file():
        return path.read_text().strip()
    path.parent.mkdir(parents=True, exist_ok=True)
    token = new_token()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token + "\n")
    return token
