#!/usr/bin/env bash
# `crew`: command line for agents and the owner on this machine (routines and more). `master.sh setup`
# links it as ~/.local/bin/crew. It talks to the local Crew server with the token in ~/.config/crew/.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
py="$here/../server/.venv/bin/python"
[[ -x "$py" ]] || { echo "crew: the server environment is missing; run ./master.sh setup in the Crew repo" >&2; exit 1; }
exec "$py" -m crew_server.cli "$@"
