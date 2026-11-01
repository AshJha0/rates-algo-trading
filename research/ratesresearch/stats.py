"""Statistics shared with the Java back-tester (``io.ratesalgo.backtest.stats.Stats``).

``SplitMix64`` reproduces the Java generator bit for bit, so the moving-block bootstrap draws
the same indices on both sides and the intervals agree exactly on the shared fixture
``data/fixtures/backtest/stats_crosscheck.json``. Welch's test uses the same Lanczos
log-gamma and continued fraction as Java; the two agree to about 1e-12.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

MASK = (1 << 64) - 1
GOLDEN = 0x9E3779B97F4A7C15


def _mix(z: int) -> int:
    z &= MASK
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK
    return z ^ (z >> 31)


class SplitMix64:
    """SplitMix64 with the Java side's ``nextLong``, ``nextDouble`` and ``nextInt``."""

    def __init__(self, seed: int) -> None:
        self.state = seed & MASK

    def next_long(self) -> int:
        """Next value as an unsigned 64-bit integer (the Java long's bit pattern)."""
        self.state = (self.state + GOLDEN) & MASK
        return _mix(self.state)

    def next_double(self) -> float:
        return (self.next_long() >> 11) * 2.0**-53

    def next_int(self, bound: int) -> int:
        if bound <= 0:
            raise ValueError(f"bound must be positive: {bound}")
        return (self.next_long() >> 1) % bound


@dataclass(frozen=True)
class Interval:
    estimate: float
    lower: float
    upper: float
    level: float


@dataclass(frozen=True)
class WelchResult:
    mean_a: float
    mean_b: float
    difference: float
    t: float
    df: float
    p: float


def mean(x: Sequence[float]) -> float:
    if not x:
        raise ValueError("empty sample")
    s = 0.0
    for v in x:
        s += v
    return s / len(x)


def variance(x: Sequence[float]) -> float:
    if len(x) < 2:
        raise ValueError("variance needs at least two values")
    m = mean(x)
    s = 0.0
    for v in x:
        s += (v - m) * (v - m)
    return s / (len(x) - 1)


TRADING_DAYS_PER_YEAR = 252


@dataclass(frozen=True)
class PnlRisk:
    """Risk figures of a daily P&L series, as ``Stats.pnlRisk`` in Java.

    Simulator consistency checks when the series comes from a back-test (DR-008).
    """

    days: int
    mean: float
    volatility: float
    sharpe: float
    annualised_sharpe: float
    max_drawdown: float


def pnl_risk(daily: Sequence[float]) -> PnlRisk:
    """Sample volatility, Sharpe ratio (zero risk-free rate) and maximum drawdown from zero."""
    m = mean(daily)
    vol = math.sqrt(variance(daily))
    sharpe = m / vol if vol > 0 else math.nan
    cumulative = 0.0
    peak = 0.0
    drawdown = 0.0
    for v in daily:
        cumulative += v
        peak = max(peak, cumulative)
        drawdown = max(drawdown, peak - cumulative)
    return PnlRisk(len(daily), m, vol, sharpe, sharpe * math.sqrt(TRADING_DAYS_PER_YEAR), drawdown)


def quantile(sorted_x: Sequence[float], q: float) -> float:
    """Type 7 quantile of an already sorted sequence."""
    if not sorted_x:
        raise ValueError("empty sample")
    h = (len(sorted_x) - 1) * q
    lo = math.floor(h)
    hi = min(lo + 1, len(sorted_x) - 1)
    return sorted_x[lo] + (h - lo) * (sorted_x[hi] - sorted_x[lo])


