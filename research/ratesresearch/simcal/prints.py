"""DTCC SOFR OIS prints for the simulator calibration: filters with counts, merge and cache.

The filters are the ones fixed in research/prereg/SIM3-CAL-001.yaml. ``parse_file`` returns
the kept prints of one end-of-day file together with a count of the rows left after each
filter, in the order the filters are applied, so the document can show the funnel.

Kept columns (one row per merged trade): ``day`` (ISO date of the file), ``t`` (seconds after
New York midnight), ``tenor`` (years, one of DESK_TENORS), ``rate_bp`` (fixed rate in bp, as
printed: annual Act/360), ``notional_mm``, ``capped``, ``dv01`` (USD per bp), ``bucket``
(size bucket 0..3), ``group`` (platform group 0..2), ``block``, ``platform``, ``lag_days``
(effective date minus execution date) and ``tenor_dev`` (maturity minus the standard tenor, in
years); the last two allow the stricter "spot, exact tenor" subset used as a sensitivity.
"""

from __future__ import annotations

import pickle
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch.simcal import DESK_TENORS

FloatArray = npt.NDArray[np.float64]

SESSION_START_S = 7 * 3600
SESSION_END_S = 17 * 3600
TENOR_TOLERANCE = 0.15
OFF_MARKET_BP = 5.0
OFF_MARKET_NEIGHBOURS = 20
BUCKET_EDGES_DV01 = (5_000.0, 25_000.0)
BUCKET_NAMES = ("under_5k", "5k_to_25k", "25k_plus", "capped")
# Platform identifier (ISO 10383 MIC of the swap execution facility) to a coarse group. The
# dealer-to-client request-for-quote facilities are Tradeweb (TWSF) and Bloomberg (BBSF); the
# other codes are interdealer or all-to-all order-book and voice facilities. XXXX and blank are
# off-facility. The grouping is by facility, not by counterparty type: the file does not say
# who traded, and the D2C facilities also carry dealer hedges.
GROUP_NAMES = ("d2c_sef", "other_sef", "off_facility")
D2C_SEFS = frozenset({"TWSF", "BBSF"})
OFF_FACILITY = frozenset({"XXXX", "XOFF", ""})

COLUMNS = (
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
    "Fixed rate-Leg 1",
    "Fixed rate-Leg 2",
    "Other payment amount",
    "Non-standardized term indicator",
    "Spread-Leg 1",
    "Spread-Leg 2",
    "Embedded Option type",
    "Platform identifier",
    "Block trade election indicator",
)
FILTER_ORDER = (
    "rows_in_file",
    "new_trades",
    "usd_sofr_ois",
    "not_package",
    "one_numeric_fixed_rate",
    "no_upfront_payment",
    "standard_terms_no_spread_no_option",
    "spot_start_0_to_7_days",
    "desk_tenor",
    "positive_notional",
    "session_07_17_ny_on_file_day",
    "within_off_market_band",
    "after_merge",
)


def _read(path: Path) -> pd.DataFrame:
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as z, z.open(z.namelist()[0]) as handle:
            df = pd.read_csv(handle, dtype=str, keep_default_na=False)
    else:
        df = pd.read_csv(path, dtype=str, keep_default_na=False)
    for col in COLUMNS:
        if col not in df.columns:
            df[col] = ""
    return df


def _to_float(text: pd.Series[str]) -> FloatArray:
    cleaned = text.str.replace(",", "", regex=False).str.rstrip("+").str.strip()
    out: FloatArray = pd.to_numeric(cleaned, errors="coerce").to_numpy(dtype=np.float64)
    return out


def annuity(rate_bp: FloatArray, tenor: FloatArray) -> FloatArray:
    """Par annuity of an annual-pay swap at a flat rate: (1 - (1 + r)^-T) / r, in years."""
    r = np.maximum(rate_bp, 1.0) * 1e-4
    out: FloatArray = (1.0 - (1.0 + r) ** (-tenor)) / r
    return out


