"""Build data/calibration/sim_v2_params.json from the calibration-period estimates.

Every entry has ``value``, ``unit``, ``interval`` (95 percent day bootstrap, or the sensitivity
band of an assumption), ``window`` and ``fitted_from`` (a manifest path, or "assumption").
``status`` is one of: ``fitted`` (estimated, interval from the data), ``bounded`` (the data
gives a range, the value is a stated choice inside it), ``assumption`` (not identified by any
data in the repository). docs/research/SIM_V2_CALIBRATION.md explains each choice.
"""

# ruff: noqa: E501  (units and notes are one-line strings so that the JSON reads as written)
from __future__ import annotations

import math
from typing import Any

from ratesresearch.simcal import CAL_END, CAL_START, CURVE_CAL_START, competitor
from ratesresearch.simcal.calibrate import SESSION_MINUTES, TENOR_KEYS
from ratesresearch.simcal.micro import EVENT_NAMES, EVENT_WINDOWS_S, N_BINS

DTCC = "data/manifests/sim_v2_dtcc.json"
PAR = "data/manifests/edge_par_curve.json"
CAL_FILES = "data/manifests/edge_alfred.json, data/manifests/edge_fomc.json, data/manifests/edge_auctions.json"
PRINT_WINDOW = f"{CAL_START} to {CAL_END}"
CURVE_WINDOW = f"{CURVE_CAL_START} to {CAL_END}"

# Declared values (not identified by the data). Bands are the sensitivity ranges to run.
COMPETITOR_LOG_SD = (0.5, 0.35, 0.7)
DEALERS = (3, 5)
RESPONSE_PROBABILITY = 0.9
ALL_IN_COST_BP = (0.08, 0.05, 0.15)
INFORMED_FRACTION = (0.2, 0.1, 0.4)
DRIFT_TIME_CONSTANT_S = (10.0, 2.0, 60.0)
DESK_INQUIRY_SHARE = (0.5, 0.2, 1.0)
SPREAD_FACTOR_SD = (0.7, 0.3, 1.3)
BASIS_SD_BP = (0.15, 0.05, 0.40)
BASIS_HALF_LIFE_MIN = (14.0, 5.0, 60.0)
IMPACT_Y = (0.7, 0.3, 1.5)
IMPACT_PERMANENT_SHARE = (0.5, 0.25, 0.75)
IMPACT_HALF_LIFE_S = (30.0, 5.0, 120.0)


def _p(
    value: Any, unit: str, interval: Any, window: str, fitted_from: str, status: str, note: str = ""
) -> dict[str, Any]:
    out = {
        "value": value,
        "unit": unit,
        "interval": interval,
        "window": window,
        "fitted_from": fitted_from,
        "status": status,
    }
    if note:
        out["note"] = note
    return out


def _assume(triple: tuple[float, float, float], unit: str, note: str) -> dict[str, Any]:
    return _p(triple[0], unit, [triple[1], triple[2]], "none", "assumption", "assumption", note)


def _r(x: float, digits: int = 4) -> float:
    return round(float(x), digits)


def _weights() -> dict[int, float]:
    return competitor.dealer_count_weights(DEALERS[0], DEALERS[1], RESPONSE_PROBABILITY)


