"""Order-flow and intraday profile: RFQ arrival intensity around fixings and data releases, and
size distributions, fitted from public trade data.

Plain idea: dealers see more requests just after 08:30 New York data releases, around the
10:00 releases, the 11:00 USD swap rate fixing and the 15:00 futures close. We count public
trades in five-minute buckets, fit a smooth baseline with a bump at each scheduled time, and
fit a lognormal to trade sizes, treating the capped sizes the public tape shows as "at least
the cap".

Formal statement. For bucket ``b`` with centre minute ``m_b`` (after midnight New York) and
exposure ``E_b`` (days times bucket minutes), counts are Poisson with

    log mu_b = log E_b + b0 + sum_k a_k exp(-(m_b - m_k)^2 / (2 w^2))

fitted by iteratively reweighted least squares; ``w`` is chosen from a grid by likelihood. The
standard errors are scaled by the Pearson dispersion (quasi-Poisson), because intraday counts
are over-dispersed. Sizes ``x`` in millions are lognormal(mu, sigma); a capped size ``c+``
contributes ``log(1 - Phi((log c - mu) / sigma))`` to the likelihood (right-censoring).

The resulting preset ``usd_rfq_flow`` drives ``SyntheticDay`` in Java. DTCC shows executed
trades, not RFQs; using the trade-arrival shape for RFQ arrivals is an assumption stated in
the preset notes, and the level is scaled by the declared ``rfq_share_of_market``.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.presets import param

EVENT_MINUTES_ET = (510.0, 600.0, 660.0, 900.0)  # 08:30, 10:00, 11:00, 15:00 New York
EVENT_LABELS = ("08:30 releases", "10:00 releases", "11:00 swap rate fixing", "15:00 futures close")
SESSION = (420.0, 1020.0)  # 07:00 to 17:00 New York
BUCKET_MINUTES = 5.0
WIDTH_GRID = (5.0, 7.5, 10.0, 15.0, 20.0)


@dataclass(frozen=True)
class IntensityFit:
    b0: float
    amplitudes: tuple[float, ...]
    se_b0: float
    se_amplitudes: tuple[float, ...]
    width: float
    dispersion: float
    log_likelihood: float
    buckets: int
    trades: int


def minutes_et(times: pd.Series, utc_offset_hours: int) -> np.ndarray:
    """Minutes after local midnight for UTC times and a fixed offset (EDT is -4, EST is -5)."""
    local = times + pd.Timedelta(hours=utc_offset_hours)
    return np.asarray(local.dt.hour * 60 + local.dt.minute + local.dt.second / 60.0, dtype=float)


def bucket_counts(minutes: np.ndarray, days: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Bucket centres, counts and exposures over the session for ``days`` days pooled."""
    edges = np.arange(SESSION[0], SESSION[1] + BUCKET_MINUTES, BUCKET_MINUTES)
    counts, _ = np.histogram(minutes, bins=edges)
    centres = (edges[:-1] + edges[1:]) / 2
    exposure = np.full(centres.shape, days * BUCKET_MINUTES)
    return centres, counts.astype(float), exposure


def _design(centres: np.ndarray, width: float, events: Sequence[float]) -> np.ndarray:
    cols = [np.ones_like(centres)]
    for m in events:
        cols.append(np.exp(-0.5 * ((centres - m) / width) ** 2))
    return np.column_stack(cols)


