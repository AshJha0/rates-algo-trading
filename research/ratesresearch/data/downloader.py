"""Plain HTTPS downloads with a hash and a manifest record for each file.

No API keys and no third-party HTTP library: ``urllib.request`` is enough for
the public endpoints this project reads. Each fetch is tried twice. If the
default certificate store rejects a site (seen on Windows for the ECB and the
Bundesbank) and ``certifi`` is importable, the fetch is retried with the
certifi bundle; otherwise the error is raised.

Every successful download appends a ``Download`` record to the manifest at
``data/manifests/downloads.json`` (``manifest_version`` 1, the same version
field as ``ratesresearch.provenance.write_manifest``).
"""

from __future__ import annotations

import importlib
import json
import ssl
import urllib.error
import urllib.request
from collections.abc import Iterable
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from ratesresearch.provenance import MANIFEST_VERSION, sha256_of

DEFAULT_MANIFEST = Path("data/manifests/downloads.json")
USER_AGENT = "ratesresearch/0.0.1 (+https://github.com/AshJha0/rates-algo-trading)"
ATTEMPTS = 2
TIMEOUT_SECONDS = 120


class DownloadError(RuntimeError):
    """A download failed after the configured number of attempts."""


@dataclass(frozen=True)
class Download:
    """One downloaded file and the facts needed to cite it."""

    name: str
    url: str
    licence: str
    downloaded_utc: str
    sha256: str
    size: int
    path: str


@dataclass(frozen=True)
class SourceSpec:
    """A named group of files from one publisher under one set of terms.

    ``files`` holds pairs of (file name under the raw directory, URL).
    """

    key: str
    name: str
    licence: str
    terms_url: str
    files: tuple[tuple[str, str], ...]


def _nyfed_url(kind: str, start: str, end: str) -> str:
    return (
        f"https://markets.newyorkfed.org/api/rates/secured/{kind}/search.csv"
        f"?startDate={start}&endDate={end}"
    )


def _ecb_url(key: str) -> str:
    return (
        f"https://data-api.ecb.europa.eu/service/data/EST/{key}"
        "?format=csvdata&startPeriod=2023-12-01&endPeriod=2025-01-31"
    )


def _treasury_url(year: int) -> str:
    return (
        "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/"
        f"daily-treasury-rates.csv/{year}/all?type=daily_treasury_yield_curve"
        f"&field_tdr_date_value={year}&page&_format=csv"
    )


def _treasurydirect_url(security_type: str) -> str:
    # The documented dateFieldName=auctionDate variant returned HTTP 500 on
    # 2026-10-31; issueDate works, so the window is widened by a month on each
    # side and the auction date is filtered when the fixture is built.
    return (
        "https://www.treasurydirect.gov/TA_WS/securities/search?format=json"
        f"&type={security_type}&dateFieldName=issueDate"
        "&startDate=12/01/2023&endDate=01/31/2025"
    )


def _bundesbank_url(parameter: str) -> str:
    key = f"D.I.ZST.{parameter}.EUR.S1311.B.A604._Z.R.A.A._Z._Z.A"
    return f"https://api.statistiken.bundesbank.de/rest/download/BBSIS/{key}?format=csv&lang=en"


def _ofr_url(mnemonic: str) -> str:
    return f"https://data.financialresearch.gov/v1/series/timeseries?mnemonic={mnemonic}"


