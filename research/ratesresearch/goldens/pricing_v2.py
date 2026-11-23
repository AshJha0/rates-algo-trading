"""Golden values for pricing v2 (docs/research/PRICING_V2.md Section 9).

Valuation date 2026-11-02. The build set of the SOFR curve is declared here by hand (labels,
dates, pillars), not derived by the rule the Java side uses, so the Java layout is checked
against a second statement of it. Quotes are declared levels, the same function of maturity as
the Java test market.

What is independent of the Java code, and what is not:

- Independent. Every instrument price on a given curve is computed by QuantLib objects
  (``OvernightIndexedSwap``, ``OvernightIndexFuture``, ``FixedRateBond`` and ``BondFunctions``),
  on a ``DiscountCurve`` with one node per calendar day, so the interpolation between pillars
  reaches QuantLib only as discount factors. The curves are solved by SciPy's ``root`` (hybrid
  Powell), not by the Java solvers. Risk is by central bumps of the quotes and a full re-solve.
- Independent. Monotone-convex forwards are QuantLib's ``ConvexMonotone`` interpolation with
  quadraticity 0, monotonicity 1 and no positivity forcing, which its documentation states is
  the basic Hagan-West method.
- Independent in derivation. The tension spline is solved here from its differential equation
  (s'''' = p^2 s'' on each segment, C2 joins, natural ends) as one dense linear system; the Java
  class uses the tridiagonal second-derivative form. Same definition, different algebra.
- Independent. The in-arrears Hull-White futures adjustments are checked two ways: against a
  numerical double integral of the short-rate covariance (``hw_numeric``), which shares only the
  model with the closed forms; and, for the compounded contract, against QuantLib's
  ``HullWhite.convexityBias``, the Kirikos-Novak bias of a rate fixed at the start of its period,
  from which the in-arrears value differs by exactly the variance of the integrated rate over
  the period itself.
- Not independent (the same formula typed a second time): the closed-form adjustments that go
  into the futures quotes (``hw_compounded_ca``, ``hw_average_ca``), the Hagan-West formula used
  to build the monotone-convex curve itself (QuantLib checks its forwards afterwards), linear log
  discount factors, and the hat weights that map build quotes onto risk tenors.
"""

from __future__ import annotations

import math
import platform
from dataclasses import dataclass
from typing import Any

import numpy as np
import QuantLib as ql  # type: ignore[import-not-found,import-untyped,unused-ignore]
from scipy.integrate import dblquad  # type: ignore[import-untyped,unused-ignore]
from scipy.optimize import root  # type: ignore[import-untyped,unused-ignore]

from ratesresearch.goldens.generator import (
    BP,
    CALENDARS,
    CONVS,
    HW_A,
    HW_SIGMA,
    Inst,
    JsonDict,
    hull_white_ca,
    iso,
    ois_inst,
    ois_swap,
    qd,
)

V2_GOLDEN_VERSION = 1
V2_TODAY_ISO = "2026-11-02"
TENSION = 0.5
RISK_TENOR_YEARS = [1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0]
SWAP_TENOR_MONTHS = [12, 24, 36, 60, 84, 120, 240, 360]
SCHEMES = ["LINEAR_LOG_DF", "STEP_THEN_MONOTONE_CONVEX", "STEP_THEN_TENSION_SPLINE"]
STEP_PILLARS = 3

# (kind, year, month, pillar): the front of the build set on 2026-11-02, by hand.
# The overnight rate holds to the effective date of the December meeting (decision 2026-12-09);
# the December one-month contract sets the level after it, up to the effective date of the
# January meeting (decision 2027-01-27); that step falls late in January, so the February
# contract sets the level after it, up to the end of February.
FRONT: list[tuple[str, int, int, str]] = [
    ("on", 0, 0, "2026-12-10"),
    ("future1m", 2026, 12, "2027-01-28"),
    ("future1m", 2027, 2, "2027-03-01"),
    ("future3m", 2026, 12, "2027-03-17"),
    ("future3m", 2027, 3, "2027-06-16"),
    ("future3m", 2027, 6, "2027-09-15"),
]

