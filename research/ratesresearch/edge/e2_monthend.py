"""EDGE-E2: month-end index extension and 2s30s (research/prereg/E2.yaml).

Month-end k-day change of 100 * (y30 - y2) minus the mean k-day change away from month ends,
in months whose term-weighted coupon issuance is above the fitting-period 67th percentile.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.edge import common

FIT = ("2015-01", "2020-12")
HOLD = ("2021-01", "2026-06")
BAND = {"low": 2, "central": 3, "high": 4}
BLOCK = 3
THRESHOLD_BP = 0.5
QUANTILE = 0.67
MONTH_END_ZONE = 5


def extension_proxy(auctions: pd.DataFrame) -> pd.Series:
    """USD bn times years of nominal coupons issued in each month (YYYY-MM)."""
    a = auctions.assign(month=auctions["issue_date"].str[:7])
    return (a["offering_bn"] * a["tenor"]).groupby(a["month"]).sum()


def month_end_table(curve: pd.DataFrame, k: int) -> tuple[pd.DataFrame, pd.Series]:
    """Per month: month-end change; and per period-month the baseline k-day changes."""
    s = (100 * (curve["y30"] - curve["y2"])).to_numpy()
    month = curve["date"].str[:7].to_numpy()
    n = len(s)
    # position of each day counted from the end of its month (0 = last business day)
    from_end = np.zeros(n, dtype=int)
    for i in range(n - 2, -1, -1):
        from_end[i] = from_end[i + 1] + 1 if month[i] == month[i + 1] else 0
    zone = from_end < MONTH_END_ZONE
    rows = []
    for i in range(k, n):
        if from_end[i] == 0 and (i == n - 1 or month[i] != month[i + 1]):
            rows.append({"month": month[i], "change": float(s[i] - s[i - k])})
    me = pd.DataFrame(rows)
    base_vals = []
    base_month = []
    for i in range(k, n):
        if not zone[i - k : i + 1].any():
            base_vals.append(float(s[i] - s[i - k]))
            base_month.append(month[i])
    base = pd.Series(base_vals, index=base_month)
    return me, base


def _in(df: pd.DataFrame, rng: tuple[str, str]) -> pd.DataFrame:
    return df[(df["month"] >= rng[0]) & (df["month"] <= rng[1])]


def load(root: Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    fx = root / "data/fixtures/edge"
    curve = pd.read_csv(fx / "par_curve.csv", dtype={"date": str})
    auctions = pd.read_csv(fx / "auctions.csv", dtype={"issue_date": str, "auction_date": str})
    return curve, auctions


def data_files(root: Path) -> list[Path]:
    fx = root / "data/fixtures/edge"
    return [fx / "par_curve.csv", fx / "auctions.csv"]


def run(root: Path) -> dict[str, Any]:
    curve, auctions = load(root)
    x = extension_proxy(auctions)
    fit_x = x[(x.index >= FIT[0]) & (x.index <= FIT[1])]
    threshold = float(np.quantile(fit_x.to_numpy(), QUANTILE))
    out: dict[str, Any] = {"band": {}, "extension_threshold_bn_years": threshold}
    for point, k in BAND.items():
        me, base = month_end_table(curve, k)
        me["x"] = me["month"].map(x).fillna(0.0)
        me["high"] = me["x"] > threshold
        band: dict[str, Any] = {}
        for label, rng in (("fitting", FIT), ("holdout", HOLD)):
            b = base[(base.index >= rng[0]) & (base.index <= rng[1])]
            sub = _in(me, rng)
            unit = sub["change"] - float(b.mean())
            hi = unit[sub["high"]]
            band[label] = {
                "months": len(sub),
                "high_months": len(hi),
                "baseline_mean_bp": float(b.mean()),
                "baseline_windows": len(b),
                "high": common.mean_test(hi.to_numpy(), BLOCK),
                "all_months": common.mean_test(unit.to_numpy(), BLOCK),
                "low_months": common.mean_test(unit[~sub["high"]].to_numpy(), BLOCK),
            }
            if label == "holdout" and point == "central":
                out["holdout_units"] = sub.assign(unit=unit)
        out["band"][point] = band
    c = out["band"]["central"]["holdout"]["high"]
    out["tests"] = {
        "E2": {**c, "sign": -1, "threshold": THRESHOLD_BP, "unit": "high-extension month"}
    }
    out["proxy"] = x
    out["data_range"] = {"start": str(curve["date"].iloc[0]), "end": str(curve["date"].iloc[-1])}
    return out
