"""Factor model of daily curve changes and the swap-spread factor (SIM3-CAL-001).

Curve factors: principal components of the covariance of daily changes (bp) of the Treasury
par yields at the desk tenors. A factor's loading vector has unit length, so a one-standard-
deviation day of factor k moves tenor i by ``loadings[i, k] * sd[k]`` bp.

Swap spread: daily swap level per tenor (median DTCC print near the close, converted from the
printed annual Act/360 rate to a semi-annual bond basis) minus the par yield. Its daily change
is regressed on the three curve-factor scores; the first principal component of the residuals
is the spread factor. Both legs are measured with error (a median of a few prints against a
fitted par curve quoted at a different minute), so the lag-1 autocovariance of the changes is
used to separate measurement noise from spread movement.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch.simcal import DESK_TENORS, RESAMPLES, SEED

FloatArray = npt.NDArray[np.float64]
PAR_COLUMNS = ("y2", "y3", "y5", "y7", "y10", "y20", "y30")
N_FACTORS = 3
SUBPERIODS = (
    ("2015-2018", "2015-01-01", "2018-12-31"),
    ("2019-2021", "2019-01-01", "2021-12-31"),
    ("2022-2025", "2022-01-01", "2025-12-31"),
)


@dataclass(frozen=True)
class Pca:
    loadings: FloatArray  # (n_tenor, n_tenor), columns are components, unit length
    sd: FloatArray  # standard deviation of each component score, bp per day
    explained: FloatArray  # share of total variance per component
    n: int


def load_par_curve(path: str | Path) -> pd.DataFrame:
    """Par yields in bp at the desk tenors, indexed by ISO date; days with a gap dropped."""
    df = pd.read_csv(path)
    out = df.set_index("date")[list(PAR_COLUMNS)].astype("float64") * 100.0
    renamed = out.rename(columns=dict(zip(PAR_COLUMNS, DESK_TENORS, strict=True)))
    return renamed.dropna()


def daily_changes(
    levels: pd.DataFrame, start: str, end: str, max_gap_days: int = 5
) -> pd.DataFrame:
    """Changes between consecutive rows at most ``max_gap_days`` calendar days apart, with the
    later date in [start, end]."""
    idx = pd.to_datetime(levels.index)
    gap = np.diff(idx.to_numpy()).astype("timedelta64[D]").astype(np.int64)
    diff = levels.diff().iloc[1:]
    keep = (gap <= max_gap_days) & (diff.index >= start) & (diff.index <= end)
    return diff.loc[keep].dropna()


def pca(changes: FloatArray) -> Pca:
    """PCA of the covariance; signs fixed so that component 1 sums positive, component 2 is
    positive at the longest tenor, component 3 is positive at the shortest tenor."""
    x = changes - changes.mean(axis=0, keepdims=True)
    cov = x.T @ x / (len(x) - 1)
    val, vec = np.linalg.eigh(cov)
    order = np.argsort(val)[::-1]
    val, vec = val[order], vec[:, order]
    signs = np.ones(vec.shape[1])
    signs[0] = np.sign(vec[:, 0].sum())
    if vec.shape[1] > 1:
        signs[1] = np.sign(vec[-1, 1])
    if vec.shape[1] > 2:
        signs[2] = np.sign(vec[0, 2])
    signs[signs == 0] = 1.0
    return Pca(vec * signs[None, :], np.sqrt(np.clip(val, 0.0, None)), val / val.sum(), len(x))


def score_sd(changes: FloatArray, loadings: FloatArray) -> FloatArray:
    """Standard deviation of the scores of ``changes`` on fixed loadings (bp per day)."""
    x = changes - changes.mean(axis=0, keepdims=True)
    out: FloatArray = (x @ loadings).std(axis=0, ddof=1)
    return out


def explained_by(changes: FloatArray, loadings: FloatArray) -> float:
    """Share of the total variance of ``changes`` captured by the span of ``loadings``."""
    x = changes - changes.mean(axis=0, keepdims=True)
    q, _ = np.linalg.qr(loadings)
    return float(((x @ q) ** 2).sum() / (x**2).sum())


def cosines(a: FloatArray, b: FloatArray) -> FloatArray:
    """Absolute cosine between matching columns of two loading matrices."""
    out: FloatArray = np.abs((a * b).sum(axis=0)) / (
        np.linalg.norm(a, axis=0) * np.linalg.norm(b, axis=0)
    )
    return out


def residual_sd(changes: FloatArray, loadings: FloatArray) -> FloatArray:
    """Per-tenor standard deviation of what the factors leave unexplained (bp per day)."""
    x = changes - changes.mean(axis=0, keepdims=True)
    q, _ = np.linalg.qr(loadings)
    out: FloatArray = (x - (x @ q) @ q.T).std(axis=0, ddof=1)
    return out


def bootstrap_rows(
    x: FloatArray, estimator: object, resamples: int = RESAMPLES, seed: int = SEED
) -> tuple[FloatArray, FloatArray]:
    """2.5 and 97.5 percentiles of ``estimator`` over resamples of the rows (days) of ``x``."""
    rng = np.random.Generator(np.random.PCG64(seed))
    draws = []
    for _ in range(resamples):
        idx = rng.integers(0, len(x), len(x))
        draws.append(np.asarray(estimator(x[idx])))  # type: ignore[operator]
    arr = np.array(draws)
    lo: FloatArray = np.nanpercentile(arr, 2.5, axis=0)
    hi: FloatArray = np.nanpercentile(arr, 97.5, axis=0)
    return lo, hi


def to_semi_bond(rate_bp: pd.DataFrame) -> pd.DataFrame:
    """Annual Act/360 rate (bp) to a semi-annual bond-basis rate (bp): r365 = r 365/360, then
    2 ((1 + r365)^(1/2) - 1). An approximation that ignores payment-date detail (< 0.1 bp)."""
    r = rate_bp * 1e-4 * 365.0 / 360.0
    return 2.0 * ((1.0 + r) ** 0.5 - 1.0) * 1e4


JOINT_TENORS = (5.0, 10.0, 30.0)


@dataclass(frozen=True)
class SpreadFit:
    """Per-tenor entries are NaN where fewer than 30 daily changes exist."""

    n_changes: FloatArray  # usable daily changes per tenor
    level_mean: FloatArray  # mean spread per tenor, bp
    change_sd: FloatArray  # sd of the daily spread change per tenor, bp
    noise_sd: FloatArray  # measurement-noise sd of one level, from the lag-1 autocovariance
    signal_sd: FloatArray  # sd of the daily change after removing 2 noise^2, bp
    beta: FloatArray  # (n_tenor, 3) regression of the spread change on curve-factor scores
    r2: FloatArray  # share of spread-change variance explained by the curve factors
    ar1: FloatArray  # lag-1 autocorrelation of the spread level per tenor
    joint_n: int  # days on which every JOINT_TENORS change exists
    joint_loading: FloatArray  # first principal component of the residuals at JOINT_TENORS
    joint_factor_sd: float  # its standard deviation, bp per day (includes measurement noise)
    joint_share: float  # share of the residual variance on that component
    joint_corr: FloatArray  # correlation matrix of the residuals at JOINT_TENORS


def _changes(level: pd.Series[float]) -> pd.Series[float]:
    lv = level.dropna().sort_index()
    idx = pd.to_datetime(lv.index)
    gap = np.diff(idx.to_numpy()).astype("timedelta64[D]").astype(np.int64)
    d = lv.diff().iloc[1:]
    return d[gap <= 5]


def spread_fit(
    swap_close_bp: pd.DataFrame, par_bp: pd.DataFrame, loadings: FloatArray, start: str, end: str
) -> SpreadFit:
    """Fit the spread statistics per tenor on days in [start, end], and the common component
    of the residual spread changes on the tenors that have enough joint days."""
    swap = to_semi_bond(swap_close_bp)
    days = swap.index.intersection(par_bp.index)
    days = days[(days >= start) & (days <= end)]
    spread = (swap.loc[days] - par_bp.loc[days]).sort_index()
    par_change = par_bp.sort_index().diff()
    scores_all = pd.DataFrame(
        (par_change - par_change.mean()).to_numpy(dtype=np.float64) @ loadings[:, :N_FACTORS],
        index=par_change.index,
    )
    nt = len(DESK_TENORS)
    nan = np.full(nt, np.nan)
    n, mean, sd, noise, signal, r2, ar1 = (nan.copy() for _ in range(7))
    beta = np.full((nt, N_FACTORS), np.nan)
    resid: dict[float, pd.Series[float]] = {}
    for k, tn in enumerate(DESK_TENORS):
        d = _changes(spread[tn])
        n[k] = len(d)
        if len(d) < 30:
            continue
        lv = spread[tn].dropna()
        mean[k] = float(lv.mean())
        dc = d - d.mean()
        var = float(dc.var(ddof=1))
        pos = pd.Series(np.arange(len(lv) - 1), index=lv.index[1:]).loc[d.index].to_numpy()
        adj = np.flatnonzero(np.diff(pos) == 1)
        x = dc.to_numpy(dtype=np.float64)
        acov = float((x[adj] * x[adj + 1]).mean()) if len(adj) else 0.0
        nv = max(-acov, 0.0)
        sc = scores_all.loc[d.index].to_numpy(dtype=np.float64)
        b = np.linalg.lstsq(sc, x, rcond=None)[0]
        res = x - sc @ b
        sd[k], noise[k], signal[k] = np.sqrt(var), np.sqrt(nv), np.sqrt(max(var - 2 * nv, 0.0))
        beta[k], r2[k] = b, 1.0 - float(res.var(ddof=1)) / var
        lc = lv.to_numpy(dtype=np.float64) - float(lv.mean())
        ar1[k] = float((lc[1:] * lc[:-1]).mean() / lc.var())
        resid[tn] = pd.Series(res, index=d.index)
    joint = pd.DataFrame({tn: resid[tn] for tn in JOINT_TENORS if tn in resid}).dropna()
    if joint.shape[1] == len(JOINT_TENORS) and len(joint) >= 30:
        jp = pca(joint.to_numpy(dtype=np.float64))
        loading = jp.loadings[:, 0] * np.sign(jp.loadings[:, 0].sum())
        jsd, jshare = float(jp.sd[0]), float(jp.explained[0])
        corr = np.corrcoef(joint.to_numpy(dtype=np.float64).T)
    else:
        loading, jsd, jshare = np.full(len(JOINT_TENORS), np.nan), float("nan"), float("nan")
        corr = np.full((len(JOINT_TENORS), len(JOINT_TENORS)), np.nan)
    return SpreadFit(
        n, mean, sd, noise, signal, beta, r2, ar1, len(joint), loading, jsd, jshare, corr
    )
