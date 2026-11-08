"""Forward log inputs and the independent ledger check. No network: downloads are faked."""

from __future__ import annotations

import hashlib
import json
from datetime import date
from pathlib import Path

import pandas as pd
import pytest

from ratesresearch.forward import check, fetch

SETTINGS = {
    "window_start_minute_et": 870,
    "window_end_minute_et": 960,
    "min_prints": 5,
    "fallback_spread_bp": {"2Y": -20.0, "5Y": -30.0, "10Y": -40.0, "30Y": -80.0},
}

# Hand-written rows in the Treasury CSV layout (values made up for the test).
TREASURY = (
    'Date,"1 Mo","2 Yr","5 Yr","10 Yr","30 Yr"\n'
    "11/06/2026,4.02,4.51,4.64,4.86,5.26\n"
    "11/05/2026,4.03,4.46,4.59,4.84,\n"
)
SOFR = json.dumps(
    {
        "refRates": [
            {"effectiveDate": "2026-11-05", "percentRate": 3.92, "revisionIndicator": ""},
            {"effectiveDate": "2026-11-04", "percentRate": 3.97, "revisionIndicator": ""},
            {"effectiveDate": "2026-11-09", "percentRate": 9.99, "revisionIndicator": ""},
        ]
    }
)


def test_parse_treasury_skips_blank_cells() -> None:
    curve = fetch.parse_treasury(TREASURY)
    assert curve[date(2026, 11, 6)]["10Y"] == 4.86
    assert "30Y" not in curve[date(2026, 11, 5)]


def test_parse_sofr_never_reads_after_the_day() -> None:
    s = fetch.parse_sofr(SOFR, date(2026, 11, 6))
    assert s == {"effective_date": "2026-11-05", "pct": 3.92, "revision": ""}
    with pytest.raises(ValueError):
        fetch.parse_sofr(SOFR, date(2026, 11, 1))


def _prints(n10: int) -> pd.DataFrame:
    t = pd.Timestamp("2026-11-06 15:00", tz=fetch.ET)
    rows = [{"t": t, "tenor": 10.0, "rate": 4.40 + 0.01 * i} for i in range(n10)]
    rows.append({"t": pd.Timestamp("2026-11-06 09:00", tz=fetch.ET), "tenor": 2.0, "rate": 4.0})
    return pd.DataFrame(rows)


def test_dtcc_marks_need_enough_prints_in_the_window() -> None:
    marks = fetch.dtcc_marks(_prints(5), SETTINGS)
    assert marks == {"10Y": {"rate": 4.42, "source": "dtcc_median", "prints": 5}}
    assert fetch.dtcc_marks(_prints(4), SETTINGS) == {}


def test_swap_marks_fall_back_to_carried_then_declared_spread() -> None:
    ust = {"2Y": 4.5, "5Y": 4.6, "10Y": 4.8, "30Y": 5.2}
    dtcc = {"10Y": {"rate": 4.42, "source": "dtcc_median", "prints": 5}}
    out = fetch.swap_marks(ust, dtcc, {"2Y": (-15.0, "2026-11-05")}, SETTINGS)
    assert out["10Y"]["source"] == "dtcc_median"
    assert out["2Y"] == {
        "rate": 4.35,
        "source": "ust_plus_spread",
        "spread_bp": -15.0,
        "spread_from": "2026-11-05",
    }
    assert out["30Y"]["spread_from"] == "config" and out["30Y"]["rate"] == pytest.approx(4.4)


def test_last_spreads_reads_only_earlier_files(tmp_path: Path) -> None:
    for d, rate in (("2026-11-04", 4.30), ("2026-11-05", 4.40), ("2026-11-06", 9.0)):
        doc = {
            "ust_par_pct": {"10Y": 4.80},
            "swaps_pct": {"10Y": {"rate": rate, "source": "dtcc_median"}},
        }
        (tmp_path / f"{d}.json").write_text(json.dumps(doc), encoding="utf-8")
    assert fetch.last_spreads(tmp_path, date(2026, 11, 6)) == {"10Y": (-40.0, "2026-11-05")}


def test_build_inputs_with_a_missing_dtcc_file(tmp_path: Path) -> None:
    def get(url: str) -> bytes:
        if "treasury" in url:
            return TREASURY.encode()
        if "newyorkfed" in url:
            return SOFR.encode()
        raise OSError("HTTP 404")

    curve = {"2Y": 4.51, "5Y": 4.64, "10Y": 4.86, "30Y": 5.26}
    doc = fetch.build_inputs(None, tmp_path, SETTINGS, get, date(2026, 11, 7))
    assert doc is not None and doc["as_of"] == "2026-11-06"
    assert doc["sofr"]["pct"] == 3.92
    assert all(v["source"] == "ust_plus_spread" for v in doc["swaps_pct"].values())
    assert doc["swaps_pct"]["10Y"]["rate"] == pytest.approx(curve["10Y"] - 0.40)
    assert "error" in doc["sources"][-1]
    assert fetch.build_inputs(date(2026, 11, 3), tmp_path, SETTINGS, get, date(2026, 11, 7)) is None


def _ledger(tmp_path: Path, mutate: bool = False) -> Path:
    vpu = check.VALUE_PER_UNIT["103"]
    decision = {"kind": "decision", "session": "2026-11-09", "mids": {"103": 4.40}}
    book = {
        "103": {
            "position": 100_000_000,
            "mid_committed": 4.40,
            "mid_marked": 4.45,
            "close_mark": round(1e8 * 0.05 * vpu, 2),
        }
    }
    outcome = {
        "kind": "outcome",
        "closes_seq": 1,
        "session": "2026-11-09",
        "book": book,
        "close_mark": book["103"]["close_mark"],
        "session_result": {"position_pnl": 100.0},
        "pnl_total": book["103"]["close_mark"] + (99.0 if mutate else 100.0),
    }
    lines: list[str] = []
    prev = check.GENESIS
    for seq, e in enumerate((decision, outcome), start=1):
        line = json.dumps(
            {**e, "seq": seq, "prev_sha256": prev}, sort_keys=True, separators=(",", ":")
        )
        prev = hashlib.sha256(line.encode()).hexdigest()
        lines.append(line)
    p = tmp_path / "ledger.jsonl"
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


def test_check_accepts_a_sound_ledger(tmp_path: Path) -> None:
    assert check.check(_ledger(tmp_path)) == []


def test_check_finds_wrong_arithmetic_and_tampering(tmp_path: Path) -> None:
    assert any("pnl_total" in p for p in check.check(_ledger(tmp_path, mutate=True)))
    p = _ledger(tmp_path)
    p.write_text(p.read_text(encoding="utf-8").replace("4.45", "4.46"), encoding="utf-8")
    assert check.check(p)
