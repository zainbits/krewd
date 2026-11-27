"""`krewd`: the command line for agents (and the owner) on the Krewd machine. Talks to the local Krewd server.

    krewd routines list
    krewd routines show ID
    krewd routines create --title T (--schedule CRON | --trigger NAME CRON ...) (--instructions TEXT | --instructions-file PATH)
    krewd routines update ID [--title T] [--trigger NAME CRON] [--remove-trigger NAME] [--attempts NAME N] [...]
    krewd routines pause ID | resume ID | delete ID | versions ID
    krewd routines run ID [--trigger NAME]
    krewd routines later ID (--in 5m | --at 19:05) --reason TEXT     during a run: try this trigger again later
    krewd routines result ID failed|waiting --reason TEXT     during a run: it failed, or waits for the owner
    krewd routines notes ID [--set TEXT | --set-file PATH | --append TEXT]
    krewd routines skip ID [--trigger NAME] (--today | --runs N | --clear)   skip coming runs (owner's ask only)
    krewd schedule CRON [--timezone TZ]      check a schedule and show its next runs
    krewd chat history CHAT [--last N] [--after SEQ]     a chat's messages (owner, agents), e.g. after a switch
    krewd chat find [WORDS] [--archived] [--limit N]     earlier chats by title (full ids, to name them in a reply)
    krewd needs add --key KEY --title T [--source S] [--link URL] [--due WHEN] [--level L]   a Needs you card
    krewd needs list | done KEY | dismiss KEY            the open cards; close one you added
    krewd alert test [--level action|urgent]             the owner's end-to-end test of a notification level
    krewd secret request [--field KIND[:LABEL[:SELECTOR]]]... [--submit enter|none|SELECTOR] --reason TEXT [--tab ID]
    krewd security status | revoke PHONE | remove-key ID | reset   the owner's phones and keys (this machine only)
    krewd browser tabs                       the agents' Brave tabs with their view (vertical or horizontal)
    krewd browser view TAB vertical|horizontal   lay a tab out for the phone or the normal window

A routine has one or more named triggers (cron schedules), e.g. check-in "55 9 * * 1-5" and check-out
"0 19 * * 1-5". Every run gets its own new chat and is told which trigger fired; the next run deletes the
last one's chat, unless that run failed or waits for the owner (`krewd routines result`). A trigger can allow
more than one attempt (--attempts check-out 3): a run that cannot finish yet asks for a follow-up with
`krewd routines later`, and the next attempt continues in the same chat; Krewd refuses past the limit.
Notes are kept between runs and shown to each run: only lasting lessons go there, never what a run did.

A tab keeps the last view it was given (vertical: the page laid out for the owner's phone; horizontal: the
normal 1080p window), also when an agent starts using it. Switch it with `krewd browser view` when the work
needs the other layout. TAB is the tab's DevTools target id (browser-harness `current_tab()`) or its first
characters.

A sign-in request (`krewd secret request`) asks the owner for a password, a one-time code or another secret on
the phone. The agent never sees it: the owner types it there, Krewd types it into the page's fields and submits
the form, and the command prints only the outcome and the page it landed on. KIND is username, email, password,
otp or text (default: username and password); the fields are found by themselves, or give a CSS selector.

A Needs you card (`krewd needs add`) is something the owner must do or decide: a popup to answer, a form due
by a time. KEY is stable (the same popup on every run is one card: `zoho-timesheet-2026-11`); the same key again
updates the card, and a card the owner closed stays closed unless --again. WHEN is 19:00 (today, else tomorrow),
"in 2h", or an ISO time. --level is only a suggestion: Krewd's rules decide (agents get at most urgent), so
alarms stay rare. Name another Krewd chat in a reply by its full id (from `krewd chat find`): the app shows its
title as a link.

ID can be the first characters of a routine id. Reads the token from ~/.config/krewd/token.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from typing import Any

from .config import CONFIG_DIR, load_env_file

RULES = """\
Rules for agents:
  - Create, change, pause, resume, run or delete a routine only when the owner asked for it in the chat.
  - Never fill in a detail the owner did not give (the time or days it runs, the site, codes, names): ask in
    the chat and wait. Show the planned routine and create or change it only after the owner says yes.
  - Before creating one, run `krewd routines list`. If an existing routine does the same or a similar job,
    and you are not sure whether to update it or create a new one, ask the owner in the chat first and wait.
    Related jobs at different times (check-in and check-out) are one routine with several triggers.
  - Before deleting one, ask the owner in the chat and wait for a yes.
  - To skip coming runs (a leave day, a holiday), use `krewd routines skip`, not pause: it skips only those
    firings and the routine carries on by itself. `--today` skips every firing left today (and today's
    pending retries); `--runs N` the next N firings; `--clear` undoes it. Without --trigger: every trigger.
  - After a change, tell the owner what changed (triggers in words, instructions version) in the chat.
  - Put the calendar rules ("on Fridays", "skip leave days") in the instructions; a trigger's cron says when to
    look. `L` is the month's last day and `LW` its last weekday ("0 18 LW * *").
  - Retries: give a trigger attempts only when the owner wants retries, and use `krewd routines later` only for
    "not yet" (a counter or time not reached). On a real failure, run `krewd routines result ID failed`,
    stop and tell the owner.
  - Notes: append only something lasting a later run needs (where a button is, an exact name, a trap, a
    correction to a note). Never what a run did or found ("checked in at 09:55", "nothing changed"): that is in
    the run's summary already. If nothing new was learned, do not touch the notes.
