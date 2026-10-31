"""COOKBOOK.md recipe 19: reconcile a run's attribution to its position-based P&L."""

from pathlib import Path

from ratesresearch import backtest_analysis as ba

root = Path(__file__).resolve().parents[2]
run = ba.load_run(root / "data/fixtures/backtest/fitting/2026-01-05")
total = run.pnl[run.pnl["instrument"] == "TOTAL"].iloc[0]

explained = 0.0
for line in ba.LINES:
    explained += float(total[line])
    print(f"{line:<15} {float(total[line]):>16,.2f}")
print(f"{'explained':<15} {explained:>16,.2f}")
print(f"{'position_pnl':<15} {float(total['position_pnl']):>16,.2f}")
print(f"{'residual':<15} {float(total['residual']):>16,.6f}")

client = run.fills[run.fills["kind"] == "client"]
hedge = run.fills[run.fills["kind"] == "hedge"]
print(f"client fills {len(client)}, sum of edges {client['edge'].sum():,.2f}")
print(f"hedge fills {len(hedge)}, sum of edges {hedge['edge'].sum():,.2f}")
failures = ba.reconcile(run)
print("reconciles to the cent:", not failures)
