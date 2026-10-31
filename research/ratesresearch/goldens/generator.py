"""Golden values from QuantLib for the Java analytics module (API_SPEC.md Section 8).

Every number in the golden file is computed by QuantLib. The curves are bootstrapped here by a
sequential root search (QuantLib's Brent solver) in which each instrument is priced by QuantLib
objects built with the same explicit schedules, calendars and conventions as the Java side, so a
difference between the two implementations shows up as a repricing difference rather than being
hidden by matching helper defaults. Market quotes are declared, realistic values for the
valuation date (DECISIONS.md DR-P1-08); past overnight fixings come from the committed fixtures.

The output is deterministic given the QuantLib version, which is recorded in the file.
"""

from __future__ import annotations

import csv
import math
import platform
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import QuantLib as ql  # type: ignore[import-not-found,import-untyped,unused-ignore]

GOLDEN_VERSION = 1
TODAY_ISO = "2025-01-15"
HW_A = 0.03
HW_SIGMA = 0.0085
BP = 1e-4

JsonDict = dict[str, Any]


def qd(iso: str) -> Any:
    y, m, d = (int(x) for x in iso.split("-"))
    return ql.Date(d, m, y)


def iso(date: Any) -> str:
    return str(date.ISO())


CALENDARS: dict[str, Any] = {
    "US-SIFMA": ql.UnitedStates(ql.UnitedStates.GovernmentBond),
    "US-SOFR": ql.UnitedStates(ql.UnitedStates.SOFR),
    "US-FED": ql.UnitedStates(ql.UnitedStates.FederalReserve),
    "TARGET": ql.TARGET(),
    "LONDON": ql.UnitedKingdom(ql.UnitedKingdom.Settlement),
}
US_NY_SWAP = ql.JointCalendar(CALENDARS["US-FED"], CALENDARS["US-SIFMA"])


@dataclass(frozen=True)
class SwapConv:
    """OIS conventions; mirror io.ratesalgo.analytics.swap.SwapConvention."""

    ccy: str
    schedule_cal: Any
    payment_cal: Any
    index_factory: Callable[[Any], Any]
    fixed_dc: Any
    spot_lag: int
    pay_lag: int


CONVS: dict[str, SwapConv] = {
    "USD": SwapConv("USD", US_NY_SWAP, US_NY_SWAP, ql.Sofr, ql.Actual360(), 2, 2),
    "EUR": SwapConv(
        "EUR", CALENDARS["TARGET"], CALENDARS["TARGET"], ql.Estr, ql.Actual360(), 2, 1
    ),
    "GBP": SwapConv(
        "GBP", CALENDARS["LONDON"], CALENDARS["LONDON"], ql.Sonia, ql.Actual365Fixed(), 0, 0
    ),
}


def holidays(cal: Any, first_year: int, last_year: int) -> list[str]:
    """Weekdays that are not business days on the calendar."""
    out: list[str] = []
    d = ql.Date(1, 1, first_year)
    end = ql.Date(31, 12, last_year)
    while d <= end:
        if d.weekday() not in (ql.Saturday, ql.Sunday) and not cal.isBusinessDay(d):
            out.append(iso(d))
        d = d + 1
    return out


def hull_white_ca(t1: float, t2: float) -> float:
    """Mirror of HullWhite.futuresConvexity with the default parameters."""
    a, s = HW_A, HW_SIGMA
    b12 = (1.0 - math.exp(-a * (t2 - t1))) / a
    b01 = (1.0 - math.exp(-a * t1)) / a
    return b12 / (t2 - t1) * (b12 * (1.0 - math.exp(-2.0 * a * t1)) + 2.0 * a * b01 * b01) * (
        s * s / (4.0 * a)
    )


def load_fixings(path: Path, last: str) -> list[tuple[str, float]]:
    rows: list[tuple[str, float]] = []
    with path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if "2024-03-01" <= row["date"] <= last and row["rate_percent"]:
                rows.append((row["date"], float(row["rate_percent"]) / 100.0))
    return rows


# ---------------------------------------------------------------- curve instruments


@dataclass
class Inst:
    """A curve instrument priced by QuantLib on the curve being built."""

    kind: str
    label: str
    tenor: str
    pillar: Any
    quote: float
    implied: Callable[[], float]
    spec: JsonDict = field(default_factory=dict)


def ois_swap(
    conv: SwapConv,
    index: Any,
    start: Any,
    end: Any,
    rate: float,
    nominal: float,
    payer: bool,
    lookback: int = 0,
    lockout: int = 0,
    shift: bool = False,
    telescopic: bool = False,
) -> Any:
    mf = ql.ModifiedFollowing
    sched = ql.Schedule(
        start,
        end,
        ql.Period(12, ql.Months),
        conv.schedule_cal,
        mf,
        mf,
        ql.DateGeneration.Backward,
        False,
    )
    swap_type = ql.Swap.Payer if payer else ql.Swap.Receiver
    return ql.OvernightIndexedSwap(
        swap_type,
        [nominal],
        sched,
        rate,
        conv.fixed_dc,
        [nominal],
        sched,
        index,
        0.0,
        conv.pay_lag,
        ql.Following,
        conv.payment_cal,
        telescopic,
        ql.RateAveraging.Compound,
        lookback,
        lockout,
        shift,
    )


def last_payment(swap: Any) -> Any:
    return max(swap.leg(0)[len(swap.leg(0)) - 1].date(), swap.leg(1)[len(swap.leg(1)) - 1].date())


@dataclass
class CurveSpec:
    """A curve to bootstrap: instruments plus the handle they price on."""

    curve_id: str
    ccy: str
    handle: Any
    index: Any
    insts: list[Inst]
    discount_id: str | None = None


