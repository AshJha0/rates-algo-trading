"""Tests for the FNV-1a 64 chain hash (API_SPEC.md Section 1)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from hypothesis import given
from hypothesis import strategies as st

from ratesresearch.fnv import FNV_OFFSET_BASIS, FNV_PRIME, fnv1a64, to_java_long

MASK = 0xFFFFFFFFFFFFFFFF


def _reference(data: bytes, prev: int) -> int:
    """Straight transcription of the definition, kept independent of fnv.py."""
    h = FNV_OFFSET_BASIS
    for b in (prev & MASK).to_bytes(8, "little") + data:
        h = ((h ^ b) * FNV_PRIME) & MASK
    return h


def test_constants_are_the_published_fnv1a64_parameters() -> None:
    assert FNV_OFFSET_BASIS == 0xCBF29CE484222325
    assert FNV_PRIME == 0x100000001B3


def test_empty_payload_genesis_hash_matches_definition() -> None:
    # Genesis: previous hash 0, folded as eight zero bytes, then no payload.
    expected = FNV_OFFSET_BASIS
    for _ in range(8):
        expected = ((expected ^ 0) * FNV_PRIME) & MASK
    assert fnv1a64(b"", 0) == expected
    assert fnv1a64(b"") == expected


def test_plain_fnv1a_of_ascii_a_after_zero_seed_bytes() -> None:
    # Check the fold step by step against the reference transcription.
    assert fnv1a64(b"a", 0) == _reference(b"a", 0)
    assert fnv1a64(b"RAJ1", 0x1234) == _reference(b"RAJ1", 0x1234)


def test_negative_previous_hash_is_two_s_complement() -> None:
    assert fnv1a64(b"x", -1) == fnv1a64(b"x", MASK)


def test_to_java_long_round_trip() -> None:
    assert to_java_long(0) == 0
    assert to_java_long(1 << 63) == -(1 << 63)
    assert to_java_long(MASK) == -1
    assert to_java_long(5) == 5


@given(st.binary(max_size=256), st.integers(min_value=0, max_value=MASK))
def test_matches_reference_definition(data: bytes, prev: int) -> None:
    assert fnv1a64(data, prev) == _reference(data, prev)


@given(st.binary(max_size=256), st.integers(min_value=0, max_value=MASK))
def test_deterministic_and_in_range(data: bytes, prev: int) -> None:
    first = fnv1a64(data, prev)
    assert first == fnv1a64(data, prev)
    assert 0 <= first <= MASK


@given(st.binary(min_size=1, max_size=64))
def test_different_single_byte_change_differs(data: bytes) -> None:
    flipped = bytes([data[0] ^ 0x01]) + data[1:]
    assert fnv1a64(data) != fnv1a64(flipped)


def test_chain_depends_on_previous_hash() -> None:
    payload = b"\x01\x02\x03"
    assert fnv1a64(payload, 0) != fnv1a64(payload, 1)


def test_matches_shared_golden_vectors() -> None:
    """The same vectors are checked on the Java side (rates-journal ChainHashTest)."""
    golden = Path(__file__).resolve().parents[2] / "data" / "golden" / "chain_hash_vectors.json"
    if not golden.is_file():
        pytest.skip(f"golden file not present: {golden}")
    doc = json.loads(golden.read_text(encoding="utf-8"))
    assert int(doc["offset_basis"], 16) == FNV_OFFSET_BASIS
    assert int(doc["prime"], 16) == FNV_PRIME
    vectors = doc["vectors"]
    assert vectors, "golden file has no vectors"
    for vector in vectors:
        previous = int(vector["previous"], 16)
        payload = vector["payload_ascii"].encode("ascii")
        assert fnv1a64(payload, previous) == int(vector["hash"], 16), vector
