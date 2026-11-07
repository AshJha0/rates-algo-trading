"""Independent check of the forward ledger: hash chain, sequence, and the P&L arithmetic.

Re-derives, from the ledger lines alone and without the Java code, that

* each line's ``prev_sha256`` is the SHA-256 of the previous line's text and ``seq`` counts up;
* every outcome closes the decision just before it, for that decision's session;
* each book line's close mark is ``position * (mid_marked - mid_committed) * value_per_unit``
  (value per unit as in ``InstrumentSet.usdDefault``), the outcome's close mark is their sum,
  and ``pnl_total`` is the session's ``position_pnl`` plus the close mark, each to a cent.

Usage: ``python -m ratesresearch.forward.check forward/ledger.jsonl``. Exit status 0 when every
check passes, 1 otherwise.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from typing import Any

GENESIS = "0" * 64
# InstrumentSet.usdDefault(): swaps annuity * 1e-4; bonds and the future 1e-4.
VALUE_PER_UNIT = {
    "101": 1.92e-4,
    "102": 4.52e-4,
    "103": 8.11e-4,
    "104": 17.29e-4,
    "201": 1e-4,
    "202": 1e-4,
    "301": 1e-4,
}
CENT = 0.0051


def check(path: Path) -> list[str]:
    """Problems found, empty when the ledger is sound."""
    problems: list[str] = []
    prev = GENESIS
    entries: list[dict[str, Any]] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    for n, line in enumerate(lines, start=1):
        e = json.loads(line)
        if e.get("prev_sha256") != prev:
            problems.append(f"line {n}: hash chain broken")
        if e.get("seq") != n:
            problems.append(f"line {n}: seq {e.get('seq')}")
        prev = hashlib.sha256(line.encode("utf-8")).hexdigest()
        entries.append(e)
    for i, e in enumerate(entries):
        if e["kind"] not in ("outcome", "skipped"):
            continue
        before = entries[i - 1] if i > 0 else {}
        if before.get("kind") != "decision" or e["closes_seq"] != before.get("seq"):
            problems.append(f"seq {e['seq']}: does not close the decision before it")
        elif e["session"] != before["session"]:
            problems.append(f"seq {e['seq']}: session {e['session']} is not {before['session']}")
        if e["kind"] == "skipped":
            continue
        total_mark = 0.0
        for iid, b in e["book"].items():
            if b["mid_committed"] != before["mids"][iid]:
                problems.append(f"seq {e['seq']} {iid}: committed mid differs from the decision")
            mark = b["position"] * (b["mid_marked"] - b["mid_committed"]) * VALUE_PER_UNIT[iid]
            if abs(mark - b["close_mark"]) > CENT:
                problems.append(f"seq {e['seq']} {iid}: close mark {b['close_mark']} != {mark:.2f}")
            total_mark += mark
        if abs(total_mark - e["close_mark"]) > CENT * len(e["book"]):
            problems.append(f"seq {e['seq']}: close mark {e['close_mark']} != {total_mark:.2f}")
        total = e["session_result"]["position_pnl"] + e["close_mark"]
        if abs(total - e["pnl_total"]) > CENT * 2:
            problems.append(f"seq {e['seq']}: pnl_total {e['pnl_total']} != {total:.2f}")
    return problems


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 1:
        print("usage: python -m ratesresearch.forward.check LEDGER", file=sys.stderr)
        return 2
    problems = check(Path(args[0]))
    for p in problems:
        print(p)
    if not problems:
        print(f"{args[0]}: chain, sessions and P&L arithmetic check out")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
