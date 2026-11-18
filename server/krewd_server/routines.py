"""Routines: owner instructions that Krewd runs on a schedule, every run in the routine's own chat.

The schedule is a plain 5-field cron expression (minute hour day-of-month month day-of-week) read in the
routine's timezone. The rules that depend on the calendar ("on Fridays", "if the month ends on a
Wednesday") stay in the instructions: the agent reads them with the date facts given in each run.
"""

from __future__ import annotations

import calendar
import os
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from cronsim import CronSim, CronSimError

# A run missed while Krewd was down still happens once if Krewd is back within this window; older ones are skipped.
CATCH_UP = timedelta(hours=6)
DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
SHORT_DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]


class Invalid(ValueError):
    """A schedule, timezone or other routine field that cannot be used."""


def local_timezone() -> str:
    """KREWD_TIMEZONE, else this machine's own timezone name, else UTC."""
    for candidate in (os.environ.get("KREWD_TIMEZONE"), os.environ.get("TZ")):
        if candidate and _known(candidate):
            return candidate
    try:
        name = Path("/etc/timezone").read_text().strip()
        if _known(name):
            return name
    except OSError:
        pass
    try:
        target = os.path.realpath("/etc/localtime")
        if "zoneinfo/" in target:
            name = target.split("zoneinfo/", 1)[1]
            if _known(name):
                return name
    except OSError:
        pass
    return "UTC"


def _known(name: str) -> bool:
    try:
        ZoneInfo(name)
        return True
    except (ZoneInfoNotFoundError, ValueError):
        return False


def zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError):
        raise Invalid(f"unknown timezone {name!r} (use a name such as Asia/Kolkata or Europe/London)") from None


def check(schedule: str, timezone: str) -> str:
    """The schedule, tidied, or Invalid with a reason."""
    expr = " ".join(schedule.split())
    if len(expr.split(" ")) != 5:
        raise Invalid("a schedule has 5 fields: minute hour day-of-month month day-of-week, e.g. '0 19 * * 1-5'")
    try:
        CronSim(expr, datetime.now(zone(timezone)))
    except CronSimError as exc:
        raise Invalid(f"bad schedule {expr!r}: {exc}") from None
    return expr


def next_runs(schedule: str, timezone: str, after: datetime, count: int = 1) -> list[datetime]:
    """The next `count` times strictly after `after`, as aware datetimes in the routine's timezone."""
    it = CronSim(schedule, after.astimezone(zone(timezone)))
    found: list[datetime] = []
    for when in it:
        found.append(when)
        if len(found) >= count:
            break
    return found


def latest_due(schedule: str, timezone: str, since: datetime, now: datetime) -> datetime:
    """The last scheduled time in (since - 1 minute, now]: after downtime only the latest missed run counts."""
    last = since
    for when in CronSim(schedule, since.astimezone(zone(timezone))):
        if when > now:
            break
        last = when
    return last


def describe(schedule: str) -> str:
    """A short English reading of common schedules ("Weekdays at 19:00"); the expression itself otherwise."""
    try:
        minute, hour, dom, month, dow = schedule.split()
    except ValueError:
        return schedule
    if not (minute.isdigit() and hour.isdigit()) or month != "*":
        if minute.isdigit() and hour == "*" and dom == "*" and month == "*" and dow == "*":
            return f"Every hour at :{int(minute):02d}"
        if minute.startswith("*/") and hour == "*" and dom == "*" and dow == "*":
            return f"Every {minute[2:]} minutes"
        return schedule
    at = f"{int(hour):02d}:{int(minute):02d}"
    if dom == "*" and dow in ("*", "0-6", "1-7"):
        return f"Every day at {at}"
    if dom == "*":
        days = _days(dow)
        return f"{days} at {at}" if days else schedule
    if dow == "*":
        if dom == "L":
            return f"Last day of every month at {at}"
        if dom.isdigit():
            return f"Day {int(dom)} of every month at {at}"
    return schedule


