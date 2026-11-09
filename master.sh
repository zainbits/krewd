#!/usr/bin/env bash
# Crew's one entry point. Run it with no arguments for a menu, or name an action:
#   ./master.sh setup | doctor | status | start | stop | restart | logs | attach | token
#               test | update | app | agent-prompt | help
# It never prints secrets except the app token, and only when you ask for it ("token").
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/deploy/lib.sh"
export PATH="$HOME/.local/bin:$PATH"

UNIT_DIR="$HOME/.config/systemd/user"
UNITS=(crew.service crew-browser.service)
B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; N=$'\e[0m'
[[ -t 1 ]] || { B=; G=; Y=; R=; N=; }

say()  { printf '%s\n' "$*"; }
ok()   { printf '  %sok%s    %s\n' "$G" "$N" "$1"; }
warn() { printf '  %swarn%s  %s\n' "$Y" "$N" "$1"; [[ -n "${2:-}" ]] && printf '        fix: %s\n' "$2"; WARNS=$((WARNS + 1)); }
bad()  { printf '  %sFAIL%s  %s\n' "$R" "$N" "$1"; [[ -n "${2:-}" ]] && printf '        fix: %s\n' "$2"; FAILS=$((FAILS + 1)); }
ask()  { local reply; read -r -p "$1 [y/N] " reply; [[ "$reply" =~ ^[Yy] ]]; }
has()  { command -v "$1" >/dev/null 2>&1; }
uvrun() { "$(find_uv)" run --project "$REPO/server" --frozen --quiet "$@"; }
port() { conf CREW_PORT 8800; }
cdp_port() { conf CREW_CDP_PORT 9222; }
linux_systemd() { [[ "$(uname)" == Linux ]] && has systemctl && systemctl --user show-environment >/dev/null 2>&1; }

# ---------------------------------------------------------------- doctor

