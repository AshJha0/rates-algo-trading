"""Back-test analysis: read the Java runner's outputs, reconcile the attribution, run the
bootstrap and tests, and render the daily report tables.

Inputs are the files ``io.ratesalgo.backtest.ResultsWriter`` writes (API_SPEC.md Section 16.9):
``fills.csv``, ``rfqs.csv``, ``pnl.csv``, ``summary.json``; and the ledger
``data/ledger/ledger.jsonl``. The statistics are ``ratesresearch.stats``, which reproduces the
Java ones exactly on ``data/fixtures/backtest/stats_crosscheck.json``.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pandas as pd

from ratesresearch import stats

CENT = 0.01
LINES = ("spread_capture", "inventory_mtm", "hedge_cost", "carry_roll", "funding", "fees")


@dataclass(frozen=True)
class Run:
    fills: pd.DataFrame
    rfqs: pd.DataFrame
    pnl: pd.DataFrame
    summary: dict[str, Any]


def load_run(directory: str | Path) -> Run:
    d = Path(directory)
    return Run(
        fills=pd.read_csv(d / "fills.csv"),
        rfqs=pd.read_csv(d / "rfqs.csv"),
        pnl=pd.read_csv(d / "pnl.csv"),
        summary=json.loads((d / "summary.json").read_text(encoding="utf-8")),
    )


def reconcile(run: Run) -> list[str]:
    """Independent checks of the attribution; returns the failures (empty when it reconciles).

    1. Each row: the lines plus the residual equal the position P&L, and the residual is below
       one cent.
    2. Spread capture per instrument equals the sum of client fill edges; hedge cost equals the
       sum of hedge fill edges; fees equal minus the sum of fill fees.
    3. The TOTAL row equals the sum of the instrument rows, and the summary's position P&L.
    """
    failures: list[str] = []
    pnl = run.pnl
    for _, row in pnl.iterrows():
        explained = sum(float(row[c]) for c in LINES)
        if abs(explained + float(row["residual"]) - float(row["position_pnl"])) > CENT:
            failures.append(f"{row['instrument']}: lines do not sum to position P&L")
        if abs(float(row["residual"])) >= CENT:
            failures.append(f"{row['instrument']}: residual {row['residual']} is a cent or more")
    rows = pnl[pnl["instrument"] != "TOTAL"]
    fills = run.fills
    for _, row in rows.iterrows():
        f = fills[fills["instrument_id"] == row["instrument_id"]]
        client = float(f.loc[f["kind"] == "client", "edge"].sum())
        hedge = float(f.loc[f["kind"] == "hedge", "edge"].sum())
        fees = -float(f["fee"].sum())
        for name, expected, actual in (
            ("spread_capture", client, row["spread_capture"]),
            ("hedge_cost", hedge, row["hedge_cost"]),
            ("fees", fees, row["fees"]),
        ):
            if abs(expected - float(actual)) > CENT:
                failures.append(f"{row['instrument']}: {name} {actual} but fills give {expected:.6f}")
    total = pnl[pnl["instrument"] == "TOTAL"].iloc[0]
    for c in (*LINES, "residual", "position_pnl"):
        if abs(float(rows[c].sum()) - float(total[c])) > CENT:
            failures.append(f"TOTAL {c} differs from the sum of instruments")
    if abs(float(total["position_pnl"]) - float(run.summary["attribution"]["position_pnl"])) > CENT:
        failures.append("summary position P&L differs from pnl.csv")
    return failures


def hit_rate_by_tier(run: Run) -> pd.DataFrame:
    r = run.rfqs
    g = r.groupby("tier").agg(rfqs=("rfq_id", "size"), quoted=("quoted", "sum"), won=("won", "sum"))
    g["hit_rate"] = (g["won"] / g["quoted"]).round(4)
    g["decline_rate"] = ((g["rfqs"] - g["quoted"]) / g["rfqs"]).round(4)
    return g.reset_index()


def pnl_by_product(run: Run) -> pd.DataFrame:
    return run.pnl[["instrument", *LINES, "residual", "position_pnl"]].round(2)


def to_markdown(df: pd.DataFrame) -> str:
    """A pipe table without the optional ``tabulate`` dependency."""
    cols = [str(c) for c in df.columns]
    lines = ["| " + " | ".join(cols) + " |", "|" + "|".join("---" for _ in cols) + "|"]
    for _, row in df.iterrows():
        cells = []
        for c in df.columns:
            v = row[c]
            cells.append(f"{v:.4f}".rstrip("0").rstrip(".") if isinstance(v, float) else str(v))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def daily_tables(run: Run) -> str:
    return (
        "## P&L by product and attribution line (USD)\n\n"
        + to_markdown(pnl_by_product(run))
        + "\n## Hit rate by client tier\n\n"
        + to_markdown(hit_rate_by_tier(run))
    )


def compare(
    treatment: list[float],
    baseline: list[float],
    block: int,
    resamples: int,
    level: float,
    seed: int,
) -> dict[str, float]:
    """Welch's test and the paired block-bootstrap interval of the daily difference."""
    w = stats.welch(treatment, baseline)
    diff = [a - b for a, b in zip(treatment, baseline, strict=True)]
    ci = stats.block_bootstrap_mean_ci(diff, block, resamples, level, seed)
    return {"mean_difference": w.difference, "t": w.t, "df": w.df, "p": w.p, "ci_low": ci.lower, "ci_high": ci.upper}