def period_inst(
    label: str, tenor: str, kind: str, start: Any, end: Any, dc: Any, quote: float, h: Any
) -> Inst:
    def implied() -> float:
        c = h.currentLink()
        return float((c.discount(start) / c.discount(end) - 1.0) / dc.yearFraction(start, end))

    return Inst(
        kind,
        label,
        tenor,
        end,
        quote,
        implied,
        {"start": iso(start), "end": iso(end)},
    )


def ois_inst(conv: SwapConv, index: Any, today: Any, months: int, tenor: str, q: float) -> Inst:
    start = conv.schedule_cal.advance(today, conv.spot_lag, ql.Days)
    end = start + ql.Period(months, ql.Months)
    swap = ois_swap(conv, index, start, end, 0.0, 1.0, True, telescopic=True)
    swap.setPricingEngine(ql.DiscountingSwapEngine(index.forwardingTermStructure()))

    def implied() -> float:
        return float(swap.fairRate())

    return Inst(
        "ois",
        f"{conv.ccy} OIS {tenor}",
        tenor,
        last_payment(swap),
        q,
        implied,
        {"tenor_months": months, "start": iso(start), "end": iso(end)},
    )


def future_inst(index: Any, today: Any, year: int, month: int, price: float, three: bool) -> Inst:
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
    ca = hull_white_ca(dc.yearFraction(today, start), dc.yearFraction(today, end))
    fut = ql.OvernightIndexFuture(index, start, end, ql.QuoteHandle(ql.SimpleQuote(ca)), avg)
    code = ("SR3" if three else "SR1") + f"{year}{month:02d}"

    def implied() -> float:
        return float(fut.NPV())

    return Inst(
        "future3m" if three else "future1m",
        code,
        code,
        end,
        price,
        implied,
        {"year": year, "month": month, "convexity": ca, "start": iso(start), "end": iso(end)},
    )


def bootstrap(spec: CurveSpec, today: Any) -> tuple[list[Any], list[float]]:
    """Sequential bootstrap on log-linear discount factors; returns pillar dates and DFs."""
    insts = sorted(spec.insts, key=lambda i: i.pillar.serialNumber())
    spec.insts = insts
    dates: list[Any] = [today]
    dfs: list[float] = [1.0]
    dc = ql.Actual365Fixed()
    solver = ql.Brent()
    solver.setMaxEvaluations(500)
    for inst in insts:
        t_prev = dc.yearFraction(today, dates[-1])
        y_prev = math.log(dfs[-1])
        dt = dc.yearFraction(today, inst.pillar) - t_prev

        def resid(y: float, inst: Inst = inst) -> float:
            curve = ql.DiscountCurve([*dates, inst.pillar], [*dfs, math.exp(y)], dc)
            curve.enableExtrapolation()
            spec.handle.linkTo(curve)
            return inst.implied() - inst.quote

        y = solver.solve(resid, 1e-16, y_prev - 0.04 * dt, y_prev - 0.15 * dt, y_prev + 0.05 * dt)
        err = resid(y)
        if abs(err) > 1e-12:
            raise RuntimeError(f"{inst.label}: residual {err}")
        dates.append(inst.pillar)
        dfs.append(math.exp(y))
    final = ql.DiscountCurve(dates, dfs, dc)
    final.enableExtrapolation()
    spec.handle.linkTo(final)
    return dates, dfs


# ---------------------------------------------------------------- market data (declared)

FOMC_EFFECTIVE = [
    "2025-01-30",
    "2025-03-20",
    "2025-05-08",
    "2025-06-19",
    "2025-07-31",
    "2025-09-18",
    "2025-10-30",
    "2025-12-11",
    "2026-01-29",
]
FOMC_RATES = [0.0430, 0.0429, 0.0422, 0.0415, 0.0408, 0.0402, 0.0395, 0.0390, 0.0385]
USD_FUTURES = [(2026, 3, 96.30), (2026, 6, 96.38), (2026, 9, 96.42), (2026, 12, 96.45)]
USD_SWAPS = [
    (24, "2Y", 0.0380),
    (36, "3Y", 0.0378),
    (48, "4Y", 0.0380),
    (60, "5Y", 0.0383),
    (72, "6Y", 0.0386),
    (84, "7Y", 0.0390),
    (96, "8Y", 0.0393),
    (108, "9Y", 0.0396),
    (120, "10Y", 0.0399),
    (144, "12Y", 0.0403),
    (180, "15Y", 0.0407),
    (240, "20Y", 0.0408),
    (300, "25Y", 0.0403),
    (360, "30Y", 0.0397),
    (480, "40Y", 0.0385),
    (600, "50Y", 0.0372),
]
EUR_SWAPS = [
    (1, "1M", 0.0285),
    (3, "3M", 0.0275),
    (6, "6M", 0.0255),
    (9, "9M", 0.0240),
    (12, "1Y", 0.0230),
    (18, "18M", 0.0215),
    (24, "2Y", 0.0210),
    (36, "3Y", 0.0208),
    (48, "4Y", 0.0210),
    (60, "5Y", 0.0213),
    (72, "6Y", 0.0216),
    (84, "7Y", 0.0220),
    (96, "8Y", 0.0223),
    (108, "9Y", 0.0226),
    (120, "10Y", 0.0229),
    (144, "12Y", 0.0234),
    (180, "15Y", 0.0238),
    (240, "20Y", 0.0235),
    (300, "25Y", 0.0227),
    (360, "30Y", 0.0220),
    (480, "40Y", 0.0208),
    (600, "50Y", 0.0198),
]
EUR6M_SWAPS = [
    (24, "2Y", 0.0245),
    (36, "3Y", 0.0240),
    (60, "5Y", 0.0242),
    (84, "7Y", 0.0248),
    (120, "10Y", 0.0255),
    (180, "15Y", 0.0262),
    (240, "20Y", 0.0258),
    (360, "30Y", 0.0245),
]
GBP_SWAPS = [
    (1, "1M", 0.0468),
    (3, "3M", 0.0462),
    (6, "6M", 0.0450),
    (9, "9M", 0.0440),
    (12, "1Y", 0.0432),
    (18, "18M", 0.0418),
    (24, "2Y", 0.0410),
    (36, "3Y", 0.0400),
    (48, "4Y", 0.0398),
    (60, "5Y", 0.0398),
    (84, "7Y", 0.0402),
    (120, "10Y", 0.0410),
    (144, "12Y", 0.0415),
    (180, "15Y", 0.0420),
    (240, "20Y", 0.0418),
    (300, "25Y", 0.0408),
    (360, "30Y", 0.0398),
    (480, "40Y", 0.0378),
    (600, "50Y", 0.0360),
]
ON_RATES = {"USD": 0.0430, "EUR": 0.0290, "GBP": 0.0470}


