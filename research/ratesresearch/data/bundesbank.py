"""Bundesbank term structure of listed Federal securities, Svensson parameters.

The task brief pointed at the ``BBSSY`` dataset. On 2026-10-31 the REST
download endpoint returned 404 for every ``BBSSY`` key tried, and the
statistics page did not list series codes. The parameters live in ``BBSIS``
(interest rate statistics) with keys
``BBSIS.D.I.ZST.{B0,B1,B2,B3,T1,T2}.EUR.S1311.B.A604._Z.R.A.A._Z._Z.A``
(the maturity dimension is ``_Z`` for the parameters; the fitted yields use
``R10XX`` and similar). Each CSV has nine metadata lines, then
``date,value,flag`` rows with ``.`` and ``No value available`` on non-trading
days.
"""

from __future__ import annotations

from pathlib import Path

from ratesresearch.data.downloader import DEFAULT_MANIFEST, download

SOURCE_KEY = "bundesbank_svensson"
PARAMETERS = ("beta0", "beta1", "beta2", "beta3", "tau1", "tau2")
FILE_NAMES = tuple(
    f"bundesbank_svensson_{code}.csv" for code in ("b0", "b1", "b2", "b3", "t1", "t2")
)


def fetch(out_dir: str | Path, *, manifest_path: str | Path = DEFAULT_MANIFEST) -> list[Path]:
    """Download the six parameter CSVs into ``out_dir``."""
    return [Path(r.path) for r in download(SOURCE_KEY, out_dir, manifest_path=manifest_path)]
