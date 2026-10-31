"""COOKBOOK.md recipe 33: markout curves by client tier from the fitting-period runs."""

from pathlib import Path

import pandas as pd

from ratesresearch import backtest_analysis as ba
from ratesresearch import markouts

root = Path(__file__).resolve().parents[2]
days = sorted((root / "data/fixtures/backtest/fitting").iterdir())
fills = pd.concat([ba.load_run(d).fills for d in days], ignore_index=True)
print("runs:", ", ".join(d.name for d in days))

by_tier = markouts.curves(fills, "tier")
print(f"{'tier':<6}{'fills':>6}" + "".join(f"{h:>9}" for h in markouts.HORIZONS))
for _, r in by_tier.iterrows():
    cells = "".join(f"{r[f'bp_{h}']:>9.4f}" for h in markouts.HORIZONS)
    print(f"{int(r['tier']):<6}{int(r['fills']):>6}{cells}")

share = markouts.informed_share(fills, "1min", by="tier")
print("informed share at 1min (2 * agreement - 1)")
for _, r in share.iterrows():
    tier, n = int(r["group"]), int(r["fills"])
    print(f"tier {tier}: {r['informed_share']:.3f} +/- {r['se']:.3f} ({n} fills)")
