# Forward log summary

Client RFQs, fills and hedge fills are synthetic (fitted models). Rates and the close mark are real (US Treasury par curve, NY Fed SOFR, DTCC SOFR OIS prints). See docs/research/PAPER_TRADING_PLAN.md.

Decisions 11, marked sessions 10, skipped 0.

## Real component: close mark on the residual position

The end-of-session position times the real move from the committed close to the marking close. The desk takes no view on direction, so the expected value is zero: this line measures the exposure left overnight, not skill.

Cumulative -738,956.81 USD over 10 sessions. Mean -73,895.68 USD a session, standard error 359,975.60 USD.

## Synthetic component: session P&L

Synthetic. Spread income, hedge cost, carry, funding and fees from model-generated client flow against a market with no intraday moves. With the market held still the income carries almost no market risk, so its mean is many times its day-to-day variation by construction. That is a property of the set-up, not a track record, and no ratio of mean to standard deviation is printed for it or for any total that contains it.

Cumulative 14,581,632.99 USD (synthetic) over 10 sessions.

| Session | Marked on | Session P&L (synthetic) | Cumulative (synthetic) | Close mark (real) | Cumulative close mark (real) | End net DV01 |
|---|---|---:|---:|---:|---:|---:|
| 2026-11-10 | 2026-11-10 | 1,532,143.87 | 1,532,143.87 | 480,032.18 | 480,032.18 | -21,077.50 |
| 2026-11-11 | 2026-11-12 | 2,023,939.61 | 3,556,083.48 | -2,493,497.26 | -2,013,465.08 | -50,801.00 |
| 2026-11-13 | 2026-11-13 | 544,318.77 | 4,100,402.25 | 68,552.68 | -1,944,912.40 | 144,104.00 |
| 2026-11-16 | 2026-11-16 | 1,114,390.91 | 5,214,793.16 | -126,049.84 | -2,070,962.24 | 19,787.50 |
| 2026-11-17 | 2026-11-17 | 1,960,996.67 | 7,175,789.83 | -193,581.60 | -2,264,543.84 | 136,736.00 |
| 2026-11-18 | 2026-11-18 | 946,452.24 | 8,122,242.07 | -184,193.88 | -2,448,737.72 | 42,902.00 |
| 2026-11-19 | 2026-11-19 | 1,558,257.47 | 9,680,499.54 | 406,239.38 | -2,042,498.34 | 8,384.00 |
| 2026-11-20 | 2026-11-20 | 1,392,971.88 | 11,073,471.42 | -353,528.33 | -2,396,026.67 | -11,886.50 |
| 2026-11-23 | 2026-11-23 | 1,635,558.01 | 12,709,029.43 | 2,154,076.85 | -241,949.82 | 49,990.00 |
| 2026-11-24 | 2026-11-24 | 1,872,603.56 | 14,581,632.99 | -497,006.99 | -738,956.81 | -4,574.50 |
