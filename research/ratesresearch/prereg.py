"""Pre-registration validator and ledger reader, the Python twin of
``io.ratesalgo.backtest.prereg`` (API_SPEC.md Section 16.8).

The rules are the Java ones: every key present and non-blank; ISO dates with start not after
end; the evaluation period and every holdout date outside the fitting period; no holdout date
inside the evaluation period; band points exactly low, central and high; the config file's
SHA-256 (text, LF line endings) equal to ``config_sha256``; ``prior_looks``, when present, a list.

``looks_report`` counts looks per evaluation data set across pre-registration ids, with the keys
of the Java ``DataSets``: the ledger's ``look`` field counts runs of one id, so several ids that
each read the same sessions once all say look 1. ``python -m ratesresearch.prereg LEDGER``
verifies the chain and prints the report; CI runs it on ``data/ledger/ledger.jsonl``.
"""

from __future__ import annotations

import hashlib
import json
import sys
from datetime import date, timedelta
from pathlib import Path
from typing import Any

import yaml

REQUIRED = (
    "prereg_version",
    "id",
    "title",
    "hypothesis",
    "metric",
    "baseline",
    "treatments",
    "period",
    "fitting_period",
    "holdout",
    "success_threshold",
    "assumption_band",
    "author",
    "date",
    "config",
    "config_sha256",
)
GENESIS = "0" * 64


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.replace("\r\n", "\n").encode("utf-8")).hexdigest()


def sha256_text_file(path: str | Path) -> str:
    return sha256_text(Path(path).read_bytes().decode("utf-8"))


def _date(v: Any, key: str, problems: list[str]) -> date | None:
    if isinstance(v, date):
        return v
    try:
        return date.fromisoformat(str(v).strip())
    except ValueError:
        problems.append(f"{key} is not an ISO date: {v}")
        return None


def _range(raw: dict[str, Any], key: str, problems: list[str]) -> tuple[date, date] | None:
    v = raw.get(key)
    if not isinstance(v, dict):
        problems.append(f"{key} must have start and end")
        return None
    s = _date(v.get("start"), f"{key}.start", problems)
    e = _date(v.get("end"), f"{key}.end", problems)
    if s is None or e is None:
        return None
    if s > e:
        problems.append(f"{key} starts after it ends: {s} > {e}")
        return None
    return s, e