def make_curves(
    today: Any, fixings: dict[str, list[tuple[str, float]]]
) -> tuple[dict[str, CurveSpec], dict[str, Any]]:
    """Builds the curve specifications; returns them and the overnight indices by currency."""
    specs: dict[str, CurveSpec] = {}
    indices: dict[str, Any] = {}
    for ccy in ("USD", "EUR", "GBP"):
        conv = CONVS[ccy]
        handle = ql.RelinkableYieldTermStructureHandle()
        index = conv.index_factory(handle)
        index.clearFixings()
        for date, rate in fixings[ccy]:
            index.addFixing(qd(date), rate)
        indices[ccy] = index
        fix_cal = index.fixingCalendar()
        idx_dc = index.dayCounter()
        insts = [
            period_inst(
                f"{ccy} ON",
                "ON",
                "fixing",
                today,
                fix_cal.advance(today, 1, ql.Days),
                idx_dc,
                ON_RATES[ccy],
                handle,
            )
        ]
        if ccy == "USD":
            prev = today
            for eff, rate in zip(FOMC_EFFECTIVE, FOMC_RATES, strict=True):
                end = qd(eff)
                insts.append(
                    period_inst(
                        f"USD FOMC {eff}", f"M{eff}", "meeting", prev, end, idx_dc, rate, handle
                    )
                )
                prev = end
            for y, m, p in USD_FUTURES:
                insts.append(future_inst(index, today, y, m, p, True))
            swaps = USD_SWAPS
        else:
            swaps = EUR_SWAPS if ccy == "EUR" else GBP_SWAPS
        for months, tenor, q in swaps:
            insts.append(ois_inst(conv, index, today, months, tenor, q))
        specs[ccy] = CurveSpec(f"{ccy}-OIS", ccy, handle, index, insts)
    return specs, indices


def make_euribor_curve(today: Any, estr_handle: Any) -> CurveSpec:
    handle = ql.RelinkableYieldTermStructureHandle()
    index = ql.Euribor6M(handle)
    cal = ql.TARGET()
    insts: list[Inst] = []
    for months, tenor, q in EUR6M_SWAPS:
        start = cal.advance(today, 2, ql.Days)
        end = start + ql.Period(months, ql.Months)
        mf = ql.ModifiedFollowing
        fixed = ql.Schedule(
            start, end, ql.Period(12, ql.Months), cal, mf, mf, ql.DateGeneration.Backward, False
        )
        flt = ql.Schedule(
            start, end, ql.Period(6, ql.Months), cal, mf, mf, ql.DateGeneration.Backward, False
        )
        swap = ql.VanillaSwap(
            ql.Swap.Payer,
            1.0,
            fixed,
            0.0,
            ql.Thirty360(ql.Thirty360.European),
            flt,
            index,
            0.0,
            ql.Actual360(),
        )
        swap.setPricingEngine(ql.DiscountingSwapEngine(estr_handle))

        def implied(swap: Any = swap) -> float:
            return float(swap.fairRate())

        pillar = max(fixed[len(fixed) - 1], flt[len(flt) - 1])
        insts.append(
            Inst(
                "term_swap",
                f"EUR 6M {tenor}",
                tenor,
                pillar,
                q,
                implied,
                {"tenor_months": months, "start": iso(start), "end": iso(end)},
            )
        )
    return CurveSpec("EUR-6M", "EUR", handle, index, insts, discount_id="EUR-OIS")


def curve_json(spec: CurveSpec, dates: list[Any], dfs: list[float]) -> JsonDict:
    return {
        "id": spec.curve_id,
        "currency": spec.ccy,
        "discount": spec.discount_id,
        "interpolation": "LINEAR_LOG_DF",
        "instruments": [
            {
                "kind": i.kind,
                "label": i.label,
                "tenor": i.tenor,
                "quote": i.quote,
                "pillar": iso(i.pillar),
                **i.spec,
            }
            for i in spec.insts
        ],
        "pillar_dates": [iso(d) for d in dates[1:]],
        "discount_factors": dfs[1:],
    }


# ---------------------------------------------------------------- swaps


@dataclass
class SwapCase:
    swap_id: str
    ccy: str
    start: Any
    end: Any
    rate: float | None
    payer: bool
    lookback: int = 0
    lockout: int = 0
    shift: bool = False
    kind: str = "spot"
    tenor_months: int = 0
    rate_offset: float = 0.0


