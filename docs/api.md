# Krewd server API

Base URL: `https://<server address>:8800` on every network address, with Krewd's own certificate, or an HTTPS proxy in front of Krewd such as Tailscale Serve (`https://<machine>.<tailnet>.ts.net:8800`) or a domain. Plain HTTP is only on loopback (`http://127.0.0.1:8800`), for local tools and those proxies. `./master.sh status` prints the addresses.
Every route except `/api/health`, `/api/security/nonce`, `/api/security/pair` and `/api/security/session` needs `Authorization: Bearer <credential>`:
- before any phone is paired: the setup token (`~/.config/krewd/token`, in the connect code);
- after that: a phone session (below), or the token, which then works only from this machine over loopback (the `krewd` command). Pairing the first phone replaces the token, so the copy the phone had stops working.

Phones and the owner's keys (`security.py`, `passkeys.py`):
- Owner keys: the FIDO security key (U2F, over USB-C or NFC on the phone; at most one) and phone passkeys (WebAuthn ES256 from Android's Credential Manager, any number). Their public keys are in `owner.json` (`{version: 2, keys: [...]}`) in the config folder, not in the database. The U2F application parameter is SHA-256 of `krewd`. Passkeys belong to `KREWD_PASSKEY_RP_ID`, a public domain whose `/.well-known/assetlinks.json` names `app.krewd` and its signing certificate (Krewd serves the statement to copy at `GET /.well-known/assetlinks.json`); the server accepts only the origin `android:apk-key-hash:<base64url SHA-256 of a certificate in KREWD_PASSKEY_CERTS>`, and requires user presence and verification.
- Both kinds sign the same 32 bytes, `SHA-256("krewd-u2f-v1\n<purpose>\n<nonce>\n<payload>")`: the security key as its U2F challenge parameter, a passkey as its WebAuthn challenge. A request carries the signature as `assertion` (U2F authenticate response, base64) or `passkey` `{credential_id (base64url), client_data, authenticator_data, signature}` (base64). A new key is made with the challenge for purpose `register` and an empty payload.
- `POST /api/security/nonce {purpose}` (`pair`, `session`, `presence`, `revoke`, `add-key`, `remove-key`) gives `{nonce, expires_in, paired, app_param, passkey_rp_id, keys?, key_handle?}` (`keys` for the owner-key purposes: `[{id, kind, name, key_handle | credential_id}]`); a nonce works once, for 3 minutes.
- `POST /api/security/pair` `{nonce, device_id, name, device_key, presence_key, pin_seen, device_sig, assertion | passkey, registration? | passkey_registration?}` (keys: P-256 SubjectPublicKeyInfo, base64). An owner key signs purpose `pair`, payload `<device_id>|<b64 sha256 device_key>|<b64 sha256 presence_key>|<pin_seen>`; the device key signs `krewd-device-v1\npair\n<nonce>\n<pin_seen>`. The first phone sends the setup token and the first owner key's registration in the same request: `registration` (U2F register response) or `passkey_registration` `{client_data, attestation_object}`. Over Krewd's own HTTPS `pin_seen` must be Krewd's pin (a man in the middle shows another certificate). 201 `{device_id, session, expires_in}`.
- `POST /api/security/session {device_id, nonce, pin_seen, sig}`: the device key signs `krewd-device-v1\nsession\n<nonce>\n<pin_seen>`; a 12-hour session (memory only). The phone's row is checked against the owner key's signature every time, so a row written straight into the database is refused.
- Sensitive requests from a paired phone carry `X-Krewd-Presence: <nonce>.<signature>`: the presence key (usable only right after the owner's fingerprint or screen lock) signs `krewd-presence-v1\n<nonce>\n<METHOD>\n<path>\n<b64 sha256 of the exact body>`. Needed for a body with a gateway key and for filling a sign-in request.
- `GET /api/security` `{paired, you, devices: [{id, name, created_at, last_seen, current, key}], keys, passkey_rp_id}` (`key`: the owner key that added the phone). `POST /api/security/devices/{id}/revoke {nonce, assertion | passkey}` (purpose `revoke`, payload the device id; no body from this machine).
- `POST /api/security/keys {nonce, kind: passkey | u2f, name, passkey_registration | registration, assertion | passkey}`: adds an owner key (one security key at most); a registered owner key signs purpose `add-key`, payload `<b64 SHA-256 of the new key's attestation object or U2F register response>`. 201 the key. `POST /api/security/keys/{id}/remove {nonce, assertion | passkey}` (purpose `remove-key`, payload the key id; any owner key, also the one being removed; no body from this machine, `krewd security remove-key ID`): the last key cannot be removed (409); phones whose proof only that key signed are removed too. `{removed, phones_removed}`.
- `POST /api/security/reset` (this machine only, `krewd security reset`): no owner keys, no phones, a new setup token.

Krewd's certificate is self-signed; clients trust it by its pin, `sha256/` + base64 of the SHA-256 of its SubjectPublicKeyInfo (as in HPKP). The app learns it from the connect link `./master.sh token` shows (`krewd://connect?a=<address>&a=...&t=<token>&p=<pin>`, also as a QR code), or over an address a certificate authority vouches for (`GET /api/tls`). The server announces its HTTPS address on the local network as `_krewd._tcp` (mDNS, TXT `v` = version, `id` = the first 12 characters of the pin after `sha256/`), so the app finds it after an address change.

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/health` | | `{ok, version}` |
| `GET /api/tls` | | `{enabled, pin}`: the pin of Krewd's own certificate (null before the first start). |
| `GET /api/prefs` | | the app's own settings kept on the server, `{key: value}` (`engine.<id>`, `last_engine`, `saved_prompts`, `browser_high_quality`, `browser_cursor`, `pip_wide_width`, `pip_tall_height`), so a new phone starts where the old one was. |
| `PUT /api/prefs/{key}` | `{value}` (null removes it) | `{ok}`. Keys: lowercase letters, digits, `_ . -`; up to 64 kB. |
| `POST /api/secrets` | `{tab_id?, fields?: [{kind, label?, selector?}], submit?, reason}` | a sign-in request (201), for the agents (`krewd secret request`). Kinds: username, email, password, otp, text (default username and password). Without `tab_id`: the tab of the one task running a turn. `submit`: `enter` (default), `none` or a CSS selector to click. Emits a `secret` event. |
| `GET /api/secrets` | | pending requests: `[{id, session_id, tab_id, site, origin, title, reason, fields: [{name, label, kind}], status, expires_at, url, error}]`. Status: pending, filled, cancelled, expired (10 minutes), failed. |
| `GET /api/secrets/{id}/wait?timeout=30` | | the request once it is no longer pending, or after the timeout. |
| `POST /api/secrets/{id}/cancel` | | the request, cancelled. |
| `POST /api/secrets/{id}/fill` | `{values: {name: value}}` | the request. Only a paired phone, over HTTPS, with `X-Krewd-Presence` for this exact body. The tab must still be on the request's site; Krewd types each value into its field (`Input.insertText`), submits, waits 2.5 s and records the page it landed on. Values are never stored, logged or put in an event. |
| `GET /api/state` | | `{last_seq, engines: [{id, name, default, agent, config_options, modes, steering, commands, profile, problem}], workdir}`. `commands` is what the agent last listed (see Session `commands`), so a new task can suggest them before its chat exists. `steering` is true when the agent advertised `_meta.steering.supported` in its ACP `initialize` answer (read from the agent, never assumed per engine). An engine never seen yet is probed in the background (a throwaway session), so its `config_options` fill in a few seconds later. `profile` is the profile the agent runs on (null: its sign-in account, or no gateway kind); `problem` says why it cannot run as set up now (its profile has no key, its sign-in account is not signed in). |
| `GET /api/profiles` | | `{profiles: [{id, name, openai, anthropic, key_set, key_hint, agents}], presets: [{id, name, openai, anthropic}], managed}`. Keys never come back: only whether one is set and its last 4 characters. |
| `POST /api/profiles` | `{name, openai?, anthropic?, key, preset?}` | the profile; 201. A body with a key is accepted only over HTTPS (Krewd's own, or a proxy on loopback); 403 otherwise. The gateway is asked for the key's models first (422 with the reason when it refuses). Saved to `profiles.toml`, the key to `keys.env` (mode 600); agents can run on it at once (Settings > Agents). |
| `PATCH /api/profiles/{id}` | `{name?, openai?, anthropic?, key?}` | the profile. `""` removes an address. Idle agents on it restart with the new settings; a running turn finishes on the old ones. 409 when it takes away the address an agent running on it needs. |
| `DELETE /api/profiles/{id}` | | `{ok}`; also removes its key from `keys.env`. 409 while an agent runs on it. |
| `GET /api/agents` | | `{agents: [{engine, name, gateway, logged_in, account, error, profile, profile_choices, flow}]}`: each agent's sign-in account, from its CLI's status (cached a minute), the profile it runs on (`profile`, null: its sign-in account), and a sign-in in progress (`flow`: `{state, url, code, paste, error, expires_in}`). |
| `PUT /api/agents/{engine}/profile` | `{profile}` (null: its sign-in account) | the agent's entry. Every chat and routine with the agent follows; its idle process restarts (a running turn finishes first). Saved as `[agents]` in `profiles.toml`. |
| `POST /api/agents/{engine}/login` | | `flow`: starts the agent's sign-in on the server. Claude Code: open `url`, then send the code the page shows (`paste` true). Codex: open `url` and type `code` there; it finishes by itself. |
| `POST /api/agents/{engine}/login/code` | `{code}` | `flow` once the CLI is done (Claude Code). |
| `DELETE /api/agents/{engine}/login` | | `{ok}`; cancels a sign-in in progress. |
| `POST /api/agents/{engine}/logout` | | the new status. |
| `GET /api/codex-catalog/{secret}/{engine}` | none (the secret) | Codex's model catalog for Codex while it runs on a profile, built from the gateway's model list. Only Krewd's own Codex processes get the URL (`model_catalog_url`), with a secret made at each start; anything else gets 404. 502 when the gateway cannot be reached. |
| `GET /api/sessions?archived=false` | | sessions, pinned first, newest first |
| `POST /api/sessions` | `{text, engine?, mode?, auto_approve?, config?: {config_id: value}, attachments?: [upload_id], tabs?: [{id, title, url}], chats?: [{id}], parent_id?, returns?, title?, client_id?}` | the new session; its first turn starts at once. With `parent_id` it is a side session of that chat (a sub-agent with `returns`), see Side sessions |
| `GET /api/sessions/{id}` | | session |
| `DELETE /api/sessions/{id}` | | `{ok}`; stops the task's turn, closes its agent session where the agent supports it, and deletes its history, approvals and messages, its attachments and tool images, and the agent's transcripts of its sessions (the app waits 3 s for Undo before calling this) |
| `POST /api/sessions/{id}/trim` | | `{repeats_bytes, tools_bytes, images_bytes, bytes}`; shrinks the chat's history in Krewd (see Storage); 409 while it works |
| `PATCH /api/sessions/{id}` | `{title?, auto_approve?, pinned?, archived?, mode?, config?: {config_id: value}}` | session |
| `POST /api/sessions/{id}/messages` | `{text, attachments?, tabs?, chats?: [{id}], delivery?: steer\|queue\|interrupt, client_id?}` | `{ok, message}` (see Messages below) |
| `GET /api/sessions/{id}/links` | | `{session, parent, children, waiting_on}`: the chat's parent, its side sessions and sub-agents, and the chats that owe it a reply (session objects) |
| `GET /api/proposals?status=pending&session_id=` | | proposals (see Side sessions); `status` empty for all |
| `POST /api/proposals` | `{session_id?, kind: new\|prompt, text, title?, engine?, returns?, target_id?}` | the proposal (201), for the agents (`krewd side propose`, `krewd chat prompt`); 409 outside a running turn of that chat, or for a chat neither attached with @ nor linked |
| `POST /api/proposals/{id}/send` | `{text?, title?, engine?, mode?, config?, returns?, chats?}` | the proposal, `sent`, with `result_id` (the chat started or messaged). Not with the machine's own token (403): only the owner sends one, from the app. 409 when it is no longer pending |
| `POST /api/proposals/{id}/dismiss` | | the proposal, `dismissed` |
| `POST /api/sessions/{id}/cancel` | | `{ok}`; stops the turn, answers its open approvals with "cancelled" and drops the messages queued behind it |
| `POST /api/sessions/{id}/engine` | `{engine, config?, mode?}` | `{ok, message}`; moves the chat to another agent after the running turn (see Switching agents) |
| `GET /api/sessions/{id}/transcript?after=0&last=N` | | the chat as messages `[{seq, from, text}]` (owner, agents, Krewd notes), without tool calls or thinking |
| `POST /api/sessions/{id}/compact` | | `{ok, message}`; compacts the agent's context after the running turn (see Compact below); 409 when the chat has no agent context yet. A second call while one is queued returns the queued one. |
| `POST /api/admin/restart` | | `{ok}`; emergency: stops every agent process and exits, so systemd starts the server again (about 10 to 15 s). Interrupted turns end with an error; queued messages run after the restart. The browser is not touched. |
| `GET /api/sessions/{id}/events?after=0` | | `{events, last_seq}`: the task's whole timeline |
| `GET /api/events?after=N&wait=25` | | long-poll across all tasks: returns once there is an event after N |
| `GET /api/approvals?status=pending` | | approvals |
| `POST /api/approvals/{id}` | `{option_id}` | the decided approval; 409 if it is no longer waiting |

Session: `{id, engine, title, status (idle|running|waiting|error), mode, modes, auto_approve, usage, last_text, pinned, archived, pending_approvals, browser_tab, routine_id, compact, commands, created_at, updated_at}`. `commands` `[{name, description, hint}]` are the agent's own slash commands and skills from ACP `available_commands_update`, sorted by name (Codex names its skills `$name`, the others plain). The app suggests them in the composer; a pick is sent as ordinary text (`/name args`, or `$name args` for Codex), which the agent itself turns into the command. `routine_id` is set on a routine's own chat. `compact` is `native` when the agent advertises a `compact` slash command (ACP `available_commands_update`), else `handover`. `pinned` sessions come first in the list. Side sessions: `parent_id`, `parent_title`, `returns`, `kind` (`side`, `subagent` or null) and `waiting_on` (ids of the chats that owe this one a reply). `browser_tab` `{id, url, title}` is the tab the agent last used (see The agents' browser). `modes` is what the agent advertises (ACP `availableModes`), so the app shows each agent's own modes.

Harness, model and effort (agent-agnostic): sessions also carry `agent` (ACP `agentInfo`: `{name, title, version}`), `config_options` (the agent's ACP `configOptions`, flattened: `[{id, name, description, category, type, current, options: [{value, name, description, group?}]}]`; categories include `model`, `thought_level` (effort) and `mode`), `config` (the user's picks, re-sent to the agent after a restart) and `model_used` (the model the agent reported for its last step, from usage metadata). Setting `config` calls ACP `session/set_config_option`; the agent's reply replaces `config_options` (a model change can change the effort choices).

Event: `{seq, session_id, kind, payload, created_at}`. Kinds:

- `user` `{text, message_id, delivery, client_id?, attachments?, tabs?, chats?, return_to?, purpose?, meta?}`: `chats` `[{id, title, relation, engine}]` are chats attached with @; `return_to` is the chat that gets this turn's reply. `purpose` is set when Krewd sent the message itself: `routine` (a run; `meta` `{routine_id, run_id, title, trigger, version}`), `compact` (`meta` `{how}`), `result` (a reply or a note sent back from another chat; `meta` `{from, from_title, kind: sub-agent|chat, ok, hops}`) or `prompt` (a message another chat sent with the owner's OK; `meta` `{from, from_title, returns}`)
- `proposal` proposal object, on the proposing chat, whenever it is made, sent or dismissed
- `link` `{kind: side|subagent|prompt, chat_id, title, returns}`: this chat started a side session or sub-agent, or sent a message to another chat
- `routine` routine object (without runs) whenever it or its runs change, on the routine's chat; `{id, deleted: true}` when deleted
- `compact` `{how: native|handover, ok}`: a compaction finished
- `switch` `{from, to, from_name, to_name, from_session, summary}`: the chat moved to another agent
- `message` message object, whenever its state changes (delivered or cancelled)
- `browser` `{id, url, title}`: the agent started using a tab
- `text` `{message_id, role: assistant|thought, text}`: append to the row with the same `message_id`
- `tool` `{tool_call_id, title, kind, status, input, output, paths, images}`: replace the row with the same id
- `plan` `{entries: [{content, status}]}`
- `approval` approval object (pending, then again when decided)
- `turn_start` `{message_id}`, `turn_end` `{stop_reason, usage, preview, files?}` (`preview`: first line of the reply; `files`: see Files from the agent), `error` `{message}`
- `session` session object, whenever it changes

## Messages and delivery

Every message is stored before anything else happens, so it survives the phone going offline and the server restarting. Message: `{id, session_id, client_id, seq, delivery, state, delivered_how, created_at, delivered_at}`.

- `state` `queued`: the server has it (one tick in the app). `delivered`: the agent has it (two ticks), `delivered_how` `started` (its turn began) or `steered` (the agent confirmed it was injected into the running turn). `cancelled`: dropped by Stop.
- `delivery` while a turn runs: `steer` sends ACP `_session/steering` with `_meta.steering.idleBehavior: "promptRequired"`; if the agent answers `promptRequired` (the turn just ended) or does not support it, the message runs as the next prompt. `queue` runs it after the current turn. `interrupt` stops the turn (like Stop, but keeps the queue) and runs this message next. Without `delivery`, the server steers where the engine supports steering and queues otherwise. A `steer` sent while no turn runs is stored as `queue`, since it simply starts the next turn.
- `client_id`: the phone's own id for the message. Sending the same `client_id` again returns the stored message instead of a second copy, so "tap to resend" is safe.
- `tabs`: browser tabs from "Add to chat". The agent's prompt lists them with their DevTools target ids.

Clients replay a task with `/sessions/{id}/events`, then follow `/api/events` from `last_seq`. Events are durable, so a client can disconnect at any time and continue from the last seq it saw.


## Switching agents

One chat can move between agents (Claude Code, Codex, any ACP agent) and stays one chat. Each agent keeps its own session and settings in the chat (`session_agents`), with the last event it saw. On a switch the current agent first writes a handover (skipped when it has no context yet); Krewd parks its session, brings in the target agent's own session (or a new one), and puts a briefing in front of the next message: the handovers written since that agent last saw the chat, the previous agents' session ids, and `krewd chat history <chat id>` for exact messages. A returning agent resumes its own session and hears only what it missed. The `switch` event `{from, to, from_name, to_name, from_session, summary}` marks it in the timeline. A routine's chat and its routine always use the same agent: changing the routine's agent switches its chat, and switching the chat changes the routine.

## Compact

Where the agent advertises a `compact` slash command (Claude Code and Codex do), Krewd sends `/compact` as the next prompt and the agent keeps its session. Otherwise the agent is asked for a handover summary; Krewd then closes that agent session, opens a fresh one in the same chat, and puts the handover in front of the next message. The chat history in Krewd is never changed. `usage` is cleared after a handover.

## Side sessions

A side session is an ordinary chat with a parent (`parent_id`). With `returns` on it is a sub-agent: each reply it finishes is sent back to the parent as a `result` message, always with `delivery: steer` (into the running turn, else it starts the next one). With it off nothing goes back; the parent reads it when asked (`krewd chat history`). `returns` can be switched later (`PATCH`); it applies to replies finished from then on. A side session's first message has its parent attached, and the parent hears of it before its next prompt and gets a `link` event.

- Replies owed: a message with `return_to` owes that chat the reply of the turn it ran in or was steered into. In a sub-agent every message gets it except Krewd's own (`compact`, `switch`, `routine`); chained replies stop after 4 hops. A turn that is stopped, fails, is cut by a restart, or whose chat is deleted sends a note instead (`ok: false`), so a chat never waits forever. `waiting_on` counts what is still owed.
- Stop drops the queued messages of the owner, never `result` messages: they run next.
- Proposals: an agent drafts a new chat (`kind: new`, `returns` for a sub-agent) or a message to another chat (`kind: prompt`, only to a chat attached with @ in that chat or linked to it; it is queued there, so it gets its own turn, and with `returns` its reply comes back). It stays `pending` until the owner sends (possibly edited, with another agent, model or `returns`) or dismisses it; `void` when its target is deleted. The proposing chat hears what happened before its next prompt.
- A new agent session's first prompt ends with `(This is Krewd chat <id>.)`, so agents can name their chat to `krewd side` and `krewd chat`.

## Routines

A routine is owner instructions that Krewd runs on a schedule of one or more named triggers. Every run goes into the routine's own chat (made with the routine, made again if deleted), and the chat can be compacted or pinned like any task. Each run starts the agent in a fresh session (empty context), so a routine does not get dearer run after run; the last run's session stays until the next run, so the owner can follow up on it in the chat, and what runs learn is kept in the routine's notes, which every run's prompt carries. The schedule is a 5-field cron expression (`minute hour day-of-month month day-of-week`; `L` for the month's last day) read in the routine's timezone (default `KREWD_TIMEZONE`, else the machine's). Calendar rules belong in the instructions: each run's prompt gives the date, weekday and the month's last day.

Triggers: each has a `name`, a cron `schedule`, `max_attempts` (1 to 10) and `enabled`; related jobs at different times (check-in at 09:55, check-out at 19:00) are one routine with one chat. Triggers due at the same time make one run that names them all. Each run's prompt says which trigger fired and the attempt ("Attempt 1 of 3"), and shows the routine's `notes` (what earlier runs learned; agents add to them with `krewd routines notes`).

Follow-ups: during a run, `POST /api/routines/{id}/later` asks Krewd to fire the same trigger again later as its next attempt (a `once` trigger with `parent`, `attempt` and `reason`). Krewd refuses past the trigger's `max_attempts`, so retries stay bounded; a new follow-up replaces a pending one, the next scheduled firing drops a pending one, and pausing the routine drops them all. While the previous run is still going, a due follow-up waits a minute instead of being skipped.

The scheduler looks every 15 s. A due trigger is claimed by moving its `next_run_at` first, so it fires once. A run missed while Krewd was down runs once if Krewd is back within 6 hours, else it is recorded as `skipped`. A run while the routine's previous run is still queued or running is `skipped` (scheduled) or refused with 409 (run now). Changing the title, instructions or schedule makes a new version; every version is kept.

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/routines` | | routines, enabled first, by next run |
| `POST /api/routines` | `{title, instructions, triggers: [{name, schedule, max_attempts?, enabled?}] (or schedule for one trigger "main"), timezone?, engine?, mode?, config?, auto_approve?, enabled?, notes?, note?, by?: owner\|agent}` | the routine (201); 422 for a bad schedule, timezone or trigger name |
| `POST /api/routines/preview` | `{schedule, timezone?}` | `{schedule, timezone, schedule_text, next_runs}`; 422 with the reason when it is not valid |
| `GET /api/routines/{id}` | | the routine with `runs` (last 50) |
| `PATCH /api/routines/{id}` | any of the create fields; `triggers` replaces the list, matched by name (a trigger whose schedule is unchanged keeps its next time) | the routine. A new `engine` switches the routine's chat to that agent (with a handover); `config` and `mode` are the picks for the routine's agent and reset to the new agent's own when the agent changes without new picks. |
| `PUT /api/routines/{id}/notes` | `{notes, append?}` | the routine; notes are at most 8000 characters and not versioned |
| `POST /api/routines/{id}/later` | `{minutes? \| at?, reason}` | the follow-up trigger (201); 409 outside a run or with no attempts left; 1 minute to 12 hours ahead |
| `DELETE /api/routines/{id}/later/{trigger_id}` | | `{ok}`: cancels a follow-up |
| `DELETE /api/routines/{id}` | | `{ok}`; the routine and its runs go, its chat stays as an ordinary task |
| `POST /api/routines/{id}/run` | `{trigger?}` | the run (202) as that trigger (default: the first), on top of the schedule |
| `GET /api/routines/{id}/runs?limit=50` | | runs, newest first |
| `GET /api/routines/{id}/versions` | | versions, newest first: `{version, title, instructions, schedule, timezone, changed_by, note, created_at}` |

Routine: `{id, title, instructions, triggers: [{id, name, kind: cron|once, schedule, schedule_text, enabled, max_attempts, next_run_at, attempt, reason, parent}], notes, schedule (the first trigger's), schedule_text, timezone, session_id, engine, mode, config, auto_approve, enabled, version, next_run_at (soonest trigger), next_runs (next 3), upcoming: [{at, trigger, attempt}], last_run, session_status, created_by, created_at, updated_at}`. Run: `{id, routine_id, session_id, message_id, seq, trigger: schedule|manual, triggers (names that fired), attempt, max_attempts, version, status: queued|running|done|error|cancelled|skipped, summary, scheduled_for, started_at, finished_at, created_at}`. `seq` is the run's message in the chat timeline, so the app can scroll to it; `summary` is the agent's last reply (clipped).

Agents manage routines with the `krewd` command on the Krewd machine (`deploy/krewd-cli.sh`, linked as `~/.local/bin/krewd` by `master.sh setup`): `krewd routines list|show|create|update|pause|resume|run|later|notes|delete|versions` and `krewd schedule '<cron>'`. It uses this API with the token in `~/.config/krewd/token`. Its help text carries the agents' rules (ask before creating a routine when an existing one may be the one to update).

## The agents' browser

Browser use only (no desktop control). Krewd talks DevTools to the agents' Brave on `127.0.0.1:$KREWD_CDP_PORT`.

| Call | Body | Answer |
|---|---|---|
| `GET /api/browser/tabs` | | `{available, reason?, tabs: [{id, title, url, session_id}]}`; `session_id`: the task whose agent last used the tab |
| `POST /api/browser/tabs` | `{url?}` | the new tab `{id, title, url}` |
| `DELETE /api/browser/tabs/{id}` | | `{ok}` |
| `POST /api/browser/tabs/{id}/view` | `{mode: vertical, width, height, scale}` or `{mode: horizontal}` | `{ok}`. Vertical lays the page out for the phone (CSS pixels, device scale) with `Emulation.setDeviceMetricsOverride`, without a reload. The override belongs to Krewd's DevTools connection: it ends when nobody watches the tab, and Krewd clears it as soon as an agent starts using the tab. |
| `GET /api/browser/tabs/{id}/stream` | `?quality=standard` (default: at most 30 fps per viewer, JPEG quality 50) or `high` (60 fps, quality 85); both full 1080p. Viewers of one tab share its screencast, and the newest viewer's quality applies | `multipart/x-mixed-replace; boundary=frame`: one JPEG per part, with `Content-Length`, `X-Viewport: <width>x<height>` (CSS pixels), `X-View: vertical|horizontal` and `X-Scroll: <scroll y>/<page height>` (CSS pixels, for the phone's scroll bar). The tab is brought to the front first (background tabs do not paint); Brave draws on its own invisible screen (`KREWD_BROWSER_DISPLAY=virtual`), so this never shows on or takes focus from the machine's desktop. The last frame is repeated every 10 s while nothing changes. |
| `GET /api/browser/tabs/{id}/snapshot` | `?width=360` (80 to 1280 pixels) | `image/jpeg`: one still of the tab's visible viewport, scaled to that width, for the app's working-task cards. The tab is not brought to the front. 404 when the tab is gone, 503 when the browser gives no picture. |
| `POST /api/browser/tabs/{id}/input` | `{type: mouse, action: move\|down\|up\|click, x, y, button?: left\|right\|middle, clicks?: 1\|2, held?}` (`held` on moves while a button is down; `clicks: 2` on down and up for the second press of a double-click), `{type: tap, x, y}`, `{type: scroll, x, y, dx, dy}` (all 0..1 of the frame), `{type: text, text}`, `{type: key, key, modifiers?}` (`Enter`, `Backspace`, `Delete`, `Tab`, `Escape`, `Space`, arrows, `Home`, `End`, `PageUp`, `PageDown`, or one letter or digit for shortcuts; `modifiers`: any of `ctrl`, `alt`, `shift`, `meta`, so `{key: ArrowLeft, modifiers: [ctrl]}` jumps a word), `{type: navigate, action: back or forward or reload}` or `{type: navigate, url}` | `{ok: true}`; after a left click or release also `{editable, password}`: whether a text field now has focus, so the phone can open its keyboard |

View only: while the session that last drove a tab is running a turn, the tab is the agent's. The tab list marks it `agent_active: true`, and the phone's input, `view: vertical` and closing that tab answer 409 until the turn ends (Stop ends it). Each tab in the list also has `view: vertical|horizontal`, the owner's layout for it: the server keeps a vertical choice across reconnects and drops it as soon as an agent uses the tab, since agents work in the 1080p layout.

Which tab an agent uses is seen from the browser side, so it works for any engine: Krewd follows the browser's tabs over DevTools (`Target.setDiscoverTargets`), and a tab that opens or navigates while the owner is not touching it is linked to the one session with a running turn (or, if several run, the one in the middle of a tool call). That session gets a `browser` event and `browser_tab`.

Typed text is passed to `Input.insertText` and nowhere else: not logged, not stored, not added to the task's events.

### Location

Pages in the agents' Brave are told the location the owner applies here (`locations.py`; the extension `deploy/fixed-location` answers the page's Geolocation API). It changes only when the owner applies one: nothing follows the phone. Until one is applied, `KREWD_BROWSER_LOCATION` is the current location (`source: config`).

