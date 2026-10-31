"""Turn raw downloads into the small CSV fixtures committed under data/fixtures.

Every builder takes text (or parsed JSON) and returns rows of strings, header
first, so it can be tested on a few lines without touching the network.
Published numbers are copied as strings; nothing is rounded or re-formatted.
Dates are normalised to ISO ``YYYY-MM-DD`` and rows are sorted ascending.

``write_fixture`` writes a CSV with LF line endings and a sibling
``<name>.provenance.json`` naming the source URL, download date, licence, raw
file hashes and the transformation applied.
"""

from __future__ import annotations

import csv
import io
import json
from collections.abc import Iterable, Mapping, Sequence
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

from ratesresearch.data import bundesbank, ofr
from ratesresearch.data.downloader import (
    DEFAULT_MANIFEST,
    SPECS,
    Download,
    latest_record,
    load_downloads,
)
from ratesresearch.provenance import sha256_of

Rows = list[list[str]]

MAX_FIXTURE_BYTES = 2_000_000
TREASURY_TENORS = (
    "1 Mo",
    "2 Mo",
    "3 Mo",
    "4 Mo",
    "6 Mo",
    "1 Yr",
    "2 Yr",
    "3 Yr",
    "5 Yr",
    "7 Yr",
    "10 Yr",
    "20 Yr",
    "30 Yr",
)
_MONTHS = {
    "jan": 1,
    "feb": 2,
    "mar": 3,
    "apr": 4,
    "may": 5,
    "jun": 6,
    "jul": 7,
    "aug": 8,
    "sep": 9,
    "oct": 10,
    "nov": 11,
    "dec": 12,
}


# Date helpers


def iso_from_us(text: str) -> str:
    """``MM/DD/YYYY`` to ``YYYY-MM-DD``; raises ValueError on anything else."""
    parts = text.strip().split("/")
    if len(parts) != 3:
        msg = f"not a MM/DD/YYYY date: {text!r}"
        raise ValueError(msg)
    month, day, year = (int(p) for p in parts)
    return date(year, month, day).isoformat()


def iso_from_boe(text: str) -> str:
    """``01 Dec 2023`` (or ``01 Dec 23``) to ``YYYY-MM-DD``."""
    parts = text.strip().split()
    if len(parts) != 3 or parts[1][:3].lower() not in _MONTHS:
        msg = f"not a 'DD Mon YYYY' date: {text!r}"
        raise ValueError(msg)
    day = int(parts[0])
    month = _MONTHS[parts[1][:3].lower()]
    year = int(parts[2])
    if year < 100:
        year += 2000
    return date(year, month, day).isoformat()


def iso_from_timestamp(text: str) -> str:
    """``2024-01-02T00:00:00`` (or a bare ISO date) to ``YYYY-MM-DD``."""
    head = text.strip()[:10]
    return date.fromisoformat(head).isoformat()


def in_range(iso_day: str, start: date, end: date) -> bool:
    """True when ``iso_day`` lies in the closed interval [start, end]."""
    day = date.fromisoformat(iso_day)
    return start <= day <= end


# Text helpers


def strip_bom(text: str) -> str:
    """Remove a UTF-8 byte order mark if present."""
    return text[1:] if text.startswith("﻿") else text


def read_csv_dicts(text: str) -> list[dict[str, str]]:
    """Parse CSV text with a header row into dicts; values are stripped strings."""
    reader = csv.DictReader(io.StringIO(strip_bom(text)))
    rows: list[dict[str, str]] = []
    for raw in reader:
        rows.append({(k or "").strip(): (v or "").strip() for k, v in raw.items()})
    return rows


def is_decimal_text(text: str) -> bool:
    """True for a plain decimal such as ``4.38``, ``-0.5``, ``107.19030968`` or ``2533``.

    Empty strings, ``.`` placeholders, exponents and thousands separators are
    rejected; the fixtures keep only what can be read back by ``float`` and
    re-printed without a change of meaning.
    """
    s = text.strip()
    if not s:
        return False
    if s[0] in "+-":
        s = s[1:]
    if not s or s.count(".") > 1:
        return False
    digits = s.replace(".", "", 1)
    return digits.isdigit()


