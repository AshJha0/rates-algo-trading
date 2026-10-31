"""The edge-study programme: run E1 to E6, Holm across the eleven primary tests, verdicts,
ledger rows and presets for the supported effects.

``python -m ratesresearch.edge.run`` prints the summary. ``--record`` appends one ledger row per
study (refused when that study already has an evaluation row: the holdout is opened once) and
writes the presets of supported effects to ``data/presets.yaml``.
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

from ratesresearch import prereg, presets
from ratesresearch.edge import (
    common,
    e1_auction,
    e2_monthend,
    e3_intraday,
    e4_size,
    e5_swapspread,
    e6_carry,
)
from ratesresearch.provenance import sha256_of

STUDIES = {
    "EDGE-E1": e1_auction,
    "EDGE-E2": e2_monthend,
    "EDGE-E3": e3_intraday,
    "EDGE-E4": e4_size,
    "EDGE-E5": e5_swapspread,
    "EDGE-E6": e6_carry,
}
FAMILY = ("E1a", "E1b", "E2", "E3a", "E3b", "E3c", "E3d", "E4a", "E4b", "E5", "E6")
MINIMUM = {"E1a": 40, "E1b": 40, "E2": 15, "E3a": 25, "E3b": 6, "E3c": 100, "E3d": 25,
           "E4a": 100, "E4b": 100, "E5": 50, "E6": 40}  # fmt: skip
LEDGER = Path("data/ledger/ledger.jsonl")


def run_all(root: Path) -> dict[str, Any]:
    """Every study's results, with Holm-adjusted p and the outcome of each primary test."""
    results = {sid: mod.run(root) for sid, mod in STUDIES.items()}
    tests: dict[str, dict[str, Any]] = {}
    for r in results.values():
        tests.update(r["tests"])
    common.apply_holm(tests)
    for name, t in tests.items():
        if name == "E4a":
            t["outcome"] = e4_size.judge_equivalence(t)
        else:
            t["outcome"] = common.judge(t, int(t["sign"]), float(t["threshold"]))
        if name == "E5" and t["outcome"] == "passed" and not t["oos_r2"] > 0:
            t["outcome"] = "failed"
        t["below_minimum"] = t["n"] < MINIMUM[name]
    verdicts = {
        "EDGE-E1": _all(tests, ("E1a", "E1b")),
        "EDGE-E2": _all(tests, ("E2",)),
        "EDGE-E3": _any(tests, ("E3a", "E3b"), ("E3a", "E3b", "E3c", "E3d")),
        "EDGE-E4": _all(tests, ("E4a",)),
        "EDGE-E5": "inconclusive",  # data defect, see e5_swapspread.deviation
        "EDGE-E6": _all(tests, ("E6",)),
    }
    return {"results": results, "tests": tests, "verdicts": verdicts}


def _all(tests: dict[str, dict[str, Any]], names: tuple[str, ...]) -> str:
    if any(tests[n]["below_minimum"] for n in names):
        return "inconclusive"
    return "supported" if all(tests[n]["outcome"] == "passed" for n in names) else "not supported"


def _any(tests: dict[str, dict[str, Any]], names: tuple[str, ...], study: tuple[str, ...]) -> str:
    if any(tests[n]["below_minimum"] for n in names):
        return "inconclusive"
    del study
    return "supported" if any(tests[n]["outcome"] == "passed" for n in names) else "not supported"


def _clean(obj: Any) -> Any:
    """Results without DataFrames and Series, for the ledger."""
    if isinstance(obj, dict):
        return {str(k): _clean(v) for k, v in obj.items() if not hasattr(v, "to_frame")
                and not hasattr(v, "iloc")}  # fmt: skip
    if isinstance(obj, list):
        return [_clean(v) for v in obj]
    return obj


def study_tests(sid: str) -> tuple[str, ...]:
    return tuple(n for n in FAMILY if n.startswith(sid.split("-")[1]))


def ledger_record(root: Path, sid: str, prog: dict[str, Any]) -> dict[str, Any]:
    pre = root / "research/prereg" / f"{sid.split('-')[1]}.yaml"
    cfg = root / "research/prereg" / f"{sid.split('-')[1]}.config.yaml"
    files = STUDIES[sid].data_files(root)
    commit, dirty = common.git_state(root)
    res = prog["results"][sid]
    record: dict[str, Any] = {
        "prereg_id": sid,
        "prereg_sha256": prereg.sha256_text_file(pre),
        "config_sha256": prereg.sha256_text_file(cfg),
        "programme_sha256": prereg.sha256_text_file(root / "research/prereg/edge_programme.yaml"),
        "code_commit": commit,
        "code_dirty": dirty,
        "data_sha256": common.files_sha256(files),
        "data": [{"name": f"data/fixtures/edge/{p.name}", "sha256": sha256_of(p)} for p in files],
        "data_range": res["data_range"],
        "look": 1,
        "scope": "evaluation",
        "tests": {n: prog["tests"][n] for n in study_tests(sid)},
        "results": _clean({k: v for k, v in res.items() if k != "tests"}),
        "statistics": {
            "bootstrap": "moving block",
            "resamples": common.RESAMPLES,
            "level": common.LEVEL,
            "seed": common.SEED,
            "multiple_comparison": f"holm over {len(FAMILY)} tests (programme family)",
        },
        "verdict": prog["verdicts"][sid],
    }
    deviations = DEVIATIONS.get(sid)
    if sid == "EDGE-E5":
        deviations = res["deviation"]
    if deviations:
        record["deviations"] = deviations
    return record


