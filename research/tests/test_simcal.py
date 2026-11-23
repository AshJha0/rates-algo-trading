"""Known-answer tests of the simulator calibration (research/ratesresearch/simcal).

Each estimator is run on data simulated from a model with known parameters and must recover
them within a stated tolerance. No test reads DTCC data.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import yaml

from ratesresearch import simcal
from ratesresearch.simcal import calibrate, competitor, factors, micro, params, prints, synth

ROOT = Path(__file__).resolve().parents[2]
PARAMS = ROOT / "data/calibration/sim_v2_params.json"


def _stats(p: pd.DataFrame) -> micro.Stats:
    return micro.day_stats(micro.make_sample(p))


@pytest.fixture(scope="module")
def drift_stats() -> micro.Stats:
    return _stats(synth.prints(80, 7, burst_s=4.0, drift_tau_s=40.0, per_minute=0.4, drift_bp=0.10))


@pytest.fixture(scope="module")
def no_drift_stats() -> micro.Stats:
    return _stats(synth.prints(80, 7, burst_s=4.0, drift_tau_s=40.0, per_minute=0.4, drift_bp=0.0))


TEN = simcal.DESK_TENORS.index(10.0)

# ------------------------------------------------------------------ pre-analysis note


def test_split_dates_match_the_pre_analysis_note() -> None:
    note = yaml.safe_load((ROOT / "research/prereg/SIM3-CAL-001.yaml").read_text(encoding="utf-8"))
    split = note["split"]
    assert str(split["dtcc_calibration"]["start"]) == simcal.CAL_START
    assert str(split["dtcc_calibration"]["end"]) == simcal.CAL_END
    assert str(split["dtcc_validation"]["start"]) == simcal.VAL_START
    assert str(split["dtcc_validation"]["end"]) == simcal.VAL_END
    assert simcal.CAL_END < simcal.VAL_START


# ------------------------------------------------------------------ factor model


def test_pca_recovers_a_simulated_factor_model() -> None:
    rng = np.random.Generator(np.random.PCG64(1))
    q, _ = np.linalg.qr(rng.standard_normal((7, 3)))
    sd = np.array([12.0, 4.0, 1.5])
    x = synth.factor_changes(q, sd, 0.3, 4000, seed=2)
    fit = factors.pca(x)
    assert np.all(factors.cosines(fit.loadings[:, :3], q) > 0.99)
    # the idiosyncratic noise adds 0.3^2 to each eigenvalue
    assert np.allclose(fit.sd[:3], np.sqrt(sd**2 + 0.09), rtol=0.05)
    assert np.allclose(factors.score_sd(x, q), np.sqrt(sd**2 + 0.09), rtol=0.05)
    assert factors.explained_by(x, q) > 0.99
    rms = math.sqrt(float((factors.residual_sd(x, q) ** 2).mean()))
    assert rms == pytest.approx(0.3 * math.sqrt(4 / 7), rel=0.05)
    lo, hi = factors.bootstrap_rows(x, lambda y: factors.score_sd(y, q), resamples=200)
    truth = np.sqrt(sd**2 + 0.09)
    assert np.all(lo < truth * 1.03) and np.all(hi > truth * 0.97)


def test_pca_sign_convention() -> None:
    par = factors.load_par_curve(ROOT / "data/fixtures/edge/par_curve.csv")
    ch = factors.daily_changes(par, simcal.CURVE_CAL_START, simcal.CAL_END).to_numpy()
    fit = factors.pca(ch)
    assert np.all(fit.loadings[:, 0] > 0)  # level
    assert fit.loadings[0, 1] < 0 < fit.loadings[-1, 1]  # slope: short end down, long end up
    assert fit.explained[:3].sum() > 0.97


def test_daily_changes_drop_long_gaps_and_respect_the_window() -> None:
    lv = pd.DataFrame(
        {2.0: [1.0, 2.0, 4.0, 7.0]}, index=["2025-01-02", "2025-01-03", "2025-01-13", "2025-01-14"]
    )
    d = factors.daily_changes(lv, "2025-01-01", "2025-01-13")
    assert list(d.index) == ["2025-01-03"]
    assert d.iloc[0, 0] == 1.0


def test_semi_bond_conversion() -> None:
    out = factors.to_semi_bond(pd.DataFrame({10.0: [400.0]}))
    r365 = 0.04 * 365 / 360
    assert out.iloc[0, 0] == pytest.approx(2 * (math.sqrt(1 + r365) - 1) * 1e4)


def test_spread_fit_separates_noise_from_signal() -> None:
    rng = np.random.Generator(np.random.PCG64(5))
    n = 1500
    days = pd.bdate_range("2019-01-01", periods=n).strftime("%Y-%m-%d")
    q, _ = np.linalg.qr(rng.standard_normal((7, 3)))
    par = pd.DataFrame(
        300.0 + np.cumsum(synth.factor_changes(q, np.array([6.0, 2.0, 1.0]), 0.2, n, 3), axis=0),
        index=days,
        columns=list(simcal.DESK_TENORS),
    )
    common = np.cumsum(rng.standard_normal(n) * 0.8)
    spread = -30.0 + common[:, None] + rng.standard_normal((n, 7)) * 0.5  # noise sd 0.5 per level
    semi = par + spread
    # invert the bond-basis conversion so that the function's own conversion returns ``semi``
    annual = (((1 + semi * 1e-4 / 2) ** 2 - 1) * 360 / 365) * 1e4
    fit = factors.spread_fit(annual, par, q, days[0], days[-1])
    assert np.allclose(fit.noise_sd, 0.5, atol=0.08)
    assert np.allclose(fit.signal_sd, 0.8, atol=0.15)
    assert np.allclose(fit.change_sd, math.sqrt(0.64 + 0.5), atol=0.08)
    assert np.all(np.abs(fit.beta) < 0.05) and np.all(fit.r2 < 0.03)
    assert fit.joint_n > 1000 and np.all(fit.joint_loading > 0.4)


# ------------------------------------------------------------------ print parsing


def _row(**kw: str) -> dict[str, str]:
    base = dict.fromkeys(prints.COLUMNS, "")
    base.update(
        {
            "Action type": "NEWT",
            "Event type": "TRAD",
            "Execution Timestamp": "2025-03-10T15:00:00Z",
            "Effective Date": "2025-03-12",
            "Expiration Date": "2035-03-12",
            "Notional amount-Leg 1": "10,000,000",
            "Notional currency-Leg 1": "USD",
            "Package indicator": "false",
            "UPI FISN": "NA/Swap OIS USD",
            "UPI Underlier Name": "USD-SOFR-OIS Compound",
            "Fixed rate-Leg 1": "0.04",
            "Platform identifier": "TWSF",
            "Block trade election indicator": "false",
        }
    )
    base.update(kw)
    return base


def test_parse_file_counts_every_filter(tmp_path: Path) -> None:
    rows = [
        _row(),
        _row(
            **{
                "Execution Timestamp": "2025-03-10T15:00:05Z",
                "Fixed rate-Leg 1": "0.04002",
                "Platform identifier": "BILT",
            }
        ),
        _row(
            **{
                "Execution Timestamp": "2025-03-10T15:00:05Z",
                "Fixed rate-Leg 1": "0.04002",
                "Notional amount-Leg 1": "170,000,000+",
            }
        ),
        _row(**{"Action type": "CORR"}),
        _row(**{"UPI Underlier Name": "USD-LIBOR"}),
        _row(**{"Package indicator": "true"}),
        _row(**{"Fixed rate-Leg 1": ""}),
        _row(**{"Other payment amount": "1000.5"}),
        _row(**{"Non-standardized term indicator": "TRUE"}),
        _row(**{"Effective Date": "2025-06-18"}),
        _row(**{"Expiration Date": "2037-03-12"}),
        _row(**{"Execution Timestamp": "2025-03-10T02:00:00Z"}),
        _row(**{"Fixed rate-Leg 1": "0.0500"}),
    ]
    path = tmp_path / "CFTC_CUMULATIVE_RATES_2025_03_10.csv"
    pd.DataFrame(rows).to_csv(path, index=False)
    out, counts = prints.parse_file(path, "2025-03-10")
    expected = [13, 12, 11, 10, 9, 8, 7, 6, 5, 5, 4, 3, 2]
    assert [counts[k] for k in prints.FILTER_ORDER] == expected
    assert prints.day_of(path) == "2025-03-10"
    first, merged = out.iloc[0], out.iloc[1]
    assert first["t"] == 11 * 3600 and first["rate_bp"] == pytest.approx(
        400.0
    )  # 15:00 UTC = 11:00 EDT
    assert first["group"] == 0 and first["lag_days"] == 2 and abs(first["tenor_dev"]) < 0.01
    assert first["dv01"] == pytest.approx(10e6 * (1 - 1.04**-10) / 0.04 * 1e-4)
    assert (
        merged["notional_mm"] == pytest.approx(180.0)
        and bool(merged["capped"])
        and merged["bucket"] == 3
    )
    assert merged["group"] == 0  # one piece on a D2C facility
    assert len(calibrate.strict(out)) == 2 and len(calibrate.d2c(out)) == 2


def test_centred_median_deviation_flags_one_outlier() -> None:
    r = np.array([400.0, 400.1, 399.9, 407.0, 400.0, 400.2, 399.8])
    dev = prints.centred_median_deviation(r)
    assert dev[3] == pytest.approx(7.0, abs=0.11)
    assert np.all(np.abs(np.delete(dev, 3)) < 0.3)


# ------------------------------------------------------------------ noise, volatility, drift


def test_least_squares_signature_recovers_half_spread_and_volatility(
    no_drift_stats: micro.Stats,
) -> None:
    point, lo, hi = micro.interval(no_drift_stats, lambda t: micro.est_basic(t)[TEN], 200, 1)
    assert point[0] == pytest.approx(0.25, abs=0.015)  # half-spread, bp
    assert point[1] == pytest.approx(0.20, abs=0.015)  # bp per sqrt-minute
    assert lo[0] < 0.25 < hi[0] and lo[1] < 0.20 < hi[1]


def test_robust_signature_recovers_dispersed_half_spread_with_outliers() -> None:
    p = synth.prints(60, 3, burst_s=8.0, half_spread_log_sd=0.5, drift_bp=0.0)
    rms = 0.25 * math.exp(0.25)  # sqrt(E[x^2]) of a lognormal with median 0.25 and log-sd 0.5
    clean = micro.est_robust(micro.total_of(_stats(p)))
    assert clean["sigma"][TEN] == pytest.approx(0.20, abs=0.03)
    assert clean["eta_long"][TEN] == pytest.approx(rms, rel=0.2)
    assert clean["eta_short"][TEN] == pytest.approx(rms, rel=0.2)
    # 5 percent of prints moved by +-3 bp: least squares is wrecked, the median version is not
    rng = np.random.Generator(np.random.PCG64(9))
    dirty = p.copy()
    hit = rng.uniform(size=len(p)) < 0.05
    dirty.loc[hit, "rate_bp"] += rng.choice(np.array([-3.0, 3.0]), int(hit.sum()))
    total = micro.total_of(_stats(dirty))
    assert micro.est_basic(total)[TEN, 0] > 2 * rms
    rob = micro.est_robust(total)
    assert rob["eta_long"][TEN] == pytest.approx(rms, rel=0.3)
    assert rob["sigma"][TEN] == pytest.approx(0.20, abs=0.04)


def test_direction_free_signature_recovers_the_adverse_drift(
    drift_stats: micro.Stats, no_drift_stats: micro.Stats
) -> None:
    def est(t: micro.Stats) -> np.ndarray:
        s = micro.est_signature(t)
        return np.array([s["h"][TEN], s["drift"][TEN]])

    point, lo, hi = micro.interval(drift_stats, est, 200, 1)
    assert point[0] == pytest.approx(0.25, abs=0.01)
    # true drift by 30 to 120 s with a 40 s time constant is 0.10 (1 - exp(-75 / 40)) = 0.085;
    # the estimator is biased low because part of the drift arrives inside the first 10 s
    assert lo[1] < 0.085 < hi[1]
    assert 0.04 < point[1] < 0.11
    point0, lo0, hi0 = micro.interval(no_drift_stats, est, 200, 1)
    assert lo0[1] < 0.0 < hi0[1] and abs(point0[1]) < 0.03


def test_signed_markout_is_attenuated_towards_zero(
    drift_stats: micro.Stats, no_drift_stats: micro.Stats
) -> None:
    def est(t: micro.Stats) -> np.ndarray:
        raw = micro.est_markout(t)["raw"][-1]
        return np.array([raw[-1], raw[-1] - raw[0]])

    _, lo, hi = micro.interval(drift_stats, est, 200, 1)
    # truth without classification error: drift - half-spread = 0.10 - 0.25 = -0.15 at 5 min
    assert -0.15 < lo[0] and hi[0] < 0.0
    # the build-up between the first horizon and 5 min is positive but smaller than the true 0.10
    assert 0.0 < lo[1] and hi[1] < 0.10
    point0, lo0, hi0 = micro.interval(no_drift_stats, est, 200, 1)
    assert lo0[1] < 0.0 < hi0[1]
    assert -0.25 < point0[0] < 0.0


def test_classification_error_rate_is_estimated() -> None:
    p = synth.prints(60, 11, drift_bp=0.0)
    s = micro.make_sample(p)
    s_mid, _, s_tick = micro.classify(s)
    side = p.sort_values(["day", "tenor", "t", "rate_bp"], kind="stable")["side"].to_numpy()
    known = s_mid != 0
    true_eps = float(np.mean(s_mid[known] != side[known]))
    est = micro.est_markout(micro.total_of(micro.day_stats(s)))["eps"][-1]
    assert 0.15 < true_eps < 0.35
    assert est == pytest.approx(true_eps, abs=0.04)
    assert float(np.mean(s_tick[s_tick != 0] != side[s_tick != 0])) < 0.4


def test_class_regression_recovers_noise_by_group() -> None:
    a = synth.prints(60, 21, half_spread_bp=0.20, drift_bp=0.0, per_minute=1.0)
    b = synth.prints(60, 22, half_spread_bp=0.50, drift_bp=0.0, per_minute=1.0)
    b["group"] = 1
    # the two groups trade different tenors; the pooled row fits one noise level per group
    b["tenor"] = 5.0
    out = micro.est_class(micro.total_of(_stats(pd.concat([a, b], ignore_index=True))), "group")
    assert out[-1, 0] == pytest.approx(0.20, abs=0.03)
    assert out[-1, 1] == pytest.approx(0.50, abs=0.03)


def test_event_window_variance_is_recovered() -> None:
    p = synth.prints(120, 31, drift_bp=0.0, half_spread_bp=0.05, per_minute=1.0)
    # add a jump of sd 2 bp at 08:30 on every second day: 4 bp^2 over 5 minutes = 20 x 0.04
    rng = np.random.Generator(np.random.PCG64(4))
    days = sorted(p["day"].unique())
    flagged = set(days[::2])
    for d in flagged:
        m = (p["day"] == d) & (p["t"] >= 8.5 * 3600 + 300)
        p.loc[m, "rate_bp"] += rng.standard_normal() * 2.0
    s = micro.make_sample(p)
    ev = np.array([[d in flagged, 0.0, 0.0] for d in s.days], dtype=np.float64)
    total = micro.total_of(micro.day_stats(s, ev))
    season = micro.est_season(total, micro.est_basic(total)[:-1, 1] ** 2)
    assert season[: micro.N_BINS].mean() == pytest.approx(1.0)
    assert 8.0 < season[micro.N_BINS] < 40.0  # true excess: 4 / 5 / 0.04 = 20 per minute
    assert abs(season[micro.N_BINS + 1]) < 1e-9


def test_arrivals_and_bootstrap_are_deterministic(no_drift_stats: micro.Stats) -> None:
    total = micro.total_of(no_drift_stats)
    per_day = micro.est_arrivals(total)[TEN].sum()
    assert per_day == pytest.approx(2 * 0.4 * 600, rel=0.05)  # each print has a burst twin
    a = micro.interval(no_drift_stats, lambda t: micro.est_basic(t)[TEN], 50, 3)
    b = micro.interval(no_drift_stats, lambda t: micro.est_basic(t)[TEN], 50, 3)
    assert np.array_equal(a[1], b[1]) and np.array_equal(a[2], b[2])
    w = micro.day_weights(80, 10, 1)
    assert np.all(w.sum(axis=1) == 80)


def test_hist_median_and_daily_close() -> None:
    h = np.zeros(len(micro.ABS_EDGES) - 1)
    k = int(np.searchsorted(micro.ABS_EDGES, 0.2))
    h[k] = 10.0
    assert micro.ABS_EDGES[k] <= micro.hist_median(h) <= micro.ABS_EDGES[k + 1]
    assert math.isnan(micro.hist_median(np.zeros_like(h)))
    p = synth.prints(3, 1, per_minute=0.5)
    close = micro.daily_close(p)
    assert close.shape == (3, 7) and close[10.0].notna().all() and close[2.0].isna().all()


# ------------------------------------------------------------------ competitor model


def test_min_moment_against_closed_form_and_simulation() -> None:
    assert competitor.min_moment(0.5, 1) == pytest.approx(math.exp(0.125), rel=1e-6)
    rng = np.random.Generator(np.random.PCG64(8))
    sim = np.exp(0.5 * rng.standard_normal((400_000, 3))).min(axis=1)
    assert competitor.min_moment(0.5, 3) == pytest.approx(float(sim.mean()), rel=0.005)
    assert competitor.min_moment(0.5, 3, 2) == pytest.approx(float((sim**2).mean()), rel=0.01)


def test_zero_profit_median_makes_the_winner_break_even() -> None:
    w = competitor.dealer_count_weights(3, 5, 0.9)
    assert sum(w.values()) == pytest.approx(1.0)
    assert w[0] == pytest.approx((0.1**2 + 0.1**3 + 0.1**4) / 3)
    m = competitor.zero_profit_median(0.06, 0.08, 0.5, w)
    assert competitor.winner_profit(m, 0.06, 0.08, 0.5, w) == pytest.approx(0.0, abs=1e-12)
    # the constants being replaced: 0.25 bp median, 20 percent informed x 4 x 0.25 bp, 0.086 hedge
    assert competitor.winner_profit(0.25, 0.2 * 4 * 0.25, 0.086, 0.5, w) < -0.05


# ------------------------------------------------------------------ committed parameter file


def _entries(node: object, path: str = "") -> list[tuple[str, dict[str, object]]]:
    out: list[tuple[str, dict[str, object]]] = []
    if isinstance(node, dict):
        if "value" in node and "unit" in node:
            return [(path, node)]
        for k, v in node.items():
            out += _entries(v, f"{path}.{k}" if path else k)
    return out


def test_parameter_file_is_complete_and_sourced() -> None:
    p = json.loads(PARAMS.read_text(encoding="utf-8"))
    entries = _entries({k: v for k, v in p.items() if k != "old_constants_check"})
    assert len(entries) >= 35
    for path, e in entries:
        assert {"value", "unit", "interval", "window", "fitted_from", "status"} <= set(e), path
        assert e["status"] in {"fitted", "bounded", "assumption"}, path
        assert isinstance(e["unit"], str) and e["unit"], path
        if e["status"] == "assumption":
            assert e["fitted_from"] == "assumption" and e["interval"] is not None, path
        else:
            for name in str(e["fitted_from"]).split(", "):
                assert (ROOT / name).is_file(), f"{path}: {name}"
    loadings = np.array([p["curve_factors"]["loadings"]["value"][k] for k in calibrate.TENOR_KEYS])
    assert np.allclose((loadings**2).sum(axis=0), 1.0, atol=1e-3)
    assert sum(p["rfq"]["tenor_mix"]["value"].values()) == pytest.approx(1.0, abs=1e-3)
    assert sum(p["rfq"]["time_of_day_share"]["value"]) == pytest.approx(1.0, abs=1e-3)
    assert "sqrt-minute" in p["curve_factors"]["sd_per_sqrt_minute"]["unit"]


def test_parameter_file_satisfies_zero_profit() -> None:
    p = json.loads(PARAMS.read_text(encoding="utf-8"))
    c, f = p["competitors"], p["informed_flow"]
    w = competitor.dealer_count_weights(3, 5, c["response_probability"]["value"])
    profit = competitor.winner_profit(
        c["median_half_spread"]["value"],
        f["expected_adverse_move"]["value"],
        c["all_in_cost"]["value"],
        c["log_sd"]["value"],
        w,
    )
    assert abs(profit) < 2e-4
    assert f["move_per_informed_trade"]["value"] * f["informed_fraction"]["value"] == pytest.approx(
        f["expected_adverse_move"]["value"], abs=1e-3
    )
    lo, hi = c["winner_half_spread_rms"]["interval"]
    assert lo < c["winner_half_spread_rms"]["value"] < hi
    assert "zero_profit_median_bp" in params.competitor_table(p)


def test_manifest_matches_the_first_download() -> None:
    m = json.loads((ROOT / "data/manifests/sim_v2_dtcc.json").read_text(encoding="utf-8"))
    first = json.loads((ROOT / "data/manifests/edge_dtcc.json").read_text(encoding="utf-8"))
    known = {f["name"]: f["sha256"] for f in first["files"]}
    assert len(m["files"]) == 419 and len(m["missing"]) == 17
    assert all(known[f["name"]] == f["sha256"] for f in m["files"])


def test_compare_applies_the_pass_marks() -> None:
    def tri(v: float) -> dict[str, float]:
        return {"value": v, "lo": v - 0.01, "hi": v + 0.01}

    def result(scale: float, markout: float) -> dict[str, object]:
        micro_part = {
            "robust_sigma": [tri(0.1 * scale)] * 8,
            "robust_eta_long": [tri(0.2 * scale)] * 8,
            "robust_eta_short": [tri(0.1 * scale)] * 8,
            "prints_per_day": [tri(30.0 * scale)] * 7,
            "markout_raw": [[tri(markout)] * 5] * 5,
        }
        return {
            "curve": {
                "factor_sd_bp_per_day": [tri(10.0 * scale)] * 3,
                "cosine_with_given_loadings": [0.99, 0.95, 0.91],
                "explained_by_loadings": 0.98,
                "explained_by_own_three": 0.99,
            },
            "strict": micro_part,
            "strict_d2c": micro_part,
        }

    same = calibrate.compare(result(1.0, -0.05), result(1.2, -0.055))
    assert same["scale_passed"] == same["scale_judged"] == 34 and same["loading_pass"]
    assert all(m["pass"] for m in same["markout_5min"])
    off = calibrate.compare(result(1.0, -0.05), result(1.6, 0.05))
    assert off["scale_passed"] == 0 and not any(m["pass"] for m in off["markout_5min"])
