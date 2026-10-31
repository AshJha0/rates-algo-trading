"""FNV-1a 64-bit chain hash, matching the Java journal (API_SPEC.md Section 1).

The journal stores one ``chainHash`` per frame. It is the FNV-1a 64-bit hash
of the previous frame's ``chainHash`` (eight little-endian bytes) followed by
this frame's payload bytes. The first frame uses a previous hash of zero.

Having the same function in Python lets a research script verify a journal
written by the Java ``JournalWriter`` without running the JVM.
"""

from __future__ import annotations

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
