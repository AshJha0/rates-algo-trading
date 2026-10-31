"""EDGE-E3: swap print volatility and intensity around releases, FOMC and SOFR (E3.yaml).

Reads ``dtcc_buckets.csv`` (per day and 5-minute bucket from 07:00 ET: trade count and realised
variance of print rates) and ``calendar.csv``. Event-day window statistics are divided by the
mean over control days of the same period; event days are the bootstrap unit (blocks of 2).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.edge import common

FIT = ("2025-01-02", "2025-06-30")
HOLD = ("2025-07-01", "2026-06-30")
BUCKETS = 120
W_0830 = (18, 19, 20)  # 08:30 to 08:45
W_1400 = (84, 85, 86, 87, 88, 89)  # 14:00 to 14:30
B_0800 = 12  # 08:00 to 08:05
ADJ_0800 = (9, 10, 11, 13, 14, 15)  # 07:45 to 08:00 and 08:05 to 08:20
BLOCK = 2
RATIO_THRESHOLD = 0.25
NEIGHBOURHOOD = 20


def bucket_label(b: int) -> str:
    m = 7 * 60 + 5 * b
    return f"{m // 60:02d}:{m % 60:02d}"


def load(root: Path) -> tuple[dict[str, np.ndarray], dict[str, np.ndarray], pd.DataFrame]:
    fx = root / "data/fixtures/edge"
    b = pd.read_csv(fx / "dtcc_buckets.csv", dtype={"date": str})
    cal = pd.read_csv(fx / "calendar.csv", dtype={"date": str})
    days = sorted(b["date"].unique())
    rv = {d: np.zeros(BUCKETS) for d in days}
    n = {d: np.zeros(BUCKETS) for d in days}
    for r in b.itertuples():
        rv[str(r.date)][int(str(r.bucket))] = float(str(r.rv))
        n[str(r.date)][int(str(r.bucket))] = float(str(r.n))
    return rv, n, cal


def data_files(root: Path) -> list[Path]:
    fx = root / "data/fixtures/edge"
    return [fx / "dtcc_buckets.csv", fx / "calendar.csv"]


def day_types(days: list[str], cal: pd.DataFrame) -> pd.DataFrame:
    release = set(cal.loc[cal["event"] != "fomc", "date"])
    fomc = set(cal.loc[cal["event"] == "fomc", "date"])
    wd = pd.to_datetime(pd.Series(days)).dt.weekday.to_numpy()
    t = pd.DataFrame({"date": days, "weekday": wd})
    t["release"] = t["date"].isin(release)
    t["fomc"] = t["date"].isin(fomc)
    quiet = ~t["release"] & ~t["fomc"]
    t["control_0830"] = quiet & (t["weekday"] != 3)
    t["control_0830_thu"] = quiet
    t["control_1400"] = quiet
    return t


def _window(x: dict[str, np.ndarray], d: str, w: tuple[int, ...]) -> float:
    return float(x[d][list(w)].mean())


def event_units(
    x: dict[str, np.ndarray],
    types: pd.DataFrame,
    rng: tuple[str, str],
    event: str,
    control: str,
    w: tuple[int, ...],
    neighbourhood: bool,
) -> np.ndarray:
    """x_d = window statistic of event day d over the control mean, minus one."""
    t = types.reset_index(drop=True)
    inside = (t["date"] >= rng[0]) & (t["date"] <= rng[1])
    ctrl = t[inside & t[control]]
    c_all = np.mean([_window(x, d, w) for d in ctrl["date"]])
    out = []
    for i in t.index[inside & t[event]]:
        if neighbourhood:
            near = ctrl[(ctrl.index >= i - NEIGHBOURHOOD) & (ctrl.index <= i + NEIGHBOURHOOD)]
            c = np.mean([_window(x, d, w) for d in near["date"]]) if len(near) else c_all
        else:
            c = c_all
        out.append(_window(x, t.loc[i, "date"], w) / c - 1 if c > 0 else np.nan)
    return np.array([v for v in out if np.isfinite(v)])


def sofr_units(
    rv: dict[str, np.ndarray], types: pd.DataFrame, rng: tuple[str, str], control: str
) -> np.ndarray:
    inside = (types["date"] >= rng[0]) & (types["date"] <= rng[1])
    days = types.loc[inside & types[control], "date"]
    c = np.mean([_window(rv, d, ADJ_0800) for d in days])
    return np.array([rv[d][B_0800] / c - 1 for d in days])


def run(root: Path) -> dict[str, Any]:
    rv, n, cal = load(root)
    types = day_types(sorted(rv), cal)
    out: dict[str, Any] = {"band": {}}
    specs = {
        "E3a": (rv, "release", "control_0830", W_0830),
        "E3b": (rv, "fomc", "control_1400", W_1400),
        "E3d": (n, "release", "control_0830", W_0830),
    }
    for point in ("low", "central", "high"):
        band: dict[str, Any] = {}
        for label, rng in (("fitting", FIT), ("holdout", HOLD)):
            res: dict[str, Any] = {}
            for name, (x, ev, ctrl, w) in specs.items():
                if point == "high" and ctrl == "control_0830":
                    ctrl = "control_0830_thu"
                u = event_units(x, types, rng, ev, ctrl, w, neighbourhood=point == "low")
                res[name] = common.mean_test(u, BLOCK)
            ctrl = "control_0830_thu" if point == "high" else "control_0830"
            res["E3c"] = common.mean_test(sofr_units(rv, types, rng, ctrl), BLOCK)
            band[label] = res
        out["band"][point] = band
    hold = out["band"]["central"]["holdout"]
    units = {"E3a": "release day", "E3b": "FOMC day", "E3c": "control day", "E3d": "release day"}
    out["tests"] = {
        k: {
            **hold[k],
            "sign": 1,
            "threshold": 0.0 if k == "E3c" else RATIO_THRESHOLD,
            "unit": units[k],
        }
        for k in ("E3a", "E3b", "E3c", "E3d")
    }
    out["schedule"] = schedule(rv, n, types, HOLD)
    out["schedule_fitting"] = schedule(rv, n, types, FIT)
    out["day_counts"] = {
        label: {
            c: int(((types["date"] >= r[0]) & (types["date"] <= r[1]) & types[c]).sum())
            for c in ("release", "fomc", "control_0830", "control_1400")
        }
        | {"days": int(((types["date"] >= r[0]) & (types["date"] <= r[1])).sum())}
        for label, r in (("fitting", FIT), ("holdout", HOLD))
    }
    out["data_range"] = {"start": min(rv), "end": max(rv)}
    return out


def schedule(
    rv: dict[str, np.ndarray], n: dict[str, np.ndarray], types: pd.DataFrame, rng: tuple[str, str]
) -> dict[str, Any]:
    """Per-bucket RV ratios in the two windows and the 30-minute intensity profile."""
    inside = (types["date"] >= rng[0]) & (types["date"] <= rng[1])
    out: dict[str, Any] = {}
    for name, ev, ctrl, w in (
        ("release", "release", "control_0830", (*W_0830, 21, 22, 23)),
        ("fomc", "fomc", "control_1400", (*W_1400, *range(90, 96))),
    ):
        ev_days = types.loc[inside & types[ev], "date"].tolist()
        c_days = types.loc[inside & types[ctrl], "date"].tolist()
        rows = []
        for b in w:
            c = float(np.mean([rv[d][b] for d in c_days]))
            vals = np.array([rv[d][b] / c for d in ev_days]) if c > 0 else np.array([np.nan])
            t = common.mean_test(vals, BLOCK)
            m = float(np.sqrt(max(t["mean"], 0.0)))
            nc = float(np.mean([n[d][b] for d in c_days]))
            ne = float(np.mean([n[d][b] for d in ev_days]))
            rows.append(
                {
                    "bucket": bucket_label(b),
                    "rv_ratio": t["mean"],
                    "rv_ratio_se": t["se"],
                    "multiplier": max(1.0, m),
                    "multiplier_se": t["se"] / (2 * m) if m > 0 else None,
                    "intensity_ratio": ne / nc if nc > 0 else None,
                }
            )
        out[name] = rows
    quiet = types.loc[inside & types["control_1400"], "date"].tolist()
    mat = np.array([n[d] for d in quiet]).reshape(len(quiet), 20, 6).sum(axis=2)
    overall = mat.mean()
    prof = []
    for j in range(20):
        t = common.mean_test(mat[:, j] / overall, 5)
        prof.append({"bucket": bucket_label(6 * j), "multiplier": t["mean"], "se": t["se"]})
    out["intensity_profile"] = prof
    out["control_trades_per_30min"] = float(overall)
    out["control_days"] = len(quiet)
    return out