def swap_cases(today: Any) -> list[SwapCase]:
    cases: list[SwapCase] = []
    tenors = [12, 24, 36, 60, 84, 120, 180, 240, 360]
    for ccy in ("USD", "EUR", "GBP"):
        conv = CONVS[ccy]
        spot = conv.schedule_cal.advance(today, conv.spot_lag, ql.Days)
        for m in tenors:
            end = spot + ql.Period(m, ql.Months)
            cases.append(
                SwapCase(f"{ccy}-SPOT-{m}M-PAR", ccy, spot, end, None, True, tenor_months=m)
            )
            cases.append(
                SwapCase(
                    f"{ccy}-SPOT-{m}M-OFF",
                    ccy,
                    spot,
                    end,
                    None,
                    m % 24 == 0,
                    tenor_months=m,
                    rate_offset=0.005,
                )
            )
        for fwd, ten in ((12, 60), (24, 120), (60, 60)):
            start = conv.schedule_cal.adjust(spot + ql.Period(fwd, ql.Months), ql.Following)
            end = start + ql.Period(ten, ql.Months)
            cases.append(
                SwapCase(
                    f"{ccy}-FWD-{fwd}Mx{ten}M",
                    ccy,
                    start,
                    end,
                    None,
                    True,
                    kind="forward",
                    tenor_months=ten,
                    rate_offset=-0.0025,
                )
            )
        imm = ql.IMM.nextDate(spot, False)
        imm_start = conv.schedule_cal.adjust(imm, ql.ModifiedFollowing)
        for ten in (60, 120):
            nxt = imm + ql.Period(ten, ql.Months)
            end = ql.Date.nthWeekday(3, ql.Wednesday, nxt.month(), nxt.year())
            cases.append(
                SwapCase(
                    f"{ccy}-IMM-{ten}M", ccy, imm_start, end, None, False, kind="imm",
                    tenor_months=ten, rate_offset=0.001,
                )
            )
        for start_iso, ten, rate in (("2024-06-17", 24, 0.0450), ("2024-03-15", 60, 0.0400)):
            start = qd(start_iso)
            cases.append(
                SwapCase(
                    f"{ccy}-SEASONED-{start_iso}-{ten}M",
                    ccy,
                    start,
                    start + ql.Period(ten, ql.Months),
                    rate,
                    True,
                    kind="seasoned",
                    tenor_months=ten,
                )
            )
    spot = US_NY_SWAP.advance(today, 2, ql.Days)
    for lb, lo, sh in ((2, 0, False), (2, 0, True), (0, 2, False), (5, 2, True)):
        for start, ten in ((spot, 60), (qd("2024-09-16"), 24)):
            cases.append(
                SwapCase(
                    f"USD-LB{lb}-LO{lo}-{'OS' if sh else 'NS'}-{iso(start)}-{ten}M",
                    "USD",
                    start,
                    start + ql.Period(ten, ql.Months),
                    0.0410,
                    True,
                    lb,
                    lo,
                    sh,
                    kind="observation",
                    tenor_months=ten,
                )
            )
    return cases


def price_swap(case: SwapCase, index: Any, handle: Any, par_rates: dict[str, float]) -> Any:
    conv = CONVS[case.ccy]
    rate = case.rate
    if rate is None:
        rate = par_rates.get(case.swap_id, 0.0)
    swap = ois_swap(
        conv,
        index,
        case.start,
        case.end,
        rate,
        1.0e8,
        case.payer,
        case.lookback,
        case.lockout,
        case.shift,
        telescopic=False,
    )
    swap.setPricingEngine(ql.DiscountingSwapEngine(handle))
    return swap


# ---------------------------------------------------------------- bonds


@dataclass(frozen=True)
class BondCase:
    bond_id: str
    market: str
    coupon: float
    dated: str
    maturity: str
    yld: float
    trade: str = TODAY_ISO


BOND_MARKETS: dict[str, tuple[Any, int, int, int]] = {
    # calendar, settlement days, period months, ex-dividend business days
    "UST": (CALENDARS["US-SIFMA"], 1, 6, 0),
    "BUND": (CALENDARS["TARGET"], 2, 12, 0),
    "GILT": (CALENDARS["LONDON"], 1, 6, 7),
}


