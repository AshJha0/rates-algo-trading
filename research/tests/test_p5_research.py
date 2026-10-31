"""Tests of the P5 research modules: stats, presets, pre-registration, ledger, hit rate, flow,
markouts, spread and back-test analysis. Fixtures live under data/fixtures/ (small, synthetic or
produced by the Java runner)."""

from __future__ import annotations

import json
import math
import shutil
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import yaml
from hypothesis import given, settings
from hypothesis import strategies as st

from ratesresearch import (
    assumptions,
    backtest_analysis,
    flow,
    hitrate,
    markouts,
    prereg,
    presets,
    spread,
    stats,
)
from ratesresearch.data import dtcc, finra

ROOT = Path(__file__).resolve().parents[2]
PRESETS = ROOT / "data" / "presets.yaml"
FIXTURES = ROOT / "data" / "fixtures"
FITTING = FIXTURES / "backtest" / "fitting"


# ---------------------------------------------------------------- stats


def test_stats_match_the_shared_fixture() -> None:
    expected = json.loads(
        (FIXTURES / "backtest" / "stats_crosscheck.json").read_text(encoding="utf-8")
    )
    assert expected["expected"] == backtest_analysis.crosscheck_values()
    assert expected["input"] == backtest_analysis.crosscheck_input()


def test_welch_matches_scipy() -> None:
    scipy_stats = pytest.importorskip("scipy.stats")
    a, b = backtest_analysis.CC_A, backtest_analysis.CC_B
    ref = scipy_stats.ttest_ind(a, b, equal_var=False)
    w = stats.welch(a, b)
    assert w.t == pytest.approx(float(ref.statistic), rel=1e-12)
    assert w.p == pytest.approx(float(ref.pvalue), rel=1e-10)


@settings(max_examples=50, deadline=None)
@given(st.floats(min_value=0.1, max_value=50), st.floats(min_value=0.5, max_value=200))
def test_student_p_matches_scipy(t: float, df: float) -> None:
    scipy_stats = pytest.importorskip("scipy.stats")
    assert stats.student_two_sided_p(t, df) == pytest.approx(
        2 * float(scipy_stats.t.sf(t, df)), rel=1e-9, abs=1e-15
    )


def test_holm_and_bootstrap_edges() -> None:
    assert stats.holm([0.01, 0.02, 0.03]) == pytest.approx([0.03, 0.04, 0.04])
    ci = stats.block_bootstrap_mean_ci([1.0, 2.0, 3.0, 4.0], 2, 200, 0.9, 1)
    assert ci.lower <= ci.estimate <= ci.upper
    with pytest.raises(ValueError):
        stats.block_bootstrap_mean_ci([1.0], 2, 10, 0.9, 1)
    with pytest.raises(ValueError):
        stats.variance([1.0])
    with pytest.raises(ValueError):
        stats.SplitMix64(1).next_int(0)
    assert stats.quantile([1.0, 2.0], 0.5) == 1.5
    assert stats.log_gamma(5.0) == pytest.approx(math.log(24.0), rel=1e-13)


# ---------------------------------------------------------------- presets


def test_presets_file_follows_the_schema() -> None:
    names = {p["name"] for p in presets.load_presets(PRESETS)}
    assert {
        "usd_rfq_hit_rate",
        "usd_rfq_competition",
        "usd_rfq_flow",
        "usd_synthetic_market",
        "usd_hedge_cost",
    } <= names
    for p in presets.load_presets(PRESETS):
        assert presets.validate_preset(p) == [], p["name"]
    hit = presets.get_preset(PRESETS, "usd_rfq_hit_rate")
    assert set(hit["assumptions"]["spread_to_cover_bp"]["band"]) == {"low", "central", "high"}