def build(cal: dict[str, Any]) -> dict[str, Any]:
    curve, d2c, strict = cal["curve"], cal["strict_d2c"], cal["strict"]
    sd = curve["factor_sd_bp_per_day"]
    sig_pooled = strict["robust_sigma"][-1]
    mean_var = sum(
        v * v for v in cal["curve_in_print_window"]["tenor_sd_bp_per_day"].values()
    ) / len(TENOR_KEYS)
    share = sig_pooled["value"] ** 2 * SESSION_MINUTES / mean_var
    share_band = [
        min(v for v in cal["intraday_share_of_daily_variance"].values() if v is not None),
        max(v for v in cal["intraday_share_of_daily_variance"].values() if v is not None),
    ]
    per_min = math.sqrt(share / SESSION_MINUTES)
    ls_over_rob = (strict["ls_sigma"][-1]["value"] / sig_pooled["value"]) ** 2
    events = {}
    for k, name in enumerate(EVENT_NAMES):
        e = strict["season"][N_BINS + k]
        lo_s, hi_s = EVENT_WINDOWS_S[k]
        events[name] = _p(
            _r(e["value"] * ls_over_rob, 1),
            "extra variance per minute inside the window, as a multiple of the normal per-minute variance of every curve factor",
            [_r(max(e["lo"], 0.0) * ls_over_rob, 1), _r(e["hi"] * ls_over_rob, 1)],
            PRINT_WINDOW,
            f"{DTCC}, {CAL_FILES}",
            "fitted",
            f"window {int(lo_s // 3600):02d}:{int(lo_s % 3600 // 60):02d} to {int(hi_s // 3600):02d}:{int(hi_s % 3600 // 60):02d} New York on days with the event; least-squares estimate rescaled to the robust variance unit by {_r(ls_over_rob, 2)}",
        )
    eta_s, eta_l = d2c["robust_eta_short"][-1], d2c["robust_eta_long"][-1]
    w = _weights()
    ws = competitor.winner_spread(COMPETITOR_LOG_SD[0], w)
    rms_central = math.sqrt(eta_s["value"] * eta_l["value"])
    median = rms_central / ws.rms
    winner_mean = median * ws.mean
    adverse = winner_mean - ALL_IN_COST_BP[0]
    raw5 = d2c["markout_raw"][-1][-1]
    change = d2c["markout_change_first_to_5min"][-1]
    tod = [x["value"] for x in d2c["time_of_day_share"]]
    per_day = d2c["prints_per_day"]
    total = sum(x["value"] for x in per_day)
    sizes = cal["sizes_strict_d2c"]
    out: dict[str, Any] = {
        "schema": "sim_v2_params/1",
        "pre_analysis_note": "research/prereg/SIM3-CAL-001.yaml",
        "evidence": "docs/research/SIM_V2_CALIBRATION.md",
        "specification": "docs/research/SIM_V2_DESIGN.md",
        "reproduce": "python -m ratesresearch.simcal calibrate --raw RAW --cache CACHE --out cal.json; python -m ratesresearch.simcal params --cal cal.json",
        "tenors_years": [2, 3, 5, 7, 10, 20, 30],
        "session": {"start_ny": "07:00", "end_ny": "17:00", "minutes": SESSION_MINUTES},
        "curve_factors": {
            "loadings": _p(
                curve["loadings"],
                "bp of par yield per unit of factor (columns: level, slope, curvature; unit-length columns)",
                None,
                CURVE_WINDOW,
                PAR,
                "fitted",
                "PCA of the covariance of daily par-yield changes",
            ),
            "daily_sd": _p(
                [x["value"] for x in sd],
                "bp per day (factor units)",
                [[x["lo"], x["hi"]] for x in sd],
                CURVE_WINDOW,
                PAR,
                "fitted",
                "subperiod values are in the evidence document; the level factor ranges from 10.1 to 17.1 across the three subperiods",
            ),
            "session_variance_share": _p(
                _r(share, 3),
                "share of the daily variance that is diffusive variance inside the session",
                [_r(share_band[0], 3), _r(share_band[1], 3)],
                PRINT_WINDOW,
                f"{DTCC}, {PAR}",
                "fitted",
                "pooled robust print volatility squared times 600 minutes over the mean daily variance of the par yields in the same window; interval = range over tenors",
            ),
            "sd_per_sqrt_minute": _p(
                [_r(x["value"] * per_min) for x in sd],
                "bp per sqrt-minute (factor units), outside event windows",
                [[_r(x["lo"] * per_min), _r(x["hi"] * per_min)] for x in sd],
                f"{CURVE_WINDOW}; share from {PRINT_WINDOW}",
                f"{PAR}, {DTCC}",
                "fitted",
                "daily_sd times sqrt(session_variance_share / 600)",
            ),
            "idiosyncratic_sd_per_sqrt_minute": _p(
                {k: _r(v * per_min) for k, v in curve["residual_sd_bp_per_day"].items()},
                "bp per sqrt-minute, independent per tenor",
                None,
                CURVE_WINDOW,
                PAR,
                "fitted",
                "daily residual of the three-factor fit, same intraday scaling",
            ),
            "time_of_day_multiplier": _p(
                1.0,
                "multiplier of the per-minute variance, flat over the session",
                None,
                PRINT_WINDOW,
                DTCC,
                "bounded",
                "the 30-minute multipliers are not stable between print subsets (evidence document, table 6); flat is used outside the event windows",
            ),
            "event_windows": events,
        },
        "swap_spread_factor": {
            "daily_sd": _p(
                SPREAD_FACTOR_SD[0],
                "bp per day, added to every swap rate with loading 1",
                [SPREAD_FACTOR_SD[1], SPREAD_FACTOR_SD[2]],
                PRINT_WINDOW,
                f"{DTCC}, {PAR}",
                "bounded",
                "the measured daily change of swap minus par yield has sd 1.4 to 2.3 bp including measurement noise and 0.9 to 1.7 bp after the autocovariance correction; these are upper figures because the two legs are observed at different minutes",
            ),
            "sd_per_sqrt_minute": _p(
                _r(SPREAD_FACTOR_SD[0] * per_min),
                "bp per sqrt-minute",
                [_r(SPREAD_FACTOR_SD[1] * per_min), _r(SPREAD_FACTOR_SD[2] * per_min)],
                PRINT_WINDOW,
                f"{DTCC}, {PAR}",
                "bounded",
                "daily_sd with the curve factors' intraday scaling",
            ),
            "loading_by_tenor": _p(
                1.0,
                "bp of swap rate per bp of factor, every tenor",
                None,
                PRINT_WINDOW,
                DTCC,
                "bounded",
                "first component of the residual spread changes at 5, 10 and 30 years has near-equal weights; 3, 7 and 20 years have too few closing prints to test",
            ),
        },
        "futures_basis": {
            "stationary_sd": _assume(
                BASIS_SD_BP,
                "bp of yield of the cheapest-to-deliver (net basis in yield terms)",
                "no futures data in the repository; Ornstein-Uhlenbeck",
            ),
            "half_life": _assume(
                BASIS_HALF_LIFE_MIN, "minutes", "no futures data in the repository"
            ),
        },
        "rfq": {
            "market_prints_per_day_by_tenor": _p(
                {k: x["value"] for k, x in zip(TENOR_KEYS, per_day, strict=True)},
                "D2C facility prints per day (strict subset)",
                {k: [x["lo"], x["hi"]] for k, x in zip(TENOR_KEYS, per_day, strict=True)},
                PRINT_WINDOW,
                DTCC,
                "fitted",
            ),
            "tenor_mix": _p(
                {k: _r(x["value"] / total) for k, x in zip(TENOR_KEYS, per_day, strict=True)},
                "probability",
                None,
                PRINT_WINDOW,
                DTCC,
                "fitted",
            ),
            "time_of_day_share": _p(
                tod,
                "share of the day's prints per 30-minute bin from 07:00 New York (20 bins)",
                [[x["lo"], x["hi"]] for x in d2c["time_of_day_share"]],
                PRINT_WINDOW,
                DTCC,
                "fitted",
            ),
            "desk_inquiry_share": _assume(
                DESK_INQUIRY_SHARE,
                "desk swap RFQs per market D2C print",
                "prints are completed trades of the whole market; the share of inquiries one dealer sees is not in the data",
            ),
            "swap_rfqs_per_day": _p(
                _r(total * DESK_INQUIRY_SHARE[0], 1),
                "RFQs per session, swaps",
                [_r(total * DESK_INQUIRY_SHARE[1], 1), _r(total * DESK_INQUIRY_SHARE[2], 1)],
                PRINT_WINDOW,
                DTCC,
                "bounded",
                "market prints per day times desk_inquiry_share",
            ),
            "size_lognormal_by_tenor": _p(
                {
                    k: None
                    if v is None
                    else {
                        "log_mean_mm": v["log_mean"]["value"],
                        "log_sd": v["log_sd"]["value"],
                        "cap_mm": v["cap_mm_median"],
                        "capped_share": v["capped_share"],
                    }
                    for k, v in sizes.items()
                },
                "notional in USD millions: exp(N(log_mean_mm, log_sd^2)), truncated at cap_mm",
                {
                    k: None
                    if v is None
                    else {"log_mean_se": v["log_mean"]["se"], "log_sd_se": v["log_sd"]["se"]}
                    for k, v in sizes.items()
                },
                PRINT_WINDOW,
                DTCC,
                "fitted",
                "right-censored maximum likelihood; standard errors instead of bootstrap intervals",
            ),
            "treasury_rfqs": _assume(
                (1.0, 0.5, 2.0),
                "Treasury RFQs per swap RFQ, with the size ladder of the present presets.json",
                "no Treasury trade data (FINRA needs an account)",
            ),
        },
        "competitors": {
            "winner_half_spread_rms": _p(
                _r(rms_central),
                "bp of yield, root mean square half-spread of the winning quote",
                [eta_s["value"], eta_l["value"]],
                PRINT_WINDOW,
                DTCC,
                "bounded",
                f"geometric mean of the short-gap dispersion {eta_s['value']} [{eta_s['lo']}, {eta_s['hi']}] (lower figure) and the long-gap dispersion {eta_l['value']} [{eta_l['lo']}, {eta_l['hi']}] (upper figure)",
            ),
            "log_sd": _assume(
                COMPETITOR_LOG_SD,
                "log standard deviation of a competitor's half-spread",
                "kept from presets.json; the prints do not separate dispersion across dealers from other noise",
            ),
            "dealers_min_max": _assume(
                (float(DEALERS[0]), 3.0, 5.0),
                "dealers asked, uniform from min to max inclusive (max 5)",
                "kept from presets.json",
            ),
            "response_probability": _assume(
                (RESPONSE_PROBABILITY, 0.8, 1.0), "probability", "kept from presets.json"
            ),
            "median_half_spread": _p(
                _r(median),
                "bp of yield, all tenors and sizes",
                [_r(eta_s["value"] / ws.rms), _r(eta_l["value"] / ws.rms)],
                PRINT_WINDOW,
                DTCC,
                "bounded",
                f"winner_half_spread_rms / {_r(ws.rms)} (root mean square of the lowest of the answering quotes per unit median); expected winning half-spread = {_r(winner_mean)} bp",
            ),
            "all_in_cost": _assume(
                ALL_IN_COST_BP,
                "bp of client DV01: hedge spread, fees and capital of a competitor; equals its realised half-spread at zero profit",
                "lower figure 0.05 is the measured raw 5-minute realised half-spread, which misclassified sides pull towards zero",
            ),
            "size_and_tenor_scaling": _p(
                0.0,
                "exponent of size and of tenor in the median half-spread",
                None,
                PRINT_WINDOW,
                DTCC,
                "bounded",
                "dispersion in bp of yield is flat across tenors and does not rise with size on the D2C facilities (evidence document, tables 7 and 8)",
            ),
        },
        "informed_flow": {
            "expected_adverse_move": _p(
                _r(adverse),
                "bp of yield per trade, averaged over all client trades, in the direction of the client",
                [0.0, _r(eta_l["value"] / ws.rms * ws.mean - max(-raw5["hi"], 0.0))],
                PRINT_WINDOW,
                DTCC,
                "bounded",
                "zero-profit value: expected winning half-spread minus all_in_cost. Upper end: largest winning half-spread the dispersion supports minus the smallest realised half-spread the markouts support",
            ),
            "informed_fraction": _assume(
                INFORMED_FRACTION,
                "probability that an RFQ is informed",
                "only the product fraction x move is constrained",
            ),
            "move_per_informed_trade": _p(
                _r(adverse / INFORMED_FRACTION[0]),
                "bp of yield at the traded tenor, expected, delivered through the level factor",
                None,
                PRINT_WINDOW,
                DTCC,
                "bounded",
                "expected_adverse_move / informed_fraction",
            ),
            "drift_time_constant": _assume(
                DRIFT_TIME_CONSTANT_S,
                "seconds (exponential approach)",
                f"not identified: the signed markout changes by {change['value']} [{change['lo']}, {change['hi']}] bp between about 3 s and 5 min",
            ),
            "measured_raw_realised_half_spread_5min": _p(
                _r(-raw5["value"]),
                "bp, mean of -s (r_ref - r), all sizes, D2C strict prints",
                [_r(-raw5["hi"]), _r(-raw5["lo"])],
                PRINT_WINDOW,
                DTCC,
                "fitted",
                "attenuated towards zero by sign misclassification; a lower figure for the realised half-spread",
            ),
        },
        "clob": {
            "impact_coefficient_Y": _assume(
                IMPACT_Y,
                "dimensionless: peak impact = Y x daily sd x sqrt(quantity / daily volume)",
                "square-root law, Toth et al., Physical Review X 1, 021006 (2011): Y of order one on futures; not fitted to Treasuries here",
            ),
            "impact_permanent_share": _assume(
                IMPACT_PERMANENT_SHARE,
                "share of peak impact that stays in the latent mid",
                "assumption",
            ),
            "impact_half_life": _assume(
                IMPACT_HALF_LIFE_S,
                "seconds, exponential decay of the transient part and refill of consumed depth",
                "assumption; exponential resilience as in Obizhaeva and Wang, Journal of Financial Markets 16 (2013) 1-32",
            ),
            "daily_volume_usd_bn": _assume(
                (40.0, 20.0, 80.0),
                "USD billions per day per on-the-run Treasury on the simulated book",
                "assumption; to be replaced when a Treasury volume source is added",
            ),
            "futures_daily_volume_contracts": _assume(
                (1_500_000.0, 750_000.0, 3_000_000.0),
                "contracts per day, 10-year note future",
                "assumption",
            ),
            "taker_trade_size_usd_mm": _assume(
                (5.0, 1.0, 25.0),
                "USD millions, median of other takers' trades (lognormal, log sd 1.0)",
                "assumption",
            ),
        },
        "old_constants_check": {},
    }
    old_ws = competitor.winner_spread(0.5, w)
    old_winner = 0.25 * old_ws.mean
    out["old_constants_check"] = {
        "old_median_half_spread_bp": 0.25,
        "old_expected_winning_half_spread_bp": _r(old_winner),
        "old_winning_half_spread_rms_bp": _r(0.25 * old_ws.rms),
        "data_range_winner_rms_bp": [eta_s["value"], eta_l["value"]],
        "old_expected_adverse_bp": 0.2,
        "old_implied_realised_half_spread_bp": _r(old_winner - 0.2),
        "measured_raw_realised_half_spread_bp": [
            _r(-raw5["hi"]),
            _r(-raw5["value"]),
            _r(-raw5["lo"]),
        ],
        "old_winner_profit_bp_at_hedge_cost_0p086": _r(
            competitor.winner_profit(0.25, 0.2, 0.086, 0.5, w)
        ),
        "verdict": "the old median half-spread is inside the range the dispersion supports; the old adverse move (0.20 bp per trade) exceeds the winning half-spread, which implies a negative realised half-spread, while the measured one is positive: outside",
    }
    return out


def competitor_table(p: dict[str, Any]) -> str:
    """Zero-profit median half-spread over the bands of adverse move, cost and log-sd."""
    w = _weights()
    lines = [
        "log_sd  adverse_bp  cost_bp  zero_profit_median_bp  expected_winner_bp  winner_rms_bp"
    ]
    adverse = p["informed_flow"]["expected_adverse_move"]
    for log_sd in COMPETITOR_LOG_SD:
        ws = competitor.winner_spread(log_sd, w)
        for a in (adverse["interval"][0], adverse["value"], adverse["interval"][1]):
            for c in ALL_IN_COST_BP:
                m = competitor.zero_profit_median(a, c, log_sd, w)
                lines.append(
                    f"{log_sd:6.2f}  {a:10.3f}  {c:7.3f}  {m:21.3f}  {m * ws.mean:18.3f}  {m * ws.rms:13.3f}"
                )
    return "\n".join(lines)
