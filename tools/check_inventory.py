"""Check docs/compliance/RTS6_INVENTORY.md for completeness.

- Every UK RTS 6 article from 1 to 29 has a row (ranges such as "19 to 23" count for each).
- Every row has a status of implemented, evidenced, planned or out of scope.
- Every row that is not out of scope has at least one link, and every relative link in the file
  resolves to a file in the repository.

Run in CI by .github/workflows/ops.yml. Usage: python tools/check_inventory.py [repo root]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
STATUSES = ("implemented", "evidenced", "planned", "out of scope")


def main(root: Path) -> int:
    path = root / "docs" / "compliance" / "RTS6_INVENTORY.md"
    text = path.read_text(encoding="utf-8")
    errors: list[str] = []
    section = text.split("## UK RTS 6, article by article", 1)[1].split("\n## ", 1)[0]
    covered: set[int] = set()
    rows = [line for line in section.splitlines() if line.startswith("| ") and not line.startswith("| Article")]
    rows = [r for r in rows if not set(r.replace("|", "").strip()) <= {"-"}]
    for row in rows:
        cells = [c.strip() for c in row.strip("|").split("|")]
        first = cells[0]
        m = re.match(r"(\d+) to (\d+)", first)
        if m:
            covered.update(range(int(m.group(1)), int(m.group(2)) + 1))
        else:
            n = re.match(r"(\d+)", first)
            if n:
                covered.add(int(n.group(1)))
        status = cells[2] if len(cells) > 2 else ""
        if not status.startswith(STATUSES):
            errors.append(f"row '{first}': status '{status}' is not one of {STATUSES}")
        if not status.startswith("out of scope") and not LINK.search(row):
            errors.append(f"row '{first}': no link to a test, file or runbook")
        if status.startswith("out of scope") and len(cells) < 4 or (len(cells) > 3 and not cells[3]):
            errors.append(f"row '{first}': no control or reason given")
    missing = sorted(set(range(1, 30)) - covered)
    if missing:
        errors.append(f"articles without a row: {missing}")
    for target in LINK.findall(text):
        if target.startswith(("http://", "https://", "#")):
            continue
        file_part = target.split("#", 1)[0]
        if not (path.parent / file_part).resolve().exists():
            errors.append(f"broken link: {target}")
    for e in errors:
        print(e)
    if not errors:
        print(f"inventory: articles 1 to 29 covered in {len(rows)} rows; every link resolves")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]))
