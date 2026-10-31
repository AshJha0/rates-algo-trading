"""Tests for the data package: parsing, fixture builders and the manifest.

No network: every test works on small in-memory samples shaped like the
real responses seen on 2026-10-31.
"""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pytest
from hypothesis import given
from hypothesis import strategies as st

from ratesresearch.data import dmo
from ratesresearch.data.downloader import (
    SPECS,
    Download,
    append_manifest,
    latest_record,
    load_downloads,
)
from ratesresearch.data.fixtures import (
    MAX_FIXTURE_BYTES,
    auction_rows,
    bundesbank_series,
    estr_rows,
    is_decimal_text,
    iso_from_boe,
    iso_from_timestamp,
    iso_from_us,
    ofr_rows,
    par_curve_rows,
    repo_gc_rows,
    rows_to_csv,
    sofr_rows,
    sonia_rows,
    svensson_rows,
    write_fixture,
)

START, END = date(2023, 12, 1), date(2025, 1, 31)
NYFED_HEADER = (
    "Effective Date,Rate Type,Rate (%),1st Percentile (%),25th Percentile (%),"
    "75th Percentile (%),99th Percentile (%),Volume ($Billions),Target Rate From (%),"
    "Target Rate To (%),Intra Day - Low (%),Intra Day - High (%),Standard Deviation (%),"
    "30-Day Average SOFR,90-Day Average SOFR,180-Day Average SOFR,SOFR Index,"
    "Revision Indicator (Y/N),Footnote ID\n"
)


def _record(name: str = "a.csv", sha: str = "0" * 64) -> Download:
    return Download(
        name=name,
        url="https://example.invalid/a.csv",
        licence="test licence",
        downloaded_utc="2026-10-31T00:00:00+00:00",
        sha256=sha,
        size=1,
        path=f"data/raw/{name}",
    )


# Dates


def test_iso_date_helpers() -> None:
    assert iso_from_us("01/31/2025") == "2025-01-31"
    assert iso_from_boe("01 Dec 2023") == "2023-12-01"
    assert iso_from_boe("29 Feb 24") == "2024-02-29"
    assert iso_from_timestamp("2024-06-04T00:00:00") == "2024-06-04"
    with pytest.raises(ValueError):
        iso_from_us("2025-01-31")
    with pytest.raises(ValueError):
        iso_from_boe("01 Foo 2023")


@given(st.dates(min_value=date(1990, 1, 1), max_value=date(2099, 12, 31)))
def test_us_dates_round_trip(day: date) -> None:
    assert iso_from_us(day.strftime("%m/%d/%Y")) == day.isoformat()
    assert iso_from_boe(day.strftime("%d %b %Y")) == day.isoformat()


# Number text


@given(
    st.decimals(allow_nan=False, allow_infinity=False, places=8, min_value=-1000, max_value=1000)
)
def test_decimal_text_accepts_plain_decimals(value: object) -> None:
    text = f"{value:f}"
    assert is_decimal_text(text), text


@given(st.text(max_size=12))
def test_decimal_text_agrees_with_a_plain_grammar(text: str) -> None:
    s = text.strip()
    body = s[1:] if s[:1] in ("+", "-") else s
    expected = (
        bool(body)
        and body.count(".") <= 1
        and body.replace(".", "", 1) != ""
        and all(c in "0123456789" for c in body.replace(".", "", 1))
    )
    assert is_decimal_text(text) == expected


@pytest.mark.parametrize("bad", ["", ".", "1e5", "1,000", "abc", "--1", "1.2.3", "+"])
def test_decimal_text_rejects(bad: str) -> None:
    assert not is_decimal_text(bad)


# Builders


def test_sofr_rows_join_and_keep_decimals() -> None:
    sofr = NYFED_HEADER + (
        "01/31/2025,SOFR,4.38,4.30,4.34,4.44,4.47,2533,,,,,,,,,,,\n"
        "11/30/2023,SOFR,5.33,4.30,4.34,4.44,4.47,2533,,,,,,,,,,,\n"
        "12/01/2023,SOFR,5.39,4.30,4.34,4.44,4.47,2533,,,,,,,,,,,\n"
    )
    sofrai = NYFED_HEADER + (
        "01/31/2025,SOFRAI,,,,,,,,,,,,4.32426,4.51270,4.84825,1.17918748,,\n"
        "12/01/2023,SOFRAI,,,,,,,,,,,,4.3,4.5,4.8,1.10999798,,\n"
    )
    rows = sofr_rows(sofr, sofrai, START, END)
    assert rows == [
        ["date", "rate_percent", "index"],
        ["2023-12-01", "5.39", "1.10999798"],
        ["2025-01-31", "4.38", "1.17918748"],
    ]


