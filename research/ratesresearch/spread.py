"""Spread optimisation: expected capture times win probability, minus expected adverse selection
and hedge cost, per product and tenor; sensitivity to volatility and dealer count; tables across
the declared assumption band.

Formal statement (the same formula as ``ModelSpreadStrategy`` in Java):

    coverHat(n) = median(product, size) * exp(sigma * e(n - 1))
    P(s)        = logistic(b0 + b1 * (s - coverHat) + b2 ln q + b3 T + b4 F + b5 n)
    E[edge](s)  = P(s) * (s - adverse * volScale - hedgeCost)          in bp of DV01
    s*          = argmax over the grid 0, 0.01, ..., 2.0 bp

with ``e(k)`` the expected minimum of ``k`` standard normals. Volatility enters through the
adverse term: the expected adverse move scales with the volatility of the mid over the markout
horizon, so ``volScale`` is the ratio of the scenario's volatility to the fitting period's.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

from ratesresearch.presets import value

EXPECTED_MIN = {1: 0.0, 2: -0.5642, 3: -0.8463, 4: -1.0294, 5: -1.1630, 6: -1.2672, 7: -1.3522}
PRODUCTS = {
    "SOFR 2Y": ("swap", 2.0),
    "SOFR 5Y": ("swap", 5.0),
    "SOFR 10Y": ("swap", 10.0),
    "SOFR 30Y": ("swap", 30.0),
    "UST 2Y": ("bond", 2.0),
    "UST 10Y": ("bond", 10.0),
}


@dataclass(frozen=True)
class Model:
    coefficients: dict[str, float]
    slope: float
    median_bp: float
    tenor_slope: float
    size_slope: float
    bond_multiplier: float
    sigma: float

    @staticmethod
    def from_presets(
        hit: dict[str, Any], competition: dict[str, Any], slope: float | None = None
    ) -> Model:
        coefficients = {
            k: value(hit, k)
            for k in ("intercept", "log_size_mm", "tenor_years", "fixing_window", "dealer_count")
        }
        central = float(hit["assumptions"]["spread_to_cover_bp"]["band"]["central"])
        return Model(
            coefficients,
            central if slope is None else slope,
            value(competition, "median_half_spread_bp"),
            value(competition, "tenor_slope"),
            value(competition, "size_slope"),
            value(competition, "bond_multiplier"),
            value(competition, "lognormal_sigma"),
        )

    def median(self, kind: str, tenor: float, size_mm: float) -> float:
        m = (
            self.median_bp
            * (tenor / 10.0) ** self.tenor_slope
            * (size_mm / 50.0) ** self.size_slope
        )
        return float(m) * (1.0 if kind == "swap" else self.bond_multiplier)

    def win_probability(
        self, s: float, kind: str, tenor: float, size_mm: float, dealers: int, window: bool
    ) -> float:
        cover = self.median(kind, tenor, size_mm) * math.exp(
            self.sigma * EXPECTED_MIN[min(max(dealers - 1, 1), 7)]
        )
        c = self.coefficients
        z = (
            c["intercept"]
            + self.slope * (s - cover)
            + c["log_size_mm"] * math.log(size_mm)
            + c["tenor_years"] * tenor
            + (c["fixing_window"] if window else 0.0)
            + c["dealer_count"] * dealers
        )
        return 1.0 / (1.0 + math.exp(-z))


def expected_edge_bp(
    model: Model,
    s: float,
    product: str,
    size_mm: float,
    dealers: int,
    adverse_bp: float,
    hedge_bp: float,
    vol_scale: float = 1.0,
    window: bool = False,
) -> float:
    kind, tenor = PRODUCTS[product]
    p = model.win_probability(s, kind, tenor, size_mm, dealers, window)
    return p * (s - adverse_bp * vol_scale - hedge_bp)


def optimal_half_spread(
    model: Model,
    product: str,
    size_mm: float,
    dealers: int,
    adverse_bp: float,
    hedge_bp: float,
    vol_scale: float = 1.0,
    window: bool = False,
    max_bp: float = 2.0,
    step_bp: float = 0.01,
) -> tuple[float, float]:
    """(s*, expected edge at s*) on the grid; ties go to the narrower spread, as in Java."""
    best_s, best_e = 0.0, -math.inf
    for i in range(round(max_bp / step_bp) + 1):
        s = i * step_bp
        e = expected_edge_bp(
            model, s, product, size_mm, dealers, adverse_bp, hedge_bp, vol_scale, window
        )
        if e > best_e + 1e-12:
            best_s, best_e = s, e
    return best_s, best_e


def band_table(
    hit: dict[str, Any],
    competition: dict[str, Any],
    adverse_bp: float,
    hedge_bp: float,
    size_mm: float = 50.0,
    dealers: int = 4,
) -> list[dict[str, Any]]:
    """Optimal half-spread and expected edge per product at each band point."""
    rows = []
    band = hit["assumptions"]["spread_to_cover_bp"]["band"]
    for product in PRODUCTS:
        row: dict[str, Any] = {"product": product}
        for point in ("low", "central", "high"):
            m = Model.from_presets(hit, competition, float(band[point]))
            s, e = optimal_half_spread(m, product, size_mm, dealers, adverse_bp, hedge_bp)
            row[f"s_{point}"] = s
            row[f"edge_{point}"] = round(e, 4)
        rows.append(row)
    return rows


def sensitivity(
    hit: dict[str, Any],
    competition: dict[str, Any],
    product: str,
    adverse_bp: float,
    hedge_bp: float,
    vol_scales: tuple[float, ...] = (0.5, 1.0, 1.5, 2.0),
    dealer_counts: tuple[int, ...] = (2, 3, 4, 5),
) -> list[dict[str, Any]]:
    """Optimal half-spread by volatility scale (rows) and dealer count (columns), central band."""
    m = Model.from_presets(hit, competition)
    rows = []
    for v in vol_scales:
        row: dict[str, Any] = {"vol_scale": v}
        for n in dealer_counts:
            row[f"dealers_{n}"] = optimal_half_spread(m, product, 50.0, n, adverse_bp, hedge_bp, v)[
                0
            ]
        rows.append(row)
    return rows
