"""US Treasury daily par yield curve, one CSV per calendar year 2015 to 2025.

Each file has a ``Date`` column (MM/DD/YYYY, newest first) and the tenor
columns the Treasury published that year. The 2 Mo column starts in October
2018 and the 4 Mo column in October 2022, so the fixture builder takes the
union of tenors and leaves cells empty where a tenor was not published.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEY = "treasury_par_curve"


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download the eleven yearly par curve CSVs into ``out_dir``."""
    return [Path(r.path) for r in download(SOURCE_KEY, out_dir, manifest_path=manifest_path)]
