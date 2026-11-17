"""Files the owner attaches to a task.

The phone uploads each file first (`POST /api/uploads`, streamed to disk), then sends a message that
names the upload ids. Sending claims the files: they move to
`<data dir>/common/attachments/<first 8 characters of the session id>-<file name>`, and the agent
gets their paths in the prompt (as ACP resource links plus a plain-text list).
"""

from __future__ import annotations

import json
import os
import re
import time
import uuid
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

INCOMING = ".incoming"
STALE_SECONDS = 24 * 3600
NAME_MAX = 150


class TooLarge(Exception):
    pass


class UnknownUpload(Exception):
    pass


def clean_name(name: str) -> str:
    """A safe file name from what the phone reports: no folders, control characters or leading dots."""
    base = re.split(r"[\\/]", name)[-1]
    base = re.sub(r"[\x00-\x1f\x7f]", "", base).strip().lstrip(".")
    base = re.sub(r"\s+", " ", base)
    if not base:
        return "file"
    if len(base) > NAME_MAX:
        stem, dot, ext = base.rpartition(".")
        if dot and 0 < len(ext) <= 10:
            base = stem[: NAME_MAX - len(ext) - 1] + "." + ext
        else:
            base = base[:NAME_MAX]
    return base


class Attachments:
    def __init__(self, root: Path, max_bytes: int):
        self.root = root
        self.incoming = root / INCOMING
        self.max_bytes = max_bytes

    def prepare(self) -> None:
        for folder in (self.root, self.incoming):
            folder.mkdir(parents=True, exist_ok=True)
            os.chmod(folder, 0o700)
        cutoff = time.time() - STALE_SECONDS
        for path in self.incoming.iterdir():
            if path.stat().st_mtime < cutoff:
                path.unlink(missing_ok=True)

    async def receive(self, name: str, mime: str | None, chunks: AsyncIterator[bytes]) -> dict[str, Any]:
        self.prepare()
        upload_id = uuid.uuid4().hex
        data = self.incoming / upload_id
        size = 0
        try:
            fd = os.open(data, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as out:
                async for chunk in chunks:
                    size += len(chunk)
                    if size > self.max_bytes:
                        raise TooLarge(f"file is larger than {self.max_bytes // (1024 * 1024)} MB")
                    out.write(chunk)
        except BaseException:
            data.unlink(missing_ok=True)
            raise
        meta = {"id": upload_id, "name": clean_name(name), "size": size, "mime": mime or "application/octet-stream"}
        (self.incoming / f"{upload_id}.json").write_text(json.dumps(meta))
        return meta

    def claim(self, session_id: uuid.UUID, upload_ids: list[str]) -> list[dict[str, Any]]:
        """Move uploads to their final names. All ids are checked before any file moves."""
        metas = []
        for upload_id in upload_ids:
            if not re.fullmatch(r"[0-9a-f]{32}", upload_id):
                raise UnknownUpload(upload_id)
            meta_path = self.incoming / f"{upload_id}.json"
            if not meta_path.is_file() or not (self.incoming / upload_id).is_file():
                raise UnknownUpload(upload_id)
            metas.append(json.loads(meta_path.read_text()))
        prefix = str(session_id)[:8]
        claimed = []
        for meta in metas:
            target = self._free_path(f"{prefix}-{meta['name']}")
            os.replace(self.incoming / meta["id"], target)
            (self.incoming / f"{meta['id']}.json").unlink(missing_ok=True)
            claimed.append({"name": meta["name"], "path": str(target), "size": meta["size"], "mime": meta["mime"]})
        return claimed

    def path(self, name: str) -> Path | None:
        """A sent attachment by its file name (what `claim` saved), for the phone to show it again."""
        if not name or name != clean_name(name) or name.startswith(INCOMING) or len(name) > NAME_MAX + 20:
            return None
        path = self.root / name
        return path if path.is_file() and not path.is_symlink() else None

    def _free_path(self, name: str) -> Path:
        path = self.root / name
        if not path.exists():
            return path
        stem, dot, ext = name.rpartition(".")
        if not dot:
            stem, ext = name, ""
        for n in range(2, 10_000):
            candidate = self.root / (f"{stem}-{n}.{ext}" if ext else f"{stem}-{n}")
            if not candidate.exists():
                return candidate
        raise FileExistsError(name)


def prompt_blocks(text: str, files: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """ACP prompt content: the message, a plain list of attached paths, and one resource link per file."""
    if not files:
        return [{"type": "text", "text": text}]
    listing = "\n".join(f"- {f['path']} ({f['mime']}, {f['size']} bytes)" for f in files)
    body = (text + "\n\n" if text else "") + f"Attached files, saved on this machine:\n{listing}"
    blocks: list[dict[str, Any]] = [{"type": "text", "text": body}]
    for f in files:
        blocks.append({
            "type": "resource_link",
            "uri": Path(f["path"]).as_uri(),
            "name": f["name"],
            "mimeType": f["mime"],
            "size": f["size"],
        })
    return blocks
