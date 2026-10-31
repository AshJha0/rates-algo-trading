"""Adverse selection: markout curves, informed-flow share and a toxicity table for client tiering.

Plain idea: after we trade with a client, watch where the mid goes. If it keeps moving in the
client's favour, the client knew something. Averaged over many trades, that drift is the cost
of adverse selection, and it differs by client, size and product.

Definitions (dealer view; ``q`` our signed quantity, ``p`` the trade price, ``m(t)`` mid):

- dealer markout at horizon ``h``: ``q * (m(t + h) - p) * v`` in USD (includes the spread we
  earned), as written by the Java runner in ``fills.csv``;
- mid-to-mid markout in bp: ``sign(q) * (m(t + h) - m(t)) / |dP/dbp|``; negative means the price
  moved against us, that is, with the client;
- agreement at ``h``: the share of client trades where the client's direction matches the
  mid move. If a share ``s`` of trades is informed over that horizon and the rest is a coin
  toss, agreement is ``s + (1 - s) / 2``, so ``s_hat = 2 * agreement - 1`` (zero moves are
  dropped). This estimator assumes informed clients always trade the right way over ``h``,
  which is how ``SyntheticDay`` generates them; on real flow it is a lower bound.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

HORIZONS = ("1s", "10s", "1min", "5min")
DV01_PER_UNIT = {
    "SOFR 2Y": 1.92e-6,
    "SOFR 5Y": 4.52e-6,
    "SOFR 10Y": 8.11e-6,
    "SOFR 30Y": 17.29e-6,
    "UST 2Y": -1.9e-6,
    "UST 10Y": -8.15e-6,
    "TY": -7.15e-6,
}
VALUE_PER_UNIT = {
    "SOFR 2Y": 1.92e-4,
    "SOFR 5Y": 4.52e-4,
    "SOFR 10Y": 8.11e-4,
    "SOFR 30Y": 17.29e-4,
    "UST 2Y": 1e-4,
    "UST 10Y": 1e-4,
    "TY": 1e-4,
}
SIZE_BUCKETS = (0.0, 10.0, 50.0, 200.0, math.inf)


def client_fills(fills: pd.DataFrame) -> pd.DataFrame:
    """Client fills with mid-to-mid markouts in bp and size buckets added."""
    f = fills[fills["kind"] == "client"].copy()
    dv01 = f["instrument"].map(DV01_PER_UNIT).abs() * f["signed_quantity"].abs()
    value = f["instrument"].map(VALUE_PER_UNIT)
    sign = np.sign(f["signed_quantity"])
    for h in HORIZONS:
        # dealer markout = q (m_h - p) v; mid-to-mid = q (m_h - m) v = dealer - edge
        mid_move_usd = f[f"markout_{h}"] - f["edge"]
        f[f"bp_{h}"] = mid_move_usd / dv01
        f[f"move_{h}"] = mid_move_usd / (f["signed_quantity"].abs() * value) * sign
    f["size_mm"] = f["signed_quantity"].abs() / 1e8
    f["size_bucket"] = pd.cut(f["size_mm"], SIZE_BUCKETS, right=False).astype(str)
    return f


def curves(fills: pd.DataFrame, by: str) -> pd.DataFrame:
    """Mean mid-to-mid markout in bp per horizon, by ``tier``, ``size_bucket`` or ``instrument``."""
    f = client_fills(fills)
    cols = [f"bp_{h}" for h in HORIZONS]
    out = f.groupby(by)[cols].mean()
    out["fills"] = f.groupby(by).size()
    return out.reset_index()


def informed_share(
    fills: pd.DataFrame, horizon: str = "1min", by: str | None = None
) -> pd.DataFrame:
    """Agreement-based informed-share estimate, overall or per group, with a binomial SE."""
    f = client_fills(fills)
    # client direction is the opposite of ours; the client is right when we lose mid-to-mid
    move = f[f"bp_{horizon}"]
    f = f[move != 0].assign(agree=(move < 0).astype(float))
    groups = [("all", f)] if by is None else list(f.groupby(by))
    rows = []
    for key, g in groups:
        n = len(g)
        a = float(g["agree"].mean()) if n else float("nan")
        se = math.sqrt(a * (1 - a) / n) if n else float("nan")
        rows.append(
            {"group": key, "fills": n, "agreement": a, "informed_share": 2 * a - 1, "se": 2 * se}
        )
    return pd.DataFrame(rows)


def adverse_bp_by_tier(fills: pd.DataFrame, horizon: str = "1min") -> dict[int, float]:
    """Expected adverse move in bp per tier (positive = against us), for the model spread."""
    c = curves(fills, "tier")
    return {int(r["tier"]): float(-r[f"bp_{horizon}"]) for _, r in c.iterrows()}


def toxicity_table(fills: pd.DataFrame, rfqs: pd.DataFrame, horizon: str = "1min") -> pd.DataFrame:
    """Per client: RFQs, fills, hit rate, mean markout, informed share; and a suggested tier.

    Suggested tier: 1 if the informed-share estimate is below 0.10, 2 if below 0.30, else 3.
    The thresholds are declared, not fitted.
    """
    share = informed_share(fills, horizon, by="client_id").rename(columns={"group": "client_id"})
    curve = curves(fills, "client_id")[["client_id", f"bp_{horizon}"]]
    q = rfqs.groupby("client_id").agg(
        rfqs=("rfq_id", "size"), quoted=("quoted", "sum"), won=("won", "sum")
    )
    q["hit_rate"] = q["won"] / q["quoted"].where(q["quoted"] > 0)
    t = (
        q.reset_index()
        .merge(curve, on="client_id", how="left")
        .merge(share, on="client_id", how="left")
    )
    t["suggested_tier"] = np.where(
        t["informed_share"] < 0.10, 1, np.where(t["informed_share"] < 0.30, 2, 3)
    )
    return t


def load_fills(paths: list[Path]) -> pd.DataFrame:
    return pd.concat([pd.read_csv(p) for p in paths], ignore_index=True)


def summary(fills: pd.DataFrame, rfqs: pd.DataFrame) -> dict[str, Any]:
    return {
        "by_tier": curves(fills, "tier"),
        "by_size": curves(fills, "size_bucket"),
        "by_product": curves(fills, "instrument"),
        "informed_by_tier": informed_share(fills, "1min", by="tier"),
        "toxicity": toxicity_table(fills, rfqs),
    }
