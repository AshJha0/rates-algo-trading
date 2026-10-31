"""Tests of the edge-study programme: pre-registrations, statistics, fits, the reserve cut-off
and the ledger rows (research/ratesresearch/edge)."""

from __future__ import annotations

import json
import math
import random
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from ratesresearch import prereg, presets
from ratesresearch.edge import READ_END, common, e1_auction, e4_size, e6_carry

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "data/fixtures/edge"
STUDIES = ("E1", "E2", "E3", "E4", "E5", "E6")


@pytest.mark.parametrize("study", STUDIES)
def test_preregistration_is_valid(study: str) -> None:
    assert prereg.validate(ROOT / "research/prereg" / f"{study}.yaml", ROOT) == []


def test_configs_pin_the_programme_file() -> None:
    sha = prereg.sha256_text_file(ROOT / "research/prereg/edge_programme.yaml")
    for study in STUDIES:
        text = (ROOT / "research/prereg" / f"{study}.config.yaml").read_text(encoding="utf-8")
        assert f"programme_sha256: {sha}" in text


def test_no_fixture_reads_the_reserve() -> None:
    checked = 0
    for path in FIXTURES.glob("*.csv"):
        df = pd.read_csv(path, dtype=str)
        for col in ("date", "auction_date", "issue_date", "report_date"):
            if col in df.columns:
                assert df[col].max() <= READ_END, f"{path.name}:{col}"
                checked += 1
    assert checked >= 9


def test_bootstrap_is_deterministic_and_p_is_bounded() -> None:
    x = np.arange(30, dtype=float) - 10.0
    a = common.mean_test(x, 3)
    b = common.mean_test(x, 3)
    assert a == b
    assert 0 < a["p"] <= 1
    assert a["ci_low"] < a["mean"] < a["ci_high"]
    zero = common.mean_test(np.r_[np.ones(20), -np.ones(20)], 2)
    assert zero["p"] > 0.5


def test_equivalence_test_inside_and_outside() -> None:
    rng = np.random.default_rng(1)
    inside = common.equivalence_test(0.05 + 0.005 * rng.standard_normal(200), 5, 0.025, 0.10)
    assert inside["p"] < 0.01
    outside = common.equivalence_test(0.2 + 0.005 * rng.standard_normal(200), 5, 0.025, 0.10)
    assert outside["p"] > 0.5


def test_judge_rule() -> None:
    t = {"mean": 1.0, "ci_low": 0.5, "ci_high": 1.5, "holm_p": 0.01}
    assert common.judge(t, 1, 0.2) == "passed"
    assert common.judge(t, -1, 0.2) == "reversed"
    assert common.judge({**t, "holm_p": 0.2}, 1, 0.2) == "failed"
    assert common.judge(t, 1, 2.0) == "failed"


def test_holm_skips_missing() -> None:
    tests = {"a": {"p": 0.01}, "b": {"p": 0.04}, "c": {"p": None}}
    common.apply_holm(tests)
    assert tests["a"]["holm_p"] == pytest.approx(0.02)
    assert tests["b"]["holm_p"] == pytest.approx(0.04)
    assert "holm_p" not in tests["c"]


def test_censored_lognormal_recovers_parameters() -> None:
    rng = random.Random(7)
    mu, sigma, cap = math.log(25.0), 0.9, 100.0
    draws = [math.exp(rng.gauss(mu, sigma)) for _ in range(20000)]
    vals = np.array([min(round(d, 1), cap) for d in draws])
    cens = np.array([d >= cap for d in draws])
    fit = e4_size.fit_censored_lognormal(vals, np.ones(len(vals)), cens, "central")
    assert fit["mu"] == pytest.approx(mu, abs=0.03)
    assert fit["sigma"] == pytest.approx(sigma, abs=0.03)
    naive = e4_size.fit_censored_lognormal(vals, np.ones(len(vals)), cens, "high")
    assert naive["sigma"] < fit["sigma"]


def test_butterfly_residual() -> None:
    curve = pd.DataFrame(
        {
            "date": ["d"],
            "y1": [1.0],
            "y2": [2.1],
            "y3": [3.0],
            "y5": [5.0],
            "y7": [7.0],
            "y10": [10.0],
            "y20": [20.0],
            "y30": [30.5],
        }
    )
    r = e1_auction.residuals(curve)
    assert r["r2"].iloc[0] == pytest.approx(10.0)
    assert r["r5"].iloc[0] == pytest.approx(0.0)
    assert r["r30"].iloc[0] == pytest.approx(1050.0)


def test_par_duration() -> None:
    assert e6_carry.duration(0.0, 5) == 5
    assert e6_carry.duration(0.04, 10) == pytest.approx(8.1757, abs=1e-3)


def test_ledger_rows_of_the_programme() -> None:
    entries = prereg.read_ledger(ROOT / "data/ledger/ledger.jsonl")
    edge = {e["prereg_id"]: e for e in entries if str(e["prereg_id"]).startswith("EDGE-")}
    assert set(edge) == {f"EDGE-{s}" for s in STUDIES}
    for sid, e in edge.items():
        assert e["verdict"] in ("supported", "not supported", "inconclusive")
        assert e["prereg_sha256"] == prereg.sha256_text_file(
            ROOT / "research/prereg" / f"{sid[5:]}.yaml"
        )
        for f in e["data"]:
            assert (ROOT / f["name"]).is_file()
        for t in e["tests"].values():
            assert {"mean", "ci_low", "ci_high", "p", "holm_p", "outcome"} <= set(t)


def test_edge_presets_name_their_ledger_row() -> None:
    for p in presets.load_presets(ROOT / "data/presets.yaml"):
        if "EDGE-E" in str(p.get("notes", "")):
            assert presets.validate_preset(p) == []
            assert "ledger seq" in p["notes"]


def test_append_ledger_extends_the_chain(tmp_path: Path) -> None:
    target = tmp_path / "ledger.jsonl"
    target.write_text("", encoding="utf-8")
    common.append_ledger(target, {"prereg_id": "X", "verdict": "inconclusive", "v": 0.1})
    common.append_ledger(target, {"prereg_id": "Y", "verdict": "inconclusive"})
    rows = prereg.read_ledger(target)
    assert [r["seq"] for r in rows] == [1, 2]
    assert json.loads(target.read_text(encoding="utf-8").splitlines()[0])["v"] == 0.1
