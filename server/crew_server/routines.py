"""Routines: owner instructions that Crew runs on a schedule, every run in the routine's own chat.

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

# A run missed while Crew was down still happens once if Crew is back within this window; older ones are skipped.
CATCH_UP = timedelta(hours=6)
DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
SHORT_DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]


class Invalid(ValueError):
    """A schedule, timezone or other routine field that cannot be used."""


def local_timezone() -> str:
    """CREW_TIMEZONE, else this machine's own timezone name, else UTC."""
    for candidate in (os.environ.get("CREW_TIMEZONE"), os.environ.get("TZ")):
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
    routine: dict, run_id: str, trigger: str, now: datetime, first_run: bool
) -> str:
    """The message a run puts into the routine's chat: what this is, today's date facts, the instructions."""
    tz = zone(routine["timezone"])
    local = now.astimezone(tz)
    last_day = calendar.monthrange(local.year, local.month)[1]
    month_end = local.replace(day=last_day)
    why = "Scheduled run" if trigger == "schedule" else "Run started by the owner now"
    lines = [
        f"{why} of the Crew routine \"{routine['title']}\" (routine id {routine['id']}, instructions version "
        f"{routine['version']}, run {run_id[:8]}).",
        f"Now: {DAYS[(local.weekday() + 1) % 7]} {local.day} {calendar.month_name[local.month]} {local.year}, "
        f"{local:%H:%M} {local.tzname()} ({routine['timezone']}). This month ends on "
        f"{DAYS[(month_end.weekday() + 1) % 7]} {last_day} {calendar.month_name[local.month]}.",
    ]
    if first_run:
        lines.append("This is the routine's own chat: every run comes here, so later runs see this one.")
    else:
        lines.append("This is the routine's own chat: earlier runs and the owner's replies are above.")
    lines += [
        "",
        "Instructions:",
        routine["instructions"].strip(),
        "",
        "Follow the instructions above for today. Start your reply with the result in a line or two. "
        "Ask in this chat before anything consequential the instructions do not clearly allow, and wait "
        "for the owner's answer. To change this routine (only when the owner asks), use the `crew routines` "
        "command.",
    ]
    return "\n".join(lines)