def bond_cases() -> list[BondCase]:
    ust = [
        (4.250, "2024-12-31", "2026-12-31", 0.0428),
        (4.375, "2024-12-15", "2027-12-15", 0.0431),
        (4.000, "2024-01-15", "2027-01-15", 0.0427),
        (4.250, "2024-01-31", "2026-01-31", 0.0424),
        (4.000, "2024-01-31", "2029-01-31", 0.0441),
        (4.000, "2024-01-31", "2031-01-31", 0.0452),
        (4.250, "2024-12-31", "2029-12-31", 0.0443),
        (4.125, "2024-12-31", "2031-12-31", 0.0456),
        (4.250, "2024-11-15", "2034-11-15", 0.0466),
        (4.500, "2023-11-15", "2033-11-15", 0.0464),
        (3.875, "2023-08-15", "2033-08-15", 0.0462),
        (2.750, "2022-08-15", "2032-08-15", 0.0460),
        (1.375, "2021-11-15", "2031-11-15", 0.0455),
        (0.625, "2020-08-15", "2030-08-15", 0.0450),
        (4.625, "2024-11-15", "2054-11-15", 0.0490),
        (4.250, "2024-08-15", "2054-08-15", 0.0489),
        (4.750, "2023-11-15", "2053-11-15", 0.0489),
        (4.625, "2024-11-15", "2044-11-15", 0.0493),
        (1.875, "2021-02-15", "2041-02-15", 0.0488),
        (3.000, "2018-08-15", "2048-08-15", 0.0491),
        (2.250, "2019-08-15", "2049-08-15", 0.0491),
        (4.375, "2024-05-31", "2026-05-31", 0.0425),
        (4.125, "2023-10-31", "2028-10-31", 0.0438),
        (3.500, "2023-02-28", "2030-02-28", 0.0448),
        (4.000, "2024-02-29", "2031-02-28", 0.0453),
    ]
    bund = [
        (2.500, "2024-02-15", "2034-02-15", 0.0245),
        (2.600, "2024-08-15", "2034-08-15", 0.0248),
        (2.200, "2023-11-15", "2033-02-15", 0.0240),
        (0.000, "2021-02-15", "2031-02-15", 0.0228),
        (1.000, "2015-08-15", "2025-08-15", 0.0220),
        (0.500, "2016-02-15", "2026-02-15", 0.0215),
        (6.500, "1997-07-04", "2027-07-04", 0.0212),
        (5.625, "1998-01-04", "2028-01-04", 0.0215),
        (4.750, "1998-07-04", "2028-07-04", 0.0218),
        (6.250, "2000-01-04", "2030-01-04", 0.0225),
        (5.500, "2000-01-04", "2031-01-04", 0.0229),
        (4.750, "2003-07-04", "2034-07-04", 0.0248),
        (4.000, "2005-01-04", "2037-01-04", 0.0258),
        (4.250, "2007-07-04", "2039-07-04", 0.0264),
        (2.500, "2014-07-04", "2044-07-04", 0.0270),
        (1.250, "2017-08-15", "2048-08-15", 0.0272),
        (2.500, "2023-02-15", "2054-08-15", 0.0275),
    ]
    gilt = [
        (0.125, "2020-07-30", "2026-01-30", 0.0455),
        (4.125, "2022-07-22", "2027-01-22", 0.0436),
        (3.750, "2023-03-07", "2027-03-07", 0.0435),
        (1.625, "2018-04-22", "2028-10-22", 0.0430),
        (4.500, "2023-06-07", "2028-06-07", 0.0431),
        (0.500, "2021-01-31", "2029-01-31", 0.0432),
        (4.125, "2023-07-22", "2029-07-22", 0.0434),
        (4.750, "2007-12-07", "2030-12-07", 0.0441),
        (0.250, "2020-07-31", "2031-07-31", 0.0447),
        (4.000, "2024-04-22", "2031-10-22", 0.0450),
        (1.000, "2021-07-31", "2032-01-31", 0.0452),
        (4.250, "2000-06-07", "2032-06-07", 0.0456),
        (3.250, "2023-01-31", "2033-01-31", 0.0462),
        (4.500, "2023-09-07", "2034-09-07", 0.0470),
        (4.250, "2005-09-07", "2036-03-07", 0.0480),
        (1.750, "2016-09-07", "2037-09-07", 0.0488),
        (4.750, "2006-12-07", "2038-12-07", 0.0494),
        (4.500, "2009-12-07", "2042-12-07", 0.0505),
        (3.500, "2014-07-22", "2045-01-22", 0.0510),
        (4.250, "2013-12-07", "2049-12-07", 0.0512),
        (1.250, "2020-07-31", "2051-07-31", 0.0505),
        (3.750, "2019-10-22", "2053-10-22", 0.0508),
        (4.000, "2011-10-22", "2063-10-22", 0.0495),
    ]
    out: list[BondCase] = []
    for market, rows in (("UST", ust), ("BUND", bund), ("GILT", gilt)):
        for c, dated, mat, y in rows:
            out.append(BondCase(f"{market}-{c:.3f}-{mat}", market, c / 100.0, dated, mat, y))
    # gilts traded inside an ex-dividend period
    out.append(
        BondCase("GILT-1.000-2032-01-31-XD", "GILT", 0.01, "2021-07-31", "2032-01-31", 0.0452,
                 "2025-01-24")
    )
    out.append(
        BondCase("GILT-3.250-2033-01-31-XD", "GILT", 0.0325, "2023-01-31", "2033-01-31", 0.0462,
                 "2025-01-27")
    )
    return out


def ql_bond(case: BondCase, adjusted: bool) -> Any:
    cal, sdays, months, exdiv = BOND_MARKETS[case.market]
    maturity = qd(case.maturity)
    sched = ql.Schedule(
        qd(case.dated),
        maturity,
        ql.Period(months, ql.Months),
        cal,
        ql.Unadjusted,
        ql.Unadjusted,
        ql.DateGeneration.Backward,
        ql.Date.isEndOfMonth(maturity),
    )
    dc = ql.ActualActual(ql.ActualActual.ISMA)
    ex_period = ql.Period(exdiv, ql.Days) if exdiv else ql.Period()
    return ql.FixedRateBond(
        sdays,
        100.0,
        sched,
        [case.coupon],
        dc,
        ql.Following if adjusted else ql.Unadjusted,
        100.0,
        qd(case.dated),
        cal,
        ex_period,
        cal,
        ql.Unadjusted,
        False,
    )


def price_bond(case: BondCase, usd_curve: Any) -> JsonDict:
    cal, sdays, months, _ = BOND_MARKETS[case.market]
    freq = ql.Annual if months == 12 else ql.Semiannual
    dc = ql.ActualActual(ql.ActualActual.ISMA)
    street = ql_bond(case, False)
    true = ql_bond(case, True)
    settle = cal.advance(qd(case.trade), sdays, ql.Days)
    y = case.yld
    comp = ql.Compounded
    clean = ql.BondFunctions.cleanPrice(street, y, dc, comp, freq, settle)
    accrued = ql.BondFunctions.accruedAmount(street, settle)
    dirty = clean + accrued
    street_yield = ql.BondFunctions.bondYield(
        street, ql.BondPrice(clean, ql.BondPrice.Clean), dc, comp, freq, settle, 1e-15, 200, y
    )
    true_yield = ql.BondFunctions.bondYield(
        true, ql.BondPrice(clean, ql.BondPrice.Clean), dc, comp, freq, settle, 1e-15, 200, y
    )
    md = ql.BondFunctions.duration(street, y, dc, comp, freq, ql.Duration.Modified, settle)
    cx = ql.BondFunctions.convexity(street, y, dc, comp, freq, settle)
    out: JsonDict = {
        "id": case.bond_id,
        "market": case.market,
        "coupon": case.coupon,
        "dated": case.dated,
        "maturity": case.maturity,
        "trade": case.trade,
        "settlement": iso(settle),
        "yield": y,
        "clean": clean,
        "dirty": dirty,
        "accrued": accrued,
        "street_yield": street_yield,
        "true_yield": true_yield,
        "modified_duration": md,
        "convexity": cx,
        "dv01": md * dirty * BP,
    }
    if case.market == "UST":
        curve = usd_curve
        out["dirty_on_curve"] = ql.BondFunctions.dirtyPrice(
            true, curve, 0.0, ql.Actual365Fixed(), ql.Continuous, ql.NoFrequency, settle
        )
        out["z_spread"] = ql.BondFunctions.zSpread(
            true,
            ql.BondPrice(clean, ql.BondPrice.Clean),
            curve,
            ql.Actual365Fixed(),
            ql.Continuous,
            ql.NoFrequency,
            settle,
            1e-15,
            200,
            0.0,
        )
    return out