doctor() {
  FAILS=0; WARNS=0
  say "${B}Tools${N}"
  for t in git python3 tmux curl; do has "$t" && ok "$t" || bad "$t is missing" "install $t with the system package manager"; done
  [[ -n "$(find_uv)" ]] && ok "uv" || bad "uv is missing" "curl -LsSf https://astral.sh/uv/install.sh | sh"
  linux_systemd && ok "systemd user session" || bad "no systemd user session (Crew's services need Linux with systemd)"
  [[ -n "$(find_brave)" ]] && ok "Brave ($(find_brave))" || bad "Brave not found" "install Brave, or set CREW_BROWSER in $ENV_FILE"
  [[ -f "$(signing_file)" ]] && ok "APK signing key ($(signing_file))" \
    || warn "no APK signing key: ./master.sh app makes debug-signed APKs" "put a signing properties file at $(signing_file) (AGENTS.md, new machine step 9)"
  has browser-harness && ok "browser-harness" || bad "browser-harness is missing" "uv tool install browser-harness (AGENTS.md, new machine step 5)"

  say "${B}Config${N} ($CONFIG_DIR)"
  if [[ -d "$CONFIG_DIR" ]]; then
    [[ "$(stat -c %a "$CONFIG_DIR" 2>/dev/null)" == 700 ]] && ok "folder is private" || warn "folder is not mode 700" "chmod 700 $CONFIG_DIR"
  else
    bad "no config folder" "./master.sh setup"
  fi
  for f in crew.env engines.toml token; do
    local path="$CONFIG_DIR/$f"
    if [[ ! -f "$path" ]]; then
      [[ "$f" == token ]] && warn "no token yet (made on first start)" || bad "$f is missing" "./master.sh setup"
    elif [[ "$f" != engines.toml && "$(stat -c %a "$path" 2>/dev/null)" != 600 ]]; then
      warn "$f is not mode 600" "chmod 600 $path"
    else
      ok "$f"
    fi
  done
  [[ -n "$(conf CREW_DATABASE_URL '')" ]] && ok "CREW_DATABASE_URL is set" || bad "CREW_DATABASE_URL is not set" "./master.sh setup"

  say "${B}Server code${N}"
  if [[ -n "$(find_uv)" ]] && uvrun python -c "import crew_server" 2>/dev/null; then ok "Python environment"; else bad "server environment is not installed" "cd server && uv sync"; fi
  if [[ -f "$CONFIG_DIR/engines.toml" ]] && [[ -n "$(find_uv)" ]]; then
    while IFS=$'\t' read -r id status cmd; do
      [[ -z "$id" ]] && continue
      [[ "$status" == ok ]] && ok "engine $id ($cmd)" || bad "engine $id: $cmd is not executable" "install the ACP adapter, AGENTS.md, new machine step 4"
    done < <(uvrun python - "$CONFIG_DIR/engines.toml" <<'PY' 2>/dev/null
import os, shutil, sys
from crew_server.config import load_engines
from pathlib import Path
engines, _ = load_engines(Path(sys.argv[1]))
for e in engines.values():
    cmd = e.command[0]
    path = cmd if os.sep in cmd else shutil.which(cmd)
    print(e.id, "ok" if path and os.access(path, os.X_OK) else "missing", cmd, sep="\t")
PY
)
  fi
  if [[ -n "$(conf CREW_DATABASE_URL '')" && -n "$(find_uv)" ]]; then
    local db; db="$(CREW_CONFIG_DIR="$CONFIG_DIR" uvrun python - <<'PY' 2>&1
import asyncio, os, asyncpg
from crew_server.config import load_env_file
load_env_file()
async def main():
    conn = await asyncpg.connect(os.environ["CREW_DATABASE_URL"], timeout=5)
    v = await conn.fetchval("show server_version")
    await conn.close()
    print("PostgreSQL", v)
try:
    asyncio.run(main())
except Exception as exc:
    print("ERROR", type(exc).__name__)
PY
)"
    [[ "$db" == PostgreSQL* ]] && ok "database reachable ($db)" || bad "database not reachable ($db)" "start PostgreSQL, or check CREW_DATABASE_URL"
  fi

  say "${B}Services${N}"
  if linux_systemd; then
    [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" == yes ]] && ok "lingering on (Crew starts at boot)" \
      || warn "lingering is off: Crew starts only after you log in" "sudo loginctl enable-linger $USER"
    for u in "${UNITS[@]}"; do
      if [[ ! -f "$UNIT_DIR/$u" ]]; then bad "$u is not installed" "./master.sh setup"; continue; fi
      local en act; en="$(systemctl --user is-enabled "$u" 2>/dev/null)"; act="$(systemctl --user is-active "$u" 2>/dev/null)"
      [[ "$en" == enabled ]] || warn "$u is $en" "systemctl --user enable $u"
      if [[ "$act" == active ]]; then ok "$u active"
      elif [[ "$act" == failed ]]; then bad "$u gave up after restarting 6 times in 10 minutes" "./master.sh logs, fix the cause, then ./master.sh start"
      else bad "$u is $act" "./master.sh start; then ./master.sh logs"; fi
    done
  fi
  curl -fsS -m 3 "http://127.0.0.1:$(port)/api/health" >/dev/null 2>&1 && ok "server answers on port $(port)" \
    || bad "server does not answer on port $(port)" "./master.sh logs"
  curl -fsS -m 3 "http://127.0.0.1:$(cdp_port)/json/version" >/dev/null 2>&1 && ok "agent Brave answers on DevTools port $(cdp_port)" \
    || bad "agent Brave is not running" "log in to the desktop, then ./master.sh start browser"
  if has tailscale; then
    tailscale serve status 2>/dev/null | grep -q ":$(port)" && ok "Tailscale Serve publishes port $(port)" \
      || warn "Tailscale Serve does not publish Crew" "tailscale serve --bg --https=$(port) http://127.0.0.1:$(port)"
  else
    warn "Tailscale is not installed: the phone works only on the home network" "install Tailscale"
  fi

  say "${B}Agent setup${N}"
  local workdir; workdir="$(conf CREW_WORKDIR "$HOME")"
  grep -qs "Crew" "$workdir/AGENTS.md" && ok "$workdir/AGENTS.md has Crew's instructions" \
    || warn "$workdir/AGENTS.md has no Crew section" "copy deploy/agents-section.md into it"
  [[ -e "$HOME/.claude/skills/browser-harness" || -e "$HOME/.agents/skills/browser-harness" ]] && ok "browser-harness skill is installed" \
    || warn "browser-harness skill not found in ~/.agents/skills or ~/.claude/skills" "AGENTS.md, new machine step 5"

  say ""
  if ((FAILS)); then say "${R}$FAILS problem(s)${N}, $WARNS warning(s)."; else say "${G}All good${N} ($WARNS warning(s))."; fi
  return $((FAILS > 0))
}

# ---------------------------------------------------------------- setup

install_units() {
  mkdir -p "$UNIT_DIR"
  for u in "${UNITS[@]}"; do
    sed -e "s#@REPO@#$REPO#g" -e "s#@CDP_PORT@#$(cdp_port)#g" "$REPO/deploy/systemd/$u" > "$UNIT_DIR/$u"
  done
  systemctl --user daemon-reload
  systemctl --user enable "${UNITS[@]}" >/dev/null 2>&1
  say "Installed and enabled: ${UNITS[*]}"
}