def test_repo_gc_rows_filters_types_and_orders() -> None:
    text = NYFED_HEADER + (
        "01/31/2025,TGCR,4.34,4.25,4.34,4.35,4.37,892,,,,,,,,,,,\n"
        "01/31/2025,BGCR,4.34,4.25,4.34,4.35,4.43,918,,,,,,,,,,,\n"
        "01/31/2025,SOFR,4.38,4.30,4.34,4.44,4.47,2533,,,,,,,,,,,\n"
        "12/01/2023,BGCR,5.31,4.25,4.34,4.35,4.43,918,,,,,,,,,,,\n"
    )
    rows = repo_gc_rows(text, START, END)
    assert rows[0] == ["date", "type", "rate_percent"]
    assert rows[1:] == [
        ["2023-12-01", "BGCR", "5.31"],
        ["2025-01-31", "TGCR", "4.34"],
        ["2025-01-31", "BGCR", "4.34"],
    ]


def test_estr_rows_from_csvdata() -> None:
    head = "KEY,FREQ,TIME_PERIOD,OBS_VALUE,TITLE\n"
    rate = head + 'EST.B.EU000A2X2A25.WT,B,2023-12-01,3.904,"Euro short-term rate"\n'
    index = head + 'EST.B.EU000A2QQF08.CI,B,2023-12-01,101.65247207,"index, 1 Oct 2019 = 100"\n'
    assert estr_rows(rate, index, START, END) == [
        ["date", "rate_percent", "index"],
        ["2023-12-01", "3.904", "101.65247207"],
    ]


def test_sonia_rows_from_iadb_csv() -> None:
    text = (
        "DATE,IUDSOIA,IUDZOS2\n04 Dec 2023,5.1878,107.23602209\n01 Dec 2023,5.1886,107.19030968\n"
    )
    assert sonia_rows(text, START, END) == [
        ["date", "rate_percent", "index"],
        ["2023-12-01", "5.1886", "107.19030968"],
        ["2023-12-04", "5.1878", "107.23602209"],
    ]


def test_par_curve_rows_union_of_tenors_keeps_empty_cells() -> None:
    y2015 = 'Date,"1 Mo","3 Mo","10 Yr"\n01/02/2015,0.02,0.02,2.12\n'
    y2024 = 'Date,"1 Mo","2 Mo","3 Mo","10 Yr"\n12/31/2024,4.40,4.39,4.37,\n'
    rows = par_curve_rows([y2024, y2015], tenors=("1 Mo", "2 Mo", "3 Mo", "10 Yr"))
    assert rows == [
        ["date", "1 Mo", "2 Mo", "3 Mo", "10 Yr"],
        ["2015-01-02", "0.02", "", "0.02", "2.12"],
        ["2024-12-31", "4.40", "4.39", "4.37", ""],
    ]


def test_auction_rows_notes_and_bills() -> None:
    note = {
        "cusip": "91282CKW0",
        "securityTerm": "7-Year",
        "competitiveAuctionCloseDate": "2024-06-27T00:00:00",
        "issueDate": "2024-07-01T00:00:00",
        "maturityDate": "2031-06-30T00:00:00",
        "interestRate": "4.250",
        "highYield": "4.276",
        "highPrice": "99.843947",
        "highRate": "",
    }
    bill = {
        "cusip": "912797KR7",
        "securityTerm": "4-Week",
        "competitiveAuctionCloseDate": "2024-06-04T00:00:00",
        "issueDate": "2024-06-06T00:00:00",
        "maturityDate": "2024-07-05T00:00:00",
        "interestRate": "",
        "highYield": "",
        "highPrice": "99.590111",
        "highRate": "5.270",
    }
    late = dict(note, competitiveAuctionCloseDate="2025-01-02T00:00:00")
    rows = auction_rows({"Note": [note, late], "Bill": [bill]}, 2024)
    assert rows[1] == [
        "912797KR7",
        "Bill",
        "4-Week",
        "2024-06-04",
        "2024-06-06",
        "2024-07-05",
        "",
        "",
        "99.590111",
        "5.270",
    ]
    assert rows[2][6:] == ["4.250", "4.276", "99.843947", ""]
    assert len(rows) == 3


BBK_SAMPLE = (
    '﻿"",BBSIS.D.I.ZST.B0.EUR.S1311.B.A604._Z.R.A.A._Z._Z.A,FLAGS\n'
    '"",Term structure ... Parameter Beta0 /daily data,\n'
    "Comment (in english),,\n"
    "Decimals,5,\n"
    "Time format code,P1D,\n"
    "category,ZRZS,\n"
    "unit,,\n"
    "unit multiplier,One,\n"
    "last update,2026-10-30 12:24:20,\n"
    "2023-12-29,0.30000,\n"
    "2024-01-01,.,No value available\n"
    "2024-01-02,0.32436,\n"
)