def test_write_preset_keeps_header_and_replaces_by_name(tmp_path: Path) -> None:
    target = tmp_path / "presets.yaml"
    shutil.copy(PRESETS, target)
    header = target.read_text(encoding="utf-8").split("presets:")[0]
    p = {
        "name": "zz_test",
        "fitted_from": "assumption",
        "data_range": {"start": "2026-01-01", "end": "2026-01-01"},
        "sample_size": 0,
        "method": "test",
        "parameters": {"a": presets.param(1.0)},
    }
    presets.write_preset(target, p)
    p["parameters"] = {"a": presets.param(2.0, 0.1)}
    presets.write_preset(target, p)
    assert target.read_text(encoding="utf-8").startswith(header)
    # other top-level sections (the P4 router block) survive a preset write unchanged
    assert yaml.safe_load(target.read_text(encoding="utf-8"))["router"] == yaml.safe_load(
        PRESETS.read_text(encoding="utf-8")
    )["router"]
    assert presets.value(presets.get_preset(target, "zz_test"), "a") == 2.0
    assert len([x for x in presets.load_presets(target) if x["name"] == "zz_test"]) == 1
    with pytest.raises(presets.PresetError):
        presets.write_preset(target, {"name": "bad"})
    with pytest.raises(KeyError):
        presets.get_preset(target, "missing")
    bad = dict(p, assumptions={"x": {"band": {"low": 1, "central": 0, "high": 2}}})
    assert presets.validate_preset(bad)


def test_declared_presets_are_reproducible(tmp_path: Path) -> None:
    target = tmp_path / "presets.yaml"
    shutil.copy(PRESETS, target)
    assumptions.write_all(target)
    for name in ("usd_synthetic_market", "usd_rfq_competition", "usd_hedge_cost"):
        assert presets.get_preset(target, name) == presets.get_preset(PRESETS, name)


# ---------------------------------------------------------------- pre-registration and ledger


def test_committed_prereg_is_valid_and_ledger_chain_holds() -> None:
    assert prereg.validate(ROOT / "data/preregs/P5-001.yaml", ROOT) == []
    entries = prereg.read_ledger(ROOT / "data/ledger/ledger.jsonl")
    first = next(e for e in entries if e["prereg_id"] == "P5-001")
    assert first["verdict"] in ("supported", "not supported")
    assert set(first["results"]["band"]) == {"low", "central", "high"}


def test_prereg_refusals(tmp_path: Path) -> None:
    text = (ROOT / "data/preregs/P5-001.yaml").read_text(encoding="utf-8")
    (tmp_path / "data/experiments").mkdir(parents=True)
    shutil.copy(
        ROOT / "data/experiments/P5-001.config.yaml",
        tmp_path / "data/experiments/P5-001.config.yaml",
    )
    bad = tmp_path / "p.yaml"
    bad.write_text(
        text.replace("holdout: [2026-02-23", "holdout: [2026-01-07, 2026-02-23"), encoding="utf-8"
    )
    assert any("overlaps the fitting period" in p for p in prereg.validate(bad, tmp_path))
    bad.write_text(
        text.replace("points: [low, central, high]", "points: [central]"), encoding="utf-8"
    )
    assert any("DR-008" in p for p in prereg.validate(bad, tmp_path))
    bad.write_text(text.replace("config_sha256: 9", "config_sha256: 8"), encoding="utf-8")
    assert any("config_sha256" in p for p in prereg.validate(bad, tmp_path))
    assert prereg.validate(tmp_path / "missing.yaml", tmp_path) == [
        f"pre-registration not found: {tmp_path / 'missing.yaml'}"
    ]
    assert (
        len(
            prereg.business_days(
                pd.Timestamp("2026-02-02").date(), pd.Timestamp("2026-02-13").date()
            )
        )
        == 10
    )


def test_ledger_tamper_is_detected(tmp_path: Path) -> None:
    first = (ROOT / "data/ledger/ledger.jsonl").read_text(encoding="utf-8").splitlines()[0]
    second = json.dumps({"prev_sha256": prereg.sha256_text(first), "seq": 2}, separators=(",", ":"))
    target = tmp_path / "ledger.jsonl"
    target.write_text(first + "\n" + second + "\n", encoding="utf-8")
    assert len(prereg.read_ledger(target)) == 2
    target.write_text(
        first.replace('"verdict":"', '"verdict":"x', 1) + "\n" + second + "\n", encoding="utf-8"
    )
    with pytest.raises(ValueError):
        prereg.read_ledger(target)


