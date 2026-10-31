"""Provenance records for the public data sources the project may use.

Nothing is downloaded in Phase 0. This module records what each source is,
where it lives and what must be checked before use, and gives the two
helpers (``sha256_of``, ``write_manifest``) that later phases use to pin
every file they read. See data/SOURCES.md for the human-readable table.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

MANIFEST_VERSION = 1


@dataclass(frozen=True)
class DataSource:
    """One public data source and the facts a reviewer needs about it."""

    name: str
    url: str
    provides: str
    terms: str
    used_for: str
    limits: str


SOURCES: tuple[DataSource, ...] = (
    DataSource(
        name="DTCC SDR public dissemination (rates)",
        url="https://pddata.dtcc.com/ppd/",
        provides="Real-time public dissemination of US swap transactions (price, notional, tenor).",
        terms="DTCC public dissemination terms; verify before redistribution.",
        used_for="Fitting trade-arrival and size distributions for the RFQ simulator (P2, P5).",
        limits="Capped notionals, delayed block trades, no quotes, no counterparty identity.",
    ),
    DataSource(
        name="FINRA TRACE Treasury aggregate data",
        url="https://www.finra.org/finra-data/fixed-income/treasury-aggregate-statistics",
        provides="Aggregate daily US Treasury trading volumes by product and venue type.",
        terms="FINRA data terms; aggregate only.",
        used_for="Volume profiles for the Treasury venue simulator (P2).",
        limits="Aggregates only; no trade-level prices.",
    ),
    DataSource(
        name="US Treasury par yield curves and auction results",
        url="https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics",
        provides="Daily par yield curve rates; auction announcements and results.",
        terms="US government work, public domain.",
        used_for="Curve fixtures and auction-cycle calendars (P1).",
        limits="Par curve is a fitted summary, not tradable prices.",
    ),
    DataSource(
        name="CME current-day settlements",
        url="https://www.cmegroup.com/markets/interest-rates.html",
        provides="Current-day settlement prices for Treasury and SOFR futures.",
        terms="CME website terms; current day only, no historical redistribution.",
        used_for="Futures fixtures for CTD and basis checks (P1).",
        limits="Current day only; historical data is licensed.",
    ),
    DataSource(
        name="NY Fed SOFR",
        url="https://www.newyorkfed.org/markets/reference-rates/sofr",
        provides="Daily SOFR fixing, percentiles and volumes.",
        terms="NY Fed terms of use.",
        used_for="Overnight index for USD curves (P1).",
        limits="Published T+1 morning; revisions possible.",
    ),
    DataSource(
        name="ECB EUR short-term rate (EUR STR)",
        url="https://www.ecb.europa.eu/stats/financial_markets_and_interest_rates/euro_short-term_rate/html/index.en.html",
        provides="Daily EUR STR fixing and volumes.",
        terms="ECB copyright, reuse permitted with attribution.",
        used_for="Overnight index for EUR curves (P1).",
        limits="Published T+1 at 08:00 CET.",
    ),
    DataSource(
        name="Bank of England SONIA",
        url="https://www.bankofengland.co.uk/markets/sonia-benchmark",
        provides="Daily SONIA fixing and compounded index.",
        terms="BoE terms; licence to be verified for bulk download.",
        used_for="Overnight index for GBP curves (P1).",
        limits="Published T+1 at 09:00 London.",
    ),
    DataSource(
        name="Deutsche Bundesbank statistics",
        url="https://www.bundesbank.de/en/statistics",
        provides="Bund yields, term structure parameters, money market statistics.",
        terms="Bundesbank terms of use.",
        used_for="EUR government curve fixtures (P1).",
        limits="Fitted parameters, not trade prices.",
    ),
    DataSource(
        name="Finanzagentur (German Finance Agency)",
        url="https://www.deutsche-finanzagentur.de/en/",
        provides="Bund auction calendar and results, outstanding issues.",
        terms="Website terms.",
        used_for="Bund securities master and auction calendar (P1).",
        limits="Issuance data only.",
    ),
    DataSource(
        name="UK Debt Management Office",
        url="https://www.dmo.gov.uk/",
        provides="Gilt issuance, auction results, gilts in issue, ex-dividend dates.",
        terms="Open Government Licence where stated; verify per dataset.",
        used_for="Gilt securities master and calendar (P1).",
        limits="Prices are reference prices, not trades.",
    ),
    DataSource(
        name="CFTC Commitments of Traders",
        url="https://www.cftc.gov/MarketReports/CommitmentsofTraders/index.htm",
        provides="Weekly positioning by trader category in rates futures.",
        terms="US government work, public domain.",
        used_for="Context for positioning studies (P5).",
        limits="Weekly, aggregated, lagged.",
    ),
    DataSource(
        name="NY Fed TGCR and BGCR",
        url="https://www.newyorkfed.org/markets/reference-rates/tgcr",
        provides="Tri-party and broad general collateral repo rates.",
        terms="NY Fed terms of use.",
        used_for="Repo and carry inputs (P1).",
        limits="General collateral only; no special rates.",
    ),
    DataSource(
        name="OFR repo and short-term funding monitors",
        url="https://www.financialresearch.gov/short-term-funding-monitor/",
        provides="Repo volumes and rates across segments.",
        terms="US government work, public domain.",
        used_for="Repo market context (P1, P5).",
        limits="Aggregates; segment definitions change over time.",
    ),
)


def sha256_of(path: str | Path) -> str:
    """Return the hex SHA-256 of a file, read in 1 MiB chunks."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_manifest(paths: Iterable[str | Path], out_path: str | Path) -> dict[str, Any]:
    """Write a JSON manifest (name, size, sha256 per file, UTC timestamp).

    Returns the manifest as a dict so callers can test without re-reading.
    """
    entries = []
    for raw in paths:
        p = Path(raw)
        entries.append({"name": p.name, "size": p.stat().st_size, "sha256": sha256_of(p)})
    manifest: dict[str, Any] = {
        "manifest_version": MANIFEST_VERSION,
        "created_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "files": entries,
    }
    Path(out_path).write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
    return manifest


def load_manifest(path: str | Path) -> dict[str, Any]:
    """Read a manifest written by ``write_manifest`` and check its version."""
    data: dict[str, Any] = json.loads(Path(path).read_text(encoding="utf-8"))
    if data.get("manifest_version") != MANIFEST_VERSION:
        msg = f"unsupported manifest_version: {data.get('manifest_version')!r}"
        raise ValueError(msg)
    return data


def sources_as_records() -> list[dict[str, str]]:
    """Return SOURCES as plain dicts, convenient for a DataFrame or a table."""
    return [asdict(s) for s in SOURCES]