NYFED_LICENCE = (
    "NY Fed Terms of Use (last updated 6/9/2023): non-exclusive licence to use, copy, "
    "distribute and make derivative works of website Content, keeping copyright notices and "
    "citing the source as '(c) [year] Federal Reserve Bank of New York'. Read 2026-10-31."
)
ECB_LICENCE = (
    "ECB Disclaimer & copyright: free use of information obtained from the website provided it "
    "appears accurately, the ECB is cited as the source and any modification by the user is "
    "stated explicitly. Read 2026-10-31."
)
BOE_LICENCE = (
    "Bank of England Database terms (bankofengland.co.uk/legal): reproduction of Database data "
    "is subject to the UK Open Government Licence v3.0. Required attribution: 'SONIA and/or "
    "SONIA Compounded Index data licensed under the Open Government Licence v3.0 and copyright "
    "the Governor and Company of the Bank of England.' Read 2026-10-31."
)
TREASURY_LICENCE = (
    "US Department of the Treasury: work of the US federal government, public domain "
    "(17 U.S.C. 105); the download page carries no licence statement. Read 2026-10-31."
)
TREASURYDIRECT_LICENCE = (
    "TreasuryDirect (Bureau of the Fiscal Service) Terms & Conditions: no copyright "
    "restriction stated; work of the US federal government, public domain (17 U.S.C. 105). "
    "Read 2026-10-31."
)
BUNDESBANK_LICENCE = (
    "Bundesbank website terms of use, section 4.1, and the ESCB terms for the reuse of "
    "statistics: free reuse of publicly released statistics on condition that the source is "
    "quoted ('Source: Deutsche Bundesbank') and the statistics are not modified. Read "
    "2026-10-31."
)
OFR_LICENCE = (
    "Office of Financial Research: work of the US federal government, public domain; the "
    "metadata 'rights' field is empty and the dataset page gives the suggested citation "
    "'Office of Financial Research, OFR Short-term Funding Monitor, refreshed daily, "
    "https://www.financialresearch.gov/short-term-funding-monitor/ (accessed 2026-10-31)'. "
    "Read 2026-10-31."
)

SPECS: dict[str, SourceSpec] = {
    spec.key: spec
    for spec in (
        SourceSpec(
            key="nyfed_sofr",
            name="NY Fed SOFR and SOFR Index",
            licence=NYFED_LICENCE,
            terms_url="https://www.newyorkfed.org/privacy/termsofuse",
            files=(
                ("nyfed_sofr.csv", _nyfed_url("sofr", "2023-10-01", "2025-01-31")),
                ("nyfed_sofrai.csv", _nyfed_url("sofrai", "2023-10-01", "2025-01-31")),
            ),
        ),
        SourceSpec(
            key="nyfed_gc",
            name="NY Fed TGCR and BGCR",
            licence=NYFED_LICENCE,
            terms_url="https://www.newyorkfed.org/privacy/termsofuse",
            files=(("nyfed_secured_all.csv", _nyfed_url("all", "2023-12-01", "2025-01-31")),),
        ),
        SourceSpec(
            key="ecb_estr",
            name="ECB euro short-term rate and compounded index",
            licence=ECB_LICENCE,
            terms_url="https://www.ecb.europa.eu/services/disclaimer/html/index.en.html",
            files=(
                ("ecb_estr_rate.csv", _ecb_url("B.EU000A2X2A25.WT")),
                # EU000A2QQF16 is the 1-week compounded average rate; the
                # compounded index (1 Oct 2019 = 100) is EU000A2QQF08 / CI.
                ("ecb_estr_index.csv", _ecb_url("B.EU000A2QQF08.CI")),
            ),
        ),
        SourceSpec(
            key="boe_sonia",
            name="Bank of England SONIA and SONIA Compounded Index",
            licence=BOE_LICENCE,
            terms_url="https://www.bankofengland.co.uk/legal",
            files=(
                (
                    "boe_sonia.csv",
                    # fromshowcolumns.asp returns an HTML table even with
                    # csv.x=yes; the _iadb- prefixed endpoint returns the CSV.
                    "https://www.bankofengland.co.uk/boeapps/database/_iadb-fromshowcolumns.asp"
                    "?csv.x=yes&Datefrom=01/Dec/2023&Dateto=31/Jan/2025"
                    "&SeriesCodes=IUDSOIA,IUDZOS2&CSVF=TN&UsingCodes=Y&VPD=Y&VFD=N",
                ),
            ),
        ),
        SourceSpec(
            key="treasury_par_curve",
            name="US Treasury daily par yield curve",
            licence=TREASURY_LICENCE,
            terms_url=(
                "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/"
                "TextView?type=daily_treasury_yield_curve&field_tdr_date_value=2024"
            ),
            files=tuple(
                (f"ust_par_curve_{year}.csv", _treasury_url(year)) for year in range(2015, 2026)
            ),
        ),
        SourceSpec(
            key="treasurydirect_auctions",
            name="TreasuryDirect auction results",
            licence=TREASURYDIRECT_LICENCE,
            terms_url="https://www.treasurydirect.gov/legal-information/terms/",
            files=tuple(
                (f"treasurydirect_{kind.lower()}s.json", _treasurydirect_url(kind))
                for kind in ("Note", "Bond", "Bill")
            ),
        ),
        SourceSpec(
            key="bundesbank_svensson",
            name="Bundesbank Svensson parameters for listed Federal securities",
            licence=BUNDESBANK_LICENCE,
            terms_url="https://www.bundesbank.de/en/homepage/user-information",
            files=tuple(
                (f"bundesbank_svensson_{parameter.lower()}.csv", _bundesbank_url(parameter))
                for parameter in ("B0", "B1", "B2", "B3", "T1", "T2")
            ),
        ),
        SourceSpec(
            key="ofr_repo",
            name="OFR short-term funding monitor repo series",
            licence=OFR_LICENCE,
            terms_url="https://www.financialresearch.gov/short-term-funding-monitor/datasets/repo/",
            files=tuple(
                (f"ofr_{mnemonic.lower().replace('-', '_')}.json", _ofr_url(mnemonic))
                for mnemonic in ("REPO-TRI_AR_OO-P", "REPO-DVP_AR_OO-P")
            ),
        ),
    )
}


