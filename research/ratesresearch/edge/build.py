"""Raw downloads (``data/raw/edge/``) to the derived fixtures in ``data/fixtures/edge/``.

Every fixture stops at ``READ_END`` (2026-06-30): the reserve dates of the pre-registrations
are dropped while parsing and never reach a fixture or a statistic. Each fixture has a
``.provenance.json`` naming its raw files (from the edge manifests), the transformation and the
row count.

Usage: ``python -m ratesresearch.edge.build [part ...]`` with parts from ``PARTS``.
"""

from __future__ import annotations

import json
import re
import sys
import zipfile
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ratesresearch.data import dtcc
from ratesresearch.edge import READ_END, common
from ratesresearch.edge.sources import MANIFESTS, RAW
from ratesresearch.provenance import sha256_of

OUT = Path("data/fixtures/edge")
PAR_TENORS = {"1 Mo": "m1", "1 Yr": "y1", "2 Yr": "y2", "3 Yr": "y3", "5 Yr": "y5",
              "7 Yr": "y7", "10 Yr": "y10", "20 Yr": "y20", "30 Yr": "y30"}  # fmt: skip
GSW_TENORS = (2, 3, 5, 7, 10, 20, 30)
AUCTION_TENORS = (2, 3, 5, 7, 10, 20, 30)
RV_TENORS = (2.0, 5.0, 10.0, 30.0)
SIZE_TENORS = (2.0, 5.0, 10.0, 30.0)
ET = "America/New_York"
BUCKET_START_MIN = 7 * 60
BUCKETS = 120
END = date.fromisoformat(READ_END)
ALFRED_SERIES = {"PAYEMS": "employment_situation", "CPIAUCSL": "cpi", "GDPC1": "gdp",
                 "PCEPI": "personal_income_outlays"}  # fmt: skip


def _manifest_files(source: str, names: set[str] | None = None) -> list[dict[str, Any]]:
    m = json.loads((MANIFESTS / f"edge_{source}.json").read_text(encoding="utf-8"))
    files: list[dict[str, Any]] = m["files"]
    return [f for f in files if names is None or f["name"] in names]


