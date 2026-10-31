"""Tests for ratesresearch.curves: PCA, hedge ratios and the fixture summary.

Every numerical comparison names its tolerance. Synthetic data uses a fixed
numpy Generator seed so the planted structure is reproducible.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from ratesresearch.curves import (
    DEFAULT_TENORS,
    PcaResult,
    daily_changes,
    load_par_curve,
    pca,
    pca_hedge_ratio,
    regression_hedge_ratio,
    rolling_hedge_ratios,
    summarise,
    write_summary,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE = REPO_ROOT / "data" / "fixtures" / "ust_par_curve_2015_2025.csv"
SUMMARY = REPO_ROOT / "data" / "fixtures" / "ust_pca_summary.json"

# Planted three-factor structure: level, slope, curvature over seven tenors.
_LEVEL = np.ones(7) / np.sqrt(7.0)
_SLOPE_RAW = np.linspace(-1.0, 1.0, 7)
_SLOPE = _SLOPE_RAW / np.linalg.norm(_SLOPE_RAW)
_CURVE_RAW = np.array([1.0, 0.0, -0.6, -0.8, -0.6, 0.0, 1.0])
_CURVE_RAW -= _CURVE_RAW @ _LEVEL * _LEVEL
_CURVE_RAW -= _CURVE_RAW @ _SLOPE * _SLOPE
_CURVE = _CURVE_RAW / np.linalg.norm(_CURVE_RAW)
PLANTED = np.vstack([_LEVEL, _SLOPE, _CURVE])
PLANTED_SD_BP = np.array([6.0, 2.0, 0.7])


def synthetic_changes(n: int = 20_000, noise_bp: float = 0.05, seed: int = 7) -> np.ndarray:
    rng = np.random.default_rng(seed)
    scores = rng.standard_normal((n, 3)) * PLANTED_SD_BP
    noise = rng.standard_normal((n, 7)) * noise_bp
    out: np.ndarray = scores @ PLANTED + noise
    return out


def test_daily_changes_are_in_basis_points() -> None:
    rates = np.array([[1.00, 2.00], [1.05, 1.90], [1.10, 1.95]])
    changes = daily_changes(rates)
    expected = np.array([[5.0, -10.0], [5.0, 5.0]])
    # Tolerance: absolute 1e-9 bp (pure arithmetic on exact decimals).
    assert changes.shape == (2, 2)
    assert np.allclose(changes, expected, atol=1e-9, rtol=0.0)


def test_daily_changes_rejects_single_row() -> None:
    with pytest.raises(ValueError, match="at least two rows"):
        daily_changes(np.array([[1.0, 2.0]]))


def test_pca_recovers_planted_three_factor_structure() -> None:
    result = pca(synthetic_changes(), n_components=3)
    assert isinstance(result, PcaResult)
    # Each recovered component should align with the planted one up to sign:
    # |cos angle| > 0.999 (tolerance chosen from the 20,000-day sample and the
    # 0.05 bp noise; a wrong factor gives a cosine near zero).
    for k in range(3):
        cosine = abs(float(result.loadings[k] @ PLANTED[k]))
        assert cosine > 0.999, f"component {k}: |cos| = {cosine:.6f}"
    # Eigenvalues recover the planted variances within 5 percent relative,
    # 0.01 bp^2 absolute floor (sampling error of a variance with n = 20,000
    # is about 1 percent).
    for k in range(3):
        planted_var = PLANTED_SD_BP[k] ** 2
        assert abs(result.eigenvalues[k] - planted_var) <= max(0.05 * planted_var, 0.01), (
            f"eigenvalue {k}: {result.eigenvalues[k]:.4f} vs planted {planted_var:.4f}"
        )
    # Sign normalisation: first component positive, second upward sloping.
    assert np.all(result.loadings[0] > 0.0)
    assert result.loadings[1, -1] > result.loadings[1, 0]
    # Rows are unit length within 1e-9.
    assert np.allclose(np.linalg.norm(result.loadings, axis=1), 1.0, atol=1e-9, rtol=0.0)


def test_explained_variance_sums_to_one_with_all_components() -> None:
    changes = synthetic_changes(n=2_000)
    result = pca(changes, n_components=7)
    total = float(result.explained_variance_ratio.sum())
    # Tolerance: absolute 1e-9 (ratios of the same eigenvalue sum).
    assert abs(total - 1.0) <= 1e-9, f"sum of ratios = {total!r}"
    assert np.all(np.diff(result.eigenvalues) <= 1e-12), "eigenvalues not descending"


def test_pca_rejects_bad_component_count() -> None:
    changes = synthetic_changes(n=50)
    with pytest.raises(ValueError, match="n_components"):
        pca(changes, n_components=0)
    with pytest.raises(ValueError, match="n_components"):
        pca(changes, n_components=8)


def test_pca_rejects_zero_variance() -> None:
    with pytest.raises(ValueError, match="not positive"):
        pca(np.zeros((10, 3)), n_components=2)


@settings(max_examples=25, deadline=None)
@given(
    slope=st.floats(min_value=-3.0, max_value=3.0),
    intercept=st.floats(min_value=-5.0, max_value=5.0),
    seed=st.integers(min_value=0, max_value=2**31 - 1),
)
def test_regression_recovers_planted_slope_within_interval(
    slope: float, intercept: float, seed: int
) -> None:
    rng = np.random.default_rng(seed)
    n = 2_000
    x = rng.standard_normal(n) * 5.0
    y = intercept + slope * x + rng.standard_normal(n) * 1.0
    fit = regression_hedge_ratio(y, x)
    # The 95 percent interval should contain the planted slope. To keep the
    # property deterministic across seeds we widen to 4.5 standard errors
    # (a miss has probability below 1e-5 per example).
    half_width = 4.5 * fit.standard_error
    assert abs(fit.slope - slope) <= half_width, (
        f"slope {fit.slope:.5f} vs planted {slope:.5f}, se {fit.standard_error:.5f}"
    )
    # The reported interval is slope +/- 1.959964 se within 1e-9 absolute.
    z = 1.959963984540054
    assert abs(fit.lower_95 - (fit.slope - z * fit.standard_error)) <= 1e-9
    assert abs(fit.upper_95 - (fit.slope + z * fit.standard_error)) <= 1e-9
    assert fit.n == n
    assert 0.0 <= fit.r_squared <= 1.0


def test_regression_exact_line_has_unit_r_squared() -> None:
    x = np.arange(10.0)
    y = 3.0 + 2.0 * x
    fit = regression_hedge_ratio(y, x)
    # Tolerance: absolute 1e-9 (exact arithmetic up to rounding).
    assert abs(fit.slope - 2.0) <= 1e-9
    assert abs(fit.intercept - 3.0) <= 1e-9
    assert abs(fit.r_squared - 1.0) <= 1e-9
    assert fit.standard_error <= 1e-9


def test_regression_rejects_constant_hedge_and_short_input() -> None:
    with pytest.raises(ValueError, match="constant"):
        regression_hedge_ratio(np.array([1.0, 2.0, 3.0]), np.array([1.0, 1.0, 1.0]))
    with pytest.raises(ValueError, match="at least three"):
        regression_hedge_ratio(np.array([1.0, 2.0]), np.array([1.0, 2.0]))
    with pytest.raises(ValueError, match="shape mismatch"):
        regression_hedge_ratio(np.array([1.0, 2.0, 3.0]), np.array([1.0, 2.0]))


def test_pca_hedge_with_one_factor_is_loading_ratio() -> None:
    result = pca(synthetic_changes(n=5_000), n_components=3)
    ratio = pca_hedge_ratio(result, target_index=4, hedge_index=2, n_factors=1)
    expected = result.loadings[0, 4] / result.loadings[0, 2]
    # Tolerance: absolute 1e-12 (same arithmetic, different path).
    assert abs(ratio - expected) <= 1e-12


def test_pca_hedge_with_all_factors_equals_regression_slope() -> None:
    changes = synthetic_changes(n=3_000)
    result = pca(changes, n_components=7)
    for target, hedge in ((4, 2), (4, 6), (0, 3)):
        full = pca_hedge_ratio(result, target, hedge, n_factors=7)
        ols = regression_hedge_ratio(changes[:, target], changes[:, hedge]).slope
        # Tolerance: absolute 1e-9; the full factor expansion reproduces the
        # covariance exactly, so only rounding separates the two.
        assert abs(full - ols) <= 1e-9, f"({target},{hedge}): pca {full!r} vs ols {ols!r}"


def test_pca_hedge_rejects_bad_factor_count() -> None:
    result = pca(synthetic_changes(n=100), n_components=2)
    with pytest.raises(ValueError, match="n_factors"):
        pca_hedge_ratio(result, 0, 1, n_factors=3)


def test_rolling_hedge_ratio_shapes_and_consistency() -> None:
    changes = synthetic_changes(n=400)
    y, x = changes[:, 4], changes[:, 2]
    window = 250
    rolling = rolling_hedge_ratios(y, x, window)
    assert rolling.shape == (400 - window + 1, 3)
    # Lower bound below slope below upper bound, every row.
    assert np.all(rolling[:, 1] <= rolling[:, 0])
    assert np.all(rolling[:, 0] <= rolling[:, 2])
    # The first row equals a direct fit on the first window within 1e-12.
    first = regression_hedge_ratio(y[:window], x[:window])
    assert abs(rolling[0, 0] - first.slope) <= 1e-12
    assert abs(rolling[0, 1] - first.lower_95) <= 1e-12
    assert abs(rolling[0, 2] - first.upper_95) <= 1e-12
    with pytest.raises(ValueError, match="window"):
        rolling_hedge_ratios(y, x, 401)
    with pytest.raises(ValueError, match="window"):
        rolling_hedge_ratios(y, x, 2)


@pytest.mark.skipif(not FIXTURE.exists(), reason="par curve fixture not present")
def test_load_par_curve_fixture() -> None:
    dates, tenors, rates = load_par_curve(FIXTURE)
    assert tenors == list(DEFAULT_TENORS)
    assert rates.shape == (len(dates), 7)
    assert dates == sorted(dates)
    assert dates[0].isoformat() == "2015-01-02"
    assert np.all(np.isfinite(rates))
    # Par yields in percent: every value in (-1, 20) is a sanity bound.
    assert rates.min() > -1.0 and rates.max() < 20.0
    with pytest.raises(ValueError, match="tenors not in"):
        load_par_curve(FIXTURE, ("2 Yr", "99 Yr"))


@pytest.mark.skipif(not FIXTURE.exists(), reason="par curve fixture not present")
def test_first_component_explains_most_of_real_daily_changes() -> None:
    _, _, rates = load_par_curve(FIXTURE)
    result = pca(daily_changes(rates), n_components=3)
    first = float(result.explained_variance_ratio[0])
    # Threshold 0.70 from the task statement; the measured value on the
    # 2015 to 2025 fixture was 0.8782 when this test was written.
    assert first > 0.70, f"first component explains {first:.4f} of variance, expected > 0.70"
    assert np.all(result.loadings[0] > 0.0), "level loadings should all be positive"
    assert result.loadings[1, -1] > result.loadings[1, 0], "second component should slope up"


@pytest.mark.skipif(not FIXTURE.exists(), reason="par curve fixture not present")
def test_summarise_matches_committed_summary(tmp_path: Path) -> None:
    summary = summarise(FIXTURE)
    assert summary["produced_by"] == "ratesresearch.curves.summarise"
    assert [h["hedge"] for h in summary["hedges"]] == ["5 Yr", "30 Yr"]
    written = write_summary(FIXTURE, tmp_path / "summary.json")
    reloaded = json.loads((tmp_path / "summary.json").read_text(encoding="utf-8"))
    assert reloaded["fixture_sha256"] == written["fixture_sha256"]
    if SUMMARY.exists():
        committed = json.loads(SUMMARY.read_text(encoding="utf-8"))
        if committed["fixture_sha256"] == summary["fixture_sha256"]:
            # Same fixture bytes, so the numbers must agree to 1e-9 relative
            # (floating point order of operations only).
            a = np.array(committed["pca"]["explained_variance_ratio"])
            b = np.array(summary["pca"]["explained_variance_ratio"])
            assert np.allclose(a, b, rtol=1e-9, atol=0.0)
            for old, new in zip(committed["hedges"], summary["hedges"], strict=True):
                assert abs(old["regression"]["slope"] - new["regression"]["slope"]) <= 1e-9
                assert abs(old["rolling"]["slope_min"] - new["rolling"]["slope_min"]) <= 1e-9
                assert abs(old["rolling"]["slope_max"] - new["rolling"]["slope_max"]) <= 1e-9