# id, coupon, dated date, maturity, declared z-spread to the SOFR curve (decimal) that sets the
# declared clean price: the four on-the-run notes of the reference data on the valuation date
BONDS: list[tuple[str, float, str, str, float]] = [
    ("UST-2Y", 0.03375, "2026-10-31", "2028-10-31", -0.0012),
    ("UST-5Y", 0.03625, "2026-10-31", "2031-10-31", -0.0020),
    ("UST-10Y", 0.04125, "2026-08-15", "2036-08-15", -0.0028),
    ("UST-30Y", 0.04625, "2026-08-15", "2056-08-15", -0.0045),
]


def swap_rate(t: float) -> float:
    """Declared par swap rate by tenor in years (SofrMarket.swapRate)."""
    return (
        0.0415
        - 0.0085 * math.exp(-t / 9.0)
        + 0.0045 * math.exp(-t / 0.9)
        - 0.0012 * math.exp(-((t - 30.0) ** 2) / 60.0)
    )


def futures_rate(years: float) -> float:
    """Declared futures rate by years to the pillar (SofrMarket.rate)."""
    return 0.0330 + 0.0060 * math.exp(-years / 0.45)


# ---------------------------------------------------------------- futures convexity


def _integrated_variance(t: float) -> float:
    """Var of the integral of the short rate over [0, t] (HullWhite.integratedVariance)."""
    if t <= 0.0:
        return 0.0
    x = HW_A * t
    if x < 0.1:
        # power series of [x - 2 (1 - e^-x) + (1 - e^-2x) / 2] / x^3, as the Java method
        phi, power, factorial, two_power = 0.0, 1.0, 6.0, 4.0
        for n in range(3, 17):
            c = (2.0 - two_power) / factorial
            phi += (c if n % 2 == 0 else -c) * power
            power *= x
            factorial *= n + 1
            two_power *= 2.0
    else:
        phi = (x + 2.0 * math.expm1(-x) - 0.5 * math.expm1(-2.0 * x)) / (x * x * x)
    return HW_SIGMA * HW_SIGMA * t * t * t * phi


def hw_compounded_ca(t1: float, t2: float) -> float:
    """HullWhite.compoundedFuturesConvexity typed again (closed form)."""
    a, s = HW_A, HW_SIGMA
    lo = max(t1, 0.0)
    tau = t2 - lo
    b = -math.expm1(-a * tau) / a
    rate_var = s * s * (-math.expm1(-2.0 * a * lo) / (2.0 * a))
    period_var = b * b * rate_var + _integrated_variance(tau)
    z = 0.5 * (_integrated_variance(t2) - _integrated_variance(lo) + period_var)
    return z / (t2 - t1)


def hw_average_ca(t1: float, t2: float) -> float:
    """HullWhite.averageFuturesConvexity typed again (closed form)."""
    lo = max(t1, 0.0)
    return 0.5 * (_integrated_variance(t2) - _integrated_variance(lo)) / (t2 - t1)


def _variance_numeric(lo: float, hi: float) -> float:
    """Var of the integral of the short rate over [lo, hi] by quadrature of its covariance.

    Cov(r(s), r(u)) = sigma^2 (exp(-a |s - u|) - exp(-a (s + u))) / (2 a) for the Hull-White
    short rate started at a known value at time zero. Integrated over the triangle s < u and
    doubled.
    """
    if hi <= lo:
        return 0.0
    a, sg = HW_A, HW_SIGMA

    def cov(s_: float, u: float) -> float:
        return sg * sg * (math.exp(-a * (u - s_)) - math.exp(-a * (s_ + u))) / (2.0 * a)

    value, _ = dblquad(cov, lo, hi, lambda u: lo, lambda u: u, epsabs=1e-18, epsrel=1e-13)
    return 2.0 * float(value)


def hw_numeric(t1: float, t2: float, compounded: bool) -> float:
    """The in-arrears adjustments from numerical variances (independent of the closed forms)."""
    drift = 0.5 * (_variance_numeric(0.0, t2) - _variance_numeric(0.0, t1))
    if compounded:
        return (drift + 0.5 * _variance_numeric(t1, t2)) / (t2 - t1)
    return drift / (t2 - t1)


