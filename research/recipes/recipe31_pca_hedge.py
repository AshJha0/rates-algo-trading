"""COOKBOOK.md recipe 31: PCA hedge ratios from the Treasury par curve fixture."""

from pathlib import Path

import numpy as np

from ratesresearch import curves

root = Path(__file__).resolve().parents[2]
dates, tenors, rates = curves.load_par_curve(root / "data/fixtures/ust_par_curve_2015_2025.csv")
changes = curves.daily_changes(rates)
result = curves.pca(changes, n_components=3)
print(f"{len(changes)} daily changes, {dates[0]} to {dates[-1]}")

print(f"{'':<6}" + "".join(f"{t:>8}" for t in tenors) + f"{'explained':>11}")
for k, name in enumerate(("PC1", "PC2", "PC3")):
    row = "".join(f"{v:>8.3f}" for v in result.loadings[k])
    print(f"{name:<6}{row}{result.explained_variance_ratio[k]:>11.4f}")

i10, i5 = tenors.index("10 Yr"), tenors.index("5 Yr")
for n in (1, 2, 3):
    h = curves.pca_hedge_ratio(result, i10, i5, n_factors=n)
    print(f"10y hedged with 5y alone, {n} factor(s): {h:.4f}")

# Hedge one unit of 10y with 2y, 5y and 30y so that PC1, PC2 and PC3 all net to zero.
legs = [tenors.index(t) for t in ("2 Yr", "5 Yr", "30 Yr")]
weights = np.linalg.solve(result.loadings[:, legs], result.loadings[:, i10])
for t, w in zip(("2y", "5y", "30y"), weights, strict=True):
    print(f"hedge leg {t:<4} {w:>8.4f}")
residual = changes[:, i10] - changes[:, legs] @ weights
print(f"10y daily sd {changes[:, i10].std(ddof=1):.3f} bp, hedged {residual.std(ddof=1):.3f} bp")
