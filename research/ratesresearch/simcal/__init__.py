"""Calibration of the Round 3 simulator specification (pre-analysis note SIM3-CAL-001).

Modules: ``prints`` (DTCC swap prints: filters with counts, merge, cache), ``factors`` (curve
PCA and the swap-spread factor), ``micro`` (print noise, intraday volatility and seasonality,
arrivals, sizes, markouts), ``competitor`` (zero-profit competitor spread), ``synth``
(known-answer generators for the tests), ``calibrate`` (the command line that writes
``data/calibration/sim_v2_params.json``). Evidence: docs/research/SIM_V2_CALIBRATION.md;
simulator specification: docs/research/SIM_V2_DESIGN.md.
"""

from __future__ import annotations

DESK_TENORS: tuple[float, ...] = (2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0)
CAL_START = "2024-11-25"
CAL_END = "2025-12-31"
VAL_START = "2026-01-02"
VAL_END = "2026-06-30"
CURVE_CAL_START = "2015-01-02"
SEED = 20261123
RESAMPLES = 1000

__all__ = [
    "CAL_END",
    "CAL_START",
    "CURVE_CAL_START",
    "DESK_TENORS",
    "RESAMPLES",
    "SEED",
    "VAL_END",
    "VAL_START",
]
