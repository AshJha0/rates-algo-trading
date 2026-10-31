# Edge studies on public data (EDGE-E1 to EDGE-E6)

Six pre-registered studies looked for effects in free public data that a rates market maker could use for spread setting, skew, hedge timing and inventory. Two survived their holdout and were promoted to presets: swap-print volatility and intensity around 08:30 releases and FOMC statements (E3), and the trade-size distribution by tenor with clustering after large trades (E4). Three were not supported (E1 auction concession, E2 month-end extension, E6 carry tilt), and one was inconclusive because its fitting data could not be downloaded (E5 swap spreads and positioning). The negative results are kept here and in the ledger.

Run on 2026-10-31. Ledger rows: `data/ledger/ledger.jsonl` seq 2 to 7. Notebooks recompute every number from the committed fixtures and check them against the ledger rows.

## Summary

| Study | Registered test (holdout, central band point) | Estimate and 95% interval | Holm p | Verdict |
|---|---|---|---|---|
| E1 auction concession | E1a: pre-auction change of the auctioned tenor's par-curve residual, 5 days, predicted positive | -0.82 bp [-1.09, -0.56], 199 auction weeks | 0.0055 | not supported (reversed) |
| | E1b: post-auction change, 3 days, predicted negative | +0.39 bp [0.18, 0.59], 199 weeks | 0.0055 | (reversed) |
| E2 month-end extension | 2s30s month-end change less the ordinary 3-day change, high-extension months, predicted at most -0.5 bp | -1.22 bp [-2.96, 0.50], 47 months | 0.50 | not supported |
| E3 releases and fixings | E3a: RV 08:30 to 08:45 ET on release days over control | ratio 4.27 (x = 3.27 [1.86, 4.58]), 37 days | 0.0055 | supported |
| | E3b: RV 14:00 to 14:30 ET on FOMC days over control | ratio 2.14 (x = 1.14 [0.38, 1.94]), 8 days | 0.0055 | (passed) |
| | E3c: RV 08:00 to 08:05 ET (SOFR publication) over adjacent buckets | ratio 1.27 (x = 0.27 [-0.34, 0.98]), 169 days | 0.88 | (failed) |
| | E3d: trade count 08:30 to 08:45 ET on release days over control | ratio 2.52 (x = 1.52 [1.06, 1.95]), 37 days | 0.0055 | (passed) |
| E4 size and clustering | E4a: daily share of trades at or above the fitted p95, equivalence band 2.5 to 10 percent | 3.69% [3.46%, 3.93%], 250 days | 0.0055 | supported |
| | E4b: extra same-tenor trades in the 5 minutes after a trade at or above p90 | +0.166 [0.096, 0.236], 250 days | 0.0055 | (passed) |
| E5 swap spread and COT | forecast times realised next-week change, and out-of-sample R^2 > 0 | mean u 0.75 [0.08, 1.65], 77 weeks; R^2 -0.089 | 0.27 | inconclusive (data defect) |
| E6 carry tilt | tilted minus equal-DV01 inventory P&L, bp per unit DV01 per month | -2.12 [-7.09, 4.08], 65 months | 0.88 | not supported |

The family is the eleven tests above. Holm adjusts across all eleven; the smallest attainable bootstrap p with 2000 resamples is 1/2001, so the smallest adjusted p is 11/2001 = 0.0055.

## Method shared by all studies

