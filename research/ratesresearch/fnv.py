"""The journal's chain hashes, matching the Java journal (API_SPEC.md Section 1).

The journal stores one ``chainHash`` per frame, a hash of the previous frame's
``chainHash`` and this frame's payload bytes. The first frame uses a previous
hash of zero. Two algorithms exist (rates-journal ``ChainHash``):

* FNV-1a 64 (id 1): the previous hash as eight little-endian bytes, then the
  payload, one byte per step. Every journal written with the default is format
  version 1, as before round 6.
* WORD64 (id 2): eight payload bytes per step with MurmurHash3's word mix and
  finaliser. Opt-in; such a journal is format version 2 and names the
  algorithm in its file header at offset 20.

Having the same functions in Python lets a research script verify a journal
written by the Java ``JournalWriter`` without running the JVM.
"""

from __future__ import annotations

import struct

FNV_OFFSET_BASIS: int = 0xCBF29CE484222325
FNV_PRIME: int = 0x100000001B3
_MASK64: int = 0xFFFFFFFFFFFFFFFF


def fnv1a64(data: bytes, seed_prev_hash: int = 0) -> int:
    """Return the chain hash of ``data`` given the previous frame's hash.

    The previous hash is treated as an unsigned 64-bit integer, written as
    eight little-endian bytes, and folded into the hash before ``data``.
    Java stores the result in a signed ``long``; callers comparing against
    Java values should reinterpret with ``value - 2**64 if value >= 2**63``.

    Args:
        data: the frame payload.
        seed_prev_hash: the previous frame's ``chainHash`` (0 for the genesis frame).
            Negative values are accepted and interpreted as two's-complement 64-bit.
    """
    h = FNV_OFFSET_BASIS
    prev = seed_prev_hash & _MASK64
    for byte in prev.to_bytes(8, "little"):
        h ^= byte
        h = (h * FNV_PRIME) & _MASK64
    for byte in data:
        h ^= byte
        h = (h * FNV_PRIME) & _MASK64
    return h


def to_java_long(value: int) -> int:
    """Reinterpret an unsigned 64-bit value as Java's signed ``long``."""
    value &= _MASK64
    return value - (1 << 64) if value >= (1 << 63) else value


# ---------------------------------------------------------------- WORD64

FNV1A: int = 1
WORD64: int = 2

_LENGTH_MIX: int = 0x9E3779B97F4A7C15
_C1: int = 0x87C37B91114253D5
_C2: int = 0x4CF5AD432745937F


def _rotl(value: int, bits: int) -> int:
    return ((value << bits) | (value >> (64 - bits))) & _MASK64


def _step(h: int, word: int) -> int:
    k = (_rotl((word * _C1) & _MASK64, 31) * _C2) & _MASK64
    return (_rotl(h ^ k, 27) * 5 + 0x52DCE729) & _MASK64


def word64(data: bytes, seed_prev_hash: int = 0) -> int:
    """Return the WORD64 chain hash of ``data`` given the previous frame's hash.

    Bit for bit Java's ``ChainHash.word64``: the length (as ``n + 1``) is mixed
    into the previous hash, each whole 8-byte little-endian word and then the
    zero-extended tail is folded in with MurmurHash3's x64 word mix, and the
    result goes through MurmurHash3's finaliser. Arithmetic is modulo 2**64;
    the result is unsigned, like :func:`fnv1a64`.
    """
    n = len(data)
    h = (seed_prev_hash & _MASK64) ^ (((n + 1) * _LENGTH_MIX) & _MASK64)
    whole = n & ~7
    for i in range(0, whole, 8):
        h = _step(h, int.from_bytes(data[i : i + 8], "little"))
    if whole < n:
        h = _step(h, int.from_bytes(data[whole:], "little"))
    h ^= h >> 33
    h = (h * 0xFF51AFD7ED558CCD) & _MASK64
    h ^= h >> 33
    h = (h * 0xC4CEB9FE1A85EC53) & _MASK64
    h ^= h >> 33
    return h


def chain_hash(algorithm: int, data: bytes, seed_prev_hash: int = 0) -> int:
    """Return the chain hash of ``algorithm`` (:data:`FNV1A` or :data:`WORD64`).

    Raises:
        ValueError: for any other algorithm id.
    """
    if algorithm == FNV1A:
        return fnv1a64(data, seed_prev_hash)
    if algorithm == WORD64:
        return word64(data, seed_prev_hash)
    raise ValueError(f"unknown chain hash algorithm {algorithm}")


# ---------------------------------------------------------------- file header

MAGIC: bytes = b"RAJ1"
FORMAT_VERSION: int = 1
FORMAT_VERSION_NAMED_HASH: int = 2
FILE_HEADER_LENGTH: int = 32
FRAME_HEADER_LENGTH: int = 32
HASH_ALGORITHM_OFFSET: int = 20


def journal_hash_algorithm(data: bytes) -> int:
    """Return the chain hash algorithm a journal file's header names.

    Applies the checks of Java's ``JournalReader``: magic ``RAJ1``; format
    version 1 means FNV-1a; version 2 names the algorithm at offset 20 and
    must name a known one other than FNV-1a (FNV-1a is always written as
    version 1); header length 32; declared capacity equal to the file size.

    Raises:
        ValueError: when any of those checks fails.
    """
    if len(data) < FILE_HEADER_LENGTH:
        raise ValueError("file shorter than its header")
    magic, version, header_length, capacity = struct.unpack_from("<4sHHi", data, 0)
    if magic != MAGIC:
        raise ValueError(f"bad magic {magic!r}")
    if version == FORMAT_VERSION:
        algorithm = FNV1A
    elif version == FORMAT_VERSION_NAMED_HASH:
        (algorithm,) = struct.unpack_from("<H", data, HASH_ALGORITHM_OFFSET)
        if algorithm != WORD64:
            raise ValueError(f"unsupported chain hash algorithm {algorithm}")
    else:
        raise ValueError(f"unsupported format version {version}")
    if header_length != FILE_HEADER_LENGTH:
        raise ValueError(f"unexpected header length {header_length}")
    if capacity != len(data):
        raise ValueError(f"declared capacity {capacity} but the file holds {len(data)}")
    return algorithm
