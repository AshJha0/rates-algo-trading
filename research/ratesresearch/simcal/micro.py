"""Swap print microstructure estimators (pre-analysis note SIM3-CAL-001).

Everything is computed from per-day sufficient statistics, so that the day bootstrap is a
weighted sum of days followed by the same estimator: no estimator sees a partial day.

Model behind the estimators. A print of tenor T at time t has rate

    r = M(t) + q x,    q = +1 or -1 (unknown side),  x >= 0 (half-spread paid on that print),

with M the unobserved mid, a random walk with variance ``sigma^2`` per minute, plus a drift
after a trade: E[M(t + h) - M(t) | q] = q a(h) (adverse selection, in bp). For two prints i < j
of one tenor and day, with q independent across prints,

    E[(r_j - r_i)^2] = eta_i^2 + eta_j^2 + sigma^2 (t_j - t_i),    eta^2 = E[x^2].

``eta`` (the root-mean-square deviation of a print from the mid) is the dispersion estimate of
the effective half-spread. It needs no trade direction. Its bias: prints at the mid (dealer to
dealer, compression) pull it down; differences between non-identical swaps in one tenor bucket
(maturity or start date a few days off) and stale or late-reported prints push it up.

Direction is not reported. The classification is s = sign(r - m), m = median of the last
prints of the tenor (up to 5, within 10 minutes), or the tick rule. The raw signed change to a
later print, -s (r_ref - r), estimates (1 - 2 eps) (x - a(h) - rho x) where eps is the
probability of a wrong sign and rho the correlation of the side of the reference print with
the side of this one. ``eps`` is estimated from the same data under a normal error in m:
eps = Phi(-eta / tau), tau^2 = E[(r - m)^2] - eta^2.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch.simcal import DESK_TENORS, RESAMPLES, SEED
from ratesresearch.simcal.prints import SESSION_END_S, SESSION_START_S

FloatArray = npt.NDArray[np.float64]
IntArray = npt.NDArray[np.int64]
Stats = dict[str, FloatArray]

MAX_LAG = 5
MAX_GAP_S = 1800.0
BIN_S = 1800.0
N_BINS = int((SESSION_END_S - SESSION_START_S) / BIN_S)
HORIZONS_S = (1.0, 5.0, 30.0, 60.0, 300.0)
SEARCH_S = (4.0, 10.0, 30.0, 60.0, 120.0)
MID_PRINTS = 5
MID_WINDOW_S = 600.0
N_BUCKETS = 4
N_GROUPS = 3
LONG_GAP_S = 300.0
GAP_EDGES_S = np.array([0.0, 2.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1200.0, 1800.0])
N_GAPS = len(GAP_EDGES_S) - 1
SHORT_BINS = 2  # gaps under 10 s give the level 2 h^2
PLATEAU_BINS = (3, 5)  # gaps of 30 to 120 s give h^2 + (h - a)^2
ROBUST_SLOPE_FROM_BIN = 5  # gaps of 120 s and more give the robust slope and intercept
# histogram grid for |rate difference| in bp: 0, then 240 log-spaced edges from 0.001 to 30
ABS_EDGES = np.concatenate([[0.0], np.logspace(-3.0, math.log10(30.0), 240)])
NORMAL_MEDIAN_ABS = 0.6744897501960817  # median of |Z| for a standard normal Z
EVENT_NAMES = ("release_0830", "auction_1300", "fomc_1400")
EVENT_WINDOWS_S = ((8.5 * 3600, 8.5 * 3600 + 300), (13 * 3600, 13 * 3600 + 300), (14 * 3600, 14.5 * 3600))


def phi(x: FloatArray) -> FloatArray:
    """Standard normal distribution function."""
    out: FloatArray = 0.5 * (1.0 + np.vectorize(math.erf, otypes=[np.float64])(x / math.sqrt(2.0)))
    return out


@dataclass(frozen=True)
class Sample:
    """Prints sorted by (day, tenor, time), with integer codes for day and tenor."""

    days: tuple[str, ...]
    day: IntArray
    tenor: IntArray
    t: FloatArray
    r: FloatArray
    bucket: IntArray
    group: IntArray
    dv01: FloatArray

    @property
    def n_days(self) -> int:
        return len(self.days)


def make_sample(prints: pd.DataFrame, days: tuple[str, ...] | None = None) -> Sample:
    p = prints.sort_values(["day", "tenor", "t", "rate_bp"], kind="stable")
    all_days = tuple(sorted(p["day"].unique())) if days is None else days
    day_code = {d: i for i, d in enumerate(all_days)}
    tenor_code = {tn: i for i, tn in enumerate(DESK_TENORS)}
    return Sample(
        days=all_days,
        day=np.array([day_code[d] for d in p["day"]], dtype=np.int64),
        tenor=np.array([tenor_code[float(x)] for x in p["tenor"]], dtype=np.int64),
        t=p["t"].to_numpy(dtype=np.float64),
        r=p["rate_bp"].to_numpy(dtype=np.float64),
        bucket=p["bucket"].to_numpy(dtype=np.int64),
        group=p["group"].to_numpy(dtype=np.int64),
        dv01=p["dv01"].to_numpy(dtype=np.float64),
    )


def pairs(s: Sample, max_lag: int = MAX_LAG, max_gap_s: float = MAX_GAP_S) -> tuple[IntArray, IntArray]:
    """Index pairs (i, j), j = i + k for k up to ``max_lag``, same day and tenor, gap in range."""
    n = len(s.t)
    ii: list[IntArray] = []
    jj: list[IntArray] = []
    for k in range(1, max_lag + 1):
        if n <= k:
            break
        i = np.arange(n - k, dtype=np.int64)
        j = i + k
        ok = (s.day[i] == s.day[j]) & (s.tenor[i] == s.tenor[j]) & (s.t[j] - s.t[i] <= max_gap_s)
        ii.append(i[ok])
        jj.append(j[ok])
    if not ii:
        e = np.zeros(0, dtype=np.int64)
        return e, e
    return np.concatenate(ii), np.concatenate(jj)


def _overlap(a: FloatArray, b: FloatArray, lo: float, hi: float) -> FloatArray:
    out: FloatArray = np.clip(np.minimum(b, hi) - np.maximum(a, lo), 0.0, None)
    return out


def _accumulate(x: FloatArray, y: FloatArray, key: IntArray, n_keys: int) -> tuple[FloatArray, FloatArray]:
    """Per-key X'X and X'y: shapes (n_keys, p, p) and (n_keys, p)."""
    p = x.shape[1]
    xtx = np.zeros((n_keys, p, p))
    xty = np.zeros((n_keys, p))
    order = np.argsort(key, kind="stable")
    ks = key[order]
    bounds = np.searchsorted(ks, np.arange(n_keys + 1))
    for k in range(n_keys):
        rows = order[bounds[k] : bounds[k + 1]]
        if len(rows):
            xk = x[rows]
            xtx[k] = xk.T @ xk
            xty[k] = xk.T @ y[rows]
    return xtx, xty


def event_flags(days: tuple[str, ...], calendar: pd.DataFrame, auctions: pd.DataFrame) -> FloatArray:
    """(n_days, 3) indicators: 08:30 release day, coupon auction day, FOMC statement day."""
    rel = set(calendar.loc[calendar["time_et"] == "08:30", "date"])
    fomc = set(calendar.loc[calendar["event"] == "fomc", "date"])
    auc = set(auctions["auction_date"])
    return np.array([[d in rel, d in auc, d in fomc] for d in days], dtype=np.float64)


def day_stats(s: Sample, events: FloatArray | None = None) -> Stats:
    """Sufficient statistics per day (leading axis) for every estimator of this module."""
    nd, nt = s.n_days, len(DESK_TENORS)
    ev = np.zeros((nd, len(EVENT_NAMES))) if events is None else events
    i, j = pairs(s)
    gap = (s.t[j] - s.t[i]) / 60.0
    y = (s.r[j] - s.r[i]) ** 2
    key = s.day[i] * nt + s.tenor[i]
    ones = np.ones(len(i))
    out: Stats = {}

    xtx, xty = _accumulate(np.column_stack([ones, gap]), y, key, nd * nt)
    out["basic_xtx"] = xtx.reshape(nd, nt, 2, 2)
    out["basic_xty"] = xty.reshape(nd, nt, 2)

    # consecutive prints only: no trade in between, so the pair sees one trade's drift
    i1, j1 = pairs(s, max_lag=1)
    gap1 = (s.t[j1] - s.t[i1]) / 60.0
    y1 = (s.r[j1] - s.r[i1]) ** 2
    key1 = s.day[i1] * nt + s.tenor[i1]
    long = gap1 >= LONG_GAP_S / 60.0
    x1 = np.column_stack([np.ones(len(i1)), gap1])
    xtx, xty = _accumulate(x1[long], y1[long], key1[long], nd * nt)
    out["long_xtx"] = xtx.reshape(nd, nt, 2, 2)
    out["long_xty"] = xty.reshape(nd, nt, 2)
    gbin = np.clip(np.searchsorted(GAP_EDGES_S, gap1 * 60.0, side="right") - 1, 0, N_GAPS - 1)
    for name, val in (("sig_y", y1), ("sig_gap", gap1), ("sig_n", np.ones(len(i1)))):
        acc = np.zeros((nd, nt, N_GAPS))
        np.add.at(acc, (s.day[i1], s.tenor[i1], gbin), val)
        out[name] = acc
    hist = np.zeros((nd, nt, N_GAPS, len(ABS_EDGES) - 1))
    hbin = np.clip(np.searchsorted(ABS_EDGES, np.sqrt(y1), side="right") - 1, 0, len(ABS_EDGES) - 2)
    np.add.at(hist, (s.day[i1], s.tenor[i1], gbin, hbin), 1.0)
    out["absd_hist"] = hist

    for name, cls, k in (("bucket", s.bucket, N_BUCKETS), ("group", s.group, N_GROUPS)):
        x = np.zeros((len(i), k + 1))
        np.add.at(x, (np.arange(len(i)), cls[i]), 1.0)
        np.add.at(x, (np.arange(len(i)), cls[j]), 1.0)
        x[:, k] = gap
        xtx, xty = _accumulate(x, y, key, nd * nt)
        out[f"{name}_xtx"] = xtx.reshape(nd, nt, k + 1, k + 1)
        out[f"{name}_xty"] = xty.reshape(nd, nt, k + 1)

    # seasonality: intercept, minutes of the pair inside each 30-minute bin, minutes inside each
    # event window on days that have the event (the event coefficient is an excess over the bin)
    cols = [ones]
    for b in range(N_BINS):
        lo = SESSION_START_S + b * BIN_S
        cols.append(_overlap(s.t[i], s.t[j], lo, lo + BIN_S) / 60.0)
    for e, (lo, hi) in enumerate(EVENT_WINDOWS_S):
        cols.append(_overlap(s.t[i], s.t[j], lo, hi) / 60.0 * ev[s.day[i], e])
    xs = np.column_stack(cols)
    xtx, xty = _accumulate(xs, y, key, nd * nt)
    out["season_xtx"] = xtx.reshape(nd, nt, xs.shape[1], xs.shape[1])
    out["season_xty"] = xty.reshape(nd, nt, xs.shape[1])
    out["event_days"] = ev.copy()

    # arrivals and sizes
    b_of_t = np.clip(((s.t - SESSION_START_S) // BIN_S).astype(np.int64), 0, N_BINS - 1)
    arr = np.zeros((nd, nt, N_BINS))
    np.add.at(arr, (s.day, s.tenor, b_of_t), 1.0)
    out["arrivals"] = arr
    cnt = np.zeros((nd, nt, N_BUCKETS))
    np.add.at(cnt, (s.day, s.tenor, s.bucket), 1.0)
    out["bucket_count"] = cnt
    grp = np.zeros((nd, nt, N_GROUPS))
    np.add.at(grp, (s.day, s.tenor, s.group), 1.0)
    out["group_count"] = grp
    dv = np.zeros((nd, nt))
    np.add.at(dv, (s.day, s.tenor), s.dv01)
    out["dv01_sum"] = dv
    out["one"] = np.ones((nd, 1))

    out.update(_markout_stats(s))
    return out


def classify(s: Sample) -> tuple[FloatArray, FloatArray, FloatArray]:
    """Per print: sign against the trailing mid, deviation from the trailing mid, tick sign.

    The trailing mid is the median of the last ``MID_PRINTS`` earlier prints of the same tenor
    and day within ``MID_WINDOW_S`` (at least 2); sign 0 where it does not exist or ties.
    """
    n = len(s.t)
    s_mid = np.zeros(n)
    dev = np.full(n, np.nan)
    s_tick = np.zeros(n)
    start = 0
    key = s.day * 100 + s.tenor
    while start < n:
        end = start
        while end < n and key[end] == key[start]:
            end += 1
        t = s.t[start:end]
        r = s.r[start:end]
        last_tick = 0.0
        first_earlier = np.searchsorted(t, t, side="left")  # prints strictly earlier in time
        for m in range(end - start):
            hi = int(first_earlier[m])
            lo = max(hi - MID_PRINTS, int(np.searchsorted(t, t[m] - MID_WINDOW_S, side="left")))
            if hi - lo >= 2:
                d = r[m] - float(np.median(r[lo:hi]))
                dev[start + m] = d
                s_mid[start + m] = float(np.sign(d))
            if m > 0 and r[m] != r[m - 1]:
                last_tick = float(np.sign(r[m] - r[m - 1]))
            s_tick[start + m] = last_tick
        start = end
    return s_mid, dev, s_tick


def reference(s: Sample, horizon_s: float, search_s: float) -> tuple[FloatArray, FloatArray, IntArray]:
    """Per print: mean rate of the prints at the first later time in [t + h, t + h + search],
    the delay of that time after the print (NaN if none), and the index of its first print."""
    n = len(s.t)
    ref = np.full(n, np.nan)
    delay = np.full(n, np.nan)
    idx = np.full(n, -1, dtype=np.int64)
    key = s.day * 100 + s.tenor
    start = 0
    while start < n:
        end = start
        while end < n and key[end] == key[start]:
            end += 1
        t = s.t[start:end]
        r = s.r[start:end]
        first = np.searchsorted(t, t + horizon_s, side="left")
        for m in range(end - start):
            f = int(first[m])
            if f < end - start and t[f] <= t[m] + horizon_s + search_s:
                g = int(np.searchsorted(t, t[f], side="right"))
                ref[start + m] = float(r[f:g].mean())
                delay[start + m] = t[f] - t[m]
                idx[start + m] = start + f
        start = end
    return ref, delay, idx


def _markout_stats(s: Sample) -> Stats:
    nd, nt, nh = s.n_days, len(DESK_TENORS), len(HORIZONS_S)
    s_mid, dev, s_tick = classify(s)
    out: Stats = {}
    d2 = np.zeros((nd, nt, N_BUCKETS))
    dn = np.zeros((nd, nt, N_BUCKETS))
    ok = np.isfinite(dev)
    np.add.at(d2, (s.day[ok], s.tenor[ok], s.bucket[ok]), dev[ok] ** 2)
    np.add.at(dn, (s.day[ok], s.tenor[ok], s.bucket[ok]), 1.0)
    out["dev2_sum"] = d2
    out["dev2_n"] = dn
    for name, sign in (("mid", s_mid), ("tick", s_tick)):
        mo = np.zeros((nd, nt, N_BUCKETS, nh))
        cnt = np.zeros((nd, nt, N_BUCKETS, nh))
        dl = np.zeros((nd, nt, N_BUCKETS, nh))
        ss = np.zeros((nd, nt, N_BUCKETS, nh))
        for h, (hz, sr) in enumerate(zip(HORIZONS_S, SEARCH_S, strict=True)):
            ref, delay, idx = reference(s, hz, sr)
            use = np.isfinite(ref) & (sign != 0.0)
            at = (s.day[use], s.tenor[use], s.bucket[use], np.full(int(use.sum()), h))
            np.add.at(mo, at, sign[use] * (ref[use] - s.r[use]))
            np.add.at(cnt, at, 1.0)
            np.add.at(dl, at, delay[use])
            np.add.at(ss, at, sign[use] * sign[idx[use]])
        out[f"mo_{name}_sum"] = mo
        out[f"mo_{name}_n"] = cnt
        out[f"mo_{name}_delay"] = dl
        out[f"mo_{name}_signsign"] = ss
    return out


# ------------------------------------------------------------------ estimators on summed stats


def _solve(xtx: FloatArray, xty: FloatArray) -> FloatArray:
    out: FloatArray = np.linalg.lstsq(xtx, xty, rcond=None)[0]
    return out


def _root(v: FloatArray) -> FloatArray:
    out: FloatArray = np.sqrt(np.clip(v, 0.0, None))
    return out


def est_basic(total: Stats) -> FloatArray:
    """(n_tenor + 1, 2): eta (bp) and sigma (bp per sqrt-minute) per tenor; last row pooled."""
    nt = len(DESK_TENORS)
    out = np.zeros((nt + 1, 2))
    for k in range(nt):
        beta = _solve(total["basic_xtx"][k], total["basic_xty"][k])
        out[k] = (math.sqrt(max(beta[0], 0.0) / 2.0), math.sqrt(max(beta[1], 0.0)))
    beta = _solve(total["basic_xtx"].sum(axis=0), total["basic_xty"].sum(axis=0))
    out[nt] = (math.sqrt(max(beta[0], 0.0) / 2.0), math.sqrt(max(beta[1], 0.0)))
    return out


def est_signature(total: Stats) -> dict[str, FloatArray]:
    """Direction-free split of the half-spread into a transitory and a permanent part.

    With a drift a(g) of the mid in the direction of a trade, two consecutive prints a gap g
    apart have E[(r_j - r_i)^2] = h^2 + (h - a(g))^2 + sigma^2 g (h the root-mean-square
    half-spread, sigma^2 the variance per minute of the mid between trades of this tenor). So

    * pairs at least LONG_GAP_S apart give sigma^2 (slope; their intercept ``c`` is reported
      but is too sensitive to the slope to be used);
    * the level L(g) = mean square difference minus sigma^2 g falls from 2 h^2 at g near zero
      to h^2 + (h - a)^2 once the drift is complete; h is read from gaps under 10 s, the
      plateau from gaps of 30 to 120 s, the realised half-spread is sqrt(plateau - h^2) and
      the adverse drift is a = h - sqrt(plateau - h^2).

    Bias: if the drift starts within the first 10 s, or if prints seconds apart are pieces of
    one order on one side, the short-gap level is below 2 h^2, h is too small and the drift is
    understated (each 0.01 bp of h moves the drift by about 0.02 to 0.03 bp).

    Returns, per tenor and pooled (last row; tenors weighted by pair count): ``sigma``, ``c``,
    ``level`` (per gap bin), ``n`` (pairs per gap bin), ``h``, ``realised``, ``drift``, and
    ``drift_curve`` (h - sqrt(L(g) - h^2) per gap bin). NaN where a bin has no pair.
    """
    nt = len(DESK_TENORS)
    sigma2 = np.zeros(nt + 1)
    c = np.zeros(nt + 1)
    n = np.vstack([total["sig_n"], total["sig_n"].sum(axis=0)])
    level = np.full((nt + 1, N_GAPS), np.nan)
    long_n = np.zeros(nt)
    for k in range(nt):
        beta = _solve(total["long_xtx"][k], total["long_xty"][k])
        c[k], sigma2[k] = beta[0], max(beta[1], 0.0)
        long_n[k] = total["long_xtx"][k][0, 0]
        with np.errstate(invalid="ignore", divide="ignore"):
            level[k] = (total["sig_y"][k] - sigma2[k] * total["sig_gap"][k]) / total["sig_n"][k]
    w = total["sig_n"]
    with np.errstate(invalid="ignore", divide="ignore"):
        level[nt] = np.nansum(np.where(w > 0, level[:nt] * w, 0.0), axis=0) / w.sum(axis=0)
    c[nt] = float(np.sum(c[:nt] * long_n) / max(long_n.sum(), 1.0))
    sigma2[nt] = float(np.sum(sigma2[:nt] * long_n) / max(long_n.sum(), 1.0))
    ns = n[:, :SHORT_BINS]
    with np.errstate(invalid="ignore", divide="ignore"):
        short = np.nansum(np.where(ns > 0, level[:, :SHORT_BINS] * ns, 0.0), axis=1) / ns.sum(axis=1)
    h = _root(short / 2.0)
    npl = n[:, PLATEAU_BINS[0] : PLATEAU_BINS[1]]
    lpl = level[:, PLATEAU_BINS[0] : PLATEAU_BINS[1]]
    with np.errstate(invalid="ignore", divide="ignore"):
        plateau = np.nansum(np.where(npl > 0, lpl * npl, 0.0), axis=1) / npl.sum(axis=1)
    realised = _root(plateau - h * h)
    curve = h[:, None] - _root(level - (h * h)[:, None])
    return {
        "sigma": _root(sigma2),
        "c": c,
        "level": level,
        "n": n,
        "h": h,
        "realised": realised,
        "drift": h - realised,
        "drift_curve": curve,
    }


def hist_median(hist: FloatArray) -> float:
    """Median of the histogram on ABS_EDGES, linear inside the bin; NaN if empty."""
    n = float(hist.sum())
    if n <= 0:
        return float("nan")
    cum = np.cumsum(hist)
    k = int(np.searchsorted(cum, 0.5 * n))
    before = float(cum[k - 1]) if k > 0 else 0.0
    frac = (0.5 * n - before) / max(float(hist[k]), 1e-12)
    return float(ABS_EDGES[k] + frac * (ABS_EDGES[k + 1] - ABS_EDGES[k]))


def est_robust(total: Stats) -> dict[str, FloatArray]:
    """Median-based version of the signature, insensitive to the tail of odd prints.

    For consecutive prints a gap g apart, V(g) = (median |r_j - r_i| / 0.6745)^2 is the variance
    a normal distribution with that median would have. V(g) = 2 eta^2 + sigma^2 g is fitted by
    least squares (weights: pair counts) on the gap bins of 120 s and more: ``sigma`` (bp per
    sqrt-minute) and ``eta_long`` (bp). ``eta_short`` is the same quantity read directly from
    gaps under 10 s: sqrt(V / 2) with no fit. ``level`` is V(g) - sigma^2 g per bin.
    Rows: tenors, then all tenors pooled (histograms added).
    """
    nt = len(DESK_TENORS)
    hist = np.concatenate([total["absd_hist"], total["absd_hist"].sum(axis=0, keepdims=True)])
    n = hist.sum(axis=2)
    gsum = np.concatenate([total["sig_gap"], total["sig_gap"].sum(axis=0, keepdims=True)])
    with np.errstate(invalid="ignore", divide="ignore"):
        gap = gsum / n
    v = np.full((nt + 1, N_GAPS), np.nan)
    sigma = np.full(nt + 1, np.nan)
    eta_long = np.full(nt + 1, np.nan)
    eta_short = np.full(nt + 1, np.nan)
    for k in range(nt + 1):
        for g in range(N_GAPS):
            v[k, g] = (hist_median(hist[k, g]) / NORMAL_MEDIAN_ABS) ** 2
        sel = np.arange(ROBUST_SLOPE_FROM_BIN, N_GAPS)
        sel = sel[n[k, sel] >= 10]
        if len(sel) >= 2:
            w = n[k, sel]
            x = np.column_stack([np.ones(len(sel)), gap[k, sel]])
            beta = _solve(x.T @ (x * w[:, None]), x.T @ (v[k, sel] * w))
            sigma[k] = math.sqrt(max(beta[1], 0.0))
            eta_long[k] = math.sqrt(max(beta[0], 0.0) / 2.0)
        short = hist[k, :SHORT_BINS].sum(axis=0)
        eta_short[k] = hist_median(short) / NORMAL_MEDIAN_ABS / math.sqrt(2.0)
    level = v - (sigma**2)[:, None] * gap
    return {"sigma": sigma, "eta_long": eta_long, "eta_short": eta_short, "level": level, "v": v, "n": n}


def est_class(total: Stats, name: str) -> FloatArray:
    """(n_tenor + 1, k): eta (bp) per class (size bucket or platform group); last row pooled.

    The pooled row shares the class noise across tenors and lets the slope differ by tenor.
    A class with no pair in a tenor gives NaN.
    """
    xtx, xty = total[f"{name}_xtx"], total[f"{name}_xty"]
    nt, k = len(DESK_TENORS), xtx.shape[-1] - 1
    out = np.full((nt + 1, k), np.nan)
    for tn in range(nt):
        have = np.diag(xtx[tn])[:k] > 0
        beta = _solve(xtx[tn], xty[tn])
        out[tn, have] = _root(beta[:k][have])
    big = np.zeros((k + nt, k + nt))
    rhs = np.zeros(k + nt)
    for tn in range(nt):
        sel = [*range(k), k + tn]
        big[np.ix_(sel, sel)] += xtx[tn]
        rhs[sel] += xty[tn]
    out[nt] = _root(_solve(big, rhs)[:k])
    return out


def est_season(total: Stats, sigma2: FloatArray) -> FloatArray:
    """Variance multipliers by 30-minute bin, pooled over tenors, then the three event excesses.

    Each tenor's pairs are weighted by 1 / sigma_T^2 (``sigma2``: the basic slope of the tenor)
    so that a multiplier of 1 means the tenor's average rate of variance; tenors keep their own
    intercept. Returns N_BINS + 3 numbers; the bin multipliers are rescaled to mean 1 and the
    event numbers are in the same unit (extra variance per minute, as a multiple of the average).
    """
    nt = len(DESK_TENORS)
    p = total["season_xtx"].shape[-1] - 1
    big = np.zeros((nt + p, nt + p))
    rhs = np.zeros(nt + p)
    for tn in range(nt):
        if sigma2[tn] <= 0:
            continue
        sel = [tn, *range(nt, nt + p)]
        big[np.ix_(sel, sel)] += total["season_xtx"][tn]
        rhs[sel] += total["season_xty"][tn] / sigma2[tn]
    beta = _solve(big, rhs)[nt:]
    scale = float(np.mean(beta[:N_BINS]))
    out: FloatArray = beta / scale if scale > 0 else beta
    return out


def est_arrivals(total: Stats) -> FloatArray:
    """(n_tenor, N_BINS): mean prints per day in each 30-minute bin."""
    out: FloatArray = total["arrivals"] / float(total["one"][0])
    return out


def est_markout(total: Stats, name: str = "mid") -> dict[str, FloatArray]:
    """Markout quantities per size bucket (rows 0..3) and all sizes (row 4), by horizon.

    Tenors are pooled by print count. ``raw`` = mean of s (r_ref - r) in bp (negative: the later
    print is back towards the mid); ``delay`` = mean seconds to the reference print; ``n``;
    ``eps`` = estimated probability of a wrong sign (trailing-mid classification only);
    ``eta`` = pooled noise of the bucket; ``rho`` = sign correlation with the reference print
    divided by (1 - 2 eps)^2; ``drift_hi`` = eta + raw / (1 - 2 eps) (assumes rho = 0, an upper
    figure when sides are positively correlated); ``drift_lo`` = eta (1 - rho) + raw / (1 - 2 eps).
    """
    mo, n = total[f"mo_{name}_sum"], total[f"mo_{name}_n"]
    eta_b = est_class(total, "bucket")[-1]
    eta_all = float(est_signature(total)["h"][-1])
    rows = []
    for b in range(N_BUCKETS + 1):
        sl: Any = slice(None) if b == N_BUCKETS else b
        axes = (0, 1) if b == N_BUCKETS else (0,)
        m_sum = mo[:, sl].sum(axis=axes)
        m_n = n[:, sl].sum(axis=axes)
        eta = eta_all if b == N_BUCKETS else eta_b[b]
        d2 = total["dev2_sum"][:, sl].sum() / max(total["dev2_n"][:, sl].sum(), 1.0)
        tau = math.sqrt(max(d2 - eta * eta, 1e-12))
        eps = float(phi(np.array([-eta / tau]))[0]) if name == "mid" else float("nan")
        rows.append(
            (
                m_sum / np.maximum(m_n, 1.0),
                m_n,
                total[f"mo_{name}_delay"][:, sl].sum(axis=axes) / np.maximum(m_n, 1.0),
                total[f"mo_{name}_signsign"][:, sl].sum(axis=axes) / np.maximum(m_n, 1.0),
                eta,
                eps,
            )
        )
    raw = np.array([r[0] for r in rows])
    eps = np.array([r[5] for r in rows])[:, None]
    eta = np.array([r[4] for r in rows])[:, None]
    att = 1.0 - 2.0 * eps
    with np.errstate(invalid="ignore", divide="ignore"):
        rho = np.clip(np.array([r[3] for r in rows]) / att**2, -1.0, 1.0)
        hi = eta + raw / att
        lo = eta * (1.0 - np.clip(rho, 0.0, 1.0)) + raw / att
    return {
        "raw": raw,
        "n": np.array([r[1] for r in rows]),
        "delay": np.array([r[2] for r in rows]),
        "eps": eps[:, 0],
        "eta": eta[:, 0],
        "rho": rho,
        "drift_hi": hi,
        "drift_lo": lo,
    }


# ------------------------------------------------------------------ bootstrap by day


def total_of(stats: Stats, weights: FloatArray | None = None) -> Stats:
    """Sum over days, optionally with a multiplicity per day (one bootstrap resample)."""
    if weights is None:
        return {k: v.sum(axis=0) for k, v in stats.items()}
    return {k: np.tensordot(weights, v, axes=1) for k, v in stats.items()}


def day_weights(n_days: int, resamples: int = RESAMPLES, seed: int = SEED) -> FloatArray:
    """(resamples, n_days) multiplicities of days drawn with replacement (PCG64, fixed seed)."""
    rng = np.random.Generator(np.random.PCG64(seed))
    draws = rng.integers(0, n_days, size=(resamples, n_days))
    w = np.zeros((resamples, n_days))
    for k in range(resamples):
        w[k] = np.bincount(draws[k], minlength=n_days)
    return w


def interval(
    stats: Stats, estimator: Any, resamples: int = RESAMPLES, seed: int = SEED
) -> tuple[FloatArray, FloatArray, FloatArray]:
    """Point estimate and the 2.5 and 97.5 percentiles over day resamples of ``estimator``."""
    point = np.asarray(estimator(total_of(stats)), dtype=np.float64)
    n_days = next(iter(stats.values())).shape[0]
    w = day_weights(n_days, resamples, seed)
    draws = np.array([np.asarray(estimator(total_of(stats, w[k]))) for k in range(resamples)])
    lo: FloatArray = np.nanpercentile(draws, 2.5, axis=0)
    hi: FloatArray = np.nanpercentile(draws, 97.5, axis=0)
    return point, lo, hi


def daily_close(prints: pd.DataFrame, lo_s: float = 14.5 * 3600, hi_s: float = 16 * 3600) -> pd.DataFrame:
    """Median print rate (bp, annual Act/360) per day and tenor in the close window; NaN if < 5."""
    w = prints[(prints["t"] >= lo_s) & (prints["t"] < hi_s)]
    g = w.groupby(["day", "tenor"])["rate_bp"].agg(["median", "count"]).reset_index()
    g.loc[g["count"] < 5, "median"] = np.nan
    table = g.pivot(index="day", columns="tenor", values="median")
    return table.reindex(columns=list(DESK_TENORS))
