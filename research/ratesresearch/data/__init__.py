"""Download public rate and curve data and turn it into small committed fixtures.

The package has three layers. ``downloader`` fetches files over plain HTTPS
with urllib, hashes them and appends a record to ``data/manifests/downloads.json``.
One module per source (``nyfed``, ``ecb``, ``boe``, ``treasury``,
``treasurydirect``, ``bundesbank``, ``dmo``, ``ofr``) knows which spec to
fetch. ``fixtures`` parses the raw files and writes the CSV fixtures under
``data/fixtures`` with a provenance JSON next to each one. Raw files live
under ``data/raw`` and are not committed (DR-006). ``dtcc`` and ``finra``
(P5) download and load the DTCC SDR and FINRA Treasury files.

Run everything with ``python -m ratesresearch.data --all``.
"""

from ratesresearch.data.downloader import Download, DownloadError, download

__all__ = ["Download", "DownloadError", "download"]
