"""Write data/golden/analytics_golden.json. Run from the repository root."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ratesresearch.goldens.generator import generate


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="data/golden/analytics_golden.json")
    parser.add_argument("--fixtures", default="data/fixtures")
    args = parser.parse_args()
    golden = generate(Path(args.fixtures))
    text = json.dumps(golden, indent=1, sort_keys=False) + "\n"
    Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {args.out}: {golden['instrument_count']} priced instruments")


if __name__ == "__main__":
    main()
