"""Curve analytics on the Treasury par curve fixture: PCA and hedge ratios.

Everything numerical is numpy. pandas is used once, to read the CSV. The
functions take and return plain arrays so they can be tested on synthetic
data with a stated tolerance.

Conventions: rates are in percent as published, daily changes are in basis
points, PCA loadings are rows of the ``loadings`` matrix (one row per
component), and a hedge ratio is the number of units of the hedge instrument
per unit of the target, measured in yield-change space (not DV01-weighted).

Confidence intervals use the normal approximation to the t distribution
(``statistics.NormalDist``). The project has no scipy dependency; with the
smallest window used here (250 daily changes) the t quantile differs from the
normal one by under one percent, which is small next to the sampling error of
the slope itself.

Run ``python -m ratesresearch.curves <fixture.csv> <summary.json>`` to write
the summary that the documentation quotes.
"""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from statistics import NormalDist
from typing import Any

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch.provenance import sha256_of

FloatArray = npt.NDArray[np.float64]

DEFAULT_TENORS: tuple[str, ...] = ("2 Yr", "3 Yr", "5 Yr", "7 Yr", "10 Yr", "20 Yr", "30 Yr")
ROLLING_WINDOW = 250


@dataclass(frozen=True)
class PcaResult:
    """Principal components of a matrix of daily changes.

    ``loadings`` has one component per row, unit length, sign-normalised so
    the first component's loadings are positive (level) and the second slopes
    upward from the first tenor to the last (slope). ``eigenvalues`` are the
    variances along each component, ``explained_variance_ratio`` divides them
    by the total variance over all tenors (so it sums to one only when
    ``n_components`` equals the number of tenors), and ``mean`` is the column
    mean removed before the covariance was formed.
    """

    loadings: FloatArray
    explained_variance_ratio: FloatArray
    eigenvalues: FloatArray
    mean: FloatArray


@dataclass(frozen=True)
class HedgeRatio:
    """Least-squares slope of target changes on hedge changes with its interval."""

    slope: float
    intercept: float
    r_squared: float
    standard_error: float
    lower_95: float
    upper_95: float
    n: int


def load_par_curve(
    path: str | Path, tenors: tuple[str, ...] = DEFAULT_TENORS
) -> tuple[list[date], list[str], FloatArray]:
    """Read the par curve fixture; drop rows missing any chosen tenor.

    Returns dates (ascending), the tenor names and a matrix of rates in
    percent with one row per date and one column per tenor.
    """
    frame = pd.read_csv(path, dtype=str, keep_default_na=False)
    missing = [t for t in tenors if t not in frame.columns]
    if missing:
        msg = f"tenors not in {path}: {missing}"
        raise ValueError(msg)
    sub = frame[["date", *tenors]]
    keep = (sub[list(tenors)] != "").all(axis=1)
    sub = sub[keep].sort_values("date")
    dates = [date.fromisoformat(d) for d in sub["date"]]
    rates = sub[list(tenors)].to_numpy(dtype=np.float64)
    if len(dates) < 3:
        msg = f"too few complete rows in {path}: {len(dates)}"
        raise ValueError(msg)
    return dates, list(tenors), rates


def daily_changes(rates: FloatArray) -> FloatArray:
    """Row-to-row differences in basis points (rates are in percent)."""
    arr = np.asarray(rates, dtype=np.float64)
    if arr.ndim != 2 or arr.shape[0] < 2:
        msg = f"need a 2-D array with at least two rows, got shape {arr.shape}"
        raise ValueError(msg)
    return np.diff(arr, axis=0) * 100.0