def _certifi_context() -> ssl.SSLContext | None:
    try:
        certifi: Any = importlib.import_module("certifi")
    except ImportError:
        return None
    cafile: str = certifi.where()
    return ssl.create_default_context(cafile=cafile)


def fetch_bytes(url: str, *, attempts: int = ATTEMPTS, timeout: float = TIMEOUT_SECONDS) -> bytes:
    """Return the body at ``url``; raise ``DownloadError`` after ``attempts`` failures."""
    errors: list[str] = []
    contexts: list[ssl.SSLContext | None] = [None]
    for _ in range(attempts):
        for context in list(contexts):
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            try:
                with urllib.request.urlopen(request, timeout=timeout, context=context) as reply:
                    body: bytes = reply.read()
                return body
            except urllib.error.URLError as exc:
                errors.append(f"{type(exc).__name__}: {exc}")
                if isinstance(exc.reason, ssl.SSLCertVerificationError) and len(contexts) == 1:
                    fallback = _certifi_context()
                    if fallback is not None:
                        contexts.append(fallback)
            except (TimeoutError, OSError) as exc:
                errors.append(f"{type(exc).__name__}: {exc}")
    msg = f"download failed after {attempts} attempt(s): {url}\n  " + "\n  ".join(errors)
    raise DownloadError(msg)


def _utc_now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def download(
    source_key: str,
    out_dir: str | Path,
    *,
    manifest_path: str | Path = DEFAULT_MANIFEST,
) -> list[Download]:
    """Download every file of ``SPECS[source_key]`` into ``out_dir``.

    Each file is written, hashed and recorded in the manifest before the next
    one starts, so a failure part way leaves the files already fetched on disk
    and in the manifest. Returns the records written.
    """
    spec = SPECS[source_key]
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    records: list[Download] = []
    for file_name, url in spec.files:
        body = fetch_bytes(url)
        path = out / file_name
        path.write_bytes(body)
        record = Download(
            name=file_name,
            url=url,
            licence=spec.licence,
            downloaded_utc=_utc_now(),
            sha256=sha256_of(path),
            size=len(body),
            path=path.as_posix(),
        )
        append_manifest([record], manifest_path)
        records.append(record)
    return records


def load_downloads(manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Download]:
    """Read the download records from the manifest, or an empty list if absent."""
    path = Path(manifest_path)
    if not path.exists():
        return []
    data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    if data.get("manifest_version") != MANIFEST_VERSION:
        msg = f"unsupported manifest_version: {data.get('manifest_version')!r}"
        raise ValueError(msg)
    return [Download(**entry) for entry in data.get("downloads", [])]


def append_manifest(
    records: Iterable[Download], manifest_path: str | Path = DEFAULT_MANIFEST
) -> dict[str, Any]:
    """Append records to the manifest, creating it if needed; returns the manifest."""
    path = Path(manifest_path)
    existing = load_downloads(path)
    created = _utc_now()
    if path.exists():
        created = str(json.loads(path.read_text(encoding="utf-8")).get("created_utc", created))
    manifest: dict[str, Any] = {
        "manifest_version": MANIFEST_VERSION,
        "created_utc": created,
        "updated_utc": _utc_now(),
        "downloads": [asdict(r) for r in [*existing, *records]],
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def latest_record(records: Iterable[Download], name: str) -> Download | None:
    """Return the most recent record whose file name is ``name``, if any."""
    found = None
    for record in records:
        if record.name == name:
            found = record
    return found