"""


SIDE_RULES = """\
Rules for agents:
  - Only when the owner explicitly asks in the chat for a side session, a sub-agent or a message to another
    chat. Otherwise answer inline. Big or slow work is not a request.
  - You only draft. Nothing starts until the owner sends it from the app, where they can edit it and pick the
    agent and model. Never poll or sleep for a reply: keep working or end your turn; a reply comes in by itself.
  - Write the opening prompt self-contained: the new chat does not have your context (it can read yours with
    `krewd chat history`, since your chat is attached to it).
"""


def _text_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("--chat-id", dest="from_chat", help="your chat's id (from your first prompt); needed when "
                   "several chats are running")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--text", help="the message")
    g.add_argument("--file", help="read the message from this file (- for stdin)")


def _text(args: argparse.Namespace) -> str:
    if args.text is not None:
        return args.text
    if args.file == "-":
        return sys.stdin.read()
    with open(os.path.expanduser(args.file)) as f:
        return f.read()


def _chat_id(prefix: str) -> str:
    ids = [s["id"] for s in call("GET", "/api/sessions") + call("GET", "/api/sessions?archived=true")
           if s["id"].startswith(prefix.lower())]
    if len(ids) != 1:
        raise CliError(f"{prefix!r} matches {len(ids)} chats; give more characters of the chat id")
    return ids[0]


def _proposed(p: dict[str, Any]) -> None:
    what = {"new": "sub-agent" if p["returns"] else "side session", "prompt": "message"}[p["kind"]]
    to = f" to chat \"{p['target_title']}\"" if p["kind"] == "prompt" else f" \"{p['title']}\""
    print(f"Proposed {what}{to} ({p['id'][:8]}). The owner sees it in the app and sends it or not; you hear "
          "which. Do not wait or poll for it: carry on or end your turn.")


def _side(args: argparse.Namespace) -> int:
    if args.side_action == "propose":
        body = {"kind": "new", "text": _text(args), "title": args.title, "returns": args.mode == "subagent",
                "engine": args.engine}
        if args.from_chat:
            body["session_id"] = _chat_id(args.from_chat)
        _proposed(call("POST", "/api/proposals", body))
        return 0
    if args.from_chat:
        chat = _chat_id(args.from_chat)
    else:
        running = [s["id"] for s in call("GET", "/api/sessions") if s["status"] in ("running", "waiting")]
        if len(running) != 1:
            raise CliError("give --chat-id (your chat's id is in your first prompt)")
        chat = running[0]
    links = call("GET", f"/api/sessions/{chat}/links")

    def line(s: dict[str, Any], label: str) -> str:
        return f"  {label:<11} {s['id']}  {s['status']:<7} {s['title']}"

    if links["parent"]:
        print(line(links["parent"], "parent"))
    for c in links["children"]:
        print(line(c, c["kind"] or "side"))
    waits = [w for w in links["waiting_on"] if w["id"] not in {c["id"] for c in links["children"]}]
    for w in waits:
        print(line(w, "prompted"))
    owed = links["session"]["waiting_on"]
    if owed:
        print(f"Waiting on {len(owed)} chat(s) for a reply; it comes into this chat by itself.")
    if not (links["parent"] or links["children"] or waits):
        print("No parent, side sessions or sub-agents.")
    print("Read one with `krewd chat history <chat id> --last 30`.")
    return 0


class CliError(Exception):
    pass


def _base() -> tuple[str, str]:
    load_env_file()
    port = os.environ.get("KREWD_PORT", "8800")
    token_file = os.environ.get("KREWD_TOKEN_FILE", str(CONFIG_DIR / "token"))
    try:
        token = open(token_file).read().strip()
    except OSError:
        raise CliError(f"cannot read the Krewd token ({token_file}); is Krewd set up on this machine?") from None
    return f"http://127.0.0.1:{port}", token


def call(method: str, path: str, body: dict[str, Any] | None = None, timeout: float = 30) -> Any:
    base, token = _base()
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as exc:
        try:
            detail = json.loads(exc.read()).get("detail")
        except Exception:
            detail = exc.reason
        if isinstance(detail, list):  # pydantic validation errors
            detail = "; ".join(f"{'.'.join(map(str, d.get('loc', [])[1:]))}: {d.get('msg')}" for d in detail)
        raise CliError(f"Krewd said {exc.code}: {detail}") from None
    except urllib.error.URLError as exc:
        raise CliError(f"Krewd is not answering on {base} ({exc.reason}). `./master.sh status` in its repo.") from None


def resolve(prefix: str) -> str:
    matches = [r["id"] for r in call("GET", "/api/routines") if r["id"].startswith(prefix.lower())]
    if not matches:
        raise CliError(f"no routine id starts with {prefix!r} (krewd routines list)")
    if len(matches) > 1:
        raise CliError(f"{prefix!r} matches {len(matches)} routines; give more characters")
    return matches[0]


def _when(iso: str | None) -> str:
    if not iso:
        return "-"
    return datetime.fromisoformat(iso).astimezone().strftime("%a %d %b %H:%M")


def _line(r: dict[str, Any]) -> str:
    state = "on" if r["enabled"] else "paused"
    last = r.get("last_run") or {}
    last_text = f"last {last.get('status')} {_when(last.get('created_at'))}" if last else "never ran"
    return (f"{r['id'][:8]}  {r['title']}\n          {r['schedule_text']} ({r['timezone']}), {state}, "
            f"next {_when(r['next_run_at'])}, {last_text}, v{r['version']}, agent {r['engine']}")


def show(r: dict[str, Any]) -> None:
    print(_line(r))
    print(f"          chat {r['session_id'] or '-'}, created by {r['created_by']}, auto-approve "
          f"{'on' if r['auto_approve'] else 'off'}")
    print("\nTriggers:")
    for t in r.get("triggers") or []:
        if t["kind"] == "cron":
            skipping = f", skipping the next {t['skip_runs']}" if t.get("skip_runs") else ""
            print(f"  {t['name']:<14} {t['schedule']:<16} {t['schedule_text']}, attempts {t['max_attempts']}, "
                  f"{'on' if t['enabled'] else 'off'}, next {_when(t['next_run_at'])}{skipping}")
        else:
            print(f"  follow-up of {t['parent']}: {_when(t['next_run_at'])}, attempt {t['attempt']} of "
                  f"{t['max_attempts']} ({t['reason'] or 'no reason'}) [id {t['id'][:8]}]")
    print("\nNotes:\n" + (r.get("notes") or "(none)"))
    print("\nInstructions:\n" + r["instructions"])
    runs = r.get("runs") or []
    if runs:
        print("\nRecent runs:")
        for run in runs[:10]:
            summary = (run.get("summary") or "").strip().splitlines()
            names = ",".join(run.get("triggers") or [])
            print(f"  {_when(run['created_at'])}  {run['trigger']:<8} {names:<14} {run['status']:<9} "
                  f"{run.get('attempt', 1)}/{run.get('max_attempts', 1)} v{run['version']}  "
                  f"{summary[0][:90] if summary else ''}")


def _instructions(args: argparse.Namespace) -> str | None:
    if getattr(args, "instructions_file", None):
        with open(os.path.expanduser(args.instructions_file)) as f:
            return f.read()
    return getattr(args, "instructions", None)


def _cron_triggers(r: dict[str, Any] | None) -> list[dict[str, Any]]:
    return [{"name": t["name"], "schedule": t["schedule"], "max_attempts": t["max_attempts"], "enabled": t["enabled"]}
            for t in (r or {}).get("triggers") or [] if t["kind"] == "cron"]


def _apply_triggers(args: argparse.Namespace, current: list[dict[str, Any]]) -> list[dict[str, Any]] | None:
    """Trigger options folded into the routine's trigger list; None when no trigger option was given."""
    triggers = [dict(t) for t in current]
    changed = False
    if getattr(args, "schedule", None):
        if len(triggers) > 1:
            raise CliError("this routine has several triggers: use --trigger NAME CRON")
        triggers = [{**(triggers[0] if triggers else {"name": "main", "max_attempts": 1, "enabled": True}),
                     "schedule": args.schedule}]
        changed = True
    for name, cron in getattr(args, "trigger", None) or []:
        name = name.strip().lower()
        found = next((t for t in triggers if t["name"] == name), None)
        if found:
            found["schedule"] = cron
        else:
            triggers.append({"name": name, "schedule": cron, "max_attempts": 1, "enabled": True})
        changed = True
    for name in getattr(args, "remove_trigger", None) or []:
        before = len(triggers)
        triggers = [t for t in triggers if t["name"] != name.strip().lower()]
        if len(triggers) == before:
            raise CliError(f"no trigger called {name!r}")
        changed = True
    for name, count in getattr(args, "attempts", None) or []:
        found = next((t for t in triggers if t["name"] == name.strip().lower()), None)
        if not found:
            raise CliError(f"no trigger called {name!r}")
        found["max_attempts"] = int(count)
        changed = True
    for name, on in [(n, False) for n in getattr(args, "pause_trigger", None) or []] + \
                    [(n, True) for n in getattr(args, "resume_trigger", None) or []]:
        found = next((t for t in triggers if t["name"] == name.strip().lower()), None)
        if not found:
            raise CliError(f"no trigger called {name!r}")
        found["enabled"] = on
        changed = True
    return triggers if changed else None


