#!/usr/bin/env bash
# Krewd's demo server: a second Krewd with made-up data, for screenshots, videos and talks.
# Run through ./master.sh demo <action>. It stays installed but stopped between recordings:
#   setup   create it once (config, database URL, units; nothing starts)
#   up      start the demo server and its Brave       down    stop both
#   seed    fill it with made-up chats and routines   reset   empty it again (keeps the pairing)
#   status  is it running, and where                  token   the connect code for the demo app
#   app     build the demo app (app.krewd.demo) and install it over adb
# The demo agent (demo/agent.py) plays the scenes in demo/scenes; nothing reaches a real model or account.
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy/lib.sh"

DEMO_DIR="${KREWD_DEMO_DIR:-$HOME/.local/share/krewd-demo}"
export KREWD_CONFIG_DIR="$DEMO_DIR/config"
ENV_FILE="$KREWD_CONFIG_DIR/krewd.env"
UNIT_DIR="$HOME/.config/systemd/user"
UNITS=(krewd-demo.service krewd-demo-browser.service)
PY="$REPO/server/.venv/bin/python"
say() { printf '%s\n' "$*"; }

setup() {
  mkdir -p "$DEMO_DIR"/{home/outputs,data,state,brave} && (umask 077; mkdir -p "$KREWD_CONFIG_DIR") && chmod 700 "$KREWD_CONFIG_DIR"
  if [[ ! -f "$ENV_FILE" ]]; then
    local url="${KREWD_DEMO_DATABASE_URL:-}"
    if [[ -z "$url" ]]; then
      read -r -s -p "PostgreSQL URL for the demo (its own database, e.g. postgresql://krewd_demo:pass@127.0.0.1:5432/krewd_demo): " url; echo
    fi
    [[ -n "$url" ]] || { say "A database URL is needed (or set KREWD_DEMO_DATABASE_URL)."; return 1; }
    URL="$url" DEMO="$DEMO_DIR" HOSTS="${KREWD_DEMO_HOST:-127.0.0.1,lan}" PORT="${KREWD_DEMO_PORT:-8810}" \
      CDP="${KREWD_DEMO_CDP_PORT:-9223}" DISP="${KREWD_DEMO_DISPLAY:-$(KREWD_CONFIG_DIR="$HOME/.config/krewd" ENV_FILE="$HOME/.config/krewd/krewd.env" conf KREWD_DISPLAY :0)}" \
      TZ_NAME="${KREWD_DEMO_TIMEZONE:-$(timedatectl show -p Timezone --value 2>/dev/null || echo UTC)}" \
      python3 - "$REPO/demo/krewd.env.template" "$ENV_FILE" <<'PY'
import os, sys
text = open(sys.argv[1]).read()
for key, value in {"@DATABASE_URL@": os.environ["URL"], "@DEMO_DIR@": os.environ["DEMO"], "@HOST@": os.environ["HOSTS"],
                   "@PORT@": os.environ["PORT"], "@CDP_PORT@": os.environ["CDP"], "@DISPLAY@": os.environ["DISP"],
                   "@TIMEZONE@": os.environ["TZ_NAME"]}.items():
    text = text.replace(key, value)
old = os.umask(0o077)
open(sys.argv[2], "w").write(text)
os.umask(old)
PY
    chmod 600 "$ENV_FILE"
    say "Created $ENV_FILE."
  else
    say "Keeping $ENV_FILE."
  fi
  sed -e "s#@REPO@#$REPO#g" -e "s#@DEMO_DIR@#$DEMO_DIR#g" "$REPO/demo/engines.toml.template" > "$KREWD_CONFIG_DIR/engines.toml"
  install_units
  say "Demo ready and stopped. Next: ./master.sh demo up, then ./master.sh demo seed."
}

install_units() {
  # The real units, renamed, with the demo's config folder and its own fake home for the server. Installed
  # but not enabled: the demo never starts at boot.
  local cdp uv; cdp="$(conf KREWD_CDP_PORT 9223)"; uv="$(find_uv)"
  mkdir -p "$UNIT_DIR"
  sed -e "s#@REPO@#$REPO#g" -e "s#--session krewd #--session krewd-demo #" -e "s#Krewd server#Krewd demo server#" \
      -e "s#tmux attach -t krewd#tmux attach -t krewd-demo#" -e "/^WantedBy=/d" -e "/^\[Install\]/d" \
      -e "s#^\[Service\]#[Service]\nEnvironment=KREWD_CONFIG_DIR=$KREWD_CONFIG_DIR\nEnvironment=HOME=$DEMO_DIR/home\nEnvironment=KREWD_UV=$uv\nEnvironment=UV_CACHE_DIR=$HOME/.cache/uv\nEnvironment=PATH=$DEMO_DIR/home/.local/bin:$(dirname "$uv"):/usr/local/bin:/usr/bin:/bin#" \
      "$REPO/deploy/systemd/krewd.service" > "$UNIT_DIR/krewd-demo.service"
  sed -e "s#@REPO@#$REPO#g" -e "s#@CDP_PORT@#$cdp#g" -e "s#--session agent-brave#--session demo-brave#" \
      -e "s#Krewd agent browser#Krewd demo browser#" -e "s#tmux attach -t agent-brave#tmux attach -t demo-brave#" \
      -e "/^WantedBy=/d" -e "/^\[Install\]/d" -e "s#^\[Service\]#[Service]\nEnvironment=KREWD_CONFIG_DIR=$KREWD_CONFIG_DIR#" \
      "$REPO/deploy/systemd/krewd-browser.service" > "$UNIT_DIR/krewd-demo-browser.service"
  # The demo agents' own `krewd` command: the real CLI, pointed at the demo's config.
  mkdir -p "$DEMO_DIR/home/.local/bin"
  printf '#!/usr/bin/env bash\nexport KREWD_CONFIG_DIR=%q\nexec %q "$@"\n' "$KREWD_CONFIG_DIR" "$REPO/deploy/krewd-cli.sh" > "$DEMO_DIR/home/.local/bin/krewd"
  chmod 755 "$DEMO_DIR/home/.local/bin/krewd"
  systemctl --user daemon-reload
  say "Installed (not enabled): ${UNITS[*]}"
}

