#!/usr/bin/env python3
"""Kill rate per module from PIT's mutations.xml files, as a Markdown table.

Usage: ``python3 tools/pit_summary.py rates-*/target/pit-reports/mutations.xml``. A mutation is
killed when a test failed (or timed out, or ran out of memory) with it in place; survived when
every covering test still passed; not covered when no test runs the line. The kill rate is killed
over all mutations, the number a line-coverage figure cannot give. Standard library only.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

KILLED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}


def summarise(path: Path) -> Counter[str]:
    """Counts of mutations by PIT status in one mutations.xml."""
    return Counter(m.get("status", "UNKNOWN") for m in ET.parse(path).getroot().iter("mutation"))


def row(name: str, counts: Counter[str]) -> str:
    total = sum(counts.values())
    killed = sum(n for status, n in counts.items() if status in KILLED)
    rate = f"{100.0 * killed / total:.1f}%" if total else "n/a"
    return (
        f"| {name} | {total} | {killed} | {counts.get('SURVIVED', 0)} "
        f"| {counts.get('NO_COVERAGE', 0)} | {rate} |"
    )


def main(argv: list[str]) -> int:
    paths = [Path(a) for a in argv if Path(a).is_file()]
    if not paths:
        print("no mutations.xml found; did the PIT run produce a report?")
        return 1
    print("| Module | Mutations | Killed | Survived | Not covered | Kill rate |")
    print("|---|---:|---:|---:|---:|---:|")
    total: Counter[str] = Counter()
    for p in sorted(paths):
        counts = summarise(p)
        total += counts
        # rates-x/target/pit-reports/mutations.xml -> rates-x
        print(row(p.parts[-4] if len(p.parts) >= 4 else str(p), counts))
    if len(paths) > 1:
        print(row("all", total))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
