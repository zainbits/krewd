"""Settings from the environment and ~/.config/crew/: database URL, token, engines."""

from __future__ import annotations

import os
import secrets
import tomllib
from dataclasses import dataclass, field
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("CREW_CONFIG_DIR", Path.home() / ".config" / "crew"))
ENV_FILE = CONFIG_DIR / "crew.env"

# 24 lowercase base32 characters (120 bits), grouped by 4 so it can be typed on a phone.
TOKEN_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"


@dataclass(frozen=True)
class Engine:
    id: str
    name: str
    command: list[str]
    env: dict[str, str] = field(default_factory=dict)


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
    cdp_port: int = 9222
    data_dir: Path = Path.home() / ".crew"
    max_upload_mb: int = 1024
    share_dirs: tuple[Path, ...] = ()  # folders the agents hand files back from; empty means workdir/outputs

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
        engines[engine_id] = Engine(
            id=engine_id,
            name=spec.get("name", engine_id),
            command=[os.path.expanduser(part) for part in command],
            env={k: str(v) for k, v in spec.get("env", {}).items()},
        )
    if not engines:
        raise ValueError(f"{path} defines no engines")
    if default not in engines:
        default = next(iter(engines))
    return engines, default


def load_settings() -> Settings:
    load_env_file()
    database_url = os.environ.get("CREW_DATABASE_URL")
    if not database_url:
        raise SystemExit(f"CREW_DATABASE_URL is not set (put it in {ENV_FILE})")
    engines, default_engine = load_engines(Path(os.environ.get("CREW_ENGINES_FILE", CONFIG_DIR / "engines.toml")))
    return Settings(
        database_url=database_url,
        host=os.environ.get("CREW_HOST", "127.0.0.1,lan"),
        port=int(os.environ.get("CREW_PORT", "8800")),
        workdir=Path(os.path.expanduser(os.environ.get("CREW_WORKDIR", "~"))),
        token_file=Path(os.environ.get("CREW_TOKEN_FILE", CONFIG_DIR / "token")),
        state_dir=Path(os.environ.get("CREW_STATE_DIR", Path.home() / ".local" / "state" / "crew")),
        engines=engines,
        default_engine=default_engine,
        cdp_port=int(os.environ.get("CREW_CDP_PORT", "9222")),
        data_dir=Path(os.path.expanduser(os.environ.get("CREW_DATA_DIR", "~/.crew"))),
        max_upload_mb=int(os.environ.get("CREW_MAX_UPLOAD_MB", "1024")),
        share_dirs=tuple(
            Path(os.path.expanduser(d)) for d in os.environ.get("CREW_SHARE_DIRS", "").split(os.pathsep) if d.strip()
        ),
    )


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