def sofr_future(index: Any, today: Any, year: int, month: int, three: bool) -> Inst:
    """A SOFR future priced by QuantLib with the in-arrears convexity adjustment."""
    dc = ql.Actual365Fixed()
    if three:
        start = ql.Date.nthWeekday(3, ql.Wednesday, month, year)
        nxt = ql.Date(1, month, year) + ql.Period(3, ql.Months)
        end = ql.Date.nthWeekday(3, ql.Wednesday, nxt.month(), nxt.year())
        avg = ql.RateAveraging.Compound
    else:
        start = ql.Date(1, month, year)
        end = ql.Date.endOfMonth(start) + 1
        avg = ql.RateAveraging.Simple
    t1, t2 = dc.yearFraction(today, start), dc.yearFraction(today, end)
    ca = hw_compounded_ca(t1, t2) if three else hw_average_ca(t1, t2)
    fut = ql.OvernightIndexFuture(index, start, end, ql.QuoteHandle(ql.SimpleQuote(ca)), avg)
    code = ("SR3 " if three else "SR1 ") + f"{year}-{month:02d}"

    def implied() -> float:
        return float(fut.NPV())

    return Inst(
        "future3m" if three else "future1m",
        code,
        code,
        end,
        0.0,
        implied,
        {"year": year, "month": month, "convexity": ca, "start": iso(start), "end": iso(end)},
    )


# ---------------------------------------------------------------- interpolation


def _hagan_west_integral(u: float, g0: float, g1: float) -> float:
    if g0 == 0.0 and g1 == 0.0:
        return 0.0
    r1 = (g0 < 0 and -0.5 * g0 <= g1 <= -2.0 * g0) or (g0 > 0 and -0.5 * g0 >= g1 >= -2.0 * g0)
    r2 = (g0 < 0 and g1 > -2.0 * g0) or (g0 > 0 and g1 < -2.0 * g0)
    r3 = (g0 > 0 and 0 > g1 > -0.5 * g0) or (g0 < 0 and 0 < g1 < -0.5 * g0)
    if r1:
        return g0 * (u - 2 * u * u + u**3) + g1 * (-u * u + u**3)
    if r2:
        eta = (g1 + 2 * g0) / (g1 - g0)
        if u <= eta:
            return g0 * u
        return g0 * u + (g1 - g0) * (u - eta) ** 3 / (3 * (1 - eta) ** 2)
    if r3:
        eta = 3 * g1 / (g1 - g0)
        if u < eta:
            return g1 * u + (g0 - g1) * (eta / 3 - (eta - u) ** 3 / (3 * eta * eta))
        return g1 * u + (g0 - g1) * eta / 3
    s = g0 + g1
    eta = g1 / s
    a = -g0 * g1 / s
    if u < eta:
        return a * u + (g0 - a) * (eta / 3 - (eta - u) ** 3 / (3 * eta * eta))
    w = 1 - eta
    k = 0.0 if w <= 0 else (u - eta) ** 3 / (3 * w * w)
    return a * u + (g0 - a) * eta / 3 + (g1 - a) * k


def monotone_convex(t: np.ndarray, y: np.ndarray, x: np.ndarray) -> np.ndarray:
    """Hagan-West monotone convex on log discount factors (a retyping of the Java class)."""
    n = len(t)
    last = n - 1
    fd = np.zeros(n)
    fd[1:] = -(y[1:] - y[:-1]) / (t[1:] - t[:-1])
    f = np.zeros(n)
    if last == 1:
        f[:] = fd[1]
    else:
        for k in range(1, last):
            hk = t[k] - t[k - 1]
            hk1 = t[k + 1] - t[k]
            f[k] = (hk * fd[k + 1] + hk1 * fd[k]) / (hk + hk1)
        f[0] = fd[1] - 0.5 * (f[1] - fd[1])
        f[last] = fd[last] - 0.5 * (f[last - 1] - fd[last])
    out = np.empty(len(x))
    seg = np.clip(np.searchsorted(t, x, side="left"), 1, last)
    for i, (xi, k) in enumerate(zip(x, seg, strict=True)):
        h = t[k] - t[k - 1]
        u = (xi - t[k - 1]) / h
        g = _hagan_west_integral(float(u), float(f[k - 1] - fd[k]), float(f[k] - fd[k]))
        out[i] = y[k - 1] - h * (fd[k] * u + g)
    return out


