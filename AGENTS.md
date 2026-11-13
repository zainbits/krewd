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
  - `hub.py`: sessions, turns, approvals, config options, and messages: stored first, then steered into the running turn (ACP `_session/steering`, only where the agent advertises it), queued, or sent after an interrupt; queued messages survive a restart. It also links a browser tab to the session whose agent is using it, has the emergency restart, compacts a chat (the agent's own `/compact` where it advertises one, else a handover to a fresh agent session), switches a chat between agents (each agent keeps its own session in the chat; the outgoing one writes a handover and a returning one hears only what it missed), and runs routines: a scheduler loop fires due triggers (several named cron triggers per routine, plus bounded follow-ups a run asks for) as messages in each routine's own chat, with the routine's notes, starts each run in a fresh agent session (the last one stays until then, for follow-ups in the chat), and tracks every run and attempt.
  - `routines.py`: cron schedules (cronsim, with `L` and `LW` for month ends) in a timezone, their reading in words, catch-up after downtime, and the prompt each run sends (date facts plus the instructions).
  - `cli.py`: the `crew` command for agents and the owner (`crew routines ...`, `crew schedule ...`), over the local HTTP API.
  - `api.py`: HTTP API (see `docs/api.md`).
  - `security.py`: the owner's security key (U2F, `owner.json`), the owner's phones (`devices` table, each signed by the key), 12-hour phone sessions, the fingerprint check for sensitive requests (`X-Crew-Presence`), and the setup token, which only pairs the first phone and is then replaced by one that works from this machine only. `secrets_store.py`: sign-in requests (`crew secret request`): the owner fills them on the phone, `browser.fill_secret` types the values into the page and submits; values are never stored or logged.
  - `tls.py` and `mdns.py`: Crew's own certificate (made on first start, `tls.crt`/`tls.key` in the config folder) and its pin, and the mDNS announcement of the HTTPS address (`_crew._tcp`). `__main__.py` runs two servers on the same app: plain HTTP on loopback (it owns the lifespan) and HTTPS on every other address, and `python -m crew_server connect` prints the connect code.
  - `profiles.py`: profiles, an agent on a gateway instead of its sign-in account (see Profiles and agents below): the gateway's model list (cached a minute), the Codex model catalog built from it, the environment each profile engine starts with, saving profiles and keys from the app, and `python -m crew_server.profiles check` for doctor.
  - `logins.py`: each agent's own sign-in from the app: runs `claude auth login --claudeai` (link, then the code pasted back) or `codex login --device-auth` (link and code) and relays them, and reads `claude auth status` / `codex login status`.
  - `attachments.py`: uploads from the phone, saved as `~/.crew/common/attachments/<session id prefix>-<name>` and passed to the agent as paths and ACP resource links.
  - `files.py`: files the agents hand back: paths in a reply that name a file in a shared folder (`CREW_SHARE_DIRS`, default `$CREW_WORKDIR/outputs`) are listed on `turn_end`, and `GET /api/files` serves only those folders. The app saves them to Downloads/Crew.
  - `browser.py`: the agents' Brave over DevTools: tabs (list, new, close), live JPEG screencast of a tab (standard 30 fps or high 60 fps, both 1080p), small stills of a tab for the app's working-task cards, mouse (move, press, release, click, double-click, right-click), scrolls, keys and typed text, the phone-sized vertical view (kept per tab, dropped when an agent uses the tab), and a watcher that sees which tab opens or navigates. While an agent's turn uses a tab, the phone only watches it (view only). Typed text goes straight to the page and is never logged or stored.
  - `schema*.sql`: migrations, applied in order by `db.migrate`.
  - `tests/`: end-to-end tests against a fake ACP agent and a real PostgreSQL.
- `android/`: the app (Kotlin, Jetpack Compose, `app.crew`, minSdk 36). `CrewViewModel` lives for the whole process (`CrewApplication`); `Notifier` and its `LiveService` (a foreground service) show a live-activity notification per running task and keep alerts coming while the phone is locked. Markdown is rendered with commonmark-java. The Crew tab (`TasksScreen`) puts what needs you first, then running tasks with their live activity, then routines and the rest; every agent has a face (`CrewFace`: a body shape and eyes only, drawn on one Canvas) whose eyes show what it is doing. New tasks start in a sheet (`NewTaskSheet`); a chat's settings open from its title (`ChatSettings`); both use `AgentSettings`, which turns whatever options the agent advertises over ACP into controls. Display type is Roboto Flex (`res/font`, licence in `android/licenses`). The launcher icon is three crew faces in a huddle (`ic_launcher_foreground`, plus a monochrome layer for themed icons), and notifications show one face (`ic_notification`). Security: `data/Keys.kt` makes this phone's two keys in its secure hardware (a device key for sessions, a presence key that needs the fingerprint or screen lock for each use), `data/Presence.kt` shows the fingerprint prompt, `data/U2f.kt` talks to the owner's security key with FIDO U2F over USB-C (CTAPHID; plugged in, then touched) or NFC (held to the back of the phone), whichever it finds first, and Settings > Security pairs the phone or removes phones (`ui/SecuritySettings.kt`). Sign-in requests appear under Needs you and open a secure sheet (`ui/SecretSheet.kt`: password-manager autofill, no screenshots). The app's own picks (per-agent settings, saved prompts, stream quality, PiP sizes) are kept on the server (`/api/prefs`), so a new phone starts where the old one was. The app talks HTTPS only (`network_security_config` forbids plain HTTP): `data/Tls.kt` trusts Crew's own certificate by its pin and everything else through the system authorities, `data/Discovery.kt` finds the server on the local network (mDNS) when no saved address answers, and a `crew://connect` link (the code `./master.sh token` shows) fills in the addresses, token and pin after the owner says yes. In the Browser screen the phone keyboard types straight into the page (`DirectKeyboard`: a `TYPE_NULL` input connection, like a terminal, so Backspace, arrows and Ctrl/Alt shortcuts arrive as real keys).
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
  - `CREW_HOST`: default `127.0.0.1,lan`. Loopback is plain HTTP, for local tools and an HTTPS proxy in front of Crew (Tailscale Serve now, Caddy or a cloud load balancer later); every other address gets HTTPS.
  - `CREW_TLS` (`auto`, or `off`, which the app refuses), `CREW_TLS_CERT` and `CREW_TLS_KEY` (default `tls.crt` and `tls.key` here; the server makes them on first start; keep the key mode 600). The app pins the certificate, so keep it: a new one means opening a new connect code on the phone (or reconnecting once over Tailscale, which teaches the app the new pin).
  - `CREW_URLS`: other HTTPS addresses the app should know (Tailscale Serve, a domain), comma-separated, for the connect code.
  - `CREW_MDNS`: default 1, announce the HTTPS address on the local network.
  - `CREW_SHARE_DIRS`: folders the agents hand files back from. Default `$CREW_WORKDIR/outputs`.
  - `CREW_BROWSER_PROFILE` and `CREW_CDP_PORT`.
  - `CREW_TIMEZONE`: the timezone routine schedules are read in. Default: the machine's own.
  - `CREW_BROWSER_GPU`: default 0. The agents' Brave renders in software, because with the GPU it stops painting (about 1 frame a second, every click waiting a second) once the monitor sleeps, and does not recover. Set 1 only where the display never sleeps.
  - `CREW_BROWSER_LOCATION`: `LAT,LON[,ACCURACY_M]`, the machine's real, fixed location. Brave on Linux has no system location source and a home server has no GPS or Wi-Fi, so pages that ask for the location wait forever. When set, `run-browser.sh` loads `deploy/fixed-location`, a small extension that answers the page's Geolocation API with it. Never put a location in the repo.
- `engines.toml`: start from `deploy/engines.example.toml`. Each engine is an ACP command with optional environment variables. `gateway = "claude"` or `"codex"` says how the engine is pointed at a profile's gateway; for Codex, `CODEX_PATH` in its env names the Codex that codex-acp runs and that Crew reads the bundled model catalog from.
- `profiles.toml` (optional): start from `deploy/profiles.example.toml`. See Profiles.
- `keys.env` (optional): gateway keys as `KEY=value` lines, for profiles. A profile can name another file with `key_file`.
- `owner.json`: the owner's security key (public key, key handle, counter), written when the first phone pairs. Keep it with the config; losing it means `./master.sh security reset`.
- `token`: the setup token, created on the first start. It pairs the first phone; then Crew replaces it with one that works only from this machine (the `crew` command). `./master.sh token` shows the connect code (QR and link) that carries it with the addresses and the certificate pin.

## Profiles and agents

Each agent runs on one thing at a time: its own sign-in account (the owner's subscription) or a profile, a gateway such as OpenRouter or a company proxy. That is set per agent in the app (Settings > Agents, "Runs on"), and nothing else in Crew cares: chats, routines and new tasks only name the agent, and follow it when its profile changes.

- Settings > Agents: each agent's sign-in (status, Sign in, Sign out) and "Runs on: Sign-in account | <profiles>" (`[agents]` in `profiles.toml`; an agent not listed runs on its sign-in account). Crew runs the agent's own CLI login on the server and relays it: Claude Code shows a link and takes the code that page shows, pasted in the app; Codex shows a link and a code to type there and finishes by itself. Signing in never changes what an agent runs on; on its sign-in account without a sign-in, the agent reports a problem instead of running.
- Settings > Profiles: add (OpenRouter preset, or any gateway), edit and delete. Crew asks the gateway for the key's models before saving, writes `profiles.toml` and the key to `keys.env`, and applies the change at once. Keys are accepted only over HTTPS: Crew's own (pinned by the app) or an HTTPS proxy on loopback such as Tailscale Serve. The server refuses them over plain HTTP, and the app never sends them over an unchecked connection. They are never sent back; the app shows the last 4 characters. A profile an agent runs on cannot be deleted, or lose the address that agent needs.
- Changing what an agent runs on restarts its idle process (a running turn finishes first on the old settings); its chats continue in their own sessions on the new profile.
- Models are never listed in Crew. They come from the gateway's model list, which the key's own limits narrow (an OpenRouter guardrail), so changing models means changing the key's limits on the gateway. After such a change, Settings > Restart Crew backend picks it up at once.
  - Claude Code: `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN` and its gateway model discovery, which keeps the model ids that contain `claude` or `anthropic`. OpenRouter names every model `anthropic/<id>[1m]` for Claude Code, so there it lists all the key's models; other gateways show their Claude models. An agent on a profile is probed with two sessions, since discovery fills the list from the second.
  - Codex: a model provider in `MODEL_PROVIDER` and `CODEX_CONFIG` (read by codex-acp at start), whose `model_catalog_url` is Crew's `/api/codex-catalog/...`. Crew builds that catalog on Codex's own bundled catalog: a gateway that uses Codex's model names (an OpenAI proxy) gets the Codex models it serves; any other gateway gets its whole list on a plain template, described from the gateway's metadata.
- The model an agent starts on follows the same split. A gateway that uses the agent's own model names (an OpenAI or Anthropic proxy, which may list models it then refuses) keeps the agent's own default (Codex: the first Codex model it serves). Any other gateway (OpenRouter, whose list is what the key may use) starts on its first listed model (`model` in `CODEX_CONFIG`, `ANTHROPIC_MODEL`); Claude Code's background model (`ANTHROPIC_SMALL_FAST_MODEL`, Haiku by default) too, unless the gateway offers a Haiku.
- CLIs: Codex from `CODEX_PATH`; Claude Code from `CLAUDE_PATH`, else the `claude` beside the engine's command (the standalone claude-agent-acp ships one), else `claude` on PATH.
- The global configs must not point at a gateway (`env` in `~/.claude/settings.json`, `model_provider` in `~/.codex/config.toml`): Claude Code's settings env would override the profile Crew gives it.
- `./master.sh doctor` shows what each agent runs on and its sign-in, and checks every profile with every agent that can use it by asking the gateway for the key's models (no tokens spent).

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
   - Put its skill in `~/.agents/skills/browser-harness/`.
   - Skills live in one place, `~/.agents/skills`. Codex reads it already; Claude Code reads only `~/.claude/skills`, so make that a link to the whole folder: `ln -s ../.agents/skills ~/.claude/skills` (move an existing `~/.claude/skills` aside first). A skill added to `~/.agents/skills` then reaches every engine.
   - Turn telemetry off. The engine environment in `engines.toml` already sets `BH_TELEMETRY=0`.
6. The agents' instructions: append `deploy/agents-section.md` to `$CREW_WORKDIR/AGENTS.md`, which is `~/AGENTS.md` by default.
7. Profiles and sign-ins: add `gateway` (and `CODEX_PATH` for Codex) to the engines in `engines.toml`, restart, then sign the agents in, add gateways and pick what each agent runs on from the app (Settings > Agents, Settings > Profiles). By hand instead: `deploy/profiles.example.toml` to `~/.config/crew/profiles.toml`, keys in `~/.config/crew/keys.env` (mode 600).
8. Optional, for use away from home: Tailscale, then `tailscale serve --bg --https=8800 http://127.0.0.1:8800`, and add that address to `CREW_URLS`. Any other HTTPS proxy on loopback, or a domain, works the same way.
9. Optional: `sudo loginctl enable-linger $USER`, so Crew starts at boot before anyone logs in. Brave still waits for the desktop login.

10. Optional, to build the app here: JDK 21, the Android SDK, and a signing key. The key is a properties file at `~/.android/keystores/release.properties` (or `$ANDROID_SIGNING_PROPERTIES`) with `storeFile` (relative to that file), `storePassword`, `keyAlias` and `keyPassword`. Keep it, and the keystore, outside the repo and backed up: every update must be signed with the same key. Without it, `./master.sh app` builds a debug-signed APK.

Then run `./master.sh setup`. It creates the config, installs the Python environment and the units, starts both, and runs `doctor`. Finally, run `./master.sh token` and scan its QR code with the phone's camera (or open its link on the phone): the app asks, then fills in the addresses, the token and the certificate pin. Then, in the app, Settings > Security > Set up with security key, and plug the owner's FIDO security key into the phone's USB-C port and touch it (or hold it to the back, on a phone with NFC): that registers the key and this phone, and the setup token stops working. Later phones: open the code from `./master.sh token` (no token in it now) and use the key the same way. Lost phone and key: `./master.sh security reset` on this machine, then set up again.

## Rules

- Stay agent-agnostic. Talk to agents only over ACP, and never build on one vendor's remote or mobile product.
  - Engines come from `engines.toml`.
  - Model, effort and mode come from the agent's own ACP `configOptions`. Never hard-code model names.
  - Agent instructions live in `AGENTS.md` files, and skills in `~/.agents/skills` (`~/.claude/skills` is a link to it). Never install a skill into one engine's own folder.
- Secrets: never print, log or commit database URLs, passwords or the token. `doctor` reports only whether they are set.
- Every long-running process runs in a named tmux session the owner can attach to. Use sudo only in a tmux session where the owner types the password.
- A release bumps `VERSION` and nothing else that holds a version.
- Work on `main`, the only branch. Use conventional commit messages, with no AI attribution lines.
- Android: build and install with `./master.sh app`. It builds the release APK signed with the shared key (step 10); never ship a debug-signed build, since a different key cannot update an installed app.

## Checks before you finish

- Server changes: `./master.sh test`, which needs `CREW_TEST_DATABASE_URL`.
- Live changes: `./master.sh restart`, then `./master.sh doctor`.
- App changes: `cd android && ./gradlew :app:assembleDebug`.
