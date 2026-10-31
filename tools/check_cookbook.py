"""Check that COOKBOOK.md quotes the recipe programs and their recorded output verbatim.

For each recipe number N that has a program under rates-cookbook, the first ```java block
after the heading "## N." must equal the program source, and the first ``` block after the
words "Expected output" in that section must equal rates-cookbook's expected file. CI runs
this in the Python job; RecipesTest runs the programs and compares them with the same
expected files, so the cookbook, the code and the output cannot drift apart.

Usage: python tools/check_cookbook.py [repo root]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

RECIPES = {
    1: ("Recipe01TimerWheel.java", "recipe01.txt"),
    2: ("Recipe02Journal.java", "recipe02.txt"),
    3: ("Recipe03QuoteRequest.java", "recipe03.txt"),
    4: ("Recipe04SofrCurve.java", "recipe04.txt"),
    5: ("Recipe05SwapDv01.java", "recipe05.txt"),
    6: ("Recipe06Treasury.java", "recipe06.txt"),
    7: ("Recipe07GiltExDividend.java", "recipe07.txt"),
    8: ("Recipe08Ctd.java", "recipe08.txt"),
    17: ("Recipe17RouteHedge.java", "recipe17.txt"),
}


def section(text: str, number: int) -> str:
    start = re.search(rf"^## {number}\. ", text, re.M)
    if start is None:
        raise SystemExit(f"COOKBOOK.md: heading for recipe {number} not found")
    rest = text[start.end() :]
    nxt = re.search(r"^## \d+\. ", rest, re.M)
    return rest if nxt is None else rest[: nxt.start()]


def fenced(block_text: str, lang: str, after: str | None = None) -> str:
    body = block_text
    if after is not None:
        idx = body.find(after)
        if idx < 0:
            raise SystemExit(f"COOKBOOK.md: '{after}' not found in section")
        body = body[idx:]
    m = re.search(rf"^```{lang}\n(.*?)^```\s*$", body, re.M | re.S)
    if m is None:
        raise SystemExit(f"COOKBOOK.md: ```{lang} block not found")
    return m.group(1)


def main(root: Path) -> int:
    cookbook = (root / "COOKBOOK.md").read_text(encoding="utf-8").replace("\r\n", "\n")
    src_dir = root / "rates-cookbook" / "src" / "main" / "java" / "io" / "ratesalgo" / "cookbook"
    exp_dir = root / "rates-cookbook" / "src" / "test" / "resources" / "expected"
    failures = 0
    for number, (java_name, expected_name) in RECIPES.items():
        sec = section(cookbook, number)
        java = (src_dir / java_name).read_text(encoding="utf-8").replace("\r\n", "\n")
        expected = (exp_dir / expected_name).read_text(encoding="utf-8").replace("\r\n", "\n")
        quoted_java = fenced(sec, "java")
        quoted_out = fenced(sec, "", after="Expected output")
        if quoted_java.strip() != java.strip():
            print(f"recipe {number}: code block differs from {java_name}")
            failures += 1
        if quoted_out.strip() != expected.strip():
            print(f"recipe {number}: output block differs from {expected_name}")
            failures += 1
    if failures == 0:
        print(f"cookbook: {len(RECIPES)} recipes match their sources and recorded output")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]))