def tension_spline(t: np.ndarray, y: np.ndarray, x: np.ndarray, p: float) -> np.ndarray:
    """Exponential tension spline with natural ends, from its differential equation.

    On segment k, with xi the distance from its left node,
    s(xi) = y_{k-1} + b xi + c sinh(p xi) + d (cosh(p xi) - 1), the general solution of
    s'''' = p^2 s'' through the left node. The unknowns b, c, d of every segment are fixed by:
    the right node value, continuity of s' and s'' at interior nodes, and s'' = 0 at both ends.
    """
    m = len(t) - 1
    h = t[1:] - t[:-1]
    a = np.zeros((3 * m, 3 * m))
    rhs = np.zeros(3 * m)
    row = 0
    for k in range(m):
        b, c, d = 3 * k, 3 * k + 1, 3 * k + 2
        sh, ch = math.sinh(p * h[k]), math.cosh(p * h[k])
        a[row, b], a[row, c], a[row, d] = h[k], sh, ch - 1.0
        rhs[row] = y[k + 1] - y[k]
        row += 1
        if k + 1 < m:
            nb, nc = 3 * (k + 1), 3 * (k + 1) + 1
            nd = 3 * (k + 1) + 2
            # s' continuous: b + c p cosh + d p sinh = b' + c' p
            a[row, b], a[row, c], a[row, d] = 1.0, p * ch, p * sh
            a[row, nb], a[row, nc] = -1.0, -p
            row += 1
            # s'' continuous: c p^2 sinh + d p^2 cosh = d' p^2
            a[row, c], a[row, d], a[row, nd] = sh, ch, -1.0
            row += 1
    a[row, 2] = 1.0  # s''(t_0) = d_0 p^2 = 0
    row += 1
    sh, ch = math.sinh(p * h[m - 1]), math.cosh(p * h[m - 1])
    a[row, 3 * (m - 1) + 1], a[row, 3 * (m - 1) + 2] = sh, ch
    coef = np.linalg.solve(a, rhs)
    seg = np.clip(np.searchsorted(t, x, side="left"), 1, m) - 1
    xi = x - t[seg]
    return np.asarray(
        y[seg]
        + coef[3 * seg] * xi
        + coef[3 * seg + 1] * np.sinh(p * xi)
        + coef[3 * seg + 2] * (np.cosh(p * xi) - 1.0)
    )


def log_df(scheme: str, t: np.ndarray, y: np.ndarray, x: np.ndarray) -> np.ndarray:
    """ln P at times x for nodes (t, y); t[0] = 0, y[0] = 0. Flat forward past the last node."""
    out = np.empty(len(x))
    inside = x <= t[-1]
    xin = x[inside]
    if scheme == "LINEAR_LOG_DF":
        out[inside] = np.interp(xin, t, y)
    else:
        s = STEP_PILLARS
        front = xin <= t[s]
        res = np.empty(len(xin))
        res[front] = np.interp(xin[front], t[: s + 1], y[: s + 1])
        back = xin[~front]
        if scheme == "STEP_THEN_MONOTONE_CONVEX":
            res[~front] = monotone_convex(t[s:], y[s:], back)
        else:
            res[~front] = tension_spline(t[s:], y[s:], back, TENSION)
        out[inside] = res
    slope = (y[-1] - y[-2]) / (t[-1] - t[-2])
    out[~inside] = y[-1] + slope * (x[~inside] - t[-1])
    return out


# ---------------------------------------------------------------- the curve


