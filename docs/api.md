# Crew server API

Base URL: `http://<server LAN address>:8800` (home network) or `https://<machine>.<tailnet>.ts.net:8800` (Tailscale Serve). `./master.sh status` prints both.
Every route except `/api/health` needs `Authorization: Bearer <token>`.

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/health` | | `{ok, version}` |
| `GET /api/state` | | `{last_seq, engines: [{id, name, default, agent, config_options, modes}], workdir}`. An engine never seen yet is probed in the background (a throwaway session), so its `config_options` fill in a few seconds later. |
| `GET /api/sessions?archived=false` | | sessions, pinned first, newest first |
| `POST /api/sessions` | `{text, engine?, mode?, auto_approve?, config?: {config_id: value}, attachments?: [upload_id]}` | the new session; its first turn starts at once |
| `GET /api/sessions/{id}` | | session |
| `PATCH /api/sessions/{id}` | `{title?, auto_approve?, pinned?, archived?, mode?, config?: {config_id: value}}` | session |
| `POST /api/sessions/{id}/messages` | `{text}` | `{ok}`; queued if a turn is running |
| `POST /api/sessions/{id}/cancel` | | `{ok}`; stops the turn and answers its open approvals with "cancelled" |
| `GET /api/sessions/{id}/events?after=0` | | `{events, last_seq}`: the task's whole timeline |
| `GET /api/events?after=N&wait=25` | | long-poll across all tasks: returns once there is an event after N |
| `GET /api/approvals?status=pending` | | approvals |
| `POST /api/approvals/{id}` | `{option_id}` | the decided approval; 409 if it is no longer waiting |

Session: `{id, engine, title, status (idle|running|waiting|error), mode, modes, auto_approve, usage, last_text, pinned, archived, pending_approvals, created_at, updated_at}`. `modes` is what the agent advertises (ACP `availableModes`), so the app shows each agent's own modes.

Harness, model and effort (agent-agnostic): sessions also carry `agent` (ACP `agentInfo`: `{name, title, version}`), `config_options` (the agent's ACP `configOptions`, flattened: `[{id, name, description, category, type, current, options: [{value, name, description, group?}]}]`; categories include `model`, `thought_level` (effort) and `mode`), `config` (the user's picks, re-sent to the agent after a restart) and `model_used` (the model the agent reported for its last step, from usage metadata). Setting `config` calls ACP `session/set_config_option`; the agent's reply replaces `config_options` (a model change can change the effort choices).

Event: `{seq, session_id, kind, payload, created_at}`. Kinds:

- `user` `{text}`
- `text` `{message_id, role: assistant|thought, text}`: append to the row with the same `message_id`
- `tool` `{tool_call_id, title, kind, status, input, output, paths}`: replace the row with the same id
- `plan` `{entries: [{content, status}]}`
- `approval` approval object (pending, then again when decided)
- `turn_start` `{}`, `turn_end` `{stop_reason, usage}`, `error` `{message}`
- `session` session object, whenever it changes

Clients replay a task with `/sessions/{id}/events`, then follow `/api/events` from `last_seq`. Events are durable, so a client can disconnect at any time and continue from the last seq it saw.


## The agents' browser

Browser use only (no desktop control). Crew talks DevTools to the agents' Brave on `127.0.0.1:$CREW_CDP_PORT`.

| Call | Body | Answer |
|---|---|---|
| `GET /api/browser/tabs` | | `{available, reason?, tabs: [{id, title, url}]}` |
| `GET /api/browser/tabs/{id}/stream` | | `multipart/x-mixed-replace; boundary=frame`: one JPEG per part, with `Content-Length` and `X-Viewport: <width>x<height>` (CSS pixels). The tab is brought to the front first (background tabs do not paint). The last frame is repeated every 10 s while nothing changes. |
| `POST /api/browser/tabs/{id}/input` | `{type: tap, x, y}`, `{type: scroll, x, y, dx, dy}` (all 0..1 of the frame), `{type: text, text}`, `{type: key, key}` (`Enter`, `Backspace`, `Tab`, `Escape`, arrows, `PageUp`, `PageDown`), `{type: navigate, action: back or forward or reload}` or `{type: navigate, url}` | `{ok: true}` |

Typed text is passed to `Input.insertText` and nowhere else: not logged, not stored, not added to the task's events.


## Attachments

1. `POST /api/uploads?name=<file name>` with the raw file as the body (any `Content-Type`; streamed to disk, at most `CREW_MAX_UPLOAD_MB`, default 1024) returns `{id, name, size, mime}`. Uploads not used within 24 hours are deleted.
2. Send the ids with a message: `POST /api/sessions` or `POST /api/sessions/{id}/messages` with `{text?, attachments: [id, ...]}` (text may be empty when files are attached). Each id works once.

Files are saved as `~/.crew/common/attachments/<first 8 characters of the session id>-<file name>` (`CREW_DATA_DIR` changes `~/.crew`), with `-2`, `-3` added on name clashes. The `user` event lists them as `attachments: [{name, path, size, mime}]`, and the agent's prompt gets the paths as text plus one ACP `resource_link` per file.