def recompute_experiment(record: dict[str, Any]) -> dict[str, dict[str, float]]:
    """Recompute every comparison of a ledger record from its daily P&L, with Holm."""
    res = record["results"]
    st = res["statistics"]
    raw: dict[str, dict[str, float]] = {}
    for point, band in res["band"].items():
        strategies = band["strategies"]
        for name in band["comparisons"]:
            treatment, baseline = name.split("_vs_")
            raw[f"{point}:{name}"] = compare(
                strategies[treatment]["daily_pnl"],
                strategies[baseline]["daily_pnl"],
                int(st["bootstrap_block_days"]),
                int(st["bootstrap_resamples"]),
                float(st["level"]),
                int(st["bootstrap_seed"]),
            )
    keys = list(raw)
    adjusted = stats.holm([raw[k]["p"] for k in keys])
    for k, a in zip(keys, adjusted, strict=True):
        raw[k]["holm_p"] = a
    return raw


def experiment_table(record: dict[str, Any]) -> pd.DataFrame:
    """One row per band point: means, difference, interval, p-values, verdict."""
    rows = []
    for point, band in record["results"]["band"].items():
        for name, c in band["comparisons"].items():
            treatment, baseline = name.split("_vs_")
            s = band["strategies"]
            rows.append(
                {
                    "band_point": point,
                    f"mean_{baseline}": s[baseline]["mean"],
                    f"mean_{treatment}": s[treatment]["mean"],
                    "mean_difference": c["mean_difference"],
                    "ci_low": c["paired_ci_low"],
                    "ci_high": c["paired_ci_high"],
                    "welch_p": c["welch_p"],
                    "holm_p": c["holm_p"],
                    "verdict": c["verdict"],
                }
            )
    return pd.DataFrame(rows)


CC_A = [1250.5, -300.25, 410.0, 980.75, -120.0, 660.5, 75.25, 1430.0, -510.5, 300.0]
CC_B = [400.0, -150.5, 210.25, 330.0, 90.75, -60.0, 120.5, 515.25, -220.0, 180.0]
CC_P = [0.04, 0.001, 0.30, 0.012, 0.049]
CC_BLOCK, CC_RESAMPLES, CC_LEVEL, CC_SEED, CC_SPLITMIX_SEED = 2, 500, 0.95, 11, 20261031


def crosscheck_input() -> dict[str, Any]:
    return {
        "a": CC_A,
        "b": CC_B,
        "p": CC_P,
        "block": CC_BLOCK,
        "resamples": CC_RESAMPLES,
        "level": CC_LEVEL,
        "seed": CC_SEED,
        "splitmix_seed": CC_SPLITMIX_SEED,
    }


def crosscheck_values() -> dict[str, Any]:
    """The statistics of the cross-check input computed in Python."""
    w = stats.welch(CC_A, CC_B)
    ci = stats.block_bootstrap_mean_ci(CC_A, CC_BLOCK, CC_RESAMPLES, CC_LEVEL, CC_SEED)
    rng = stats.SplitMix64(CC_SPLITMIX_SEED)
    longs = [format(rng.next_long(), "016x") for _ in range(4)]
    ints = [rng.next_int(10) for _ in range(6)]
    return {
        "welch": {"t": w.t, "df": w.df, "p": w.p},
        "bootstrap": {"estimate": ci.estimate, "lower": ci.lower, "upper": ci.upper},
        "holm": stats.holm(CC_P),
        "splitmix_longs_hex": longs,
        "splitmix_ints_bound_10": ints,
    }


def write_crosscheck(path: str | Path) -> None:
    payload = {"input": crosscheck_input(), "expected": crosscheck_values()}
    Path(path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
