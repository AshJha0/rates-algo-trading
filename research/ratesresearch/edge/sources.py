"""Downloads for the edge studies, each file recorded with URL, licence, time, size and SHA-256.

Raw files go under ``data/raw/edge/<source>/`` (ignored by git, DR-006). Each source writes a
manifest ``data/manifests/edge_<source>.json`` with ``manifest_version`` 1 and, per file, the
fields of ``ratesresearch.provenance.write_manifest`` plus ``url`` and ``downloaded_utc``.

Usage: ``python -m ratesresearch.edge.sources <source> [...]`` with sources from ``SOURCES``.
"""

from __future__ import annotations

import http.client
import json
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

from ratesresearch.data import dtcc
from ratesresearch.provenance import MANIFEST_VERSION, sha256_of

USER_AGENT = "ratesresearch/0.0.1 (+https://github.com/AshJha0/rates-algo-trading)"
RAW = Path("data/raw/edge")
MANIFESTS = Path("data/manifests")

TREASURY_LICENCE = (
    "US Treasury and TreasuryDirect data: work of the US federal government, public domain "
    "(17 U.S.C. 105); TreasuryDirect Terms & Conditions state no copyright restriction. "
    "Read 2026-10-31."
)
FED_LICENCE = (
    "Board of Governors of the Federal Reserve System website: public domain US government "
    "information unless marked otherwise; feds200628 is the Gurkaynak, Sack and Wright (2007) "
    "dataset, cite FEDS 2006-28. Read 2026-10-31."
)
CFTC_LICENCE = "CFTC Commitments of Traders: work of the US federal government, public domain."
BEA_LICENCE = "BEA release schedule: work of the US federal government, public domain."


@dataclass(frozen=True)
class Item:
    name: str
    url: str


def business_days(start: date, end: date) -> list[date]:
    out = []
    d = start
    while d <= end:
        if d.weekday() < 5:
            out.append(d)
        d += timedelta(days=1)
    return out


def dtcc_days() -> list[date]:
    """Every business day 2025-01-02 to 2026-06-30, plus the first weekday of each 2024 week.

    Weekday holidays have no file; the fetch records them as missing.
    """
    days = set(business_days(date(2025, 1, 2), date(2026, 6, 30)))
    d = date(2024, 1, 1)
    while d < date(2025, 1, 1):
        for k in range(5):
            days.add(d + timedelta(days=k))  # the first available weekday is chosen when built
        d += timedelta(days=7)
    return sorted(x for x in days if x.weekday() < 5)


def _treasury_url(year: int) -> str:
    return (
        "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/"
        f"daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve"
        f"&field_tdr_date_value={year}&page&_format=csv"
    )


def _td_url(kind: str, start: str, end: str) -> str:
    return (
        "https://www.treasurydirect.gov/TA_WS/securities/search?format=json"
        f"&type={kind}&dateFieldName=issueDate&startDate={start}&endDate={end}"
    )


def _items_par() -> list[Item]:
    return [Item(f"par_curve_{y}.csv", _treasury_url(y)) for y in range(2015, 2027)]


def _items_auctions() -> list[Item]:
    out = []
    for kind in ("Note", "Bond"):
        for y in range(2014, 2027):
            start = "12/01/2014" if y == 2014 else f"01/01/{y}"
            end = "12/31/2014" if y == 2014 else (f"07/31/{y}" if y == 2026 else f"12/31/{y}")
            if y == 2014:
                out.append(Item(f"td_{kind.lower()}_2014.json", _td_url(kind, start, end)))
            else:
                out.append(Item(f"td_{kind.lower()}_{y}.json", _td_url(kind, start, end)))
    return out


def _items_gsw() -> list[Item]:
    return [
        Item(
            "feds200628.csv",
            "https://www.federalreserve.gov/data/yield-curve-tables/feds200628.csv",
        )
    ]


def _items_cot() -> list[Item]:
    return [
        Item(f"fut_fin_txt_{y}.zip", f"https://www.cftc.gov/files/dea/history/fut_fin_txt_{y}.zip")
        for y in range(2023, 2027)
    ]


def _items_fomc() -> list[Item]:
    return [
        Item("fomccalendars.htm", "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm")
    ]


