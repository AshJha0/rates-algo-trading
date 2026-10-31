"""Bank of England Interactive Database: SONIA (IUDSOIA) and the SONIA Compounded Index (IUDZOS2).

The ``fromshowcolumns.asp`` URL answers with an HTML table even when
``csv.x=yes`` is set; the ``_iadb-fromshowcolumns.asp`` endpoint with the same
query string returns ``DATE,IUDSOIA,IUDZOS2`` as CSV with dates like
``01 Dec 2023``.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEY = "boe_sonia"


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download the SONIA rate and index CSV into ``out_dir``."""
    return [Path(r.path) for r in download(SOURCE_KEY, out_dir, manifest_path=manifest_path)]
