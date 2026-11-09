"""Images in tool results (a screenshot the agent read, an image an MCP tool returned).

Agents return them as base64 inside the tool result: ACP image content blocks
(`{"type": "image", "data", "mimeType"}`) or, in raw output, Anthropic-style blocks
(`{"type": "image", "source": {"type": "base64", "data", "media_type"}}`). Crew saves each image once
under `<data dir>/common/tool-images/<first 8 characters of the session id>-<hash>.<ext>` (mode 600),
puts the file names on the tool event as `images`, and drops the base64 from the text output. The app
fetches them with `GET /api/tool-images/{name}`.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import os
import re
from pathlib import Path
from typing import Any

MAX_IMAGES = 8
MAX_BYTES = 20 * 1024 * 1024
EXTENSIONS = {"image/png": "png", "image/jpeg": "jpg", "image/gif": "gif", "image/webp": "webp"}
MIME = {ext: mime for mime, ext in EXTENSIONS.items()}
NAME_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{20}\.(png|jpg|gif|webp)$")


def _image(block: Any) -> tuple[str, str] | None:
    """(base64 data, mime type) if the block is an inline image."""
    if not isinstance(block, dict) or block.get("type") != "image":
        return None
    source = block.get("source")
    if isinstance(source, dict) and source.get("type") == "base64" and isinstance(source.get("data"), str):
        return source["data"], source.get("media_type") or "image/png"
    if isinstance(block.get("data"), str):
        return block["data"], block.get("mimeType") or block.get("mime_type") or "image/png"
    return None


def split(value: Any) -> tuple[Any, list[tuple[str, str]]]:
    """The value without its inline images, and the images found (anywhere in nested lists and dicts)."""
    found: list[tuple[str, str]] = []

    def walk(item: Any) -> Any:
        image = _image(item)
        if image is not None:
            found.append(image)
            return None
        if isinstance(item, list):
            kept = [walk(x) for x in item]
            return [x for x in kept if x is not None]
        if isinstance(item, dict):
            return {k: walk(v) for k, v in item.items()}
        return item

    return walk(value), found


def is_empty(value: Any) -> bool:
    return value in (None, "", [], {}) or (isinstance(value, dict) and all(is_empty(v) for v in value.values()))


class ToolImages:
    def __init__(self, root: Path):
        self.root = root

    def prepare(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)

    def save(self, session_id: Any, images: list[tuple[str, str]]) -> list[str]:
        """Writes each image once (named by its content hash) and returns the file names."""
        names: list[str] = []
        for data, mime in images[:MAX_IMAGES]:
            ext = EXTENSIONS.get(mime.lower())
            if ext is None:
                continue
            try:
                raw = base64.b64decode(data, validate=False)
            except (binascii.Error, ValueError):
                continue
            if not raw or len(raw) > MAX_BYTES:
                continue
            name = f"{str(session_id)[:8]}-{hashlib.sha256(raw).hexdigest()[:20]}.{ext}"
            path = self.root / name
            if not path.exists():
                self.prepare()
                tmp = path.with_suffix(".part")
                tmp.write_bytes(raw)
                os.chmod(tmp, 0o600)
                tmp.replace(path)
            if name not in names:
                names.append(name)
        return names

    def path(self, name: str) -> Path | None:
        if not NAME_RE.match(name):
            return None
        path = self.root / name
        return path if path.is_file() else None

    @staticmethod
    def mime(name: str) -> str:
        return MIME.get(name.rsplit(".", 1)[-1], "application/octet-stream")
