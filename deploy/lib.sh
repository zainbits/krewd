# Shared helpers for Crew's shell scripts. Source it; it sets REPO, CONFIG_DIR, ENV_FILE and conf().
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_DIR="${CREW_CONFIG_DIR:-$HOME/.config/crew}"
ENV_FILE="$CONFIG_DIR/crew.env"

# conf KEY DEFAULT: one value from crew.env without sourcing it (values hold passwords and URLs).
conf() {
  local value=""
  [[ -f "$ENV_FILE" ]] && value="$(grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2-)"
  value="${value:-$2}"
  [[ "$value" == "~" || "$value" == "~/"* ]] && value="$HOME${value:1}"
  printf '%s' "$value"
}

find_uv() { command -v uv 2>/dev/null || { [[ -x "$HOME/.local/bin/uv" ]] && echo "$HOME/.local/bin/uv"; }; }
find_brave() {
  local b; b="$(conf CREW_BROWSER '')"
  [[ -n "$b" ]] && { echo "$b"; return; }
  for b in brave-browser brave brave-browser-stable; do command -v "$b" 2>/dev/null && return; done
}
