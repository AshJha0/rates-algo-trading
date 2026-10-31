"""UK Debt Management Office gilt reference prices.

On 2026-10-31 every request to https://www.dmo.gov.uk/data/ from this
downloader was answered with an HTTP 200 bot-protection page (a ShieldSquare
block that needs JavaScript), so the terms could not be read and no file
could be fetched. ``fetch`` makes one attempt and raises ``DownloadError``
with that explanation when it sees the block page, so the CLI records the
failure and carries on. The gilt reference price fixture is therefore not
produced; data/SOURCES.md says so.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, DownloadError, fetch_bytes

DATA_URL = "https://www.dmo.gov.uk/data/"
BLOCK_MARKERS = (b"ShieldSquare", b"perfdrive.com")


def looks_blocked(body: bytes) -> bool:
    """True when the body is the bot-protection page rather than data."""
    return any(marker in body for marker in BLOCK_MARKERS)


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Attempt the DMO data page; raise ``DownloadError`` if it is bot-blocked.

    Nothing is written to ``out_dir`` or the manifest unless a real data file
    comes back, which has not happened yet.
    """
    del out_dir, manifest_path
    body = fetch_bytes(DATA_URL, attempts=1)
    if looks_blocked(body):
        msg = (
            f"{DATA_URL} answered with a bot-protection page (HTTP 200, ShieldSquare); "
            "the download is manual and no gilt fixture is produced"
        )
        raise DownloadError(msg)
    msg = f"{DATA_URL} answered without the block page; add a parser before using it"
    raise DownloadError(msg)
