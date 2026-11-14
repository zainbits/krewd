# Krewd demo

A second, throwaway Krewd for screenshots, videos and talks. It has its own database, port, config,
browser and fake home folder, and its agents are scripted: they look like Claude Code and Codex in the
app but run no model, need no key and never touch a real account. Everything in it is made up, except
the websites the browser scenes open, which are real.

```bash
./master.sh demo setup    # once: config, database URL, services (installed, never started at boot)
./master.sh demo up       # start the demo server and its Brave
./master.sh demo seed     # fill it with made-up chats, routines and files
./master.sh demo app      # build the demo app (app.krewd.demo) and install it over adb
./master.sh demo token    # connect code for the demo app
./master.sh demo reset    # empty it again (the demo phone's pairing stays)
./master.sh demo down     # stop it; the data stays for next time
```

Keep it stopped between recordings. After a new feature: `up`, `reset`, `seed`, record, `down`.

## Pieces

- `agent.py`: the demo agent. It speaks ACP like any agent, so the server, the app and the recordings go
  through the real code paths. The first prompt of a chat picks the scene whose `match` words fit best;
  a routine run picks the scene that names the routine.
- `scenes/*.toml`: what the agents say and do, step by step: `think`, `plan`, `say`, `run` (terminal),
  `read`, `edit`, `browse` (a real website in the demo Brave), `js` (read a value off the page, or
  click and scroll), `shot` (a screenshot in the tool card), `ask` (an approval), `secret` (a sign-in
  request through `krewd secret request`), `krewd` (the real `krewd` command, for routines), `file` (a
  file handed back in `~/outputs`), `usage` and `wait`. `{name}` puts a value a `js` step saved into
  the text, so a summary always matches what the page showed. `followups` answer later messages;
  `routine_steps` and `routine_retry_steps` play routine runs.
- `personas/*.json`: names, models, modes and commands the two agents advertise.
- `seed.py`: plays the scenes through the API, answers the approvals, lets an agent create a routine
  itself, runs every routine once and moves some chats back in time.
- `krewd.env.template`, `engines.toml.template`, `demo.sh`: what `setup` writes and how it runs.

## The demo app

`app.krewd.demo` installs beside the real app and is the same app, with one difference: screen
recording and screenshots are allowed on the sign-in sheet, so videos can show it. Connect it with
`./master.sh demo token`, then pair it with your security key in Settings > Security as usual.

The sign-in scene uses a public practice site: username `student`, password `Password123`.