need_setup() { [[ -f "$ENV_FILE" ]] || { say "No demo yet: run ./master.sh demo setup first."; exit 1; }; }

health() { curl -s --max-time 3 "http://127.0.0.1:$(conf KREWD_PORT 8810)/api/health"; }

up() {
  need_setup
  systemctl --user reset-failed "${UNITS[@]}" 2>/dev/null
  systemctl --user start "${UNITS[@]}" || return 1
  local i; for i in $(seq 1 40); do health >/dev/null && break; sleep 1; done
  status
}

down() { systemctl --user stop "${UNITS[@]}" && say "Demo stopped (data kept; ./master.sh demo up brings it back)."; }

status() {
  local u; for u in "${UNITS[@]}"; do printf '%-28s %s\n' "$u" "$(systemctl --user is-active "$u" 2>/dev/null)"; done
  [[ -f "$ENV_FILE" ]] || { say "not set up"; return; }
  say "port $(conf KREWD_PORT 8810) on $(conf KREWD_HOST 127.0.0.1,lan); health: $(health || echo 'not answering')"
  say "watch: tmux attach -t krewd-demo | tmux attach -t demo-brave"
}

seed() { need_setup; health >/dev/null || { say "Start it first: ./master.sh demo up"; return 1; }; close_tabs; "$PY" "$REPO/demo/seed.py"; }

reset() {
  need_setup
  local was_up=0; health >/dev/null && was_up=1
  down >/dev/null 2>&1
  "$PY" - <<'PY'
import asyncio, os, asyncpg
url = next(l.split("=", 1)[1].strip() for l in open(os.environ["KREWD_CONFIG_DIR"] + "/krewd.env") if l.startswith("KREWD_DATABASE_URL="))
KEEP = {"schema_version", "devices", "app_prefs"}  # the demo phone's pairing and the app's settings survive a reset
async def main():
    conn = await asyncpg.connect(url)
    tables = [r["tablename"] for r in await conn.fetch("select tablename from pg_tables where schemaname = 'public'")]
    wipe = [t for t in tables if t not in KEEP]
    if wipe:
        await conn.execute("truncate " + ", ".join(f'"{t}"' for t in wipe) + " restart identity cascade")
    await conn.close()
    print(f"Emptied {len(wipe)} tables.")
asyncio.run(main())
PY
  rm -rf "$DEMO_DIR/home/outputs"/* "$DEMO_DIR/data"/*
  say "Demo emptied."
  ((was_up)) && { up; close_tabs; }
}

close_tabs() {
  # The demo Brave keeps one blank tab; the scenes open their own.
  python3 - "$(conf KREWD_CDP_PORT 9223)" <<'PY'
import json, sys, urllib.request
port = sys.argv[1]
base = f"http://127.0.0.1:{port}"
try:
    pages = [t for t in json.load(urllib.request.urlopen(base + "/json/list", timeout=5)) if t.get("type") == "page"]
except OSError:
    sys.exit(0)
urllib.request.urlopen(urllib.request.Request(base + "/json/new?about:blank", method="PUT"), timeout=5)
for tab in pages:
    urllib.request.urlopen(base + "/json/close/" + tab["id"], timeout=5)
print(f"Closed {len(pages)} browser tabs.")
PY
}

token() { need_setup; (cd "$REPO/server" && "$PY" -m krewd_server connect); }

app() {
  local signer apk
  (cd "$REPO/android" && ./gradlew --quiet :app:assembleDemo) || return 1
  apk="$REPO/android/app/build/outputs/apk/demo/app-demo.apk"
  say "Built $apk (app.krewd.demo, screen recording allowed everywhere)"
  if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then adb install -r "$apk"; else say "No phone on adb."; fi
}

case "${1:-status}" in
  setup) setup ;; up|start) up ;; down|stop) down ;; status) status ;; seed) seed ;; reset) reset ;;
  token) token ;; app) app ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
