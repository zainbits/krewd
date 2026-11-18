#!/usr/bin/env bash
# Krewd's one entry point. Run it with no arguments for a menu, or name an action:
#   ./master.sh setup | doctor | status | start | stop | restart | logs | attach | token
#               test | update | app | demo | agent-prompt | help
# It never prints secrets except the app token, and only when you ask for it ("token").
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/deploy/lib.sh"
export PATH="$HOME/.local/bin:$PATH"

UNIT_DIR="$HOME/.config/systemd/user"
UNITS=(krewd.service krewd-browser.service)
B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; N=$'\e[0m'
[[ -t 1 ]] || { B=; G=; Y=; R=; N=; }

say()  { printf '%s\n' "$*"; }
ok()   { printf '  %sok%s    %s\n' "$G" "$N" "$1"; }
warn() { printf '  %swarn%s  %s\n' "$Y" "$N" "$1"; [[ -n "${2:-}" ]] && printf '        fix: %s\n' "$2"; WARNS=$((WARNS + 1)); }
bad()  { printf '  %sFAIL%s  %s\n' "$R" "$N" "$1"; [[ -n "${2:-}" ]] && printf '        fix: %s\n' "$2"; FAILS=$((FAILS + 1)); }
ask()  { local reply; read -r -p "$1 [y/N] " reply; [[ "$reply" =~ ^[Yy] ]]; }
has()  { command -v "$1" >/dev/null 2>&1; }
# The Krewd hook (deploy/browser-harness/agent_helpers.py) patches browser-harness internals: check it still applies.
harness_hook_works() {
  local exe py
  exe="$(command -v browser-harness)" || return 1
  py="$(head -1 "$exe" | sed 's/^#!//')"
  [[ -x "$py" ]] || return 1
  env -u BU_NAME -u CODEX_THREAD_ID BH_AGENT_WORKSPACE="$REPO/deploy/browser-harness" CLAUDE_CODE_SESSION_ID=doctor-check BH_TELEMETRY=0 \
    "$py" -I -c 'import sys, browser_harness.helpers as h, browser_harness.admin as a
sys.exit(0 if getattr(h, "_krewd_hooked", False) and h.NAME == a.NAME == "krewd-doctor-check" and h._send.__name__ == "krewd_send" else 1)' >/dev/null 2>&1
}
uvrun() { "$(find_uv)" run --project "$REPO/server" --frozen --quiet "$@"; }
port() { conf KREWD_PORT 8800; }
cdp_port() { conf KREWD_CDP_PORT 9222; }
# "virtual" when the agents' Brave draws on its own Xvfb screen, else the display it uses (or "unknown").
brave_display() {
  local pid d
  pid="$(pgrep -f -- "--remote-debugging-port=$(cdp_port)( |$)" | head -n 1)"
  [[ -n "$pid" ]] || { echo unknown; return; }
  d="$(tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null | sed -n 's/^DISPLAY=//p')"
  pgrep -f "^Xvfb $d( |$)" >/dev/null 2>&1 && echo virtual || echo "${d:-unknown}"
}
# Files earlier versions made that nothing uses now.
tidy_old() {
  [[ -d "$CONFIG_DIR/fixed-location" ]] && rm -rf "$CONFIG_DIR/fixed-location" && say "Removed $CONFIG_DIR/fixed-location (the location extension now loads from the repo)."
  return 0
}
# Xvfb for the agents' Brave (KREWD_BROWSER_DISPLAY=virtual, the default). Needs sudo, so only when the owner says yes.
offer_xvfb() {
  [[ "$(conf KREWD_BROWSER_DISPLAY virtual)" == virtual ]] || return 0
  has Xvfb && has xauth && has mcookie && return 0
  if has apt-get && [[ -t 0 ]] && ask "Install Xvfb, so the agents' Brave gets its own invisible screen (sudo apt-get install xvfb xauth)?"; then
    sudo apt-get install -y xvfb xauth
  else
    say "${Y}Without Xvfb the agents' Brave opens on the desktop and can take focus there:${N} sudo apt install xvfb xauth"
  fi
}
linux_systemd() { [[ "$(uname)" == Linux ]] && has systemctl && systemctl --user show-environment >/dev/null 2>&1; }