RESERVE_ROWS = (
    "While checking the file layout on 2026-10-31, the first rows of the 2026 par-curve "
    "file (newest first, dates 2026-10-28 to 2026-10-30, reserve period) were displayed; no "
    "statistic was computed on them and the build drops every date after 2026-06-30."
)
UNIT_FIX = (
    "Before this row was recorded, an implausible first E4b estimate (41 extra trades in 5 "
    "minutes) showed that pandas 3 stores these timestamps in microseconds, so int64 / 1e9 "
    "shrank time by 1000: the 300 s E4b horizon covered the whole day and the 30-minute gap "
    "filter of the E3 realised variance never applied. Both now use exact seconds "
    "(common.epoch_seconds). The metric is the registered one; E3a and E3b moved by under 0.12 "
    "in mean ratio, and no outcome of E3 changed."
)
DEVIATIONS = {
    "EDGE-E1": RESERVE_ROWS,
    "EDGE-E2": RESERVE_ROWS,
    "EDGE-E6": RESERVE_ROWS,
    "EDGE-E3": (
        "Release dates are ALFRED vintage dates (weekdays), which may include a vintage that "
        "was not an 08:30 release (annual or benchmark revisions); no date was removed by "
        "hand. " + UNIT_FIX
    ),
    "EDGE-E4": UNIT_FIX,
}


def preset_entries(root: Path, prog: dict[str, Any]) -> list[dict[str, Any]]:
    """Presets for the passed tests of supported studies (programme promotion rule)."""
    out: list[dict[str, Any]] = []
    t, v, r = prog["tests"], prog["verdicts"], prog["results"]
    seq = {e["prereg_id"]: e["seq"] for e in prereg.read_ledger(root / LEDGER)
           if str(e.get("prereg_id", "")).startswith("EDGE")}  # fmt: skip
    if v["EDGE-E1"] == "supported":
        c = r["EDGE-E1"]["band"]["central"]
        params = {
            "pre_auction_cheapening_bp": presets.param(t["E1a"]["mean"], t["E1a"]["se"]),
            "post_auction_richening_bp": presets.param(t["E1b"]["mean"], t["E1b"]["se"]),
            "window_pre_days": presets.param(5),
            "window_post_days": presets.param(3),
        }
        for ten, x in c["holdout_by_tenor"].items():
            params[f"tenor_{ten}y_pre_bp"] = presets.param(x["pre"])
            params[f"tenor_{ten}y_post_bp"] = presets.param(x["post"])
        out.append(_preset("ust_auction_concession", "EDGE-E1", r, t, ("E1a", "E1b"), params,
                           "2021-01-01", "2026-06-30", seq, v))  # fmt: skip
    if v["EDGE-E2"] == "supported":
        params = {
            "month_end_flattening_bp": presets.param(t["E2"]["mean"], t["E2"]["se"]),
            "window_days": presets.param(3),
            "extension_threshold_bn_years": presets.param(
                r["EDGE-E2"]["extension_threshold_bn_years"]
            ),
        }
        out.append(_preset("ust_month_end_extension", "EDGE-E2", r, t, ("E2",), params,
                           "2021-01-01", "2026-06-30", seq, v))  # fmt: skip
    if v["EDGE-E3"] == "supported":
        s = r["EDGE-E3"]["schedule"]
        params = {}
        for name, test in (("release", "E3a"), ("fomc", "E3b")):
            if t[test]["outcome"] != "passed":
                continue
            for row in s[name]:
                key = f"{name}_{row['bucket'].replace(':', '')}_spread_multiplier"
                params[key] = presets.param(row["multiplier"], row["multiplier_se"])
        for row in s["intensity_profile"]:
            key = f"intensity_{row['bucket'].replace(':', '')}_multiplier"
            params[key] = presets.param(row["multiplier"], row["se"])
        if t["E3d"]["outcome"] == "passed":
            params["release_0830_intensity_ratio"] = presets.param(
                1 + t["E3d"]["mean"], t["E3d"]["se"]
            )
        out.append(_preset("usd_time_of_day_spread", "EDGE-E3", r, t, ("E3a", "E3b", "E3c", "E3d"),
                           params, "2025-07-01", "2026-06-30", seq, v))  # fmt: skip
    if v["EDGE-E4"] == "supported":
        f = r["EDGE-E4"]["fits_holdout"]["central"]
        params = {}
        for ten, x in f.items():
            k = f"tenor_{int(ten)}y"
            params[f"{k}_log_mu"] = presets.param(x["mu"], x["mu_se"])
            params[f"{k}_log_sigma"] = presets.param(x["sigma"], x["sigma_se"])
            for q in ("p50", "p90", "p95", "p99"):
                params[f"{k}_{q}_mm"] = presets.param(x[q])
            params[f"{k}_trades_per_day"] = presets.param(
                r["EDGE-E4"]["trades_per_day"][str(int(ten))]
            )
        if t["E4b"]["outcome"] == "passed":
            params["post_large_trade_excess_5min"] = presets.param(t["E4b"]["mean"], t["E4b"]["se"])
        out.append(_preset("usd_sofr_ois_size_by_tenor", "EDGE-E4", r, t, ("E4a", "E4b"), params,
                           "2025-07-01", "2026-06-30", seq, v))  # fmt: skip
    if v["EDGE-E6"] == "supported":
        m = r["EDGE-E6"]["monthly"].iloc[-1]
        params = {
            "tilt_k": presets.param(0.5),
            "mean_excess_bp_per_month": presets.param(t["E6"]["mean"], t["E6"]["se"]),
        }
        for ten in e6_carry.TENORS:
            params[f"carry_roll_{ten}y_bp_at_end"] = presets.param(float(m[f"cr{ten}"]))
        out.append(_preset("ust_carry_roll_inventory_tilt", "EDGE-E6", r, t, ("E6",), params,
                           "2021-01-01", "2026-06-30", seq, v))  # fmt: skip
    return out


