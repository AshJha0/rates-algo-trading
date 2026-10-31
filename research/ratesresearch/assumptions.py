"""Declared (unfitted) presets: the synthetic market, the competing dealers, the hedge costs.

Each value here is an assumption, written with ``fitted_from: assumption``, ``sample_size: 0``
and no standard error, and with a rationale where a reader would ask for one. The win
probability slope is the assumption DR-008 is about; it is declared as a band in
``usd_rfq_competition`` and carried into ``usd_rfq_hit_rate`` by ``ratesresearch.hitrate``.
Changing a value here changes the synthetic world, so a change needs a new pre-registration
for any experiment that depends on it.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from ratesresearch.presets import param, write_preset

DECLARED_RANGE = {"start": "2026-10-31", "end": "2026-10-31"}

SLOPE_BAND = {"low": -1.25, "central": -2.5, "high": -5.0}
SLOPE_RATIONALE = (
    "Win-probability slope per bp of spread-to-cover. No public source shows losing quotes "
    "(DR-008), so the slope is declared: central -2.5 per bp (a quote 0.2 bp behind the cover "
    "loses 0.5 of its log-odds), low -1.25 (clients loyal, price matters less), high -5.0 "
    "(clients pick the best price almost always). Results are reported at all three points."
)


def _declared(name: str, method: str, params: dict[str, float], notes: str) -> dict[str, Any]:
    return {
        "name": name,
        "fitted_from": "assumption",
        "data_range": dict(DECLARED_RANGE),
        "sample_size": 0,
        "method": method,
        "parameters": {k: param(v) for k, v in params.items()},
        "notes": notes,
    }


def market() -> dict[str, Any]:
    return _declared(
        "usd_synthetic_market",
        "declared assumption, research/ratesresearch/assumptions.py::market",
        {
            "session_open_minute_et": 420,
            "session_close_minute_et": 1020,
            "level_vol_bp_per_sqrt_min": 0.35,
            "idio_vol_bp_per_sqrt_min": 0.06,
            "event_0_jump_bp": 2.5,
            "event_1_jump_bp": 1.2,
            "event_2_jump_bp": 0.0,
            "event_3_jump_bp": 0.0,
            "loading_2y": 1.10,
            "loading_5y": 1.05,
            "loading_10y": 1.00,
            "loading_30y": 0.85,
            "rate_2y_pct": 3.60,
            "rate_5y_pct": 3.65,
            "rate_10y_pct": 3.95,
            "rate_30y_pct": 4.30,
            "sofr_fixing_pct": 4.30,
            "future_price_0": 112.0,
            "basis_mean_reversion_per_min": 0.05,
            "basis_vol_points_per_sqrt_min": 0.004,
            "curve_input_interval_s": 5,
            "bond_book_updates_per_s": 0.2,
            "future_book_updates_per_s": 0.5,
            "bond_spread_ticks": 1,
            "future_spread_ticks": 1,
            "book_depth_median_mm": 50,
            "rfq_share_of_market": 0.6,
            "bond_rfq_ratio": 0.5,
            "mix_sofr_2y": 0.12,
            "mix_sofr_5y": 0.19,
            "mix_sofr_10y": 0.23,
            "mix_sofr_30y": 0.12,
            "mix_ust_2y": 0.14,
            "mix_ust_10y": 0.20,
            "bond_size_mu_ln_mm": 2.3,
            "clients_per_tier": 4,
            "tier_1_weight": 0.5,
            "tier_2_weight": 0.3,
            "tier_3_weight": 0.2,
            "tier_1_informed_share": 0.05,
            "tier_2_informed_share": 0.20,
            "tier_3_informed_share": 0.45,
            "informed_horizon_s": 60,
            "dealers_min": 2,
            "dealers_max": 5,
            "quote_latency_ms": 5,
            "client_decision_ms": 1500,
        },
        "Synthetic USD session for SyntheticDay. Swap tenor mix follows the DTCC tenor shares of "
        "usd_rfq_flow scaled to 0.66 of RFQs; Treasuries take the rest. Tier 1 is real money "
        "(mostly uninformed), tier 3 fast money (45 percent informed over 60 s). Informed share "
        "and horizon are what ratesresearch.markouts tries to recover.",
    )


def competition() -> dict[str, Any]:
    p = _declared(
        "usd_rfq_competition",
        "declared assumption, research/ratesresearch/assumptions.py::competition",
        {
            "median_half_spread_bp": 0.25,
            "tenor_slope": 0.30,
            "size_slope": 0.15,
            "bond_multiplier": 0.5,
            "lognormal_sigma": 0.35,
            "true_intercept": 1.2,
            "true_log_size_mm": -0.15,
            "true_tenor_years": -0.01,
            "true_fixing_window": 0.3,
            "true_dealer_count": -0.25,
        },
        "Competing dealers quote lognormal half-spreads around a median that widens with tenor "
        "and size; Treasuries quote half as wide in yield bp. The true_* coefficients are the "
        "logistic the label simulator uses; the slope is the band below.",
    )
    p["assumptions"] = {
        "spread_to_cover_bp": {"band": dict(SLOPE_BAND), "rationale": SLOPE_RATIONALE}
    }
    return p


def hedge_cost() -> dict[str, Any]:
    return _declared(
        "usd_hedge_cost",
        "declared assumption, research/ratesresearch/assumptions.py::hedge_cost",
        {
            "impact_ticks_per_lot": 0.0002,
            "max_impact_ticks": 1.0,
            "fee_per_lot": 1.5,
            "queue_trade_share": 0.6,
            "order_latency_ms": 2,
        },
        "Futures hedges cross half the book spread plus 0.2 tick per 1000 contracts (capped at 1 "
        "ticks) and pay 1.50 USD per contract in fees. In the queue model 60 percent of displayed "
        "size decreases are trades.",
    )


def write_all(presets_path: str | Path) -> None:
    for p in (market(), competition(), hedge_cost()):
        write_preset(presets_path, p)