# ---------------------------------------------------------------- doctor

doctor() {
  FAILS=0; WARNS=0
  say "${B}Tools${N}"
  for t in git python3 tmux curl; do has "$t" && ok "$t" || bad "$t is missing" "install $t with the system package manager"; done
  [[ -n "$(find_uv)" ]] && ok "uv" || bad "uv is missing" "curl -LsSf https://astral.sh/uv/install.sh | sh"
  linux_systemd && ok "systemd user session" || bad "no systemd user session (Krewd's services need Linux with systemd)"
  [[ -n "$(find_brave)" ]] && ok "Brave ($(find_brave))" || bad "Brave not found" "install Brave, or set KREWD_BROWSER in $ENV_FILE"
  [[ -f "$(signing_file)" ]] && ok "APK signing key ($(signing_file))" \
    || warn "no APK signing key: ./master.sh app makes debug-signed APKs" "put a signing properties file at $(signing_file) (AGENTS.md, new machine step 10)"
  has browser-harness && ok "browser-harness" || bad "browser-harness is missing" "uv tool install browser-harness (AGENTS.md, new machine step 5)"
  if has browser-harness; then
    harness_hook_works && ok "browser-harness loads Krewd's tab hook (a daemon per chat, tab claims)" \
      || bad "browser-harness does not load deploy/browser-harness/agent_helpers.py as Krewd expects: chats would not lock their tabs" \
             "check the hook against the installed browser-harness (uv tool list), see AGENTS.md, The agents' browser"
  fi
  if [[ "$(conf KREWD_BROWSER_DISPLAY virtual)" == virtual ]]; then
    if has Xvfb && has xauth && has mcookie; then ok "Xvfb (the agents' Brave gets its own invisible screen)"
    else warn "Xvfb or xauth is missing: the agents' Brave falls back to a window on the desktop" "sudo apt install xvfb xauth (AGENTS.md, new machine step 5)"; fi
  fi

  say "${B}Config${N} ($CONFIG_DIR)"
  if [[ -d "$CONFIG_DIR" ]]; then
    [[ "$(stat -c %a "$CONFIG_DIR" 2>/dev/null)" == 700 ]] && ok "folder is private" || warn "folder is not mode 700" "chmod 700 $CONFIG_DIR"
  else
    bad "no config folder" "./master.sh setup"
  fi
  for f in krewd.env engines.toml token; do
    local path="$CONFIG_DIR/$f"
    if [[ ! -f "$path" ]]; then
      [[ "$f" == token ]] && warn "no token yet (made on first start)" || bad "$f is missing" "./master.sh setup"
    elif [[ "$f" != engines.toml && "$(stat -c %a "$path" 2>/dev/null)" != 600 ]]; then
      warn "$f is not mode 600" "chmod 600 $path"
    else
      ok "$f"
    fi
  done
  [[ -n "$(conf KREWD_DATABASE_URL '')" ]] && ok "KREWD_DATABASE_URL is set" || bad "KREWD_DATABASE_URL is not set" "./master.sh setup"

  say "${B}Server code${N}"
  if [[ -n "$(find_uv)" ]] && uvrun python -c "import krewd_server" 2>/dev/null; then ok "Python environment"; else bad "server environment is not installed" "cd server && uv sync"; fi
  if [[ -f "$CONFIG_DIR/engines.toml" ]] && [[ -n "$(find_uv)" ]]; then
    while IFS=$'\t' read -r id status cmd; do
      [[ -z "$id" ]] && continue
      [[ "$status" == ok ]] && ok "engine $id ($cmd)" || bad "engine $id: $cmd is not executable" "install the ACP adapter, AGENTS.md, new machine step 4"
    done < <(uvrun python - "$CONFIG_DIR/engines.toml" <<'PY' 2>/dev/null
import os, shutil, sys
from krewd_server.config import load_engines
from pathlib import Path
engines, _ = load_engines(Path(sys.argv[1]))
for e in engines.values():
    cmd = e.command[0]
    path = cmd if os.sep in cmd else shutil.which(cmd)
    print(e.id, "ok" if path and os.access(path, os.X_OK) else "missing", cmd, sep="\t")
PY
)
  fi
  if [[ -f "$CONFIG_DIR/engines.toml" && -n "$(find_uv)" ]]; then
    # Sign-ins (each agent's own subscription) and profiles (each agent on each gateway, checked by asking the
    # gateway for the key's models; no tokens spent).
    local keys="$CONFIG_DIR/keys.env" checked
    [[ ! -f "$keys" || "$(stat -c %a "$keys" 2>/dev/null)" == 600 ]] || warn "keys.env is not mode 600" "chmod 600 $keys"
    if checked="$(KREWD_CONFIG_DIR="$CONFIG_DIR" uvrun python -m krewd_server.profiles check 2>/dev/null)"; then
      while IFS=$'\t' read -r status id detail; do
        [[ -z "$status" ]] && continue
        case "$status" in
          ok) ok "$( [[ "$id" == *" for "* ]] && echo profile || echo agent ) $id: $detail" ;;
          warn) warn "$( [[ "$id" == *" for "* ]] && echo profile || echo agent ) $id: $detail" ;;
          *) bad "profile $id: $detail" "fix the key or URL in the app (Settings > Profiles), or set CODEX_PATH (AGENTS.md, Profiles)" ;;
        esac
      done <<< "$checked"
    else
      bad "sign-ins and profiles could not be checked" "cd $REPO/server && uv run python -m krewd_server.profiles check"
    fi
  fi
  if [[ -n "$(conf KREWD_DATABASE_URL '')" && -n "$(find_uv)" ]]; then
    local db; db="$(KREWD_CONFIG_DIR="$CONFIG_DIR" uvrun python - <<'PY' 2>&1
