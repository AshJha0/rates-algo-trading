"""Journal format versions 1 and 2 and the WORD64 chain hash, checked against Java.

The fixtures under ``fixtures/journal/`` were written by Java's ``JournalWriter`` and
``ChainHash`` (the program and the command that ran it are in ``MakeJournals.java`` there):
``fnv1a_v1.raj`` with the default FNV-1a chain (format version 1), ``word64_v2.raj`` with
WORD64 (format version 2), both holding the same eight frames with payloads of 0 to 17 bytes,
and ``word64_vectors.txt``, Java's WORD64 hash of 100 (previous hash, payload) pairs. Both
Python readers, ``ratesresearch.fnv`` and ``tools/journal_query.py``, must reproduce every
stored hash bit for bit and refuse the headers Java's ``JournalReader`` refuses.
"""

from __future__ import annotations

import importlib.util
import struct
from collections.abc import Callable
from pathlib import Path
from types import ModuleType

import pytest
from hypothesis import given
from hypothesis import strategies as st

from ratesresearch.fnv import (
    FNV1A,
    WORD64,
    chain_hash,
    fnv1a64,
    journal_hash_algorithm,
    to_java_long,
    word64,
)

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "journal"
MASK = (1 << 64) - 1


def _load_journal_query() -> ModuleType:
    spec = importlib.util.spec_from_file_location(
        "journal_query", ROOT / "tools" / "journal_query.py"
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


jq = _load_journal_query()


def _walk(data: bytes) -> list[tuple[int, int, bytes, int]]:
    """(sequence, template id, payload, stored hash) of every frame, stored hash unsigned."""
    out = []
    pos = 32
    while pos + 4 <= len(data):
        (length,) = struct.unpack_from("<i", data, pos)
        if length == 0:
            break
        seq, _ts, template, _version, stored = struct.unpack_from("<qqHHq", data, pos + 4)
        out.append((seq, template, data[pos + 32 : pos + length], stored & MASK))
        pos += (length + 7) & ~7
    return out


def _vectors() -> list[tuple[int, bytes, int]]:
    rows = []
    for line in (FIXTURES / "word64_vectors.txt").read_text(encoding="ascii").splitlines():
        previous, payload, expected = line.split()
        data = b"" if payload == "-" else bytes.fromhex(payload)
        rows.append((int(previous, 16), data, int(expected, 16)))
    return rows


def test_word64_matches_java_known_answers() -> None:
    rows = _vectors()
    assert len(rows) == 100
    assert {len(data) for _, data, _ in rows} == set(range(25)), "every tail length, 0 to 24"
    for previous, data, expected in rows:
        assert word64(data, previous) == expected, (hex(previous), data.hex())
        assert jq.word64(previous, data) == expected, (hex(previous), data.hex())
        assert chain_hash(WORD64, data, previous) == expected


def test_negative_previous_hash_is_two_s_complement_in_word64() -> None:
    assert word64(b"x", -1) == word64(b"x", MASK)


@pytest.mark.parametrize(
    ("name", "version", "algorithm"),
    [("fnv1a_v1.raj", 1, FNV1A), ("word64_v2.raj", 2, WORD64)],
)
def test_java_journal_verifies_frame_by_frame(name: str, version: int, algorithm: int) -> None:
    data = (FIXTURES / name).read_bytes()
    assert struct.unpack_from("<H", data, 4)[0] == version
    assert journal_hash_algorithm(data) == algorithm
    frames = _walk(data)
    assert [seq for seq, *_ in frames] == list(range(1, 9))
    assert [len(payload) for _, _, payload, _ in frames] == [0, 1, 7, 8, 9, 15, 16, 17]
    previous = 0
    for _seq, _template, payload, stored in frames:
        previous = chain_hash(algorithm, payload, previous)
        assert previous == stored
        assert to_java_long(previous) == to_java_long(stored)
    # journal_query reads the same file the same way
    assert [(s, t, p) for s, t, p, _ in frames] == [
        (s, t, p) for s, _ts, t, p in jq.frames(FIXTURES / name)
    ]


def test_the_two_journals_hold_the_same_frames_but_different_chains() -> None:
    fnv = _walk((FIXTURES / "fnv1a_v1.raj").read_bytes())
    w64 = _walk((FIXTURES / "word64_v2.raj").read_bytes())
    assert [f[:3] for f in fnv] == [f[:3] for f in w64]
    assert all(a[3] != b[3] for a, b in zip(fnv, w64, strict=True))


def test_version_one_header_is_unchanged_with_a_zero_algorithm_field() -> None:
    data = (FIXTURES / "fnv1a_v1.raj").read_bytes()
    assert struct.unpack_from("<H", data, 20)[0] == 0
    assert data[22:32] == bytes(10), "reserved bytes are zero"


def _patched(name: str, offset: int, fmt: str, value: int) -> bytes:
    data = bytearray((FIXTURES / name).read_bytes())
    struct.pack_into(fmt, data, offset, value)
    return bytes(data)


def _both_refuse(data: bytes, tmp_path: Path, message: str) -> None:
    with pytest.raises(ValueError, match=message):
        journal_hash_algorithm(data)
    path = tmp_path / "patched.raj"
    path.write_bytes(data)
    with pytest.raises(SystemExit):
        list(jq.frames(path))


def test_a_version_two_header_naming_fnv1a_is_refused_as_java_does(tmp_path: Path) -> None:
    # Java writes FNV-1a only as version 1, and its JournalReader refuses version 2 naming it
    data = _patched("fnv1a_v1.raj", 4, "<H", 2)
    data = data[:20] + struct.pack("<H", FNV1A) + data[22:]
    _both_refuse(data, tmp_path, "unsupported chain hash algorithm 1")


@pytest.mark.parametrize("algorithm", [0, 3, 0xFFFF])
def test_a_version_two_header_naming_an_unknown_hash_is_refused(
    algorithm: int, tmp_path: Path
) -> None:
    data = _patched("word64_v2.raj", 20, "<H", algorithm)
    _both_refuse(data, tmp_path, f"unsupported chain hash algorithm {algorithm}")


@pytest.mark.parametrize(
    ("offset", "fmt", "value", "message"),
    [
        (0, "<I", 0x324A4152, "bad magic"),
        (4, "<H", 3, "unsupported format version 3"),
        (6, "<H", 40, "unexpected header length 40"),
        (8, "<i", 1024, "declared capacity 1024"),
    ],
)
def test_other_bad_headers_are_refused(
    offset: int, fmt: str, value: int, message: str, tmp_path: Path
) -> None:
    _both_refuse(_patched("word64_v2.raj", offset, fmt, value), tmp_path, message)


def test_a_file_shorter_than_its_header_is_refused() -> None:
    with pytest.raises(ValueError, match="shorter than its header"):
        journal_hash_algorithm(b"RAJ1")


@pytest.mark.parametrize("name", ["fnv1a_v1.raj", "word64_v2.raj"])
def test_one_flipped_payload_bit_breaks_the_chain_at_that_frame(name: str, tmp_path: Path) -> None:
    data = bytearray((FIXTURES / name).read_bytes())
    # frame 2 starts at 32 + 32 (frame 1 has an empty payload); its one payload byte follows
    data[32 + 32 + 32] ^= 0x01
    path = tmp_path / name
    path.write_bytes(bytes(data))
    with pytest.raises(SystemExit, match="sequence 2"):
        list(jq.frames(path))
    algorithm = journal_hash_algorithm(bytes(data))
    frames = _walk(bytes(data))
    assert chain_hash(algorithm, frames[0][2], 0) == frames[0][3]
    assert chain_hash(algorithm, frames[1][2], frames[0][3]) != frames[1][3]


def test_chain_hash_refuses_an_unknown_algorithm() -> None:
    with pytest.raises(ValueError, match="unknown chain hash algorithm 7"):
        chain_hash(7, b"", 0)


HASHES: list[tuple[Callable[[bytes, int], int], Callable[[int, bytes], int]]] = [
    (fnv1a64, jq.chain),
    (word64, jq.word64),
]


@given(st.binary(max_size=64), st.integers(min_value=0, max_value=MASK))
def test_the_two_python_implementations_agree(data: bytes, previous: int) -> None:
    for research, tool in HASHES:
        assert research(data, previous) == tool(previous, data)


@given(st.binary(max_size=40), st.integers(min_value=0, max_value=MASK))
def test_word64_tells_trailing_zero_bytes_apart(data: bytes, previous: int) -> None:
    # the length is mixed in first, so a payload and the same payload plus a zero byte differ
    assert word64(data, previous) != word64(data + b"\x00", previous)
