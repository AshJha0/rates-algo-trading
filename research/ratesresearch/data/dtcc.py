"""DTCC SDR public price dissemination, rates asset class, CFTC end-of-day cumulative slices.

Source: one zip per business day at
``https://kgc0418-tdw-data-0.s3.amazonaws.com/cftc/eod/CFTC_CUMULATIVE_RATES_YYYY_MM_DD.zip``,
the files behind the DTCC public price dissemination dashboard (https://pddata.dtcc.com/ppd/).
Each zip holds one CSV with the CFTC Part 43 public fields (about 115 columns).

What the loader keeps (``load_sofr_ois``): new trades (``Action type`` NEWT, ``Event type``
TRAD) of USD SOFR overnight index swaps (``UPI FISN`` "NA/Swap OIS USD" and an underlier name
containing SOFR), that are not part of a package (``Package indicator`` false: package legs
are priced against each other and would distort sizes and times), that start within seven days
of execution (spot-starting, so the tenor is the swap's maturity), with a standard tenor within
0.15 years of 1, 2, 3, 5, 7, 10, 15, 20 or 30 years. Notionals ending in ``+`` are capped by the
CFTC dissemination caps; they are kept with ``capped = True`` and the cap as the value, and the
size fit treats them as right-censored rather than as exact.

Download and manifest: ``download`` writes zips to a directory outside the repository and
returns their paths; ``ratesresearch.provenance.write_manifest`` records name, size and sha256,
and the manifest (not the data) is committed under ``data/manifests/``.
"""

from __future__ import annotations

import urllib.request
import zipfile
from collections.abc import Iterable
from datetime import date
from pathlib import Path

import pandas as pd

URL = "https://kgc0418-tdw-data-0.s3.amazonaws.com/cftc/eod/CFTC_CUMULATIVE_RATES_{d}.zip"
LICENCE = (
    "DTCC public price dissemination data, published under CFTC Part 43; DTCC terms of use "
    "(https://www.dtcc.com/terms-of-use) apply. Raw files are not redistributed; only fitted "
    "parameters and a manifest are committed."
)
STANDARD_TENORS = (1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 15.0, 20.0, 30.0)


def url_for(day: date) -> str:
    return URL.format(d=day.strftime("%Y_%m_%d"))


def download(days: Iterable[date], out_dir: str | Path, timeout: float = 60.0) -> list[Path]:
    """Download the cumulative rates slice of each day into ``out_dir`` (outside the repo)."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for d in days:
        target = out / f"CFTC_CUMULATIVE_RATES_{d.strftime('%Y_%m_%d')}.zip"
        if not target.exists():
            with urllib.request.urlopen(url_for(d), timeout=timeout) as resp:  # noqa: S310
                target.write_bytes(resp.read())
        paths.append(target)
    return paths


def _read_csv(path: Path) -> pd.DataFrame:
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as z:
            name = z.namelist()[0]
            with z.open(name) as handle:
                return pd.read_csv(handle, dtype=str, keep_default_na=False)
    return pd.read_csv(path, dtype=str, keep_default_na=False)


def _notional(text: str) -> tuple[float, bool]:
    t = text.strip().replace(",", "")
    capped = t.endswith("+")
    t = t.rstrip("+")
    return (float(t) if t else float("nan")), capped


def load_sofr_ois(path: str | Path) -> pd.DataFrame:
    """Filtered SOFR OIS trades of one file: exec_time (UTC), tenor_years, notional_mm, capped."""
    df = _read_csv(Path(path))
    keep = (
        (df["Action type"] == "NEWT")
        & (df["Event type"] == "TRAD")
        & (df["Notional currency-Leg 1"] == "USD")
        & (df["UPI FISN"] == "NA/Swap OIS USD")
        & df["UPI Underlier Name"].str.contains("SOFR", case=False)
        & (df["Package indicator"].str.lower() == "false")
    )
    sub = df.loc[keep, ["Execution Timestamp", "Effective Date", "Expiration Date", "Notional amount-Leg 1"]]
    exec_time = pd.to_datetime(sub["Execution Timestamp"], utc=True, errors="coerce")
    eff = pd.to_datetime(sub["Effective Date"], errors="coerce")
    exp = pd.to_datetime(sub["Expiration Date"], errors="coerce")
    tenor = (exp - eff).dt.days / 365.25
    start_lag = (eff - exec_time.dt.tz_localize(None).dt.normalize()).dt.days
    parsed = sub["Notional amount-Leg 1"].map(_notional)
    out = pd.DataFrame(
        {
            "exec_time": exec_time,
            "tenor_years": tenor,
            "notional_mm": [p[0] / 1e6 for p in parsed],
            "capped": [p[1] for p in parsed],
            "start_lag_days": start_lag,
        }
    )
    out = out.dropna()
    out = out[(out["start_lag_days"] <= 7) & (out["notional_mm"] > 0)]
    nearest = out["tenor_years"].map(lambda t: min(STANDARD_TENORS, key=lambda s: abs(s - t)))
    out = out[(out["tenor_years"] - nearest).abs() <= 0.15].copy()
    out["tenor_bucket"] = nearest[out.index]
    return out.reset_index(drop=True)


def fixture_layout_columns() -> list[str]:
    """The DTCC columns the loader reads; a fixture in this layout is enough for tests."""
    return [
        "Action type",
        "Event type",
        "Execution Timestamp",
        "Effective Date",
        "Expiration Date",
        "Notional amount-Leg 1",
        "Notional currency-Leg 1",
        "Package indicator",
        "UPI FISN",
        "UPI Underlier Name",
    ]
