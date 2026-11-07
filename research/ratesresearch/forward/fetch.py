"""Download one business day of real rates and write the forward log's input file.

Sources (terms read 2026-10-31 and re-checked 2026-11-07; data/SOURCES.md):

* US Treasury daily par yield curve, the yearly CSV. Public domain.
* NY Fed SOFR, markets API JSON. NY Fed Terms of Use; notice
  "(c) <year> Federal Reserve Bank of New York".
* DTCC SDR end-of-day cumulative rates slice, for SOFR OIS swap marks. DTCC terms; CFTC Part 43
  public data. The raw file is never committed, only the derived median rate and the count.

The input file is ``<inputs_dir>/<as_of>.json``: the Treasury curve of ``as_of`` (the latest
Treasury date, or the one given), the latest SOFR fixing on or before it, the four swap marks
and a source list with each download's URL, size and SHA-256. Nothing in it is synthetic.

Usage: ``python -m ratesresearch.forward.fetch --inputs-dir forward/inputs [--as-of YYYY-MM-DD]``.
Exit status 0 when the file was written or already exists, 3 when the Treasury has no row for
the requested day (a holiday: nothing to do), 1 on a download failure.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import sys
import tempfile
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import pandas as pd
import yaml

TREASURY_URL = (
    "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/"
    "daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve"
    "&field_tdr_date_value={year}&page&_format=csv"
)
SOFR_URL = "https://markets.newyorkfed.org/api/rates/secured/sofr/last/10.json"
DTCC_URL = "https://kgc0418-tdw-data-0.s3.amazonaws.com/cftc/eod/CFTC_CUMULATIVE_RATES_{d}.zip"
USER_AGENT = "ratesresearch/1.0.0 (+https://github.com/AshJha0/rates-algo-trading)"

# Treasury column per tenor key used by the desk.
UST_COLUMNS = {
    "1M": "1 Mo",
    "3M": "3 Mo",
    "6M": "6 Mo",
    "1Y": "1 Yr",
    "2Y": "2 Yr",
    "3Y": "3 Yr",
    "5Y": "5 Yr",
    "7Y": "7 Yr",
    "10Y": "10 Yr",
    "20Y": "20 Yr",
    "30Y": "30 Yr",
}
SWAP_TENORS = {"2Y": 2.0, "5Y": 5.0, "10Y": 10.0, "30Y": 30.0}
ET = "America/New_York"

Fetcher = Callable[[str], bytes]


@dataclass(frozen=True)
class Download:
    name: str
    url: str
    body: bytes
    fetched_utc: str

    def record(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "url": self.url,
            "bytes": len(self.body),
            "sha256": hashlib.sha256(self.body).hexdigest(),
            "fetched_utc": self.fetched_utc,
        }


def http_get(url: str, timeout: float = 60.0) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body: bytes = resp.read()
        return body


def _now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


# ------------------------------------------------------------------ parsing (pure)


def parse_treasury(text: str) -> dict[date, dict[str, float]]:
    """Par yields in percent by date and tenor key; blank cells are left out."""
    out: dict[date, dict[str, float]] = {}
    for row in csv.DictReader(io.StringIO(text)):
        d = datetime.strptime(row["Date"], "%m/%d/%Y").date()
        out[d] = {k: float(row[c]) for k, c in UST_COLUMNS.items() if row.get(c, "").strip()}
    return out


def parse_sofr(text: str, on_or_before: date) -> dict[str, Any]:
    """The latest SOFR fixing effective on or before ``on_or_before``."""
    rates = json.loads(text)["refRates"]
    best: dict[str, Any] | None = None
    for r in rates:
        d = date.fromisoformat(r["effectiveDate"])
        if d <= on_or_before and (best is None or d > date.fromisoformat(best["effectiveDate"])):
            best = r
    if best is None:
        raise ValueError(f"no SOFR fixing on or before {on_or_before}")
    return {
        "effective_date": best["effectiveDate"],
        "pct": float(best["percentRate"]),
        "revision": best.get("revisionIndicator", "") or "",
    }


def dtcc_prints(zip_bytes: bytes, day: date) -> pd.DataFrame:
    """Spot-starting, non-package USD SOFR OIS prints of ``day``: t (New York), tenor, rate (%)."""
    from ratesresearch.edge.build import parse_dtcc  # the EDGE-E5 filter, reused unchanged

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / f"CFTC_CUMULATIVE_RATES_{day.strftime('%Y_%m_%d')}.zip"
        path.write_bytes(zip_bytes)
        return parse_dtcc(path)


def dtcc_marks(prints: pd.DataFrame, settings: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """Median print rate per tenor inside the window, where there are enough prints."""
    a = int(settings["window_start_minute_et"])
    b = int(settings["window_end_minute_et"])
    need = int(settings["min_prints"])
    out: dict[str, dict[str, Any]] = {}
    if prints.empty:
        return out
    mins = prints["t"].dt.hour * 60 + prints["t"].dt.minute
    for key, tenor in SWAP_TENORS.items():
        w = prints[(prints["tenor"] == tenor) & (mins >= a) & (mins < b)]
        if len(w) >= need:
            out[key] = {
                "rate": round(float(w["rate"].median()), 6),
                "source": "dtcc_median",
                "prints": len(w),
            }
    return out


def last_spreads(inputs_dir: Path, before: date) -> dict[str, tuple[float, str]]:
    """Per tenor, the DTCC-minus-Treasury spread (bp) of the latest earlier DTCC-marked input."""
    out: dict[str, tuple[float, str]] = {}
    files = sorted(p for p in inputs_dir.glob("*.json") if p.stem < before.isoformat())
    for p in reversed(files):
        doc = json.loads(p.read_text(encoding="utf-8"))
        for key, mark in doc["swaps_pct"].items():
            if key in out or mark["source"] != "dtcc_median":
                continue
            ust = doc["ust_par_pct"].get(key)
            if ust is not None:
                out[key] = (round((mark["rate"] - ust) * 100.0, 4), p.stem)
        if len(out) == len(SWAP_TENORS):
            break
    return out


def swap_marks(
    ust: dict[str, float],
    dtcc: dict[str, dict[str, Any]],
    spreads: dict[str, tuple[float, str]],
    settings: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    """DTCC mark where there is one, else Treasury par plus the carried or declared spread."""
    out: dict[str, dict[str, Any]] = {}
    for key in SWAP_TENORS:
        if key in dtcc:
            out[key] = dtcc[key]
            continue
        if key in spreads:
            bp, frm = spreads[key]
        else:
            bp, frm = float(settings["fallback_spread_bp"][key]), "config"
        out[key] = {
            "rate": round(ust[key] + bp / 100.0, 6),
            "source": "ust_plus_spread",
            "spread_bp": bp,
            "spread_from": frm,
        }
    return out


def build_inputs(
    as_of: date | None,
    inputs_dir: Path,
    settings: dict[str, Any],
    get: Fetcher,
    today: date,
) -> dict[str, Any] | None:
    """The input document, or None when the Treasury has no row for ``as_of``."""
    year = (as_of or today).year
    t_url = TREASURY_URL.format(year=year)
    treasury = Download("treasury_par_curve", t_url, get(t_url), _now())
    curve = parse_treasury(treasury.body.decode("utf-8-sig"))
    if as_of is None:
        as_of = max(d for d in curve if d <= today)
    if as_of not in curve:
        return None
    ust = curve[as_of]
    sofr_dl = Download("nyfed_sofr", SOFR_URL, get(SOFR_URL), _now())
    sofr = parse_sofr(sofr_dl.body.decode("utf-8"), as_of)
    sources = [treasury.record(), sofr_dl.record()]
    d_url = DTCC_URL.format(d=as_of.strftime("%Y_%m_%d"))
    dtcc: dict[str, dict[str, Any]] = {}
    try:
        d_dl = Download("dtcc_sdr_rates_eod", d_url, get(d_url), _now())
        dtcc = dtcc_marks(dtcc_prints(d_dl.body, as_of), settings)
        sources.append(d_dl.record())
    except Exception as e:
        sources.append(
            {"name": "dtcc_sdr_rates_eod", "url": d_url, "error": f"{type(e).__name__}: {e}"}
        )
    swaps = swap_marks(ust, dtcc, last_spreads(inputs_dir, as_of), settings)
    return {
        "schema": "forward_inputs/1",
        "as_of": as_of.isoformat(),
        "ust_par_pct": ust,
        "sofr": sofr,
        "swaps_pct": swaps,
        "sources": sources,
        "notice": "SOFR (c) " + str(as_of.year) + " Federal Reserve Bank of New York. "
        "Treasury par yields: US Treasury, public domain. Swap marks derived from DTCC public "
        "price dissemination (CFTC Part 43); raw DTCC data not redistributed.",
    }


def write_canonical(doc: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(doc, sort_keys=True, indent=1) + "\n", encoding="utf-8", newline="\n"
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--inputs-dir", required=True, type=Path)
    ap.add_argument("--as-of", type=date.fromisoformat, default=None)
    ap.add_argument("--config", type=Path, default=Path("config/forward/desk.yaml"))
    a = ap.parse_args(argv)
    settings = yaml.safe_load(a.config.read_text(encoding="utf-8"))["swap_mark"]
    if a.as_of is not None and (a.inputs_dir / f"{a.as_of}.json").exists():
        print(f"inputs for {a.as_of} already exist")
        return 0
    try:
        doc = build_inputs(a.as_of, a.inputs_dir, settings, http_get, datetime.now(UTC).date())
    except Exception as e:
        print(f"download failed: {type(e).__name__}: {e}", file=sys.stderr)
        return 1
    if doc is None:
        print(f"no Treasury row for {a.as_of}: not a Treasury business day")
        return 3
    target = a.inputs_dir / f"{doc['as_of']}.json"
    if target.exists():
        print(f"inputs for {doc['as_of']} already exist")
        return 0
    write_canonical(doc, target)
    print(
        f"wrote {target}: swaps "
        + ", ".join(f"{k} {v['rate']} ({v['source']})" for k, v in doc["swaps_pct"].items())
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
