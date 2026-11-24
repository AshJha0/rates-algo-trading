# Tutorial 01: a first back-test day, and how to read its report

Goal: generate one synthetic trading day, run the unattended daily report on it, and understand each section well enough to say what would make it wrong.

Background: [LEARN.md Section 10](../../LEARN.md#10-back-testing-honestly-pre-registration-holdouts-attribution) and [API_SPEC.md](../../API_SPEC.md) Section 16. Recipes [18](../../COOKBOOK.md#18-how-do-i-run-the-back-tester-on-one-synthetic-day) and [20](../../COOKBOOK.md#20-how-do-i-reconcile-attribution-to-position-pl) are the same ideas as programs.

## Step 1. Build a class path

**Not run here.** The script compiles `rates-app` and what it depends on, installs nothing, and writes one class path file. It runs Maven offline; run `mvn -B -ntp verify` once first if your local repository is empty.

```bash
MVN=mvn tools/sim-v2/build_classpath.sh
export CP="$(cat rates-app/target/sim-v2-cp.txt)"
export OPENS="--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED"
```

The three `--add-opens` flags are the ones the images and the benchmarks use for Agrona.

## Step 2. Generate the day

**Not run here.** `SyntheticDayCli` writes one session to a journal. This is the version 1 generator, which the daily report's configuration belongs to; it prints a notice that version 1 is kept only to reproduce recorded rows. That makes it fine for learning the report and wrong for a new experiment.

```bash
mkdir -p target/tutorial
java $OPENS -cp "$CP" io.ratesalgo.backtest.synth.SyntheticDayCli --date 2026-02-02 --out target/tutorial/2026-02-02.raj
```

The seed is 5,000,000 plus the date's epoch day. Recipe 18 uses the same date and seed, and its recorded first line is `journal: 94571 frames, 398 rfqs, chain hash f136c9dbcf88d7b1`: expect the same frames, RFQs and hash, in this command's own wording. If the hash differs, stop. Either the generator changed or the file is not the day you think it is.

To look at a simulator v2 day instead, use `--sim-v2 --seed N`. The command refuses spent bases, but it does not refuse every base somebody is saving: do not generate any session of seed base 31,000,000, the unread data set of the unsigned registration.

## Step 3. Run the report

**Not run here.**

```bash
java $OPENS -cp "$CP" io.ratesalgo.backtest.report.ReportCli --config data/experiments/daily_report.yaml --journal target/tutorial/2026-02-02.raj --out target/tutorial/report
```

It prints one line per band point and writes `results.raj`, `fills.csv`, `rfqs.csv`, `pnl.csv`, `summary.json`, `report.md` and `report.json` under `target/tutorial/report/` in `low`, `central` and `high`, with `band_risk.json` and `band_risk.md` beside them. The strategy (`fixed_spread`), the outcome seed and the limits come from the config file, so nothing on the command line changes a result quietly.

## Step 4. Read it, in this order

1. **The header.** Strategy, band point, seed, and the chain hashes of the input and the output. Two reports with different input hashes describe different days. The band point names the win-probability assumption; no report is "the" result without the other two.
2. **P&L by product and attribution line.** Check the residual first: below one cent or the report is wrong. Then see which line is large. On recipe 18's recorded day the central point made 1,846,659.10 USD, of which inventory was 1,230,784.67: most of the day is direction, and one day says little.
3. **Hit rate by client tier.** Winning most from the tier with the worst markouts is a warning.
4. **Markouts, by tier and product.** The mid's move after the trade, in our favour. A positive spread capture with negative five-minute markouts is a spread being given back.
5. **Hedge slippage.** Compare it with half a tick of the instrument used. Less than that needs an explanation.
6. **Router fill quality** and **Latency percentiles** say that they are not available when the run had no router record or no latency file. Absent is not zero.
7. **Limit utilisation**, **Client analytics**, **Response time against win rate**.
8. **P&L risk across the assumption band.** One day gives a mean and no volatility. Its label says the figures are a consistency check on the simulator.

## Step 5. Reconcile it without Java

**Run on 2026-11-24.** Recipe 20 rebuilds spread capture, hedge cost and fees from the fills of a committed day. The last three lines of its output:

```bash
PYTHONPATH=research python research/recipes/recipe20_reconcile.py
```

```
client fills 144, sum of edges 1,986,264.94
hedge fills 13, sum of edges -1,633,546.88
reconciles to the cent: True
```

**Run on 2026-11-24**, with `PYTHONPATH=research`. The same module reads any run directory; here the committed day of 2026-01-05:

```python
from ratesresearch import backtest_analysis as ba

run = ba.load_run("data/fixtures/backtest/fitting/2026-01-05")
print(ba.to_markdown(ba.hit_rate_by_tier(run)))
print(ba.reconcile(run))
```

```
| tier | rfqs | quoted | won | hit_rate | decline_rate |
|---|---|---|---|---|---|
| 1 | 163 | 163 | 67 | 0.411 | 0 |
| 2 | 110 | 110 | 50 | 0.4545 | 0 |
| 3 | 70 | 70 | 27 | 0.3857 | 0 |

[]
```

An empty list means no failure. That day lost 1,301,665.84 USD: 1,986,264.94 of spread captured, less 1,547,896.30 of inventory, 1,633,546.88 of hedge cost, 104,547.00 of fees and 1,940.61 of funding. The five displayed lines add to -1,301,665.85; the cent is rounding of the display, and the recorded residual is zero to six decimals. Point `load_run` at `target/tutorial/report/central` for your own run.

## Before believing a report

- Would the conclusion survive the low and the high band point?
- Is the P&L mostly spread or mostly inventory?
- Has somebody already read this day? `PYTHONPATH=research python -m ratesresearch.prereg data/ledger/ledger.jsonl` prints the reads per data set (**run on 2026-11-24**; last line `32 ledger rows, 13 data sets, 3 read more than once`).
- It is simulated, so it is a statement about the simulator.
