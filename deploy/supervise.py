#!/usr/bin/env python3
"""Run one command in a named, visible tmux session for systemd, and stop it cleanly.

    supervise.py --session NAME --cwd DIR [--stop-key C-c | --close-cdp PORT] -- COMMAND...

- Starts `COMMAND` in tmux session NAME (or adopts the session if it already runs), so the owner can
  always `tmux attach -t NAME` and watch or take over.
- Exits non-zero when the session ends, so systemd (Restart=always) starts it again.
- On SIGTERM it stops the program gracefully before removing the session: Ctrl-C for the server,
  or the Chrome DevTools `Browser.close` call for Brave, which flushes cookies and site data to
  disk (a plain kill can lose logins saved in the last ~30 seconds).

Standard library only, so it runs before any project environment exists.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shlex
import signal
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request

TMUX = "/usr/bin/tmux" if os.path.exists("/usr/bin/tmux") else "tmux"
# Passed into the tmux session explicitly: an already-running tmux server keeps its own old environment.
PASS_ENV = ("PATH", "HOME", "USER", "LANG", "DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR",
            "DBUS_SESSION_BUS_ADDRESS", "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP")
# Also every KREWD_* and UV_* variable the unit sets (the demo server's config folder, a fixed uv).
PASS_PREFIXES = ("KREWD_", "UV_")


def tmux(*args: str) -> int:
    return subprocess.run([TMUX, *args], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode


def alive(session: str) -> bool:
    return tmux("has-session", "-t", "=" + session) == 0


def wait_gone(session: str, seconds: float) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if not alive(session):
            return True
        time.sleep(0.5)
    return not alive(session)


def cdp_close(port: int) -> None:
    """Send Browser.close over the DevTools websocket (minimal client, one masked text frame)."""
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=3) as r:
        url = urllib.parse.urlparse(json.load(r)["webSocketDebuggerUrl"])
    with socket.create_connection((url.hostname, url.port), timeout=5) as sock:
        key = base64.b64encode(os.urandom(16)).decode()
        sock.sendall(
            (f"GET {url.path} HTTP/1.1\r\nHost: {url.hostname}:{url.port}\r\nUpgrade: websocket\r\n"
             f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode()
        )
        if b" 101 " not in sock.recv(4096).split(b"\r\n", 1)[0]:
            raise ConnectionError("DevTools refused the websocket")
        payload = json.dumps({"id": 1, "method": "Browser.close"}).encode()
        mask = os.urandom(4)
        sock.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))
        try:
            sock.recv(4096)
        except OSError:
            pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", required=True)
    parser.add_argument("--cwd", default=os.path.expanduser("~"))
    parser.add_argument("--stop-key", help="tmux key to send for a graceful stop, e.g. C-c")
    parser.add_argument("--close-cdp", type=int, metavar="PORT", help="close Brave through DevTools on this port")
    parser.add_argument("--stop-timeout", type=float, default=20)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("no command")

    def stop(*_: object) -> None:
        if alive(args.session):
            try:
                if args.close_cdp:
                    cdp_close(args.close_cdp)
                elif args.stop_key:
                    tmux("send-keys", "-t", "=" + args.session + ":", args.stop_key)
            except Exception as exc:  # fall through to removing the session
                print(f"graceful stop failed: {exc}", flush=True)
            if not wait_gone(args.session, args.stop_timeout):
                tmux("kill-session", "-t", "=" + args.session)
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    if alive(args.session):
        print(f"adopting running tmux session {args.session}", flush=True)
    else:
        keys = [*PASS_ENV, *sorted(k for k in os.environ if k.startswith(PASS_PREFIXES))]
        env = [f"-e{k}={os.environ[k]}" for k in keys if os.environ.get(k)]
        if tmux("new-session", "-d", "-s", args.session, "-c", args.cwd, *env, "exec " + shlex.join(command)):
            sys.exit(f"could not start tmux session {args.session}")
        print(f"started {args.session}; watch it with: tmux attach -t {args.session}", flush=True)
    while True:
        time.sleep(2)
        if not alive(args.session):
            print(f"{args.session} ended; systemd will start it again", flush=True)
            sys.exit(1)


if __name__ == "__main__":
    main()
