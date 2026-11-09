# Crew

An agent-agnostic personal agent team on a home server. You ask from your phone; a coding agent
(Claude Code today, Codex or any other ACP agent later) does the work on the server with its
terminal and browser, and asks you before anything consequential.

- `server/`: the Crew server (Python, FastAPI, PostgreSQL). It talks to agents over the
  [Agent Client Protocol](https://agentclientprotocol.com) and keeps a durable event log, so the
  phone can disconnect at any time.
- `android/`: the Crew Android app (Kotlin, Jetpack Compose).
- `docs/`: API and host setup.

Nothing here is tied to one agent vendor: engines are ACP commands in `~/.config/crew/engines.toml`,
instructions live in `AGENTS.md`, and the browser is driven by `browser-harness`.