def _items_bea() -> list[Item]:
    return [
        Item("bea_schedule.htm", "https://www.bea.gov/news/schedule"),
        Item("bea_schedule_2025.htm", "https://www.bea.gov/news/schedule/full?year=2025"),
        Item("bea_schedule_2026.htm", "https://www.bea.gov/news/schedule/full?year=2026"),
    ]


def _items_alfred() -> list[Item]:
    # ALFRED vintage list pages: the first vintage date of a release is its publication day.
    return [
        Item(f"alfred_{s}.htm", f"https://alfred.stlouisfed.org/series/downloaddata?seid={s}")
        for s in ("PAYEMS", "CPIAUCSL", "GDPC1", "PCEPI")
    ]


def _items_dtcc() -> list[Item]:
    return [
        Item(f"CFTC_CUMULATIVE_RATES_{d.strftime('%Y_%m_%d')}.zip", dtcc.url_for(d))
        for d in dtcc_days()
    ]


SOURCES: dict[str, tuple[str, Callable[[], list[Item]]]] = {
    "par_curve": (TREASURY_LICENCE, _items_par),
    "auctions": (TREASURY_LICENCE, _items_auctions),
    "gsw": (FED_LICENCE, _items_gsw),
    "cot": (CFTC_LICENCE, _items_cot),
    "fomc": (FED_LICENCE, _items_fomc),
    "bea": (BEA_LICENCE, _items_bea),
    "alfred": (
        "FRED/ALFRED terms of use (St. Louis Fed); series data from BLS and BEA.",
        _items_alfred,
    ),
    "dtcc": (dtcc.LICENCE, _items_dtcc),
}


def _get(url: str, timeout: float = 120.0) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    last: Exception | None = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data: bytes = resp.read()
                return data
        except urllib.error.HTTPError as e:
            if e.code in (403, 404):
                raise
            last = e
        except (urllib.error.URLError, TimeoutError, http.client.HTTPException, OSError) as e:
            last = e
        time.sleep(2.0 * (attempt + 1))
    raise RuntimeError(f"{url}: {last}")


def fetch(source: str, items: Iterable[Item] | None = None) -> dict[str, Any]:
    """Download every item of ``source`` not yet on disk and rewrite its manifest."""
    licence, make = SOURCES[source]
    out = RAW / source
    out.mkdir(parents=True, exist_ok=True)
    manifest_path = MANIFESTS / f"edge_{source}.json"
    old: dict[str, dict[str, Any]] = {}
    if manifest_path.exists():
        prev = json.loads(manifest_path.read_text(encoding="utf-8"))
        old = {f["name"]: f for f in prev.get("files", [])}
        missing_old = {m["name"]: m for m in prev.get("missing", [])}
    else:
        missing_old = {}
    files: list[dict[str, Any]] = []
    missing: list[dict[str, Any]] = []
    for item in items if items is not None else make():
        target = out / item.name
        stamp = old.get(item.name, {}).get("downloaded_utc")
        if not target.exists():
            if item.name in missing_old:
                missing.append(missing_old[item.name])
                continue
            try:
                tmp = target.with_suffix(target.suffix + ".part")
                tmp.write_bytes(_get(item.url))
                tmp.replace(target)
                stamp = datetime.now(UTC).isoformat(timespec="seconds")
            except urllib.error.HTTPError as e:
                body = e.read().decode("utf-8", "replace")
                reason = "S3 DEEP_ARCHIVE (InvalidObjectState)" if "DEEP_ARCHIVE" in body else ""
                missing.append(
                    {"name": item.name, "url": item.url, "error": f"HTTP {e.code} {reason}".strip()}
                )
                continue
            except RuntimeError as e:
                missing.append({"name": item.name, "url": item.url, "error": str(e)[:200]})
                continue
        files.append(
            {
                "name": item.name,
                "url": item.url,
                "downloaded_utc": stamp or datetime.now(UTC).isoformat(timespec="seconds"),
                "size": target.stat().st_size,
                "sha256": sha256_of(target),
            }
        )
    manifest = {
        "manifest_version": MANIFEST_VERSION,
        "source": source,
        "licence": licence,
        "created_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "files": files,
        "missing": missing,
    }
    MANIFESTS.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n"
    )
    return manifest


def main(argv: list[str]) -> int:
    for source in argv or list(SOURCES):
        m = fetch(source)
        print(f"{source}: {len(m['files'])} files, {len(m['missing'])} missing", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
