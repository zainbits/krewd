# Crew server API

Base URL: `http://<server LAN address>:8800` (home network) or `https://<machine>.<tailnet>.ts.net:8800` (Tailscale Serve). `./master.sh status` prints both.
Every route except `/api/health` needs `Authorization: Bearer <token>`.

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/health` | | `{ok, version}` |
| `GET /api/state` | | `{last_seq, engines: [{id, name, default, agent, config_options, modes, steering}], workdir}`. `steering` is true when the agent advertised `_meta.steering.supported` in its ACP `initialize` answer (read from the agent, never assumed per engine). An engine never seen yet is probed in the background (a throwaway session), so its `config_options` fill in a few seconds later. |
| `GET /api/sessions?archived=false` | | sessions, pinned first, newest first |
| `POST /api/sessions` | `{text, engine?, mode?, auto_approve?, config?: {config_id: value}, attachments?: [upload_id], tabs?: [{id, title, url}], client_id?}` | the new session; its first turn starts at once |
| `GET /api/sessions/{id}` | | session |
| `DELETE /api/sessions/{id}` | | `{ok}`; stops the task's turn, closes its agent session where the agent supports it, and deletes its history, approvals and messages (the app waits 3 s for Undo before calling this) |
| `PATCH /api/sessions/{id}` | `{title?, auto_approve?, pinned?, archived?, mode?, config?: {config_id: value}}` | session |
| `POST /api/sessions/{id}/messages` | `{text, attachments?, tabs?, delivery?: steer\|queue\|interrupt, client_id?}` | `{ok, message}` (see Messages below) |
| `POST /api/sessions/{id}/cancel` | | `{ok}`; stops the turn, answers its open approvals with "cancelled" and drops the messages queued behind it |
| `POST /api/admin/restart` | | `{ok}`; emergency: stops every agent process and exits, so systemd starts the server again (about 10 to 15 s). Interrupted turns end with an error; queued messages run after the restart. The browser is not touched. |
| `GET /api/sessions/{id}/events?after=0` | | `{events, last_seq}`: the task's whole timeline |
| `GET /api/events?after=N&wait=25` | | long-poll across all tasks: returns once there is an event after N |
| `GET /api/approvals?status=pending` | | approvals |
| `POST /api/approvals/{id}` | `{option_id}` | the decided approval; 409 if it is no longer waiting |

Session: `{id, engine, title, status (idle|running|waiting|error), mode, modes, auto_approve, usage, last_text, pinned, archived, pending_approvals, browser_tab, created_at, updated_at}`. `browser_tab` `{id, url, title}` is the tab the agent last used (see The agents' browser). `modes` is what the agent advertises (ACP `availableModes`), so the app shows each agent's own modes.

Harness, model and effort (agent-agnostic): sessions also carry `agent` (ACP `agentInfo`: `{name, title, version}`), `config_options` (the agent's ACP `configOptions`, flattened: `[{id, name, description, category, type, current, options: [{value, name, description, group?}]}]`; categories include `model`, `thought_level` (effort) and `mode`), `config` (the user's picks, re-sent to the agent after a restart) and `model_used` (the model the agent reported for its last step, from usage metadata). Setting `config` calls ACP `session/set_config_option`; the agent's reply replaces `config_options` (a model change can change the effort choices).

Event: `{seq, session_id, kind, payload, created_at}`. Kinds:

- `user` `{text, message_id, delivery, client_id?, attachments?, tabs?}`
- `message` message object, whenever its state changes (delivered or cancelled)
- `browser` `{id, url, title}`: the agent started using a tab
- `text` `{message_id, role: assistant|thought, text}`: append to the row with the same `message_id`
- `tool` `{tool_call_id, title, kind, status, input, output, paths}`: replace the row with the same id
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


## The agents' browser

Browser use only (no desktop control). Crew talks DevTools to the agents' Brave on `127.0.0.1:$CREW_CDP_PORT`.

| Call | Body | Answer |
|---|---|---|
| `GET /api/browser/tabs` | | `{available, reason?, tabs: [{id, title, url, session_id}]}`; `session_id`: the task whose agent last used the tab |
| `POST /api/browser/tabs` | `{url?}` | the new tab `{id, title, url}` |
| `DELETE /api/browser/tabs/{id}` | | `{ok}` |
| `POST /api/browser/tabs/{id}/view` | `{mode: vertical, width, height, scale}` or `{mode: horizontal}` | `{ok}`. Vertical lays the page out for the phone (CSS pixels, device scale) with `Emulation.setDeviceMetricsOverride`, without a reload. The override belongs to Crew's DevTools connection: it ends when nobody watches the tab, and Crew clears it as soon as an agent starts using the tab. |
| `GET /api/browser/tabs/{id}/stream` | `?quality=standard` (default: 30 fps, JPEG quality 50) or `high` (60 fps, quality 85); both full 1080p. Viewers of one tab share its screencast, and the newest viewer's quality applies | `multipart/x-mixed-replace; boundary=frame`: one JPEG per part, with `Content-Length`, `X-Viewport: <width>x<height>` (CSS pixels), `X-View: vertical|horizontal` and `X-Scroll: <scroll y>/<page height>` (CSS pixels, for the phone's scroll bar). The tab is brought to the front first (background tabs do not paint). The last frame is repeated every 10 s while nothing changes. |
| `POST /api/browser/tabs/{id}/input` | `{type: mouse, action: move\|down\|up\|click, x, y, button?: left\|right\|middle, clicks?: 1\|2, held?}` (`held` on moves while a button is down; `clicks: 2` on down and up for the second press of a double-click), `{type: tap, x, y}`, `{type: scroll, x, y, dx, dy}` (all 0..1 of the frame), `{type: text, text}`, `{type: key, key, modifiers?}` (`Enter`, `Backspace`, `Delete`, `Tab`, `Escape`, `Space`, arrows, `Home`, `End`, `PageUp`, `PageDown`, or one letter or digit for shortcuts; `modifiers`: any of `ctrl`, `alt`, `shift`, `meta`, so `{key: ArrowLeft, modifiers: [ctrl]}` jumps a word), `{type: navigate, action: back or forward or reload}` or `{type: navigate, url}` | `{ok: true}`; after a left click or release also `{editable, password}`: whether a text field now has focus, so the phone can open its keyboard |

Which tab an agent uses is seen from the browser side, so it works for any engine: Crew follows the browser's tabs over DevTools (`Target.setDiscoverTargets`), and a tab that opens or navigates while the owner is not touching it is linked to the one session with a running turn (or, if several run, the one in the middle of a tool call). That session gets a `browser` event and `browser_tab`.

Typed text is passed to `Input.insertText` and nowhere else: not logged, not stored, not added to the task's events.


## Attachments

1. `POST /api/uploads?name=<file name>` with the raw file as the body (any `Content-Type`; streamed to disk, at most `CREW_MAX_UPLOAD_MB`, default 1024) returns `{id, name, size, mime}`. Uploads not used within 24 hours are deleted.
2. Send the ids with a message: `POST /api/sessions` or `POST /api/sessions/{id}/messages` with `{text?, attachments: [id, ...]}` (text may be empty when files are attached). Each id works once.

Files are saved as `~/.crew/common/attachments/<first 8 characters of the session id>-<file name>` (`CREW_DATA_DIR` changes `~/.crew`), with `-2`, `-3` added on name clashes. The `user` event lists them as `attachments: [{name, path, size, mime}]`, and the agent's prompt gets the paths as text plus one ACP `resource_link` per file.

## Files from the agent

An agent hands a file back by saving it in a shared folder (`CREW_SHARE_DIRS`, colon-separated; default `$CREW_WORKDIR/outputs`) and writing its path in the reply. At the end of the turn, every absolute or `~` path in the reply that names a regular file inside a shared folder is listed on `turn_end` as `files: [{path, name, size, mime}]` (at most 10).

`GET /api/files?path=<path>` returns the file as a download. Only regular files inside the shared folders are served, after resolving symlinks and `..`; anything else is 404.
