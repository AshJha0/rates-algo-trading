"""The forward log's health check and the pinned dependency file."""

from __future__ import annotations

import json
import re
import tomllib
from datetime import date
from pathlib import Path

import pytest

from ratesresearch.forward import health

ROOT = Path(__file__).resolve().parents[2]


def _ledger(tmp_path: Path, rows: list[dict[str, object]]) -> Path:
    lines = [json.dumps({"seq": n, **r}) for n, r in enumerate(rows, 1)]
    (tmp_path / "ledger.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return tmp_path


def _decision(decided_on: str, session: str) -> dict[str, object]:
    return {"kind": "decision", "decided_on": decided_on, "session": session}


def _outcome(session: str) -> dict[str, object]:
    return {"kind": "outcome", "session": session}


def test_business_days_leave_out_weekends_and_bond_market_holidays() -> None:
    # Veterans Day, Wednesday 2026-11-11, and Thanksgiving, Thursday 2026-11-26
    assert health.business_days_between(date(2026, 11, 10), date(2026, 11, 12)) == []
    assert health.business_days_between(date(2026, 11, 25), date(2026, 11, 30)) == [
        date(2026, 11, 27)
    ]
    # Good Friday 2026-04-03 is not a federal holiday but the bond market is closed
    assert health.business_days_between(date(2026, 4, 2), date(2026, 4, 6)) == []
    assert health.business_days_between(date(2026, 11, 20), date(2026, 11, 23)) == []
    assert health.business_days_between(date(2026, 11, 20), date(2026, 11, 20)) == []


def test_a_ledger_that_follows_the_calendar_is_healthy(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    # the shape of the real log: 11-10 decides for 11-11 (a holiday), marked on 11-12
    d = _ledger(
        tmp_path,
        [
            _decision("2026-11-09", "2026-11-10"),
            _outcome("2026-11-10"),
            _decision("2026-11-10", "2026-11-11"),
            _outcome("2026-11-11"),
            _decision("2026-11-12", "2026-11-13"),
        ],
    )
    assert health.problems(d, date(2026, 11, 13)) == []
    # Saturday's run and a Monday manual run still see Friday as missing only from Tuesday on
    assert health.problems(d, date(2026, 11, 14)) == [
        "the last decision is from 2026-11-12; nothing from the business day(s) 2026-11-13"
    ]
    assert health.main([str(d), "--today", "2026-11-13"]) == 0
    assert "up to date as of 2026-11-13" in capsys.readouterr().out


def test_missing_days_skipped_sessions_and_a_closed_ledger_are_reported(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    d = _ledger(
        tmp_path,
        [
            _decision("2026-11-16", "2026-11-17"),
            {"kind": "skipped", "session": "2026-11-17", "reason": "no mark within 5 days"},
            _decision("2026-11-19", "2026-11-20"),
        ],
    )
    assert health.problems(d, date(2026, 11, 24)) == [
        "no decision was made from the business day(s) 2026-11-17, 2026-11-18",
        "the last decision is from 2026-11-19; nothing from the business day(s) "
        "2026-11-20, 2026-11-23",
        "session 2026-11-17 was skipped: no mark within 5 days",
    ]
    assert health.main([str(d), "--today", "2026-11-24"]) == 1
    assert capsys.readouterr().out.count("\n") == 3

    closed = tmp_path / "closed"
    closed.mkdir()
    _ledger(closed, [_decision("2026-11-19", "2026-11-20"), _outcome("2026-11-20")])
    assert health.problems(closed, date(2026, 11, 20)) == [
        "the last ledger line (seq 2) is not an open decision"
    ]
    empty = tmp_path / "empty"
    empty.mkdir()
    assert health.problems(empty, date(2026, 11, 20)) == [f"no ledger at {empty / 'ledger.jsonl'}"]
    _ledger(empty, [_outcome("2026-11-20")])
    assert health.problems(empty, date(2026, 11, 20)) == ["the ledger holds no decision"]


def test_the_lock_file_pins_every_declared_dependency() -> None:
    """SECURITY.md says Python dependencies are pinned; this is the pin, and CI installs with it."""
    project = tomllib.loads((ROOT / "research/pyproject.toml").read_text(encoding="utf-8"))[
        "project"
    ]
    declared = list(project["dependencies"])
    for extra in project["optional-dependencies"].values():
        declared += extra
    names = {re.split(r"[<>=!~ \[]", d, maxsplit=1)[0].lower().replace("_", "-") for d in declared}
    pinned: dict[str, str] = {}
    for line in (ROOT / "research/requirements-lock.txt").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            m = re.fullmatch(r"([A-Za-z0-9._-]+)==([^ ;]+)( ;.*)?", line.strip())
            assert m, f"not an exact pin: {line}"
            pinned[m.group(1).lower().replace("_", "-")] = m.group(2)
    assert names <= pinned.keys(), f"not pinned: {sorted(names - pinned.keys())}"
    assert pinned["quantlib"] == "1.44"  # DR-P1-06: the golden generator's version
    for workflow in ("ci.yml", "forward.yml"):
        text = (ROOT / ".github/workflows" / workflow).read_text(encoding="utf-8")
        assert "-c research/requirements-lock.txt" in text or (
            "-c code/research/requirements-lock.txt" in text
        ), f"{workflow} installs without the lock file"