def _fields(args: argparse.Namespace, current: dict[str, Any] | None = None) -> dict[str, Any]:
    body: dict[str, Any] = {"by": args.by}
    for key in ("title", "timezone", "engine", "mode", "note"):
        value = getattr(args, key, None)
        if value is not None:
            body[key] = value
    text = _instructions(args)
    if text is not None:
        body["instructions"] = text
    if getattr(args, "auto_approve", None) is not None:
        body["auto_approve"] = args.auto_approve == "on"
    triggers = _apply_triggers(args, _cron_triggers(current))
    if triggers is not None:
        body["triggers"] = triggers
    return body


def _later_time(text: str) -> dict[str, Any]:
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(m|min|h)?", text.strip())
    if m:
        value = float(m.group(1))
        return {"minutes": value * 60 if m.group(2) == "h" else value}
    raise CliError(f"--in takes minutes like 5m or 1h, not {text!r}")


def _at_time(text: str) -> dict[str, Any]:
    m = re.fullmatch(r"(\d{1,2}):(\d{2})", text.strip())
    if not m:
        raise CliError(f"--at takes a local time like 19:05, not {text!r}")
    now = datetime.now().astimezone()
    at = now.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=0, microsecond=0)
    if at <= now:
        at += timedelta(days=1)
    return {"at": at.isoformat()}