def test_experiment_statistics_recompute_from_ledger() -> None:
    entries = prereg.read_ledger(ROOT / "data/ledger/ledger.jsonl")
    record = next(e for e in entries if e["prereg_id"] == "P5-001")
    recomputed = backtest_analysis.recompute_experiment(record)
    for point, band in record["results"]["band"].items():
        for name, c in band["comparisons"].items():
            r = recomputed[f"{point}:{name}"]
            assert r["p"] == pytest.approx(c["welch_p"], abs=1e-8)
            assert r["holm_p"] == pytest.approx(c["holm_p"], abs=1e-8)
            assert round(r["ci_low"], 2) == pytest.approx(c["paired_ci_low"], abs=0.006)
            assert round(r["ci_high"], 2) == pytest.approx(c["paired_ci_high"], abs=0.006)
    assert len(backtest_analysis.experiment_table(record)) == 3


# ---------------------------------------------------------------- hit rate


def test_hitrate_fit_beats_constant_and_recovers_slope() -> None:
    comp = presets.get_preset(PRESETS, "usd_rfq_competition")
    x, y = hitrate.simulate_labels(comp, 20_000, seed=7)
    f = hitrate.fit(x, y)
    assert f.log_loss_model < f.log_loss_constant
    assert f.beta[1] == pytest.approx(-2.5, abs=4 * f.se[1])
    rows = hitrate.calibration_table(x[f.n_fit :], y[f.n_fit :], f.beta)
    assert all(abs(p - o) < 0.06 for p, o, _ in rows)
    svg = hitrate.calibration_svg(rows)
    assert svg.startswith("<svg") and "polyline" in svg
    preset = hitrate.preset_from_fit(f, comp, 20_000)
    assert presets.validate_preset(preset) == []


def test_committed_hit_rate_preset_reports_oos_improvement() -> None:
    hit = presets.get_preset(PRESETS, "usd_rfq_hit_rate")
    assert presets.value(hit, "oos_log_loss_model") < presets.value(hit, "oos_log_loss_constant")


# ---------------------------------------------------------------- flow and loaders


def test_dtcc_loader_filters_the_documented_layout() -> None:
    df = dtcc.load_sofr_ois(FIXTURES / "dtcc" / "rates_layout_fixture.csv")
    assert len(df) == 4  # package, non-SOFR, forward-start and odd-tenor rows are dropped
    assert df["capped"].sum() == 1
    assert set(df["tenor_bucket"]) <= set(dtcc.STANDARD_TENORS)
    assert dtcc.url_for(pd.Timestamp("2025-10-01").date()).endswith(
        "CFTC_CUMULATIVE_RATES_2025_10_01.zip"
    )
    assert "Package indicator" in dtcc.fixture_layout_columns()


def test_finra_loader_and_fit_on_the_synthetic_fixture(tmp_path: Path) -> None:
    path = FIXTURES / "finra" / "treasury_trades_fixture.csv"
    df = finra.load_treasury_trades(path)
    assert len(df) == 400 and df["capped"].any()
    regenerated = tmp_path / "f.csv"
    finra.write_synthetic_fixture(regenerated)
    assert regenerated.read_text(encoding="utf-8") == path.read_text(encoding="utf-8")
    p = flow.fit_finra_fixture(df, "data/fixtures/finra/treasury_trades_fixture.csv")
    assert presets.validate_preset(p) == []
    assert "SYNTHETIC" in p["notes"]
    bad = tmp_path / "bad.csv"
    bad.write_text("a,b\n1,2\n", encoding="utf-8")
    with pytest.raises(ValueError):
        finra.load_treasury_trades(bad)


def test_intensity_fit_recovers_a_known_bump() -> None:
    rng = np.random.default_rng(3)
    centres = np.arange(422.5, 1020, 5.0)
    exposure = np.full(centres.shape, 10 * 5.0)
    true = np.exp(-0.5 + 0.9 * np.exp(-0.5 * ((centres - 660) / 15.0) ** 2))
    counts = rng.poisson(true * exposure).astype(float)
    fit = flow.fit_intensity(centres, counts, exposure, events=(660.0,), widths=(15.0,))
    assert fit.b0 == pytest.approx(-0.5, abs=4 * fit.se_b0)
    assert fit.amplitudes[0] == pytest.approx(0.9, abs=4 * fit.se_amplitudes[0])


