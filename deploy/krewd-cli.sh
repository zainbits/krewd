#!/usr/bin/env bash
# `krewd`: command line for agents and the owner on this machine (routines and more). `master.sh setup`
# links it as ~/.local/bin/krewd. It talks to the local Krewd server with the token in ~/.config/krewd/.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
py="$here/../server/.venv/bin/python"
[[ -x "$py" ]] || { echo "krewd: the server environment is missing; run ./master.sh setup in the Krewd repo" >&2; exit 1; }
exec "$py" -m krewd_server.cli "$@"