def _due(text: str) -> str:
    text = text.strip()
    m = re.fullmatch(r"in\s+(\d+(?:\.\d+)?)\s*(m|min|h|d)", text)
    if m:
        value = float(m.group(1))
        minutes = value * {"m": 1, "min": 1, "h": 60, "d": 1440}[m.group(2)]
        return (datetime.now().astimezone() + timedelta(minutes=minutes)).isoformat()
    if re.fullmatch(r"\d{1,2}:\d{2}", text):
        return _at_time(text)["at"]
    try:
        when = datetime.fromisoformat(text)
    except ValueError:
        raise CliError(f"--due takes 19:00, 'in 2h' or an ISO time, not {text!r}") from None
    return (when if when.tzinfo else when.astimezone()).isoformat()


def _need_line(n: dict[str, Any]) -> str:
    due = f", due {_when(n['due_at'])}" if n.get("due_at") else ""
    snooze = f", snoozed until {_when(n['snoozed_until'])}" if n.get("snoozed_until") else ""
    return f"  {n['key']}: {n['title']} ({n['level']}{due}{snooze}){' - ' + n['source'] if n['source'] else ''}"


def _push_line() -> str:
    p = call("GET", "/api/push")
    if not p.get("configured"):
        return "off (no FCM key on this machine); the phone shows it only while the app is open"
    return f"FCM project {p.get('project')}, {p.get('phones', 0)} phone(s)" + \
        (f", last errors: {'; '.join(p['errors'][:2])}" if p.get("errors") else "")


def _needs(args: argparse.Namespace) -> int:
    if args.needs_action == "list":
        found = call("GET", "/api/needs")
        if not found:
            print("No open Needs you cards.")
        for n in found:
            print(_need_line(n))
        return 0
    if args.needs_action in ("done", "dismiss"):
        import urllib.parse

        action = "done" if args.needs_action == "done" else "dismissed"
        n = call("POST", f"/api/needs/key/{urllib.parse.quote(args.key, safe='')}", {"action": action})
        print(f"Marked {action}: {n['title']}")
        return 0
    body: dict[str, Any] = {"key": args.key, "title": args.title, "source": args.source, "again": args.again}
    if args.link:
        body["link"] = args.link
    if args.due:
        body["due_at"] = _due(args.due)
    if args.level:
        body["level"] = args.level
    if args.from_chat:
        body["session_id"] = _chat_id(args.from_chat)
    n = call("POST", "/api/needs", body)
    said = {"added": "Added", "updated": "Updated", "reopened": "Raised again"}.get(n["outcome"])
    if said is None:
        print(f"Not raised: the owner already marked this {n['status']} ({_when(n['closed_at'])}). "
              "Use --again only if it really needs them again.")
        return 0
    level = n["level"] + (f" (you suggested {n['suggested_level']})" if n.get("suggested_level") and
                          n["suggested_level"] != n["level"] else "")
    print(f"{said}: {n['title']}, level {level}." + (" The owner gets a notification." if n["outcome"] != "updated"
                                                      and n["level"] != "info" else ""))
    return 0


def _find_chats(args: argparse.Namespace) -> int:
    chats = call("GET", "/api/sessions") + (call("GET", "/api/sessions?archived=true") if args.archived else [])
    words = [w.lower() for w in args.words]
    found = [c for c in chats if all(w in (c["title"] or "").lower() for w in words)]
    found.sort(key=lambda c: c.get("updated_at") or "", reverse=True)
    if not found:
        print("No chat title has those words. Try fewer words, or --archived.")
        return 0
    for c in found[: max(1, args.limit)]:
        print(f"{c['id']}  {_when(c.get('updated_at'))}  {c['status']:<7} {c['title']}"
              + ("  (archived)" if c.get("archived") else ""))
    print("Name a chat in your reply by its full id: the app shows its title as a link that opens it.")
    return 0


def _secret(args: argparse.Namespace) -> int:
    import time

    fields = []
    for spec in args.field or []:
        kind, _, rest = spec.partition(":")
        label, _, selector = rest.partition(":")
        fields.append({"kind": kind.strip().lower(), "label": label.strip() or None, "selector": selector.strip() or None})
    body: dict[str, Any] = {"fields": fields or None, "submit": args.submit, "reason": args.reason}
    if args.tab:
        body["tab_id"] = args.tab
    req = call("POST", "/api/secrets", body)
    print(f"Asked the owner on the phone for {', '.join(f['label'] for f in req['fields'])} on {req['site']}. Waiting...",
          flush=True)
    deadline = time.time() + args.timeout
    try:
        while req["status"] == "pending" and time.time() < deadline:
            req = call("GET", f"/api/secrets/{req['id']}/wait?timeout=25")
    except KeyboardInterrupt:
        req = call("POST", f"/api/secrets/{req['id']}/cancel")
    if req["status"] == "pending":
        req = call("POST", f"/api/secrets/{req['id']}/cancel")
    if req["status"] == "filled":
        print(f"Filled and submitted. The page is now: {req.get('url')}\nCheck the page to see whether the sign-in worked.")
        return 0
    print(f"Not filled: {req['status']}" + (f" ({req['error']})" if req.get("error") else "")
          + ". Do not ask for the secret in the chat; tell the owner and stop, or ask again if they want to.")
    return 1


