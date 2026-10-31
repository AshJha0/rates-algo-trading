"""Tables for docs/research/PNL_AUDIT.md from the AUDIT ledger rows.

Reads every AUDIT-* evaluation row of data/ledger/ledger.jsonl (the hash chain is verified by
ratesresearch.prereg.read_ledger), recomputes each comparison from the recorded daily P&L with
ratesresearch.backtest_analysis.recompute_experiment, checks that the recomputation matches the
row, and prints Markdown tables. Run from the repository root:

    python research/audit/summarize.py > research/audit/results/summary.md
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research"))

from ratesresearch import backtest_analysis, prereg  # noqa: E402

POINTS = ("low", "central", "high")


def rows() -> list[dict[str, Any]]:
    entries = prereg.read_ledger(ROOT / "data/ledger/ledger.jsonl")
    return [
        e
        for e in entries
        if str(e.get("prereg_id", "")).startswith("AUDIT-") and e.get("scope") == "evaluation"
    ]


def check(row: dict[str, Any]) -> None:
    """Recompute the paired intervals and Holm p from the daily P&L; fail on any mismatch."""
    recomputed = backtest_analysis.recompute_experiment(row)
    for point, band in row["results"]["band"].items():
        for name, c in band["comparisons"].items():
            r = recomputed[f"{point}:{name}"]
            assert abs(r["holm_p"] - c["holm_p"]) < 1e-6, (row["prereg_id"], point, name)
            assert abs(round(r["ci_low"], 2) - c["paired_ci_low"]) < 0.02, (point, name)
            assert abs(round(r["ci_high"], 2) - c["paired_ci_high"]) < 0.02, (point, name)
    rc = row["results"]["replica_check"]
    assert rc["mismatches"] == 0, f"{row['prereg_id']}: engine does not reproduce model_spread"


def money(x: float) -> str:
    return f"{x:,.0f}"


def comparisons(row: dict[str, Any]) -> str:
    out = [
        f"### {row['prereg_id']} (ledger seq {row['seq']}, commit {row['code_commit'][:7]})",
        "",
        "| variant | band | mean diff USD/day | 95% CI | per 1mm DV01 traded | bp notional "
        "| Holm p | verdict |",
        "|---|---|---:|---|---:|---:|---:|---|",
    ]
    for point in POINTS:
        band = row["results"]["band"][point]
        for name, c in band["comparisons"].items():
            out.append(
                f"| {name.split('_vs_')[0]} | {point} | {money(c['mean_difference'])} "
                f"| {money(c['paired_ci_low'])} to {money(c['paired_ci_high'])} "
                f"| {money(c['per_mm_dv01_traded'])} "
                f"({money(c['per_mm_dv01_ci_low'])} to {money(c['per_mm_dv01_ci_high'])}) "
                f"| {c['bp_of_notional']:.4f} | {c['holm_p']:.4f} | {c['verdict']} |"
            )
    return "\n".join(out)


def strategies(row: dict[str, Any]) -> str:
    out = [
        "",
        "| variant | band | mean P&L USD/day | 95% CI | daily std | hit rate | client DV01/day "
        "| spread capture | hedge cost | inventory | fees |",
        "|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for point in POINTS:
        for name, s in row["results"]["band"][point]["strategies"].items():
            a = s["attribution_total"]
            n = len(s["daily_pnl"])
            out.append(
                f"| {name} | {point} | {money(s['mean'])} | {money(s['ci_low'])} to "
                f"{money(s['ci_high'])} | {money(s['daily_std'])} | {s['hit_rate']:.3f} "
                f"| {money(s['client_dv01_traded_per_day'])} | {money(a['spread_capture'] / n)} "
                f"| {money(a['hedge_cost'] / n)} | {money(a['inventory_mtm'] / n)} "
                f"| {money(a['fees'] / n)} |"
            )
    return "\n".join(out)


def diagnostics(row: dict[str, Any]) -> str:
    out = ["", f"Engine diagnostics ({row['prereg_id']}), per day:", ""]
    for point in POINTS:
        d = row["results"]["band"][point]["strategies"]["engine"]["diagnostics"]
        out.append(f"- {point}: " + ", ".join(f"{k} {v}" for k, v in d.items()))
    return "\n".join(out)


def main() -> None:
    found = rows()
    if not found:
        raise SystemExit("no AUDIT evaluation rows in the ledger")
    for row in found:
        check(row)
        print(comparisons(row))
        print(strategies(row))
        if row["prereg_id"] == "AUDIT-01":
            print(diagnostics(row))
        print()


if __name__ == "__main__":
    main()