| Route | Body | Returns |
| --- | --- | --- |
| `GET /api/browser/location` | | the state: `{current, browser, detail, recent, favorites, config}` |
| `POST /api/browser/location` | `{lat, lon, accuracy? (metres, 1..100000, default 50), name?, source?: phone\|manual\|favorite\|recent}` | the state. Makes it current and sends it to Brave. Applying a place already among the recent ones moves it to the top instead of adding it again. |
| `POST /api/browser/location/reapply` | | the state; sends the current location to Brave again |
| `POST /api/browser/location/favorites` | `{name, lat, lon, accuracy?}` | the state (201); at most 50 favorites |
| `PATCH /api/browser/location/favorites/{id}` | `{name}` | the state |
| `DELETE /api/browser/location/{id}` | | the state; forgets a favorite or a recent place (not the current one: 422) |

A place: `{id, name, lat, lon, accuracy, source, applied_at | created_at}`. `current` is the newest applied place (or the config one, `id: null`), `recent` the up to 3 applied before it, newest first, `favorites` the named places by name. `browser`: `applied` (Brave holds the current location), `waiting` (Brave is not running; it gets the location when it starts), `error` (with `detail`) or `unknown`. To change Brave's location the server opens the extension's own page (`set.html`, not reachable from websites) in a background tab over DevTools, reads the answer from its title and closes it; the tab list never shows that page. When Brave starts, the server checks that it still holds the current location and puts it back if not.


