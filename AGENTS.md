# Crew: guide for coding agents

Crew is a self-hosted, agent-agnostic agent team. The owner asks for a task in the Crew Android app. On their home Linux machine, a coding agent such as Claude Code, Codex or any ACP agent does the work. It uses that machine's terminal and its own Brave through browser-harness, and it asks the owner before anything consequential.

`CLAUDE.md` is a symlink to this file, so every agent reads the same guide.

## Start here: `./master.sh`

`master.sh` is the one command to remember. Run it with no arguments for a menu, or name an action: `setup`, `doctor`, `status`, `start`, `stop`, `restart`, `logs`, `attach`, `token`, `test`, `update`, `app`, `agent-prompt`.

`setup` also links the `crew` command (`deploy/crew-cli.sh`) as `~/.local/bin/crew`: the command line the agents use to manage routines.

- Setting up, moving or fixing a machine: run `./master.sh doctor` first. Fix what it reports, then run it again. Every FAIL line comes with a fix.
- When you add a setup step, a dependency, a service or a check, add it to `master.sh` (`setup` and `doctor`) and to this file in the same change. A manual step that only lives in chat is lost.
- `agent-prompt` only copies a ready-made prompt for the owner to paste into an agent session. Keep it that way. It never starts an agent.

## Layout

- `server/`: the Crew server, `crew_server` (Python 3.12, FastAPI, asyncpg, managed with uv).
  - `acp.py`: JSON-RPC over stdio to one agent process per engine.
  - `hub.py`: sessions, turns, approvals, config options, and messages: stored first, then steered into the running turn (ACP `_session/steering`, only where the agent advertises it), queued, or sent after an interrupt; queued messages survive a restart. It also links a browser tab to the session whose agent is using it, has the emergency restart, compacts a chat (the agent's own `/compact` where it advertises one, else a handover to a fresh agent session), switches a chat between agents (each agent keeps its own session in the chat; the outgoing one writes a handover and a returning one hears only what it missed), and runs routines: a scheduler loop fires due triggers (several named cron triggers per routine, plus bounded follow-ups a run asks for) as messages in each routine's own chat, with the routine's notes, and tracks every run and attempt.
  - `routines.py`: cron schedules (cronsim, with `L` and `LW` for month ends) in a timezone, their reading in words, catch-up after downtime, and the prompt each run sends (date facts plus the instructions).
  - `cli.py`: the `crew` command for agents and the owner (`crew routines ...`, `crew schedule ...`), over the local HTTP API.
  - `api.py`: HTTP API (see `docs/api.md`).
  - `profiles.py`: profiles, an agent on a gateway instead of its own login (see Profiles below): the gateway's model list (cached a minute), the Codex model catalog built from it, the environment each profile engine starts with, and `python -m crew_server.profiles check` for doctor.
  - `attachments.py`: uploads from the phone, saved as `~/.crew/common/attachments/<session id prefix>-<name>` and passed to the agent as paths and ACP resource links.
  - `files.py`: files the agents hand back: paths in a reply that name a file in a shared folder (`CREW_SHARE_DIRS`, default `$CREW_WORKDIR/outputs`) are listed on `turn_end`, and `GET /api/files` serves only those folders. The app saves them to Downloads/Crew.
  - `browser.py`: the agents' Brave over DevTools: tabs (list, new, close), live JPEG screencast of a tab (standard 30 fps or high 60 fps, both 1080p), small stills of a tab for the app's working-task cards, mouse (move, press, release, click, double-click, right-click), scrolls, keys and typed text, the phone-sized vertical view (kept per tab, dropped when an agent uses the tab), and a watcher that sees which tab opens or navigates. While an agent's turn uses a tab, the phone only watches it (view only). Typed text goes straight to the page and is never logged or stored.
  - `schema*.sql`: migrations, applied in order by `db.migrate`.
  - `tests/`: end-to-end tests against a fake ACP agent and a real PostgreSQL.
- `android/`: the app (Kotlin, Jetpack Compose, `app.crew`, minSdk 36). `CrewViewModel` lives for the whole process (`CrewApplication`); `Notifier` and its `LiveService` (a foreground service) show a live-activity notification per running task and keep alerts coming while the phone is locked. Markdown is rendered with commonmark-java. The Crew tab (`TasksScreen`) puts what needs you first, then running tasks with their live activity, then routines and the rest; every agent has a face (`CrewFace`: a body shape and eyes only, drawn on one Canvas) whose eyes show what it is doing. New tasks start in a sheet (`NewTaskSheet`); a chat's settings open from its title (`ChatSettings`); both use `AgentSettings`, which turns whatever options the agent advertises over ACP into controls. Display type is Roboto Flex (`res/font`, licence in `android/licenses`). The launcher icon is three crew faces in a huddle (`ic_launcher_foreground`, plus a monochrome layer for themed icons), and notifications show one face (`ic_notification`). In the Browser screen the phone keyboard types straight into the page (`DirectKeyboard`: a `TYPE_NULL` input connection, like a terminal, so Backspace, arrows and Ctrl/Alt shortcuts arrive as real keys).
- `deploy/`:
  - `supervise.py`: keeps one command alive in a named tmux session for systemd, and stops it gracefully.
  - `run-server.sh`, `run-browser.sh`.
  - `systemd/*.service`: templates that `master.sh setup` installs to `~/.config/systemd/user/`.
  - Example config files.
  - `agents-section.md`: instructions for the agents Crew runs, including the routine rules (ask before creating a routine when an existing one may be the one to update).
  - `crew-cli.sh`: the `crew` command, run with the server's environment.
- `docs/`: `api.md`.
- `VERSION`: the one Crew version. The server reads it live (`/api/health`, its ACP client info and its package metadata), and the app's build takes its version name from it, with the version code worked out from it (0.8.1 is 801).

## How it runs

The server and the browser each have a systemd user unit, and each unit shows its program in a visible tmux session:

- `crew.service` runs tmux session `crew`. It starts at boot when lingering is on (`loginctl enable-linger`).
- `crew-browser.service` runs tmux session `agent-brave`. It starts with the desktop login, because Brave needs the display and the desktop keyring to decrypt its cookies.

systemd restarts either one 10 s after it dies, and gives up after 6 restarts in 10 minutes (then `./master.sh start` tries again; the server also waits up to 3 minutes for the database at boot instead of crashing). To stop Brave, `supervise.py` sends `Browser.close` over DevTools, which flushes logins to disk; killing it can lose logins saved in the last ~30 s. The owner can always `tmux attach -t crew` or `tmux attach -t agent-brave` to watch or take over.

Emergency restart: Settings in the app has "Restart Crew backend" (`POST /api/admin/restart`). The server stops every agent process group and exits; systemd brings it back after `RestartSec`, and startup marks the interrupted turns as errors. The browser unit is left alone. Closing or swiping away the app never cancels a task: only Stop does.

User units, not system units, on purpose:
- The agents must run as the owner's user, with their `~/.claude`, `~/.codex`, git and gh logins.
- Brave needs their desktop session.
- A system unit would need root and still have to switch to their user and wait for their desktop.

Lingering gives boot-time start without root services.

## Config (never commit, never print)

All config is in `~/.config/crew/` (folder mode 700, files 600):

- `crew.env`: start from `deploy/crew.env.example`.
  - `CREW_DATABASE_URL`, and `CREW_TEST_DATABASE_URL` for a throwaway test database.
  - `CREW_WORKDIR`: the agents' starting folder. Default `~`.
  - `CREW_PORT`: default 8800.
  - `CREW_SHARE_DIRS`: folders the agents hand files back from. Default `$CREW_WORKDIR/outputs`.
  - `CREW_BROWSER_PROFILE` and `CREW_CDP_PORT`.
  - `CREW_TIMEZONE`: the timezone routine schedules are read in. Default: the machine's own.
  - `CREW_BROWSER_GPU`: default 0. The agents' Brave renders in software, because with the GPU it stops painting (about 1 frame a second, every click waiting a second) once the monitor sleeps, and does not recover. Set 1 only where the display never sleeps.
  - `CREW_BROWSER_LOCATION`: `LAT,LON[,ACCURACY_M]`, the machine's real, fixed location. Brave on Linux has no system location source and a home server has no GPS or Wi-Fi, so pages that ask for the location wait forever. When set, `run-browser.sh` loads `deploy/fixed-location`, a small extension that answers the page's Geolocation API with it. Never put a location in the repo.
- `engines.toml`: start from `deploy/engines.example.toml`. Each engine is an ACP command with optional environment variables. `gateway = "claude"` or `"codex"` says how the engine is pointed at a profile's gateway; for Codex, `CODEX_PATH` in its env names the Codex that codex-acp runs and that Crew reads the bundled model catalog from.
- `profiles.toml` (optional): start from `deploy/profiles.example.toml`. See Profiles.
- `keys.env` (optional): gateway keys as `KEY=value` lines, for profiles. A profile can name another file with `key_file`.
- `token`: the phone's bearer token, created on the first start. `./master.sh token` shows it.

## Profiles

An agent runs on its own login (the owner's subscription: `claude` then /login, `codex login --device-auth`) unless a chat picks a profile: the same agent on a gateway such as OpenRouter or a company proxy.

- Subscription needs no config and is the default for every new task. The app remembers the agent, never the profile.
- A profile is one entry per gateway in `profiles.toml`: a name, the variable that holds its key, and its OpenAI (Responses) and Anthropic (Messages) base URLs. Crew adds one engine per agent and profile, `<engine>@<profile>`, which the app shows as that agent's profile, not as another agent. Switching profile in a chat is an agent switch (a handover).
- Models are never listed here. They come from the gateway's model list, which the key's own limits narrow (an OpenRouter guardrail), so changing models means changing the key's limits on the gateway, nothing in Crew.
  - Claude Code: `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` and its gateway model discovery, which keeps the model ids that contain `claude` or `anthropic`. OpenRouter names every model `anthropic/<id>[1m]` for Claude Code, so there it lists all the key's models; other gateways show their Claude models.
  - Codex: a model provider in `MODEL_PROVIDER` and `CODEX_CONFIG` (read by codex-acp at start), whose `model_catalog_url` is Crew's `/api/codex-catalog/...`. Crew builds that catalog on Codex's own bundled catalog: a gateway that uses Codex's model names (an OpenAI proxy) gets the Codex models it serves; any other gateway gets its whole list on a plain template, described from the gateway's metadata.
- A profile starts on the first model its gateway lists (`ANTHROPIC_MODEL`, or `model` in `CODEX_CONFIG`), since an agent's own default may be a model the key blocks; Claude Code's Haiku slot, used for its background calls, also points there when the gateway has no Haiku. The app remembers each profile's picks like any agent's.
- The global configs must not point at a gateway (`env` in `~/.claude/settings.json`, `model_provider` in `~/.codex/config.toml`), or the subscription is not the default.
- `./master.sh doctor` checks every profile engine by asking its gateway for the key's models (no tokens spent).
- OpenCode keeps its own providers in its own config; it has no Crew profiles.

## Setting up a new machine

Prerequisites that `master.sh` checks but does not install:

1. Linux with systemd and a desktop session: GNOME on X11 or Wayland. Also install `git`, `python3`, `tmux` and `curl`.
2. uv: `curl -LsSf https://astral.sh/uv/install.sh | sh`.
3. PostgreSQL 14 or newer, reachable from this machine. Create a `crew` database and its own login, plus `crew_test` for tests. A shared PostgreSQL 18 + pgvector container works well. Do not give Crew a superuser.
4. An ACP engine.
   - Claude Code: download the standalone `claude-agent-acp` binary from its GitHub releases (agentclientprotocol/claude-agent-acp). Put it in `~/.local/share/claude-agent-acp/<version>/` and symlink it to `~/.local/bin/claude-agent-acp`. It bundles Claude Code and reads `~/.claude/settings.json`.
   - Codex: install an ACP adapter such as `codex-acp`, then add it to `engines.toml`.
5. Brave and browser-harness.
   - Install browser-harness with `uv tool install browser-harness`.
   - Put its skill in `~/.agents/skills/browser-harness/` and symlink it into `~/.claude/skills/`.
   - Turn telemetry off. The engine environment in `engines.toml` already sets `BH_TELEMETRY=0`.
6. The agents' instructions: append `deploy/agents-section.md` to `$CREW_WORKDIR/AGENTS.md`, which is `~/AGENTS.md` by default.
7. Optional, profiles: copy `deploy/profiles.example.toml` to `~/.config/crew/profiles.toml`, put each key in `~/.config/crew/keys.env` (mode 600), add `gateway` (and `CODEX_PATH` for Codex) to the engines in `engines.toml`, then restart and run `doctor`. Log each agent in to its subscription once (`claude` then /login; `codex login --device-auth`).
8. Optional, for use away from home: Tailscale, then `tailscale serve --bg --https=8800 http://127.0.0.1:8800`.
9. Optional: `sudo loginctl enable-linger $USER`, so Crew starts at boot before anyone logs in. Brave still waits for the desktop login.

10. Optional, to build the app here: JDK 21, the Android SDK, and a signing key. The key is a properties file at `~/.android/keystores/release.properties` (or `$ANDROID_SIGNING_PROPERTIES`) with `storeFile` (relative to that file), `storePassword`, `keyAlias` and `keyPassword`. Keep it, and the keystore, outside the repo and backed up: every update must be signed with the same key. Without it, `./master.sh app` builds a debug-signed APK.

Then run `./master.sh setup`. It creates the config, installs the Python environment and the units, starts both, and runs `doctor`. Finally, enter the machine's address and the token in the app under Settings.

## Rules

- Stay agent-agnostic. Talk to agents only over ACP, and never build on one vendor's remote or mobile product.
  - Engines come from `engines.toml`.
  - Model, effort and mode come from the agent's own ACP `configOptions`. Never hard-code model names.
  - Agent instructions live in `AGENTS.md` files, and skills in `~/.agents/skills`, symlinked for Claude Code.
- Secrets: never print, log or commit database URLs, passwords or the token. `doctor` reports only whether they are set.
- Every long-running process runs in a named tmux session the owner can attach to. Use sudo only in a tmux session where the owner types the password.
- A release bumps `VERSION` and nothing else that holds a version.
- Work on `main`, the only branch. Use conventional commit messages, with no AI attribution lines.
- Android: build and install with `./master.sh app`. It builds the release APK signed with the shared key (step 10); never ship a debug-signed build, since a different key cannot update an installed app.

## Checks before you finish

- Server changes: `./master.sh test`, which needs `CREW_TEST_DATABASE_URL`.
- Live changes: `./master.sh restart`, then `./master.sh doctor`.
- App changes: `cd android && ./gradlew :app:assembleDebug`.
