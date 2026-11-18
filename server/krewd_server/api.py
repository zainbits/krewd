"""HTTP API for the Krewd apps. Every route except /api/health and the pairing ones needs a phone session (or the
local token, from this machine); see security.py."""

from __future__ import annotations

import asyncio
import mimetypes
import time
import hmac
import uuid
from datetime import datetime
from contextlib import asynccontextmanager, suppress
from typing import Any, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Path, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, Field, model_validator

from . import __version__, tls
from .secrets_store import SecretRequest, Secrets, origin as url_origin, parse_fields
from .security import Principal, Security, SecurityError
from . import files as shared_files
from .attachments import TooLarge
from .browser import Browser, BrowserUnavailable, TabGone
from .hub import Conflict, Hub, NotFound, TabTaken
from .locations import LOCATION_SCRIPT, Invalid as LocationInvalid, Locations
from .profiles import ProfileError
from .routines import Invalid

LONG_POLL_MAX_SECONDS = 30


class TabRef(BaseModel):
    """A tab of the agents' browser, added to the chat with "Add to chat"."""
    id: str = Field(max_length=100)
    title: str = Field(default="", max_length=500)
    url: str = Field(default="", max_length=4000)


class ChatRef(BaseModel):
    """A Krewd chat attached to a message with @."""
    id: uuid.UUID


class Note(BaseModel):
    """A margin note: the owner's reply to one part of an earlier agent message."""
    quote: str = Field(min_length=1, max_length=4000)
    note: str = Field(default="", max_length=4000)
    ref: str | None = Field(default=None, max_length=200)  # where the quote is in the chat (the app's own key)


class WithAttachments(BaseModel):
    text: str = ""
    attachments: list[str] = Field(default_factory=list, max_length=50)  # upload ids from POST /api/uploads
    tabs: list[TabRef] = Field(default_factory=list, max_length=10)
    chats: list[ChatRef] = Field(default_factory=list, max_length=5)
    client_id: str | None = Field(default=None, max_length=64)  # makes a resend idempotent

    @model_validator(mode="after")
    def text_or_files(self):
        # Margin notes (Message only) are a message on their own too.
        if not self.text.strip() and not self.attachments and not self.tabs and not self.chats and not getattr(self, "notes", None):
            raise ValueError("send some text, an attachment, a tab, a chat or a note")
        return self


class NewSession(WithAttachments):
    engine: str | None = None
    mode: str | None = None
    auto_approve: bool = False
    config: dict[str, str] | None = None
    parent_id: uuid.UUID | None = None  # a side session of that chat
    returns: bool = False  # with a parent: a sub-agent, whose finished replies go back to it
    title: str | None = Field(default=None, max_length=200)


class NewProposal(BaseModel):
    session_id: uuid.UUID | None = None  # the proposing chat; without it, the one chat running a turn
    kind: Literal["new", "prompt"] = "new"
    text: str = Field(min_length=1, max_length=50000)
    title: str = Field(default="", max_length=200)
    engine: str | None = Field(default=None, max_length=100)
    returns: bool = False
    target_id: uuid.UUID | None = None  # kind prompt


class ProposalSend(BaseModel):
    text: str | None = Field(default=None, max_length=50000)
    title: str | None = Field(default=None, max_length=200)
    engine: str | None = Field(default=None, max_length=100)
    mode: str | None = None
    config: dict[str, str] | None = None
    returns: bool | None = None
    chats: list[ChatRef] = Field(default_factory=list, max_length=5)


class Message(WithAttachments):
    # steer: into the running turn (where the engine supports it); queue: after it; interrupt: stop it, then send.
    delivery: Literal["steer", "queue", "interrupt"] | None = None
    notes: list[Note] = Field(default_factory=list, max_length=20)  # margin notes on parts of earlier replies


