"""OFR short-term funding monitor: tri-party and DVP overnight repo rate series.

The task brief described ``REPO-TRI_AR_OO-P`` as a volume; the OFR metadata
endpoint (``/v1/metadata/query?mnemonic=...``) says it is the "Tri-Party
Average Rate: Overnight/Open (Preliminary)", a volume-weighted mean rate in
percent. ``REPO-DVP_AR_OO-P`` is the DVP counterpart. The fixture records
the series as published and names them by mnemonic. The timeseries endpoint
returns a JSON array of ``[date, value]`` pairs with ``null`` for missing days.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEY = "ofr_repo"
MNEMONICS = ("REPO-TRI_AR_OO-P", "REPO-DVP_AR_OO-P")


def file_name(mnemonic: str) -> str:
    """Raw file name for a mnemonic, matching the downloader spec."""
    return f"ofr_{mnemonic.lower().replace('-', '_')}.json"


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download both repo series JSON files into ``out_dir``."""
    return [Path(r.path) for r in download(SOURCE_KEY, out_dir, manifest_path=manifest_path)]