def _clean_number(text: str, *, where: str) -> str:
    """Return ``text`` stripped, or '' for a missing value; raise on garbage."""
    s = text.strip()
    if s in ("", ".", "N/A", "NA", "null"):
        return ""
    if not is_decimal_text(s):
        msg = f"unexpected number text {text!r} in {where}"
        raise ValueError(msg)
    return s


# Builders: one per fixture


def sofr_rows(sofr_csv: str, sofrai_csv: str, start: date, end: date) -> Rows:
    """date,rate_percent,index from the NY Fed ``sofr`` and ``sofrai`` searches."""
    rates: dict[str, str] = {}
    for row in read_csv_dicts(sofr_csv):
        if row.get("Rate Type") != "SOFR":
            continue
        day = iso_from_us(row["Effective Date"])
        rates[day] = _clean_number(row["Rate (%)"], where="SOFR Rate (%)")
    index: dict[str, str] = {}
    for row in read_csv_dicts(sofrai_csv):
        if row.get("Rate Type") != "SOFRAI":
            continue
        day = iso_from_us(row["Effective Date"])
        index[day] = _clean_number(row["SOFR Index"], where="SOFR Index")
    days = sorted(d for d in rates.keys() | index.keys() if in_range(d, start, end))
    out: Rows = [["date", "rate_percent", "index"]]
    out.extend([d, rates.get(d, ""), index.get(d, "")] for d in days)
    return out


def repo_gc_rows(
    all_csv: str, start: date, end: date, types: Sequence[str] = ("TGCR", "BGCR")
) -> Rows:
    """date,type,rate_percent for the chosen rate types from the ``all`` search."""
    keep = set(types)
    body: list[list[str]] = []
    for row in read_csv_dicts(all_csv):
        kind = row.get("Rate Type", "")
        if kind not in keep:
            continue
        day = iso_from_us(row["Effective Date"])
        if not in_range(day, start, end):
            continue
        body.append([day, kind, _clean_number(row["Rate (%)"], where=f"{kind} Rate (%)")])
    body.sort(key=lambda r: (r[0], list(types).index(r[1])))
    return [["date", "type", "rate_percent"], *body]


def ecb_series(csvdata: str) -> dict[str, str]:
    """``TIME_PERIOD -> OBS_VALUE`` from an ECB Data Portal ``csvdata`` response."""
    out: dict[str, str] = {}
    for row in read_csv_dicts(csvdata):
        out[row["TIME_PERIOD"]] = _clean_number(row["OBS_VALUE"], where=row.get("KEY", "ECB"))
    return out


def estr_rows(rate_csv: str, index_csv: str, start: date, end: date) -> Rows:
    """date,rate_percent,index from the two ECB ``EST`` series."""
    rates = ecb_series(rate_csv)
    index = ecb_series(index_csv)
    days = sorted(d for d in rates.keys() | index.keys() if in_range(d, start, end))
    out: Rows = [["date", "rate_percent", "index"]]
    out.extend([d, rates.get(d, ""), index.get(d, "")] for d in days)
    return out


def sonia_rows(boe_csv: str, start: date, end: date) -> Rows:
    """date,rate_percent,index from the IADB CSV (``DATE,IUDSOIA,IUDZOS2``)."""
    body: list[list[str]] = []
    for row in read_csv_dicts(boe_csv):
        day = iso_from_boe(row["DATE"])
        if not in_range(day, start, end):
            continue
        body.append(
            [
                day,
                _clean_number(row.get("IUDSOIA", ""), where="IUDSOIA"),
                _clean_number(row.get("IUDZOS2", ""), where="IUDZOS2"),
            ]
        )
    body.sort(key=lambda r: r[0])
    return [["date", "rate_percent", "index"], *body]