def block_bootstrap_mean_ci(
    x: Sequence[float], block_length: int, resamples: int, level: float, seed: int
) -> Interval:
    """Moving-block bootstrap percentile interval of the mean, identical to the Java one."""
    n = len(x)
    if n == 0:
        raise ValueError("empty sample")
    if not 1 <= block_length <= n:
        raise ValueError(f"block length {block_length} outside [1, {n}]")
    rng = SplitMix64(seed)
    starts = n - block_length + 1
    means: list[float] = []
    for _ in range(resamples):
        total = 0.0
        filled = 0
        while filled < n:
            start = rng.next_int(starts)
            j = 0
            while j < block_length and filled < n:
                total += x[start + j]
                filled += 1
                j += 1
        means.append(total / n)
    means.sort()
    alpha = (1 - level) / 2
    return Interval(mean(x), quantile(means, alpha), quantile(means, 1 - alpha), level)


_LANCZOS = (
    0.9999999999998099,
    676.5203681218851,
    -1259.1392167224028,
    771.3234287776531,
    -176.6150291621406,
    12.507343278686905,
    -0.13857109526572012,
    9.984369578019572e-6,
    1.5056327351493116e-7,
)


def log_gamma(x: float) -> float:
    """Lanczos log-gamma, the same series as the Java side."""
    if x <= 0:
        raise ValueError("log_gamma needs a positive argument")
    if x < 0.5:
        return math.log(math.pi / math.sin(math.pi * x)) - log_gamma(1 - x)
    z = x - 1
    s = _LANCZOS[0]
    for i in range(1, len(_LANCZOS)):
        s += _LANCZOS[i] / (z + i)
    t = z + 7.5
    return 0.5 * math.log(2 * math.pi) + (z + 0.5) * math.log(t) - t + math.log(s)


def _beta_cf(x: float, a: float, b: float) -> float:
    tiny, eps = 1e-300, 1e-15
    c = 1.0
    d = 1 - (a + b) * x / (a + 1)
    d = tiny if abs(d) < tiny else d
    d = 1 / d
    h = d
    for m in range(1, 1001):
        m2 = 2 * m
        aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
        d = 1 + aa * d
        d = tiny if abs(d) < tiny else d
        c = 1 + aa / c
        c = tiny if abs(c) < tiny else c
        d = 1 / d
        h *= d * c
        aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
        d = 1 + aa * d
        d = tiny if abs(d) < tiny else d
        c = 1 + aa / c
        c = tiny if abs(c) < tiny else c
        d = 1 / d
        delta = d * c
        h *= delta
        if abs(delta - 1) < eps:
            return h
    raise ArithmeticError("incomplete beta did not converge")


def regularized_incomplete_beta(x: float, a: float, b: float) -> float:
    if not 0 <= x <= 1 or a <= 0 or b <= 0:
        raise ValueError("incomplete beta outside its domain")
    if x in (0.0, 1.0):
        return x
    front = math.exp(
        log_gamma(a + b) - log_gamma(a) - log_gamma(b) + a * math.log(x) + b * math.log1p(-x)
    )
    if x < (a + 1) / (a + b + 2):
        return front * _beta_cf(x, a, b) / a
    return 1.0 - front * _beta_cf(1 - x, b, a) / b


def student_two_sided_p(t: float, df: float) -> float:
    return regularized_incomplete_beta(df / (df + t * t), df / 2.0, 0.5)


def welch(a: Sequence[float], b: Sequence[float]) -> WelchResult:
    ma, mb = mean(a), mean(b)
    va, vb = variance(a) / len(a), variance(b) / len(b)
    se2 = va + vb
    if se2 <= 0:
        raise ValueError("both samples have zero variance")
    t = (ma - mb) / math.sqrt(se2)
    df = se2 * se2 / (va * va / (len(a) - 1) + vb * vb / (len(b) - 1))
    return WelchResult(ma, mb, ma - mb, t, df, student_two_sided_p(t, df))


def holm(p: Sequence[float]) -> list[float]:
    """Holm step-down adjusted p-values in input order."""
    m = len(p)
    order = sorted(range(m), key=lambda i: (p[i], i))
    out = [0.0] * m
    running = 0.0
    for k, i in enumerate(order):
        running = max(running, min(1.0, (m - k) * p[i]))
        out[i] = running
    return out
