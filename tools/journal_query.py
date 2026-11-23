"""Query a RAJ1 journal from the command line, verifying the hash chain as it reads.

The runbooks under docs/runbooks/ use this to answer "what happened, in what order" during an
incident without starting the trading process. Frame layout: API_SPEC.md Section 1. Reads format
version 1 (FNV-1a chain) and version 2 (the chain hash named in the header; WORD64 is the one
defined), with the checks Java's JournalReader makes. The hashes match research/ratesresearch/fnv.py
bit for bit; this file stays standalone so a runbook needs nothing installed.

Usage:
  python tools/journal_query.py JOURNAL [--template NAME_OR_ID ...] [--from-seq N] [--to-seq N]
                                        [--since ISO8601] [--until ISO8601] [--count] [--tail N]

Examples:
  python tools/journal_query.py trading.raj --template KillSwitch
  python tools/journal_query.py trading.raj --since 2026-10-30T14:00:00Z --until 2026-10-30T14:05:00Z
  python tools/journal_query.py trading.raj --template ExecutionReport --count
"""

from __future__ import annotations

import argparse
import struct
import sys
from collections.abc import Iterator
from datetime import UTC, datetime
from pathlib import Path

TEMPLATES = {
    1: "Heartbeat", 2: "BookUpdate", 3: "CurveInput", 10: "QuoteRequest", 11: "Quote",
    12: "QuoteResponse", 20: "NewOrder", 21: "ExecutionReport", 30: "RiskLimitBreach",
    31: "KillSwitch", 100: "PostTradeBust", 101: "TradeAmend",
}
BY_NAME = {v.lower(): k for k, v in TEMPLATES.items()}
FNV_OFFSET = 0xCBF29CE484222325
FNV_PRIME = 0x100000001B3
MASK = (1 << 64) - 1
FNV1A, WORD64 = 1, 2
HASH_NAMES = {FNV1A: "fnv1a", WORD64: "word64"}


def chain(previous: int, payload: bytes) -> int:
    """FNV-1a 64 of the previous hash (eight little-endian bytes) and the payload."""
    h = FNV_OFFSET
    for b in previous.to_bytes(8, "little") + payload:
        h = ((h ^ b) * FNV_PRIME) & MASK
    return h


def _rotl(x: int, r: int) -> int:
    return ((x << r) | (x >> (64 - r))) & MASK


def _step(h: int, word: int) -> int:
    k = (_rotl((word * 0x87C37B91114253D5) & MASK, 31) * 0x4CF5AD432745937F) & MASK
    return (_rotl(h ^ k, 27) * 5 + 0x52DCE729) & MASK


def word64(previous: int, payload: bytes) -> int:
    """Java's ChainHash.word64: 8-byte words, MurmurHash3 x64 mix and finaliser."""
    n = len(payload)
    h = previous ^ (((n + 1) * 0x9E3779B97F4A7C15) & MASK)
    whole = n & ~7
    for i in range(0, whole, 8):
        h = _step(h, int.from_bytes(payload[i : i + 8], "little"))
    if whole < n:
        h = _step(h, int.from_bytes(payload[whole:], "little"))
    for mul in (0xFF51AFD7ED558CCD, 0xC4CEB9FE1A85EC53):
        h ^= h >> 33
        h = (h * mul) & MASK
    return h ^ (h >> 33)


def hash_algorithm(path: Path, data: bytes) -> int:
    """The chain hash the file header names; exits on a header Java's JournalReader refuses."""
    if len(data) < 32 or data[:4] != b"RAJ1":
        raise SystemExit(f"{path}: not a RAJ1 journal")
    version, header_length, capacity = struct.unpack_from("<HHi", data, 4)
    if version == 1:
        algorithm = FNV1A
    elif version == 2:
        (algorithm,) = struct.unpack_from("<H", data, 20)
        # FNV-1a is always written as version 1, so a version-2 file must name WORD64
        if algorithm != WORD64:
            raise SystemExit(f"{path}: unsupported chain hash algorithm {algorithm}")
    else:
        raise SystemExit(f"{path}: unsupported format version {version}")
    if header_length != 32 or capacity != len(data):
        raise SystemExit(f"{path}: bad header (length {header_length}, capacity {capacity})")
    return algorithm


def iso(nanos: int) -> str:
    seconds, frac = divmod(nanos, 1_000_000_000)
    return datetime.fromtimestamp(seconds, UTC).strftime("%Y-%m-%dT%H:%M:%S") + f".{frac:09d}Z"


def parse_time(text: str) -> int:
    dt = datetime.fromisoformat(text)
    return int(dt.timestamp()) * 1_000_000_000 + dt.microsecond * 1000


def template_id(text: str) -> int:
    return int(text) if text.isdigit() else BY_NAME[text.lower()]


def frames(path: Path) -> Iterator[tuple[int, int, int, bytes]]:
    """Yield (sequence, timestamp, template id, payload), verifying sequence and chain."""
    data = path.read_bytes()
    algorithm = hash_algorithm(path, data)
    pos, expected, h = 32, 1, 0
    while pos + 4 <= len(data):
        (length,) = struct.unpack_from("<i", data, pos)
        if length == 0:
            return
        if length < 32 or pos + length > len(data):
            raise SystemExit(f"{path}: bad frame length {length} at sequence {expected}")
        seq, ts, tid, _ver, stored = struct.unpack_from("<qqHHq", data, pos + 4)
        payload = data[pos + 32 : pos + length]
        h = chain(h, payload) if algorithm == FNV1A else word64(h, payload)
        if seq != expected or (stored & MASK) != h:
            raise SystemExit(f"{path}: chain or sequence broken at sequence {expected}")
        yield seq, ts, tid, payload
        expected += 1
        pos += (length + 7) & ~7


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("journal", type=Path)
    p.add_argument("--template", action="append", default=[])
    p.add_argument("--from-seq", type=int, default=1)
    p.add_argument("--to-seq", type=int, default=2**63 - 1)
    p.add_argument("--since")
    p.add_argument("--until")
    p.add_argument("--count", action="store_true")
    p.add_argument("--tail", type=int)
    a = p.parse_args()
    wanted = {template_id(t) for t in a.template}
    since = parse_time(a.since) if a.since else 0
    until = parse_time(a.until) if a.until else 2**63 - 1
    rows = []
    last = 0
    for seq, ts, tid, payload in frames(a.journal):
        last = seq
        if a.from_seq <= seq <= a.to_seq and since <= ts <= until and (not wanted or tid in wanted):
            rows.append(f"{seq:>10} {iso(ts)} {TEMPLATES.get(tid, str(tid)):<16} {len(payload)} bytes")
    if a.tail:
        rows = rows[-a.tail :]
    if a.count:
        print(len(rows))
    else:
        print("\n".join(rows))
    algorithm = HASH_NAMES[hash_algorithm(a.journal, a.journal.read_bytes())]
    print(f"chain ({algorithm}) verified to sequence {last}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