def test_bundesbank_series_skips_metadata_and_placeholders() -> None:
    assert bundesbank_series(BBK_SAMPLE) == {
        "2023-12-29": "0.30000",
        "2024-01-01": "",
        "2024-01-02": "0.32436",
    }


def test_svensson_rows_keeps_year_and_drops_empty_days() -> None:
    texts = dict.fromkeys(("beta0", "beta1", "beta2", "beta3", "tau1", "tau2"), BBK_SAMPLE)
    rows = svensson_rows(texts, 2024)
    assert rows == [
        ["date", "beta0", "beta1", "beta2", "beta3", "tau1", "tau2"],
        ["2024-01-02", *(["0.32436"] * 6)],
    ]


def test_ofr_rows_long_form_with_nulls() -> None:
    data = {
        "REPO-TRI_AR_OO-P": [["2023-12-29", 5.3], ["2024-01-02", 5.32]],
        "REPO-DVP_AR_OO-P": [["2024-01-02", None]],
    }
    assert ofr_rows(data, 2024) == [
        ["date", "mnemonic", "value"],
        ["2024-01-02", "REPO-DVP_AR_OO-P", ""],
        ["2024-01-02", "REPO-TRI_AR_OO-P", "5.32"],
    ]


def test_builders_reject_garbage_numbers() -> None:
    with pytest.raises(ValueError, match="unexpected number text"):
        sonia_rows("DATE,IUDSOIA,IUDZOS2\n01 Dec 2023,5.18x,1\n", START, END)


# Writing and manifests


def test_rows_to_csv_uses_lf_and_quotes_commas() -> None:
    text = rows_to_csv([["a", "b"], ["1", "x,y"]])
    assert text == 'a,b\n1,"x,y"\n'
    assert "\r" not in text


def test_write_fixture_writes_csv_and_provenance(tmp_path: Path) -> None:
    rows = [["date", "rate_percent", "index"], ["2024-01-02", "5.32", "1.1"]]
    path = write_fixture(
        tmp_path,
        "tiny",
        rows,
        source_key="nyfed_sofr",
        raw_records=[_record()],
        transformation="test",
    )
    assert path.read_bytes() == b"date,rate_percent,index\n2024-01-02,5.32,1.1\n"
    prov = json.loads((tmp_path / "tiny.provenance.json").read_text(encoding="utf-8"))
    assert prov["date_range"] == ["2024-01-02", "2024-01-02"]
    assert prov["source"]["licence"] == SPECS["nyfed_sofr"].licence
    assert prov["raw_files"][0]["sha256"] == "0" * 64
    assert prov["transformation"] == "test"


def test_write_fixture_refuses_oversized_output(tmp_path: Path) -> None:
    big = [["date", "v"], *[["2024-01-02", "x" * 1000] for _ in range(MAX_FIXTURE_BYTES // 1000)]]
    with pytest.raises(ValueError, match="over the"):
        write_fixture(
            tmp_path, "big", big, source_key="ofr_repo", raw_records=[], transformation="t"
        )


def test_manifest_append_and_load(tmp_path: Path) -> None:
    manifest = tmp_path / "m" / "downloads.json"
    assert load_downloads(manifest) == []
    append_manifest([_record("a.csv", "1" * 64)], manifest)
    append_manifest([_record("a.csv", "2" * 64), _record("b.csv")], manifest)
    records = load_downloads(manifest)
    assert [r.name for r in records] == ["a.csv", "a.csv", "b.csv"]
    data = json.loads(manifest.read_text(encoding="utf-8"))
    assert data["manifest_version"] == 1
    found = latest_record(records, "a.csv")
    assert found is not None and found.sha256 == "2" * 64
    assert latest_record(records, "missing.csv") is None


def test_manifest_rejects_unknown_version(tmp_path: Path) -> None:
    bad = tmp_path / "downloads.json"
    bad.write_text(json.dumps({"manifest_version": 9, "downloads": []}), encoding="utf-8")
    with pytest.raises(ValueError, match="manifest_version"):
        load_downloads(bad)


def test_specs_are_https_and_name_a_licence() -> None:
    for spec in SPECS.values():
        assert spec.licence.strip()
        assert spec.terms_url.startswith("https://")
        for name, url in spec.files:
            assert url.startswith("https://"), url
            assert "/" not in name


def test_dmo_block_page_detection() -> None:
    assert dmo.looks_blocked(b"<head><title>ShieldSquare Block</title>")
    assert not dmo.looks_blocked(b"ISIN,Gilt Name,Clean Price\n")
