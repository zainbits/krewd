#!/usr/bin/env bash
# Start the Crew server in the foreground (systemd runs this inside tmux session "crew").
set -euo pipefail
source "$(dirname "$0")/lib.sh"
# Agents need the same tools as the owner's own terminal (gh, browser-harness, SDKs), so take the login
# shell's PATH; fall back to the current one if that shell is slow or noisy.
if login_path="$(timeout 10 "${SHELL:-/bin/bash}" -lic 'printf "%s" "$PATH"' </dev/null 2>/dev/null)" && [[ -n "$login_path" ]]; then
  export PATH="$login_path"
fi
export PATH="$HOME/.local/bin:$PATH"
UV="$(find_uv)" || { echo "uv not found: install it (https://docs.astral.sh/uv/)" >&2; exit 1; }
cd "$REPO/server"
"$UV" sync --frozen --quiet
# exec the environment's Python itself (not `uv run`), so the tmux pane process is the server and a
# crash or stop never leaves an orphan holding the port.
exec "$REPO/server/.venv/bin/python" -m crew_server serve
