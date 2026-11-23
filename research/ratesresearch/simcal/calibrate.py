"""Run the SIM3-CAL-001 estimates on one period and write them as JSON.

``estimate_period`` returns every number of docs/research/SIM_V2_CALIBRATION.md for a date
range, each as ``{"value": ..., "lo": ..., "hi": ...}`` (95 percent percentile interval over
day resamples) or a plain value where no interval applies. ``compare`` applies the pass marks
of the note to a calibration and a validation result.

Print subsets. ``registered``: the filters of the note. ``strict``: also spot-starting on the
standard date (effective 2 to 5 calendar days after execution) with a maturity within 0.02
years of the tenor; chosen on the calibration period because swaps of slightly different
maturity in one bucket differ by a few tenths of a bp and inflate the noise. ``strict_d2c``:
the strict prints executed on the two dealer-to-client request-for-quote facilities.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch import flow
from ratesresearch.simcal import DESK_TENORS, RESAMPLES, SEED, factors, micro
from ratesresearch.simcal import prints as prints_mod

FloatArray = npt.NDArray[np.float64]
SESSION_MINUTES = (prints_mod.SESSION_END_S - prints_mod.SESSION_START_S) / 60.0
TENOR_KEYS = [f"{int(t)}y" for t in DESK_TENORS]
ROW_KEYS = [*TENOR_KEYS, "pooled"]


def strict(p: pd.DataFrame) -> pd.DataFrame:
    keep = p["lag_days"].between(2, 5) & (p["tenor_dev"].abs() <= 0.02)
    return p.loc[keep].reset_index(drop=True)


def d2c(p: pd.DataFrame) -> pd.DataFrame:
    return p.loc[p["group"] == 0].reset_index(drop=True)


def _triple(point: Any, lo: Any, hi: Any) -> Any:
    """Nested lists of {value, lo, hi} with the shape of ``point`` (NaN becomes None)."""
    p, a, b = np.asarray(point, dtype=np.float64), np.asarray(lo), np.asarray(hi)

    def clean(x: float) -> float | None:
        return None if not math.isfinite(x) else round(float(x), 5)

    if p.ndim == 0:
        return {"value": clean(float(p)), "lo": clean(float(a)), "hi": clean(float(b))}
    return [_triple(p[i], a[i], b[i]) for i in range(p.shape[0])]


def _keyed(keys: list[str], values: Any) -> dict[str, Any]:
    return dict(zip(keys, values, strict=True))


def _micro_vector(total: micro.Stats) -> FloatArray:
    """Every microstructure estimate of one (resampled) sample as one flat vector."""
    rob = micro.est_robust(total)
    basic = micro.est_basic(total)
    sig = micro.est_signature(total)
    mo = micro.est_markout(total, "mid")
    tick = micro.est_markout(total, "tick")
    season = micro.est_season(total, basic[:-1, 1] ** 2)
    arrivals = micro.est_arrivals(total)
    per_day = arrivals.sum(axis=1)
    shape = arrivals.sum(axis=0) / max(float(arrivals.sum()), 1e-12)
    change = mo["raw"][:, -1] - mo["raw"][:, 0]
    change30 = mo["raw"][:, -1] - mo["raw"][:, 2]
    parts = [
        rob["sigma"],
        rob["eta_long"],
        rob["eta_short"],
        basic[:, 0],
        basic[:, 1],
        micro.est_class(total, "bucket")[-1],
        micro.est_class(total, "group")[-1],
        mo["raw"].ravel(),
        tick["raw"].ravel(),
        change,
        change30,
        mo["eps"],
        mo["drift_hi"][:, -1],
        mo["drift_lo"][:, -1],
        season,
        per_day,
        shape,
        np.array([sig["h"][-1], sig["realised"][-1], sig["drift"][-1]]),
    ]
    return np.concatenate([np.asarray(x, dtype=np.float64).ravel() for x in parts])


def _micro_layout() -> list[tuple[str, tuple[int, ...]]]:
    nt, nb, nh = len(DESK_TENORS), micro.N_BUCKETS, len(micro.HORIZONS_S)
    return [
        ("robust_sigma", (nt + 1,)),
        ("robust_eta_long", (nt + 1,)),
        ("robust_eta_short", (nt + 1,)),
        ("ls_eta", (nt + 1,)),
        ("ls_sigma", (nt + 1,)),
        ("ls_eta_by_bucket", (nb,)),
        ("ls_eta_by_group", (micro.N_GROUPS,)),
        ("markout_raw", (nb + 1, nh)),
        ("markout_raw_tick", (nb + 1, nh)),
        ("markout_change_first_to_5min", (nb + 1,)),
        ("markout_change_30s_to_5min", (nb + 1,)),
        ("sign_error_probability", (nb + 1,)),
        ("drift_5min_registered_hi", (nb + 1,)),
        ("drift_5min_registered_lo", (nb + 1,)),
        ("season", (micro.N_BINS + len(micro.EVENT_NAMES),)),
        ("prints_per_day", (nt,)),
        ("time_of_day_share", (micro.N_BINS,)),
        ("ls_signature_h_realised_drift", (3,)),
    ]


def micro_estimates(
    p: pd.DataFrame, events_of: Any, resamples: int = RESAMPLES, seed: int = SEED
) -> dict[str, Any]:
    """Point estimates and day-bootstrap intervals for one print subset."""
    sample = micro.make_sample(p)
    stats = micro.day_stats(sample, events_of(sample.days))
    point, lo, hi = micro.interval(stats, _micro_vector, resamples, seed)
    total = micro.total_of(stats)
    out: dict[str, Any] = {"n_prints": len(p), "n_days": sample.n_days}
    at = 0
    for name, shape in _micro_layout():
        size = int(np.prod(shape))
        sl = slice(at, at + size)
        out[name] = _triple(point[sl].reshape(shape), lo[sl].reshape(shape), hi[sl].reshape(shape))
        at += size
    mo = micro.est_markout(total, "mid")
    rob = micro.est_robust(total)
    out["markout_n"] = mo["n"].tolist()
    out["markout_delay_s"] = np.round(mo["delay"], 1).tolist()
    out["markout_sign_correlation"] = np.round(mo["rho"], 3).tolist()
    out["robust_level_pooled"] = np.round(rob["level"][-1], 4).tolist()
    out["robust_pairs_pooled"] = rob["n"][-1].tolist()
    out["bucket_count"] = total["bucket_count"].sum(axis=0).tolist()
    out["group_count"] = total["group_count"].sum(axis=0).tolist()
    out["event_days"] = total["event_days"].tolist()
    out["platforms"] = prints_mod.platform_counts(p)
    return out


def size_fit(p: pd.DataFrame) -> dict[str, Any]:
    """Right-censored lognormal of the notional (USD mm) per tenor, and DV01 quantiles."""
    out: dict[str, Any] = {}
    for tn, key in zip(DESK_TENORS, TENOR_KEYS, strict=True):
        q = p.loc[p["tenor"] == tn]
        if len(q) < 50:
            out[key] = None
            continue
        x = q["notional_mm"].to_numpy(dtype=np.float64)
        capped = q["capped"].to_numpy(dtype=bool)
        mu, sigma, se_mu, se_sigma = flow.fit_censored_lognormal(x, capped)
        dv = q["dv01"].to_numpy(dtype=np.float64)
        out[key] = {
            "n": len(q),
            "capped_share": round(float(capped.mean()), 4),
            "cap_mm_median": round(float(np.median(x[capped])), 1) if capped.any() else None,
            "log_mean": {"value": round(mu, 4), "se": round(se_mu, 4)},
            "log_sd": {"value": round(sigma, 4), "se": round(se_sigma, 4)},
            "median_mm": round(math.exp(mu), 2),
            "dv01_usd_quantiles_25_50_75_95": [
                round(float(v)) for v in np.percentile(dv, [25, 50, 75, 95])
            ],
        }
    return out


def factor_estimates(
    par: pd.DataFrame, start: str, end: str, loadings: FloatArray | None
) -> dict[str, Any]:
    """Curve PCA on [start, end]; with ``loadings`` given, also scores on those loadings."""
    ch = factors.daily_changes(par, start, end).to_numpy(dtype=np.float64)
    own = factors.pca(ch)
    k = factors.N_FACTORS
    use = own.loadings[:, :k] if loadings is None else loadings[:, :k]
    lo, hi = factors.bootstrap_rows(ch, lambda x: factors.score_sd(x, use))
    out: dict[str, Any] = {
        "n_days": own.n,
        "start": start,
        "end": end,
        "loadings": _keyed(TENOR_KEYS, np.round(own.loadings[:, :k], 4).tolist()),
        "own_factor_sd_bp_per_day": np.round(own.sd[:k], 3).tolist(),
        "own_explained": np.round(own.explained[:k], 4).tolist(),
        "factor_sd_bp_per_day": _triple(factors.score_sd(ch, use), lo, hi),
        "explained_by_loadings": round(factors.explained_by(ch, use), 4),
        "explained_by_own_three": round(float(own.explained[:k].sum()), 4),
        "residual_sd_bp_per_day": _keyed(
            TENOR_KEYS, np.round(factors.residual_sd(ch, use), 3).tolist()
        ),
        "tenor_sd_bp_per_day": _keyed(TENOR_KEYS, np.round(ch.std(axis=0, ddof=1), 3).tolist()),
    }
    if loadings is not None:
        out["cosine_with_given_loadings"] = np.round(
            factors.cosines(own.loadings[:, :k], use), 4
        ).tolist()
    return out


def spread_estimates(
    close: pd.DataFrame, par: pd.DataFrame, loadings: FloatArray, start: str, end: str
) -> dict[str, Any]:
    f = factors.spread_fit(close, par, loadings, start, end)

    def per_tenor(x: FloatArray, digits: int = 3) -> dict[str, Any]:
        return _keyed(
            TENOR_KEYS, [None if not math.isfinite(v) else round(float(v), digits) for v in x]
        )

    return {
        "daily_changes": per_tenor(f.n_changes, 0),
        "days_with_close": _keyed(TENOR_KEYS, [int(v) for v in close.notna().sum().to_numpy()]),
        "level_mean_bp": per_tenor(f.level_mean, 1),
        "change_sd_bp": per_tenor(f.change_sd),
        "noise_sd_bp": per_tenor(f.noise_sd),
        "signal_sd_bp": per_tenor(f.signal_sd),
        "r2_on_curve_factors": per_tenor(f.r2),
        "beta_on_curve_factors": _keyed(
            TENOR_KEYS,
            [[None if not math.isfinite(v) else round(float(v), 3) for v in row] for row in f.beta],
        ),
        "level_ar1": per_tenor(f.ar1),
        "joint_tenors": list(factors.JOINT_TENORS),
        "joint_days": f.joint_n,
        "joint_loading": np.round(f.joint_loading, 3).tolist(),
        "joint_factor_sd_bp": round(f.joint_factor_sd, 3),
        "joint_share": round(f.joint_share, 3),
        "joint_residual_correlation": np.round(f.joint_corr, 3).tolist(),
    }


def estimate_period(
    prints: pd.DataFrame,
    funnel: pd.DataFrame,
    par: pd.DataFrame,
    calendar: pd.DataFrame,
    auctions: pd.DataFrame,
    start: str,
    end: str,
    curve_start: str,
    loadings: FloatArray | None = None,
    resamples: int = RESAMPLES,
) -> dict[str, Any]:
    """All estimates for file days in [start, end]; curve PCA on [curve_start, end]."""

    def events_of(days: tuple[str, ...]) -> FloatArray:
        return micro.event_flags(days, calendar, auctions)

    st = strict(prints)
    sd2c = d2c(st)
    curve = factor_estimates(par, curve_start, end, loadings)
    use = np.array([curve["loadings"][k] for k in TENOR_KEYS]) if loadings is None else loadings
    same_window = factor_estimates(par, start, end, use)
    out: dict[str, Any] = {
        "period": {"start": start, "end": end, "file_days": len(funnel)},
        "funnel": {k: int(funnel[k].sum()) for k in funnel.columns if k != "day"},
        "subset_counts": {"registered": len(prints), "strict": len(st), "strict_d2c": len(sd2c)},
        "curve": curve,
        "curve_in_print_window": same_window,
        "spread": spread_estimates(micro.daily_close(st), par, use, start, end),
        "registered": micro_estimates(prints, events_of, resamples),
        "strict": micro_estimates(st, events_of, resamples),
        "strict_d2c": micro_estimates(sd2c, events_of, resamples),
        "sizes_strict_d2c": size_fit(sd2c),
    }
    sig = [row["value"] for row in out["strict"]["robust_sigma"]][:-1]
    tenor_sd = [same_window["tenor_sd_bp_per_day"][k] for k in TENOR_KEYS]
    out["intraday_share_of_daily_variance"] = _keyed(
        TENOR_KEYS,
        [
            None if s is None else round(s * s * SESSION_MINUTES / (d * d), 3)
            for s, d in zip(sig, tenor_sd, strict=True)
        ],
    )
    return out


def _ratio(val: dict[str, Any], cal: dict[str, Any]) -> float | None:
    if val["value"] is None or cal["value"] is None or cal["value"] == 0:
        return None
    return round(float(val["value"]) / float(cal["value"]), 3)


def compare(cal: dict[str, Any], val: dict[str, Any]) -> dict[str, Any]:
    """The pass marks of SIM3-CAL-001: scale ratios in [0.667, 1.5], loadings, markouts."""
    rows: list[dict[str, Any]] = []

    def scale(name: str, c: dict[str, Any], v: dict[str, Any]) -> None:
        r = _ratio(v, c)
        rows.append(
            {
                "parameter": name,
                "calibration": c["value"],
                "validation": v["value"],
                "ratio": r,
                "pass": None if r is None else bool(2 / 3 <= r <= 1.5),
            }
        )

    for k, (c, v) in enumerate(
        zip(cal["curve"]["factor_sd_bp_per_day"], val["curve"]["factor_sd_bp_per_day"], strict=True)
    ):
        scale(f"factor_sd_{k + 1}", c, v)
    for subset, names in (
        ("strict", ("robust_sigma",)),
        ("strict_d2c", ("robust_eta_long", "robust_eta_short")),
    ):
        for name in names:
            for key, c, v in zip(ROW_KEYS, cal[subset][name], val[subset][name], strict=True):
                scale(f"{subset}.{name}.{key}", c, v)
    for key, c, v in zip(
        TENOR_KEYS,
        cal["strict_d2c"]["prints_per_day"],
        val["strict_d2c"]["prints_per_day"],
        strict=True,
    ):
        scale(f"strict_d2c.prints_per_day.{key}", c, v)
    cos = val["curve"]["cosine_with_given_loadings"]
    gap = abs(val["curve"]["explained_by_loadings"] - val["curve"]["explained_by_own_three"])
    markout: list[dict[str, Any]] = []
    for b, name in enumerate([*prints_mod.BUCKET_NAMES, "all"]):
        c = cal["strict_d2c"]["markout_raw"][b][-1]
        v = val["strict_d2c"]["markout_raw"][b][-1]
        if c["value"] is None or v["value"] is None or c["lo"] is None or v["lo"] is None:
            markout.append({"bucket": name, "pass": None})
            continue
        overlap = c["lo"] <= v["hi"] and v["lo"] <= c["hi"]
        same = (c["value"] > 0) == (v["value"] > 0)
        markout.append(
            {
                "bucket": name,
                "calibration": c,
                "validation": v,
                "intervals_overlap": overlap,
                "same_sign": same,
                "pass": bool(overlap and same),
            }
        )
    judged = [r for r in rows if r["pass"] is not None]
    return {
        "scale": rows,
        "scale_passed": sum(1 for r in judged if r["pass"]),
        "scale_judged": len(judged),
        "loading_cosines": cos,
        "loading_pass": bool(min(cos) >= 0.90 and gap <= 0.05),
        "explained_gap": round(gap, 4),
        "markout_5min": markout,
    }


def load_inputs(repo: Path) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    edge = repo / "data/fixtures/edge"
    par = factors.load_par_curve(edge / "par_curve.csv")
    calendar = pd.read_csv(edge / "calendar.csv", dtype=str)
    auctions = pd.read_csv(edge / "auctions.csv", dtype=str)
    return par, calendar, auctions
