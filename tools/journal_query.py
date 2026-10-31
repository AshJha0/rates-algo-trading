"""Query a RAJ1 journal from the command line, verifying the hash chain as it reads.

The runbooks under docs/runbooks/ use this to answer "what happened, in what order" during an
incident without starting the trading process. Frame layout: API_SPEC.md Section 1.

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


def chain(previous: int, payload: bytes) -> int:
    h = FNV_OFFSET
    for b in previous.to_bytes(8, "little") + payload:
        h = ((h ^ b) * FNV_PRIME) & MASK
    return h


def iso(nanos: int) -> str:
    seconds, frac = divmod(nanos, 1_000_000_000)
    return datetime.fromtimestamp(seconds, UTC).strftime("%Y-%m-%dT%H:%M:%S") + f".{frac:09d}Z"


def parse_time(text: str) -> int:
    dt = datetime.fromisoformat(text)
    return int(dt.timestamp()) * 1_000_000_000 + dt.microsecond * 1000


def template_id(text: str) -> int:
    return int(text) if text.isdigit() else BY_NAME[text.lower()]


def frames(path: Path):  # type: ignore[no-untyped-def]
    data = path.read_bytes()
    if data[:4] != b"RAJ1":
        raise SystemExit(f"{path}: not a RAJ1 journal")
    pos, expected, h = 32, 1, 0
    while pos + 4 <= len(data):
        (length,) = struct.unpack_from("<i", data, pos)
        if length == 0:
            return
        seq, ts, tid, _ver, stored = struct.unpack_from("<qqHHq", data, pos + 4)
        payload = data[pos + 32 : pos + length]
        h = chain(h, payload)
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
    print(f"chain verified to sequence {last}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
