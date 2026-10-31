"""NY Fed secured reference rates: SOFR, the SOFR Index, TGCR and BGCR.

The markets API returns one CSV per search with the same 19 columns for
every rate type. The ``sofr`` search holds the rate and percentiles, the
``sofrai`` search holds the compounded averages and the SOFR Index, and
``all`` holds every secured rate on each date.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEYS = ("nyfed_sofr", "nyfed_gc")


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download the SOFR, SOFR Index and all-secured-rates CSVs into ``out_dir``."""
    paths: list[Path] = []
    for key in SOURCE_KEYS:
        paths.extend(Path(r.path) for r in download(key, out_dir, manifest_path=manifest_path))
    return paths
