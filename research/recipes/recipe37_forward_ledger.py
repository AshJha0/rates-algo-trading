"""COOKBOOK.md recipe 37: check a forward-log ledger from Python, then tamper with it."""

import hashlib
import json
import tempfile
from pathlib import Path

from ratesresearch.forward import check

# A two-line ledger in the forward layout: a decision, then the outcome that closes it.
# Made-up numbers: one 10-year swap position of 10 million (1e9 cents) marked 2 bp higher.
vpu = check.VALUE_PER_UNIT["103"]
mark = round(1_000_000_000 * (4.47 - 4.45) * vpu, 2)
decision = {"kind": "decision", "session": "2026-11-09", "mids": {"103": 4.45}}
outcome = {
    "kind": "outcome",
    "closes_seq": 1,
    "session": "2026-11-09",
    "book": {
        "103": {
            "position": 1_000_000_000,
            "mid_committed": 4.45,
            "mid_marked": 4.47,
            "close_mark": mark,
        }
    },
    "close_mark": mark,
    "session_result": {"position_pnl": 1250.0},
    "pnl_total": round(mark + 1250.0, 2),
}
lines, prev = [], check.GENESIS
for seq, entry in enumerate((decision, outcome), start=1):
    line = json.dumps(
        {**entry, "seq": seq, "prev_sha256": prev}, sort_keys=True, separators=(",", ":")
    )
    prev = hashlib.sha256(line.encode()).hexdigest()
    lines.append(line)
print(f"close mark of 10mm 10Y on +2 bp: {mark:,.2f} USD")

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "ledger.jsonl"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("sound ledger problems:", check.check(path))
    # rewrite history: pretend the decision had committed a higher mid
    path.write_text(
        path.read_text(encoding="utf-8").replace('"mids":{"103":4.45}', '"mids":{"103":4.46}'),
        encoding="utf-8",
    )
    for problem in check.check(path):
        print("tampered:", problem)