def _browsers(args: argparse.Namespace) -> int:
    """People's own browsers: list, add, use (this chat), start, stop."""
    action = args.browser_action
    if action == "list":
        state = call("GET", "/api/browsers")
        for b in state["browsers"]:
            print(f"{b['name']:<12} {'running' if b['running'] else 'stopped':<8} port {b['port']}  {b['profile']}")
        idle = state.get("idle_minutes")
        print(f"A person's browser starts when a chat that uses it calls browser-harness, and stops after "
              f"{idle:g} idle minutes." if idle else "A person's browser starts when a chat uses it; it is never stopped by itself.")
        return 0
    if action == "add":
        b = call("POST", "/api/browsers", {"name": args.name})
        print(f"Browser {b['name']}: own profile {b['profile']}, DevTools port {b['port']}. It starts when a chat "
              f"uses it (krewd browser use {b['name']}); its logins are its own.")
        return 0
    if action == "use":
        body: dict[str, Any] = {"browser": args.name}
        if args.from_chat:
            body["session_id"] = _chat_id(args.from_chat)
        else:
            agent = os.environ.get("CLAUDE_CODE_SESSION_ID") or os.environ.get("CODEX_THREAD_ID")
            if not agent:
                raise CliError("give --chat-id (your chat's id is in your first prompt)")
            body["agent_session"] = agent
        b = call("POST", "/api/browser/use", body)
        print(f"This chat now uses the browser {b['name']} (DevTools {b['cdp_url']}, tmux {b['tmux']}). Your next "
              "browser-harness call attaches there (and starts it if needed); open your tab with new_tab().")
        return 0
    if action == "start":
        b = call("POST", f"/api/browsers/{args.name}/start", timeout=90)
        print(f"Browser {b['name']} is running (DevTools {b['cdp_url']}; watch it: tmux attach -t {b['tmux']}).")
        return 0
    b = call("POST", f"/api/browsers/{args.name}/stop", timeout=90)
    print(f"Browser {b['name']} stopped; its logins stay in {b['profile']}.")
    return 0


def _browser(args: argparse.Namespace) -> int:
    if args.browser_action in ("list", "add", "use", "start", "stop"):
        return _browsers(args)
    state = call("GET", "/api/browser/tabs")
    if not state.get("available"):
        raise CliError(state.get("reason") or "the agents' browser is not running")
    tabs = state["tabs"]
    if args.browser_action == "tabs":
        for t in tabs:
            print(f"{t['id']}  {t.get('browser', 'main'):<10}  {t['view']:<10}  {t['title'][:60]}  {t['url'][:80]}")
        return 0
    ids = [t["id"] for t in tabs if t["id"].lower().startswith(args.tab.lower())]
    if len(ids) != 1:
        raise CliError(f"{args.tab!r} matches {len(ids)} tabs (krewd browser tabs)")
    call("POST", f"/api/browser/tabs/{ids[0]}/view", {"mode": args.mode, "by_agent": True})
    print(f"Tab {ids[0]} is now {args.mode}"
          + (" (laid out for the owner's phone)." if args.mode == "vertical" else " (the normal window)."))
    return 0