def test_censored_lognormal_recovers_parameters() -> None:
    rng = np.random.default_rng(5)
    x = np.exp(rng.normal(3.0, 1.5, size=4000))
    cap = 250.0
    capped = x >= cap
    x = np.where(capped, cap, x)
    mu, sigma, se_mu, se_sigma = flow.fit_censored_lognormal(x, capped)
    assert mu == pytest.approx(3.0, abs=4 * se_mu)
    assert sigma == pytest.approx(1.5, abs=4 * se_sigma)


def test_committed_flow_preset_has_the_fixing_bump() -> None:
    p = presets.get_preset(PRESETS, "usd_rfq_flow")
    assert p["fitted_from"] == "data/manifests/dtcc_rates_2025-10.json"
    assert (ROOT / p["fitted_from"]).is_file()
    assert presets.value(p, "event_2_log_amplitude") > 0
    rows = flow.profile_table(p)
    assert rows[3][1] > rows[0][1]  # 11:00 fixing busier than the open


# ---------------------------------------------------------------- markouts, spread, analysis


def _fitting_runs() -> list[backtest_analysis.Run]:
    return [backtest_analysis.load_run(d) for d in sorted(FITTING.iterdir())]


def test_java_outputs_reconcile() -> None:
    for run in _fitting_runs():
        assert backtest_analysis.reconcile(run) == []
        assert "Hit rate by client tier" in backtest_analysis.daily_tables(run)
        assert len(backtest_analysis.hit_rate_by_tier(run)) == 3


def test_reconcile_detects_a_broken_line() -> None:
    run = _fitting_runs()[0]
    broken = run.pnl.copy()
    broken.loc[0, "spread_capture"] += 1.0
    assert backtest_analysis.reconcile(
        backtest_analysis.Run(run.fills, run.rfqs, broken, run.summary)
    )


def test_markouts_rank_tiers_and_estimate_informed_share() -> None:
    runs = _fitting_runs()
    fills = pd.concat([r.fills for r in runs], ignore_index=True)
    rfqs = pd.concat([r.rfqs for r in runs], ignore_index=True)
    truth = pd.concat(
        [pd.read_csv(d / "truth.csv") for d in sorted(FITTING.iterdir())], ignore_index=True
    )
    adverse = markouts.adverse_bp_by_tier(fills)
    assert adverse[3] > adverse[1]
    est = markouts.informed_share(fills, "1min", by="tier").set_index("group")
    cf = fills[fills["kind"] == "client"].merge(truth, left_on="ref_id", right_on="rfq_id")
    actual = cf.groupby("tier")["informed"].mean()
    for tier in (1, 2, 3):
        assert abs(est.loc[tier, "informed_share"] - actual[tier]) < 3 * est.loc[tier, "se"] + 0.05
    tox = markouts.toxicity_table(fills, rfqs)
    assert set(tox["suggested_tier"]) <= {1, 2, 3}
    s = markouts.summary(fills, rfqs)
    assert {"by_tier", "by_size", "by_product", "informed_by_tier", "toxicity"} == set(s)


def test_spread_tables_agree_with_the_java_strategy_rule() -> None:
    hit = presets.get_preset(PRESETS, "usd_rfq_hit_rate")
    comp = presets.get_preset(PRESETS, "usd_rfq_competition")
    rows = spread.band_table(hit, comp, adverse_bp=0.05, hedge_bp=0.25)
    assert len(rows) == 6
    for r in rows:
        assert r["s_low"] >= r["s_high"]  # flatter slope, wider optimal spread
    m = spread.Model.from_presets(hit, comp)
    s_toxic, _ = spread.optimal_half_spread(m, "SOFR 10Y", 50, 4, 0.4, 0.1)
    s_clean, _ = spread.optimal_half_spread(m, "SOFR 10Y", 50, 4, 0.0, 0.1)
    assert s_toxic > s_clean
    sens = spread.sensitivity(hit, comp, "SOFR 10Y", 0.05, 0.25)
    assert sens[-1]["dealers_4"] >= sens[0]["dealers_4"]