import asyncio, os, asyncpg
from krewd_server.config import load_env_file
load_env_file()
async def main():
    conn = await asyncpg.connect(os.environ["KREWD_DATABASE_URL"], timeout=5)
    v = await conn.fetchval("show server_version")
    await conn.close()
    print("PostgreSQL", v)
try:
    asyncio.run(main())
except Exception as exc:
    print("ERROR", type(exc).__name__)
PY
)"
    [[ "$db" == PostgreSQL* ]] && ok "database reachable ($db)" || bad "database not reachable ($db)" "start PostgreSQL, or check KREWD_DATABASE_URL"
  fi

  say "${B}Services${N}"
  if linux_systemd; then
    [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" == yes ]] && ok "lingering on (Krewd starts at boot)" \
      || warn "lingering is off: Krewd starts only after you log in" "sudo loginctl enable-linger $USER"
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
  if [[ -f "$CONFIG_DIR/owner.json" ]]; then
    [[ "$(stat -c %a "$CONFIG_DIR/owner.json" 2>/dev/null)" == 600 ]] && ok "owner key registered (phones pair with it)" \
      || warn "owner.json is not mode 600" "chmod 600 $CONFIG_DIR/owner.json"
  else
    warn "no owner key yet: anyone with the setup token can use Krewd" "in the app: Settings > Security > Set up with security key (or passkey)"
  fi
  local rp; rp="$(conf KREWD_PASSKEY_RP_ID "")"
  if [[ -n "$rp" ]]; then
    if curl -fsS -m 5 "https://$rp/.well-known/assetlinks.json" 2>/dev/null | grep -q '"app.krewd"'; then ok "passkeys on ($rp serves assetlinks.json for app.krewd)"
    else warn "passkeys: https://$rp/.well-known/assetlinks.json does not name app.krewd, so Android refuses the passkey" "publish the statement from curl -s http://127.0.0.1:$(port)/.well-known/assetlinks.json there"; fi
    [[ -n "$(conf KREWD_PASSKEY_CERTS "")" ]] || bad "KREWD_PASSKEY_RP_ID is set but KREWD_PASSKEY_CERTS is not" "add the app's signing certificate SHA-256 to $ENV_FILE"
  fi
  if [[ "$(conf KREWD_TLS auto)" == off ]]; then
    warn "KREWD_TLS=off: the home network gets plain HTTP, readable by anyone on it" "remove KREWD_TLS from krewd.env, then ./master.sh restart"
  elif [[ -f "$(conf KREWD_TLS_CERT "$CONFIG_DIR/tls.crt")" && -f "$(conf KREWD_TLS_KEY "$CONFIG_DIR/tls.key")" ]]; then
    [[ "$(stat -c %a "$(conf KREWD_TLS_KEY "$CONFIG_DIR/tls.key")" 2>/dev/null)" == 600 ]] && ok "HTTPS certificate (pin $(uvrun python -m krewd_server pin | cut -c1-19)...)" \
      || warn "the HTTPS key is not mode 600" "chmod 600 $(conf KREWD_TLS_KEY "$CONFIG_DIR/tls.key")"
    local lan_ip; lan_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    if [[ -n "$lan_ip" && "$(conf KREWD_HOST 127.0.0.1,lan)" == *lan* ]]; then
      curl -kfsS -m 3 "https://$lan_ip:$(port)/api/health" >/dev/null 2>&1 && ok "HTTPS answers on the home network ($lan_ip)" \
        || bad "no HTTPS on the home network address" "./master.sh restart, then ./master.sh logs"
    fi
  else
    warn "no HTTPS certificate yet (made on first start)" "./master.sh restart"
  fi
  if curl -fsS -m 3 "http://127.0.0.1:$(cdp_port)/json/version" >/dev/null 2>&1; then
    ok "agent Brave answers on DevTools port $(cdp_port)"
    local shown; shown="$(brave_display)"
    if [[ "$(conf KREWD_BROWSER_DISPLAY virtual)" == virtual ]]; then
      [[ "$shown" == virtual ]] && ok "agent Brave is on its own screen (not on the desktop)" \
        || warn "agent Brave is on the desktop display ($shown), so it can take focus there" "sudo apt install xvfb xauth, then ./master.sh restart browser"
    fi
    case "$(uvrun python -m krewd_server.locations check "$(cdp_port)" 2>/dev/null)" in
      set) ok "the location extension answers (a location is applied)" ;;
      none) warn "no browser location applied: pages that ask for one wait" "the app: Settings > Browser location (or KREWD_BROWSER_LOCATION in $ENV_FILE)" ;;
      *) bad "the location extension does not answer in the agents' Brave" "./master.sh restart browser; it loads a copy of deploy/fixed-location from the config folder (location-extension)" ;;
    esac
  else
    bad "agent Brave is not running" "log in to the desktop, then ./master.sh start browser"
  fi
  if has tailscale; then
    tailscale serve status 2>/dev/null | grep -q ":$(port)" && ok "Tailscale Serve publishes port $(port)" \
      || warn "Tailscale Serve does not publish Krewd" "tailscale serve --bg --https=$(port) http://127.0.0.1:$(port)"
  else
    say "  info  no Tailscale: the phone reaches Krewd on the home network, or an HTTPS address in KREWD_URLS"
  fi

  say "${B}Agent setup${N}"
  local workdir; workdir="$(conf KREWD_WORKDIR "$HOME")"
  grep -qs "Krewd" "$workdir/AGENTS.md" && ok "$workdir/AGENTS.md has Krewd's instructions" \
    || warn "$workdir/AGENTS.md has no Krewd section" "copy deploy/agents-section.md into it"
  grep -qs "krewd routines" "$workdir/AGENTS.md" && ok "Krewd's agent instructions cover routines" \
    || warn "$workdir/AGENTS.md has an older Krewd section (no routines)" "replace it with deploy/agents-section.md"
  grep -qs "krewd side propose" "$workdir/AGENTS.md" && ok "Krewd's agent instructions cover side sessions and sub-agents" \
    || warn "$workdir/AGENTS.md has an older Krewd section (no side sessions)" "add the side-session rules from deploy/agents-section.md"
  [[ "$(readlink -f "$HOME/.local/bin/krewd" 2>/dev/null)" == "$(readlink -f "$REPO/deploy/krewd-cli.sh")" ]] && ok "krewd command (~/.local/bin/krewd)" \
    || warn "the krewd command is not installed" "./master.sh setup (links ~/.local/bin/krewd to deploy/krewd-cli.sh)"
  [[ -e "$HOME/.agents/skills/browser-harness" ]] && ok "browser-harness skill is installed" \
    || warn "browser-harness skill not found in ~/.agents/skills" "AGENTS.md, new machine step 5"
  [[ -d "$HOME/.agents/skills" && "$(readlink -f "$HOME/.claude/skills" 2>/dev/null)" == "$(readlink -f "$HOME/.agents/skills")" ]] \
    && ok "Claude Code shares ~/.agents/skills (~/.claude/skills links to it)" \
    || warn "~/.claude/skills is not a link to ~/.agents/skills, so Claude misses shared skills" "AGENTS.md, new machine step 5"

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
  say "${B}Krewd setup${N} on $(hostname). Safe to run again; it keeps existing files."
  (umask 077; mkdir -p "$CONFIG_DIR"); chmod 700 "$CONFIG_DIR"
  if [[ ! -f "$ENV_FILE" ]]; then
    (umask 077; cp "$REPO/deploy/krewd.env.example" "$ENV_FILE")
    say "Created $ENV_FILE from the example."
    local url
    read -r -s -p "PostgreSQL URL for Krewd (postgresql://user:pass@host:5432/krewd; Enter to edit the file later): " url; echo
    if [[ -n "$url" ]]; then
      URL="$url" python3 - "$ENV_FILE" <<'PY'