def pca(changes: FloatArray, n_components: int = 3) -> PcaResult:
    """PCA of ``changes`` (rows are days, columns tenors) via the covariance matrix.

    Uses ``numpy.linalg.eigh`` on the sample covariance (ddof 1). Components
    are ordered by decreasing eigenvalue and sign-normalised as described on
    ``PcaResult``.
    """
    x = np.asarray(changes, dtype=np.float64)
    if x.ndim != 2:
        msg = f"changes must be 2-D, got shape {x.shape}"
        raise ValueError(msg)
    n_days, n_tenors = x.shape
    if n_days < 2:
        msg = "need at least two rows of changes"
        raise ValueError(msg)
    if not 1 <= n_components <= n_tenors:
        msg = f"n_components must be in [1, {n_tenors}], got {n_components}"
        raise ValueError(msg)
    mean = x.mean(axis=0)
    centred = x - mean
    cov = centred.T @ centred / (n_days - 1)
    values, vectors = np.linalg.eigh(cov)
    order = np.argsort(values)[::-1]
    values = values[order]
    vectors = vectors[:, order]
    total = float(values.sum())
    if not np.isfinite(total) or total <= 0.0:
        msg = f"total variance is not positive: {total!r}; inputs {x.shape}"
        raise ValueError(msg)
    loadings = vectors[:, :n_components].T.copy()
    if loadings[0].sum() < 0.0:
        loadings[0] *= -1.0
    if n_components >= 2 and loadings[1, -1] < loadings[1, 0]:
        loadings[1] *= -1.0
    for k in range(2, n_components):
        # Later components: make the largest-magnitude loading positive so
        # the sign is reproducible run to run.
        pivot = int(np.argmax(np.abs(loadings[k])))
        if loadings[k, pivot] < 0.0:
            loadings[k] *= -1.0
    return PcaResult(
        loadings=loadings,
        explained_variance_ratio=values[:n_components] / total,
        eigenvalues=values[:n_components],
        mean=mean,
    )


def _normal_quantile(p: float) -> float:
    return NormalDist().inv_cdf(p)


def regression_hedge_ratio(target_changes: FloatArray, hedge_changes: FloatArray) -> HedgeRatio:
    """Ordinary least squares of target on hedge: ``target = intercept + slope * hedge``.

    Returns the slope, intercept, R squared, the standard error of the slope
    and a 95 percent interval ``slope +/- z * se`` with ``z`` the normal
    quantile 1.959964 (normal approximation to the t distribution; see the
    module docstring). Raises ``ValueError`` on fewer than three points or a
    constant hedge series.
    """
    y = np.asarray(target_changes, dtype=np.float64).ravel()
    x = np.asarray(hedge_changes, dtype=np.float64).ravel()
    if y.shape != x.shape:
        msg = f"shape mismatch: target {y.shape}, hedge {x.shape}"
        raise ValueError(msg)
    n = int(y.size)
    if n < 3:
        msg = f"need at least three points for a slope with an interval, got {n}"
        raise ValueError(msg)
    x_mean = x.mean()
    y_mean = y.mean()
    sxx = float(((x - x_mean) ** 2).sum())
    if sxx <= 0.0:
        msg = "hedge changes are constant; slope is undefined"
        raise ValueError(msg)
    sxy = float(((x - x_mean) * (y - y_mean)).sum())
    slope = sxy / sxx
    intercept = float(y_mean - slope * x_mean)
    residuals = y - (intercept + slope * x)
    sse = float((residuals**2).sum())
    sst = float(((y - y_mean) ** 2).sum())
    r_squared = 1.0 - sse / sst if sst > 0.0 else 1.0
    se = float(np.sqrt(sse / (n - 2) / sxx))
    z = _normal_quantile(0.975)
    return HedgeRatio(
        slope=float(slope),
        intercept=intercept,
        r_squared=float(r_squared),
        standard_error=se,
        lower_95=float(slope - z * se),
        upper_95=float(slope + z * se),
        n=n,
    )


def pca_hedge_ratio(
    pca_result: PcaResult, target_index: int, hedge_index: int, n_factors: int = 1
) -> float:
    """Hedge ratio implied by the first ``n_factors`` components.

    Hedging the target with the hedge instrument so that the residual has no
    exposure to the retained factors, in the least-squares sense weighted by
    factor variance, gives

        h = sum_k lambda_k L[k, target] L[k, hedge] / sum_k lambda_k L[k, hedge]^2

    summed over k < n_factors. With one factor this is the ratio of loadings
    ``L[0, target] / L[0, hedge]``, the usual PCA (level) hedge. With all
    factors retained it equals the regression slope exactly, because the
    factor decomposition then reproduces the covariance matrix.
    """
    k = n_factors
    if not 1 <= k <= pca_result.loadings.shape[0]:
        msg = f"n_factors must be in [1, {pca_result.loadings.shape[0]}], got {k}"
        raise ValueError(msg)
    lam = pca_result.eigenvalues[:k]
    lt = pca_result.loadings[:k, target_index]
    lh = pca_result.loadings[:k, hedge_index]
    denominator = float((lam * lh * lh).sum())
    if denominator <= 0.0:
        msg = f"hedge instrument has no loading on the first {k} factor(s)"
        raise ValueError(msg)
    return float((lam * lt * lh).sum() / denominator)


