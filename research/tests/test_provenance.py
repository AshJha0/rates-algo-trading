"""Tests for provenance records and the file manifest helpers."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from ratesresearch.provenance import (
    SOURCES,
    DataSource,
    load_manifest,
    sha256_of,
    sources_as_records,
    write_manifest,
)


def test_sources_are_frozen_and_complete() -> None:
    assert len(SOURCES) == 13
    names = {s.name for s in SOURCES}
    assert len(names) == len(SOURCES), "duplicate source names"
    for source in SOURCES:
        assert isinstance(source, DataSource)
        assert source.url.startswith("https://")
        for field in (source.provides, source.terms, source.used_for, source.limits):
            assert field.strip()


def test_datasource_is_immutable() -> None:
    source = SOURCES[0]
    with pytest.raises(AttributeError):
        source.name = "changed"  # type: ignore[misc]


def test_sources_as_records_round_trip() -> None:
    records = sources_as_records()
    assert [r["name"] for r in records] == [s.name for s in SOURCES]


# deadline off: the first example pays for tmp_path creation and failed at 383 ms on Windows
@settings(deadline=None)
@given(st.binary(max_size=4096))
def test_sha256_of_matches_hashlib(tmp_path_factory: pytest.TempPathFactory, data: bytes) -> None:
    path = tmp_path_factory.mktemp("sha") / "blob.bin"
    path.write_bytes(data)
    assert sha256_of(path) == hashlib.sha256(data).hexdigest()


def test_write_and_load_manifest(tmp_path: Path) -> None:
    a = tmp_path / "a.csv"
    b = tmp_path / "b.csv"
    a.write_text("x,y\n1,2\n", encoding="utf-8")
    b.write_bytes(b"\x00" * 10)
    out = tmp_path / "manifest.json"

    written = write_manifest([a, b], out)
    loaded = load_manifest(out)

    assert loaded == written
    assert loaded["manifest_version"] == 1
    assert loaded["created_utc"].endswith("+00:00")
    files = {entry["name"]: entry for entry in loaded["files"]}
    assert files["a.csv"]["size"] == a.stat().st_size
    assert files["a.csv"]["sha256"] == sha256_of(a)
    assert files["b.csv"]["sha256"] == hashlib.sha256(b"\x00" * 10).hexdigest()


def test_load_manifest_rejects_unknown_version(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text(json.dumps({"manifest_version": 99, "files": []}), encoding="utf-8")
    with pytest.raises(ValueError, match="manifest_version"):
        load_manifest(bad)
