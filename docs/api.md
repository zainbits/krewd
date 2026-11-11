# Crew server API

Base URL: `http://<server LAN address>:8800` (home network) or `https://<machine>.<tailnet>.ts.net:8800` (Tailscale Serve). `./master.sh status` prints both.
Every route except `/api/health` needs `Authorization: Bearer <token>`.

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/health` | | `{ok, version}` |
| `GET /api/state` | | `{last_seq, engines: [{id, name, default, agent, config_options, modes, steering, base, profile, profile_name, problem}], workdir}`. `steering` is true when the agent advertised `_meta.steering.supported` in its ACP `initialize` answer (read from the agent, never assumed per engine). An engine never seen yet is probed in the background (a throwaway session), so its `config_options` fill in a few seconds later. Profile engines (the same agent on a gateway from `profiles.toml`, such as `codex@openrouter`) have `base` (the agent, which itself runs on its own login), `profile`, `profile_name` and `problem` (why it cannot start, such as a missing key, else null); other engines have nulls there, except that an agent that is not signed in has a `problem` (its own engine is its sign-in account). `preferred` marks, per agent, the engine its new tasks start on. A profile engine's probe opens two sessions (Claude Code lists a gateway's models from its second session on), then stops the process until a chat uses it. Profile engines are asked again on each start and, while no chat runs them, every 10 minutes, since a gateway's models follow the key's limits. |
| `GET /api/profiles` | | `{profiles: [{id, name, openai, anthropic, key_set, key_hint, engines}], presets: [{id, name, openai, anthropic}], managed}`. Keys never come back: only whether one is set and its last 4 characters. |
| `POST /api/profiles` | `{name, openai?, anthropic?, key, preset?}` | the profile; 201. The gateway is asked for the key's models first (422 with the reason when it refuses). Saved to `profiles.toml`, the key to `keys.env` (mode 600); the new `<agent>@<id>` engines are usable at once. |
| `PATCH /api/profiles/{id}` | `{name?, openai?, anthropic?, key?}` | the profile. `""` removes an address. Idle agent processes on it restart with the new settings; a running turn finishes on the old ones. 409 when the change takes away an engine a chat, routine or agent default still uses. |
| `DELETE /api/profiles/{id}` | | `{ok}`; also removes its key from `keys.env`. 409 while a chat (not archived), routine or agent default uses it. |
| `GET /api/logins` | | `{agents: [{engine, name, gateway, logged_in, account, error, default, default_choices, flow}]}`: each agent's sign-in account, from its CLI's status (cached a minute), the profile its new tasks start on (`default`, null: the sign-in account), and a sign-in in progress (`flow`: `{state, url, code, paste, error, expires_in}`). |
| `POST /api/logins/{engine}` | | `flow`: starts the agent's sign-in on the server. Claude Code: open `url`, then send the code the page shows (`paste` true). Codex: open `url` and type `code` there; it finishes by itself. |
| `POST /api/logins/{engine}/code` | `{code}` | `flow` once the CLI is done (Claude Code). |
| `DELETE /api/logins/{engine}` | | `{ok}`; cancels a sign-in in progress. |
| `POST /api/logins/{engine}/logout` | | the new status. |
| `PUT /api/logins/{engine}/default` | `{profile}` (null: the sign-in account) | the agent's entry: the profile its new tasks start on (`[defaults]` in `profiles.toml`). The engine list marks it `preferred`. Chats keep their own profile, and the agent's own engine always runs on its sign-in account. |
| `GET /api/codex-catalog/{secret}/{engine}` | none (the secret) | Codex's model catalog for a Codex profile engine, built from the gateway's model list. Only Crew's own Codex processes get the URL (`model_catalog_url`), with a secret made at each start; anything else gets 404. 502 when the gateway cannot be reached. |
| `GET /api/sessions?archived=false` | | sessions, pinned first, newest first |
| `POST /api/sessions` | `{text, engine?, mode?, auto_approve?, config?: {config_id: value}, attachments?: [upload_id], tabs?: [{id, title, url}], client_id?}` | the new session; its first turn starts at once |
| `GET /api/sessions/{id}` | | session |
| `DELETE /api/sessions/{id}` | | `{ok}`; stops the task's turn, closes its agent session where the agent supports it, and deletes its history, approvals and messages (the app waits 3 s for Undo before calling this) |
| `PATCH /api/sessions/{id}` | `{title?, auto_approve?, pinned?, archived?, mode?, config?: {config_id: value}}` | session |
| `POST /api/sessions/{id}/messages` | `{text, attachments?, tabs?, delivery?: steer\|queue\|interrupt, client_id?}` | `{ok, message}` (see Messages below) |
| `POST /api/sessions/{id}/cancel` | | `{ok}`; stops the turn, answers its open approvals with "cancelled" and drops the messages queued behind it |
| `POST /api/sessions/{id}/engine` | `{engine, config?, mode?}` | `{ok, message}`; moves the chat to another agent after the running turn (see Switching agents) |
| `GET /api/sessions/{id}/transcript?after=0&last=N` | | the chat as messages `[{seq, from, text}]` (owner, agents, Crew notes), without tool calls or thinking |
| `POST /api/sessions/{id}/compact` | | `{ok, message}`; compacts the agent's context after the running turn (see Compact below); 409 when the chat has no agent context yet. A second call while one is queued returns the queued one. |
| `POST /api/admin/restart` | | `{ok}`; emergency: stops every agent process and exits, so systemd starts the server again (about 10 to 15 s). Interrupted turns end with an error; queued messages run after the restart. The browser is not touched. |
| `GET /api/sessions/{id}/events?after=0` | | `{events, last_seq}`: the task's whole timeline |
| `GET /api/events?after=N&wait=25` | | long-poll across all tasks: returns once there is an event after N |
| `GET /api/approvals?status=pending` | | approvals |
| `POST /api/approvals/{id}` | `{option_id}` | the decided approval; 409 if it is no longer waiting |

Session: `{id, engine, title, status (idle|running|waiting|error), mode, modes, auto_approve, usage, last_text, pinned, archived, pending_approvals, browser_tab, routine_id, compact, created_at, updated_at}`. `routine_id` is set on a routine's own chat. `compact` is `native` when the agent advertises a `compact` slash command (ACP `available_commands_update`), else `handover`. `pinned` sessions come first in the list. `browser_tab` `{id, url, title}` is the tab the agent last used (see The agents' browser). `modes` is what the agent advertises (ACP `availableModes`), so the app shows each agent's own modes.

Harness, model and effort (agent-agnostic): sessions also carry `agent` (ACP `agentInfo`: `{name, title, version}`), `config_options` (the agent's ACP `configOptions`, flattened: `[{id, name, description, category, type, current, options: [{value, name, description, group?}]}]`; categories include `model`, `thought_level` (effort) and `mode`), `config` (the user's picks, re-sent to the agent after a restart) and `model_used` (the model the agent reported for its last step, from usage metadata). Setting `config` calls ACP `session/set_config_option`; the agent's reply replaces `config_options` (a model change can change the effort choices).

Event: `{seq, session_id, kind, payload, created_at}`. Kinds:

- `user` `{text, message_id, delivery, client_id?, attachments?, tabs?, purpose?, meta?}`: `purpose` is set when Crew sent the message itself: `routine` (a run; `meta` `{routine_id, run_id, title, trigger, version}`) or `compact` (`meta` `{how}`)
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

One chat can move between agents (Claude Code, Codex, any ACP agent) and stays one chat. Each agent keeps its own session and settings in the chat (`session_agents`), with the last event it saw. On a switch the current agent first writes a handover (skipped when it has no context yet); Crew parks its session, brings in the target agent's own session (or a new one), and puts a briefing in front of the next message: the handovers written since that agent last saw the chat, the previous agents' session ids, and `crew chat history <chat id>` for exact messages. A returning agent resumes its own session and hears only what it missed. The `switch` event `{from, to, from_name, to_name, from_session, summary}` marks it in the timeline. A routine's chat and its routine always use the same agent: changing the routine's agent switches its chat, and switching the chat changes the routine.

## Compact

Where the agent advertises a `compact` slash command (Claude Code and Codex do), Crew sends `/compact` as the next prompt and the agent keeps its session. Otherwise (OpenCode) the agent is asked for a handover summary; Crew then closes that agent session, opens a fresh one in the same chat, and puts the handover in front of the next message. The chat history in Crew is never changed. `usage` is cleared after a handover.

## Routines

A routine is owner instructions that Crew runs on a schedule of one or more named triggers. Every run goes into the routine's own chat (made with the routine, made again if deleted), so later runs see earlier ones and the owner's replies, and the chat can be compacted or pinned like any task. The schedule is a 5-field cron expression (`minute hour day-of-month month day-of-week`; `L` for the month's last day) read in the routine's timezone (default `CREW_TIMEZONE`, else the machine's). Calendar rules belong in the instructions: each run's prompt gives the date, weekday and the month's last day.

Triggers: each has a `name`, a cron `schedule`, `max_attempts` (1 to 10) and `enabled`; related jobs at different times (check-in at 09:55, check-out at 19:00) are one routine with one chat. Triggers due at the same time make one run that names them all. Each run's prompt says which trigger fired and the attempt ("Attempt 1 of 3"), and shows the routine's `notes` (what earlier runs learned; agents add to them with `crew routines notes`).

Follow-ups: during a run, `POST /api/routines/{id}/later` asks Crew to fire the same trigger again later as its next attempt (a `once` trigger with `parent`, `attempt` and `reason`). Crew refuses past the trigger's `max_attempts`, so retries stay bounded; a new follow-up replaces a pending one, the next scheduled firing drops a pending one, and pausing the routine drops them all. While the previous run is still going, a due follow-up waits a minute instead of being skipped.

The scheduler looks every 15 s. A due trigger is claimed by moving its `next_run_at` first, so it fires once. A run missed while Crew was down runs once if Crew is back within 6 hours, else it is recorded as `skipped`. A run while the routine's previous run is still queued or running is `skipped` (scheduled) or refused with 409 (run now). Changing the title, instructions or schedule makes a new version; every version is kept.

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

Agents manage routines with the `crew` command on the Crew machine (`deploy/crew-cli.sh`, linked as `~/.local/bin/crew` by `master.sh setup`): `crew routines list|show|create|update|pause|resume|run|later|notes|delete|versions` and `crew schedule '<cron>'`. It uses this API with the token in `~/.config/crew/token`. Its help text carries the agents' rules (ask before creating a routine when an existing one may be the one to update).

## The agents' browser

Browser use only (no desktop control). Crew talks DevTools to the agents' Brave on `127.0.0.1:$CREW_CDP_PORT`.

| Call | Body | Answer |
|---|---|---|
| `GET /api/browser/tabs` | | `{available, reason?, tabs: [{id, title, url, session_id}]}`; `session_id`: the task whose agent last used the tab |
| `POST /api/browser/tabs` | `{url?}` | the new tab `{id, title, url}` |
| `DELETE /api/browser/tabs/{id}` | | `{ok}` |
| `POST /api/browser/tabs/{id}/view` | `{mode: vertical, width, height, scale}` or `{mode: horizontal}` | `{ok}`. Vertical lays the page out for the phone (CSS pixels, device scale) with `Emulation.setDeviceMetricsOverride`, without a reload. The override belongs to Crew's DevTools connection: it ends when nobody watches the tab, and Crew clears it as soon as an agent starts using the tab. |
| `GET /api/browser/tabs/{id}/stream` | `?quality=standard` (default: at most 30 fps per viewer, JPEG quality 50) or `high` (60 fps, quality 85); both full 1080p. Viewers of one tab share its screencast, and the newest viewer's quality applies | `multipart/x-mixed-replace; boundary=frame`: one JPEG per part, with `Content-Length`, `X-Viewport: <width>x<height>` (CSS pixels), `X-View: vertical|horizontal` and `X-Scroll: <scroll y>/<page height>` (CSS pixels, for the phone's scroll bar). The tab is brought to the front first (background tabs do not paint). The last frame is repeated every 10 s while nothing changes. |
| `GET /api/browser/tabs/{id}/snapshot` | `?width=360` (80 to 1280 pixels) | `image/jpeg`: one still of the tab's visible viewport, scaled to that width, for the app's working-task cards. The tab is not brought to the front. 404 when the tab is gone, 503 when the browser gives no picture. |
| `POST /api/browser/tabs/{id}/input` | `{type: mouse, action: move\|down\|up\|click, x, y, button?: left\|right\|middle, clicks?: 1\|2, held?}` (`held` on moves while a button is down; `clicks: 2` on down and up for the second press of a double-click), `{type: tap, x, y}`, `{type: scroll, x, y, dx, dy}` (all 0..1 of the frame), `{type: text, text}`, `{type: key, key, modifiers?}` (`Enter`, `Backspace`, `Delete`, `Tab`, `Escape`, `Space`, arrows, `Home`, `End`, `PageUp`, `PageDown`, or one letter or digit for shortcuts; `modifiers`: any of `ctrl`, `alt`, `shift`, `meta`, so `{key: ArrowLeft, modifiers: [ctrl]}` jumps a word), `{type: navigate, action: back or forward or reload}` or `{type: navigate, url}` | `{ok: true}`; after a left click or release also `{editable, password}`: whether a text field now has focus, so the phone can open its keyboard |

View only: while the session that last drove a tab is running a turn, the tab is the agent's. The tab list marks it `agent_active: true`, and the phone's input, `view: vertical` and closing that tab answer 409 until the turn ends (Stop ends it). Each tab in the list also has `view: vertical|horizontal`, the owner's layout for it: the server keeps a vertical choice across reconnects and drops it as soon as an agent uses the tab, since agents work in the 1080p layout.

Which tab an agent uses is seen from the browser side, so it works for any engine: Crew follows the browser's tabs over DevTools (`Target.setDiscoverTargets`), and a tab that opens or navigates while the owner is not touching it is linked to the one session with a running turn (or, if several run, the one in the middle of a tool call). That session gets a `browser` event and `browser_tab`.

Typed text is passed to `Input.insertText` and nowhere else: not logged, not stored, not added to the task's events.


## Attachments

1. `POST /api/uploads?name=<file name>` with the raw file as the body (any `Content-Type`; streamed to disk, at most `CREW_MAX_UPLOAD_MB`, default 1024) returns `{id, name, size, mime}`. Uploads not used within 24 hours are deleted.
2. Send the ids with a message: `POST /api/sessions` or `POST /api/sessions/{id}/messages` with `{text?, attachments: [id, ...]}` (text may be empty when files are attached). Each id works once.

Files are saved as `~/.crew/common/attachments/<first 8 characters of the session id>-<file name>` (`CREW_DATA_DIR` changes `~/.crew`), with `-2`, `-3` added on name clashes. The `user` event lists them as `attachments: [{name, path, size, mime}]`, and the agent's prompt gets the paths as text plus one ACP `resource_link` per file.

## Files from the agent

An agent hands a file back by saving it in a shared folder (`CREW_SHARE_DIRS`, colon-separated; default `$CREW_WORKDIR/outputs`) and writing its path in the reply. At the end of the turn, every absolute or `~` path in the reply that names a regular file inside a shared folder is listed on `turn_end` as `files: [{path, name, size, mime}]` (at most 10).

`GET /api/files?path=<path>` returns the file as a download. Only regular files inside the shared folders are served, after resolving symlinks and `..`; anything else is 404.

## Images in tool results

When a tool result carries an image (an ACP image content block, or an `image` block with base64 `source` in the raw output, as Claude Code's Read returns for a screenshot), Crew saves it once as `~/.crew/common/tool-images/<first 8 characters of the session id>-<content hash>.<png|jpg|gif|webp>` and lists the names on the `tool` event as `images: [name, ...]`. The base64 never appears in `output`. `GET /api/tool-images/{name}` returns the image; any other name is 404.