def _write(name: str, df: pd.DataFrame, sources: list[str], transformation: str) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    df.to_csv(path, index=False, lineterminator="\n", float_format="%.10g")
    raw: list[dict[str, Any]] = []
    for s in sources:
        raw.extend(
            {k: f[k] for k in ("name", "url", "downloaded_utc", "sha256", "size")}
            for f in _manifest_files(s)
        )
    prov = {
        "fixture": name,
        "created_utc": datetime.now(UTC).isoformat(timespec="seconds"),
        "sha256": sha256_of(path),
        "size": path.stat().st_size,
        "rows": len(df),
        "columns": list(df.columns),
        "read_end": READ_END,
        "manifests": [f"data/manifests/edge_{s}.json" for s in sources],
        "raw_file_count": len(raw),
        "raw_files": raw if len(raw) <= 30 else "listed in the manifests",
        "transformation": transformation,
        "produced_by": "ratesresearch.edge.build",
    }
    path.with_suffix(".provenance.json").write_text(
        json.dumps(prov, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    return path


# ------------------------------------------------------------------ daily curves and auctions


def build_par_curve() -> Path:
    frames = []
    for y in range(2015, 2027):
        df = pd.read_csv(RAW / "par_curve" / f"par_curve_{y}.csv", dtype=str)
        df["date"] = pd.to_datetime(df["Date"], format="%m/%d/%Y").dt.date
        keep = df[["date", *[c for c in PAR_TENORS if c in df.columns]]].rename(columns=PAR_TENORS)
        frames.append(keep)
    out = pd.concat(frames).sort_values("date")
    out = out[(out["date"] >= date(2015, 1, 1)) & (out["date"] <= END)]
    for c in PAR_TENORS.values():
        out[c] = pd.to_numeric(out[c], errors="coerce")
    out = out.dropna(subset=[c for c in PAR_TENORS.values() if c != "m1"])
    out["date"] = out["date"].astype(str)
    return _write(
        "par_curve.csv",
        out.reset_index(drop=True),
        ["par_curve"],
        "Treasury daily par yield curve, percent; columns 1 Mo, 1, 2, 3, 5, 7, 10, 20, 30 Yr; "
        f"2015-01-02 to {READ_END}; rows with a missing coupon tenor dropped.",
    )


def build_gsw() -> Path:
    path = RAW / "gsw" / "feds200628.csv"
    lines = path.read_text(encoding="utf-8").splitlines()
    head = next(i for i, x in enumerate(lines) if x.startswith("Date,"))
    df = pd.read_csv(path, skiprows=head, dtype=str)
    cols = [f"SVENPY{t:02d}" for t in GSW_TENORS]
    out = df[["Date", *cols]].rename(columns={"Date": "date"})
    out = out[(out["date"] >= "2015-01-01") & (out["date"] <= READ_END)]
    for c in cols:
        out[c] = pd.to_numeric(out[c], errors="coerce")
    out = out.dropna().rename(columns={c: f"g{int(c[-2:])}" for c in cols})
    return _write(
        "gsw_par.csv",
        out.reset_index(drop=True),
        ["gsw"],
        "Federal Reserve feds200628 (Gurkaynak, Sack and Wright) fitted par yields SVENPY02 to "
        f"SVENPY30 at 2, 3, 5, 7, 10, 20, 30 years, percent; 2015-01-02 to {READ_END}.",
    )


def _tenor_from_term(term: str) -> int | None:
    m = re.match(r"(\d+)-Year(?: (\d+)-Month)?", term)
    if not m:
        return None
    years = int(m.group(1)) + (int(m.group(2)) / 12 if m.group(2) else 0)
    best = min(AUCTION_TENORS, key=lambda t: abs(t - years))
    return best if abs(best - years) <= 0.5 else None


def build_auctions() -> Path:
    rows = []
    for f in sorted((RAW / "auctions").glob("td_*.json")):
        for r in json.loads(f.read_text(encoding="utf-8")):
            if r.get("type") not in ("Note", "Bond"):
                continue
            if r.get("floatingRate") == "Yes" or r.get("inflationIndexSecurity") == "Yes":
                continue
            tenor = _tenor_from_term(str(r.get("securityTerm", "")))
            day = (r.get("auctionDate") or r.get("competitiveAuctionCloseDate") or "")[:10]
            if tenor is None or not day:
                continue
            rows.append(
                {
                    "cusip": r["cusip"],
                    "auction_date": day,
                    "issue_date": str(r["issueDate"])[:10],
                    "term": r["securityTerm"],
                    "tenor": tenor,
                    "reopening": r.get("reopening", ""),
                    "offering_bn": float(r.get("offeringAmount") or 0) / 1e9,
                }
            )
    df = pd.DataFrame(rows).drop_duplicates(["cusip", "auction_date"])
    df = df[(df["auction_date"] >= "2014-12-01") & (df["auction_date"] <= READ_END)]
    df = df[df["issue_date"] <= READ_END].sort_values(["auction_date", "tenor"])
    return _write(
        "auctions.csv",
        df.reset_index(drop=True),
        ["auctions"],
        "TreasuryDirect auction records (Note and Bond searches by issue date 2014-12-01 to "
        "2026-07-31), nominal fixed coupons only (TIPS and FRNs dropped), tenor = securityTerm "
        "rounded to 2, 3, 5, 7, 10, 20 or 30 years, auction day from auctionDate or "
        f"competitiveAuctionCloseDate; auction and issue dates up to {READ_END}.",
    )


# ------------------------------------------------------------------ calendars and positioning


def build_calendar() -> Path:
    rows = []
    for series, label in ALFRED_SERIES.items():
        text = (RAW / "alfred" / f"alfred_{series}.htm").read_text(encoding="utf-8")
        for d in sorted(set(re.findall(r'<option[^>]*value="(\d{4}-\d\d-\d\d)"', text))):
            day = date.fromisoformat(d)
            if date(2024, 10, 1) <= day <= END and day.weekday() < 5:
                rows.append({"date": d, "event": label, "time_et": "08:30"})
    html = (RAW / "fomc" / "fomccalendars.htm").read_text(encoding="utf-8")
    months = {m: i for i, m in enumerate(
        ["January", "February", "March", "April", "May", "June", "July", "August",
         "September", "October", "November", "December"], 1)}  # fmt: skip
    for y in (2024, 2025, 2026):
        i = html.find(f"{y} FOMC Meetings")
        seg = html[i : html.find("FOMC Meetings", i + 20)]
        pat = (
            r"fomc-meeting__month[^>]*>\s*<strong>([^<]+)</strong>"
            r".*?fomc-meeting__date[^>]*>([^<]+)<"
        )
        for month, days in re.findall(pat, seg, re.S):
            if "notation" in days.lower() or "unscheduled" in days.lower():
                continue
            last_month = month.split("/")[-1]
            last_month = {"Apr": "April", "Jan": "January", "Oct": "October"}.get(
                last_month, last_month
            )
            dom = int(re.findall(r"\d+", days)[-1])
            d = date(y, months[last_month], dom)
            if d <= END:
                rows.append({"date": d.isoformat(), "event": "fomc", "time_et": "14:00"})
    df = pd.DataFrame(rows).drop_duplicates().sort_values(["date", "event"])
    return _write(
        "calendar.csv",
        df.reset_index(drop=True),
        ["alfred", "fomc"],
        "Release days 2024-10-01 to READ_END: ALFRED vintage dates (weekdays) of PAYEMS "
        "(Employment Situation), CPIAUCSL (CPI), GDPC1 (GDP) and PCEPI (Personal Income and "
        "Outlays), 08:30 ET; FOMC statement days (last meeting day, notation votes dropped) from "
        "the Federal Reserve FOMC calendars page, 14:00 ET.",
    )


def build_cot() -> Path:
    frames = []
    for y in range(2023, 2027):
        with zipfile.ZipFile(RAW / "cot" / f"fut_fin_txt_{y}.zip") as z:
            df = pd.read_csv(z.open(z.namelist()[0]), low_memory=False)
        m = df[df["CFTC_Contract_Market_Code"].astype(str).str.strip() == "043602"]
        frames.append(
            pd.DataFrame(
                {
                    "report_date": pd.to_datetime(m["Report_Date_as_YYYY-MM-DD"]).dt.date.astype(
                        str
                    ),
                    "open_interest": m["Open_Interest_All"].astype(float),
                    "lev_long": m["Lev_Money_Positions_Long_All"].astype(float),
                    "lev_short": m["Lev_Money_Positions_Short_All"].astype(float),
                    "am_long": m["Asset_Mgr_Positions_Long_All"].astype(float),
                    "am_short": m["Asset_Mgr_Positions_Short_All"].astype(float),
                }
            )
        )
    out = pd.concat(frames).drop_duplicates("report_date").sort_values("report_date")
    out = out[out["report_date"] <= READ_END]
    return _write(
        "cot_ust10y.csv",
        out.reset_index(drop=True),
        ["cot"],
        "CFTC Traders in Financial Futures, futures only, UST 10Y NOTE (code 043602): open "
        "interest, leveraged-funds and asset-manager long and short contracts, report (Tuesday) "
        f"dates 2023 to {READ_END}.",
    )


# ------------------------------------------------------------------ DTCC swap prints


def parse_dtcc(path: Path) -> pd.DataFrame:
    """Filtered SOFR OIS prints of one file with the fixed rate (percent) and New York time."""
    with zipfile.ZipFile(path) as z:
        df = pd.read_csv(z.open(z.namelist()[0]), dtype=str, keep_default_na=False)
    keep = (
        (df["Action type"] == "NEWT")
        & (df["Event type"] == "TRAD")
        & (df["Notional currency-Leg 1"] == "USD")
        & (df["UPI FISN"] == "NA/Swap OIS USD")
        & df["UPI Underlier Name"].str.contains("SOFR", case=False)
        & (df["Package indicator"].str.lower() == "false")
    )
    sub = df.loc[keep]
    exec_time = pd.to_datetime(sub["Execution Timestamp"], utc=True, errors="coerce")
    eff = pd.to_datetime(sub["Effective Date"], errors="coerce")
    exp = pd.to_datetime(sub["Expiration Date"], errors="coerce")
    tenor = (exp - eff).dt.days / 365.25
    start_lag = (eff - exec_time.dt.tz_localize(None).dt.normalize()).dt.days
    parsed = sub["Notional amount-Leg 1"].map(dtcc._notional)
    leg1 = sub["Fixed rate-Leg 1"]
    fixed = pd.to_numeric(leg1.where(leg1 != "", sub["Fixed rate-Leg 2"]), errors="coerce")
    out = pd.DataFrame(
        {
            "t": exec_time.dt.tz_convert(ET),
            "tenor_years": tenor,
            "notional_mm": [p[0] / 1e6 for p in parsed],
            "capped": [p[1] for p in parsed],
            "rate": fixed * 100.0,
            "start_lag": start_lag,
        }
    ).dropna()
    out = out[(out["start_lag"] <= 7) & (out["notional_mm"] > 0)]
    nearest = out["tenor_years"].map(lambda t: min(dtcc.STANDARD_TENORS, key=lambda s: abs(s - t)))
    out = out[(out["tenor_years"] - nearest).abs() <= 0.15].copy()
    out["tenor"] = nearest[out.index]
    day = date.fromisoformat(path.stem[-10:].replace("_", "-"))
    out = out[out["t"].dt.date == day]
    result: pd.DataFrame = out[["t", "tenor", "notional_mm", "capped", "rate"]].sort_values("t")
    return result


def dtcc_trades() -> dict[str, pd.DataFrame]:
    """Parsed prints by ISO day, cached as pickles under data/raw/edge/dtcc_parsed."""
    cache = RAW / "dtcc_parsed"
    cache.mkdir(parents=True, exist_ok=True)
    # business days are the par-curve dates (Treasury market open); SIFMA holidays drop out
    curve_days = set(pd.read_csv(OUT / "par_curve.csv", usecols=["date"])["date"].astype(str))
    out: dict[str, pd.DataFrame] = {}
    for f in sorted((RAW / "dtcc").glob("CFTC_CUMULATIVE_RATES_*.zip")):
        iso = f.stem[-10:].replace("_", "-")
        if iso > READ_END or iso not in curve_days:
            continue
        c = cache / f"{iso}.pkl"
        if c.exists():
            out[iso] = pd.read_pickle(c)
        else:
            out[iso] = parse_dtcc(f)
            out[iso].to_pickle(c)
    return out


def _bucket(t: pd.Series) -> np.ndarray:
    minutes = t.dt.hour * 60 + t.dt.minute
    b = (minutes - BUCKET_START_MIN) // 5
    return np.where((b >= 0) & (b < BUCKETS), b, -1)


def day_buckets(tr: pd.DataFrame) -> pd.DataFrame:
    """Per 5-minute bucket: trade count (all tenors), RV (mean over 2, 5, 10, 30 of sum d^2),
    and per-tenor counts for the four size tenors."""
    n = np.zeros(BUCKETS)
    rv = np.zeros(BUCKETS)
    per = {k: np.zeros(BUCKETS) for k in SIZE_TENORS}
    b_all = _bucket(tr["t"])
    for b in b_all[b_all >= 0]:
        n[b] += 1
    for k in RV_TENORS:
        s = tr[tr["tenor"] == k]
        if len(s) == 0:
            continue
        b = _bucket(s["t"])
        for x in b[b >= 0]:
            per[k][x] += 1
        if len(s) < 2:
            continue
        d = np.diff(s["rate"].to_numpy()) * 100.0
        gap = np.diff(common.epoch_seconds(s["t"]))
        ok = (gap <= 1800) & (np.abs(d) <= 10.0)
        bi = b[1:]
        for x, v, good in zip(bi, d, ok, strict=True):
            if good and x >= 0:
                rv[x] += v * v / len(RV_TENORS)
    data = {"bucket": np.arange(BUCKETS), "n": n, "rv": rv}
    for k in SIZE_TENORS:
        data[f"n{int(k)}"] = per[k]
    return pd.DataFrame(data)


def build_dtcc() -> list[Path]:
    trades = dtcc_trades()
    bucket_frames = []
    size_frames = []
    ois_rows = []
    for iso, tr in trades.items():
        db = day_buckets(tr)
        db.insert(0, "date", iso)
        bucket_frames.append(db)
        s = tr[tr["tenor"].isin(SIZE_TENORS)]
        vc = s.groupby(["tenor", "notional_mm", "capped"]).size().reset_index(name="count")
        vc.insert(0, "date", iso)
        size_frames.append(vc)
        ten = tr[tr["tenor"] == 10.0]
        mins = ten["t"].dt.hour * 60 + ten["t"].dt.minute
        row: dict[str, Any] = {"date": iso}
        for band, (a, b) in {"low": (900, 930), "central": (870, 960), "high": (0, 1440)}.items():
            w = ten[(mins >= a) & (mins < b)]
            row[f"ois10_{band}"] = float(w["rate"].median()) if len(w) >= 5 else np.nan
            row[f"n_{band}"] = len(w)
        ois_rows.append(row)
    buckets = pd.concat(bucket_frames)
    buckets = buckets[(buckets["n"] > 0) | (buckets["rv"] > 0)]
    sizes = pd.concat(size_frames)
    sizes["tenor"] = sizes["tenor"].astype(int)
    srcs = ["dtcc"]
    filt = (
        "DTCC CFTC cumulative rates slices; trades NEWT/TRAD, USD SOFR OIS, not a package, "
        "spot-starting (effective within 7 days), standard tenor within 0.15 years, executed "
        f"(New York time) on the file's day; days up to {READ_END}. "
    )
    return [
        _write(
            "dtcc_buckets.csv",
            buckets.reset_index(drop=True),
            srcs,
            filt + "Per day and 5-minute bucket from 07:00 ET (bucket 0) to 17:00 ET (119): n = "
            "trades, rv = mean over 2, 5, 10, 30Y of the summed squared print-to-print rate "
            "changes in bp (gap <= 30 min, |change| <= 10 bp), n2/n5/n10/n30 = trades per "
            "tenor. Buckets with no trade and no RV are omitted (zero).",
        ),
        _write(
            "dtcc_sizes.csv",
            sizes.reset_index(drop=True),
            srcs,
            filt + "Per day, tenor (2, 5, 10, 30Y), notional (USD mm) and capped flag: count.",
        ),
        _write(
            "dtcc_ois10.csv",
            pd.DataFrame(ois_rows),
            srcs,
            filt + "Per day, median fixed rate (percent) of 10Y prints in 15:00-15:30 (low), "
            "14:30-16:00 (central) and the whole day (high), New York time; NaN when fewer than "
            "5 prints; n_* = print counts.",
        ),
    ]


def build_cluster(fits: dict[str, dict[float, dict[str, float]]]) -> Path:
    """E4b day statistics: excess same-tenor trades in the 5 minutes after a large trade."""
    from ratesresearch.edge import e4_size

    trades = dtcc_trades()
    rows = e4_size.cluster_rows(trades, fits)
    return _write(
        "dtcc_cluster.csv",
        pd.DataFrame(rows),
        ["dtcc"],
        "Per day and band point: large trades (notional at or above the fitting-period p90 of "
        "the tenor's censored lognormal for that band) in 2, 5, 10, 30Y between 07:00 and 17:00 "
        "ET; n_large and the sum over them of (same-tenor trades in the next 300 s minus the "
        "period mean count of that tenor in the large trade's 5-minute bucket).",
    )


def build_cluster_from_fixture() -> Path:
    from ratesresearch.edge import e4_size

    return build_cluster(e4_size.fits(e4_size.load_sizes(Path(".")), e4_size.FIT))


PARTS = {
    "par_curve": build_par_curve,
    "gsw": build_gsw,
    "auctions": build_auctions,
    "calendar": build_calendar,
    "cot": build_cot,
    "dtcc": build_dtcc,
    "cluster": build_cluster_from_fixture,
}


def main(argv: list[str]) -> int:
    for part in argv or list(PARTS):
        res = PARTS[part]()
        print(part, res, flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