setup() {
  say "${B}Crew setup${N} on $(hostname). Safe to run again; it keeps existing files."
  (umask 077; mkdir -p "$CONFIG_DIR"); chmod 700 "$CONFIG_DIR"
  if [[ ! -f "$ENV_FILE" ]]; then
    (umask 077; cp "$REPO/deploy/crew.env.example" "$ENV_FILE")
    say "Created $ENV_FILE from the example."
    local url
    read -r -s -p "PostgreSQL URL for Crew (postgresql://user:pass@host:5432/crew; Enter to edit the file later): " url; echo
    if [[ -n "$url" ]]; then
      URL="$url" python3 - "$ENV_FILE" <<'PY'
import os, sys
p = sys.argv[1]
lines = [("CREW_DATABASE_URL=" + os.environ["URL"]) if l.startswith("CREW_DATABASE_URL=") else l
         for l in open(p).read().splitlines()]
open(p, "w").write("\n".join(lines) + "\n")
PY
    fi
  else
    say "Keeping $ENV_FILE."
  fi
  chmod 600 "$ENV_FILE"
  [[ -f "$CONFIG_DIR/engines.toml" ]] || { cp "$REPO/deploy/engines.example.toml" "$CONFIG_DIR/engines.toml"; say "Created engines.toml (Claude Code)."; }
  if [[ -n "$(find_uv)" ]]; then (cd "$REPO/server" && "$(find_uv)" sync --frozen --quiet) && say "Server environment ready."; else say "Install uv first, then run setup again."; fi
  mkdir -p "$(conf CREW_BROWSER_PROFILE "$HOME/.local/share/crew/agent-brave")"
  if linux_systemd; then
    install_units
    [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" == yes ]] \
      || say "${Y}To start Crew at boot without logging in, run once:${N} sudo loginctl enable-linger $USER"
    if ask "Start Crew now?"; then start all; fi
  fi
  say ""
  doctor
  say ""
  say "Next: open the Crew app, Settings, enter this machine's address and the token (./master.sh token)."
}

# ---------------------------------------------------------------- run

pick_units() {
  case "${1:-all}" in
    server|crew) echo crew.service ;;
    browser|brave) echo crew-browser.service ;;
    all|"") echo "${UNITS[@]}" ;;
    *) say "Unknown part '$1' (server, browser or all)" >&2; return 1 ;;
  esac
}
start()   { local u; u="$(pick_units "${1:-}")" || return 1; systemctl --user reset-failed $u 2>/dev/null; systemctl --user start $u && status; }
stop()    { local u; u="$(pick_units "${1:-}")" || return 1; systemctl --user stop $u && say "Stopped: $u"; }
restart() { local u; u="$(pick_units "${1:-}")" || return 1; systemctl --user reset-failed $u 2>/dev/null; systemctl --user restart $u && status; }

status() {
  for u in "${UNITS[@]}"; do printf '%-22s %s\n' "$u" "$(systemctl --user is-active "$u" 2>/dev/null)"; done
  curl -fsS -m 3 "http://127.0.0.1:$(port)/api/health" 2>/dev/null && echo || say "server: not answering on $(port)"
  local ip; ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  say "Home network: http://${ip:-<this machine>}:$(port)"
  if has tailscale; then
    local dns; dns="$(tailscale status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null)"
    [[ -n "$dns" ]] && say "Tailscale:    https://$dns:$(port)"
  fi
  say "Watch:        tmux attach -t crew   |   tmux attach -t agent-brave   (detach: Ctrl+B, then D)"
}

logs() {
  local which="${1:-server}" session=crew unit=crew.service
  [[ "$which" == browser || "$which" == brave ]] && { session=agent-brave; unit=crew-browser.service; }
  say "${B}systemd ($unit)${N}"; journalctl --user -u "$unit" -n 15 --no-pager -o short-iso 2>/dev/null
  say "${B}tmux $session (last 40 lines)${N}"; tmux capture-pane -p -J -S -40 -t "=$session:" 2>/dev/null || say "(no tmux session)"
}

attach() { local s=crew; [[ "${1:-}" == browser || "${1:-}" == brave ]] && s=agent-brave; say "Detach with Ctrl+B, then D."; tmux attach -t "=$s"; }

token() {
  say "The app's token (Settings > Token). Keep it private."
  uvrun python -m crew_server token
}

run_tests() {
  [[ -n "$(conf CREW_TEST_DATABASE_URL '')" ]] || { say "Set CREW_TEST_DATABASE_URL in $ENV_FILE (a throwaway database) first."; return 1; }
  (cd "$REPO/server" && "$(find_uv)" run --frozen pytest -q)
}

update() {
  git -C "$REPO" pull --ff-only || return 1
  (cd "$REPO/server" && "$(find_uv)" sync --frozen --quiet)
  linux_systemd && install_units && restart all
}

signing_file() { printf '%s' "${ANDROID_SIGNING_PROPERTIES:-$HOME/.android/keystores/release.properties}"; }