def _security(args: argparse.Namespace) -> int:
    state = call("GET", "/api/security")
    if args.security_action == "status":
        keys = state.get("keys", [])
        print("Owner keys: " + (str(len(keys)) if keys else "none yet (setup: ./master.sh token)"))
        names = {k["id"]: k["name"] for k in keys}
        for k in keys:
            kind = "phone passkey" if k["kind"] == "passkey" else "security key"
            print(f"  {k['id']}  {k['name']} ({kind})")
        print("Passkeys: " + (f"on, for {state['passkey_rp_id']}" if state.get("passkey_rp_id") else
                              "off (KREWD_PASSKEY_RP_ID and KREWD_PASSKEY_CERTS in krewd.env)"))
        print("Phones:")
        for d in state["devices"]:
            via = names.get(d.get("key") or "", "no valid key")
            print(f"  {d['id'][:8]}  {d['name']}  added with {via} {_when(d['created_at'])}, last seen {_when(d['last_seen'])}")
        return 0
    if args.security_action == "remove-key":
        result = call("POST", f"/api/security/keys/{args.key}/remove")
        print(f"Removed {result['removed']}." + (f" Phones removed with it: {len(result['phones_removed'])}." if result["phones_removed"] else ""))
        return 0
    if args.security_action == "revoke":
        ids = [d["id"] for d in state["devices"] if d["id"].startswith(args.phone.lower())]
        if len(ids) != 1:
            raise CliError(f"{args.phone!r} matches {len(ids)} phones (krewd security status)")
        call("POST", f"/api/security/devices/{ids[0]}/revoke")
        print("Removed. That phone can no longer use Krewd.")
        return 0
    call("POST", "/api/security/reset")
    print("Reset: no owner keys and no phones now. Run ./master.sh token and open its code on your phone to set up again.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="krewd", description=__doc__, epilog=RULES, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    chat = sub.add_parser("chat", help="read a Krewd chat (CHAT is its id or the first characters)")
    csub = chat.add_subparsers(dest="chat_action", required=True)
    hist = csub.add_parser("history", help="the chat's messages without tool calls")
    hist.add_argument("chat")
    hist.add_argument("--last", type=int, default=30, help="how many messages from the end (default 30)")
    hist.add_argument("--after", type=int, default=0, help="only messages after this event seq")
    find = csub.add_parser("find", help="earlier chats whose title has these words, newest first, with full ids")
    find.add_argument("words", nargs="*")
    find.add_argument("--archived", action="store_true", help="also archived chats")
    find.add_argument("--limit", type=int, default=20)
    prompt = csub.add_parser("prompt", help="draft a message to another chat; it goes only when the owner sends it",
                             epilog=SIDE_RULES, formatter_class=argparse.RawDescriptionHelpFormatter)
    prompt.add_argument("chat", help="the chat to message: one the owner attached with @, or a linked one")
    prompt.add_argument("--reply", action="store_true", help="its reply to this message comes back to your chat")
    _text_args(prompt)

    side = sub.add_parser("side", help="side sessions and sub-agents of a chat (only when the owner asks)",
                          epilog=SIDE_RULES, formatter_class=argparse.RawDescriptionHelpFormatter)
    sidesub = side.add_subparsers(dest="side_action", required=True)
    prop = sidesub.add_parser("propose", help="draft a new side session or sub-agent; the owner picks the agent "
                              "and model and sends it", epilog=SIDE_RULES, formatter_class=argparse.RawDescriptionHelpFormatter)
    prop.add_argument("--mode", choices=["side", "subagent"], required=True,
                      help="subagent: each reply it finishes comes back to your chat; side: nothing comes back")
    prop.add_argument("--title", default="", help="a short title for the new chat")
    prop.add_argument("--engine", help="the agent to suggest (the owner can change it); default: the owner's pick")
    _text_args(prop)
    lst = sidesub.add_parser("list", help="this chat's parent, side sessions, sub-agents and the chats it waits on")
    lst.add_argument("--chat-id", dest="from_chat", help="your chat's id (default: the one chat running a turn)")

    secret = sub.add_parser("secret", help="ask the owner to fill a sign-in or code on the phone (never shown to you)")
    ssub = secret.add_subparsers(dest="secret_action", required=True)
    ask = ssub.add_parser("request", help="wait until the owner fills the page's login (or code) on the phone")
    ask.add_argument("--field", action="append", metavar="KIND[:LABEL[:SELECTOR]]",
                     help="username, email, password, otp or text; repeat for several (default username and password)")
    ask.add_argument("--submit", default="enter", help="enter (default), none, or a CSS selector to click")
    ask.add_argument("--reason", required=True, help="one line for the owner: what you are signing in to and why")
    ask.add_argument("--tab", help="the tab id (default: the tab your task is using)")
    ask.add_argument("--timeout", type=int, default=600, help="seconds to wait for the owner (default 600)")

    sec = sub.add_parser("security", help="the owner's phones and security key (from this machine)")
    secsub = sec.add_subparsers(dest="security_action", required=True)
    secsub.add_parser("status")
    secsub.add_parser("revoke").add_argument("phone", help="the phone's id or its first characters")
    secsub.add_parser("remove-key", help="remove an owner key (not the last one); phones added with it go too").add_argument(
        "key", help="the key's id (krewd security status)")
    secsub.add_parser("reset", help="lost phone and keys: remove every owner key and phone, make a new setup token")

    br = sub.add_parser("browser", help="the agents' Brave tabs and their view (vertical for the phone, horizontal), "
                        "and people's own browsers (list, add, use, start, stop)")
    brsub = br.add_subparsers(dest="browser_action", required=True)
    brsub.add_parser("tabs", help="the tabs with their view")
    view = brsub.add_parser("view", help="lay a tab out for the owner's phone (vertical) or the normal window (horizontal); "
                            "it keeps that view until someone changes it")
    view.add_argument("tab", help="the tab's DevTools target id (browser-harness current_tab()) or its first characters")
    view.add_argument("mode", choices=["vertical", "horizontal"])
    brsub.add_parser("list", help="the browsers: main (the usual one) and each person's own, with their own logins")
    badd = brsub.add_parser("add", help="add a person's own browser (own profile and logins), named after them")
    badd.add_argument("name", help="short lowercase name, e.g. the person's first name")
    buse = brsub.add_parser("use", help="this chat uses that browser from now on (main: the usual one)")
    buse.add_argument("name")
    buse.add_argument("--chat-id", dest="from_chat", help="the chat's id, when not run from that chat's agent")
    for name, text in (("start", "start a person's browser now"), ("stop", "stop a person's browser (logins stay)")):
        brsub.add_parser(name, help=text).add_argument("name")

    needs = sub.add_parser("needs", help="Needs you cards for the owner (things to do or decide, with a due time)",
                           description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    nsub = needs.add_subparsers(dest="needs_action", required=True)
    nadd = nsub.add_parser("add", help="add a card, or update the one with this key")
    nadd.add_argument("--key", required=True, help="stable key: the same thing seen again is the same card")
    nadd.add_argument("--title", required=True)
    nadd.add_argument("--source", default="", help="where it comes from, e.g. 'Zoho People popup'")
    nadd.add_argument("--link", help="https://... or krewd://chat/<id> (default: your chat)")
    nadd.add_argument("--due", help="19:00, 'in 2h' or an ISO time")
    nadd.add_argument("--level", choices=["info", "action", "urgent", "critical"],
                      help="a suggestion; Krewd's rules decide (agents get at most urgent)")
    nadd.add_argument("--again", action="store_true", help="raise it again even though the owner closed it")
    nadd.add_argument("--chat-id", dest="from_chat", help="your chat's id (from your first prompt)")
    nsub.add_parser("list", help="the open cards")
    for name in ("done", "dismiss"):
        nsub.add_parser(name, help=f"mark a card {name}").add_argument("key")
    alert = sub.add_parser("alert", help="notifications on the owner's phone")
    asub = alert.add_subparsers(dest="alert_action", required=True)
    atest = asub.add_parser("test", help="send a test alert through push (the owner's check)")
    atest.add_argument("--level", choices=["action", "urgent"], default="action",
                       help="a Critical test alarm only from the app (Settings > Alerts)")

    sched = sub.add_parser("schedule", help="check a 5-field cron schedule and show its next runs")
    sched.add_argument("cron")
    sched.add_argument("--timezone")

    routines = sub.add_parser("routines", help="list, show, create and change routines", epilog=RULES,
                              formatter_class=argparse.RawDescriptionHelpFormatter)
    rsub = routines.add_subparsers(dest="action", required=True)
    rsub.add_parser("list")
    for name in ("show", "pause", "resume", "delete", "versions"):
        rsub.add_parser(name).add_argument("id")
    run = rsub.add_parser("run")
    run.add_argument("id")
    run.add_argument("--trigger", help="which trigger to run as (default: the first)")
    later = rsub.add_parser("later", help="during a run: try this trigger again later (its next attempt)")
    later.add_argument("id")
    when = later.add_mutually_exclusive_group(required=True)
    when.add_argument("--in", dest="in_", metavar="5m", help="minutes (5m) or hours (1h) from now")
    when.add_argument("--at", metavar="19:05", help="a local time today (or tomorrow if passed)")
    later.add_argument("--reason", required=True, help="why it could not be done yet, shown to the next attempt")
    result = rsub.add_parser("result", help="during a run: it failed, or it waits for the owner (its chat is kept)")
    result.add_argument("id")
    result.add_argument("outcome", choices=["failed", "waiting"])
    result.add_argument("--reason", required=True, help="one line: what went wrong, or what the owner must answer")
    skip = rsub.add_parser("skip", help="skip coming runs of one trigger or all (a leave day); --clear undoes it")
    skip.add_argument("id")
    skip.add_argument("--trigger", help="only this trigger (default: every trigger)")
    sgroup = skip.add_mutually_exclusive_group(required=True)
    sgroup.add_argument("--today", action="store_true", help="every firing left today, and today's pending retries")
    sgroup.add_argument("--runs", type=int, metavar="N", help="the next N firings (1 to 100)")
    sgroup.add_argument("--clear", action="store_true", help="skip nothing")
    notes = rsub.add_parser("notes", help="show or change the notes kept between runs")
    notes.add_argument("id")
    ngroup = notes.add_mutually_exclusive_group()
    ngroup.add_argument("--set", dest="set_text")
    ngroup.add_argument("--set-file")
    ngroup.add_argument("--append")

    def editable(p: argparse.ArgumentParser, required: bool) -> None:
        p.add_argument("--title", required=required)
        p.add_argument("--schedule", help="one trigger: 5-field cron, e.g. '0 19 * * 1-5' (weekdays 19:00)")
        p.add_argument("--trigger", nargs=2, action="append", metavar=("NAME", "CRON"),
                       help="add or change a named trigger, e.g. --trigger check-in '55 9 * * 1-5' (repeatable)")
        p.add_argument("--attempts", nargs=2, action="append", metavar=("NAME", "N"),
                       help="how many tries a trigger's firing gets (1 to 10, default 1)")
        group = p.add_mutually_exclusive_group(required=required)
        group.add_argument("--instructions")
        group.add_argument("--instructions-file")
        p.add_argument("--timezone", help="default: the Krewd machine's timezone")
        p.add_argument("--engine", help="agent id from engines.toml (default: Krewd's default agent)")
        p.add_argument("--mode")
        p.add_argument("--auto-approve", choices=["on", "off"])
        p.add_argument("--note", help="why it changed; kept with the version")
        p.add_argument("--by", choices=["agent", "owner"], default="agent")

    create = rsub.add_parser("create", epilog=RULES, formatter_class=argparse.RawDescriptionHelpFormatter)
    editable(create, True)
    create.add_argument("--paused", action="store_true", help="create it paused")
    update = rsub.add_parser("update", epilog=RULES, formatter_class=argparse.RawDescriptionHelpFormatter)
    update.add_argument("id")
    editable(update, False)
    update.add_argument("--remove-trigger", action="append", metavar="NAME")
    update.add_argument("--pause-trigger", action="append", metavar="NAME")
    update.add_argument("--resume-trigger", action="append", metavar="NAME")

    args = parser.parse_args(argv)
    try:
        if args.cmd == "side":
            return _side(args)
        if args.cmd == "chat" and args.chat_action == "prompt":
            body = {"kind": "prompt", "text": _text(args), "returns": args.reply, "target_id": _chat_id(args.chat)}
            if args.from_chat:
                body["session_id"] = _chat_id(args.from_chat)
            _proposed(call("POST", "/api/proposals", body))
            return 0
        if args.cmd == "chat" and args.chat_action == "find":
            return _find_chats(args)
        if args.cmd == "needs":
            return _needs(args)
        if args.cmd == "alert":
            a = call("POST", "/api/alerts/test", {"level": args.level})
            print(f"Sent a {a['level']} test alert ({a['id'][:8]}). Push: {_push_line()}")
            return 0
        if args.cmd == "chat":
            chat_id = _chat_id(args.chat)
            for m in call("GET", f"/api/sessions/{chat_id}/transcript?after={args.after}&last={args.last}"):
                print(f"[{m['seq']}] {m['from']}:\n{m['text'].strip()}\n")
            return 0
        if args.cmd == "secret":
            return _secret(args)
        if args.cmd == "browser":
            return _browser(args)
        if args.cmd == "security":
            return _security(args)
        if args.cmd == "schedule":
            body = {"schedule": args.cron}
            if args.timezone:
                body["timezone"] = args.timezone
            p = call("POST", "/api/routines/preview", body)
            print(f"{p['schedule_text']} ({p['schedule']}, {p['timezone']})")
            for t in p["next_runs"]:
                print("  " + _when(t))
            return 0
        if args.action == "list":
            found = call("GET", "/api/routines")
            if not found:
                print("No routines yet.")
            for r in found:
                print(_line(r))
            return 0
        if args.action == "create":
            body = _fields(args)
            if "triggers" not in body:
                raise CliError("give --schedule CRON or one or more --trigger NAME CRON")
            if args.paused:
                body["enabled"] = False
            r = call("POST", "/api/routines", body)
            print("Created:")
            show(call("GET", f"/api/routines/{r['id']}"))
            return 0
        routine_id = resolve(args.id)
        if args.action == "show":
            show(call("GET", f"/api/routines/{routine_id}"))
        elif args.action == "update":
            current = call("GET", f"/api/routines/{routine_id}")
            body = _fields(args, current)
            if len(body) <= 1:
                raise CliError("nothing to change: give --title, --trigger, --instructions or another option")
            call("PATCH", f"/api/routines/{routine_id}", body)
            show(call("GET", f"/api/routines/{routine_id}"))
        elif args.action in ("pause", "resume"):
            r = call("PATCH", f"/api/routines/{routine_id}", {"enabled": args.action == "resume", "by": "agent"})
            print(_line(r))
        elif args.action == "run":
            run_body = {"trigger": args.trigger} if args.trigger else {}
            run = call("POST", f"/api/routines/{routine_id}/run", run_body)
            print(f"Run {run['id'][:8]} ({','.join(run.get('triggers') or [])}) {run['status']}: it runs in its own "
                  f"chat {run['session_id']}.")
        elif args.action == "result":
            call("POST", f"/api/routines/{routine_id}/result", {"result": args.outcome, "reason": args.reason})
            print(f"This run is marked {args.outcome}; its chat is kept for the owner. Tell the owner in your reply.")
        elif args.action == "later":
            body = _later_time(args.in_) if args.in_ else _at_time(args.at)
            body["reason"] = args.reason
            t = call("POST", f"/api/routines/{routine_id}/later", body)
            print(f"Follow-up set: {t['parent']} again at {_when(t['next_run_at'])}, attempt {t['attempt']} of "
                  f"{t['max_attempts']}. End this run now; the next attempt comes into this chat.")
        elif args.action == "skip":
            body: dict[str, Any] = {"today": True} if args.today else {"runs": 0 if args.clear else args.runs}
            if args.trigger:
                body["trigger"] = args.trigger
            r = call("POST", f"/api/routines/{routine_id}/skip", body)
            for t in r.get("triggers") or []:
                if t["kind"] == "cron" and (not args.trigger or t["name"] == args.trigger.strip().lower()):
                    n = t.get("skip_runs") or 0
                    print(f"  {t['name']}: " + (f"skipping the next {n} run{'s' if n != 1 else ''}" if n
                                                 else "not skipping"))
            print(f"Next real run: {_when(r.get('next_live_at'))}")
        elif args.action == "notes":
            if args.set_text is None and args.set_file is None and args.append is None:
                print(call("GET", f"/api/routines/{routine_id}").get("notes") or "(no notes yet)")
                return 0
            if args.set_file:
                with open(os.path.expanduser(args.set_file)) as f:
                    text, append = f.read(), False
            else:
                text, append = (args.append, True) if args.append is not None else (args.set_text, False)
            r = call("PUT", f"/api/routines/{routine_id}/notes", {"notes": text, "append": append})
            print(f"Notes saved ({len(r.get('notes') or '')} characters).")
        elif args.action == "delete":
            call("DELETE", f"/api/routines/{routine_id}")
            print("Deleted the routine. Its run chats stay as ordinary tasks.")
        elif args.action == "versions":
            for v in call("GET", f"/api/routines/{routine_id}/versions"):
                trig = ", ".join(f"{t['name']} {t['schedule']}" for t in v.get("triggers") or []) or v["schedule"]
                print(f"v{v['version']}  {_when(v['created_at'])}  by {v['changed_by']}  {trig}  "
                      f"{v['note'] or ''}\n    {v['instructions'][:300]}")
        return 0
    except CliError as exc:
        print(f"krewd: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
