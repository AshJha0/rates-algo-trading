"""EDGE-E4: swap trade size by tenor and clustering after large trades (research/prereg/E4.yaml).

Sizes: per tenor, a lognormal fitted by maximum likelihood with the dissemination cap as a right
censoring point (EM on the log scale), from ``dtcc_sizes.csv`` (value counts per day). E4a: the
holdout daily share of trades at or above the fitted p95. E4b: excess same-tenor trades in the
5 minutes after a trade at or above the fitted p90 (day statistics in ``dtcc_cluster.csv``).
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.edge import common

FIT = ("2025-01-02", "2025-06-30")
HOLD = ("2025-07-01", "2026-06-30")
TENORS = (2, 5, 10, 30)
BLOCK = 5
EQ_LOW, EQ_HIGH = 0.025, 0.10
EXCESS_THRESHOLD = 0.1
Z = {"p50": 0.0, "p90": 1.2815515655446004, "p95": 1.6448536269514722, "p99": 2.3263478740408408}
BANDS = ("low", "central", "high")
HORIZON_S = 300


def _phi(z: float) -> float:
    return math.exp(-0.5 * z * z) / math.sqrt(2 * math.pi)


def _sf(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2))


def fit_censored_lognormal(
    values: np.ndarray, counts: np.ndarray, censored: np.ndarray, band: str
) -> dict[str, float]:
    """MLE of (mu, sigma) of log notional; ``censored`` marks capped prints (value = cap)."""
    if band == "low":
        keep = ~censored
        values, counts, censored = values[keep], counts[keep], censored[keep]
    if band == "high":
        censored = np.zeros_like(censored)
    logv = np.log(values)
    w = counts.astype(float)
    exact = ~censored
    mu = float(np.average(logv, weights=w))
    sigma = float(math.sqrt(np.average((logv - mu) ** 2, weights=w)))
    for _ in range(500):
        e1 = logv.copy()
        e2 = logv * logv
        for i in np.flatnonzero(censored):
            a = (logv[i] - mu) / sigma
            lam = _phi(a) / max(_sf(a), 1e-300)
            e1[i] = mu + sigma * lam
            e2[i] = mu * mu + sigma * sigma * (1 + a * lam) + 2 * mu * sigma * lam
        new_mu = float(np.average(e1, weights=w))
        new_sigma = float(math.sqrt(max(np.average(e2, weights=w) - new_mu**2, 1e-12)))
        done = abs(new_mu - mu) < 1e-10 and abs(new_sigma - sigma) < 1e-10
        mu, sigma = new_mu, new_sigma
        if done:
            break
    n_exact = float(w[exact].sum())
    out = {
        "mu": mu,
        "sigma": sigma,
        "mu_se": sigma / math.sqrt(max(n_exact, 1.0)),
        "sigma_se": sigma / math.sqrt(2 * max(n_exact, 1.0)),
        "n": float(w.sum()),
        "n_capped": float(w[censored].sum()) if band != "high" else 0.0,
    }
    for q, z in Z.items():
        out[q] = math.exp(mu + sigma * z)
    return out


def load_sizes(root: Path) -> pd.DataFrame:
    return pd.read_csv(root / "data/fixtures/edge/dtcc_sizes.csv", dtype={"date": str})


def data_files(root: Path) -> list[Path]:
    fx = root / "data/fixtures/edge"
    return [fx / "dtcc_sizes.csv", fx / "dtcc_cluster.csv"]


def _period(df: pd.DataFrame, rng: tuple[str, str]) -> pd.DataFrame:
    return df[(df["date"] >= rng[0]) & (df["date"] <= rng[1])]


def fits(sizes: pd.DataFrame, rng: tuple[str, str]) -> dict[str, dict[float, dict[str, float]]]:
    sub = _period(sizes, rng)
    out: dict[str, dict[float, dict[str, float]]] = {}
    for band in BANDS:
        out[band] = {}
        for t in TENORS:
            g = (
                sub[sub["tenor"] == t]
                .groupby(["notional_mm", "capped"])["count"]
                .sum()
                .reset_index()
            )
            out[band][float(t)] = fit_censored_lognormal(
                g["notional_mm"].to_numpy(float),
                g["count"].to_numpy(float),
                g["capped"].astype(bool).to_numpy(),
                band,
            )
    return out


def daily_exceedance(sizes: pd.DataFrame, f: dict[float, dict[str, float]], band: str) -> pd.Series:
    """Per day, share of the four tenors' trades at or above the tenor's fitted p95."""
    s = sizes.copy()
    capped = s["capped"].astype(bool)
    if band == "low":
        s = s[~capped]
        capped = capped[~capped]
    p95 = s["tenor"].astype(float).map(lambda t: f[t]["p95"])
    above = s["notional_mm"] >= p95
    s = s.assign(above=above * s["count"])
    g = s.groupby("date")[["above", "count"]].sum()
    return g["above"] / g["count"]


def cluster_rows(
    trades: dict[str, pd.DataFrame], fit_by_band: dict[str, dict[float, dict[str, float]]]
) -> list[dict[str, Any]]:
    """E4b day statistics from parsed prints (called by the build, raw data needed)."""
    rows: list[dict[str, Any]] = []
    for rng in (FIT, HOLD):
        days = [d for d in sorted(trades) if rng[0] <= d <= rng[1]]
        expected: dict[float, np.ndarray] = {}
        for t in TENORS:
            counts = np.zeros((len(days), 120))
            for j, d in enumerate(days):
                s = trades[d]
                s = s[s["tenor"] == t]
                b = (s["t"].dt.hour * 60 + s["t"].dt.minute - 420) // 5
                b = b[(b >= 0) & (b < 120)]
                np.add.at(counts[j], b.to_numpy(int), 1)
            expected[float(t)] = counts.mean(axis=0) if len(days) else np.zeros(120)
        for d in days:
            tr = trades[d]
            for band in BANDS:
                excess = 0.0
                n_large = 0
                for t in TENORS:
                    s = tr[tr["tenor"] == t]
                    if band == "low":
                        s = s[~s["capped"].astype(bool)]
                    if len(s) == 0:
                        continue
                    p90 = fit_by_band[band][float(t)]["p90"]
                    secs = common.epoch_seconds(s["t"])

                    mins = (s["t"].dt.hour * 60 + s["t"].dt.minute).to_numpy()
                    large = s["notional_mm"].to_numpy() >= p90
                    for i in np.flatnonzero(large):
                        b = (mins[i] - 420) // 5
                        if not 0 <= b < 120:
                            continue
                        nxt = int(np.sum((secs > secs[i]) & (secs <= secs[i] + HORIZON_S)))
                        excess += nxt - expected[float(t)][b]
                        n_large += 1
                rows.append({"date": d, "band": band, "n_large": n_large, "excess_sum": excess})
    return rows


def run(root: Path) -> dict[str, Any]:
    sizes = load_sizes(root)
    cluster = pd.read_csv(root / "data/fixtures/edge/dtcc_cluster.csv", dtype={"date": str})
    fit_fit = fits(sizes, FIT)
    fit_hold = fits(sizes, HOLD)
    out: dict[str, Any] = {"band": {}, "fits_fitting": fit_fit, "fits_holdout": fit_hold}
    for band in BANDS:
        res: dict[str, Any] = {}
        for label, rng in (("fitting", FIT), ("holdout", HOLD)):
            share = daily_exceedance(_period(sizes, rng), fit_fit[band], band)
            c = _period(cluster[cluster["band"] == band], rng)
            c = c[c["n_large"] > 0]
            res[label] = {
                "days": len(share),
                "E4a": common.equivalence_test(share.to_numpy(), BLOCK, EQ_LOW, EQ_HIGH),
                "E4b": common.mean_test((c["excess_sum"] / c["n_large"]).to_numpy(), BLOCK),
                "large_trades": int(c["n_large"].sum()),
            }
        out["band"][band] = res
    hold = out["band"]["central"]["holdout"]
    out["tests"] = {
        "E4a": {**hold["E4a"], "unit": "day", "equivalence": [EQ_LOW, EQ_HIGH]},
        "E4b": {**hold["E4b"], "sign": 1, "threshold": EXCESS_THRESHOLD, "unit": "day"},
    }
    tr = _period(sizes, HOLD)
    days = tr["date"].nunique()
    out["trades_per_day"] = {
        str(t): float(tr.loc[tr["tenor"] == t, "count"].sum() / days) for t in TENORS
    }
    out["data_range"] = {"start": str(sizes["date"].min()), "end": str(sizes["date"].max())}
    return out


def judge_equivalence(test: dict[str, Any]) -> str:
    inside = test["ci_low"] >= EQ_LOW and test["ci_high"] <= EQ_HIGH
    return "passed" if test["holm_p"] < common.ALPHA and inside else "failed"