import os, sys
p = sys.argv[1]
lines = [("KREWD_DATABASE_URL=" + os.environ["URL"]) if l.startswith("KREWD_DATABASE_URL=") else l
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
  mkdir -p "$(conf KREWD_BROWSER_PROFILE "$HOME/.local/share/krewd/agent-brave")"
  offer_xvfb
  tidy_old
  mkdir -p "$HOME/.local/bin" && ln -sfn "$REPO/deploy/krewd-cli.sh" "$HOME/.local/bin/krewd" && say "Linked the krewd command: ~/.local/bin/krewd."
  if linux_systemd; then
    install_units
    [[ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" == yes ]] \
      || say "${Y}To start Krewd at boot without logging in, run once:${N} sudo loginctl enable-linger $USER"
    if ask "Start Krewd now?"; then start all; fi
  fi
  say ""
  doctor
  say ""
  say "Next: ./master.sh token, then scan its code with the phone (or open its link there) to connect the app."
}

# ---------------------------------------------------------------- run

pick_units() {
  case "${1:-all}" in
    server|krewd) echo krewd.service ;;
    browser|brave) echo krewd-browser.service ;;
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
  [[ "$(conf KREWD_TLS auto)" == off ]] && say "Home network: http://${ip:-<this machine>}:$(port)" || say "Home network: https://${ip:-<this machine>}:$(port)"
  if has tailscale; then
    local dns; dns="$(tailscale status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null)"
    [[ -n "$dns" ]] && say "Tailscale:    https://$dns:$(port)"
  fi
  say "Watch:        tmux attach -t krewd   |   tmux attach -t agent-brave   (detach: Ctrl+B, then D)"
}