## Storage

`GET /api/storage` reports what each chat takes on the machine, largest first: `{database_bytes, total_bytes, chats: [{id, title, engine, status, pinned, archived, routine_id, updated_at, bytes, history: {bytes, events, repeats_bytes, repeats}, attachments: {bytes, count}, tool_images: {bytes, count}, transcript: {bytes, count}}], reclaimable: {repeats_bytes, loose_files: {bytes, count}, loose_transcripts: {bytes, count}}}`.

- `history`: the chat's events in PostgreSQL. `repeats` are older copies the app never shows: every change to a chat stores a `session` snapshot, and every tool-call update stores the whole tool call again. Since 0.19.0 only the newest snapshot is kept as it is written.
- `transcript`: the agent's own record of the chat's ACP sessions (Claude Code `~/.claude/projects/*/<id>.jsonl` and `<id>/`, Codex `~/.codex/sessions/**/rollout-*-<id>.jsonl`; `CLAUDE_CONFIG_DIR` and `CODEX_HOME` are honoured). The agent resumes the chat from it, so only deleting the chat removes it.
- `loose_files`: attachments and tool images of chats that no longer exist. `loose_transcripts`: transcripts Krewd's agents wrote (Claude Code through the SDK in `KREWD_WORKDIR`, Codex through codex-acp) that no chat uses (deleted chats, sessions replaced by a handover compact, probes) and that nothing changed for 24 hours. The owner's own terminal sessions never count.