def par_curve_rows(year_csvs: Iterable[str], tenors: Sequence[str] = TREASURY_TENORS) -> Rows:
    """``date`` plus every tenor column, union over the yearly files, ascending by date.

    Text is copied as published; a tenor the Treasury did not publish on a
    date (or in a year) is an empty cell.
    """
    by_day: dict[str, dict[str, str]] = {}
    for text in year_csvs:
        for row in read_csv_dicts(text):
            day = iso_from_us(row["Date"])
            cells = by_day.setdefault(day, {})
            for tenor in tenors:
                if tenor in row:
                    cells[tenor] = _clean_number(row[tenor], where=f"{tenor} on {day}")
    out: Rows = [["date", *tenors]]
    for day in sorted(by_day):
        cells = by_day[day]
        out.append([day, *(cells.get(t, "") for t in tenors)])
    return out


AUCTION_COLUMNS = (
    "cusip",
    "type",
    "term",
    "auction_date",
    "issue_date",
    "maturity_date",
    "interest_rate",
    "high_yield",
    "price_per_100",
    "high_discount_rate",
)


def auction_rows(records_by_type: Mapping[str, Sequence[Mapping[str, Any]]], year: int) -> Rows:
    """Auction fixture rows for notes, bonds and bills auctioned in ``year``.

    ``records_by_type`` maps the TreasuryDirect ``type`` ("Note", "Bond",
    "Bill") to the parsed JSON array. The auction day is
    ``competitiveAuctionCloseDate`` (the records carry no ``auctionDate``).
    The records carry ``highPrice`` with ``pricePerAmount`` equal to 100, so
    ``price_per_100`` is ``highPrice``. Coupon securities fill
    ``interest_rate`` and ``high_yield``; bills fill ``high_discount_rate``
    from ``highRate`` (the API has no ``highDiscountRate`` field).
    """
    body: list[list[str]] = []
    for kind, records in records_by_type.items():
        for rec in records:
            close = str(rec.get("competitiveAuctionCloseDate", "")).strip()
            if not close:
                continue
            auction_day = iso_from_timestamp(close)
            if date.fromisoformat(auction_day).year != year:
                continue
            is_bill = kind == "Bill"
            body.append(
                [
                    str(rec.get("cusip", "")),
                    kind,
                    str(rec.get("securityTerm", "")),
                    auction_day,
                    iso_from_timestamp(str(rec["issueDate"])),
                    iso_from_timestamp(str(rec["maturityDate"])),
                    ""
                    if is_bill
                    else _clean_number(str(rec.get("interestRate", "")), where="interestRate"),
                    ""
                    if is_bill
                    else _clean_number(str(rec.get("highYield", "")), where="highYield"),
                    _clean_number(str(rec.get("highPrice", "")), where="highPrice"),
                    _clean_number(str(rec.get("highRate", "")), where="highRate")
                    if is_bill
                    else "",
                ]
            )
    body.sort(key=lambda r: (r[3], r[1], r[2], r[0]))
    return [list(AUCTION_COLUMNS), *body]


def bundesbank_series(text: str) -> dict[str, str]:
    """``date -> value`` from a Bundesbank REST CSV; ``.`` placeholders become ''.

    The file starts with a BOM and nine metadata lines (title, comment,
    decimals, time format, category, unit, multiplier, last update); data
    lines look like ``2024-01-02,2.58123,`` or ``2024-01-06,.,No value available``.
    """
    out: dict[str, str] = {}
    for line in strip_bom(text).splitlines():
        parts = line.split(",")
        if len(parts) < 2:
            continue
        head = parts[0].strip()
        if len(head) != 10 or head[4] != "-" or head[7] != "-":
            continue
        try:
            day = date.fromisoformat(head).isoformat()
        except ValueError:
            continue
        out[day] = _clean_number(parts[1], where=f"Bundesbank value on {day}")
    return out


