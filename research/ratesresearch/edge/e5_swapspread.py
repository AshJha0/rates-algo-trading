"""EDGE-E5: next-week 10Y swap spread change from CFTC positioning (research/prereg/E5.yaml).

Weekly on the first business day with a DTCC file. The DTCC files before 2024-10-29 are in S3
Deep Archive (HTTP 403 InvalidObjectState on 2026-10-31), so the registered fitting period
2024-01-01 to 2024-12-31 has about eight usable weeks; the run is reported as registered and
the verdict is inconclusive under the programme's data-defect rule.
"""

from __future__ import annotations

from datetime import date, timedelta
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.edge import common

FIT = ("2024-01-01", "2024-12-31")
HOLD = ("2025-01-01", "2026-06-30")
BAND = ("low", "central", "high")
BLOCK = 4
MIN_HOLDOUT_WEEKS = 50
FIRST_AVAILABLE = "2024-10-29"


def load(root: Path) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    fx = root / "data/fixtures/edge"
    ois = pd.read_csv(fx / "dtcc_ois10.csv", dtype={"date": str})
    curve = pd.read_csv(fx / "par_curve.csv", dtype={"date": str})
    cot = pd.read_csv(fx / "cot_ust10y.csv", dtype={"report_date": str})
    return ois, curve, cot


def data_files(root: Path) -> list[Path]:
    fx = root / "data/fixtures/edge"
    return [fx / "dtcc_ois10.csv", fx / "par_curve.csv", fx / "cot_ust10y.csv"]


def weekly_table(
    ois: pd.DataFrame, curve: pd.DataFrame, cot: pd.DataFrame, band: str
) -> pd.DataFrame:
    m = ois.merge(curve[["date", "y10"]], on="date", how="inner")
    m["ss"] = 100 * (m[f"ois10_{band}"] - m["y10"])
    d = pd.to_datetime(m["date"])
    m["week"] = (d - pd.to_timedelta(d.dt.weekday, unit="D")).dt.date.astype(str)
    first = m.sort_values("date").groupby("week").first().reset_index()
    cot = cot.assign(net=(cot["lev_long"] - cot["lev_short"]) / cot["open_interest"])
    cot = cot.assign(
        available=[
            (date.fromisoformat(r) + timedelta(days=3)).isoformat() for r in cot["report_date"]
        ]
    )
    rows = []
    for i in range(1, len(first) - 1):
        w, prev, nxt = first.iloc[i], first.iloc[i - 1], first.iloc[i + 1]
        known = cot[cot["available"] < w["date"]]
        if len(known) < 2:
            continue
        x1 = float(known["net"].iloc[-1] - known["net"].iloc[-2])
        rows.append(
            {
                "date": w["date"],
                "ss": w["ss"],
                "x1": x1,
                "x2": w["ss"] - prev["ss"],
                "target": nxt["ss"] - w["ss"],
            }
        )
    return pd.DataFrame(rows).dropna()


def run(root: Path) -> dict[str, Any]:
    ois, curve, cot = load(root)
    out: dict[str, Any] = {"band": {}}
    for band in BAND:
        tab = weekly_table(ois, curve, cot, band)
        fit = tab[(tab["date"] >= FIT[0]) & (tab["date"] <= FIT[1])]
        hold = tab[(tab["date"] >= HOLD[0]) & (tab["date"] <= HOLD[1])]
        x = np.column_stack([np.ones(len(fit)), fit["x1"], fit["x2"]])
        beta, *_ = np.linalg.lstsq(x, fit["target"].to_numpy(), rcond=None)
        f_fit = x @ beta
        xh = np.column_stack([np.ones(len(hold)), hold["x1"], hold["x2"]])
        f = xh @ beta
        t = hold["target"].to_numpy()
        var_f = float(np.var(f_fit, ddof=1)) if len(fit) > 2 else float("nan")
        u = f * t / var_f
        r2 = 1 - float(np.sum((t - f) ** 2)) / float(np.sum((t - fit["target"].mean()) ** 2))
        out["band"][band] = {
            "fitting_weeks": len(fit),
            "holdout_weeks": len(hold),
            "beta": {
                "intercept": beta[0],
                "slope_cot_change": beta[1],
                "slope_lagged_change": beta[2],
            },
            "test": common.mean_test(u, BLOCK),
            "oos_r2": r2,
            "corr_x1_target": float(np.corrcoef(hold["x1"], t)[0, 1]),
            "corr_x2_target": float(np.corrcoef(hold["x2"], t)[0, 1]),
            "target_sd_bp": float(np.std(t, ddof=1)),
        }
        if band == "central":
            out["weekly"] = tab
    c = out["band"]["central"]
    out["tests"] = {
        "E5": {**c["test"], "sign": 1, "threshold": 0.0, "oos_r2": c["oos_r2"], "unit": "week"}
    }
    out["deviation"] = (
        f"DTCC files before {FIRST_AVAILABLE} are in S3 Deep Archive (HTTP 403 "
        "InvalidObjectState, 2026-10-31); the registered fitting period has "
        f"{c['fitting_weeks']} usable weeks instead of about 50. CFTC reports are assumed "
        "available three days after the report date; during the October to November 2025 US "
        "government shutdown the CFTC published late, so x1 was not known in real time in those "
        "weeks."
    )
    out["data_range"] = {"start": str(ois["date"].min()), "end": str(ois["date"].max())}
    return out