def size_bucket(dv01: FloatArray, capped: npt.NDArray[np.bool_]) -> npt.NDArray[np.int64]:
    b = np.digitize(dv01, BUCKET_EDGES_DV01).astype(np.int64)
    b[capped] = 3
    return b


def centred_median_deviation(rate: FloatArray, k: int = OFF_MARKET_NEIGHBOURS) -> FloatArray:
    """Each value minus the median of its ``k`` nearest neighbours in order (itself excluded)."""
    n = len(rate)
    out = np.zeros(n)
    if n < 3:
        return out
    half = k // 2
    for i in range(n):
        lo = max(0, i - half)
        hi = min(n, i + half + 1)
        if hi - lo - 1 < k and n > k:
            if lo == 0:
                hi = k + 1
            else:
                lo = n - k - 1
        others = np.concatenate([rate[lo:i], rate[i + 1 : hi]])
        out[i] = rate[i] - float(np.median(others))
    return out


def parse_file(
    path: str | Path, day: str, off_market_bp: float = OFF_MARKET_BP
) -> tuple[pd.DataFrame, dict[str, int]]:
    """Kept prints of one file and the count left after each filter (keys of FILTER_ORDER)."""
    df = _read(Path(path))
    counts: dict[str, int] = {"rows_in_file": len(df)}

    def keep(mask: Any, name: str) -> None:
        nonlocal df
        df = df.loc[np.asarray(mask, dtype=bool)]
        counts[name] = len(df)

    keep((df["Action type"] == "NEWT") & (df["Event type"] == "TRAD"), "new_trades")
    keep(
        (df["Notional currency-Leg 1"] == "USD")
        & (df["UPI FISN"] == "NA/Swap OIS USD")
        & df["UPI Underlier Name"].str.contains("SOFR", case=False),
        "usd_sofr_ois",
    )
    keep(df["Package indicator"].str.lower() == "false", "not_package")
    f1 = _to_float(df["Fixed rate-Leg 1"])
    f2 = _to_float(df["Fixed rate-Leg 2"])
    one = np.isfinite(f1) ^ np.isfinite(f2)
    df = df.assign(rate_bp=np.where(np.isfinite(f1), f1, f2) * 1e4)
    keep(one & (df["rate_bp"].to_numpy(dtype=np.float64) > 0.0), "one_numeric_fixed_rate")
    other = _to_float(df["Other payment amount"])
    keep(~(np.isfinite(other) & (other != 0.0)), "no_upfront_payment")
    s1 = _to_float(df["Spread-Leg 1"])
    s2 = _to_float(df["Spread-Leg 2"])
    spread = (np.isfinite(s1) & (s1 != 0.0)) | (np.isfinite(s2) & (s2 != 0.0))
    keep(
        (df["Non-standardized term indicator"].str.upper() != "TRUE").to_numpy()
        & ~spread
        & (df["Embedded Option type"] == "").to_numpy(),
        "standard_terms_no_spread_no_option",
    )
    exec_utc = pd.to_datetime(df["Execution Timestamp"], utc=True, errors="coerce")
    ny = exec_utc.dt.tz_convert("America/New_York")
    eff = pd.to_datetime(df["Effective Date"], errors="coerce")
    exp = pd.to_datetime(df["Expiration Date"], errors="coerce")
    lag = (eff - ny.dt.tz_localize(None).dt.normalize()).dt.days
    tenor_raw = (exp - eff).dt.days / 365.25
    df = df.assign(
        t=(ny.dt.hour * 3600 + ny.dt.minute * 60 + ny.dt.second).astype("float64"),
        ny_day=ny.dt.strftime("%Y-%m-%d"),
        tenor_raw=tenor_raw,
        lag_days=lag,
    )
    keep((lag >= 0) & (lag <= 7) & tenor_raw.notna() & exec_utc.notna(), "spot_start_0_to_7_days")
    grid = np.asarray(DESK_TENORS)
    raw = df["tenor_raw"].to_numpy(dtype=np.float64)
    nearest = grid[np.abs(raw[:, None] - grid[None, :]).argmin(axis=1)]
    df = df.assign(tenor=nearest, tenor_dev=raw - nearest)
    keep(np.abs(raw - nearest) <= TENOR_TOLERANCE, "desk_tenor")
    df = df.assign(
        notional_mm=_to_float(df["Notional amount-Leg 1"]) / 1e6,
        capped=df["Notional amount-Leg 1"].str.strip().str.endswith("+"),
    )
    keep(df["notional_mm"].to_numpy(dtype=np.float64) > 0.0, "positive_notional")
    keep(
        (df["t"] >= SESSION_START_S) & (df["t"] < SESSION_END_S) & (df["ny_day"] == day),
        "session_07_17_ny_on_file_day",
    )
    df = df.sort_values(["tenor", "t"], kind="stable")
    dev = np.zeros(len(df))
    tenors = df["tenor"].to_numpy(dtype=np.float64)
    rates = df["rate_bp"].to_numpy(dtype=np.float64)
    for tn in np.unique(tenors):
        m = tenors == tn
        dev[m] = centred_median_deviation(rates[m])
    counts["off_market_3bp"] = int((np.abs(dev) <= 3.0).sum())
    counts["off_market_10bp"] = int((np.abs(dev) <= 10.0).sum())
    keep(np.abs(dev) <= off_market_bp, "within_off_market_band")
    platform = df["Platform identifier"].str.strip().str.upper()
    group = np.where(platform.isin(D2C_SEFS), 0, np.where(platform.isin(OFF_FACILITY), 2, 1))
    df = df.assign(
        group=group,
        block=df["Block trade election indicator"].str.lower() == "true",
        platform=platform,
    )
    merged = (
        df.groupby(["tenor", "t", "rate_bp"], sort=True)
        .agg(
            notional_mm=("notional_mm", "sum"),
            capped=("capped", "max"),
            group=("group", "min"),
            block=("block", "max"),
            platform=("platform", "first"),
            lag_days=("lag_days", "first"),
            tenor_dev=("tenor_dev", "first"),
        )
        .reset_index()
    )
    counts["after_merge"] = len(merged)
    dv01 = (
        merged["notional_mm"].to_numpy(dtype=np.float64)
        * 1e6
        * annuity(
            merged["rate_bp"].to_numpy(dtype=np.float64),
            merged["tenor"].to_numpy(dtype=np.float64),
        )
        * 1e-4
    )
    capped = merged["capped"].to_numpy(dtype=bool)
    merged = merged.assign(day=day, dv01=dv01, bucket=size_bucket(dv01, capped))
    return merged, counts