METHODS = {
    "EDGE-E1": (
        "auction-week mean of par-curve butterfly residual changes,"
        " research/ratesresearch/edge/e1_auction.py::run"
    ),
    "EDGE-E2": (
        "month-end 2s30s change less the non-month-end mean,"
        " research/ratesresearch/edge/e2_monthend.py::run"
    ),
    "EDGE-E3": (
        "event-day over control-day realised variance and trade "
        "counts per 5-minute bucket, research/ratesresearch/edge/e3_intraday.py::run"
    ),
    "EDGE-E4": (
        "censored lognormal MLE per tenor (EM), holdout fit,"
        " research/ratesresearch/edge/e4_size.py::run"
    ),
    "EDGE-E6": (
        "carry and roll-down tilt against equal DV01, research/ratesresearch/edge/e6_carry.py::run"
    ),
}
FITTED_FROM = {
    "EDGE-E1": "data/manifests/edge_par_curve.json, data/manifests/edge_auctions.json",
    "EDGE-E2": "data/manifests/edge_par_curve.json, data/manifests/edge_auctions.json",
    "EDGE-E3": (
        "data/manifests/edge_dtcc.json,"
        " data/manifests/edge_alfred.json, data/manifests/edge_fomc.json"
    ),
    "EDGE-E4": "data/manifests/edge_dtcc.json",
    "EDGE-E6": "data/manifests/edge_par_curve.json",
}


def _preset(
    name: str,
    sid: str,
    r: dict[str, Any],
    t: dict[str, dict[str, Any]],
    names: tuple[str, ...],
    params: dict[str, Any],
    start: str,
    end: str,
    seq: dict[str, int],
    v: dict[str, str],
) -> dict[str, Any]:
    outcomes = ", ".join(f"{n} {t[n]['outcome']} (Holm p {t[n]['holm_p']:.4g})" for n in names)
    return {
        "name": name,
        "fitted_from": FITTED_FROM[sid],
        "data_range": {"start": start, "end": end},
        "sample_size": int(t[names[0]]["n"]),
        "method": METHODS[sid],
        "parameters": params,
        "notes": (
            f"Holdout estimate (out of sample) of pre-registered study {sid} "
            f"(research/prereg/{sid.split('-')[1]}.yaml), ledger seq {seq.get(sid, 'pending')}, "
            f"holdout verdict {v[sid]}: {outcomes}. Values are holdout means with bootstrap "
            "standard errors; docs/research/EDGE_STUDIES.md."
        ),
    }


def main(argv: list[str]) -> int:
    root = Path(".")
    prog = run_all(root)
    for name, t in prog["tests"].items():
        print(f"{name}: n {t['n']} mean {t['mean']:.4g} [{t['ci_low']:.4g}, {t['ci_high']:.4g}] "
              f"p {t['p']:.4g} holm {t['holm_p']:.4g} {t['outcome']}")  # fmt: skip
    for sid, v in prog["verdicts"].items():
        print(sid, v)
    if "--record" in argv:
        existing = {e.get("prereg_id") for e in prereg.read_ledger(root / LEDGER)}
        for sid in STUDIES:
            if sid in existing:
                raise SystemExit(f"{sid} already has an evaluation record; the holdout opens once")
        for sid in STUDIES:
            common.append_ledger(root / LEDGER, ledger_record(root, sid, prog))
        for p in preset_entries(root, prog):
            presets.write_preset(root / "data/presets.yaml", p)
            print("preset", p["name"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
