"""COOKBOOK.md recipe 32: fit the hit-rate model at each point of the slope band."""

import copy
from pathlib import Path

from ratesresearch import hitrate, presets

root = Path(__file__).resolve().parents[2]
competition = presets.get_preset(root / "data/presets.yaml", "usd_rfq_competition")
band = competition["assumptions"]["spread_to_cover_bp"]["band"]

fits, tables = {}, {}
for point in ("low", "central", "high"):
    # simulate_labels draws with the band's central slope, so move that slot to this point.
    comp = copy.deepcopy(competition)
    comp["assumptions"]["spread_to_cover_bp"]["band"]["central"] = band[point]
    x, y = hitrate.simulate_labels(comp, 20_000)
    fits[point] = f = hitrate.fit(x, y)
    tables[point] = hitrate.calibration_table(x[f.n_fit :], y[f.n_fit :], f.beta, bins=5)

print(f"{'band point':<20}" + "".join(f"{p:>20}" for p in fits))
print(f"{'declared slope':<20}" + "".join(f"{band[p]:>20.2f}" for p in fits))
for j, name in enumerate(hitrate.FEATURES):
    cells = "".join(f"{f.beta[j]:>10.3f} +/- {f.se[j]:.3f}" for f in fits.values())
    print(f"{name:<20}{cells}")
for label, attr in (("oos log-loss model", "log_loss_model"), ("constant", "log_loss_constant")):
    print(f"{label:<20}" + "".join(f"{getattr(f, attr):>20.4f}" for f in fits.values()))

print("test-set calibration by quintile of predicted win probability (predicted / observed)")
for b in range(5):
    cells = "".join(f"{tables[p][b][0]:>12.3f} / {tables[p][b][1]:.3f}" for p in fits)
    print(f"{f'quintile {b + 1}':<20}{cells}")
