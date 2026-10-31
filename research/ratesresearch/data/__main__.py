"""Command line entry point: download the raw files and build the fixtures.

Example, from the repository root::

    python -m ratesresearch.data --all --raw-dir data/raw --fixtures-dir data/fixtures

A source that fails after two attempts is reported and skipped; the fixtures
that can be built from what did arrive are still written.
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

from ratesresearch.data import (
    boe,
    bundesbank,
    dmo,
    ecb,
    fixtures,
    nyfed,
    ofr,
    treasury,
    treasurydirect,
)
from ratesresearch.data.downloader import DEFAULT_MANIFEST, DownloadError

Fetcher = Callable[..., list[Path]]

FETCHERS: dict[str, Fetcher] = {
    "nyfed": nyfed.fetch,
    "ecb": ecb.fetch,
    "boe": boe.fetch,
    "treasury": treasury.fetch,
    "treasurydirect": treasurydirect.fetch,
    "bundesbank": bundesbank.fetch,
    "dmo": dmo.fetch,
    "ofr": ofr.fetch,
}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m ratesresearch.data", description=__doc__)
    parser.add_argument("--all", action="store_true", help="download every source")
    parser.add_argument(
        "--source",
        action="append",
        choices=sorted(FETCHERS),
        default=[],
        help="download one source (repeatable)",
    )
    parser.add_argument("--raw-dir", default="data/raw", help="where raw files go (not committed)")
    parser.add_argument(
        "--fixtures-dir", default="data/fixtures", help="where fixtures are written"
    )
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST), help="downloads manifest path")
    parser.add_argument(
        "--no-fixtures", action="store_true", help="download only; do not rebuild the fixtures"
    )
    args = parser.parse_args(argv)

    keys = list(FETCHERS) if args.all else list(dict.fromkeys(args.source))
    failures = 0
    for key in keys:
        try:
            paths = FETCHERS[key](args.raw_dir, manifest_path=args.manifest)
        except DownloadError as exc:
            failures += 1
            print(f"FAILED {key}: {exc}", file=sys.stderr)
            continue
        for path in paths:
            print(f"downloaded {key}: {path}")

    if not args.no_fixtures:
        for path in fixtures.build_all(
            args.raw_dir, args.fixtures_dir, manifest_path=args.manifest
        ):
            print(f"wrote {path} ({path.stat().st_size} bytes)")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
