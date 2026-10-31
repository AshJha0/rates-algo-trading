"""Hit-rate model: logistic win probability against spread-to-cover, size, tenor, time of day
and dealer count.

Plain idea: a quote wins more often the closer it is to the best competing price. We cannot
see losing quotes on any public tape (DR-008), so we generate labelled RFQs from the declared
simulator model (preset ``usd_rfq_competition``), fit a logistic regression to them, check
that it predicts held-out labels better than a constant, and write the coefficients, their
standard errors and the declared band for the slope into ``usd_rfq_hit_rate``. The fit is a
consistency check of the pipeline and a calibrated input for the strategy; it is not a fitted
hit rate of any real market.

Formal statement. Features ``x = (1, s, ln q, T, F, n)``: spread-to-cover ``s`` in bp, size
``q`` in millions, tenor ``T`` in years, fixing-window flag ``F``, dealer count ``n``.
``P(win | x) = 1 / (1 + exp(-x . beta))``. The fit is maximum likelihood by Newton-Raphson;
standard errors are the square roots of the diagonal of the inverse observed information.
Out-of-sample: the labels are split by RFQ index (first 70 percent fit, last 30 percent test,
no shuffling), and the log-loss is compared with the constant predictor equal to the fitting
sample's win rate.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from ratesresearch.presets import get_preset, param, value

FEATURES = (
    "intercept",
    "spread_to_cover_bp",
    "log_size_mm",
    "tenor_years",
    "fixing_window",
    "dealer_count",
)
TENORS = (2.0, 5.0, 10.0, 30.0)
LABEL_SEED = 20261031


@dataclass(frozen=True)
class Fit:
    beta: np.ndarray
    se: np.ndarray
    n_fit: int
    n_test: int
    log_loss_model: float
    log_loss_constant: float
    win_rate_fit: float


def sigmoid(z: np.ndarray) -> np.ndarray:
    out: np.ndarray = 1.0 / (1.0 + np.exp(-z))
    return out


def simulate_labels(
    competition: dict[str, Any], n: int, seed: int = LABEL_SEED
) -> tuple[np.ndarray, np.ndarray]:
    """Labelled RFQs from the declared competition model (central slope), as (X, y)."""
    rng = np.random.default_rng(seed)
    tenor = rng.choice(TENORS, size=n)
    size = np.clip(np.rint(np.exp(rng.normal(3.05, 1.2, size=n))), 1, 1000)
    dealers = rng.integers(2, 6, size=n)
    window = (rng.random(n) < 0.25).astype(float)
    median = (
        value(competition, "median_half_spread_bp")
        * (tenor / 10.0) ** value(competition, "tenor_slope")
        * (size / 50.0) ** value(competition, "size_slope")
    )
    sigma = value(competition, "lognormal_sigma")
    cover = np.array(
        [
            m * math.exp(sigma * float(rng.normal(size=k - 1).min()))
            for m, k in zip(median, dealers, strict=True)
        ]
    )
    ours = cover + rng.normal(0.0, 0.35, size=n)  # our quotes scatter around the cover
    s = ours - cover
    x = np.column_stack([np.ones(n), s, np.log(size), tenor, window, dealers.astype(float)])
    slope = float(competition["assumptions"]["spread_to_cover_bp"]["band"]["central"])
    beta = np.array(
        [
            value(competition, "true_intercept"),
            slope,
            value(competition, "true_log_size_mm"),
            value(competition, "true_tenor_years"),
            value(competition, "true_fixing_window"),
            value(competition, "true_dealer_count"),
        ]
    )
    y = (rng.random(n) < sigmoid(x @ beta)).astype(float)
    return x, y


def fit_logistic(x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    beta = np.zeros(x.shape[1])
    for _ in range(100):
        p = sigmoid(x @ beta)
        grad = x.T @ (y - p)
        hess = (x.T * (p * (1 - p))) @ x
        step = np.linalg.solve(hess, grad)
        beta = beta + step
        if np.max(np.abs(step)) < 1e-10:
            break
    p = sigmoid(x @ beta)
    cov = np.linalg.inv((x.T * (p * (1 - p))) @ x)
    return beta, np.sqrt(np.diag(cov))


def log_loss(y: np.ndarray, p: np.ndarray) -> float:
    q = np.clip(p, 1e-12, 1 - 1e-12)
    return float(-np.mean(y * np.log(q) + (1 - y) * np.log(1 - q)))


def fit(x: np.ndarray, y: np.ndarray, train_share: float = 0.7) -> Fit:
    n_fit = int(len(y) * train_share)
    beta, se = fit_logistic(x[:n_fit], y[:n_fit])
    rate = float(y[:n_fit].mean())
    test_x, test_y = x[n_fit:], y[n_fit:]
    return Fit(
        beta=beta,
        se=se,
        n_fit=n_fit,
        n_test=len(y) - n_fit,
        log_loss_model=log_loss(test_y, sigmoid(test_x @ beta)),
        log_loss_constant=log_loss(test_y, np.full(len(test_y), rate)),
        win_rate_fit=rate,
    )


def calibration_table(
    x: np.ndarray, y: np.ndarray, beta: np.ndarray, bins: int = 10
) -> list[tuple[float, float, int]]:
    """(mean predicted, observed rate, count) per decile of predicted probability."""
    p = sigmoid(x @ beta)
    order = np.argsort(p)
    rows = []
    for chunk in np.array_split(order, bins):
        if len(chunk):
            rows.append((float(p[chunk].mean()), float(y[chunk].mean()), len(chunk)))
    return rows


def calibration_svg(rows: list[tuple[float, float, int]], size: int = 320) -> str:
    """A reliability diagram as a self-contained SVG (predicted on x, observed on y)."""
    pad = 40
    span = size - 2 * pad

    def xy(px: float, py: float) -> tuple[float, float]:
        return pad + px * span, size - pad - py * span

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 {size} {size}">',
        f'<rect x="{pad}" y="{pad}" width="{span}" height="{span}" fill="none" stroke="#888"/>',
        f'<line x1="{pad}" y1="{size - pad}" x2="{size - pad}" y2="{pad}" stroke="#bbb" '
        'stroke-dasharray="4 3"/>',
    ]
    pts = " ".join(f"{a:.1f},{b:.1f}" for a, b in (xy(r[0], r[1]) for r in rows))
    parts.append(f'<polyline points="{pts}" fill="none" stroke="#3949ab" stroke-width="2"/>')
    for r in rows:
        cx, cy = xy(r[0], r[1])
        parts.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="3" fill="#3949ab"/>')
    parts.append(
        f'<text x="{size / 2}" y="{size - 8}" text-anchor="middle" font-size="12">'
        "predicted win probability</text>"
    )
    parts.append(
        f'<text x="12" y="{size / 2}" text-anchor="middle" font-size="12" '
        f'transform="rotate(-90 12 {size / 2})">observed win rate</text>'
    )
    parts.append("</svg>")
    return "\n".join(parts)


def preset_from_fit(f: Fit, competition: dict[str, Any], n: int) -> dict[str, Any]:
    params = {
        name: param(float(b), float(s)) for name, b, s in zip(FEATURES, f.beta, f.se, strict=True)
    }
    params["oos_log_loss_model"] = param(f.log_loss_model)
    params["oos_log_loss_constant"] = param(f.log_loss_constant)
    band = dict(competition["assumptions"]["spread_to_cover_bp"]["band"])
    return {
        "name": "usd_rfq_hit_rate",
        "fitted_from": (
            f"synthetic labels: ratesresearch.hitrate.simulate_labels(n={n}, seed={LABEL_SEED}) "
            "from usd_rfq_competition"
        ),
        "data_range": {"start": "2026-10-31", "end": "2026-10-31"},
        "sample_size": int(n),
        "method": "logistic regression, Newton-Raphson MLE, research/ratesresearch/hitrate.py::fit",
        "parameters": params,
        "assumptions": {
            "spread_to_cover_bp": {
                "band": band,
                "rationale": competition["assumptions"]["spread_to_cover_bp"]["rationale"],
            }
        },
        "notes": (
            "Fitted to labels drawn from the declared simulator model, so the fit recovers the "
            "assumption; it is not a market hit rate (DR-008). The back-tester replaces "
            "spread_to_cover_bp by the band value of each run. Out-of-sample log-loss on the last "
            f"{f.n_test} labels: model {f.log_loss_model:.4f}, constant {f.log_loss_constant:.4f}."
        ),
    }


def main(repo_root: str | Path, n: int = 40_000) -> Fit:
    from ratesresearch.presets import write_preset

    path = Path(repo_root) / "data" / "presets.yaml"
    competition = get_preset(path, "usd_rfq_competition")
    x, y = simulate_labels(competition, n)
    f = fit(x, y)
    write_preset(path, preset_from_fit(f, competition, n))
    return f
