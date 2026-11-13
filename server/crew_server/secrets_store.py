"""Sign-in requests: an agent asks for a password, an OTP or another secret; the owner types it on the phone.

The agent never sees the value. `crew secret request` asks, the phone shows a secure sheet (password manager
autofill, no screenshots), and after the owner's fingerprint the server types the values straight into the
page's fields and submits the form. Values exist only in memory for that moment: never in the database, the
logs, the events or the agent's messages. Each request works once, only on the site it was made for, and
expires.
"""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit

KINDS = {"username": "Username or email", "email": "Email", "password": "Password", "otp": "One-time code", "text": "Value"}
REQUEST_SECONDS = 600
TERMINAL = ("filled", "cancelled", "expired", "failed")


def origin(url: str) -> str:
    parts = urlsplit(url or "")
    return f"{parts.scheme}://{parts.netloc}" if parts.scheme in ("http", "https") and parts.netloc else ""


@dataclass
class SecretRequest:
    id: uuid.UUID
    session_id: uuid.UUID
    tab_id: str
    url: str
    title: str
    reason: str
    fields: list[dict[str, Any]]  # {name, label, kind, selector}
    submit: str  # "enter", "none" or a CSS selector to click
    created: float = field(default_factory=time.time)
    status: str = "pending"
    result_url: str | None = None
    error: str | None = None
    done: asyncio.Event = field(default_factory=asyncio.Event)

    @property
    def origin(self) -> str:
        return origin(self.url)

    @property
    def expires(self) -> float:
        return self.created + REQUEST_SECONDS

    def json(self) -> dict[str, Any]:
        return {
            "id": str(self.id), "session_id": str(self.session_id), "tab_id": self.tab_id,
            "site": urlsplit(self.url).netloc, "origin": self.origin, "title": self.title, "reason": self.reason,
            "fields": [{"name": f["name"], "label": f["label"], "kind": f["kind"]} for f in self.fields],
            "status": self.status, "expires_at": self.expires, "url": self.result_url, "error": self.error,
        }


def parse_fields(raw: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    """Fields the agent asks for; default: username and password."""
    raw = raw or [{"kind": "username"}, {"kind": "password"}]
    out, names = [], set()
    for item in raw[:6]:
        kind = str(item.get("kind") or item.get("name") or "text").lower()
        if kind not in KINDS:
            kind = "text"
        name = str(item.get("name") or kind)[:40]
        if name in names:
            name = f"{name}{len(names) + 1}"
        names.add(name)
        out.append({"name": name, "kind": kind, "label": str(item.get("label") or KINDS[kind])[:60],
                    "selector": (str(item["selector"])[:300] if item.get("selector") else None)})
    return out


class Secrets:
    def __init__(self) -> None:
        self.requests: dict[uuid.UUID, SecretRequest] = {}

    def add(self, request: SecretRequest) -> SecretRequest:
        self.expire()
        self.requests[request.id] = request
        return request

    def expire(self) -> list[SecretRequest]:
        now, expired = time.time(), []
        for req in list(self.requests.values()):
            if req.status == "pending" and req.expires < now:
                req.status = "expired"
                req.done.set()
                expired.append(req)
            if req.status in TERMINAL and req.created + REQUEST_SECONDS + 600 < now:
                del self.requests[req.id]
        return expired

    def pending(self) -> list[SecretRequest]:
        self.expire()
        return [r for r in self.requests.values() if r.status == "pending"]

    def get(self, request_id: uuid.UUID) -> SecretRequest | None:
        self.expire()
        return self.requests.get(request_id)
