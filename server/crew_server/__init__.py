"""Crew server: runs ACP coding agents on this host and serves the Crew apps."""

from importlib import metadata
from pathlib import Path


def _version() -> str:
    # One version for the server and the app: VERSION at the repo root, read live so a pull is enough.
    path = Path(__file__).resolve().parents[2] / "VERSION"
    if path.is_file():
        return path.read_text().strip()
    return metadata.version("crew-server")


__version__ = _version()