app() {
  local variant=release apk
  if [[ ! -f "$(signing_file)" ]]; then
    say "${Y}No signing key at $(signing_file); building a debug APK (Android debug key).${N}"
    variant=debug
  fi
  local task=":app:assembleRelease"; [[ "$variant" == debug ]] && task=":app:assembleDebug"
  (cd "$REPO/android" && ./gradlew --quiet "$task") || return 1
  apk="$REPO/android/app/build/outputs/apk/$variant/app-$variant.apk"
  say "Built $apk"
  local signer; signer="$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1)"
  [[ -n "$signer" ]] && "$signer" verify --print-certs "$apk" 2>/dev/null | grep -m1 "SHA-256" | sed 's/^/Signed: /'
  if has adb && adb get-state >/dev/null 2>&1; then
    ask "Install on the connected phone ($(adb get-serialno))?" && adb install -r "$apk"
  else
    say "No phone on adb. Copy the APK to the phone, or connect it and run this again."
  fi
}

# ---------------------------------------------------------------- agent help

copy_text() {
  if [[ "$(uname)" == Darwin ]] && has pbcopy; then pbcopy; return; fi
  if [[ -n "${WAYLAND_DISPLAY:-}" ]] && has wl-copy; then wl-copy; return; fi
  if [[ -n "${DISPLAY:-}" ]] && has xclip; then xclip -selection clipboard; return; fi
  if [[ -n "${DISPLAY:-}" ]] && has xsel; then xsel --clipboard --input; return; fi
  # Over SSH: OSC 52 asks your own terminal to put the text on your computer's clipboard.
  local data; data="$(base64 | tr -d '\n')"
  if [[ -n "${TMUX:-}" ]]; then printf '\ePtmux;\e\e]52;c;%s\a\e\\' "$data" > /dev/tty; else printf '\e]52;c;%s\a' "$data" > /dev/tty; fi
}

agent_prompt() {
  local report; report="$(doctor 2>&1 | sed $'s/\e\\[[0-9;]*m//g')"
  local prompt
  prompt="$(cat <<EOF
You are helping me set up, check or fix Crew on this machine ($(hostname), $(uname -sr)).
Crew is my self-hosted agent team: a server that runs coding agents (Claude Code, Codex or any ACP agent) for my phone app.

- Repository: $REPO (read its AGENTS.md first; it explains every part and how to set it up).
- The one script for everything is $REPO/master.sh: run ./master.sh doctor to see what is wrong, ./master.sh help for all actions.
- Config lives in $CONFIG_DIR (crew.env, engines.toml, token). Never print or paste secrets from it (database passwords, the token).
- Use sudo only in a tmux session where I type the password myself. Ask me before anything consequential.
- When you are done, run ./master.sh doctor again and tell me what changed and what is still open.

Current doctor report:
$report

What I need:
EOF
)"
  if printf '%s\n' "$prompt" | copy_text 2>/dev/null; then
    say "Copied a prompt for Claude Code, Codex or any agent. Paste it into the agent session and add what you need at the end."
  else
    say "Could not reach a clipboard; here is the prompt to copy:"; say ""; say "$prompt"
  fi
}

# ---------------------------------------------------------------- menu

usage() {
  cat <<EOF
${B}Crew master script${N} ($REPO)

  setup          first-time setup on this machine (safe to rerun)
  doctor         check everything and say how to fix what is wrong
  status         services, health and the app's addresses
  start|stop|restart [server|browser|all]
  logs [server|browser]     recent output
  attach [server|browser]   watch live in tmux (detach: Ctrl+B, then D)
  token          show the token the phone app needs
  test           run the server tests
  update         pull the latest code, reinstall, restart
  app            build the Android app and install it over adb
  agent-prompt   copy a ready prompt to paste into Claude Code, Codex or any agent
EOF
}

menu() {
  usage; say ""
  local choices=(doctor status setup start stop restart logs attach token test update app agent-prompt quit)
  PS3="Pick a number: "
  select c in "${choices[@]}"; do
    [[ "$c" == quit || -z "$c" ]] && return
    say ""; dispatch "$c"; return
  done
}

dispatch() {
  local action="${1:-}"; shift || true
  case "$action" in
    setup) setup ;;
    doctor|check) doctor ;;
    status) status ;;
    start) start "$@" ;;
    stop) stop "$@" ;;
    restart) restart "$@" ;;
    logs) logs "$@" ;;
    attach) attach "$@" ;;
    token) token ;;
    test) run_tests ;;
    update) update ;;
    app) app ;;
    agent-prompt|agent|help-agent) agent_prompt ;;
    help|-h|--help) usage ;;
    "") menu ;;
    *) say "Unknown action '$action'."; usage; return 1 ;;
  esac
}

dispatch "$@"