class SessionChanges(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    auto_approve: bool | None = None
    pinned: bool | None = None
    archived: bool | None = None
    mode: str | None = None
    config: dict[str, str] | None = None
    returns: bool | None = None  # a side session: send its finished replies back to the parent (sub-agent) or not


class TriggerFields(BaseModel):
    name: str = Field(default="main", min_length=1, max_length=40)
    schedule: str = Field(min_length=9, max_length=200)  # 5-field cron
    max_attempts: int = Field(default=1, ge=1, le=10)
    enabled: bool = True


class RoutineFields(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    triggers: list[TriggerFields] | None = Field(default=None, max_length=10)  # replaces all triggers, by name
    notes: str | None = Field(default=None, max_length=8000)
    instructions: str | None = Field(default=None, min_length=1, max_length=20000)
    schedule: str | None = Field(default=None, min_length=9, max_length=200)  # 5-field cron
    timezone: str | None = Field(default=None, max_length=64)
    engine: str | None = None
    mode: str | None = None
    config: dict[str, str] | None = None
    auto_approve: bool | None = None
    enabled: bool | None = None
    note: str | None = Field(default=None, max_length=500)  # why it changed, kept with the version
    by: Literal["owner", "agent"] = "owner"


class NewRoutine(RoutineFields):
    title: str = Field(min_length=1, max_length=200)
    instructions: str = Field(min_length=1, max_length=20000)


class RunRequest(BaseModel):
    trigger: str | None = Field(default=None, max_length=40)


class FollowUp(BaseModel):
    minutes: float | None = Field(default=None, gt=0, le=720)
    at: datetime | None = None
    reason: str = Field(default="", max_length=300)


class RunResult(BaseModel):
    result: Literal["failed", "waiting"]
    reason: str = Field(min_length=1, max_length=300)


class NotesChange(BaseModel):
    notes: str = Field(max_length=8000)
    append: bool = False


class SchedulePreview(BaseModel):
    schedule: str = Field(min_length=1, max_length=200)
    timezone: str | None = Field(default=None, max_length=64)


class ProfileFields(BaseModel):
    name: str | None = Field(default=None, max_length=60)
    openai: str | None = Field(default=None, max_length=300)  # "" removes it
    anthropic: str | None = Field(default=None, max_length=300)
    key: str | None = Field(default=None, max_length=1000)  # write-only; never sent back
    preset: str | None = Field(default=None, max_length=40)  # on create: start from a preset (only the key needed)


# Keys may only arrive over an encrypted, verified connection: Krewd's own HTTPS (the app pins its certificate),
# or from this machine, where an HTTPS proxy in front of Krewd (Tailscale Serve, Caddy...) and local tools connect.
LOOPBACK = {"127.0.0.1", "::1", "localhost"}


def verified_channel(request: Request) -> bool:
    if request.scope.get("krewd_tls"):
        return True
    # The socket's own peer, not the X-Forwarded-For caller: Tailscale Serve connects from loopback for a phone.
    peer = request.scope.get("krewd_peer", request.client.host if request.client else None)
    return peer in LOOPBACK


class LoginCode(BaseModel):
    code: str = Field(min_length=4, max_length=2000)


class AgentProfile(BaseModel):
    profile: str | None = Field(default=None, max_length=40)


class EngineSwitch(BaseModel):
    engine: str = Field(min_length=1, max_length=100)
    config: dict[str, str] | None = None
    mode: str | None = None


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
    modifiers: list[Literal["ctrl", "alt", "shift", "meta"]] | None = Field(default=None, max_length=4)  # with key
    action: str | None = None
    url: str | None = None
    button: str | None = None
    clicks: int | None = None
    held: bool | None = None

    def __repr__(self) -> str:  # never show typed text in logs or tracebacks
        return f"BrowserInput(type={self.type!r})"

    __str__ = __repr__


class TabClaim(BaseModel):
    """An agent's browser-harness claims a tab for its chat (deploy/browser-harness/agent_helpers.py)."""
    agent_session: str = Field(min_length=1, max_length=200)
    tab_id: str = Field(min_length=1, max_length=200)


class NewTab(BaseModel):
    url: str | None = Field(default=None, max_length=4000)


class TabView(BaseModel):
    mode: Literal["vertical", "horizontal"]
    width: int = 0  # CSS pixels of the phone's view, for vertical
    height: int = 0
    scale: float = 0


PREF_KEY = r"^[a-z0-9][a-z0-9_.-]{0,79}$"


class LocationApply(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    accuracy: float = Field(default=50, ge=1, le=100_000)  # metres
    name: str | None = Field(default=None, max_length=80)  # the favorite it comes from, shown in recents
    source: Literal["phone", "manual", "favorite", "recent"] = "manual"


class LocationFavorite(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    accuracy: float = Field(default=50, ge=1, le=100_000)


class LocationName(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class SecretAsk(BaseModel):
    tab_id: str | None = Field(default=None, max_length=200)
    fields: list[dict[str, Any]] | None = Field(default=None, max_length=6)
    submit: str = Field(default="enter", max_length=300)
    reason: str = Field(default="", max_length=300)


def create_app(hub: Hub, token: str, lifespan_hub: bool = True) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if lifespan_hub:
            await hub.start()
        async def on_connect() -> None:
            with suppress(Exception):
                await hub.forget_tabs({t["id"] for t in await browser.tabs()})
            await locations.sync()

        watcher = asyncio.create_task(browser.watch(hub.browser_activity, on_connect,
                                                    lambda tab_id: hub.forget_tabs(closed=tab_id)))
        try:
            yield
        finally:
            watcher.cancel()
            with suppress(asyncio.CancelledError):
                await watcher
            await browser.close()
            if lifespan_hub:
                await hub.close()

    app = FastAPI(title="Krewd", version=__version__, lifespan=lifespan)
    browser = Browser(hub.settings.cdp_port)
    locations = Locations(hub.pool, browser, hub.settings.browser_location, script=LOCATION_SCRIPT)
    cert = hub.settings.tls_cert
    security = Security(hub.settings, hub.pool, token, tls.pin(cert) if hub.settings.tls != "off" and cert.is_file() else None)
    secrets_ = Secrets()
    app.state.security = security

    def principal_of(request: Request, authorization: str) -> Principal:
        scheme, _, value = authorization.partition(" ")
        if scheme.lower() != "bearer":
            raise SecurityError("missing or wrong token", 401)
        host = request.client.host if request.client else None
        return security.authenticate(value, host, bool(request.scope.get("krewd_tls")))

    def require_token(request: Request, authorization: str = Header(default="")) -> Principal:
        principal = principal_of(request, authorization)
        request.state.principal = principal
        return principal

    async def require_presence(request: Request, principal: Principal = Depends(require_token)) -> Principal:
        """A sensitive request: once a phone is paired, it needs the owner's fingerprint for this exact request."""
        if security.paired:
            await security.check_presence(principal, request.headers.get("x-krewd-presence", ""), request.method,
                                          request.url.path, await request.body())
        return principal

    @app.exception_handler(SecurityError)
    async def security_error(_, exc: SecurityError):
        return JSONResponse(status_code=exc.status, content={"detail": str(exc)})

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

    @app.exception_handler(Invalid)
    async def invalid(_, exc: Invalid):
        return JSONResponse(status_code=422, content={"detail": str(exc)})

    @app.exception_handler(Conflict)
    async def conflict(_, exc: Conflict):
        return JSONResponse(status_code=409, content={"detail": str(exc)})

    @app.get("/api/health")
    async def health() -> dict[str, Any]:
        return {"ok": True, "version": __version__}

    @app.get("/api/codex-catalog/{secret}/{engine_id}")
    async def codex_catalog(secret: str, engine_id: str) -> Any:
        """Codex's model catalog for a profile engine, fetched by that engine's Codex (see profiles.py).

        No token: the per-start secret in the path, which only Krewd's own agent processes are given, stands in.
        """
        engine = hub.settings.engines.get(engine_id)
        profile = hub._profile_of(engine) if engine else None
        if not hmac.compare_digest(secret, hub.gateways.secret) or not profile or engine.gateway != "codex":
            raise HTTPException(404, "not found")
        try:
            return await hub.gateways.codex_catalog(engine, profile)
        except ProfileError as exc:
            return JSONResponse(status_code=502, content={"detail": str(exc)})

    auth = [Depends(require_token)]

    @app.get("/api/tls", dependencies=auth)
    async def tls_info() -> dict[str, Any]:
        """The pin of Krewd's own certificate, for an app that reached the server another trusted way."""
        cert = hub.settings.tls_cert
        return {"enabled": hub.settings.tls != "off", "pin": tls.pin(cert) if cert.is_file() else None}

    @app.get("/api/state", dependencies=auth)
    async def state() -> dict[str, Any]:
        return {
            "last_seq": hub.last_seq,
            "engines": hub.engines(),
            "workdir": str(hub.settings.workdir),
        }

    async def key_route(request: Request, body: ProfileFields) -> None:
        if body.key and not verified_channel(request):
            raise HTTPException(403, "Keys are only accepted over HTTPS, never plain HTTP")
        if body.key and security.paired:
            await require_presence(request, request.state.principal)

    # security: the owner's security key, phones and sessions

    def krewd_tls(request: Request) -> bool:
        return bool(request.scope.get("krewd_tls"))

    async def json_body(request: Request) -> dict[str, Any]:
        try:
            body = await request.json()
        except ValueError:
            raise HTTPException(422, "expected JSON") from None
        if not isinstance(body, dict):
            raise HTTPException(422, "expected a JSON object")
        return body

    @app.post("/api/security/nonce")
    async def security_nonce(request: Request) -> dict[str, Any]:
        """A one-time value to sign (no login: it is useless without the phone's or the security key's key)."""
        return security.nonce(str((await json_body(request)).get("purpose", "")))

    @app.post("/api/security/pair", status_code=201)
    async def security_pair(request: Request, authorization: str = Header(default="")) -> dict[str, Any]:
        """Add a phone. The first comes with the setup token; later ones with a tap of the owner's security key."""
        principal = None
        if authorization:
            try:
                principal = principal_of(request, authorization)
            except SecurityError:
                principal = None
        if not verified_channel(request):
            raise HTTPException(403, "Phones are only added over HTTPS")
        return await security.pair(await json_body(request), principal, krewd_tls(request))

    @app.post("/api/security/session")
    async def security_session(request: Request) -> dict[str, Any]:
        if not verified_channel(request):
            raise HTTPException(403, "Sessions are only given over HTTPS")
        return await security.session(await json_body(request), krewd_tls(request))

    @app.get("/api/security", dependencies=auth)
    async def security_state(request: Request) -> dict[str, Any]:
        principal: Principal = request.state.principal
        return {"paired": security.paired, "you": principal.kind,
                "devices": await security.devices(principal.device_id) if security.paired else [],
                "keys": security.public_keys(), "passkey_rp_id": security.settings.passkey_rp_id if security.passkeys_on else None}

    @app.post("/api/security/keys", status_code=201, dependencies=auth)
    async def security_add_key(request: Request) -> dict[str, Any]:
        """A new owner key (phone passkey or security key), signed for by an owner key already registered."""
        if not verified_channel(request):
            raise HTTPException(403, "Owner keys are only added over HTTPS")
        return await security.add_key(await json_body(request), request.state.principal)

    @app.post("/api/security/keys/{key_id}/remove", dependencies=auth)
    async def security_remove_key(key_id: str, request: Request) -> dict[str, Any]:
        body = await json_body(request) if (await request.body()) else {}
        return await security.remove_key(key_id, body, request.state.principal)

    @app.get("/.well-known/assetlinks.json")
    async def assetlinks() -> Any:
        """What the passkey domain (KREWD_PASSKEY_RP_ID) must serve, so Android lets the app use passkeys for it."""
        if not security.settings.passkey_certs:
            raise HTTPException(404, "passkeys are not set up")
        return security.assetlinks()

    @app.post("/api/security/devices/{device_id}/revoke", dependencies=auth)
    async def security_revoke(device_id: uuid.UUID, request: Request) -> dict[str, Any]:
        body = await json_body(request) if (await request.body()) else {}
        await security.revoke(device_id, body, request.state.principal)
        return {"ok": True}

    @app.post("/api/security/reset", dependencies=auth)
    async def security_reset(request: Request) -> dict[str, Any]:
        """Back to setup, for a lost phone and key: only from this machine (`krewd security reset`)."""
        if request.state.principal.kind not in ("local", "setup") or krewd_tls(request):
            raise HTTPException(403, "Only from the Krewd machine: krewd security reset")
        await security.reset()
        return {"ok": True}

    # the app's own settings, kept here so a new phone starts where the old one was

    @app.get("/api/prefs", dependencies=auth)
    async def prefs() -> dict[str, Any]:
        rows = await hub.pool.fetch("select key, value from app_prefs")
        return {r["key"]: r["value"] for r in rows}

    @app.put("/api/prefs/{key}", dependencies=auth)
    async def set_pref(request: Request, key: str = Path(pattern=PREF_KEY)) -> dict[str, Any]:
        raw = await request.body()
        if len(raw) > 64_000:
            raise HTTPException(413, "too large")
        body = await json_body(request)
        if "value" not in body:
            raise HTTPException(422, "expected {value}")
        if body["value"] is None:
            await hub.pool.execute("delete from app_prefs where key = $1", key)
        else:
            await hub.pool.execute(
                """insert into app_prefs (key, value) values ($1, $2)
                   on conflict (key) do update set value = $2, updated_at = now()""", key, body["value"])
        return {"ok": True}

    # sign-in requests: an agent asks, the owner fills it on the phone, the server types it into the page

    async def emit_secret(req: SecretRequest) -> None:
        await hub.emit(req.session_id, "secret", req.json())

    @app.post("/api/secrets", dependencies=auth, status_code=201)
    async def ask_secret(body: SecretAsk) -> dict[str, Any]:
        tabs = await browser.tabs()
        tab = next((t for t in tabs if t["id"] == body.tab_id), None) if body.tab_id else None
        if tab is None and body.tab_id is None:
            # The tab of the one task whose agent is running a turn and last drove a tab.
            busy = [t for t in tabs if hub.tab_busy(t["id"])]
            tab = busy[0] if len(busy) == 1 else None
            if tab is None:
                raise HTTPException(422, "name the tab (--tab): no single task is using one right now")
        if tab is None:
            raise HTTPException(404, "that tab is closed")
        session_id = hub.tab_owner.get(tab["id"])
        if session_id is None:
            raise HTTPException(422, "that tab is not used by a Krewd task")
        if not url_origin(tab.get("url", "")):
            raise HTTPException(422, "the tab is not on a website")
        submit = body.submit.strip() or "enter"
        req = secrets_.add(SecretRequest(
            id=uuid.uuid4(), session_id=session_id, tab_id=tab["id"], url=tab.get("url", ""),
            title=tab.get("title") or "", reason=body.reason.strip(), fields=parse_fields(body.fields), submit=submit,
        ))
        await emit_secret(req)
        return req.json()

    @app.get("/api/secrets", dependencies=auth)
    async def pending_secrets() -> list[dict[str, Any]]:
        for req in secrets_.expire():
            await emit_secret(req)
        return [r.json() for r in secrets_.pending()]

    @app.get("/api/secrets/{request_id}/wait", dependencies=auth)
    async def wait_secret(request_id: uuid.UUID, timeout: float = Query(default=30, ge=0, le=60)) -> dict[str, Any]:
        req = secrets_.get(request_id)
        if req is None:
            raise HTTPException(404, "no such request")
        if req.status == "pending":
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(req.done.wait(), min(timeout, max(0.0, req.expires - time.time())))
            if req.status == "pending" and req.expires <= time.time():
                secrets_.expire()
                await emit_secret(req)
        return req.json()

    @app.post("/api/secrets/{request_id}/cancel", dependencies=auth)
    async def cancel_secret(request_id: uuid.UUID) -> dict[str, Any]:
        req = secrets_.get(request_id)
        if req is None:
            raise HTTPException(404, "no such request")
        if req.status == "pending":
            req.status = "cancelled"
            req.done.set()
            await emit_secret(req)
        return req.json()

    @app.post("/api/secrets/{request_id}/fill")
    async def fill_secret(request_id: uuid.UUID, request: Request, principal: Principal = Depends(require_token)) -> dict[str, Any]:
        """The owner's values, typed into the page. Needs a paired phone, its fingerprint for this exact body, and
        HTTPS. The body is read by hand, so a bad one is never echoed back in a validation error."""
        if not verified_channel(request):
            raise HTTPException(403, "Secrets only go over HTTPS")
        if principal.kind != "device":
            raise HTTPException(403, "Secrets need a phone set up with the security key (Settings > Security)")
        await security.check_presence(principal, request.headers.get("x-krewd-presence", ""), request.method,
                                      request.url.path, await request.body())
        req = secrets_.get(request_id)
        if req is None or req.status != "pending":
            raise HTTPException(409, f"that request is {req.status if req else 'gone'}")
        body = await json_body(request)
        values = body.get("values")
        if not isinstance(values, dict) or not all(isinstance(values.get(f["name"]), str) and values.get(f["name"]) for f in req.fields):
            raise HTTPException(422, "every field needs a value")
        try:
            current = await browser.page_url(req.tab_id)
            if url_origin(current) != req.origin:
                raise LookupError(f"the tab moved to {url_origin(current) or 'another page'}; nothing was typed")
            await browser.fill_secret(req.tab_id, [(f["kind"], f["selector"], values[f["name"]]) for f in req.fields], req.submit)
            await asyncio.sleep(2.5)  # let the form submit and the next page load before telling the agent
            req.result_url = await browser.page_url(req.tab_id)
            req.status = "filled"
        except (LookupError, TabGone, BrowserUnavailable, RuntimeError, asyncio.TimeoutError) as exc:
            req.status, req.error = "failed", str(exc) or type(exc).__name__
        finally:
            values = None  # noqa: F841 - drop the only reference to the values
            body = None  # noqa: F841
        req.done.set()
        await emit_secret(req)
        return req.json()

    @app.get("/api/profiles", dependencies=auth)
    async def profiles() -> dict[str, Any]:
        return hub.profiles()

    @app.post("/api/profiles", dependencies=auth, status_code=201)
    async def new_profile(body: ProfileFields, request: Request) -> dict[str, Any]:
        await key_route(request, body)
        return await hub.save_profile(None, body.model_dump(exclude={"key"}), body.key)

    @app.patch("/api/profiles/{profile_id}", dependencies=auth)
    async def change_profile(profile_id: str, body: ProfileFields, request: Request) -> dict[str, Any]:
        await key_route(request, body)
        return await hub.save_profile(profile_id, body.model_dump(exclude={"key", "preset"}), body.key)

    @app.delete("/api/profiles/{profile_id}", dependencies=auth)
    async def remove_profile(profile_id: str) -> dict[str, Any]:
        await hub.delete_profile(profile_id)
        return {"ok": True}

    @app.get("/api/agents", dependencies=auth)
    async def agents() -> dict[str, Any]:
        return hub.agents()

    @app.put("/api/agents/{engine_id}/profile", dependencies=auth)
    async def set_profile(engine_id: str, body: AgentProfile) -> dict[str, Any]:
        return await hub.set_profile(engine_id, body.profile)

    @app.post("/api/agents/{engine_id}/login", dependencies=auth)
    async def start_login(engine_id: str) -> dict[str, Any]:
        return await hub.start_login(engine_id)

    @app.post("/api/agents/{engine_id}/login/code", dependencies=auth)
    async def login_code(engine_id: str, body: LoginCode) -> dict[str, Any]:
        return await hub.submit_login(engine_id, body.code)

    @app.delete("/api/agents/{engine_id}/login", dependencies=auth)
    async def cancel_login(engine_id: str) -> dict[str, Any]:
        await hub.cancel_login(engine_id)
        return {"ok": True}

    @app.post("/api/agents/{engine_id}/logout", dependencies=auth)
    async def logout(engine_id: str) -> dict[str, Any]:
        return await hub.logout(engine_id)

    @app.get("/api/sessions", dependencies=auth)
    async def sessions(archived: bool = False) -> list[dict[str, Any]]:
        return await hub.list_sessions(archived)

    @app.post("/api/sessions", dependencies=auth, status_code=201)
    async def new_session(body: NewSession) -> dict[str, Any]:
        return await hub.create_session(
            body.text, body.engine, body.mode, body.auto_approve, body.config, body.attachments,
            tabs=[t.model_dump() for t in body.tabs], client_id=body.client_id,
            chats=[{"id": str(c.id)} for c in body.chats], parent_id=body.parent_id, returns=body.returns,
            title=body.title,
        )

    @app.get("/api/sessions/{session_id}", dependencies=auth)
    async def session(session_id: uuid.UUID) -> dict[str, Any]:
        return await hub.get_session(session_id)

    @app.patch("/api/sessions/{session_id}", dependencies=auth)
    async def change_session(session_id: uuid.UUID, body: SessionChanges) -> dict[str, Any]:
        return await hub.update_session(session_id, body.model_dump(exclude_none=True))

    @app.delete("/api/sessions/{session_id}", dependencies=auth)
    async def delete_session(session_id: uuid.UUID) -> dict[str, Any]:
        await hub.delete_session(session_id)
        return {"ok": True}

    @app.post("/api/sessions/{session_id}/trim", dependencies=auth)
    async def trim_session(session_id: uuid.UUID) -> dict[str, Any]:
        return await hub.trim_session(session_id)

    @app.get("/api/storage", dependencies=auth)
    async def storage() -> dict[str, Any]:
        return await hub.storage_report()

    @app.post("/api/storage/clean", dependencies=auth)
    async def storage_clean() -> dict[str, Any]:
        return await hub.clean_storage()

    @app.post("/api/sessions/{session_id}/messages", dependencies=auth, status_code=202)
    async def message(session_id: uuid.UUID, body: Message) -> dict[str, Any]:
        await hub.get_session(session_id)
        message = await hub.send(
            session_id, body.text, body.attachments, tabs=[t.model_dump() for t in body.tabs],
            delivery=body.delivery, client_id=body.client_id, chats=[{"id": str(c.id)} for c in body.chats],
            notes=[n.model_dump() for n in body.notes],
        )
        return {"ok": True, "message": message}

    @app.get("/api/sessions/{session_id}/links", dependencies=auth)
    async def links(session_id: uuid.UUID) -> dict[str, Any]:
        """The chat's parent, its side sessions and sub-agents, and the chats it waits on for a reply."""
        return await hub.links(session_id)

    # Proposals: a side session, sub-agent or message to another chat an agent drafted; only the owner sends one.

    @app.get("/api/proposals", dependencies=auth)
    async def proposals(status: str | None = "pending", session_id: uuid.UUID | None = None) -> list[dict[str, Any]]:
        return await hub.list_proposals(status or None, session_id)

    @app.post("/api/proposals", dependencies=auth, status_code=201)
    async def new_proposal(body: NewProposal) -> dict[str, Any]:
        return await hub.propose(body.session_id, body.kind, body.text, body.title, body.engine, body.returns,
                                 body.target_id)

    @app.post("/api/proposals/{proposal_id}/send", dependencies=auth)
    async def send_proposal(request: Request, proposal_id: uuid.UUID, body: ProposalSend) -> dict[str, Any]:
        # The agents use the machine's own token; a proposal only starts when the owner sends it from the phone.
        if request.state.principal.kind == "local":
            raise HTTPException(403, "only the owner sends a proposal, from the Krewd app")
        return await hub.send_proposal(
            proposal_id, body.text, body.title, body.engine, body.mode, body.config, body.returns,
            [{"id": str(c.id)} for c in body.chats],
        )

    @app.post("/api/proposals/{proposal_id}/dismiss", dependencies=auth)
    async def dismiss_proposal(proposal_id: uuid.UUID) -> dict[str, Any]:
        return await hub.dismiss_proposal(proposal_id)

    @app.post("/api/sessions/{session_id}/cancel", dependencies=auth)
    async def cancel(session_id: uuid.UUID) -> dict[str, Any]:
        await hub.cancel(session_id)
        return {"ok": True}

    @app.post("/api/sessions/{session_id}/engine", dependencies=auth, status_code=202)
    async def switch_engine(session_id: uuid.UUID, body: EngineSwitch) -> dict[str, Any]:
        """Move the chat to another agent after the running turn; the current one writes a handover first."""
        return {"ok": True, "message": await hub.switch_engine(session_id, body.engine, body.config, body.mode)}

    @app.get("/api/sessions/{session_id}/transcript", dependencies=auth)
    async def transcript(session_id: uuid.UUID, after: int = 0, last: int | None = Query(default=None, ge=1, le=2000)) -> list[dict[str, Any]]:
        """The chat as messages (owner, agents, switches), without tool calls: for agents catching up."""
        return await hub.transcript(session_id, after, last)

    @app.post("/api/sessions/{session_id}/compact", dependencies=auth, status_code=202)
    async def compact(session_id: uuid.UUID) -> dict[str, Any]:
        """Compact the agent's context after the running turn (the agent's own /compact, or a handover)."""
        return {"ok": True, "message": await hub.compact(session_id)}

    # Routines: instructions that run on a schedule, every run in the routine's own chat.

    @app.get("/api/routines", dependencies=auth)
    async def routines() -> list[dict[str, Any]]:
        return await hub.list_routines()

    @app.post("/api/routines", dependencies=auth, status_code=201)
    async def new_routine(body: NewRoutine) -> dict[str, Any]:
        return await hub.create_routine(
            body.title, body.instructions, body.schedule, body.timezone, body.engine, body.mode, body.config,
            bool(body.auto_approve), body.enabled is not False, created_by=body.by, note=body.note,
            triggers=[t.model_dump() for t in body.triggers] if body.triggers else None, notes=body.notes or "",
        )

    @app.post("/api/routines/preview", dependencies=auth)
    async def preview_schedule(body: SchedulePreview) -> dict[str, Any]:
        return hub.preview_schedule(body.schedule, body.timezone)

    @app.get("/api/routines/{routine_id}", dependencies=auth)
    async def routine(routine_id: uuid.UUID) -> dict[str, Any]:
        return await hub.get_routine(routine_id)

    @app.patch("/api/routines/{routine_id}", dependencies=auth)
    async def change_routine(routine_id: uuid.UUID, body: RoutineFields) -> dict[str, Any]:
        changes = body.model_dump(exclude_none=True, exclude={"note", "by"})
        return await hub.update_routine(routine_id, changes, changed_by=body.by, note=body.note)

    @app.put("/api/routines/{routine_id}/notes", dependencies=auth)
    async def routine_notes(routine_id: uuid.UUID, body: NotesChange) -> dict[str, Any]:
        return await hub.set_routine_notes(routine_id, body.notes, body.append)

    @app.post("/api/routines/{routine_id}/later", dependencies=auth, status_code=201)
    async def routine_later(routine_id: uuid.UUID, body: FollowUp) -> dict[str, Any]:
        """During a run: try this trigger again later, as its next attempt (refused past the trigger's limit)."""
        return await hub.follow_up(routine_id, body.minutes, body.at, body.reason)

    @app.post("/api/routines/{routine_id}/result", dependencies=auth)
    async def routine_result(routine_id: uuid.UUID, body: RunResult) -> dict[str, Any]:
        """During a run: it did not do the job (failed) or needs the owner's answer (waiting). Its chat is kept."""
        return await hub.run_result(routine_id, body.result, body.reason)

    @app.delete("/api/routines/{routine_id}/later/{trigger_id}", dependencies=auth)
    async def routine_cancel_later(routine_id: uuid.UUID, trigger_id: uuid.UUID) -> dict[str, Any]:
        await hub.cancel_follow_up(routine_id, trigger_id)
        return {"ok": True}

    @app.delete("/api/routines/{routine_id}", dependencies=auth)
    async def delete_routine(routine_id: uuid.UUID) -> dict[str, Any]:
        await hub.delete_routine(routine_id)
        return {"ok": True}

    @app.post("/api/routines/{routine_id}/run", dependencies=auth, status_code=202)
    async def run_routine(routine_id: uuid.UUID, body: RunRequest | None = None) -> dict[str, Any]:
        return await hub.run_routine(routine_id, body.trigger if body else None)

    @app.get("/api/routines/{routine_id}/runs", dependencies=auth)
    async def routine_runs(routine_id: uuid.UUID, limit: int = Query(default=50, le=500)) -> list[dict[str, Any]]:
        return await hub.routine_runs(routine_id, limit)

    @app.get("/api/routines/{routine_id}/versions", dependencies=auth)
    async def routine_versions(routine_id: uuid.UUID) -> list[dict[str, Any]]:
        return await hub.routine_versions(routine_id)

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

    @app.get("/api/files", dependencies=auth)
    async def shared_file(path: str = Query(min_length=1, max_length=4096)) -> FileResponse:
        """A file an agent handed back: only regular files inside the shared folders (KREWD_SHARE_DIRS)."""
        real = shared_files.resolve(path, hub.settings.shared)
        if real is None:
            raise HTTPException(404, "no such shared file")
        info = shared_files.describe(real)
        return FileResponse(real, media_type=info["mime"], filename=info["name"])

    @app.get("/api/attachments/{name}", dependencies=auth)
    async def attachment(name: str) -> FileResponse:
        """A file the owner sent with a message (the file name of its `path`), so the app can show it again."""
        path = hub.attachments.path(name)
        if path is None:
            raise HTTPException(404, "no such attachment")
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        return FileResponse(path, media_type=mime, headers={"Cache-Control": "private, max-age=31536000, immutable"})

    @app.get("/api/tool-images/{name}", dependencies=auth)
    async def tool_image(name: str) -> FileResponse:
        """An image from a tool result (a screenshot the agent read), saved by Krewd when it arrived."""
        path = hub.tool_images.path(name)
        if path is None:
            raise HTTPException(404, "no such image")
        return FileResponse(path, media_type=hub.tool_images.mime(name), headers={"Cache-Control": "private, max-age=31536000, immutable"})

    @app.get("/api/approvals", dependencies=auth)
    async def approvals(status: str | None = "pending") -> list[dict[str, Any]]:
        return await hub.approvals(status or None)

    @app.post("/api/approvals/{approval_id}", dependencies=auth)
    async def decide(approval_id: uuid.UUID, body: Decision) -> dict[str, Any]:
        return await hub.decide(approval_id, body.option_id)

    # The agents' browser: watch a tab live and take over (logins, codes) without the chat seeing it.

    @app.post("/api/admin/restart", dependencies=auth, status_code=202)
    async def restart() -> dict[str, Any]:
        """Emergency: stop the agents and exit; systemd brings the server back in about 10 to 15 seconds."""
        hub.restart()
        return {"ok": True}

    @app.get("/api/browser/tabs", dependencies=auth)
    async def browser_tabs() -> dict[str, Any]:
        try:
            tabs = await browser.tabs()
        except BrowserUnavailable as exc:
            return {"available": False, "reason": str(exc), "tabs": []}
        for tab in tabs:
            owner = hub.tab_owner.get(tab["id"])
            tab["session_id"] = str(owner) if owner else None
            tab["agent_active"] = hub.tab_busy(tab["id"]) is not None
        return {"available": True, "tabs": tabs}

    @app.post("/api/browser/claim", dependencies=auth)
    async def browser_claim(body: TabClaim) -> dict[str, Any]:
        """Lock a tab for the chat of the agent session that is about to use it; 409 if another chat's turn holds it."""
        try:
            tab = next((t for t in await browser.tabs() if t["id"] == body.tab_id), None)
        except BrowserUnavailable:
            tab = None
        try:
            claimed = await hub.claim_tab(body.agent_session, body.tab_id, tab)
        except TabTaken as exc:
            raise HTTPException(409, str(exc)) from None
        if tab and tab.get("view") == "vertical":  # agents work in the horizontal view
            with suppress(Exception):
                await browser.set_view(body.tab_id, "horizontal")
        return claimed

    def not_while_agent(tab_id: str) -> None:
        """The owner watches, and does not steer, a tab an agent is using (view only); Stop ends the turn."""
        if hub.tab_busy(tab_id):
            raise HTTPException(409, "An agent is using this tab: view only until its turn ends")

    @app.post("/api/browser/tabs", dependencies=auth, status_code=201)
    async def browser_new_tab(body: NewTab) -> dict[str, Any]:
        return await browser.new_tab(body.url)

    @app.delete("/api/browser/tabs/{tab_id}", dependencies=auth)
    async def browser_close_tab(tab_id: str) -> dict[str, Any]:
        not_while_agent(tab_id)
        await browser.close_tab(tab_id)
        return {"ok": True}

    @app.post("/api/browser/tabs/{tab_id}/view", dependencies=auth)
    async def browser_view(tab_id: str, body: TabView) -> dict[str, Any]:
        if body.mode == "vertical":
            not_while_agent(tab_id)
        try:
            await browser.set_view(tab_id, body.mode, body.width, body.height, body.scale)
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None
        return {"ok": True}

    @app.get("/api/browser/tabs/{tab_id}/stream", dependencies=auth)
    async def browser_stream(tab_id: str, quality: Literal["standard", "high"] = "standard") -> StreamingResponse:
        if tab_id not in {t["id"] for t in await browser.tabs()}:
            raise TabGone(tab_id)
        frames = browser.stream(tab_id, quality)

        async def body():
            try:
                async for frame in frames:
                    head = (
                        f"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame.jpeg)}\r\n"
                        f"X-Viewport: {frame.width:.0f}x{frame.height:.0f}\r\n"
                        f"X-View: {'vertical' if frame.emulated else 'horizontal'}\r\n"
                        f"X-Scroll: {frame.scroll_y:.0f}/{frame.content_height:.0f}\r\n\r\n"
                    )
                    yield head.encode() + frame.jpeg + b"\r\n"
            finally:
                await frames.aclose()

        return StreamingResponse(
            body(), media_type="multipart/x-mixed-replace; boundary=frame", headers={"Cache-Control": "no-store"}
        )

    @app.get("/api/browser/tabs/{tab_id}/snapshot", dependencies=auth)
    async def browser_snapshot(tab_id: str, width: int = Query(360, ge=80, le=1280)) -> Response:
        """A small still of a tab (JPEG) for the phone's working-task cards."""
        try:
            jpeg = await browser.snapshot(tab_id, width)
        except (RuntimeError, TimeoutError) as exc:
            raise HTTPException(503, f"no snapshot: {exc}") from exc
        return Response(content=jpeg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})

    @app.post("/api/browser/tabs/{tab_id}/input", dependencies=auth)
    async def browser_input(tab_id: str, body: BrowserInput) -> dict[str, Any]:
        not_while_agent(tab_id)
        try:
            result = await browser.input(tab_id, body.model_dump(exclude_none=True))
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from None
        return {"ok": True, **result}

    # where the agents' Brave says it is: changed only when the owner applies a location (see locations.py)

    def location_errors(exc: Exception) -> HTTPException:
        if isinstance(exc, LookupError):
            return HTTPException(404, str(exc))
        return HTTPException(422, str(exc))

    @app.get("/api/browser/location", dependencies=auth)
    async def browser_location() -> dict[str, Any]:
        return await locations.state()

    @app.post("/api/browser/location", dependencies=auth)
    async def browser_location_apply(body: LocationApply) -> dict[str, Any]:
        try:
            return await locations.apply(body.lat, body.lon, body.accuracy, body.name, body.source)
        except LocationInvalid as exc:
            raise location_errors(exc) from None

    @app.post("/api/browser/location/reapply", dependencies=auth)
    async def browser_location_reapply() -> dict[str, Any]:
        await locations.push()
        return await locations.state()

    @app.post("/api/browser/location/favorites", dependencies=auth, status_code=201)
    async def browser_location_favorite(body: LocationFavorite) -> dict[str, Any]:
        try:
            return await locations.add_favorite(body.name, body.lat, body.lon, body.accuracy)
        except LocationInvalid as exc:
            raise location_errors(exc) from None

    @app.patch("/api/browser/location/favorites/{location_id}", dependencies=auth)
    async def browser_location_rename(location_id: uuid.UUID, body: LocationName) -> dict[str, Any]:
        try:
            return await locations.rename_favorite(location_id, body.name)
        except (LocationInvalid, LookupError) as exc:
            raise location_errors(exc) from None

    @app.delete("/api/browser/location/{location_id}", dependencies=auth)
    async def browser_location_delete(location_id: uuid.UUID) -> dict[str, Any]:
        try:
            return await locations.delete(location_id)
        except (LocationInvalid, LookupError) as exc:
            raise location_errors(exc) from None

    return app
