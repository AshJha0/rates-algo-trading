"""Is the forward log up to date, or is a trading day missing?

The forward-log job runs unattended. A failed run leaves a red mark that nobody is told about,
and a run that never starts (GitHub disables a schedule after 60 days without repository
activity) leaves no mark at all. This check reads the ledger and says, in words, what is missing,
so the workflow can open an issue.

A decision is expected from every US bond-market business day: Monday to Friday except the US
federal holidays and Good Friday (pandas' holiday rules; an early close is still a business day).
The check looks at the newest end of the ledger only, so an old gap does not alert for ever:

* the last line is a decision (the open one);
* no business day lies strictly between the last two decisions' ``decided_on`` dates;
* the last decision is from the latest business day before ``today``;
* the line before the last decision is not a ``skipped`` session.

Usage: ``python -m ratesresearch.forward.health FORWARD_DIR [--today YYYY-MM-DD]``. Exit status 0
when healthy, 1 with one line per problem otherwise.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

from pandas.tseries.holiday import AbstractHolidayCalendar, GoodFriday, USFederalHolidayCalendar


class _BondMarketHolidays(AbstractHolidayCalendar):
    # pandas reads the rules from the class; they are never changed
    rules = [*USFederalHolidayCalendar.rules, GoodFriday]  # noqa: RUF012


def business_days_between(start: date, end: date) -> list[date]:
    """Business days strictly after ``start`` and strictly before ``end``."""
    if end <= start:
        return []
    holidays = {
        d.date() for d in _BondMarketHolidays().holidays(start=str(start), end=str(end)).tolist()
    }
    out = []
    d = start + timedelta(days=1)
    while d < end:
        if d.weekday() < 5 and d not in holidays:
            out.append(d)
        d += timedelta(days=1)
    return out


def _days(days: list[date]) -> str:
    return ", ".join(str(d) for d in days)


def problems(forward_dir: Path, today: date) -> list[str]:
    """What is missing from the forward log as of ``today``; empty when it is up to date."""
    ledger = forward_dir / "ledger.jsonl"
    if not ledger.is_file():
        return [f"no ledger at {ledger}"]
    entries: list[dict[str, Any]] = [
        json.loads(line) for line in ledger.read_text(encoding="utf-8").splitlines() if line.strip()
    ]
    decisions = [e for e in entries if e.get("kind") == "decision"]
    if not decisions:
        return ["the ledger holds no decision"]
    out: list[str] = []
    last = decisions[-1]
    if entries[-1] is not last:
        out.append(f"the last ledger line (seq {entries[-1].get('seq')}) is not an open decision")
    decided = date.fromisoformat(last["decided_on"])
    if len(decisions) > 1:
        gap = business_days_between(date.fromisoformat(decisions[-2]["decided_on"]), decided)
        if gap:
            out.append(f"no decision was made from the business day(s) {_days(gap)}")
    late = business_days_between(decided, today)
    if late:
        out.append(
            f"the last decision is from {decided}; nothing from the business day(s) {_days(late)}"
        )
    before = entries[entries.index(last) - 1] if entries.index(last) > 0 else {}
    if before.get("kind") == "skipped":
        out.append(f"session {before.get('session')} was skipped: {before.get('reason')}")
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("forward_dir", type=Path)
    parser.add_argument("--today", type=date.fromisoformat, default=None)
    args = parser.parse_args(argv)
    today = args.today or datetime.now(UTC).date()
    found = problems(args.forward_dir, today)
    if found:
        for p in found:
            print(p)
        return 1
    print(f"forward log up to date as of {today}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