def rolling_hedge_ratios(
    target_changes: FloatArray, hedge_changes: FloatArray, window: int
) -> FloatArray:
    """Regression slope and its 95 percent interval over each trailing window.

    Returns an array of shape ``(n - window + 1, 3)`` with columns
    ``slope, lower_95, upper_95``; row ``i`` uses observations
    ``i .. i + window - 1``.
    """
    y = np.asarray(target_changes, dtype=np.float64).ravel()
    x = np.asarray(hedge_changes, dtype=np.float64).ravel()
    n = int(y.size)
    if window < 3 or window > n:
        msg = f"window must be in [3, {n}], got {window}"
        raise ValueError(msg)
    out = np.empty((n - window + 1, 3), dtype=np.float64)
    for i in range(n - window + 1):
        fit = regression_hedge_ratio(y[i : i + window], x[i : i + window])
        out[i] = (fit.slope, fit.lower_95, fit.upper_95)
    return out


def _pair_summary(
    changes: FloatArray,
    result: PcaResult,
    tenors: list[str],
    target: str,
    hedge: str,
    window: int,
) -> dict[str, Any]:
    ti = tenors.index(target)
    hi = tenors.index(hedge)
    fit = regression_hedge_ratio(changes[:, ti], changes[:, hi])
    rolling = rolling_hedge_ratios(changes[:, ti], changes[:, hi], window)
    return {
        "target": target,
        "hedge": hedge,
        "regression": asdict(fit),
        "pca_hedge_1_factor": pca_hedge_ratio(result, ti, hi, 1),
        "pca_hedge_2_factors": pca_hedge_ratio(result, ti, hi, 2),
        "pca_hedge_3_factors": pca_hedge_ratio(result, ti, hi, 3),
        "rolling": {
            "window": window,
            "count": int(rolling.shape[0]),
            "slope_min": float(rolling[:, 0].min()),
            "slope_max": float(rolling[:, 0].max()),
            "slope_mean": float(rolling[:, 0].mean()),
        },
    }


def summarise(
    path: str | Path,
    tenors: tuple[str, ...] = DEFAULT_TENORS,
    window: int = ROLLING_WINDOW,
) -> dict[str, Any]:
    """Full-sample PCA and hedge ratios for the fixture at ``path``.

    The dictionary holds the fixture name and sha256, the date range and
    count, the three-component loadings and explained variance, and for the
    10y-versus-5y and 10y-versus-30y pairs the regression and PCA hedge
    ratios plus the min, max and mean of the rolling ``window``-day slope.
    """
    dates, names, rates = load_par_curve(path, tenors)
    changes = daily_changes(rates)
    result = pca(changes, 3)
    return {
        "fixture": Path(path).name,
        "fixture_sha256": sha256_of(path),
        "computed_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "produced_by": "ratesresearch.curves.summarise",
        "tenors": names,
        "first_date": dates[0].isoformat(),
        "last_date": dates[-1].isoformat(),
        "n_days": len(dates),
        "n_changes": int(changes.shape[0]),
        "change_units": "basis points per day",
        "pca": {
            "loadings": result.loadings.tolist(),
            "explained_variance_ratio": result.explained_variance_ratio.tolist(),
            "eigenvalues_bp2": result.eigenvalues.tolist(),
            "mean_bp": result.mean.tolist(),
        },
        "interval_method": "normal approximation to t, z = 1.959964",
        "hedges": [
            _pair_summary(changes, result, names, "10 Yr", "5 Yr", window),
            _pair_summary(changes, result, names, "10 Yr", "30 Yr", window),
        ],
    }


def write_summary(fixture: str | Path, out_path: str | Path) -> dict[str, Any]:
    """Write ``summarise(fixture)`` as indented JSON to ``out_path`` and return it."""
    summary = summarise(fixture)
    Path(out_path).write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8", newline="\n")
    return summary


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print("usage: python -m ratesresearch.curves <par_curve_fixture.csv> <summary.json>")
        return 2
    summary = write_summary(args[0], args[1])
    ratios = summary["pca"]["explained_variance_ratio"]
    print(f"wrote {args[1]}: explained variance {[round(r, 4) for r in ratios]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
