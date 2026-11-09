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
# --disable-gpu: software rendering. With the GPU, Brave stops painting (about 1 frame a second, and every
# mouse event waits a second) once the monitor sleeps or is unplugged, and it does not recover when the
# monitor returns. Nobody watches this window on the monitor; the phone's live view is the screen.
# CREW_BROWSER_GPU=1 keeps the GPU for a machine whose display is always on.
GPU_FLAG=(--disable-gpu)
[[ "$(conf CREW_BROWSER_GPU 0)" == 1 ]] && GPU_FLAG=()
# CREW_BROWSER_LOCATION=LAT,LON[,ACCURACY_M]: the machine's real, fixed location. A home server has no GPS
# or Wi-Fi and Brave on Linux has no system location source, so location requests (for example a web
# attendance check-in) wait forever. If set, a small extension (deploy/fixed-location) answers them with it.
EXT_FLAGS=()
LOCATION="$(conf CREW_BROWSER_LOCATION '')"
if [[ -n "$LOCATION" ]]; then
  IFS=, read -r LAT LON ACC <<<"$LOCATION"
  ACC="${ACC:-50}"
  NUM='^-?[0-9]+(\.[0-9]+)?$'
  if [[ "$LAT" =~ $NUM && "$LON" =~ $NUM && "$ACC" =~ $NUM ]]; then
    EXT="$CONFIG_DIR/fixed-location"
    mkdir -p "$EXT"
    cp "$REPO/deploy/fixed-location/manifest.json" "$EXT/"
    sed -e "s/__LAT__/$LAT/" -e "s/__LON__/$LON/" -e "s/__ACC__/$ACC/" \
      "$REPO/deploy/fixed-location/location.template.js" > "$EXT/location.js"
    # Recent Chromium ignores --load-extension unless this feature is turned off.
    EXT_FLAGS=(--load-extension="$EXT" --disable-features=DisableLoadExtensionCommandLineSwitch)
  else
    echo "CREW_BROWSER_LOCATION must be LAT,LON[,ACCURACY_M] in decimal degrees and metres; ignoring it" >&2
  fi
fi
exec "$BRAVE" --user-data-dir="$PROFILE" --remote-debugging-port="$PORT" --remote-debugging-address=127.0.0.1 \
  --no-first-run --no-default-browser-check --window-position=0,0 --window-size=1920,1080 "${GPU_FLAG[@]}" \
  "${EXT_FLAGS[@]}"