def platform_counts(prints: pd.DataFrame) -> dict[str, int]:
    return dict(Counter(prints["platform"].tolist()).most_common())


def day_of(path: Path) -> str:
    return path.stem.replace("CFTC_CUMULATIVE_RATES_", "").replace("_", "-")


def build_cache(raw_dir: str | Path, cache_dir: str | Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Parse every zip in ``raw_dir`` (one pickle per day in ``cache_dir``): prints and funnel."""
    cache = Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    frames: list[pd.DataFrame] = []
    funnel: list[dict[str, Any]] = []
    for f in sorted(Path(raw_dir).glob("CFTC_CUMULATIVE_RATES_*.zip")):
        day = day_of(f)
        pk = cache / f"{day}.pkl"
        if pk.exists():
            with pk.open("rb") as handle:
                prints, counts = pickle.load(handle)
        else:
            prints, counts = parse_file(f, day)
            with pk.open("wb") as handle:
                pickle.dump((prints, counts), handle)
        frames.append(prints)
        funnel.append({"day": day, **counts})
    return pd.concat(frames, ignore_index=True), pd.DataFrame(funnel)


def load(
    raw_dir: str | Path, cache_dir: str | Path, start: str, end: str
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Prints and funnel of file days in [start, end] (the split of SIM3-CAL-001)."""
    prints, funnel = build_cache(raw_dir, cache_dir)
    keep = (prints["day"] >= start) & (prints["day"] <= end)
    fk = (funnel["day"] >= start) & (funnel["day"] <= end)
    return prints.loc[keep].reset_index(drop=True), funnel.loc[fk].reset_index(drop=True)
