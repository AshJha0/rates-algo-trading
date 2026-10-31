# Weekly back-test review: week of YYYY-MM-DD

Template for the weekly review of the daily reports (`ReportCli`, API_SPEC.md Section 16.9). Copy it to the review's own file, fill every section from the week's `report.json` files and the ledger, and name the file and job behind each number. Results are simulated; every P&L figure is given at the low, central and high band points (DR-008).

## 1. Runs covered

| Session date | Journal SHA-256 or chain hash | Strategy | Report directory |
|---|---|---|---|
| | | | |

## 2. P&L by attribution line, week total (USD)

| Line | Low | Central | High |
|---|---|---|---|
| spread_capture | | | |
| inventory_mtm | | | |
| hedge_cost | | | |
| carry_roll (zero until P1) | | | |
| funding | | | |
| fees | | | |
| residual (each day below one cent: yes or no) | | | |
| position_pnl | | | |

## 3. Clients and flow

- Hit rate and decline rate by tier, against last week.
- Clients whose suggested tier changed (`ratesresearch.markouts.toxicity_table`), with the number of fills behind each estimate.
- Near misses within 0.05 bp of the cover and mean money left on the table, by tier.

## 4. Adverse selection

Mean mid-to-mid markout in bp at 1 s, 10 s, 1 min and 5 min, by tier and by product. Any tier whose 1-minute markout moved by more than two standard errors from the fitting-period estimate in the experiment config.

## 5. Hedging, limits and latency

- Hedge count, total hedge cost and mean slippage in bp.
- Largest net DV01 utilisation and largest position utilisation of the week.
- Latency percentiles if the harness file was present, with its machine; otherwise "not available".
- Router fill quality (P4 onward).

## 6. Experiments

Ledger lines added this week (`data/ledger/ledger.jsonl`): pre-registration id, scope, look number, verdict. Pre-registrations written and not yet run. Holdouts opened.

## 7. Decisions and follow-ups

What changes, who approves it, and whether it needs a new pre-registration (a change to a strategy, a preset or an assumption band does).
