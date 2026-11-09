"""`crew`: the command line for agents (and the owner) on the Crew machine. Talks to the local Crew server.

    crew routines list
    crew routines show ID
    crew routines create --title T (--schedule CRON | --trigger NAME CRON ...) (--instructions TEXT | --instructions-file PATH)
    crew routines update ID [--title T] [--trigger NAME CRON] [--remove-trigger NAME] [--attempts NAME N] [...]
    crew routines pause ID | resume ID | delete ID | versions ID
    crew routines run ID [--trigger NAME]
    crew routines later ID (--in 5m | --at 19:05) --reason TEXT     during a run: try this trigger again later
    crew routines notes ID [--set TEXT | --set-file PATH | --append TEXT]
    crew schedule CRON [--timezone TZ]      check a schedule and show its next runs
    crew chat history CHAT [--last N] [--after SEQ]     a chat's messages (owner, agents), e.g. after a switch

A routine has one or more named triggers (cron schedules), e.g. check-in "55 9 * * 1-5" and check-out
"0 19 * * 1-5". Every run goes into the routine's own chat and is told which trigger fired. A trigger can
allow more than one attempt (--attempts check-out 3): a run that cannot finish yet asks for a follow-up with
`crew routines later`, and Crew refuses past the limit. Notes are kept between runs and shown to each run.

ID can be the first characters of a routine id. Reads the token from ~/.config/crew/token.
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
  - Before creating one, run `crew routines list`. If an existing routine does the same or a similar job,
    and you are not sure whether to update it or create a new one, ask the owner in the chat first and wait.
    Related jobs at different times (check-in and check-out) are one routine with several triggers.
  - Before deleting one, ask the owner in the chat and wait for a yes.
  - After a change, tell the owner what changed (triggers in words, instructions version) in the chat.
  - Put the calendar rules ("on Fridays", "skip leave days") in the instructions; a trigger's cron says when to
    look. `L` is the month's last day and `LW` its last weekday ("0 18 LW * *").
  - Retries: give a trigger attempts only when the owner wants retries, and use `crew routines later` only for
    "not yet" (a counter or time not reached). On a real failure, stop and tell the owner.
"""


class CliError(Exception):
    pass


def _base() -> tuple[str, str]:
    load_env_file()
    port = os.environ.get("CREW_PORT", "8800")
    token_file = os.environ.get("CREW_TOKEN_FILE", str(CONFIG_DIR / "token"))
    try:
        token = open(token_file).read().strip()
    except OSError:
        raise CliError(f"cannot read the Crew token ({token_file}); is Crew set up on this machine?") from None
    return f"http://127.0.0.1:{port}", token


def call(method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    base, token = _base()
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as exc:
        try:
            detail = json.loads(exc.read()).get("detail")
        except Exception:
            detail = exc.reason
        if isinstance(detail, list):  # pydantic validation errors
            detail = "; ".join(f"{'.'.join(map(str, d.get('loc', [])[1:]))}: {d.get('msg')}" for d in detail)
        raise CliError(f"Crew said {exc.code}: {detail}") from None
    except urllib.error.URLError as exc:
        raise CliError(f"Crew is not answering on {base} ({exc.reason}). `./master.sh status` in its repo.") from None


def resolve(prefix: str) -> str:
    matches = [r["id"] for r in call("GET", "/api/routines") if r["id"].startswith(prefix.lower())]
    if not matches:
        raise CliError(f"no routine id starts with {prefix!r} (crew routines list)")
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
            print(f"  {t['name']:<14} {t['schedule']:<16} {t['schedule_text']}, attempts {t['max_attempts']}, "
                  f"{'on' if t['enabled'] else 'off'}, next {_when(t['next_run_at'])}")
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


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="crew", description=__doc__, epilog=RULES, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    chat = sub.add_parser("chat", help="read a Crew chat (CHAT is its id or the first characters)")
    csub = chat.add_subparsers(dest="chat_action", required=True)
    hist = csub.add_parser("history", help="the chat's messages without tool calls")
    hist.add_argument("chat")
    hist.add_argument("--last", type=int, default=30, help="how many messages from the end (default 30)")
    hist.add_argument("--after", type=int, default=0, help="only messages after this event seq")

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
        p.add_argument("--timezone", help="default: the Crew machine's timezone")
        p.add_argument("--engine", help="agent id from engines.toml (default: Crew's default agent)")
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
        if args.cmd == "chat":
            ids = [s["id"] for s in call("GET", "/api/sessions") + call("GET", "/api/sessions?archived=true")
                   if s["id"].startswith(args.chat.lower())]
            if len(ids) != 1:
                raise CliError(f"{args.chat!r} matches {len(ids)} chats; give more characters of the chat id")
            for m in call("GET", f"/api/sessions/{ids[0]}/transcript?after={args.after}&last={args.last}"):
                print(f"[{m['seq']}] {m['from']}:\n{m['text'].strip()}\n")
            return 0
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
            print(f"Run {run['id'][:8]} ({','.join(run.get('triggers') or [])}) {run['status']}: it goes into the "
                  f"routine's chat {run['session_id']}.")
        elif args.action == "later":
            body = _later_time(args.in_) if args.in_ else _at_time(args.at)
            body["reason"] = args.reason
            t = call("POST", f"/api/routines/{routine_id}/later", body)
            print(f"Follow-up set: {t['parent']} again at {_when(t['next_run_at'])}, attempt {t['attempt']} of "
                  f"{t['max_attempts']}. End this run now; the next attempt comes into this chat.")
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
            print("Deleted the routine. Its chat stays as an ordinary task.")
        elif args.action == "versions":
            for v in call("GET", f"/api/routines/{routine_id}/versions"):
                trig = ", ".join(f"{t['name']} {t['schedule']}" for t in v.get("triggers") or []) or v["schedule"]
                print(f"v{v['version']}  {_when(v['created_at'])}  by {v['changed_by']}  {trig}  "
                      f"{v['note'] or ''}\n    {v['instructions'][:300]}")
        return 0
    except CliError as exc:
        print(f"crew: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
