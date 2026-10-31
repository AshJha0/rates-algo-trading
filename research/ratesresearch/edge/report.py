"""Tables for the edge notebooks, and the check that a recomputation equals its ledger row."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pandas as pd

from ratesresearch import prereg


def ledger_row(root: Path, sid: str) -> dict[str, Any]:
    for e in prereg.read_ledger(root / "data/ledger/ledger.jsonl"):
        if e.get("prereg_id") == sid:
            return e
    raise KeyError(f"no ledger row for {sid}")


def tests_table(res: dict[str, Any], row: dict[str, Any]) -> pd.DataFrame:
    """Recomputed holdout tests next to the ledger's Holm p and outcome."""
    out = []
    for name, t in res["tests"].items():
        lt = row["tests"][name]
        out.append(
            {
                "test": name,
                "unit": t["unit"],
                "n": t["n"],
                "mean": t["mean"],
                "95% low": t["ci_low"],
                "95% high": t["ci_high"],
                "boot se": t["se"],
                "p": t["p"],
                "Holm p (ledger)": lt["holm_p"],
                "outcome (ledger)": lt["outcome"],
            }
        )
    return pd.DataFrame(out)


def check_against_ledger(res: dict[str, Any], row: dict[str, Any], rel: float = 1e-6) -> str:
    """Raise if the recomputed n, mean, interval or p differ from the ledger row."""
    for name, t in res["tests"].items():
        lt = row["tests"][name]
        if t["n"] != lt["n"]:
            raise AssertionError(f"{name}: n {t['n']} != ledger {lt['n']}")
        for k in ("mean", "ci_low", "ci_high", "p"):
            a, b = float(t[k]), float(lt[k])
            if abs(a - b) > rel * max(1.0, abs(b)):
                raise AssertionError(f"{name}: {k} {a} != ledger {b}")
    return (
        f"recomputed tests equal ledger seq {row['seq']} ({row['prereg_id']}, "
        f"verdict {row['verdict']})"
    )


def band_table(res: dict[str, Any], key: str, stat: str | None = None) -> pd.DataFrame:
    """Mean and interval of one statistic per band point and period."""
    rows = []
    for point, band in res["band"].items():
        for period in ("fitting", "holdout"):
            t = band[period][key] if stat is None else band[period][key][stat]
            rows.append(
                {
                    "band": point,
                    "period": period,
                    "n": t["n"],
                    "mean": t["mean"],
                    "95% low": t["ci_low"],
                    "95% high": t["ci_high"],
                    "p (unadjusted)": t["p"],
                }
            )
    return pd.DataFrame(rows)
