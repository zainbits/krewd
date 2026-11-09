"""`crew`: the command line for agents (and the owner) on the Crew machine. Talks to the local Crew server.

    crew routines list
    crew routines show ID
    crew routines create --title T --schedule CRON (--instructions TEXT | --instructions-file PATH) [...]
    crew routines update ID [--title T] [--schedule CRON] [--instructions TEXT | --instructions-file PATH] [...]
    crew routines pause ID | resume ID | run ID | delete ID | versions ID
    crew schedule CRON [--timezone TZ]      check a schedule and show its next runs

ID can be the first characters of a routine id. Reads the token from ~/.config/crew/token.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime
from typing import Any

from .config import CONFIG_DIR, load_env_file

RULES = """\
Rules for agents:
  - Create, change, pause, resume, run or delete a routine only when the owner asked for it in the chat.
  - Never fill in a detail the owner did not give (the time or days it runs, the site, codes, names): ask in
    the chat and wait. Show the planned routine and create or change it only after the owner says yes.
  - Before creating one, run `crew routines list`. If an existing routine does the same or a similar job,
    and you are not sure whether to update it or create a new one, ask the owner in the chat first and wait.
  - Before deleting one, ask the owner in the chat and wait for a yes.
  - After a change, tell the owner what changed (schedule in words, instructions version) in the chat.
  - Put the calendar rules ("on Fridays", "if the month ends mid-week") in the instructions, not in the cron.
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
    return (f"{r['id'][:8]}  {r['title']}\n          {r['schedule_text']} ({r['schedule']}, {r['timezone']}), {state}, "
            f"next {_when(r['next_run_at'])}, {last_text}, v{r['version']}, agent {r['engine']}")


def show(r: dict[str, Any]) -> None:
    print(_line(r))
    print(f"          chat {r['session_id'] or '-'}, created by {r['created_by']}, auto-approve "
          f"{'on' if r['auto_approve'] else 'off'}")
    if r.get("next_runs"):
        print("          next runs: " + ", ".join(_when(t) for t in r["next_runs"]))
    print("\nInstructions:\n" + r["instructions"])
    runs = r.get("runs") or []
    if runs:
        print("\nRecent runs:")
        for run in runs[:10]:
            summary = (run.get("summary") or "").strip().splitlines()
            print(f"  {_when(run['created_at'])}  {run['trigger']:<8} {run['status']:<9} v{run['version']}  "
                  f"{summary[0][:100] if summary else ''}")


def _instructions(args: argparse.Namespace) -> str | None:
    if getattr(args, "instructions_file", None):
        with open(os.path.expanduser(args.instructions_file)) as f:
            return f.read()
    return getattr(args, "instructions", None)


def _fields(args: argparse.Namespace) -> dict[str, Any]:
    body: dict[str, Any] = {"by": args.by}
    for key in ("title", "schedule", "timezone", "engine", "mode", "note"):
        value = getattr(args, key, None)
        if value is not None:
            body[key] = value
    text = _instructions(args)
    if text is not None:
        body["instructions"] = text
    if getattr(args, "auto_approve", None) is not None:
        body["auto_approve"] = args.auto_approve == "on"
    return body


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="crew", description=__doc__, epilog=RULES, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    sched = sub.add_parser("schedule", help="check a 5-field cron schedule and show its next runs")
    sched.add_argument("cron")
    sched.add_argument("--timezone")

    routines = sub.add_parser("routines", help="list, show, create and change routines", epilog=RULES,
                              formatter_class=argparse.RawDescriptionHelpFormatter)
    rsub = routines.add_subparsers(dest="action", required=True)
    rsub.add_parser("list")
    for name in ("show", "pause", "resume", "run", "delete", "versions"):
        rsub.add_parser(name).add_argument("id")

    def editable(p: argparse.ArgumentParser, required: bool) -> None:
        p.add_argument("--title", required=required)
        p.add_argument("--schedule", required=required,
                       help="5-field cron in the routine's timezone, e.g. '0 19 * * 1-5' (weekdays 19:00)")
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

    args = parser.parse_args(argv)
    try:
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
            if args.paused:
                body["enabled"] = False
            r = call("POST", "/api/routines", body)
            print("Created:")
            show(r)
            return 0
        routine_id = resolve(args.id)
        if args.action == "show":
            show(call("GET", f"/api/routines/{routine_id}"))
        elif args.action == "update":
            body = _fields(args)
            if len(body) <= 1:
                raise CliError("nothing to change: give --title, --schedule, --instructions or another option")
            show(call("PATCH", f"/api/routines/{routine_id}", body))
        elif args.action in ("pause", "resume"):
            r = call("PATCH", f"/api/routines/{routine_id}", {"enabled": args.action == "resume", "by": "agent"})
            print(_line(r))
        elif args.action == "run":
            run = call("POST", f"/api/routines/{routine_id}/run")
            print(f"Run {run['id'][:8]} {run['status']}: it goes into the routine's chat {run['session_id']}.")
        elif args.action == "delete":
            call("DELETE", f"/api/routines/{routine_id}")
            print("Deleted the routine. Its chat stays as an ordinary task.")
        elif args.action == "versions":
            for v in call("GET", f"/api/routines/{routine_id}/versions"):
                print(f"v{v['version']}  {_when(v['created_at'])}  by {v['changed_by']}  {v['schedule']}  "
                      f"{v['note'] or ''}\n    {v['instructions'][:300]}")
        return 0
    except CliError as exc:
        print(f"crew: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