# ---------------------------------------------------------------- futures


CME_BASKETS: list[tuple[str, int, int, list[tuple[float, str, str]]]] = [
    (
        "TY",
        2025,
        3,
        [
            (1.375, "2021-11-15", "2031-11-15"),
            (1.875, "2022-02-15", "2032-02-15"),
            (2.875, "2022-05-15", "2032-05-15"),
            (2.750, "2022-08-15", "2032-08-15"),
            (4.125, "2022-11-15", "2032-11-15"),
            (4.125, "2024-11-30", "2031-11-30"),
            (3.500, "2023-02-15", "2033-02-15"),
        ],
    ),
    (
        "FV",
        2025,
        3,
        [
            (4.500, "2024-05-31", "2029-05-31"),
            (4.250, "2024-06-30", "2029-06-30"),
            (4.000, "2024-07-31", "2029-07-31"),
            (3.625, "2024-08-31", "2029-08-31"),
            (3.500, "2024-09-30", "2029-09-30"),
            (4.125, "2024-10-31", "2029-10-31"),
            (4.125, "2024-11-30", "2029-11-30"),
            (4.375, "2024-12-31", "2029-12-31"),
        ],
    ),
    (
        "US",
        2025,
        3,
        [
            (4.500, "2016-02-15", "2046-02-15"),
            (3.000, "2018-02-15", "2048-02-15"),
            (2.875, "2019-05-15", "2049-05-15"),
            (4.375, "2020-05-15", "2040-05-15"),
            (1.125, "2020-08-15", "2040-08-15"),
        ],
    ),
    (
        "TU",
        2025,
        3,
        [
            (4.250, "2024-12-31", "2026-12-31"),
            (4.000, "2024-01-15", "2027-01-15"),
            (4.375, "2024-12-15", "2026-12-15"),
        ],
    ),
    (
        "UB",
        2025,
        3,
        [
            (4.625, "2024-11-15", "2054-11-15"),
            (4.250, "2024-08-15", "2054-08-15"),
            (4.750, "2023-11-15", "2053-11-15"),
        ],
    ),
]


def cme_cf(coupon: float, maturity: str, year: int, month: int, rounding: int) -> float:
    """CBOT factor as the 6% clean price of the truncated bond on 30/360 (definition)."""
    first = ql.Date(1, month, year)
    mat = qd(maturity)
    months = (mat.year() - first.year()) * 12 + mat.month() - first.month()
    n, z = divmod(months, 12)
    z = z // rounding * rounding
    hyp_mat = first + ql.Period(12 * n + z, ql.Months)
    sched = ql.Schedule(
        hyp_mat - ql.Period(12 * n + z + 6, ql.Months),
        hyp_mat,
        ql.Period(6, ql.Months),
        ql.NullCalendar(),
        ql.Unadjusted,
        ql.Unadjusted,
        ql.DateGeneration.Backward,
        False,
    )
    dc = ql.Thirty360(ql.Thirty360.BondBasis)
    bond = ql.FixedRateBond(0, 100.0, sched, [coupon], dc, ql.Unadjusted)
    clean = ql.BondFunctions.cleanPrice(bond, 0.06, dc, ql.Compounded, ql.Semiannual, first)
    return round(clean / 100.0, 4)


EUREX = [
    ("FGBL", "2025-03-10", "BUND", [(2.500, "2024-02-15", "2034-02-15"),
                                    (2.600, "2024-08-15", "2034-08-15"),
                                    (2.200, "2023-11-15", "2033-02-15")]),
    ("FGBM", "2025-03-10", "BUND", [(0.000, "2021-02-15", "2030-02-15"),
                                    (2.400, "2024-01-15", "2029-10-19")]),
    ("FGBS", "2025-03-10", "BUND", [(0.500, "2016-02-15", "2026-02-15"),
                                    (2.200, "2024-03-12", "2026-12-10")]),
]
LONG_GILT = [
    (4.000, "2024-04-22", "2031-10-22"),
    (1.000, "2021-07-31", "2032-01-31"),
    (4.250, "2000-06-07", "2032-06-07"),
    (3.250, "2023-01-31", "2033-01-31"),
    (4.500, "2023-09-07", "2034-09-07"),
]


def bond_clean_at(
    market: str, coupon: float, dated: str, mat: str, y: float, settle: Any
) -> float:
    case = BondCase("cf", market, coupon / 100.0, dated, mat, y)
    bond = ql_bond(case, False)
    _, _, months, _ = BOND_MARKETS[market]
    freq = ql.Annual if months == 12 else ql.Semiannual
    dc = ql.ActualActual(ql.ActualActual.ISMA)
    return float(ql.BondFunctions.cleanPrice(bond, y, dc, ql.Compounded, freq, settle))


