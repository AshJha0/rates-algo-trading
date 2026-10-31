"""EDGE-E1: Treasury auction concession on the par curve (research/prereg/E1.yaml).

For each nominal coupon auction of tenor T on day A, the butterfly residual r_T of the par
curve (bp) is compared k business days before A and h days after it. Auctions are averaged
within ISO weeks; weeks are the unit of the block bootstrap (blocks of 2).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pandas as pd

from ratesresearch.edge import common

FIT = ("2015-01-01", "2020-12-31")
HOLD = ("2021-01-01", "2026-06-30")
BAND = {"low": (3, 2), "central": (5, 3), "high": (7, 5)}
NEIGHBOURS = {2: (1, 3), 3: (2, 5), 5: (3, 7), 7: (5, 10), 10: (7, 20), 20: (10, 30)}
BLOCK = 2
THRESHOLD_BP = 0.2


def residuals(curve: pd.DataFrame) -> pd.DataFrame:
    """Par-curve butterfly residual per tenor (bp); 30Y is the 20s30s spread."""
    out = pd.DataFrame({"date": curve["date"]})
    for t, (lo, hi) in NEIGHBOURS.items():
        w = (hi - t) / (hi - lo)
        out[f"r{t}"] = 100 * (curve[f"y{t}"] - (w * curve[f"y{lo}"] + (1 - w) * curve[f"y{hi}"]))
    out["r30"] = 100 * (curve["y30"] - curve["y20"])
    return out


def gsw_residuals(curve: pd.DataFrame, gsw: pd.DataFrame) -> pd.DataFrame:
    m = curve.merge(gsw, on="date", how="inner")
    out = pd.DataFrame({"date": m["date"]})
    for t in (2, 3, 5, 7, 10, 20, 30):
        out[f"r{t}"] = 100 * (m[f"y{t}"] - m[f"g{t}"])
    return out


def auction_changes(res: pd.DataFrame, auctions: pd.DataFrame, k: int, h: int) -> pd.DataFrame:
    """One row per auction found on a curve date: tenor, pre and post change (bp)."""
    pos = {d: i for i, d in enumerate(res["date"])}
    rows = []
    for day, tenor in zip(
        auctions["auction_date"].astype(str), auctions["tenor"].astype(int), strict=True
    ):
        i = pos.get(day)
        if i is None or i - k < 0 or i + h >= len(res):
            continue
        col = f"r{tenor}"
        v = res[col].to_numpy()
        rows.append(
            {
                "auction_date": day,
                "tenor": int(tenor),
                "pre": float(v[i] - v[i - k]),
                "post": float(v[i + h] - v[i]),
            }
        )
    return pd.DataFrame(rows)


def weekly(ch: pd.DataFrame) -> pd.DataFrame:
    iso = pd.to_datetime(ch["auction_date"]).dt.isocalendar()
    g = ch.assign(week=iso["year"].astype(str) + "-" + iso["week"].astype(str).str.zfill(2))
    return g.groupby("week", sort=True)[["pre", "post"]].mean().reset_index()


def _in(df: pd.DataFrame, rng: tuple[str, str], col: str = "auction_date") -> pd.DataFrame:
    return df[(df[col] >= rng[0]) & (df[col] <= rng[1])]


def load(root: Path) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    fx = root / "data/fixtures/edge"
    curve = pd.read_csv(fx / "par_curve.csv", dtype={"date": str})
    gsw = pd.read_csv(fx / "gsw_par.csv", dtype={"date": str})
    auctions = pd.read_csv(fx / "auctions.csv", dtype={"auction_date": str})
    return curve, gsw, auctions


def data_files(root: Path) -> list[Path]:
    fx = root / "data/fixtures/edge"
    return [fx / "par_curve.csv", fx / "gsw_par.csv", fx / "auctions.csv"]


def run(root: Path) -> dict[str, Any]:
    curve, gsw, auctions = load(root)
    res = residuals(curve)
    gres = gsw_residuals(curve, gsw)
    out: dict[str, Any] = {"band": {}, "tests": {}}
    for point, (k, h) in BAND.items():
        ch = auction_changes(res, auctions, k, h)
        band: dict[str, Any] = {}
        for label, rng in (("fitting", FIT), ("holdout", HOLD)):
            wk = weekly(_in(ch, rng))
            band[label] = {
                "auctions": len(_in(ch, rng)),
                "weeks": len(wk),
                "pre": common.mean_test(wk["pre"], BLOCK),
                "post": common.mean_test(wk["post"], BLOCK, seed=common.SEED + 1),
            }
        hold = _in(ch, HOLD)
        band["holdout_by_tenor"] = {
            str(t): {
                "auctions": len(g),
                "pre": float(g["pre"].mean()),
                "post": float(g["post"].mean()),
            }
            for t, g in hold.groupby("tenor")
        }
        gch = weekly(_in(auction_changes(gres, auctions, k, h), HOLD))
        band["gsw_holdout"] = {
            "weeks": len(gch),
            "pre": common.mean_test(gch["pre"], BLOCK),
            "post": common.mean_test(gch["post"], BLOCK, seed=common.SEED + 1),
        }
        out["band"][point] = band
        if point == "central":
            out["weekly_holdout"] = weekly(hold)
            out["auction_changes"] = ch
    c = out["band"]["central"]["holdout"]
    out["tests"]["E1a"] = {**c["pre"], "sign": 1, "threshold": THRESHOLD_BP, "unit": "auction week"}
    out["tests"]["E1b"] = {
        **c["post"],
        "sign": -1,
        "threshold": THRESHOLD_BP,
        "unit": "auction week",
    }
    out["data_range"] = {"start": str(curve["date"].iloc[0]), "end": str(curve["date"].iloc[-1])}
    return out