- Pre-registration: `research/prereg/E1.yaml` to `E6.yaml`, in the P5-001 format and valid under `ratesresearch.prereg.validate`, each with a hashed config and the shared rules `research/prereg/edge_programme.yaml`. Commit ca98eef registered all six before any study data was downloaded; the data commit is 915d126 and the ledger commit 06572d2.
- Periods: each study has a fitting period, a holdout test period (the pre-registration's `period`, opened once, which decides the verdict), and reserve dates from 2026-07-01 to 2026-10-30 that no study reads. The fixtures stop at 2026-06-30 (`ratesresearch.edge.READ_END`), and `research/tests/test_edge.py` checks that.
- Statistics: a moving-block bootstrap over the unit of analysis in time order (2000 resamples, seed 20261031, the SplitMix64 generator of `ratesresearch.stats`, vectorised), a 95 percent percentile interval, the bootstrap standard error, and a two-sided centred bootstrap p. A test passes when the estimate has the predicted sign, its interval excludes the null on that side, the Holm-adjusted p is below 0.05 and the economic threshold is met.
- Promotion: only passed tests of supported studies become presets, with the holdout estimate (out of sample) as the value and the bootstrap standard error.
- Code: `research/ratesresearch/edge/` (`sources` downloads, `build` derives the fixtures, one module per study, `run` runs the programme and writes the ledger rows and presets).

## E1 Treasury auction cycle: not supported (reversed)

Finding: on the holdout (2021 to mid-2026) the auctioned tenor richened against its par-curve neighbours over the 5 days before nominal coupon auctions, by 0.82 bp, and cheapened by 0.39 bp over the 3 days after, the opposite of the registered concession pattern. The fitting period (2015 to 2020) showed the registered pattern: +0.44 bp [0.25, 0.63] before and -0.54 bp [-0.67, -0.41] after, over 154 auction weeks. No skew term is promoted.

Method: for each nominal fixed-coupon note or bond auction (TreasuryDirect records, TIPS and FRNs dropped, tenor from the security term), the residual of the auctioned tenor against the linear interpolation of its par-curve neighbours (2Y against 1Y and 3Y, up to 20Y against 10Y and 30Y; 30Y as the 20s30s spread), in bp; the change from 5 business days before the auction day to the auction day, and from the auction day to 3 days after; averaged within ISO weeks (blocks of 2 weeks).

Data: US Treasury daily par curve 2015-01-02 to 2026-06-30 (2,874 days); 909 auctions from 2014-12 to 2026-06, of which 463 fall in the holdout and 441 in the fitting period; Federal Reserve GSW fitted par yields for the secondary residual.

Numbers: across the band (windows 3 and 2, 5 and 3, 7 and 5 days) the holdout pre-auction change is -1.03, -0.82 and -0.84 bp and the post-auction change +0.17, +0.39 and +0.72 bp. The residual against the GSW curve gives the same signs (-0.88 bp before, +0.45 bp after, central). By tenor in the holdout, the pre-auction richening is largest at 2Y (-2.94 bp over 66 auctions), 3Y (-1.53 bp) and 5Y (-1.04 bp), and close to zero at 7Y, 10Y and 30Y.

Caveats: the par curve is the Treasury's interpolation of on-the-run closing bid yields, and its input switches from the old to the new on-the-run issue around the auction, so the residual change mixes concession with that switch. The Treasury moved the par curve to a monotone convex method on 2021-12-06, inside the holdout, which may explain why the sign changed between periods; this was not registered and is not tested here. Daily closes cannot show whether any effect is capturable at a bond RFQ's bid-offer. Sign reversals between periods are a warning against using either period's estimate.

## E2 Month-end index extension: not supported

Finding: in months with large term-weighted coupon issuance, 2s30s did not flatten over the last 3 business days by a significant amount once the ordinary 3-day change is subtracted: -1.22 bp [-2.96, 0.50] over 47 holdout months, Holm p 0.50. Nothing is promoted.

Method: month-end change of 100 x (30Y - 2Y) from 3 par-curve days before the last business day to that day, less the mean of all 3-day changes that do not touch the last 5 business days of a month (same period). High-extension months: issuance in the month (offering amount in USD bn times term in years, nominal coupons) above 1,465.9 USD bn-years, the 67th percentile of the 72 fitting months (2015 to 2020). Blocks of 3 months.

Numbers: fitting period +0.12 bp [-1.43, 2.16] over 24 high months. Holdout over all 66 months -1.04 bp [-2.92, 0.63]. The band (windows 2, 3, 4 days) gives -1.61, -1.22 and -1.96 bp in the holdout; the 2-day window has an unadjusted p of 0.0075, which is a band point and not a registered test, so it carries no inference.

Caveats: index extension is proprietary; issuance is a proxy that ignores maturing debt and index rules. Coupon sizes grew after 2020, so the fitting-period threshold marks 47 of 66 holdout months as high, which makes the high group close to all months.

## E3 Swap-print volatility and intensity around releases: supported

Finding: on 08:30 ET release days, the realised variance of USD SOFR OIS print rates from 08:30 to 08:45 is 4.3 times that of the same window on quiet days, and trade counts are 2.5 times higher; from 14:00 to 14:30 on FOMC statement days realised variance is 2.1 times that of control days. The 08:00 SOFR publication shows no significant effect. The time-of-day schedule below is promoted as a spread multiplier and a simulator intensity profile.

Method: DTCC SDR public dissemination (CFTC cumulative rates slices), new USD SOFR OIS trades, not packages, spot-starting, standard tenors. Per 5-minute bucket from 07:00 to 17:00 ET: trade count, and realised variance as the mean over 2, 5, 10 and 30 years of the summed squared print-to-print rate changes (bp; consecutive prints at most 30 minutes apart and at most 10 bp apart). Release days: actual release dates (ALFRED vintage dates) of the Employment Situation, CPI, GDP and Personal Income and Outlays. Control days for 08:30: Monday, Tuesday, Wednesday or Friday with no release and no FOMC; for 14:00: any day with neither. Unit: event day, blocks of 2.

Data: 416 DTCC business days from 2024-10-29 to 2026-06-30; fitting 2025-01-02 to 2025-06-30 (123 days: 23 release, 4 FOMC), holdout 2025-07-01 to 2026-06-30 (250 days: 37 release, 8 FOMC, 169 control days for 08:30, 206 for 14:00).

Numbers: fitting period ratios 3.84 (E3a), 1.68 (E3b), 2.15 (E3d). The band (control days within 20 business days, all control days, Thursdays added) keeps every passed test above its threshold in the holdout: E3a x = 3.58, 3.27, 2.82; E3b 1.49, 1.14, 1.14; E3d 1.55, 1.52, 1.43. Holdout spread multipliers, sqrt of the per-bucket RV ratio, floored at 1 (standard errors from the bootstrap over event days):

| Window | Bucket start (ET) | RV ratio | Multiplier | se | Trade-count ratio |
|---|---|---|---|---|---|
| release | 08:30 | 5.55 | 2.36 | 0.22 | 2.79 |
| release | 08:35 | 3.48 | 1.87 | 0.24 | 2.47 |
| release | 08:40 | 3.70 | 1.92 | 0.30 | 2.31 |
| release | 08:45 | 1.24 | 1.11 | 0.17 | 1.87 |
| release | 08:50 | 2.10 | 1.45 | 0.19 | 1.81 |
| release | 08:55 | 2.26 | 1.50 | 0.31 | 1.63 |
| FOMC | 14:00 | 2.09 | 1.45 | 0.32 | 1.74 |
| FOMC | 14:05 to 14:25 | 0.86 to 3.99 | 1.00 to 2.00 | 0.16 to 0.71 | 1.58 to 1.87 |
| FOMC | 14:30 to 14:55 (press conference) | 0.76 to 3.98 | 1.00 to 1.99 | 0.16 to 0.47 | 1.55 to 2.72 |

The per-bucket FOMC values rest on 8 days and have large standard errors; the engine should treat the FOMC window as one block rather than trust the bucket-to-bucket pattern. The quiet-day intensity profile (30-minute buckets, relative to the day mean of 1.0) rises from 0.36 at 07:00 to 1.78 at 11:00 and falls to 0.57 at 16:30.

Caveats: prints are executions across the market, not the RFQs one dealer sees, so intensity stands for RFQ intensity only by assumption. Print-to-print changes include bid-offer bounce across dealers and sizes. Late-reported blocks carry their execution time. ALFRED vintage dates can include a revision vintage that was not an 08:30 release. A unit error (pandas 3 timestamps in microseconds) was found and fixed before the ledger row was written; it is recorded in the row's deviations and moved E3a and E3b by under 0.12.

## E4 Trade size by tenor and clustering: supported

Finding: a censored lognormal fitted per tenor on the first half of 2025 kept its 95th percentile calibrated over the following year: 3.7 percent of holdout trades were at or above it, inside the registered band of 2.5 to 10 percent. After a trade at or above the fitted 90th percentile, the same tenor sees 0.17 more trades than its time-of-day average in the next 5 minutes. Both are promoted: size limits and simulator size distributions per tenor, and the post-large-trade excess.

Method: DTCC prints as in E3, tenors 2, 5, 10 and 30 years. Lognormal notional (USD mm) fitted by maximum likelihood with capped prints right-censored at the dissemination cap (EM on the log scale). E4a: equivalence test of the mean daily exceedance share, two one-sided bootstrap tests. E4b: per large trade, same-tenor trades in the next 300 seconds less the period's mean count in that 5-minute bucket; mean per day. Unit: day, blocks of 5.

Data: fitting 123 days, 48,339 trades in the four tenors (411 capped); holdout 250 days, 7,454 large trades for E4b.

Numbers: holdout fit (promoted), USD mm:

| Tenor | log mu | log sigma | p50 | p90 | p95 | p99 | trades per day |
|---|---|---|---|---|---|---|---|
| 2Y | 3.756 | 1.545 | 42.8 | 309.9 | 543.1 | 1,556.5 | 63.4 |
| 5Y | 3.145 | 1.515 | 23.2 | 161.9 | 280.7 | 788.4 | 102.2 |
| 10Y | 2.792 | 1.431 | 16.3 | 102.2 | 171.9 | 455.8 | 130.0 |
| 30Y | 2.190 | 1.385 | 8.9 | 52.7 | 87.2 | 224.1 | 66.1 |

The fitting-period p95 values were 500.2, 282.7, 160.5 and 85.3. The exceedance share is 3.6, 3.7 and 4.1 percent across the band (capped prints dropped, censored, taken as exact), all inside the equivalence band, and below the nominal 5 percent: the fitted tail is slightly heavier than the observed one. The E4b excess is 0.155 to 0.166 across the band; in the fitting period it was 0.137 [0.053, 0.234].

Caveats: notionals above the CFTC caps are not observed, so p99 and above are extrapolations. Market-wide prints, not one dealer's RFQs. FINRA Treasury trades need an account (HTTP 401); the FINRA fixture of P5 is synthetic and was not used in any test.

## E5 Swap spread and futures positioning: inconclusive

Finding: the registered fitting period (2024) could not be downloaded in full, so the study is inconclusive by the programme's data-defect rule. As run, the forecast also fails its own test: out-of-sample R^2 is -0.089 and the Holm-adjusted p is 0.27. Nothing suggests a usable weekly signal.

Method: weekly on the first business day of each week, swap spread = median 10Y SOFR OIS print rate from 14:30 to 16:00 ET less the 10Y par yield (bp); target = next week's change; predictors = change in leveraged-fund net position as a share of open interest in 10Y note futures (CFTC Traders in Financial Futures, reports assumed available three days after the Tuesday report date) and last week's spread change; OLS on the fitting weeks.

Data and deviation: DTCC files before 2024-10-29 are kept in S3 Deep Archive and answered HTTP 403 `InvalidObjectState` on 2026-10-31, so the fitting period has 9 usable weeks instead of about 50. Holdout 77 weeks (2025-01 to 2026-06). During the October to November 2025 US government shutdown the CFTC published late, so the positioning change was not known in real time in those weeks.

Numbers: fitted slopes (central) 33.2 bp per unit share change and -0.65 on the lagged change, from 9 weeks. Out-of-sample R^2 -0.106, -0.089, -0.071 across the band. The registered statistic has mean 0.75 with interval [0.08, 1.65] and unadjusted p 0.067.

Caveats: a fit on 9 weeks cannot test the hypothesis as registered. A repeat needs DTCC history from another source, or a new pre-registration with a later fitting period.

## E6 Carry and roll-down inventory tilt: not supported

Finding: tilting a Treasury inventory towards the tenors with the most carry and roll-down per unit DV01 did not beat an equal-DV01 inventory over the following month: -2.12 bp per unit DV01 per month [-7.09, 4.08] over 65 holdout months, Holm p 0.88. In the fitting period the difference was -0.22 [-3.59, 3.23] over 72 months. No inventory tilt is promoted.

Method: at each month end, expected carry and roll-down per tenor (2, 3, 5, 7, 10, 20, 30 years) for a par bond of one bp DV01, funding at the 1-month bill plus 10 bp; tilt weights max(0, 1 + 0.5 z) rescaled to sum 7; realised P&L to first order from the par curve one month later at the aged maturity. Blocks of 3 months.

Numbers: across funding spreads of 0, 10 and 25 bp the holdout difference is -2.35, -2.12 and -1.42 bp per month. Both inventories lost on average in the holdout (equal DV01 -40.1 bp per month summed over the seven units, central), which reflects the 2022 rise in yields.

Caveats: constant-maturity yields, no bid-offer or repo specialness, a bill-plus-spread funding proxy. The tilt is also a curve position; 65 months is short for a curve factor.

## Presets promoted

Both presets are in `data/presets.yaml`; each names its ledger row, data range, method and holdout verdict in `notes`. Keys the engine and simulator must read (the coordinator wires them; no Java was changed):

- `usd_time_of_day_spread` (EDGE-E3, ledger seq 4), times are New York 5-minute bucket starts:
  - quoting engine, half-spread multiplier inside the window: `release_0830_spread_multiplier`, `release_0835_spread_multiplier`, `release_0840_spread_multiplier`, `release_0845_spread_multiplier`, `release_0850_spread_multiplier`, `release_0855_spread_multiplier` on release days; `fomc_1400_spread_multiplier` to `fomc_1455_spread_multiplier` (12 keys, every 5 minutes) on FOMC statement days;
  - simulator, RFQ arrival multiplier per 30-minute bucket on ordinary days: `intensity_0700_multiplier` to `intensity_1630_multiplier` (20 keys);
  - simulator, release-day arrival ratio from 08:30 to 08:45: `release_0830_intensity_ratio`.
- `usd_sofr_ois_size_by_tenor` (EDGE-E4, ledger seq 5), for each of `tenor_2y`, `tenor_5y`, `tenor_10y`, `tenor_30y`:
  - quoting engine size limit: `<tenor>_p95_mm` (quote up to this size; above it quote on request or widen), with `<tenor>_p90_mm`, `<tenor>_p50_mm`, `<tenor>_p99_mm` for reference;
  - simulator size distribution: `<tenor>_log_mu`, `<tenor>_log_sigma` (lognormal of notional in USD mm), arrivals `<tenor>_trades_per_day`;
  - simulator clustering: `post_large_trade_excess_5min` (extra same-tenor arrivals in the 5 minutes after a trade at or above p90).

The release and FOMC calendars that switch the multipliers on are in `data/fixtures/edge/calendar.csv` up to 2026-06-30; the engine needs its own forward calendar.