def basis_rows(contract: str, year: int, month: int, repo: float, today: Any) -> JsonDict:
    """Implied repo, net basis and CTD for a CME basket with yields from a smooth curve.

    The futures price is set 1/64 below the lowest repo-implied forward price divided by the
    conversion factor, so the CTD's implied repo sits just below the repo rate.
    """
    basket = next(b for b in CME_BASKETS if b[0] == contract)[3]
    rounding = 1 if contract in ("TU", "FV", "Z3N") else 3
    settle = CALENDARS["US-SIFMA"].advance(today, 1, ql.Days)
    last_day = CALENDARS["US-SIFMA"].endOfMonth(ql.Date(1, month, year))
    futures_price = 1e9
    for c, dated, mat in basket:
        case = BondCase("b", "UST", c / 100.0, dated, mat, 0.0)
        bond = ql_bond(case, False)
        years = ql.Actual365Fixed().yearFraction(settle, qd(mat))
        y = 0.0425 + 0.0012 * math.log(1.0 + years) + 0.0004 * (c - 3.0)
        dc = ql.ActualActual(ql.ActualActual.ISMA)
        clean = ql.BondFunctions.cleanPrice(bond, y, dc, ql.Compounded, ql.Semiannual, settle)
        dirty = clean + ql.BondFunctions.accruedAmount(bond, settle)
        fwd = dirty * (1.0 + repo * (last_day - settle) / 360.0)
        for cf_ in bond.cashflows():
            if settle < cf_.date() <= last_day and cf_.date() != bond.maturityDate():
                fwd -= cf_.amount() * (1.0 + repo * (last_day - cf_.date()) / 360.0)
        fwd -= ql.BondFunctions.accruedAmount(bond, last_day)
        futures_price = min(futures_price, fwd / cme_cf(c / 100.0, mat, year, month, rounding))
    futures_price = math.floor(futures_price * 64.0) / 64.0 - 1.0 / 64.0
    rows = []
    for c, dated, mat in basket:
        case = BondCase("b", "UST", c / 100.0, dated, mat, 0.0)
        bond = ql_bond(case, False)
        years = ql.Actual365Fixed().yearFraction(settle, qd(mat))
        y = 0.0425 + 0.0012 * math.log(1.0 + years) + 0.0004 * (c - 3.0)
        dc = ql.ActualActual(ql.ActualActual.ISMA)
        clean = ql.BondFunctions.cleanPrice(bond, y, dc, ql.Compounded, ql.Semiannual, settle)
        cf = cme_cf(c / 100.0, mat, year, month, rounding)
        ai_spot = ql.BondFunctions.accruedAmount(bond, settle)
        ai_del = ql.BondFunctions.accruedAmount(bond, last_day)
        dirty = clean + ai_spot
        d = last_day - settle
        coupons = 0.0
        ctime = 0.0
        for cf_ in bond.cashflows():
            if settle < cf_.date() <= last_day and cf_.date() != bond.maturityDate():
                coupons += cf_.amount()
                ctime += cf_.amount() * (last_day - cf_.date()) / 360.0
        invoice = futures_price * cf + ai_del
        irr = (invoice + coupons - dirty) / (dirty * d / 360.0 - ctime)
        gross = clean - futures_price * cf
        carry = ai_del + coupons - ai_spot - (dirty * repo * d / 360.0 - ctime * repo)
        rows.append(
            {
                "coupon": c / 100.0,
                "dated": dated,
                "maturity": mat,
                "clean": clean,
                "conversion_factor": cf,
                "gross_basis": gross,
                "carry": carry,
                "net_basis": gross - carry,
                "implied_repo": irr,
            }
        )
    ctd = max(range(len(rows)), key=lambda i: rows[i]["implied_repo"])
    return {
        "contract": contract,
        "year": year,
        "month": month,
        "futures_price": futures_price,
        "repo": repo,
        "settlement": iso(settle),
        "delivery": iso(last_day),
        "bonds": rows,
        "ctd_index": ctd,
    }


# ---------------------------------------------------------------- driver


