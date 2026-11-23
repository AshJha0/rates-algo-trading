"""Known-answer generators for the estimators' tests. Nothing here is calibrated or market data.

``factor_changes`` draws daily curve changes from a factor model with given loadings and
factor standard deviations. ``prints`` draws swap prints in the layout of
``ratesresearch.simcal.prints`` from the model written at the top of ``micro``: a random-walk
mid, a half-spread paid on each print, and a drift of the mid after each trade in the trade's
direction that builds up exponentially to ``drift_bp``.
"""

from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch.simcal import DESK_TENORS
from ratesresearch.simcal.prints import SESSION_END_S, SESSION_START_S, annuity, size_bucket

FloatArray = npt.NDArray[np.float64]


def factor_changes(
    loadings: FloatArray, factor_sd: FloatArray, idio_sd: float, n_days: int, seed: int
) -> FloatArray:
    """(n_days, n_tenor) changes: factors (independent normals) times loadings plus noise."""
    rng = np.random.Generator(np.random.PCG64(seed))
    f = rng.standard_normal((n_days, len(factor_sd))) * factor_sd[None, :]
    e = rng.standard_normal((n_days, loadings.shape[0])) * idio_sd
    out: FloatArray = f @ loadings.T + e
    return out


def prints(
    n_days: int,
    seed: int,
    sigma_bp: float = 0.20,
    half_spread_bp: float = 0.25,
    drift_bp: float = 0.10,
    drift_tau_s: float = 20.0,
    per_minute: float = 0.5,
    half_spread_log_sd: float = 0.0,
    at_mid_share: float = 0.0,
    tenors: tuple[float, ...] = (10.0,),
    level_bp: float = 400.0,
    burst_s: float = 0.0,
) -> pd.DataFrame:
    """Synthetic prints. ``sigma_bp`` is per sqrt-minute; ``per_minute`` the arrival rate.

    ``half_spread_log_sd`` > 0 makes the half-spread lognormal with median ``half_spread_bp``;
    ``at_mid_share`` of prints are at the mid with no information (dealer-to-dealer style);
    ``burst_s`` > 0 puts a second, independent print a uniform 1..burst_s seconds after each
    print so that short horizons have reference prints.
    """
    rng = np.random.Generator(np.random.PCG64(seed))
    rows: list[pd.DataFrame] = []
    d0 = date(2025, 1, 6)
    for d in range(n_days):
        day = (d0 + timedelta(days=d)).isoformat()
        for tn in tenors:
            span = float(SESSION_END_S - SESSION_START_S)
            n = int(rng.poisson(per_minute * span / 60.0))
            t = np.sort(np.floor(rng.uniform(SESSION_START_S, SESSION_END_S, n)))
            if burst_s > 0:
                extra = t + np.floor(rng.uniform(1.0, burst_s + 1.0, n))
                t = np.sort(np.concatenate([t, extra[extra < SESSION_END_S]]))
                n = len(t)
            q = rng.choice(np.array([-1.0, 1.0]), n)
            x = half_spread_bp * np.exp(half_spread_log_sd * rng.standard_normal(n))
            informed = rng.uniform(size=n) >= at_mid_share
            x = np.where(informed, x, 0.0)
            w = np.concatenate([[0.0], np.cumsum(np.sqrt(np.diff(t) / 60.0) * rng.standard_normal(max(n - 1, 0)))])
            mid = level_bp + sigma_bp * w
            # drift from earlier trades: sum over i < m of q_i a (1 - exp(-(t_m - t_i) / tau))
            for m in range(1, n):
                lo = int(np.searchsorted(t, t[m] - 20.0 * drift_tau_s))
                dt = t[m] - t[lo:m]
                before = float(np.sum(q[:lo] * informed[:lo]))
                recent = float(np.sum(q[lo:m] * informed[lo:m] * (1.0 - np.exp(-dt / drift_tau_s))))
                mid[m] += drift_bp * (before + recent)
            rate = mid + q * x
            notional = np.exp(rng.normal(np.log(20.0), 1.0, n))
            dv01 = notional * 1e6 * annuity(rate, np.full(n, tn)) * 1e-4
            capped = np.zeros(n, dtype=bool)
            rows.append(
                pd.DataFrame(
                    {
                        "tenor": tn,
                        "t": t,
                        "rate_bp": rate,
                        "notional_mm": notional,
                        "capped": capped,
                        "group": 0,
                        "block": False,
                        "platform": "SYNTH",
                        "lag_days": 2,
                        "tenor_dev": 0.0,
                        "day": day,
                        "dv01": dv01,
                        "bucket": size_bucket(dv01, capped),
                        "side": q,
                    }
                )
            )
    return pd.concat(rows, ignore_index=True)


def all_tenors() -> tuple[float, ...]:
    return DESK_TENORS