def svensson_rows(param_csvs: Mapping[str, str], year: int) -> Rows:
    """date,beta0,beta1,beta2,beta3,tau1,tau2 for ``year``; days with no value are dropped.

    ``param_csvs`` maps each name in ``bundesbank.PARAMETERS`` to its CSV text.
    """
    series = {name: bundesbank_series(param_csvs[name]) for name in bundesbank.PARAMETERS}
    days = sorted(set().union(*(s.keys() for s in series.values())))
    out: Rows = [["date", *bundesbank.PARAMETERS]]
    for day in days:
        if date.fromisoformat(day).year != year:
            continue
        values = [series[name].get(day, "") for name in bundesbank.PARAMETERS]
        if all(v == "" for v in values):
            continue
        out.append([day, *values])
    return out


def ofr_rows(series_json: Mapping[str, Sequence[Sequence[Any]]], year: int) -> Rows:
    """date,mnemonic,value from ``[date, value]`` pair arrays; ``null`` becomes ''."""
    body: list[list[str]] = []
    for mnemonic, pairs in series_json.items():
        for pair in pairs:
            day = iso_from_timestamp(str(pair[0]))
            if date.fromisoformat(day).year != year:
                continue
            value = pair[1]
            body.append([day, mnemonic, "" if value is None else str(value)])
    body.sort(key=lambda r: (r[0], r[1]))
    return [["date", "mnemonic", "value"], *body]


# Writing


def rows_to_csv(rows: Rows) -> str:
    """Serialise rows as CSV text with LF line endings."""
    buf = io.StringIO()
    writer = csv.writer(buf, lineterminator="\n")
    writer.writerows(rows)
    return buf.getvalue()


def provenance_for(
    fixture_path: Path,
    rows: Rows,
    source_key: str,
    raw_records: Sequence[Download],
    transformation: str,
) -> dict[str, Any]:
    """Build the provenance dict written next to a fixture."""
    spec = SPECS[source_key]
    body = rows[1:]
    date_column = next((i for i, c in enumerate(rows[0]) if c in ("date", "auction_date")), None)
    dates = sorted(r[date_column] for r in body) if body and date_column is not None else []
    return {
        "fixture": fixture_path.name,
        "created_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "sha256": sha256_of(fixture_path),
        "size": fixture_path.stat().st_size,
        "rows": len(body),
        "columns": rows[0],
        "date_range": [dates[0], dates[-1]] if dates else None,
        "source": {
            "key": spec.key,
            "name": spec.name,
            "licence": spec.licence,
            "terms_url": spec.terms_url,
        },
        "raw_files": [
            {
                "name": r.name,
                "url": r.url,
                "downloaded_utc": r.downloaded_utc,
                "sha256": r.sha256,
                "size": r.size,
            }
            for r in raw_records
        ],
        "transformation": transformation,
        "produced_by": "ratesresearch.data.fixtures",
    }


