#!/usr/bin/env bash
# Start the agents' own Brave with DevTools on loopback (systemd runs this inside tmux "agent-brave").
# It uses a dedicated profile: Brave refuses remote debugging on the default profile, and this keeps
# the owner's own logins and tabs out of the agents' reach. The profile lives on disk and survives restarts.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
BRAVE="$(find_brave)" || { echo "Brave not found; set CREW_BROWSER in $ENV_FILE" >&2; exit 1; }
PROFILE="$(conf CREW_BROWSER_PROFILE "$HOME/.local/share/crew/agent-brave")"
PORT="$(conf CREW_CDP_PORT 9222)"
mkdir -p "$PROFILE"
export DISPLAY="${DISPLAY:-$(conf CREW_DISPLAY :0)}"
if [[ -z "${XAUTHORITY:-}" || ! -s "${XAUTHORITY:-}" ]]; then
  for f in "/run/user/$(id -u)/gdm/Xauthority" "$HOME/.Xauthority"; do [[ -s "$f" ]] && { export XAUTHORITY="$f"; break; }; done
fi
# Full 1920x1080, so pages get the normal desktop (1080p) layout; the app's vertical view resizes per tab.
exec "$BRAVE" --user-data-dir="$PROFILE" --remote-debugging-port="$PORT" --remote-debugging-address=127.0.0.1 \
  --no-first-run --no-default-browser-check --window-position=0,0 --window-size=1920,1080