logs() {
  local which="${1:-server}" session=krewd unit=krewd.service
  [[ "$which" == browser || "$which" == brave ]] && { session=agent-brave; unit=krewd-browser.service; }
  say "${B}systemd ($unit)${N}"; journalctl --user -u "$unit" -n 15 --no-pager -o short-iso 2>/dev/null
  say "${B}tmux $session (last 40 lines)${N}"; tmux capture-pane -p -J -S -40 -t "=$session:" 2>/dev/null || say "(no tmux session)"
}

attach() { local s=krewd; [[ "${1:-}" == browser || "${1:-}" == brave ]] && s=agent-brave; say "Detach with Ctrl+B, then D."; tmux attach -t "=$s"; }

security() {
  # The owner's phones and security key: status, revoke PHONE, reset (lost phone and key: back to setup).
  "$REPO/deploy/krewd-cli.sh" security "${@:-status}"
}

token() {
  # The connect code: the app's addresses, the certificate pin and the token, as a QR code and a link.
  uvrun python -m krewd_server connect
}

run_tests() {
  [[ -n "$(conf KREWD_TEST_DATABASE_URL '')" ]] || { say "Set KREWD_TEST_DATABASE_URL in $ENV_FILE (a throwaway database) first."; return 1; }
  (cd "$REPO/server" && "$(find_uv)" run --frozen pytest -q)
}

