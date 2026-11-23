"""Command line of the simulator calibration (SIM3-CAL-001).

    python -m ratesresearch.simcal manifest  --raw RAW [--fetch-log LOG]
    python -m ratesresearch.simcal calibrate --raw RAW --cache CACHE --out cal.json
    python -m ratesresearch.simcal validate  --raw RAW --cache CACHE --cal cal.json --out val.json
    python -m ratesresearch.simcal params    --cal cal.json
    python -m ratesresearch.simcal competitor

RAW is the directory of DTCC zips (outside the repository), CACHE a directory for the parsed
prints (outside the repository). ``calibrate`` reads file days up to the calibration end only;
``validate`` reads the validation period only and takes the curve loadings from ``cal.json``.
``params`` writes data/calibration/sim_v2_params.json; ``competitor`` prints the zero-profit
table from that file.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np

from ratesresearch import provenance
from ratesresearch.data import dtcc
from ratesresearch.simcal import (
    CAL_END,
    CAL_START,
    CURVE_CAL_START,
    VAL_END,
    VAL_START,
    calibrate,
    params,
    prints,
)

REPO = Path(__file__).resolve().parents[3]
MANIFEST = REPO / "data/manifests/sim_v2_dtcc.json"
PARAMS = REPO / "data/calibration/sim_v2_params.json"


def _manifest(raw: Path, fetch_log: Path | None) -> None:
    files = sorted(raw.glob("CFTC_CUMULATIVE_RATES_*.zip"))
    m = provenance.write_manifest(files, MANIFEST)
    for entry in m["files"]:
        entry["url"] = dtcc.URL.format(d=entry["name"][22:32])
    m["source"] = "dtcc"
    m["licence"] = dtcc.LICENCE
    m["note"] = (
        "Second download of the files of data/manifests/edge_dtcc.json, for SIM3-CAL-001. Every "
        "file here has the SHA-256 recorded in that manifest. Files listed under missing were in "
        "the first manifest and could not be fetched again."
    )
    m["missing"] = []
    if fetch_log is not None:
        log = json.loads(fetch_log.read_text(encoding="utf-8"))
        times = {x["name"]: x.get("downloaded_utc") for x in log}
        for entry in m["files"]:
            entry["downloaded_utc"] = times.get(entry["name"])
        m["missing"] = [
            {"name": x["name"], "error": x["status"]} for x in log if "sha256" not in x
        ]
    first = provenance.load_manifest(REPO / "data/manifests/edge_dtcc.json")
    known = {f["name"]: f["sha256"] for f in first["files"]}
    m["sha256_equal_to_edge_dtcc"] = all(known.get(f["name"]) == f["sha256"] for f in m["files"])
    MANIFEST.write_text(json.dumps(m, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{MANIFEST}: {len(m['files'])} files, {len(m['missing'])} missing")


def _write(path: Path, data: dict[str, Any]) -> None:
    data["created_utc"] = datetime.now(UTC).isoformat(timespec="seconds")
    path.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {path}")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(prog="python -m ratesresearch.simcal")
    ap.add_argument("command", choices=["manifest", "calibrate", "validate", "params", "competitor"])
    ap.add_argument("--raw", type=Path)
    ap.add_argument("--cache", type=Path)
    ap.add_argument("--cal", type=Path)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--fetch-log", type=Path)
    ap.add_argument("--resamples", type=int, default=1000)
    a = ap.parse_args(argv)
    if a.command == "manifest":
        _manifest(a.raw, a.fetch_log)
        return 0
    if a.command == "params":
        cal = json.loads(a.cal.read_text(encoding="utf-8"))
        PARAMS.parent.mkdir(parents=True, exist_ok=True)
        PARAMS.write_text(json.dumps(params.build(cal), indent=2) + "\n", encoding="utf-8")
        print(f"wrote {PARAMS}")
        return 0
    if a.command == "competitor":
        print(params.competitor_table(json.loads(PARAMS.read_text(encoding="utf-8"))))
        return 0
    par, calendar, auctions = calibrate.load_inputs(REPO)
    if a.command == "calibrate":
        p, funnel = prints.load(a.raw, a.cache, CAL_START, CAL_END)
        out = calibrate.estimate_period(
            p, funnel, par, calendar, auctions, CAL_START, CAL_END, CURVE_CAL_START, None, a.resamples
        )
        out["subperiods"] = {
            name: calibrate.factor_estimates(
                par, s, e, np.array([out["curve"]["loadings"][k] for k in calibrate.TENOR_KEYS])
            )
            for name, s, e in calibrate.factors.SUBPERIODS
        }
        _write(a.out, out)
        return 0
    cal = json.loads(a.cal.read_text(encoding="utf-8"))
    loadings = np.array([cal["curve"]["loadings"][k] for k in calibrate.TENOR_KEYS])
    p, funnel = prints.load(a.raw, a.cache, VAL_START, VAL_END)
    out = calibrate.estimate_period(
        p, funnel, par, calendar, auctions, VAL_START, VAL_END, VAL_START, loadings, a.resamples
    )
    out["comparison"] = calibrate.compare(cal, out)
    _write(a.out, out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