def _irls(x: np.ndarray, y: np.ndarray, offset: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    beta = np.zeros(x.shape[1])
    beta[0] = math.log(max(y.sum(), 1.0) / np.exp(offset).sum())
    for _ in range(100):
        eta = x @ beta + offset
        mu = np.exp(eta)
        z = eta - offset + (y - mu) / mu
        w = mu
        xtw = x.T * w
        new = np.linalg.solve(xtw @ x, xtw @ z)
        if np.max(np.abs(new - beta)) < 1e-10:
            beta = new
            break
        beta = new
    mu = np.exp(x @ beta + offset)
    cov = np.linalg.inv((x.T * mu) @ x)
    return beta, cov


def fit_intensity(
    centres: np.ndarray,
    counts: np.ndarray,
    exposure: np.ndarray,
    events: Sequence[float] = EVENT_MINUTES_ET,
    widths: Iterable[float] = WIDTH_GRID,
) -> IntensityFit:
    """Poisson log-linear fit of bucket counts with Gaussian bumps at ``events``."""
    offset = np.log(exposure)
    best: IntensityFit | None = None
    for w in widths:
        x = _design(centres, w, events)
        beta, cov = _irls(x, counts, offset)
        mu = np.exp(x @ beta + offset)
        ll = float(
            np.sum(counts * np.log(mu) - mu - np.array([math.lgamma(c + 1) for c in counts]))
        )
        dof = max(len(counts) - x.shape[1], 1)
        disp = float(np.sum((counts - mu) ** 2 / mu) / dof)
        se = np.sqrt(np.diag(cov) * max(disp, 1.0))
        fit = IntensityFit(
            b0=float(beta[0]),
            amplitudes=tuple(float(v) for v in beta[1:]),
            se_b0=float(se[0]),
            se_amplitudes=tuple(float(v) for v in se[1:]),
            width=float(w),
            dispersion=disp,
            log_likelihood=ll,
            buckets=len(counts),
            trades=int(counts.sum()),
        )
        if best is None or fit.log_likelihood > best.log_likelihood:
            best = fit
    if best is None:
        raise ValueError("empty width grid")
    return best


def _norm_logsf(z: np.ndarray) -> np.ndarray:
    out: np.ndarray = np.log(np.array([0.5 * math.erfc(v / math.sqrt(2)) for v in z]))
    return out


def censored_lognormal_loglik(mu: float, sigma: float, x: np.ndarray, capped: np.ndarray) -> float:
    lx = np.log(x)
    z = (lx - mu) / sigma
    exact = ~capped
    ll = float(
        np.sum(-0.5 * z[exact] ** 2 - math.log(sigma) - 0.5 * math.log(2 * math.pi) - lx[exact])
    )
    if capped.any():
        ll += float(np.sum(_norm_logsf(z[capped])))
    return ll


def fit_censored_lognormal(x: np.ndarray, capped: np.ndarray) -> tuple[float, float, float, float]:
    """MLE of (mu, sigma) with right-censoring; returns mu, sigma, se_mu, se_sigma."""
    lx = np.log(x)
    mu, sigma = float(lx.mean()), float(lx.std(ddof=1))

    def nll(theta: np.ndarray) -> float:
        return -censored_lognormal_loglik(float(theta[0]), math.exp(float(theta[1])), x, capped)

    theta = np.array([mu, math.log(sigma)])
    # Newton steps with a numerical gradient and Hessian (two parameters, smooth likelihood)
    h = 1e-4
    for _ in range(50):
        g = np.zeros(2)
        hess = np.zeros((2, 2))
        f0 = nll(theta)
        for i in range(2):
            e = np.zeros(2)
            e[i] = h
            g[i] = (nll(theta + e) - nll(theta - e)) / (2 * h)
            for j in range(2):
                f = np.zeros(2)
                f[j] = h
                hess[i, j] = (
                    nll(theta + e + f)
                    - nll(theta + e - f)
                    - nll(theta - e + f)
                    + nll(theta - e - f)
                ) / (4 * h * h)
        step = np.linalg.solve(hess, g)
        t = 1.0
        while nll(theta - t * step) > f0 and t > 1e-6:
            t /= 2
        theta = theta - t * step
        if np.max(np.abs(t * step)) < 1e-9:
            break
    cov = np.linalg.inv(hess)
    mu, log_sigma = float(theta[0]), float(theta[1])
    sigma = math.exp(log_sigma)
    se_mu = math.sqrt(cov[0, 0])
    se_sigma = sigma * math.sqrt(cov[1, 1])  # delta method from log sigma
    return mu, sigma, se_mu, se_sigma


def fit_dtcc(
    trades: pd.DataFrame, days: int, data_range: tuple[str, str], manifest: str
) -> dict[str, Any]:
    """Fit the ``usd_rfq_flow`` preset from filtered DTCC SOFR OIS trades (EDT session)."""
    minutes = minutes_et(trades["exec_time"], -4)
    in_session = (minutes >= SESSION[0]) & (minutes < SESSION[1])
    centres, counts, exposure = bucket_counts(minutes[in_session], days)
    fit = fit_intensity(centres, counts, exposure)
    mu, sigma, se_mu, se_sigma = fit_censored_lognormal(
        trades["notional_mm"].to_numpy(float), trades["capped"].to_numpy(bool)
    )
    four = trades[trades["tenor_bucket"].isin([2.0, 5.0, 10.0, 30.0])]
    shares = four["tenor_bucket"].value_counts(normalize=True)
    parameters: dict[str, Any] = {
        "log_base_rate_per_min": param(fit.b0, fit.se_b0),
        "event_count": param(len(EVENT_MINUTES_ET)),
        "event_bump_width_minutes": param(fit.width),
        "event_half_width_minutes": param(15.0),
        "utc_offset_hours": param(-5.0),
        "size_mu_ln_mm": param(mu, se_mu),
        "size_sigma_ln_mm": param(sigma, se_sigma),
        "size_cap_mm": param(1000.0),
        "dispersion": param(fit.dispersion),
    }
    for k, m in enumerate(EVENT_MINUTES_ET):
        parameters[f"event_{k}_minute_et"] = param(m)
        parameters[f"event_{k}_log_amplitude"] = param(fit.amplitudes[k], fit.se_amplitudes[k])
    for t in (2.0, 5.0, 10.0, 30.0):
        parameters[f"tenor_share_{int(t)}y"] = param(float(shares.get(t, 0.0)))
    return {
        "name": "usd_rfq_flow",
        "fitted_from": manifest,
        "data_range": {"start": data_range[0], "end": data_range[1]},
        "sample_size": len(trades),
        "method": (
            "quasi-Poisson IRLS on 5-minute counts with Gaussian event bumps; censored lognormal "
            "MLE for sizes; research/ratesresearch/flow.py::fit_dtcc"
        ),
        "parameters": parameters,
        "notes": (
            "DTCC SDR public dissemination, USD SOFR OIS new trades, non-package, spot-starting, "
            f"{days} business days; session 07:00-17:00 New York (EDT in the data). Trade arrivals "
            "stand in for RFQ arrivals (assumption). event_half_width_minutes, utc_offset_hours "
            "(-5, for the February synthetic sessions) and size_cap_mm are declared, not fitted, "
            "and carry no standard error."
        ),
    }


def fit_finra_fixture(trades: pd.DataFrame, source: str) -> dict[str, Any]:
    """Size fit of the FINRA-layout fixture; labelled synthetic, not used by the simulator."""
    mu, sigma, se_mu, se_sigma = fit_censored_lognormal(
        trades["size_mm"].to_numpy(float), trades["capped"].to_numpy(bool)
    )
    minutes = minutes_et(trades["time"], -4)
    share_0830 = float(np.mean(np.abs(minutes - 510.0) <= 15.0))
    return {
        "name": "ust_trace_flow_fixture",
        "fitted_from": source,
        "data_range": {"start": "2025-10-01", "end": "2025-10-01"},
        "sample_size": len(trades),
        "method": "censored lognormal MLE, research/ratesresearch/flow.py::fit_finra_fixture",
        "parameters": {
            "size_mu_ln_mm": param(mu, se_mu),
            "size_sigma_ln_mm": param(sigma, se_sigma),
            "share_within_15min_of_0830": param(share_0830),
        },
        "notes": (
            "SYNTHETIC FIXTURE in the documented FINRA layout; FINRA TRACE Treasury data could "
            "not be downloaded unattended (API requires an account). Exercises the loader and "
            "the fit only; not used by the simulator."
        ),
    }


def intensity_per_minute(fit_or_preset: dict[str, Any], minute: float) -> float:
    """Platform-wide trade rate per minute at ``minute`` from a ``usd_rfq_flow`` preset."""
    p = fit_or_preset["parameters"]
    z = float(p["log_base_rate_per_min"]["value"])
    w = float(p["event_bump_width_minutes"]["value"])
    for k in range(int(p["event_count"]["value"])):
        m = float(p[f"event_{k}_minute_et"]["value"])
        z += float(p[f"event_{k}_log_amplitude"]["value"]) * math.exp(
            -0.5 * ((minute - m) / w) ** 2
        )
    return math.exp(z)


def profile_table(preset: dict[str, Any]) -> list[tuple[str, float]]:
    """Intensity at the session open, each event and the close, for reports."""
    rows = [("07:00 open", intensity_per_minute(preset, SESSION[0]))]
    for label, m in zip(EVENT_LABELS, EVENT_MINUTES_ET, strict=True):
        rows.append((label, intensity_per_minute(preset, m)))
    rows.append(("16:55 close", intensity_per_minute(preset, SESSION[1] - 5)))
    return rows


def main(raw_dir: str, repo_root: str) -> None:  # pragma: no cover - run by hand with downloads
    """Fit from downloaded DTCC zips; write the manifest and the preset."""
    from ratesresearch.data import dtcc
    from ratesresearch.presets import write_preset
    from ratesresearch.provenance import write_manifest

    root = Path(repo_root)
    paths = sorted(Path(raw_dir).glob("CFTC_CUMULATIVE_RATES_*.zip"))
    frames = [dtcc.load_sofr_ois(p) for p in paths]
    trades = pd.concat(frames, ignore_index=True)
    manifest = root / "data/manifests/dtcc_rates_2025-10.json"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    write_manifest(paths, manifest)
    first = paths[0].stem[-10:].replace("_", "-")
    last = paths[-1].stem[-10:].replace("_", "-")
    preset = fit_dtcc(trades, len(paths), (first, last), "data/manifests/dtcc_rates_2025-10.json")
    write_preset(root / "data/presets.yaml", preset)