def generate(fixtures: Path) -> JsonDict:
    ql.IndexManager.instance().clearHistories()
    today = qd(TODAY_ISO)
    ql.Settings.instance().evaluationDate = today
    yesterday = "2025-01-14"
    fixings = {
        "USD": load_fixings(fixtures / "sofr_2024.csv", yesterday),
        "EUR": load_fixings(fixtures / "estr_2024.csv", yesterday),
        "GBP": load_fixings(fixtures / "sonia_2024.csv", yesterday),
    }
    specs, indices = make_curves(today, fixings)
    curves_out: list[JsonDict] = []
    built: dict[str, tuple[list[Any], list[float]]] = {}
    for ccy in ("USD", "EUR", "GBP"):
        built[ccy] = bootstrap(specs[ccy], today)
        curves_out.append(curve_json(specs[ccy], *built[ccy]))
    eur6m = make_euribor_curve(today, specs["EUR"].handle)
    d6, f6 = bootstrap(eur6m, today)
    curves_out.append(curve_json(eur6m, d6, f6))

    # swaps: par rates first, then prices
    cases = swap_cases(today)
    par: dict[str, float] = {}
    for case in cases:
        if case.rate is None:
            s = price_swap(case, indices[case.ccy], specs[case.ccy].handle, {})
            par[case.swap_id] = float(s.fairRate()) + case.rate_offset
    swaps_out: list[JsonDict] = []
    base_pv: dict[str, float] = {}
    for case in cases:
        s = price_swap(case, indices[case.ccy], specs[case.ccy].handle, par)
        rate = case.rate if case.rate is not None else par[case.swap_id]
        pv = float(s.NPV())
        base_pv[case.swap_id] = pv
        swaps_out.append(
            {
                "id": case.swap_id,
                "currency": case.ccy,
                "kind": case.kind,
                "start": iso(case.start),
                "end": iso(case.end),
                "notional": 1.0e8,
                "fixed_rate": rate,
                "payer": case.payer,
                "lookback": case.lookback,
                "lockout": case.lockout,
                "observation_shift": case.shift,
                "pv": pv,
                "par_rate": float(s.fairRate()),
                "fixed_leg_bps": float(s.fixedLegBPS()),
            }
        )

    # parallel DV01 by central bump of every quote of the currency's curve
    def rebuild(ccy: str, shift: float) -> None:
        for inst in specs[ccy].insts:
            per_rate = -100.0 if inst.kind.startswith("future") else 1.0
            inst.quote += shift * per_rate
        bootstrap(specs[ccy], today)

    for ccy in ("USD", "EUR", "GBP"):
        pv_up: dict[str, float] = {}
        rebuild(ccy, BP)
        for case in cases:
            if case.ccy == ccy:
                s = price_swap(case, indices[ccy], specs[ccy].handle, par)
                pv_up[case.swap_id] = float(s.NPV())
        rebuild(ccy, -2 * BP)
        for row in swaps_out:
            if row["currency"] == ccy:
                case = next(c for c in cases if c.swap_id == row["id"])
                s = price_swap(case, indices[ccy], specs[ccy].handle, par)
                row["dv01_parallel_bump"] = (pv_up[row["id"]] - float(s.NPV())) / 2.0
        rebuild(ccy, BP)

    # key-rate ladder of the USD 10Y par swap and the USD 5Y off-market swap by central bumps
    ladders: list[JsonDict] = []
    for swap_id in ("USD-SPOT-120M-PAR", "USD-SPOT-60M-OFF"):
        case = next(c for c in cases if c.swap_id == swap_id)
        krs: list[float] = []
        for inst in specs["USD"].insts:
            per_rate = -100.0 if inst.kind.startswith("future") else 1.0
            base_q = inst.quote
            vals = []
            for sgn in (1.0, -1.0):
                inst.quote = base_q + sgn * BP * per_rate
                bootstrap(specs["USD"], today)
                vals.append(float(price_swap(case, indices["USD"], specs["USD"].handle, par).NPV()))
            inst.quote = base_q
            krs.append((vals[0] - vals[1]) / 2.0)
        bootstrap(specs["USD"], today)
        ladders.append(
            {
                "swap": swap_id,
                "tenors": [i.tenor for i in specs["USD"].insts],
                "key_rate_dv01": krs,
            }
        )

    # SOFR futures priced on the USD curve
    futures_out: list[JsonDict] = []
    for y, m, three in [(2025, mm, False) for mm in range(2, 8)] + [
        (2025, mm, True) for mm in (3, 6, 9, 12)
    ]:
        inst = future_inst(indices["USD"], today, y, m, 0.0, three)
        futures_out.append(
            {
                "code": inst.label,
                "kind": inst.kind,
                **inst.spec,
                "price": inst.implied(),
            }
        )

    bonds_out = [price_bond(c, specs["USD"].handle.currentLink()) for c in bond_cases()]

    cf_out: list[JsonDict] = []
    for contract, year, month, basket in CME_BASKETS:
        rounding = 1 if contract in ("TU", "FV", "Z3N") else 3
        for c, dated, mat in basket:
            cf_out.append(
                {
                    "exchange": "CME",
                    "contract": contract,
                    "year": year,
                    "month": month,
                    "coupon": c / 100.0,
                    "dated": dated,
                    "maturity": mat,
                    "factor": cme_cf(c / 100.0, mat, year, month, rounding),
                }
            )
    for contract, delivery, market, basket in EUREX:
        for c, dated, mat in basket:
            clean = bond_clean_at(market, c, dated, mat, 0.06, qd(delivery))
            cf_out.append(
                {
                    "exchange": "EUREX",
                    "contract": contract,
                    "delivery": delivery,
                    "coupon": c / 100.0,
                    "dated": dated,
                    "maturity": mat,
                    "factor": round(clean / 100.0, 6),
                }
            )
    for c, dated, mat in LONG_GILT:
        clean = bond_clean_at("GILT", c, dated, mat, 0.04, ql.Date(1, 3, 2025))
        cf_out.append(
            {
                "exchange": "ICE",
                "contract": "G",
                "year": 2025,
                "month": 3,
                "coupon": c / 100.0,
                "dated": dated,
                "maturity": mat,
                "factor": round(clean / 100.0, 7),
            }
        )

    ctd_out = [basis_rows(k, 2025, 3, 0.0430, today) for k in ("TU", "FV", "TY", "US", "UB")]

    fixings_out = {
        ccy: {"dates": [d for d, _ in rows], "rates": [r for _, r in rows]}
        for ccy, rows in fixings.items()
    }
    count = len(swaps_out) + len(bonds_out) + len(futures_out) + len(cf_out)
    count += sum(len(c["bonds"]) for c in ctd_out)
    return {
        "golden_version": GOLDEN_VERSION,
        "description": (
            "QuantLib golden values for rates-analytics (API_SPEC.md Section 8). Generated by "
            "research/ratesresearch/goldens/generator.py; quotes are declared values "
            "(DECISIONS.md DR-P1-08); past fixings from data/fixtures."
        ),
        "quantlib_version": ql.__version__,
        "python_version": platform.python_version(),
        "platform": platform.system(),
        "valuation_date": TODAY_ISO,
        "hull_white": {"a": HW_A, "sigma": HW_SIGMA},
        "instrument_count": count,
        "calendars": {name: holidays(cal, 2000, 2060) for name, cal in CALENDARS.items()},
        "fixings": fixings_out,
        "curves": curves_out,
        "swaps": swaps_out,
        "key_rate_ladders": ladders,
        "sofr_futures": futures_out,
        "bonds": bonds_out,
        "conversion_factors": cf_out,
        "basis": ctd_out,
    }
