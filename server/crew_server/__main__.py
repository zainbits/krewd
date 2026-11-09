"""Entry point: `python -m crew_server [serve|token]`."""

from __future__ import annotations

import asyncio
import logging
import socket
import sys

import asyncpg
import uvicorn

from . import db
from .api import create_app
from .config import load_settings, read_or_create_token
from .hub import Hub


def lan_address() -> str:
    """This machine's primary IPv4 address (the one its default route uses). Sends nothing."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("192.0.2.1", 9))
        return probe.getsockname()[0]


def listen_sockets(hosts: str, port: int) -> list[socket.socket]:
    """One listening socket per address in CREW_HOST ("127.0.0.1,lan", "0.0.0.0", ...).

    Binding chosen addresses instead of 0.0.0.0 lets Tailscale Serve use the same port on the
    Tailscale address while proxying to loopback.
    """
    sockets = []
    for host in dict.fromkeys(h.strip() for h in hosts.split(",") if h.strip()):
        address = lan_address() if host == "lan" else host
        family = socket.AF_INET6 if ":" in address else socket.AF_INET
        sock = socket.socket(family, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((address, port))
        sock.set_inheritable(True)
        sockets.append(sock)
        logging.getLogger("crew").info("listening on %s:%s", address, port)
    return sockets


DB_WAIT_SECONDS = 180


async def connect_with_retry(url: str):
    """At boot the database may start after Crew; wait for it instead of crashing (and using up restarts)."""
    log = logging.getLogger("crew")
    deadline = asyncio.get_running_loop().time() + DB_WAIT_SECONDS
    delay = 1.0
    while True:
        try:
            return await db.connect(url)
        except (OSError, asyncio.TimeoutError, asyncpg.CannotConnectNowError) as exc:
            if asyncio.get_running_loop().time() + delay > deadline:
                raise
            log.warning("database not reachable yet (%s); retrying in %.0f s", type(exc).__name__, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 15)


async def serve() -> None:
    settings = load_settings()
    token = read_or_create_token(settings.token_file)
    sockets = listen_sockets(settings.host, settings.port)
    pool = await connect_with_retry(settings.database_url)
    hub = Hub(settings, pool)
    app = create_app(hub, token)
    config = uvicorn.Config(app, log_level="info", proxy_headers=True)
    try:
        await uvicorn.Server(config).serve(sockets=sockets)
    finally:
        await pool.close()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    command = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if command == "token":
        settings = load_settings()
        print(read_or_create_token(settings.token_file))
    elif command == "serve":
        asyncio.run(serve())
    else:
        raise SystemExit("usage: python -m crew_server [serve|token]")


if __name__ == "__main__":
    main()