`POST /api/storage/clean` frees all of `reclaimable` (repeats only in chats that are not working), then runs `VACUUM FULL events` so the space goes back to the disk. Returns `{repeats_bytes, files_bytes, transcripts_bytes, bytes}`. Live chats keep their messages, attachments and agent sessions, and each tool call stays in its place with its latest state.

`POST /api/sessions/{id}/trim` goes further for one chat, still without touching the agent: it drops the repeats, cuts each tool call's input and output to 600 characters and removes its tool images. Messages, replies, attachments and the agent's session stay, so the agent resumes it as before.

## Attachments

1. `POST /api/uploads?name=<file name>` with the raw file as the body (any `Content-Type`; streamed to disk, at most `KREWD_MAX_UPLOAD_MB`, default 1024) returns `{id, name, size, mime}`. Uploads not used within 24 hours are deleted.
2. Send the ids with a message: `POST /api/sessions` or `POST /api/sessions/{id}/messages` with `{text?, attachments: [id, ...]}` (text may be empty when files are attached). Each id works once.

Files are saved as `~/.krewd/common/attachments/<first 8 characters of the session id>-<file name>` (`KREWD_DATA_DIR` changes `~/.krewd`), with `-2`, `-3` added on name clashes. The `user` event lists them as `attachments: [{name, path, size, mime}]`, and the agent's prompt gets the paths as text plus one ACP `resource_link` per file.

## Files from the agent

An agent hands a file back by saving it in a shared folder (`KREWD_SHARE_DIRS`, colon-separated; default `$KREWD_WORKDIR/outputs`) and writing its path in the reply. At the end of the turn, every absolute or `~` path in the reply that names a regular file inside a shared folder is listed on `turn_end` as `files: [{path, name, size, mime}]` (at most 10).

`GET /api/files?path=<path>` returns the file as a download. Only regular files inside the shared folders are served, after resolving symlinks and `..`; anything else is 404.

## Images in tool results

When a tool result carries an image (an ACP image content block, or an `image` block with base64 `source` in the raw output, as Claude Code's Read returns for a screenshot), Krewd saves it once as `~/.krewd/common/tool-images/<first 8 characters of the session id>-<content hash>.<png|jpg|gif|webp>` and lists the names on the `tool` event as `images: [name, ...]`. The base64 never appears in `output`. `GET /api/tool-images/{name}` returns the image; any other name is 404.