def validate(path: str | Path, repo_root: str | Path) -> list[str]:
    """Every problem with the pre-registration at ``path``; empty when it is valid."""
    p = Path(path)
    if not p.is_file():
        return [f"pre-registration not found: {p}"]
    raw = yaml.safe_load(p.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        return [f"{p}: not a YAML mapping"]
    problems = [f"missing or blank key '{k}'" for k in REQUIRED if raw.get(k) in (None, "")]
    if problems:
        return problems
    if raw["prereg_version"] != 1:
        problems.append(f"prereg_version must be 1, found {raw['prereg_version']}")
    period = _range(raw, "period", problems)
    fitting = _range(raw, "fitting_period", problems)
    holdout: list[date] = []
    if isinstance(raw["holdout"], list) and raw["holdout"]:
        for v in raw["holdout"]:
            d = _date(v, "holdout", problems)
            if d is not None:
                holdout.append(d)
    else:
        problems.append("holdout must be a non-empty list of dates")
    if len(holdout) == 1:
        problems.append("holdout needs at least two dates: the daily statistics need two days")
    if period and len(business_days(period[0], period[1])) < 2:
        problems.append("evaluation period needs at least two business days")
    _date(raw["date"], "date", problems)
    if period and fitting and not (period[1] < fitting[0] or period[0] > fitting[1]):
        problems.append(f"evaluation period {period[0]}..{period[1]} overlaps the fitting period")
    for d in holdout:
        if fitting and fitting[0] <= d <= fitting[1]:
            problems.append(f"holdout date {d} overlaps the fitting period")
        if period and period[0] <= d <= period[1]:
            problems.append(f"holdout date {d} lies inside the evaluation period")
    if not isinstance(raw["treatments"], list) or not raw["treatments"]:
        problems.append("treatments must be a non-empty list")
    band = raw["assumption_band"]
    if not isinstance(band, dict) or {str(x).lower() for x in band.get("points") or []} != {
        "low",
        "central",
        "high",
    }:
        problems.append("assumption_band.points must be low, central and high (DR-008)")
    if raw.get("prior_looks") is not None and not isinstance(raw["prior_looks"], list):
        problems.append("prior_looks must be a list of pre-registration ids")
    config = Path(repo_root) / str(raw["config"])
    if not config.is_file():
        problems.append(f"config file not found: {raw['config']}")
    elif sha256_text_file(config) != str(raw["config_sha256"]):
        problems.append("config_sha256 does not match the config file")
    return problems


def business_days(start: date, end: date) -> list[date]:
    out = []
    d = start
    while d <= end:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def read_ledger(path: str | Path) -> list[dict[str, Any]]:
    """Ledger entries in order, after verifying seq and the SHA-256 chain."""
    p = Path(path)
    if not p.exists():
        return []
    entries: list[dict[str, Any]] = []
    prev = GENESIS
    for n, line in enumerate(
        (x for x in p.read_text(encoding="utf-8").splitlines() if x.strip()), 1
    ):
        entry = json.loads(line)
        if entry.get("prev_sha256") != prev:
            raise ValueError(f"{p}: hash chain broken at line {n}")
        if entry.get("seq") != n:
            raise ValueError(f"{p}: seq {entry.get('seq')} at line {n}")
        entries.append(entry)
        prev = sha256_text(line)
    return entries


def _sessions_key(lines: set[str]) -> str:
    text = "".join(line + "\n" for line in sorted(lines))
    return "sessions:" + sha256_text(text)[:16]


def data_set_key(entry: dict[str, Any]) -> str | None:
    """The data set a ledger row read, as ``DataSets.key`` in Java; None when it names none.

    Rows with journals: the sorted distinct ``<date>|<seed>`` lines, hashed. Two runs with the
    same dates and seeds share a key even when a model change altered the generated bytes.
    """
    journals = entry.get("journals")
    if isinstance(journals, list) and journals:
        return _sessions_key({f"{j['date']}|{int(j['seed'])}" for j in journals})
    sha = entry.get("data_sha256")
    if isinstance(sha, str) and len(sha) >= 16:
        return "data:" + sha[:16]
    return None


def _describe(entry: dict[str, Any]) -> str:
    journals = entry.get("journals")
    if isinstance(journals, list) and journals:
        dates = sorted({str(j["date"]) for j in journals})
        seeds = sorted({int(j["seed"]) for j in journals})
        return f"{len(dates)} sessions {dates[0]}..{dates[-1]}, seeds {seeds[0]}..{seeds[-1]}"
    data = entry.get("data")
    files = data if isinstance(data, list) else [data]
    return "files " + ", ".join(str(f["name"]) if isinstance(f, dict) else str(f) for f in files)


def looks_by_data_set(entries: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    """Ledger rows per data set key, keys in order of first use, rows in ledger order."""
    out: dict[str, list[dict[str, Any]]] = {}
    for e in entries:
        k = data_set_key(e)
        if k is not None:
            out.setdefault(k, []).append(e)
    return out


def undeclared_looks(
    entries: list[dict[str, Any]], key: str, prereg_id: str, declared: list[str]
) -> list[str]:
    """Ids that have read ``key`` and are neither ``prereg_id`` nor declared."""
    out: list[str] = []
    for e in entries:
        pid = str(e.get("prereg_id"))
        if data_set_key(e) == key and pid != prereg_id and pid not in declared and pid not in out:
            out.append(pid)
    return out


def looks_report(entries: list[dict[str, Any]]) -> str:
    """The looks of every data set as text, the same text as ``DataSets.report`` in Java."""
    groups = looks_by_data_set(entries)
    lines: list[str] = []
    repeated = 0
    unkeyed = len(entries)
    for key, rows in groups.items():
        unkeyed -= len(rows)
        lines.append(
            f"{key}  {_describe(rows[0])}: {len(rows)} look{'' if len(rows) == 1 else 's'}"
        )
        for n, r in enumerate(rows, 1):
            lines.append(
                f"  look {n}  seq {r.get('seq')}  {r.get('prereg_id')}  {r.get('scope')}"
                f"  {r.get('verdict')}  (recorded as look {r.get('look')})"
            )
        if len(rows) > 1:
            repeated += 1
            ids = ", ".join(str(r.get("prereg_id")) for r in rows)
            lines.append(
                f"  warning: {len(rows)} looks at the same data by {ids}; treat their verdicts"
                " as one family, not as independent results"
            )
    tail = f"{len(entries)} ledger rows, {len(groups)} data sets, {repeated} read more than once"
    if unkeyed > 0:
        tail += f", {unkeyed} rows name no data"
    lines.append(tail)
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    """Verifies a ledger's chain and prints its looks per data set; 1 when the chain is broken."""
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("usage: python -m ratesresearch.prereg LEDGER", file=sys.stderr)
        return 2
    path = Path(args[0])
    if not path.is_file():
        print(f"ledger not found: {path}")
        return 1
    try:
        entries = read_ledger(path)
    except ValueError as e:
        print(e)
        return 1
    print(f"ledger chain verified: {len(entries)} lines")
    print(looks_report(entries), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