@dataclass
class V2Curve:
    """The build set priced by QuantLib on a relinkable daily-node curve."""

    today: Any
    handle: Any
    index: Any
    insts: list[Inst]
    days: list[Any]
    times: np.ndarray
    pillar_times: np.ndarray

    def link(self, scheme: str, y: np.ndarray) -> None:
        t = np.concatenate(([0.0], self.pillar_times))
        yy = np.concatenate(([0.0], y))
        dfs = np.exp(log_df(scheme, t, yy, self.times))
        curve = ql.DiscountCurve(self.days, [float(v) for v in dfs], ql.Actual365Fixed())
        curve.enableExtrapolation()
        self.handle.linkTo(curve)

    def residuals(self, scheme: str, y: np.ndarray) -> np.ndarray:
        self.link(scheme, y)
        return np.array(
            [
                (i.implied() - i.quote) / (100.0 if i.kind.startswith("future") else 1.0)
                for i in self.insts
            ]
        )

    def jacobian(self, scheme: str, y: np.ndarray) -> np.ndarray:
        jac = np.empty((len(y), len(y)))
        for j in range(len(y)):
            e = np.zeros(len(y))
            e[j] = 1e-6
            jac[:, j] = (self.residuals(scheme, y + e) - self.residuals(scheme, y - e)) / 2e-6
        return jac

    def solve(self, scheme: str, start: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """Solves from scratch; returns the log discount factors and the Jacobian there."""
        sol = root(lambda v: self.residuals(scheme, v), start, method="hybr", tol=1e-15)
        y = np.asarray(sol.x, dtype=float)
        # the hybrid method stops near 1e-11; finish with Newton on a central-difference Jacobian
        jac = self.jacobian(scheme, y)
        return self.resolve(scheme, y, jac), jac

    def resolve(self, scheme: str, start: np.ndarray, jac: np.ndarray) -> np.ndarray:
        """Newton from a nearby solution with a fixed Jacobian (the quotes moved by a bump)."""
        y = start.copy()
        for _ in range(4):
            y = y - np.linalg.solve(jac, self.residuals(scheme, y))
        self.link(scheme, y)
        return y


def make_curve() -> V2Curve:
    today = qd(V2_TODAY_ISO)
    handle = ql.RelinkableYieldTermStructureHandle()
    index = ql.Sofr(handle)
    conv = CONVS["USD"]
    cal = CALENDARS["US-SOFR"]
    dc = ql.Actual360()
    insts: list[Inst] = []
    pillars: list[Any] = []
    for kind, year, month, pillar_iso in FRONT:
        pillar = qd(pillar_iso)
        years = (pillar - today) / 365.25
        if kind == "on":
            end = cal.advance(today, 1, ql.Days)

            def implied(end: Any = end) -> float:
                c = handle.currentLink()
                return float((1.0 / c.discount(end) - 1.0) / dc.yearFraction(today, end))

            insts.append(Inst("on", "SOFR ON", "ON", pillar, 0.0390, implied, {"end": iso(end)}))
        else:
            inst = sofr_future(index, today, year, month, kind == "future3m")
            inst.quote = 100.0 * (1.0 - futures_rate(years) - inst.spec["convexity"])
            inst.pillar = pillar
            insts.append(inst)
        pillars.append(pillar)
    for months in SWAP_TENOR_MONTHS:
        tenor = f"{months // 12}Y"
        inst = ois_inst(conv, index, today, months, tenor, swap_rate(months / 12.0))
        insts.append(inst)
        pillars.append(inst.pillar)
    last = pillars[-1]
    days = [today + k for k in range(int(last - today) + 30)]
    times = np.array([(d - today) / 365.0 for d in days])
    pillar_times = np.array([(p - today) / 365.0 for p in pillars])
    return V2Curve(today, handle, index, insts, days, times, pillar_times)


def par_rate(curve: V2Curve, months: int) -> float:
    conv = CONVS["USD"]
    start = conv.schedule_cal.advance(curve.today, conv.spot_lag, ql.Days)
    end = start + ql.Period(months, ql.Months)
    swap = ois_swap(conv, curve.index, start, end, 0.0, 1.0, True, telescopic=True)
    swap.setPricingEngine(ql.DiscountingSwapEngine(curve.handle))
    return float(swap.fairRate())


def overnight_forward(curve: V2Curve, date: Any) -> float:
    c = curve.handle.currentLink()
    return float((c.discount(date) / c.discount(date + 1) - 1.0) * 360.0)


# ---------------------------------------------------------------- bonds


def make_bond(coupon: float, dated: str, maturity: str) -> Any:
    cal = CALENDARS["US-SIFMA"]
    mat = qd(maturity)
    sched = ql.Schedule(
        qd(dated),
        mat,
        ql.Period(6, ql.Months),
        cal,
        ql.Unadjusted,
        ql.Unadjusted,
        ql.DateGeneration.Backward,
        ql.Date.isEndOfMonth(mat),
    )
    return ql.FixedRateBond(
        1,
        100.0,
        sched,
        [coupon],
        ql.ActualActual(ql.ActualActual.ISMA),
        ql.Following,
        100.0,
        qd(dated),
        cal,
        ql.Period(),
        cal,
        ql.Unadjusted,
        False,
    )


def bond_dirty(bond: Any, curve: V2Curve, z: float, settle: Any) -> float:
    return float(
        ql.BondFunctions.dirtyPrice(
            bond,
            curve.handle.currentLink(),
            z,
            ql.Actual365Fixed(),
            ql.Continuous,
            ql.NoFrequency,
            settle,
        )
    )


def hat_weights(years: float) -> list[float]:
    """Share of a maturity on each risk tenor: flat outside, linear between (declared rule)."""
    w = [0.0] * len(RISK_TENOR_YEARS)
    if years <= RISK_TENOR_YEARS[0]:
        w[0] = 1.0
    elif years >= RISK_TENOR_YEARS[-1]:
        w[-1] = 1.0
    else:
        k = next(i for i, t in enumerate(RISK_TENOR_YEARS) if t >= years)
        a = (years - RISK_TENOR_YEARS[k - 1]) / (RISK_TENOR_YEARS[k] - RISK_TENOR_YEARS[k - 1])
        w[k - 1], w[k] = 1.0 - a, a
    return w


# ---------------------------------------------------------------- the file


def generate_v2() -> JsonDict:
    ql.IndexManager.instance().clearHistories()
    saved = ql.Settings.instance().evaluationDate
    today = qd(V2_TODAY_ISO)
    ql.Settings.instance().evaluationDate = today
    try:
        return _generate(today)
    finally:
        ql.Settings.instance().evaluationDate = saved


def _generate(today: Any) -> JsonDict:
    curve = make_curve()
    n = len(curve.insts)
    start = -0.039 * curve.pillar_times
    inputs: list[JsonDict] = [
        {
            "label": i.label,
            "kind": i.kind,
            "pillar": iso(i.pillar),
            "quote": i.quote,
            **{
                k: v
                for k, v in i.spec.items()
                if k in ("year", "month", "convexity", "tenor_months")
            },
        }
        for i in curve.insts
    ]

    off_curve_months = [9, 18, 48, 72, 96, 144, 180, 300]
    extra_futures = [(2027, 1, False), (2027, 4, False), (2027, 9, True)]
    grid = [today + 14 + 365 * yr for yr in (0, 1, 2, 3, 5, 7, 9, 11, 16, 21, 26)]
    solved: dict[str, np.ndarray] = {}
    jacobians: dict[str, np.ndarray] = {}
    curves_out: list[JsonDict] = []
    for scheme in SCHEMES:
        y, jacobians[scheme] = curve.solve(scheme, start)
        solved[scheme] = y
        err = float(np.max(np.abs(curve.residuals(scheme, y))))
        futures = []
        for year, month, three in extra_futures:
            fut = sofr_future(curve.index, today, year, month, three)
            futures.append({"code": fut.label, **fut.spec, "price": fut.implied()})
        curves_out.append(
            {
                "interpolation": scheme,
                "tension": TENSION,
                "step_pillars": 0 if scheme == "LINEAR_LOG_DF" else STEP_PILLARS,
                "pillar_dates": [iso(i.pillar) for i in curve.insts],
                "discount_factors": [float(math.exp(v)) for v in y],
                "max_reprice_error": err,
                "par_rates": [
                    {"tenor_months": m, "par_rate": par_rate(curve, m)} for m in off_curve_months
                ],
                "futures": futures,
                "overnight_forwards": [
                    {"date": iso(d), "forward": overnight_forward(curve, d)} for d in grid
                ],
            }
        )

    # monotone convex forwards by QuantLib's interpolation, on the solved nodes past the join
    y_mc = solved["STEP_THEN_MONOTONE_CONVEX"]
    t_all = np.concatenate(([0.0], curve.pillar_times))
    y_all = np.concatenate(([0.0], y_mc))
    t_mc = t_all[STEP_PILLARS:]
    fd = [0.0] + [
        float(-(y_all[STEP_PILLARS + k] - y_all[STEP_PILLARS + k - 1]) / (t_mc[k] - t_mc[k - 1]))
        for k in range(1, len(t_mc))
    ]
    interp = ql.ConvexMonotoneInterpolation([float(v) for v in t_mc], fd, 0.0, 1.0, False)
    mc_forwards = [
        {"time": x, "instantaneous_forward": float(interp(x, True))}
        for x in [0.4, 0.7, 1.0, 1.3, 1.9, 2.6, 4.2, 6.1, 8.5, 12.0, 17.5, 24.0, 29.5]
    ]

    # the tension spline against a second, variational statement of it: among interpolants on a
    # fine grid, the one that minimises the sum of (s'')^2 + p^2 (s')^2. Recorded as evidence that
    # the closed form above is the tension spline; not compared by the Java test.
    y_ts = solved["STEP_THEN_TENSION_SPLINE"]
    variational = _variational_check(
        t_all[STEP_PILLARS:], np.concatenate(([0.0], y_ts))[STEP_PILLARS:]
    )

    # Hull-White in-arrears adjustments: closed form, numerical variances, and QuantLib's bias
    # of a rate fixed at the start of the period (z_KN), which the compounded value exceeds by
    # the variance of the integrated rate over the period: tau * closed = z_KN + V(tau)
    hw_rows: list[JsonDict] = []
    for i in curve.insts:
        if i.kind.startswith("future"):
            t1 = ql.Actual365Fixed().yearFraction(today, qd(i.spec["start"]))
            t2 = ql.Actual365Fixed().yearFraction(today, qd(i.spec["end"]))
            three = i.kind == "future3m"
            rate = (100.0 - i.quote) / 100.0
            bias = float(ql.HullWhite.convexityBias(i.quote, t1, t2, HW_SIGMA, HW_A))
            hw_rows.append(
                {
                    "code": i.label,
                    "compounded": three,
                    "t1": t1,
                    "t2": t2,
                    "closed_form": i.spec["convexity"],
                    "numerical": hw_numeric(t1, t2, three),
                    "hull_forward_looking": hull_white_ca(t1, t2),
                    "quantlib_kirikos_novak_z": -math.log(1.0 - bias / (rate + 1.0 / (t2 - t1))),
                    "period_variance_numerical": _variance_numeric(0.0, t2 - t1),
                }
            )

    # bonds on the default curve: z-spread, and risk by bumping every build quote
    scheme = "STEP_THEN_TENSION_SPLINE"
    base_y = solved[scheme]
    curve.link(scheme, base_y)
    cal = CALENDARS["US-SIFMA"]
    settle = cal.advance(today, 1, ql.Days)
    bonds = []
    for bond_id, coupon, dated, maturity, declared in BONDS:
        b = make_bond(coupon, dated, maturity)
        accrued = float(ql.BondFunctions.accruedAmount(b, settle))
        clean = round(bond_dirty(b, curve, declared, settle) - accrued, 6)
        z = float(
            ql.BondFunctions.zSpread(
                b,
                ql.BondPrice(clean, ql.BondPrice.Clean),
                curve.handle.currentLink(),
                ql.Actual365Fixed(),
                ql.Continuous,
                ql.NoFrequency,
                settle,
                1e-15,
                200,
                0.0,
            )
        )
        bonds.append((bond_id, coupon, dated, maturity, b, clean, accrued, z))
    quote_dv01 = [[0.0] * n for _ in bonds]
    base_quotes = [i.quote for i in curve.insts]
    for j, inst in enumerate(curve.insts):
        per_rate = -100.0 if inst.kind.startswith("future") else 1.0
        vals: list[list[float]] = []
        for sgn in (1.0, -1.0):
            inst.quote = base_quotes[j] + sgn * BP * per_rate
            curve.resolve(scheme, base_y, jacobians[scheme])
            vals.append([bond_dirty(b[4], curve, b[7], settle) for b in bonds])
        inst.quote = base_quotes[j]
        for k in range(len(bonds)):
            quote_dv01[k][j] = (vals[0][k] - vals[1][k]) / 2.0
    curve.resolve(scheme, base_y, jacobians[scheme])
    bump_error = float(np.max(np.abs(curve.residuals(scheme, base_y))))
    hats = [
        hat_weights(
            i.spec["tenor_months"] / 12.0 if i.kind == "ois" else (i.pillar - today) / 365.25
        )
        for i in curve.insts
    ]
    bonds_out: list[JsonDict] = []
    for k, (bond_id, coupon, dated, maturity, b, clean, accrued, z) in enumerate(bonds):
        up = bond_dirty(b, curve, z + BP, settle)
        down = bond_dirty(b, curve, z - BP, settle)
        ladder = [
            sum(quote_dv01[k][j] * hats[j][r] for j in range(n))
            for r in range(len(RISK_TENOR_YEARS))
        ]
        bonds_out.append(
            {
                "id": bond_id,
                "coupon": coupon,
                "dated": dated,
                "maturity": maturity,
                "settlement": iso(settle),
                "clean_price": clean,
                "accrued": accrued,
                "z_spread": z,
                "dirty_at_zero_spread": bond_dirty(b, curve, 0.0, settle),
                "quote_dv01": quote_dv01[k],
                "risk_ladder": ladder,
                "spread_dv01": (up - down) / 2.0,
            }
        )

    return {
        "golden_version": V2_GOLDEN_VERSION,
        "description": (
            "QuantLib golden values for pricing v2 (docs/research/PRICING_V2.md Section 9). "
            "Generated by research/ratesresearch/goldens/pricing_v2.py; quotes are declared "
            "values. The module docstring lists what is independent of the Java code."
        ),
        "quantlib_version": ql.__version__,
        "python_version": platform.python_version(),
        "platform": platform.system(),
        "valuation_date": V2_TODAY_ISO,
        "hull_white": {"a": HW_A, "sigma": HW_SIGMA},
        "risk_tenor_years": RISK_TENOR_YEARS,
        "inputs": inputs,
        "curves": curves_out,
        "monotone_convex_forwards": {
            "source": "QuantLib ConvexMonotoneInterpolation(quadraticity=0, monotonicity=1)",
            "first_node": STEP_PILLARS,
            "points": mc_forwards,
        },
        "tension_spline_variational_check": variational,
        "hull_white_convexity": hw_rows,
        "bonds": bonds_out,
        "bond_risk_curve": scheme,
        "bond_risk_reprice_error": bump_error,
    }


def _variational_check(t: np.ndarray, y: np.ndarray) -> JsonDict:
    """Largest gap between the closed-form spline and the grid minimiser of its energy."""
    step = 1.0 / 128.0
    # a grid that contains every node: nodes are snapped to it, so the two splines are compared
    # on the snapped nodes
    idx = np.round((t - t[0]) / step).astype(int)
    tn = t[0] + idx * step
    m = int(idx[-1]) + 1
    grid = t[0] + step * np.arange(m)
    # energy = sum over interior points of (second difference / step^2)^2 * step
    #        + p^2 * sum over intervals of (first difference / step)^2 * step
    d2 = np.zeros((m - 2, m))
    for i in range(m - 2):
        d2[i, i], d2[i, i + 1], d2[i, i + 2] = 1.0, -2.0, 1.0
    d1 = np.zeros((m - 1, m))
    for i in range(m - 1):
        d1[i, i], d1[i, i + 1] = -1.0, 1.0
    q = d2.T @ d2 / step**3 + TENSION**2 * d1.T @ d1 / step
    fixed = np.zeros(m, dtype=bool)
    fixed[idx] = True
    s = np.zeros(m)
    s[idx] = y
    free = ~fixed
    s[free] = np.linalg.solve(q[np.ix_(free, free)], -q[np.ix_(free, fixed)] @ y)
    closed = tension_spline(tn, y, grid[1:-1], TENSION)
    gap = float(np.max(np.abs(closed - s[1:-1])))
    scale = float(np.max(np.abs(y)))
    return {
        "grid_step_years": step,
        "max_abs_log_df_gap": gap,
        "largest_abs_log_df": scale,
        "note": (
            "closed-form tension spline against the finite-difference minimiser of "
            "integral (s'')^2 + p^2 (s')^2 on the same nodes; agreement is second order in the "
            "grid step"
        ),
    }