def _days(field: str) -> str | None:
    picked: set[int] = set()
    for part in field.split(","):
        if "-" in part:
            a, _, b = part.partition("-")
            if not (a.isdigit() and b.isdigit()):
                return None
            picked.update(d % 7 for d in range(int(a), int(b) + 1))
        elif part.isdigit():
            picked.add(int(part) % 7)
        else:
            return None
    if picked == {1, 2, 3, 4, 5}:
        return "Weekdays"
    if picked == {0, 6}:
        return "Weekends"
    if picked == set(range(7)):
        return "Every day"
    if len(picked) == 1:
        return DAYS[next(iter(picked))] + "s"
    order = [1, 2, 3, 4, 5, 6, 0]
    return ", ".join(SHORT_DAYS[d] for d in order if d in picked)


def run_prompt(
    routine: dict,
    run_id: str,
    trigger: str,
    now: datetime,
    triggers: list[tuple[str, str | None]] | None = None,
    attempt: int = 1,
    max_attempts: int = 1,
    reason: str | None = None,
    notes: str = "",
    several: bool = False,
    continued: bool = False,
) -> str:
    """The message a run puts into the routine's chat: what fired, today's date facts, notes, the instructions."""
    tz = zone(routine["timezone"])
    local = now.astimezone(tz)
    last_day = calendar.monthrange(local.year, local.month)[1]
    month_end = local.replace(day=last_day)
    why = "Scheduled run" if trigger == "schedule" else "Run started by the owner now"
    lines = [
        f"{why} of the Krewd routine \"{routine['title']}\" (routine id {routine['id']}, instructions version "
        f"{routine['version']}, run {run_id[:8]}).",
        f"Now: {DAYS[(local.weekday() + 1) % 7]} {local.day} {calendar.month_name[local.month]} {local.year}, "
        f"{local:%H:%M} {local.tzname()} ({routine['timezone']}). This month ends on "
        f"{DAYS[(month_end.weekday() + 1) % 7]} {last_day} {calendar.month_name[local.month]}.",
    ]
    named = [t for t in (triggers or []) if t[0] != "main" or several]
    if named:
        fired = " and ".join(f"{name} ({text})" if text else name for name, text in named)
        lines.append(f"Trigger: {fired}. Attempt {attempt} of {max_attempts}.")
    elif max_attempts > 1:
        lines.append(f"Attempt {attempt} of {max_attempts}.")
    if reason:
        lines.append(f"This is the follow-up you asked for in the last attempt: {reason}")
    if continued:
        lines.append("This attempt continues in the chat and session of the last one: what you did then is above.")
    else:
        lines.append(
            "This run has its own new chat and session: earlier runs are not in your context. What they learned "
            "is in the notes below."
        )
    rid = routine["id"][:8]
    lines += [
        "",
        "Notes kept for this routine (what earlier runs learned):",
        notes.strip() or "(none yet)",
        "",
        "Instructions:",
        routine["instructions"].strip(),
        "",
        ("Do only the part of the instructions for this trigger. " if named else "Follow the instructions above for today. ")
        + "Start your reply with the result in a line or two.",
    ]
    if attempt < max_attempts:
        lines.append(
            f"If it cannot be done yet (for example a counter or time is not there yet), ask Krewd to try again later: "
            f"`krewd routines later {rid} --in 5m --reason \"...\"` makes attempt {attempt + 1} of {max_attempts}. "
            "On a real failure (signed out, a missing button, an error page), do not retry: stop and tell the owner."
        )
    else:
        lines.append("No attempts are left after this one: if it cannot be done, stop and tell the owner it could not be done.")
    lines.append(
        f"If the job was not done (a failure, or no attempts left), run `krewd routines result {rid} failed --reason "
        f"\"...\"` before you reply. If you ask the owner something and must wait for the answer, run "
        f"`krewd routines result {rid} waiting --reason \"...\"`. Krewd keeps those chats for the owner; a run "
        "that did its job needs neither."
    )
    lines.append(
        f"Notes: only when you learned something lasting that a later run needs (where a button is, an exact name, "
        f"a trap, or a correction to a note), add it with `krewd routines notes {rid} --append \"...\"`, or fix the "
        "note it corrects. Never note what this run did or found (\"checked in at 09:55\", \"no change needed\"): "
        "your reply is kept as the run's summary. If nothing new was learned, leave the notes alone."
    )
    lines.append(
        "Ask in this chat before anything consequential the instructions do not clearly allow, and wait for the "
        "owner's answer. Change the routine itself only when the owner asks (`krewd routines --help`)."
    )
    return "\n".join(lines)
