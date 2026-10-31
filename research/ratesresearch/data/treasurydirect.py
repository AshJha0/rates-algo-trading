"""TreasuryDirect auction results for notes, bonds and bills.

The ``TA_WS/securities/search`` endpoint returns a JSON array of auction
records. On 2026-10-31 the documented ``dateFieldName=auctionDate`` filter
returned HTTP 500 for every variant tried, while ``dateFieldName=issueDate``
worked, so the download asks for issue dates from 2023-12-01 to 2025-01-31 and
the fixture builder keeps records whose auction day falls in 2024. The records
carry no ``auctionDate`` field; ``competitiveAuctionCloseDate`` is the auction
day and is used as such.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEY = "treasurydirect_auctions"


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download the note, bond and bill auction JSON files into ``out_dir``."""
    return [Path(r.path) for r in download(SOURCE_KEY, out_dir, manifest_path=manifest_path)]