def write_fixture(
    fixtures_dir: Path,
    name: str,
    rows: Rows,
    *,
    source_key: str,
    raw_records: Sequence[Download],
    transformation: str,
) -> Path:
    """Write ``<name>.csv`` and ``<name>.provenance.json``; returns the CSV path."""
    fixtures_dir.mkdir(parents=True, exist_ok=True)
    csv_path = fixtures_dir / f"{name}.csv"
    text = rows_to_csv(rows)
    if len(text.encode("utf-8")) > MAX_FIXTURE_BYTES:
        msg = f"{csv_path.name} would be {len(text)} bytes, over the {MAX_FIXTURE_BYTES} limit"
        raise ValueError(msg)
    csv_path.write_text(text, encoding="utf-8", newline="\n")
    prov = provenance_for(csv_path, rows, source_key, raw_records, transformation)
    (fixtures_dir / f"{name}.provenance.json").write_text(
        json.dumps(prov, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    return csv_path


def _records_for(
    raw_dir: Path, names: Sequence[str], manifest: Sequence[Download]
) -> list[Download] | None:
    """Manifest records for the named raw files, or None if any is missing on disk."""
    records: list[Download] = []
    for name in names:
        path = raw_dir / name
        if not path.exists():
            return None
        record = latest_record(manifest, name)
        if record is None or record.sha256 != sha256_of(path):
            # The file on disk is not the one the manifest knows; record what is there.
            record = Download(
                name=name,
                url=next((u for n, u in _all_files() if n == name), ""),
                licence=_licence_for(name),
                downloaded_utc="unknown (file present without a manifest record)",
                sha256=sha256_of(path),
                size=path.stat().st_size,
                path=path.as_posix(),
            )
        records.append(record)
    return records


def _all_files() -> list[tuple[str, str]]:
    return [pair for spec in SPECS.values() for pair in spec.files]


def _licence_for(name: str) -> str:
    for spec in SPECS.values():
        if any(n == name for n, _ in spec.files):
            return spec.licence
    return ""


def _read(raw_dir: Path, name: str) -> str:
    return (raw_dir / name).read_text(encoding="utf-8")


def build_all(
    raw_dir: str | Path,
    fixtures_dir: str | Path,
    *,
    manifest_path: str | Path = DEFAULT_MANIFEST,
) -> list[Path]:
    """Build every fixture whose raw files are present; returns the CSV paths written.

    A fixture whose raw files are missing is skipped with a note on stdout,
    never written from partial input.
    """
    raw = Path(raw_dir)
    out = Path(fixtures_dir)
    manifest = load_downloads(manifest_path)
    written: list[Path] = []
    holdout_start, holdout_end = date(2023, 12, 1), date(2025, 1, 31)

    def skip(name: str, files: Sequence[str]) -> None:
        print(f"skip {name}: missing raw file(s) under {raw}: {', '.join(files)}")

    files = ["nyfed_sofr.csv", "nyfed_sofrai.csv"]
    if (recs := _records_for(raw, files, manifest)) is not None:
        rows = sofr_rows(_read(raw, files[0]), _read(raw, files[1]), holdout_start, holdout_end)
        written.append(
            write_fixture(
                out,
                "sofr_2024",
                rows,
                source_key="nyfed_sofr",
                raw_records=recs,
                transformation=(
                    "Joined the SOFR search (Rate (%)) and the SOFRAI search (SOFR Index) on "
                    "Effective Date, converted MM/DD/YYYY to ISO, kept 2023-12-01 to 2025-01-31, "
                    "sorted ascending; numbers copied as published."
                ),
            )
        )
    else:
        skip("sofr_2024", files)

    files = ["nyfed_secured_all.csv"]
    if (recs := _records_for(raw, files, manifest)) is not None:
        rows = repo_gc_rows(_read(raw, files[0]), holdout_start, holdout_end)
        written.append(
            write_fixture(
                out,
                "repo_gc_2024",
                rows,
                source_key="nyfed_gc",
                raw_records=recs,
                transformation=(
                    "Kept Rate Type in (TGCR, BGCR) with Effective Date and Rate (%), converted "
                    "dates to ISO, kept 2023-12-01 to 2025-01-31, sorted by date then type."
                ),
            )
        )
    else:
        skip("repo_gc_2024", files)

    files = ["ecb_estr_rate.csv", "ecb_estr_index.csv"]
    if (recs := _records_for(raw, files, manifest)) is not None:
        rows = estr_rows(_read(raw, files[0]), _read(raw, files[1]), holdout_start, holdout_end)
        written.append(
            write_fixture(
                out,
                "estr_2024",
                rows,
                source_key="ecb_estr",
                raw_records=recs,
                transformation=(
                    "Took TIME_PERIOD and OBS_VALUE from EST.B.EU000A2X2A25.WT (rate) and "
                    "EST.B.EU000A2QQF08.CI (compounded index), joined on date, kept 2023-12-01 "
                    "to 2025-01-31; values copied as published."
                ),
            )
        )
    else:
        skip("estr_2024", files)

    files = ["boe_sonia.csv"]
    if (recs := _records_for(raw, files, manifest)) is not None:
        rows = sonia_rows(_read(raw, files[0]), holdout_start, holdout_end)
        written.append(
            write_fixture(
                out,
                "sonia_2024",
                rows,
                source_key="boe_sonia",
                raw_records=recs,
                transformation=(
                    "Renamed DATE,IUDSOIA,IUDZOS2 to date,rate_percent,index, converted "
                    "'DD Mon YYYY' to ISO, kept 2023-12-01 to 2025-01-31; values copied as "
                    "published."
                ),
            )
        )
    else:
        skip("sonia_2024", files)

    files = [f"ust_par_curve_{year}.csv" for year in range(2015, 2026)]
    if (recs := _records_for(raw, files, manifest)) is not None:
        rows = par_curve_rows(_read(raw, f) for f in files)
        written.append(
            write_fixture(
                out,
                "ust_par_curve_2015_2025",
                rows,
                source_key="treasury_par_curve",
                raw_records=recs,
                transformation=(
                    "Concatenated the yearly CSVs, converted Date to ISO, took the union of "
                    "tenor columns 1 Mo to 30 Yr (empty where not published), sorted ascending; "
                    "rates copied as published."
                ),
            )
        )
    else:
        skip("ust_par_curve_2015_2025", files)

    files = ["treasurydirect_notes.json", "treasurydirect_bonds.json", "treasurydirect_bills.json"]
    if (recs := _records_for(raw, files, manifest)) is not None:
        parsed = {
            kind: json.loads(_read(raw, name))
            for kind, name in zip(("Note", "Bond", "Bill"), files, strict=True)
        }
        rows = auction_rows(parsed, 2024)
        written.append(
            write_fixture(
                out,
                "ust_auctions_2024",
                rows,
                source_key="treasurydirect_auctions",
                raw_records=recs,
                transformation=(
                    "From the search endpoint (dateFieldName=issueDate, 2023-12-01 to "
                    "2025-01-31) kept records whose competitiveAuctionCloseDate falls in 2024; "
                    "columns cusip, type, securityTerm, auction/issue/maturity dates (ISO), "
                    "interestRate and highYield for notes and bonds, highRate (discount rate) "
                    "for bills, highPrice (per 100) for all."
                ),
            )
        )
    else:
        skip("ust_auctions_2024", files)

    files = list(bundesbank.FILE_NAMES)
    if (recs := _records_for(raw, files, manifest)) is not None:
        texts = dict(zip(bundesbank.PARAMETERS, (_read(raw, f) for f in files), strict=True))
        rows = svensson_rows(texts, 2024)
        written.append(
            write_fixture(
                out,
                "bund_svensson_2024",
                rows,
                source_key="bundesbank_svensson",
                raw_records=recs,
                transformation=(
                    "Parsed the six BBSIS parameter CSVs (skipping metadata lines and '.' "
                    "placeholders), joined on date, kept 2024 days with at least one value; "
                    "values copied as published."
                ),
            )
        )
    else:
        skip("bund_svensson_2024", files)

    files = [ofr.file_name(m) for m in ofr.MNEMONICS]
    if (recs := _records_for(raw, files, manifest)) is not None:
        parsed_ofr = {
            m: json.loads(_read(raw, f)) for m, f in zip(ofr.MNEMONICS, files, strict=True)
        }
        rows = ofr_rows(parsed_ofr, 2024)
        written.append(
            write_fixture(
                out,
                "ofr_repo_2024",
                rows,
                source_key="ofr_repo",
                raw_records=recs,
                transformation=(
                    "Flattened the [date, value] arrays of both mnemonics into long form, kept "
                    "2024, null became an empty cell, sorted by date then mnemonic."
                ),
            )
        )
    else:
        skip("ofr_repo_2024", files)

    return written
