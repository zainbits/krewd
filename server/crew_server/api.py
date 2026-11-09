"""HTTP API for the Crew apps. Every route except /api/health needs the bearer token."""

from __future__ import annotations

import hmac
import uuid
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, model_validator

from . import __version__
from .attachments import TooLarge
from .browser import Browser, BrowserUnavailable, TabGone
from .hub import Conflict, Hub, NotFound

LONG_POLL_MAX_SECONDS = 30


class WithAttachments(BaseModel):
    text: str = ""
    attachments: list[str] = Field(default_factory=list, max_length=50)  # upload ids from POST /api/uploads

    @model_validator(mode="after")
    def text_or_files(self):
        if not self.text.strip() and not self.attachments:
            raise ValueError("send some text or at least one attachment")
        return self


class NewSession(WithAttachments):
    engine: str | None = None
    mode: str | None = None
    auto_approve: bool = False
    config: dict[str, str] | None = None


class Message(WithAttachments):
    pass


class SessionChanges(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    auto_approve: bool | None = None
    pinned: bool | None = None
    archived: bool | None = None
    mode: str | None = None
    config: dict[str, str] | None = None


class Decision(BaseModel):
    option_id: str


class BrowserInput(BaseModel):
    # tap/scroll: x, y as 0..1 of the frame; scroll: dx, dy as 0..1 of the frame; text; key; navigate.
    type: str
    x: float | None = None
    y: float | None = None
    dx: float | None = None
    dy: float | None = None
    text: str | None = Field(default=None, max_length=10000)
    key: str | None = None
    action: str | None = None
    url: str | None = None

    def __repr__(self) -> str:  # never show typed text in logs or tracebacks
        return f"BrowserInput(type={self.type!r})"

    __str__ = __repr__


def create_app(hub: Hub, token: str, lifespan_hub: bool = True) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if lifespan_hub:
            await hub.start()
        try:
            yield
        finally:
            await browser.close()
            if lifespan_hub:
                await hub.close()

    app = FastAPI(title="Crew", version=__version__, lifespan=lifespan)
    browser = Browser(hub.settings.cdp_port)

    def require_token(authorization: str = Header(default="")) -> None:
        scheme, _, value = authorization.partition(" ")
        if scheme.lower() != "bearer" or not hmac.compare_digest(value.strip().encode(), token.encode()):
            raise HTTPException(status_code=401, detail="missing or wrong token")

    @app.exception_handler(NotFound)
    async def not_found(_, exc: NotFound):
        return JSONResponse(status_code=404, content={"detail": str(exc)})

    @app.exception_handler(BrowserUnavailable)
    async def browser_down(_, exc: BrowserUnavailable):
        return JSONResponse(status_code=503, content={"detail": str(exc)})

    @app.exception_handler(TabGone)
    async def tab_gone(_, exc: TabGone):
        return JSONResponse(status_code=404, content={"detail": "that tab is closed"})

    @app.exception_handler(TooLarge)
    async def too_large(_, exc: TooLarge):
        return JSONResponse(status_code=413, content={"detail": str(exc)})

    @app.exception_handler(Conflict)
    async def conflict(_, exc: Conflict):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.get("/api/health")
    async def health() -> dict[str, Any]:
        return {"ok": True, "version": __version__}

    auth = [Depends(require_token)]

    @app.get("/api/state", dependencies=auth)
    async def state() -> dict[str, Any]:
        return {
            "last_seq": hub.last_seq,
            "engines": hub.engines(),
            "workdir": str(hub.settings.workdir),
        }

    @app.get("/api/sessions", dependencies=auth)
    async def sessions(archived: bool = False) -> list[dict[str, Any]]:
        return await hub.list_sessions(archived)

    @app.post("/api/sessions", dependencies=auth, status_code=201)
    async def new_session(body: NewSession) -> dict[str, Any]:
        return await hub.create_session(body.text, body.engine, body.mode, body.auto_approve, body.config, body.attachments)

    @app.get("/api/sessions/{session_id}", dependencies=auth)
    async def session(session_id: uuid.UUID) -> dict[str, Any]:
        return await hub.get_session(session_id)

    @app.patch("/api/sessions/{session_id}", dependencies=auth)
    async def change_session(session_id: uuid.UUID, body: SessionChanges) -> dict[str, Any]:
        return await hub.update_session(session_id, body.model_dump(exclude_none=True))

    @app.post("/api/sessions/{session_id}/messages", dependencies=auth, status_code=202)
    async def message(session_id: uuid.UUID, body: Message) -> dict[str, Any]:
        await hub.get_session(session_id)
        await hub.send(session_id, body.text, body.attachments)
        return {"ok": True}

    @app.post("/api/sessions/{session_id}/cancel", dependencies=auth)
    async def cancel(session_id: uuid.UUID) -> dict[str, Any]:
        await hub.cancel(session_id)
        return {"ok": True}

    @app.get("/api/sessions/{session_id}/events", dependencies=auth)
    async def session_events(
        session_id: uuid.UUID, after: int = 0, limit: int = Query(default=5000, le=20000)
    ) -> dict[str, Any]:
        await hub.get_session(session_id)
        events = await hub.events(after, session_id, limit)
        return {"events": events, "last_seq": events[-1]["seq"] if events else after}

    @app.get("/api/events", dependencies=auth)
    async def events(after: int = 0, wait: float = Query(default=25, ge=0, le=LONG_POLL_MAX_SECONDS)) -> dict[str, Any]:
        found = await hub.wait_events(after, wait)
        return {"events": found, "last_seq": found[-1]["seq"] if found else max(after, 0)}

    @app.post("/api/uploads", dependencies=auth, status_code=201)
    async def upload(request: Request, name: str = Query(min_length=1, max_length=255)) -> dict[str, Any]:
        """Raw file body (any type), streamed to disk. Send the returned id with a message to attach it."""
        mime = (request.headers.get("content-type") or "").split(";")[0].strip() or None
        return await hub.attachments.receive(name, mime, request.stream())

    @app.get("/api/approvals", dependencies=auth)
    async def approvals(status: str | None = "pending") -> list[dict[str, Any]]:
        return await hub.approvals(status or None)

    @app.post("/api/approvals/{approval_id}", dependencies=auth)
    async def decide(approval_id: uuid.UUID, body: Decision) -> dict[str, Any]:
        return await hub.decide(approval_id, body.option_id)

    # The agents' browser: watch a tab live and take over (logins, codes) without the chat seeing it.

    @app.get("/api/browser/tabs", dependencies=auth)
    async def browser_tabs() -> dict[str, Any]:
        try:
            return {"available": True, "tabs": await browser.tabs()}
        except BrowserUnavailable as exc:
            return {"available": False, "reason": str(exc), "tabs": []}

    @app.get("/api/browser/tabs/{tab_id}/stream", dependencies=auth)
    async def browser_stream(tab_id: str) -> StreamingResponse:
        if tab_id not in {t["id"] for t in await browser.tabs()}:
            raise TabGone(tab_id)
        frames = browser.stream(tab_id)

        async def body():
            try:
                async for frame in frames:
                    head = (
                        f"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame.jpeg)}\r\n"
                        f"X-Viewport: {frame.width:.0f}x{frame.height:.0f}\r\n\r\n"
                    )
                    yield head.encode() + frame.jpeg + b"\r\n"
            finally:
                await frames.aclose()

        return StreamingResponse(
            body(), media_type="multipart/x-mixed-replace; boundary=frame", headers={"Cache-Control": "no-store"}
        )

    @app.post("/api/browser/tabs/{tab_id}/input", dependencies=auth)
    async def browser_input(tab_id: str, body: BrowserInput) -> dict[str, Any]:
        try:
            await browser.input(tab_id, body.model_dump(exclude_none=True))
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None
        return {"ok": True}

    return app
