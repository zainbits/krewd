#!/usr/bin/env bash
# Start the agents' own Brave with DevTools on loopback (systemd runs this inside tmux "agent-brave").
# It uses a dedicated profile: Brave refuses remote debugging on the default profile, and this keeps
# the owner's own logins and tabs out of the agents' reach. The profile lives on disk and survives restarts.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
BRAVE="$(find_brave)" || { echo "Brave not found; set KREWD_BROWSER in $ENV_FILE" >&2; exit 1; }
PROFILE="$(conf KREWD_BROWSER_PROFILE "$HOME/.local/share/krewd/agent-brave")"
PORT="$(conf KREWD_CDP_PORT 9222)"
mkdir -p "$PROFILE"
# KREWD_BROWSER_DISPLAY=virtual (default): Brave gets its own invisible screen (Xvfb), so it never shows up on,
# or takes focus from, the desktop of whoever uses this machine. The phone's live view is its screen.
# KREWD_BROWSER_DISPLAY=desktop: the old way, a window on the desktop (KREWD_DISPLAY, default :0).
# The rest of the desktop session's environment (D-Bus, XDG_CURRENT_DESKTOP) stays, so Brave still unlocks its
# cookies with the desktop keyring either way.
MODE="$(conf KREWD_BROWSER_DISPLAY virtual)"
XVFB_PID=""
if [[ "$MODE" == virtual ]] && ! command -v Xvfb >/dev/null; then
  echo "KREWD_BROWSER_DISPLAY=virtual needs Xvfb (sudo apt install xvfb); using the desktop for now" >&2
  MODE=desktop
fi
if [[ "$MODE" == virtual ]]; then
  # The first free display from :99 up. Two Krewds (the demo) each get their own.
  N=99
  while [[ -e "/tmp/.X11-unix/X$N" || -e "/tmp/.X$N-lock" ]]; do N=$((N + 1)); done
  AUTH="$CONFIG_DIR/xvfb-$PORT.auth"  # only this user's programs may draw on it
  (umask 077; : > "$AUTH")
  xauth -q -f "$AUTH" add ":$N" . "$(mcookie)"
  Xvfb ":$N" -screen 0 1920x1080x24 -nolisten tcp -auth "$AUTH" &
  XVFB_PID=$!
  trap 'kill "$XVFB_PID" 2>/dev/null' EXIT
  trap 'exit 143' TERM HUP INT
  for _ in $(seq 50); do [[ -S "/tmp/.X11-unix/X$N" ]] && break; sleep 0.2; done
  [[ -S "/tmp/.X11-unix/X$N" ]] || { echo "Xvfb did not start on :$N" >&2; exit 1; }
  export DISPLAY=":$N" XAUTHORITY="$AUTH"
  unset WAYLAND_DISPLAY  # on a Wayland desktop, Brave would otherwise open its window there
  echo "Brave runs on its own screen :$N (Xvfb); nothing appears on the desktop"
else
  export DISPLAY="${DISPLAY:-$(conf KREWD_DISPLAY :0)}"
  if [[ -z "${XAUTHORITY:-}" || ! -s "${XAUTHORITY:-}" ]]; then
    for f in "/run/user/$(id -u)/gdm/Xauthority" "$HOME/.Xauthority"; do [[ -s "$f" ]] && { export XAUTHORITY="$f"; break; }; done
  fi
fi
# Full 1920x1080, so pages get the normal desktop (1080p) layout; the app's vertical view resizes per tab.
# --disable-gpu: software rendering. With the GPU, Brave stops painting (about 1 frame a second, and every
# mouse event waits a second) once the monitor sleeps or is unplugged, and it does not recover when the
# monitor returns. Nobody watches this window on the monitor; the phone's live view is the screen.
# KREWD_BROWSER_GPU=1 keeps the GPU for a machine whose display is always on.
GPU_FLAG=(--disable-gpu)
[[ "$(conf KREWD_BROWSER_GPU 0)" == 1 ]] && GPU_FLAG=()
# The Krewd location extension (deploy/fixed-location): a home server has no GPS or Wi-Fi and Brave on Linux has
# no system location source, so location requests (a web attendance check-in) would wait forever. It answers
# them with the location the owner applies in the app (or KREWD_BROWSER_LOCATION, which the server applies
# when the app has never set one); with neither, pages get Brave's own location API.
# Brave loads a copy in the config folder, made fresh from the repo at each start except current.js: the server
# keeps the applied location there, so pages have it the instant they start.
EXT="$CONFIG_DIR/location-extension"
mkdir -p "$EXT" && chmod 700 "$EXT"
find "$EXT" -mindepth 1 -maxdepth 1 ! -name current.js -exec rm -rf {} +
for f in "$REPO/deploy/fixed-location"/*; do [[ "$(basename "$f")" == current.js ]] || cp -r "$f" "$EXT/"; done
[[ -s "$EXT/current.js" ]] || cp "$REPO/deploy/fixed-location/current.js" "$EXT/current.js"
# Recent Chromium ignores --load-extension unless this feature is turned off.
EXT_FLAGS=(--load-extension="$EXT" --disable-features=DisableLoadExtensionCommandLineSwitch)
FLAGS=(--user-data-dir="$PROFILE" --remote-debugging-port="$PORT" --remote-debugging-address=127.0.0.1
  --no-first-run --no-default-browser-check --window-position=0,0 --window-size=1920,1080 "${GPU_FLAG[@]}"
  "${EXT_FLAGS[@]}")
[[ "$MODE" == virtual ]] && FLAGS+=(--ozone-platform=x11)
if [[ -z "$XVFB_PID" ]]; then
  exec "$BRAVE" "${FLAGS[@]}"
fi
# With its own screen: run Brave beside Xvfb, and end both when either ends (systemd starts them again).
"$BRAVE" "${FLAGS[@]}" &
BRAVE_PID=$!
wait -n "$XVFB_PID" "$BRAVE_PID" || true
kill "$BRAVE_PID" 2>/dev/null && wait "$BRAVE_PID" 2>/dev/null
exit 1
