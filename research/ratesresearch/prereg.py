"""Pre-registration validator and ledger reader, the Python twin of
``io.ratesalgo.backtest.prereg`` (API_SPEC.md Section 16.8).

The rules are the Java ones: every key present and non-blank; ISO dates with start not after
end; the evaluation period and every holdout date outside the fitting period; no holdout date
inside the evaluation period; band points exactly low, central and high; the config file's
SHA-256 (text, LF line endings) equal to ``config_sha256``.
"""

from __future__ import annotations

import hashlib
import json
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
    for n, line in enumerate((x for x in p.read_text(encoding="utf-8").splitlines() if x.strip()), 1):
        entry = json.loads(line)
        if entry.get("prev_sha256") != prev:
            raise ValueError(f"{p}: hash chain broken at line {n}")
        if entry.get("seq") != n:
            raise ValueError(f"{p}: seq {entry.get('seq')} at line {n}")
        entries.append(entry)
        prev = sha256_text(line)
    return entries
