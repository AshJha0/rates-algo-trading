# Daily-P&L risk across the assumption band

This table moved here from README "Measured results" on 2026-11-23, after the review of that date ([docs/reviews/REVIEW_2026-11-23.md](../reviews/REVIEW_2026-11-23.md), Sections 2.1 and 9).

**What these numbers are.** Each figure is a property of the declared simulator constants: the hit-rate model, the competitor spreads, the share of informed flow and the size of the informed move (DECISIONS.md DR-008). They check that the parts of the simulator agree with each other. They are not evidence that any strategy would make money, and they are not a forecast. A Sharpe ratio computed here is not comparable with a Sharpe ratio from a real track record.

**How they are computed.** Volatility, Sharpe ratio (zero risk-free rate, not annualised) and maximum drawdown of the recorded daily P&L of each ledger row, with `ratesresearch.stats.pnl_risk` (Python) or `Stats.pnlRisk` and `BandRisk.fromExperimentResults` (Java, API_SPEC.md 16.9). `ReportCli` and `ExperimentCli` write the same table for every run (`band_risk.json`, `band_risk.md`).

Recomputed on 2026-11-23 from `data/ledger/ledger.jsonl` with `ratesresearch.stats.pnl_risk`.

| Run | Band point | Strategy | Days | Mean daily P&L (USD) | P&L volatility (USD) | Daily Sharpe | Max drawdown (USD) |
|---|---|---|---:|---:|---:|---:|---:|
| P5-001, 10 synthetic days (seq 1) | low | model_spread | 10 | 2,350,531 | 1,272,516 | 1.85 | 0 |
| | central | model_spread | 10 | 1,832,066 | 1,254,129 | 1.46 | 319,515 |
| | high | model_spread | 10 | 617,976 | 847,380 | 0.73 | 311,741 |
| | central | fixed_spread | 10 | 768,724 | 1,336,526 | 0.58 | 2,129,307 |
| AUDIT-21, 20 sessions of 15 minutes (seq 25) | low | engine | 20 | -113,552 | 143,746 | -0.79 | 2,271,036 |
| | central | engine | 20 | -108,992 | 156,601 | -0.70 | 2,179,838 |
| | high | engine | 20 | -125,146 | 178,145 | -0.70 | 2,502,918 |

AUDIT-21 re-ran AUDIT-11 (seq 19) at v1.0.0 and supersedes it (docs/research/PNL_AUDIT.md). The AUDIT-11 rows that README showed until 2026-11-23 were -82,167, -72,476 and -105,217 USD per session at low, central and high; they predate DR-INT-05 and are kept in the ledger.

Why the P5-001 ratios are positive: the simulated clients follow the declared hit-rate model that the quoter is optimised against, and the default outcome model lets a quote well behind the best price still win part of the time (review Section 2.3). Why the AUDIT-21 figures are negative: the desk's half-spread is clamped to 0.02 to 0.08 bp (`rates-app/src/main/java/io/ratesalgo/app/loop/DeskSettings.java`, lines 59-60), while the D2C simulator's informed flow costs about 0.20 bp per swap trade in expectation (20% informed, 4 competitor half-spreads of 0.25 bp; `rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`). Both signs follow from the constants.

An AUDIT-21 session is 15 minutes, so annualising its ratio would mean nothing.