update() {
  git -C "$REPO" pull --ff-only || return 1
  # The pull may have changed this script; bash keeps running the copy it already read, so run the new one.
  exec "$REPO/master.sh" update-finish
}

update_finish() {
  (cd "$REPO/server" && "$(find_uv)" sync --frozen --quiet) || return 1
  offer_xvfb
  tidy_old
  if linux_systemd; then
    install_units
    restart all
  fi
  say "Checking (Brave and the server take a few seconds to come back)..."
  sleep 8
  doctor
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
You are helping me set up, check or fix Krewd on this machine ($(hostname), $(uname -sr)).
Krewd is my self-hosted agent team: a server that runs coding agents (Claude Code, Codex or any ACP agent) for my phone app.

- Repository: $REPO (read its AGENTS.md first; it explains every part and how to set it up).
- The one script for everything is $REPO/master.sh: run ./master.sh doctor to see what is wrong, ./master.sh help for all actions.
- Config lives in $CONFIG_DIR (krewd.env, engines.toml, profiles.toml, keys.env, token). Never print or paste secrets from it (database passwords, gateway keys, the token).
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
${B}Krewd master script${N} ($REPO)

  setup          first-time setup on this machine (safe to rerun)
  doctor         check everything and say how to fix what is wrong
  status         services, health and the app's addresses
  start|stop|restart [server|browser|all]
  logs [server|browser]     recent output
  attach [server|browser]   watch live in tmux (detach: Ctrl+B, then D)
  token          show the code (QR and link) that connects the phone app
  security       the owner's phones and security key: status | revoke PHONE | reset
  test           run the server tests
  update         pull the latest code, reinstall, restart
  app            build the Android app and install it over adb
  demo ACTION    the throwaway demo server for screenshots and videos:
                 setup | up | down | seed | reset | status | token | app
  agent-prompt   copy a ready prompt to paste into Claude Code, Codex or any agent
EOF
}

menu() {
  usage; say ""
  local choices=(doctor status setup start stop restart logs attach token security test update app agent-prompt quit)
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
    security) security "$@" ;;
    test) run_tests ;;
    update) update ;;
    update-finish) update_finish ;;  # the second half of update, run by the freshly pulled script
    app) app ;;
    demo) "$REPO/demo/demo.sh" "$@" ;;
    agent-prompt|agent|help-agent) agent_prompt ;;
    help|-h|--help) usage ;;
    "") menu ;;
    *) say "Unknown action '$action'."; usage; return 1 ;;
  esac
}

dispatch "$@"
