"""ECB Data Portal: euro short-term rate and its compounded index.

Series keys in the ``EST`` dataflow: ``B.EU000A2X2A25.WT`` is the
volume-weighted trimmed mean rate. The task brief named ``B.EU000A2QQF16.CI``
for the index; on 2026-10-31 that key returned 404 and the wildcard query
``B.EU000A2QQF16.`` showed EU000A2QQF16 to be the 1-week compounded average
rate (data type ``CR``). The compounded index (1 Oct 2019 = 100) is
``B.EU000A2QQF08.CI``, which is what this module downloads.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEY = "ecb_estr"


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download the rate and compounded index CSVs into ``out_dir``."""
    return [Path(r.path) for r in download(SOURCE_KEY, out_dir, manifest_path=manifest_path)]
