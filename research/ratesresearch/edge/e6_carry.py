"""EDGE-E6: carry and roll-down inventory tilt (research/prereg/E6.yaml).

Monthly rebalance at the last par-curve day of each month. Per tenor, a position of one bp DV01
earns carry less funding over its duration plus the yield change at the aged maturity. The
tilt reweights DV01 by 1 + 0.5 z (no shorts, same total) where z is the cross-sectional z-score
of expected carry and roll-down.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.edge import common

FIT = ("2015-01", "2020-12")
HOLD = ("2021-01", "2026-05")  # rebalance months; the last is held to 2026-06-30
TENORS = (2, 3, 5, 7, 10, 20, 30)
GRID = (1 / 12, 1, 2, 3, 5, 7, 10, 20, 30)
GRID_COLS = ("m1", "y1", "y2", "y3", "y5", "y7", "y10", "y20", "y30")
BAND = {"low": 0.0, "central": 10.0, "high": 25.0}
K = 0.5
BLOCK = 3
THRESHOLD_BP = 0.1


def duration(y: float, t: float) -> float:
    """Modified duration of a par bond with semi-annual coupons, yield y (decimal)."""
    if abs(y) < 1e-9:
        return t
    return float((1 - (1 + y / 2) ** (-2 * t)) / y)


def month_ends(curve: pd.DataFrame) -> pd.DataFrame:
    month = curve["date"].str[:7]
    return curve[month != month.shift(-1)].reset_index(drop=True)


def monthly_table(curve: pd.DataFrame, spread_bp: float) -> pd.DataFrame:
    me = month_ends(curve)
    rows = []
    for i in range(len(me) - 1):
        now, nxt = me.iloc[i], me.iloc[i + 1]
        grid_now = np.array([now[c] for c in GRID_COLS]) / 100
        grid_nxt = np.array([nxt[c] for c in GRID_COLS]) / 100
        f = float(now["m1"]) / 100 + spread_bp / 1e4
        exp_, real = [], []
        for t in TENORS:
            y = float(now[f"y{t}"]) / 100
            d = duration(y, t)
            carry = 1e4 * (y - f) / 12 / d
            aged_now = float(np.interp(t - 1 / 12, GRID, grid_now))
            aged_next = float(np.interp(t - 1 / 12, GRID, grid_nxt))
            exp_.append(carry + 1e4 * (y - aged_now))
            real.append(carry - 1e4 * (aged_next - y))
        e = np.array(exp_)
        z = (e - e.mean()) / e.std(ddof=0) if e.std(ddof=0) > 0 else np.zeros_like(e)
        w = np.maximum(0.0, 1 + K * z)
        w = w * len(TENORS) / w.sum()
        r = np.array(real)
        row: dict[str, Any] = {
            "month": str(now["date"])[:7],
            "baseline": float(r.sum()),
            "tilt": float((w * r).sum()),
            "diff": float((w * r).sum() - r.sum()),
        }
        for t, ev, wv in zip(TENORS, e, w, strict=True):
            row[f"cr{t}"] = float(ev)
            row[f"w{t}"] = float(wv)
        rows.append(row)
    return pd.DataFrame(rows)


def _in(df: pd.DataFrame, rng: tuple[str, str]) -> pd.DataFrame:
    return df[(df["month"] >= rng[0]) & (df["month"] <= rng[1])]


def data_files(root: Path) -> list[Path]:
    return [root / "data/fixtures/edge/par_curve.csv"]


def run(root: Path) -> dict[str, Any]:
    curve = pd.read_csv(root / "data/fixtures/edge/par_curve.csv", dtype={"date": str})
    out: dict[str, Any] = {"band": {}}
    for point, spread in BAND.items():
        tab = monthly_table(curve, spread)
        band = {}
        for label, rng in (("fitting", FIT), ("holdout", HOLD)):
            sub = _in(tab, rng)
            band[label] = {
                "months": len(sub),
                "diff": common.mean_test(sub["diff"].to_numpy(), BLOCK),
                "baseline_mean_bp": float(sub["baseline"].mean()),
                "tilt_mean_bp": float(sub["tilt"].mean()),
            }
        out["band"][point] = band
        if point == "central":
            out["monthly"] = tab
    c = out["band"]["central"]["holdout"]["diff"]
    out["tests"] = {"E6": {**c, "sign": 1, "threshold": THRESHOLD_BP, "unit": "month"}}
    out["data_range"] = {"start": str(curve["date"].iloc[0]), "end": str(curve["date"].iloc[-1])}
    return out
