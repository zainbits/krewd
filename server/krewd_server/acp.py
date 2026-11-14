"""Agent Client Protocol (ACP) client: JSON-RPC 2.0 over an agent process's stdin and stdout.

The client advertises no filesystem or terminal capabilities, so the agent does that work on
the host itself. Requests the agent sends to us (permission prompts) are handed to a callback;
anything else is answered with method-not-found.
"""

from __future__ import annotations

import asyncio
import itertools
import json
import logging
import os
import signal
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

from . import __version__

log = logging.getLogger("krewd.acp")

PROTOCOL_VERSION = 1
METHOD_NOT_FOUND = -32601
STREAM_LIMIT = 64 * 1024 * 1024

NotificationHandler = Callable[[str, dict[str, Any]], Awaitable[None]]
RequestHandler = Callable[[str, dict[str, Any]], Awaitable[dict[str, Any]]]


class AcpError(Exception):
    def __init__(self, message: str, code: int | None = None, data: Any = None):
        super().__init__(message)
        self.code = code
        self.data = data


class MethodNotFound(Exception):
    pass


class AcpProcess:
    """One running agent. `generation` changes on every start so sessions know to reload."""

    _generations = itertools.count(1)

    def __init__(
        self,
        engine_id: str,
        command: list[str],
        env: dict[str, str],
        cwd: Path,
        log_dir: Path,
        on_notification: NotificationHandler,
        on_request: RequestHandler,
        on_exit: Callable[[AcpProcess], Awaitable[None]],
    ):
        self.engine_id = engine_id
        self.command = command
        self.env = env
        self.cwd = cwd
        self.log_dir = log_dir
        self.on_notification = on_notification
        self.on_request = on_request
        self.on_exit = on_exit
        self.generation = 0
        self.init_result: dict[str, Any] = {}
        self._proc: asyncio.subprocess.Process | None = None
        self._ids = itertools.count(1)
        self._pending: dict[int, asyncio.Future] = {}
        self._tasks: set[asyncio.Task] = set()
        self._write_lock = asyncio.Lock()

    @property
    def alive(self) -> bool:
        return self._proc is not None and self._proc.returncode is None

    @property
    def capabilities(self) -> dict[str, Any]:
        return self.init_result.get("agentCapabilities", {})

    async def start(self) -> None:
        self.log_dir.mkdir(parents=True, exist_ok=True)
        stderr = open(self.log_dir / f"{self.engine_id}.log", "ab")
        self._proc = await asyncio.create_subprocess_exec(
            *self.command,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=stderr,
            cwd=self.cwd,
            env={**os.environ, **self.env},
            limit=STREAM_LIMIT,
            start_new_session=True,
        )
        stderr.close()
        self.generation = next(self._generations)
        self._spawn(self._read_loop())
        self.init_result = await self.request(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "clientCapabilities": {},
                "clientInfo": {"name": "krewd", "title": "Krewd", "version": __version__},
            },
            timeout=60,
        )

    async def stop(self) -> None:
        if not self.alive:
            return
        assert self._proc is not None
        self._proc.terminate()
        try:
            await asyncio.wait_for(self._proc.wait(), 10)
        except TimeoutError:
            self._proc.kill()

    @property
    def pid(self) -> int | None:
        return self._proc.pid if self._proc else None

    @property
    def steering(self) -> bool:
        """Whether the agent advertises the `_session/steering` extension (mid-turn messages)."""
        for meta in (self.init_result.get("_meta"), self.capabilities.get("_meta")):
            if isinstance(meta, dict) and isinstance(meta.get("steering"), dict) and meta["steering"].get("supported"):
                return True
        return False

    async def request(self, method: str, params: dict[str, Any], timeout: float | None = None) -> dict[str, Any]:
        if not self.alive:
            raise AcpError(f"{self.engine_id} agent is not running")
        request_id = next(self._ids)
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        await self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        try:
            return await asyncio.wait_for(future, timeout)
        finally:
            self._pending.pop(request_id, None)

    async def notify(self, method: str, params: dict[str, Any]) -> None:
        if self.alive:
            await self._send({"jsonrpc": "2.0", "method": method, "params": params})

    async def _send(self, message: dict[str, Any]) -> None:
        assert self._proc is not None and self._proc.stdin is not None
        data = (json.dumps(message, separators=(",", ":")) + "\n").encode()
        async with self._write_lock:
            self._proc.stdin.write(data)
            await self._proc.stdin.drain()

    def _spawn(self, coro: Awaitable[None]) -> None:
        task = asyncio.ensure_future(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _read_loop(self) -> None:
        assert self._proc is not None and self._proc.stdout is not None
        stdout = self._proc.stdout
        try:
            while line := await stdout.readline():
                try:
                    message = json.loads(line)
                except json.JSONDecodeError:
                    log.warning("%s: non-JSON line on stdout: %.200s", self.engine_id, line)
                    continue
                await self._dispatch(message)
        except Exception:
            log.exception("%s: reader failed", self.engine_id)
        finally:
            await self._proc.wait()
            error = AcpError(f"{self.engine_id} agent exited (code {self._proc.returncode})")
            for future in self._pending.values():
                if not future.done():
                    future.set_exception(error)
            self._pending.clear()
            await self.on_exit(self)

    async def _dispatch(self, message: dict[str, Any]) -> None:
        method = message.get("method")
        if method is None:
            future = self._pending.get(message.get("id"))
            if future is None or future.done():
                return
            if "error" in message:
                err = message["error"] or {}
                future.set_exception(AcpError(err.get("message", "agent error"), err.get("code"), err.get("data")))
            else:
                future.set_result(message.get("result") or {})
            return
        params = message.get("params") or {}
        if "id" not in message:
            try:
                await self.on_notification(method, params)
            except Exception:
                log.exception("%s: notification handler failed for %s", self.engine_id, method)
            return
        self._spawn(self._answer(message["id"], method, params))

    async def _answer(self, request_id: Any, method: str, params: dict[str, Any]) -> None:
        try:
            result = await self.on_request(method, params)
            reply = {"jsonrpc": "2.0", "id": request_id, "result": result}
        except MethodNotFound:
            reply = {"jsonrpc": "2.0", "id": request_id, "error": {"code": METHOD_NOT_FOUND, "message": "Method not found"}}
        except Exception as exc:
            log.exception("%s: request handler failed for %s", self.engine_id, method)
            reply = {"jsonrpc": "2.0", "id": request_id, "error": {"code": -32603, "message": str(exc)}}
        if self.alive:
            await self._send(reply)
