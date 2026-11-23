"""Competitor quote model consistent with zero expected profit for the winner.

Each of n competing dealers quotes a half-spread c = m exp(s Z) (lognormal, median m, log
standard deviation s); the client trades with the lowest. The winner's expected profit per
unit of DV01 is E[min c] - adverse - hedge, where ``adverse`` is the expected move of the mid
against the dealer after the trade and ``hedge`` the cost of laying the risk off, all in bp.
Zero profit fixes the median: m = (adverse + hedge) / E[min of n unit-median lognormals].

Because the lognormal scales, E[min] is linear in m, so the solution is closed form given the
order-statistic constant, which is computed by quadrature (no simulation).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
import numpy.typing as npt

FloatArray = npt.NDArray[np.float64]
_Z = np.linspace(-9.0, 9.0, 36001)
_PDF = np.exp(-0.5 * _Z**2) / math.sqrt(2.0 * math.pi)
_SF = 0.5 * (1.0 - np.vectorize(math.erf, otypes=[np.float64])(_Z / math.sqrt(2.0)))


def min_moment(log_sd: float, n: int, power: int = 1) -> float:
    """E[(min of n iid lognormal(0, log_sd))^power], by trapezoid quadrature over Z."""
    dens = n * _PDF * _SF ** (n - 1)
    y = np.exp(power * log_sd * _Z) * dens
    return float(np.sum((y[1:] + y[:-1]) * 0.5 * np.diff(_Z)))


def dealer_count_weights(
    min_dealers: int, max_dealers: int, response_probability: float, engines: int = 1
) -> dict[int, float]:
    """Distribution of the number of competitors that answer: the RFQ goes to a uniform number
    of dealers in [min, max], ``engines`` of them are the desk, each other dealer answers with
    the given probability (binomial)."""
    out: dict[int, float] = {}
    counts = range(min_dealers, max_dealers + 1)
    for d in counts:
        others = d - engines
        for k in range(others + 1):
            p = (
                math.comb(others, k)
                * response_probability**k
                * (1.0 - response_probability) ** (others - k)
            )
            out[k] = out.get(k, 0.0) + p / len(counts)
    return out


@dataclass(frozen=True)
class WinnerSpread:
    mean: float  # E[winning half-spread] per unit median, given at least one answer
    rms: float  # root mean square of the same
    p_no_answer: float


def winner_spread(log_sd: float, weights: dict[int, float]) -> WinnerSpread:
    p0 = weights.get(0, 0.0)
    tot = 1.0 - p0
    m1 = sum(w * min_moment(log_sd, n, 1) for n, w in weights.items() if n > 0) / tot
    m2 = sum(w * min_moment(log_sd, n, 2) for n, w in weights.items() if n > 0) / tot
    return WinnerSpread(m1, math.sqrt(m2), p0)


def zero_profit_median(
    adverse_bp: float, hedge_bp: float, log_sd: float, weights: dict[int, float]
) -> float:
    """Median competitor half-spread (bp) at which the winner breaks even on average."""
    return (adverse_bp + hedge_bp) / winner_spread(log_sd, weights).mean


def winner_profit(
    median_bp: float, adverse_bp: float, hedge_bp: float, log_sd: float, weights: dict[int, float]
) -> float:
    """Expected profit of the winning competitor in bp of DV01 (negative: a loss)."""
    return median_bp * winner_spread(log_sd, weights).mean - adverse_bp - hedge_bp
