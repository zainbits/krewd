"""Files the agent hands back to the owner.

An agent saves a file in one of the shared folders (`KREWD_SHARE_DIRS`, default `$KREWD_WORKDIR/outputs`)
and writes its path in the reply. At the end of the turn Krewd lists the paths that name a real file in
those folders on the `turn_end` event, and the app shows each as a download (`GET /api/files?path=`).
Nothing outside the shared folders can be fetched: paths are resolved, symlinks included, first.
"""

from __future__ import annotations

import mimetypes
import os
import re
from pathlib import Path
from typing import Any

MAX_FILES = 10
# An absolute or ~ path; it stops at whitespace, quotes, brackets and Markdown marks.
PATH_RE = re.compile(r"(?<![\w/~.])(~?/[^\s`'\"<>()\[\]{}|*]+)")
TRAILING = ".,;:!?"


def resolve(path: str, roots: list[Path]) -> Path | None:
    """The real file a path names, if it is a regular file inside one of the roots."""
    if not path or "\x00" in path:
        return None
    candidate = Path(os.path.expanduser(path))
    if not candidate.is_absolute():
        return None
    try:
        real = candidate.resolve(strict=True)
    except (OSError, RuntimeError):
        return None
    if not real.is_file():
        return None
    for root in roots:
        try:
            if real.is_relative_to(root.resolve()):
                return real
        except OSError:
            continue
    return None


def describe(path: Path) -> dict[str, Any]:
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return {"path": str(path), "name": path.name, "size": path.stat().st_size, "mime": mime}


def mentioned(text: str, roots: list[Path]) -> list[dict[str, Any]]:
    """Files under the roots that the text names, in order of first mention."""
    found: list[dict[str, Any]] = []
    seen: set[Path] = set()
    for match in PATH_RE.finditer(text or ""):
        real = resolve(match.group(1).rstrip(TRAILING), roots)
        if real is None or real in seen:
            continue
        seen.add(real)
        found.append(describe(real))
        if len(found) >= MAX_FILES:
            break
    return found
