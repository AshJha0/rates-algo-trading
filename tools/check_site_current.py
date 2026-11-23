"""Check that the public front pages are not older than the latest CHANGELOG release.

The 2026-11-23 review (docs/reviews/REVIEW_2026-11-23.md, finding E1) found the site home page
still describing "Phase 0: foundations only" a month after v1.0.0. This check stops that
happening again. It reads the latest release heading in CHANGELOG.md (``## [x.y.z] - YYYY-MM-DD``,
Unreleased ignored) and then, for each front page:

- the header (the text before the first ``## `` heading) must name that version, must not name an
  older one, and every ISO date in it must be on or after the release date;
- the whole page must not contain a phrase that described an earlier state of the project.

Front pages: the site home page (docs/site/pages/index.md), STATUS.md and README.md.
Stdlib only. Run in CI by .github/workflows/docs.yml before the site build.
Usage: python tools/check_site_current.py [repo root]
"""

from __future__ import annotations

import re
import sys
from datetime import date
from pathlib import Path

FRONT_PAGES = ("docs/site/pages/index.md", "STATUS.md", "README.md")

# Phrases that were true once and are not now. Lower case; matched case-insensitively.
STALE_PHRASES = (
    "phase 0: foundations only",
    "no pricing, no venue simulator",
    "repository is private",
    "is private until",
    "with no numbers yet",
    "the rest are outlined with their phase",
    "three of them against the phase 0 api",
)

RELEASE = re.compile(r"^## \[(\d+)\.(\d+)\.(\d+)\] - (\d{4}-\d{2}-\d{2})\s*$", re.MULTILINE)
VERSION = re.compile(r"(?<![\w.])v?(\d+)\.(\d+)\.(\d+)(?![\w.])")
ISO_DATE = re.compile(r"\b(\d{4})-(\d{2})-(\d{2})\b")


def latest_release(changelog: str) -> tuple[tuple[int, int, int], date]:
    releases = [
        ((int(a), int(b), int(c)), date.fromisoformat(d)) for a, b, c, d in RELEASE.findall(changelog)
    ]
    if not releases:
        raise ValueError("CHANGELOG.md has no release heading of the form '## [x.y.z] - YYYY-MM-DD'")
    return max(releases)


def header(text: str) -> str:
    """Text before the first second-level heading."""
    match = re.search(r"^## ", text, re.MULTILINE)
    return text[: match.start()] if match else text


def check_page(rel: str, text: str, version: tuple[int, int, int], released: date) -> list[str]:
    errors: list[str] = []
    lowered = text.lower()
    for phrase in STALE_PHRASES:
        if phrase in lowered:
            errors.append(f"{rel}: stale phrase '{phrase}'")
    head = header(text)
    named = {tuple(int(x) for x in m) for m in VERSION.findall(head)}
    wanted = ".".join(map(str, version))
    if version not in named:
        errors.append(f"{rel}: header does not name the latest release {wanted}")
    for older in sorted(v for v in named if v < version):
        errors.append(f"{rel}: header names {'.'.join(map(str, older))}, older than {wanted}")
    for y, m, d in ISO_DATE.findall(head):
        when = date(int(y), int(m), int(d))
        if when < released:
            errors.append(f"{rel}: header date {when} is before the {wanted} release date {released}")
    return errors


def main(root: Path) -> int:
    version, released = latest_release((root / "CHANGELOG.md").read_text(encoding="utf-8"))
    errors: list[str] = []
    for rel in FRONT_PAGES:
        path = root / rel
        if not path.is_file():
            errors.append(f"{rel}: missing")
            continue
        errors.extend(check_page(rel, path.read_text(encoding="utf-8"), version, released))
    for e in errors:
        print(e)
    if not errors:
        wanted = ".".join(map(str, version))
        print(f"front pages current: {len(FRONT_PAGES)} pages name {wanted} ({released}) and no stale phrase")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]))
