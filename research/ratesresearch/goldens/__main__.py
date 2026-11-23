"""Write data/golden/analytics_golden.json and data/golden/pricing_v2_golden.json.

Run from the repository root.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ratesresearch.goldens.generator import generate
from ratesresearch.goldens.pricing_v2 import generate_v2


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="data/golden/analytics_golden.json")
    parser.add_argument("--out-v2", default="data/golden/pricing_v2_golden.json")
    parser.add_argument("--fixtures", default="data/fixtures")
    args = parser.parse_args()
    golden = generate(Path(args.fixtures))
    text = json.dumps(golden, indent=1, sort_keys=False) + "\n"
    Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {args.out}: {golden['instrument_count']} priced instruments")
    golden_v2 = generate_v2()
    text_v2 = json.dumps(golden_v2, indent=1, sort_keys=False) + "\n"
    Path(args.out_v2).write_text(text_v2, encoding="utf-8", newline="\n")
    curves, bonds = len(golden_v2["curves"]), len(golden_v2["bonds"])
    print(f"wrote {args.out_v2}: {curves} curves, {bonds} bonds")


if __name__ == "__main__":
    main()
