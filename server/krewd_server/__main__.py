"""Entry point: `python -m krewd_server [serve|token|connect]`."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import signal
import socket
import sys
from urllib.parse import urlencode

import asyncpg
import uvicorn

from . import __version__, db, tls
from .api import create_app
from .config import Settings, load_settings, read_or_create_token
from .hub import Hub
from .mdns import Announcer


def lan_address() -> str:
    """This machine's primary IPv4 address (the one its default route uses). Sends nothing."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("192.0.2.1", 9))
        return probe.getsockname()[0]


def listen_addresses(hosts: str) -> list[str]:
    return [lan_address() if h == "lan" else h for h in dict.fromkeys(h.strip() for h in hosts.split(",") if h.strip())]


def listen_sockets(hosts: str, port: int) -> list[tuple[str, socket.socket]]:
    """One listening socket per address in KREWD_HOST ("127.0.0.1,lan", "0.0.0.0", ...).

    Binding chosen addresses instead of 0.0.0.0 lets Tailscale Serve use the same port on the
    Tailscale address while proxying to loopback.
    """
    sockets = []
    for address in listen_addresses(hosts):
        family = socket.AF_INET6 if ":" in address else socket.AF_INET
        sock = socket.socket(family, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((address, port))
        sock.set_inheritable(True)
        sockets.append((address, sock))
    return sockets


DB_WAIT_SECONDS = 180


async def connect_with_retry(url: str):
    """At boot the database may start after Krewd; wait for it instead of crashing (and using up restarts)."""
    log = logging.getLogger("krewd")
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


class _Server(uvicorn.Server):
    """A uvicorn server that leaves signals to `serve`, which runs two of them side by side."""

    @contextlib.contextmanager
    def capture_signals(self):
        yield


def tls_marked(app):
    """Marks requests that arrived over Krewd's own HTTPS (`scope["krewd_tls"]`), for the routes that take keys."""

    async def marked(scope, receive, send):
        if scope["type"] in ("http", "websocket"):
            scope = {**scope, "krewd_tls": True}
        await app(scope, receive, send)

    return marked


def https_addresses(settings: Settings, addresses: list[str]) -> list[str]:
    """The listening addresses that get HTTPS: every one but loopback (unless KREWD_TLS=off)."""
    if settings.tls == "off":
        return []
    return [a for a in addresses if not tls.is_loopback(a) and a not in ("0.0.0.0", "::")] or (
        [a for a in addresses if a in ("0.0.0.0", "::")]
    )


async def serve() -> None:
    log = logging.getLogger("krewd")
    settings = load_settings()
    token = read_or_create_token(settings.token_file)
    sockets = listen_sockets(settings.host, settings.port)
    secure = https_addresses(settings, [a for a, _ in sockets])
    if secure:
        tls.ensure_certificate(settings.tls_cert, settings.tls_key, [lan_address()] + secure)
    pool = await connect_with_retry(settings.database_url)
    hub = Hub(settings, pool)
    app = create_app(hub, token)
    servers: list[tuple[uvicorn.Server, list[socket.socket]]] = []
    plain = [sock for address, sock in sockets if address not in secure]
    https = [sock for address, sock in sockets if address in secure]
    # The plain server owns the app's lifespan (hub, browser watcher); the HTTPS one only serves requests.
    if plain:
        servers.append((_Server(uvicorn.Config(app, log_level="info", proxy_headers=True)), plain))
    if https:
        config = uvicorn.Config(
            tls_marked(app), log_level="info", proxy_headers=False, lifespan="on" if not plain else "off",
            ssl_certfile=str(settings.tls_cert), ssl_keyfile=str(settings.tls_key),
        )
        servers.append((_Server(config), https))
    for address, _ in sockets:
        log.info("listening on %s://%s:%s", "https" if address in secure else "http", address, settings.port)
    announcer = Announcer(secure, settings.port, __version__, tls.pin(settings.tls_cert)) if secure and settings.mdns else None

    def stop(*_):
        for server, _ in servers:
            server.should_exit = True

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop)
    tasks = [asyncio.create_task(server.serve(sockets=socks)) for server, socks in servers]
    try:
        if announcer:
            await announcer.start()
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        stop()  # one stopped (or failed to start): stop the other too
        await asyncio.gather(*tasks, return_exceptions=True)
        for task in done:
            task.result()
    finally:
        if announcer:
            await announcer.close()
        await pool.close()


def connect_link(settings: Settings, token: str) -> str:
    """The link the phone opens to connect (also shown as a QR code): addresses, the certificate pin, the token."""
    addresses = []
    for address in https_addresses(settings, listen_addresses(settings.host)):
        if address in ("0.0.0.0", "::"):
            address = lan_address()
        addresses.append(f"https://{address}:{settings.port}")
    if settings.tls == "off":
        addresses += [f"http://{a}:{settings.port}" for a in listen_addresses(settings.host) if not tls.is_loopback(a)]
    addresses += list(settings.urls)
    # Once the owner's security key is registered, the token stays on this machine: a new phone pairs with the key.
    paired = (settings.token_file.parent / "owner.json").is_file()
    query = [("a", a) for a in dict.fromkeys(addresses)] + ([] if paired else [("t", token)])
    if settings.tls != "off":
        tls.ensure_certificate(settings.tls_cert, settings.tls_key, [lan_address()])
        query.append(("p", tls.pin(settings.tls_cert)))
    return "krewd://connect?" + urlencode(query)


def print_connect(settings: Settings, token: str) -> None:
    import segno

    link = connect_link(settings, token)
    if "&t=" in link:
        print("Scan this with the phone's camera, or open the link on the phone. It holds the setup token: keep it private.\n")
    else:
        print("Scan this with a new phone's camera (or open the link there), then tap your security key on the phone.\n")
    segno.make(link, error="m").terminal(compact=True)
    print("\n" + link)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    command = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if command == "token":
        settings = load_settings()
        print(read_or_create_token(settings.token_file))
    elif command == "connect":
        settings = load_settings()
        print_connect(settings, read_or_create_token(settings.token_file))
    elif command == "pin":
        settings = load_settings()
        print(tls.pin(settings.tls_cert) if settings.tls_cert.is_file() else "no certificate yet (made on first start)")
    elif command == "serve":
        asyncio.run(serve())
    else:
        raise SystemExit("usage: python -m krewd_server [serve|token|connect|pin]")


if __name__ == "__main__":
    main()
