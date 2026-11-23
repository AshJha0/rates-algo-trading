# Simulator calibration (SIM3-CAL-001): what the public data supports

Date: 2026-11-23. Pre-analysis note: `research/prereg/SIM3-CAL-001.yaml` (committed first, commit `268b5f0`). Code: `research/ratesresearch/simcal/`. Parameter file: `data/calibration/sim_v2_params.json`. Specification that uses it: `docs/research/SIM_V2_DESIGN.md`.

The review of 2026-11-23 found that every simulated P&L figure followed from hand-declared constants. This document replaces those constants with estimates where public data can give one, and with a stated assumption and a sensitivity range where it cannot. It is written so that a reader can see which is which. Three status words are used throughout and in the parameter file:

- **fitted**: estimated from data, with a 95 percent interval from resampling whole days.
- **bounded**: the data gives a range; the value used is a stated choice inside it.
- **assumption**: not identified by any data in this repository; a value and a band to run.

## 1. Summary

| Quantity | Old declared value | Calibrated value (calibration period) | Status |
|---|---|---|---|
| Common rate factors | none: each instrument an independent random walk | three factors (level, slope, curvature) explain 98.9 % of daily par-yield variance at 2 to 30 years; daily sd 13.28 [12.75, 13.78], 4.47 [4.25, 4.75], 1.51 [1.43, 1.59] bp in factor units | fitted |
| Intraday swap mid volatility | swap 1.5 bp per sqrt-hour = 0.194 bp per sqrt-minute, independent per instrument | 0.123 [0.117, 0.131] bp per sqrt-minute pooled (0.114 at 10 years), outside event windows; plus 181 [97, 324] times normal variance for the 5 minutes after an 08:30 release | fitted |
| Half-spread paid on D2C swap prints (root mean square) | competitor median 0.25 bp, so the winning quote is about 0.19 bp | between 0.103 [0.096, 0.105] and 0.227 [0.207, 0.246] bp; 0.153 used | bounded |
| Competitor median half-spread | 0.25 bp | 0.183 bp (range 0.123 to 0.272) | bounded |
| Adverse move after a trade, average over all trades | 0.20 bp (20 % of trades x 1 bp jump at 30 s), on the traded instrument only | 0.06 bp (range 0 to 0.20), delivered through the level factor; not separately identified, set by the zero-profit condition | bounded |
| Realised half-spread at 5 minutes | implied -0.01 bp (negative) | measured raw value +0.046 [0.004, 0.084] bp, a lower figure | fitted |
| Swap-spread factor | none | daily sd 0.7 bp (range 0.3 to 1.3); the data gives only an upper figure near 1 bp | bounded |
| Futures basis | none in the venue simulator | no data | assumption |
| Order-book impact and resilience | none | no data | assumption |

What the data could not identify, in one place: the level of adverse selection (only its sign-robust consequence, a positive realised spread); the time profile of the post-trade drift (1 s and 5 s horizons have a few hundred usable prints and no detectable build-up); the split between "fraction informed" and "size of the move"; dispersion across dealers; size or tenor dependence of the half-spread beyond "flat within error"; the swap-spread factor other than as an upper figure; anything about futures, Treasury trades or order-book impact; the share of market inquiries one dealer sees.

## 2. Data and provenance

| Input | File | Manifest |
|---|---|---|
| DTCC SDR public dissemination, rates, end-of-day cumulative slices | 419 zips, outside git | `data/manifests/sim_v2_dtcc.json` |
| US Treasury daily par yield curve, 2015-01-02 to 2026-06-30 | `data/fixtures/edge/par_curve.csv` | `data/manifests/edge_par_curve.json` |
| Release, FOMC and auction days | `data/fixtures/edge/calendar.csv`, `auctions.csv` | `edge_alfred.json`, `edge_fomc.json`, `edge_auctions.json` |

The raw DTCC zips recorded in `data/manifests/edge_dtcc.json` (436 files) were no longer on disk on 2026-11-23: `data/raw/` does not exist in the main checkout and a search of `C:\Work\Claude` and the user profile for `CFTC_CUMULATIVE_RATES_*.zip` found none. They were downloaded again that day from the same public endpoint (no account, no key), into a directory outside the repository. 419 of the 436 files were served and **every one has the SHA-256 recorded in the first manifest**. The 17 files dated 2024-10-29 to 2024-11-20 now answer HTTP 403: the bucket moves files older than about two years to archive storage. The new manifest lists the 419 files and the 17 missing ones. Two files (2024-11-21, 2024-11-22) fall before the calibration start fixed in the note and are not read.

The split, fixed before reading:

| | DTCC prints | Par curve |
|---|---|---|
| Calibration | 2024-11-25 to 2025-12-31 (288 file days, of which 2024 is one day a week) | 2015-01-02 to 2025-12-31 (2,749 daily changes) |
| Validation | 2026-01-02 to 2026-06-30 (128 file days) | 2026-01-02 to 2026-06-30 (124 daily changes) |

The validation period is unread for the statistics below. It is not unread by the repository: the edge studies E3 and E4 used the same files for other statistics, as the note says.

Reproduce (RAW = directory of zips, CACHE = empty directory, both outside git):

```
python -m ratesresearch.simcal manifest  --raw RAW --fetch-log fetch_log.json
python -m ratesresearch.simcal calibrate --raw RAW --cache CACHE --out cal.json
python -m ratesresearch.simcal validate  --raw RAW --cache CACHE --cal cal.json --out val.json
python -m ratesresearch.simcal params    --cal cal.json
python -m ratesresearch.simcal competitor
```

`calibrate` reads only file days up to 2025-12-31; `validate` reads only 2026 and takes the curve loadings from `cal.json`. Every number in this document is in `cal.json` or `val.json` under the key named beside it. A second run of `calibrate` after the type fixes reproduced `cal.json` exactly. Bootstrap: whole days resampled with replacement, 1000 resamples, PCG64 seed 20261123, percentile interval.

## 3. Filters and counts

Filters are applied in this order (`simcal.prints.parse_file`); the count is what is left after each (`cal.json: funnel`, `val.json: funnel`).

| Step | Calibration | Validation |
|---|---:|---:|
| Rows in the files | 6,238,449 | 3,160,311 |
| New trades (Action NEWT, Event TRAD) | 3,800,452 | 2,177,527 |
| USD, UPI "NA/Swap OIS USD", SOFR underlier | 971,465 | 509,932 |
| Not a package | 347,319 | 182,648 |
| A numeric fixed rate on exactly one leg | 346,745 | 182,525 |
| No upfront (other) payment | 270,093 | 140,020 |
| Not flagged non-standard, no floating spread, no embedded option | 244,373 | 130,057 |
| Effective date 0 to 7 days after execution | 173,844 | 91,676 |
| Maturity within 0.15 years of 2, 3, 5, 7, 10, 20, 30 years | 103,633 | 54,078 |
| Positive notional | 103,633 | 54,078 |
| Executed 07:00 to 17:00 New York on the file's day | 82,343 | 41,803 |
| Within 5 bp of the centred median of the 20 nearest prints of the tenor (3 bp: 74,550; 10 bp: 79,246) | 77,308 | 39,528 |
| After merging prints of one tenor, second and rate | **76,369** | **39,102** |

Two further subsets were defined on the calibration period, before the validation read, and are used as stated in each table:

- **strict** (68,798 calibration prints; 35,177 validation): effective date 2 to 5 calendar days after execution (the standard spot date across weekends) and maturity within 0.02 years of the tenor. Reason: the registered 0.15-year tolerance lets a 9.9-year swap share a bucket with a 10-year swap; on a curve with a few bp of slope per year that alone is a few tenths of a bp of "noise".
- **strict D2C** (63,789; 32,200): strict prints whose platform identifier is TWSF or BBSF, the two dealer-to-client request-for-quote facilities. The other codes (BILT 7,339 prints in the registered set, then XXXX, BMTF, BTFE, XOFF, TREU, BGCD, TSEF and smaller) are interdealer, all-to-all or off-facility. The grouping is by facility: the file does not say who traded or which side the client was on.

Prints per day in the strict D2C subset: 2y 28.0, 3y 19.6, 5y 47.4, 7y 12.8, 10y 78.5, 20y 9.8, 30y 36.7 (total 233; `strict_d2c.prints_per_day`). Prints of one tenor are therefore minutes apart. This single fact limits everything in sections 6 and 7.

## 4. Curve factor model (fitted)

Method: principal components of the covariance of daily changes, in bp, of the Treasury par yields at the seven desk tenors (`simcal.factors.pca`). Loadings have unit length. Calibration window 2015-01-02 to 2025-12-31, 2,749 daily changes (`cal.json: curve`).

| Tenor | Level | Slope | Curvature | Residual sd, bp/day | Total sd, bp/day (print window) |
|---|---:|---:|---:|---:|---:|
| 2y | 0.3383 | -0.5378 | 0.6533 | 0.39 | 5.26 |
| 3y | 0.3785 | -0.4176 | -0.0498 | 0.78 | 5.28 |
| 5y | 0.4097 | -0.1897 | -0.4024 | 0.55 | 5.42 |
| 7y | 0.4177 | 0.0127 | -0.4127 | 0.51 | 5.39 |
| 10y | 0.3945 | 0.1986 | -0.1959 | 0.57 | 4.96 |
| 20y | 0.3583 | 0.4450 | 0.2255 | 0.54 | 4.83 |
| 30y | 0.3406 | 0.5125 | 0.3864 | 0.49 | 4.78 |

| | Level | Slope | Curvature |
|---|---:|---:|---:|
| Daily sd, bp (factor units) | 13.28 [12.75, 13.78] | 4.47 [4.25, 4.75] | 1.51 [1.43, 1.59] |
| Share of variance | 87.8 % | 10.0 % | 1.1 % |

A one-standard-deviation day of the level factor moves the 10-year yield by 0.3945 x 13.28 = 5.2 bp.

Stability across subperiods (`cal.json: subperiods`), each projected on the full-window loadings:

| Subperiod | Days | Level sd | Slope sd | Curvature sd | Cosine of own loadings with full-window | Variance explained by full-window loadings (own three) |
|---|---:|---:|---:|---:|---|---|
| 2015-2018 | 999 | 10.07 [9.57, 10.56] | 3.20 | 1.21 | 0.992, 0.987, 0.985 | 98.6 % (98.6 %) |
| 2019-2021 | 752 | 10.95 [9.94, 11.92] | 4.05 | 1.51 | 0.985, 0.978, 0.962 | 98.4 % (98.5 %) |
| 2022-2025 | 998 | 17.13 [16.13, 18.10] | 5.69 | 1.76 | 0.995, 0.994, 0.993 | 99.2 % (99.2 %) |

Reading: the *shape* of the factors is stable (cosines 0.96 and above; the fixed loadings lose under 0.1 percentage points of explained variance in any subperiod). The *scale* is not: the level factor's volatility was 70 % higher in 2022 to 2025 than in 2015 to 2018. The full-window value 13.28 is an average of regimes, and the simulator should be run at 10 and 17 as a sensitivity (design document, section 12).

The par curve is a fitted summary published once a day, not tradable prices, and it is the only Treasury input. The factor model is therefore a model of the Treasury curve; section 5 covers what swaps add.

## 5. Swap-spread factor (bounded: the data gives an upper figure)

Method (`simcal.factors.spread_fit`, `cal.json: spread`): daily swap level per tenor = median strict print 14:30 to 16:00 New York when at least 5 prints exist, converted from annual Act/360 to a semi-annual bond basis; spread = swap level minus par yield; daily changes over consecutive business days.

Coverage decides what can be said. Days with a closing level out of 288: 10y 251, 5y 216, 30y 174, 2y 116, 3y 84, 7y 34, 20y 4. So 7 and 20 years cannot be tested at all, and 3 years barely.

| Tenor | Daily changes | Mean spread, bp | sd of change, bp | Noise sd of one level, bp | sd after removing noise, bp | R2 on curve factors |
|---|---:|---:|---:|---:|---:|---:|
| 2y | 94 | -18.1 | 2.27 | 1.32 | 1.30 | 0.19 |
| 3y | 55 | -23.6 | 2.24 | 1.32 | 1.23 | 0.20 |
| 5y | 212 | -31.9 | 1.46 | 0.77 | 0.98 | 0.14 |
| 10y | 249 | -47.2 | 1.40 | 0.73 | 0.94 | 0.22 |
| 30y | 163 | -79.1 | 1.66 | 0.00 | 1.66 | 0.20 |

"Noise" is estimated from the negative lag-1 autocovariance of the changes (a level measured with independent error e has changes with autocovariance -var(e)).

What this supports and what it does not:

- Swap and Treasury changes are highly but not perfectly correlated (10y: correlation 0.95, regression slope 0.95 on 249 days). A spread component exists in the measured series.
- Its size is an **upper figure**. The swap level is a median over 90 minutes; the par curve is quoted near 15:30. With a mid that moves 0.12 bp per sqrt-minute, a timing mismatch of half an hour alone produces about 0.7 bp of apparent spread change, and this error is common to all tenors on a day, so the cross-tenor correlation of the residuals (0.49 to 0.70 at 5, 10, 30 years; first component 72 % of residual variance, loadings 0.58, 0.54, 0.61 on 129 joint days) does not prove a common spread factor either. The autocovariance correction removes independent error, not this common timing error.
- Used in the parameter file: one spread factor with loading 1 at every tenor, daily sd **0.7 bp, band 0.3 to 1.3**, random walk within the day (the daily level autocorrelation is 0.92 to 0.96 at 5 to 30 years, so mean reversion inside a day is negligible). The regression of the spread on curve factors is set to zero: the measured betas (about -0.02 on level, -0.1 to -0.16 on slope) are of the size the timing error would produce.

A closing swap curve from a source that timestamps both legs together would settle this; none is available without an account.

## 6. Print noise, intraday volatility, seasonality

### 6.1 Model and estimators

A print of tenor T at time t is r = M(t) + q x: M the unobserved mid, q = +1 or -1 the unknown side, x >= 0 the half-spread paid. For two prints of one tenor and day,

    E[(r_j - r_i)^2] = eta_i^2 + eta_j^2 + sigma^2 (t_j - t_i),   eta^2 = E[x^2].

The intercept gives the dispersion eta without any trade direction, the slope gives the mid variance per minute. Two estimators of this relation are reported.

1. **Least squares** (the estimator registered in the note; `micro.est_basic`): regression of squared differences on the gap, pairs up to 5 prints and 30 minutes apart.
2. **Median-based** (`micro.est_robust`; added on the calibration period, before the validation read): for consecutive prints in a gap bin, V(g) = (median |difference| / 0.6745)^2, then V(g) = 2 eta^2 + sigma^2 g fitted on the bins from 2 minutes up (`eta_long`), and eta read directly from gaps under 10 s (`eta_short`).

Why the second was needed. In the registered sample the least-squares dispersion is 0.70 bp pooled, and 1.53 bp for prints off the D2C facilities against 0.52 bp on them (`registered.ls_eta`, `ls_eta_by_group`). These are not half-spreads. For pairs of 10-year prints under 10 s apart, the median absolute difference is 0.10 bp and the root mean square is 0.69 bp: a few percent of prints sit 0.5 to 3 bp from their neighbours (a different clearing venue or convention not visible in the public fields, fee-inclusive rates, late or mistimed reports). Squared-difference estimators measure that tail. The known-answer test `test_robust_signature_recovers_dispersed_half_spread_with_outliers` shows the same behaviour on synthetic prints with 5 percent contaminated.

### 6.2 Results (calibration period)

| Tenor | sigma, bp per sqrt-minute (strict, median-based) | eta_long, bp (strict D2C) | eta_short, bp (strict D2C) | sigma least squares (registered) | eta least squares (registered) |
|---|---|---|---|---|---|
| 2y | 0.116 [0.100, 0.129] | 0.170 [0.110, 0.217] | 0.103 [0.074, 0.122] | 0.242 | 0.976 |
| 3y | 0.128 [0.114, 0.142] | 0.082 [0.000, 0.170] | 0.053 [0.045, 0.106] | 0.261 | 0.869 |
| 5y | 0.118 [0.108, 0.133] | 0.236 [0.197, 0.263] | 0.092 [0.073, 0.106] | 0.195 | 0.766 |
| 7y | 0.163 [0.138, 0.192] | 0.115 [0.000, 0.249] | 0.105 [0.063, 0.354] | 0.207 | 0.731 |
| 10y | 0.114 [0.100, 0.125] | 0.257 [0.232, 0.284] | 0.102 [0.085, 0.105] | 0.185 | 0.668 |
| 20y | 0.134 [0.104, 0.162] | 0.235 [0.129, 0.312] | 0.094 [0.052, 0.265] | 0.219 | 0.461 |
| 30y | 0.143 [0.129, 0.158] | 0.209 [0.158, 0.240] | 0.113 [0.104, 0.133] | 0.284 | 0.452 |
| pooled | **0.123 [0.117, 0.131]** | **0.227 [0.207, 0.246]** | **0.103 [0.096, 0.105]** | 0.227 | 0.697 |

Keys: `strict.robust_sigma`, `strict_d2c.robust_eta_long`, `strict_d2c.robust_eta_short`, `registered.ls_sigma`, `registered.ls_eta`.

**The two dispersion figures disagree, and the disagreement is informative.** The level V(g) - sigma^2 g should be flat at 2 eta^2 if sides were independent across prints. Pooled over tenors it rises with the gap (`strict_d2c.robust_level_pooled`): 0.020 under 10 s, 0.026 at 10 to 30 s, 0.040 at 30 to 60 s, 0.059 at 1 to 2 min, 0.093 at 2 to 5 min, about 0.10 beyond. Prints seconds apart are closer to each other than a common mid plus independent half-spreads allows. Causes that cannot be separated with these fields: several prints from one request (allocations at one price level, a client trade and its immediate dealer hedge), sides that cluster, and noise that is not half-spread at all but accumulates with time (clock error on execution timestamps, small differences between dealers' mids). So:

- `eta_short` = 0.10 bp is a **lower** figure for the root-mean-square half-spread (same-side clustering shrinks it; the rounding of 28 % of rates to 0.1 bp also sits at this scale);
- `eta_long` = 0.23 bp is an **upper** figure (it contains everything that is not mid diffusion over minutes).

The parameter file uses the geometric mean 0.153 bp with the two figures as the band. In bp of yield the dispersion is flat across tenors within its intervals; no tenor exponent is supported.

Bias statement asked for in the brief: the SDR mixes client-to-dealer and dealer-to-dealer prints and does not flag direction. Prints at the mid pull eta down; restricting to the two D2C facilities removes most interdealer prints (5,009 of 68,798 strict prints are on other facilities or off-facility) but dealer hedges on D2C facilities remain. Product differences inside a bucket push eta up; the strict subset removes the part visible in dates.

By size bucket and facility group, least squares only (`ls_eta_by_bucket`, `ls_eta_by_group`; the median version has too few short-gap pairs per bucket):

| Bucket (USD DV01) | Prints, strict D2C | eta least squares, strict D2C | registered |
|---|---:|---|---|
| under 5k | 20,263 | 0.587 [0.517, 0.649] | 0.677 |
| 5k to 25k | 29,577 | 0.489 [0.410, 0.568] | 0.573 |
| 25k and over | 13,634 | 0.465 [0.352, 0.560] | 0.898 |
| capped | 315 | 0.921 [0.470, 1.352] | 1.324 |

The levels are tail-driven (section 6.1) and are not half-spreads. The ordering is the usable part: on the D2C facilities dispersion does not rise with size up to the cap (small prints are the most dispersed), so the parameter file uses no size exponent. In the registered sample the 25k-and-over bucket is wider only because large prints are more often off the D2C facilities, where the dispersion is three times larger.

### 6.3 Intraday share of daily variance

sigma^2 x 600 minutes, over the variance of the daily par-yield change of the same tenor in the same window (`intraday_share_of_daily_variance`): 2y 0.29, 3y 0.36, 5y 0.29, 7y 0.55, 10y 0.31, 20y 0.46, 30y 0.54; pooled 0.344. About a third of daily variance is ordinary diffusion inside the 07:00 to 17:00 session. The rest is event jumps (next paragraph) and the 14 hours outside the session. The simulator therefore scales the daily factor model by sqrt(0.344 / 600) per sqrt-minute: level 0.318, slope 0.107, curvature 0.036 in factor units. Check: this implies 0.127 bp per sqrt-minute at 10 years against 0.114 [0.100, 0.125] measured.

### 6.4 Seasonality

Pooled regression with 30-minute bins and three event windows (`micro.est_season`, least squares, `strict.season`). Event windows, as extra variance per minute in multiples of the average rate:

| Window | Event days in sample | Calibration | Validation |
|---|---:|---|---|
| 08:30 to 08:35, release days (employment, CPI, GDP, personal income) | 46 | 55.2 [29.5, 98.4] | 43.3 [18.6, 107.2] |
| 13:00 to 13:05, coupon auction days | 91 | 4.9 [0.4, 11.9] | 1.3 [-5.1, 11.0] |
| 14:00 to 14:30, FOMC statement days | 9 | 9.2 [3.4, 15.7] | 8.7 [-1.7, 15.6] |

The release window is large and repeats in validation. Independent check from daily data only: in the same window the mean squared daily par-yield change is 37.8 bp^2 on release days and 23.7 bp^2 on other days, an excess of 14.1 bp^2; the print estimate, converted to bp^2 (55.2 x 5 minutes x the least-squares variance per minute, equal to 907 normal minutes at the median-based rate), is 13.7 bp^2. The auction and FOMC windows have intervals that exclude zero in calibration and include it in validation (4 FOMC days); they are kept in the parameter file with their intervals. The auction time is assumed to be 13:00 for every coupon auction; some close at 11:30, which biases that estimate down.

The 30-minute multipliers themselves are **not** used. Strict versus strict D2C, bins from 07:00: 0.95/1.69, -0.15/0.06, 1.30/1.91, 0.82/1.36, 0.72/1.26, 1.66/2.06, 1.40/2.27, 3.07/0.86, 2.61/1.35, 1.24/0.51, ... Several are negative, the intervals are as wide as the estimates, and removing 7 % of prints moves single bins by a factor of three. The data does not support a time-of-day curve for variance beyond the event windows; the simulator is flat between them.

## 7. Arrivals and sizes (fitted)

Share of the day's strict D2C prints per 30-minute bin from 07:00 (`strict_d2c.time_of_day_share`): 1.9, 3.4, 5.4, 7.5, 6.0, 6.2, 7.1, 6.5, 6.7, 5.9, 4.8, 4.5, 4.3, 3.8, 3.9, 4.4, 6.3, 4.5, 4.1, 2.7 percent. Peak 08:30 to 09:00, a second rise 15:00 to 15:30. Tenor mix: 2y 12.0, 3y 8.4, 5y 20.4, 7y 5.5, 10y 33.7, 20y 4.2, 30y 15.8 percent.

Sizes, right-censored lognormal of the notional (`sizes_strict_d2c`, `flow.fit_censored_lognormal`):

| Tenor | Prints | Median notional, USD mm | Log sd | Capped share | Cap seen, USD mm | DV01 quartiles and 95th, USD |
|---|---:|---:|---:|---:|---:|---|
| 2y | 7,665 | 35.8 | 1.32 | 0.4 % | 1,100 | 3.0k, 7.4k, 14.9k, 49.0k |
| 3y | 5,380 | 25.9 | 1.35 | 1.1 % | 650 | 3.1k, 7.0k, 15.5k, 72.8k |
| 5y | 12,996 | 17.2 | 1.40 | 0.4 % | 650 | 3.2k, 8.2k, 21.1k, 63.2k |
| 7y | 3,511 | 19.1 | 1.37 | 1.1 % | 470 | 4.9k, 11.0k, 29.7k, 97.9k |
| 10y | 21,502 | 13.3 | 1.22 | 0.3 % | 470 | 4.9k, 9.9k, 24.9k, 69.7k |
| 20y | 2,676 | 7.6 | 1.31 | 0.9 % | 250 | 5.4k, 9.6k, 24.3k, 61.6k |
| 30y | 10,059 | 7.1 | 1.19 | 0.4 % | 250 | 5.3k, 14.0k, 27.5k, 59.0k |

The median DV01 is close to 7k to 14k USD at every tenor: clients size in risk. The old ladder (10 to 100 mm at every swap tenor) put the typical 10-year RFQ at 20k to 80k USD of DV01, several times too large.

Prints are completed trades of the whole market. The number of RFQs one dealer sees is not in the data: `desk_inquiry_share` (0.5, band 0.2 to 1.0) is an assumption, giving 116 swap RFQs per session (47 to 233).

## 8. Post-trade drift (markouts)

### 8.1 Method and its bias

Direction is not reported. Classification as registered: s = sign(print rate - trailing mid), trailing mid = median of the last five prints of the tenor in the previous 10 minutes; second classification, the tick rule. Markout at horizon h: s x (r_ref - r), r_ref the first later print at or after t + h within a search window. A negative value means the later print is back towards the mid (the dealer kept part of the spread); adverse drift makes it less negative.

Under the model of section 6.1 the mean of -s (r_ref - r) is (1 - 2 eps) x (half-spread - drift - rho x half-spread), eps the probability of a wrong sign, rho the correlation of the reference print's side with this print's side.

- **Classification error biases the estimate towards zero**, because a wrong sign flips the contribution of both the spread and the drift. It cannot change the sign. The estimated error rate is high: 37 % for all sizes in the strict D2C subset (`sign_error_probability`), so the attenuation factor 1 - 2 eps may be as low as 0.26. The synthetic test `test_signed_markout_is_attenuated_towards_zero` recovers about half of a known drift, as this predicts.
- A stale trailing mid adds a bias **away from zero in the drift direction** if the markout is measured from the trailing mid (the classification then selects on the mid's own recent move). For that reason the markout here is measured from the print's own rate, not from the trailing mid.
- Positive side correlation rho (clients trading the same way within minutes) makes the reference print share the spread and biases the realised spread towards zero and the implied drift up.

The registered model-based correction (`drift_5min_registered_hi/lo`) is reported in `cal.json` but **not used**: it combines the tail-driven least-squares dispersion with the signed means and returns drifts of 0.06 to 0.56 bp depending on the bucket, which is the tail again, not information.

### 8.2 Results: strict D2C, calibration period, bp, [95 % interval], n

| Bucket | 1 s (mean delay 3 s) | 5 s (10 s) | 30 s (45 s) | 60 s (88 s) | 5 min (354 s) |
|---|---|---|---|---|---|
| under 5k | -0.062 [-0.106, -0.014], 147 | -0.070 [-0.123, -0.022], 293 | -0.001 [-0.038, 0.031], 811 | -0.010 [-0.049, 0.027], 1,272 | -0.023 [-0.077, 0.032], 2,054 |
| 5k to 25k | -0.046 [-0.083, -0.006], 205 | -0.059 [-0.094, -0.026], 384 | -0.041 [-0.072, -0.005], 991 | -0.052 [-0.086, -0.016], 1,624 | -0.059 [-0.111, -0.003], 2,550 |
| 25k and over | -0.081 [-0.163, -0.008], 94 | -0.045 [-0.096, 0.008], 186 | -0.065 [-0.105, -0.025], 489 | -0.056 [-0.108, -0.008], 849 | -0.050 [-0.104, 0.007], 1,280 |
| capped | n = 2 | n = 0 | n = 12 | n = 6 | n = 18 |
| all | -0.059 [-0.088, -0.029], 448 | -0.059 [-0.082, -0.037], 863 | -0.034 [-0.056, -0.013], 2,303 | -0.039 [-0.064, -0.014], 3,751 | -0.046 [-0.084, -0.004], 5,902 |

Keys: `strict_d2c.markout_raw`, `markout_n`, `markout_delay_s`. The tick-rule classification gives the same picture (all sizes: -0.058, -0.040, -0.050, -0.047, -0.038; `markout_raw_tick`). In the registered sample the values are three times larger (all sizes -0.18 to -0.14) because of the off-facility tail.

What this supports:

1. **The realised half-spread is positive.** At every horizon the all-sizes interval excludes zero; at 5 minutes the raw value is 0.046 [0.004, 0.084] bp in calibration and 0.086 [0.050, 0.122] in validation. Since misclassification only shrinks it, the true realised half-spread at 5 minutes is at least this.
2. **No build-up of drift is detectable between 3 s and 5 minutes.** Change of the all-sizes markout from the first horizon to 5 minutes: +0.013 [-0.030, +0.060] bp in calibration, -0.027 [-0.082, +0.026] in validation (`markout_change_first_to_5min`). By bucket the intervals are wider and all include zero except the capped bucket (18 prints).
3. **The 1 s and 5 s horizons are not what their names say.** A reference print within 1 to 5 s exists for 448 of 63,789 prints, and those pairs are the clustered prints of section 6.2. The table reports them because the brief asks for them; they carry no weight in the parameter file.
4. **No size dependence is detectable.** The three uncapped buckets have overlapping intervals at every horizon.
5. The direction-free check (`ls_signature_h_realised_drift`: short-gap level minus the 30 to 120 s level) gives a "drift" of -0.20 [-0.27, -0.13]: the wrong sign for adverse selection, for the reason in section 6.2. It does not support a drift either.

So the prints say that a D2C swap dealer keeps a positive spread after 5 minutes and they do not show information arriving after the first seconds. They cannot say how much of the half-spread was lost to an adverse move in the first seconds, because that needs the half-spread itself, which is only bounded (section 6.2).

## 9. Competitor model at zero expected profit

Model (`simcal.competitor`): n competing dealers each quote a half-spread m exp(s Z); the client trades at the lowest. The RFQ goes to 3 to 5 dealers of which one is the desk and each other answers with probability 0.9 (kept from the present presets), so 0 to 4 competitors answer. With log-sd s = 0.5 the lowest answering quote has mean 0.761 m and root mean square 0.835 m. The winner breaks even when

    E[winning half-spread] = adverse move + all-in cost.

Inputs: winning root-mean-square half-spread 0.153 bp (range 0.103 to 0.227, section 6.2) gives m = **0.183 bp** (0.123 to 0.272) and an expected winning half-spread of 0.139 bp. The all-in cost of a competitor (hedge spread, fees, capital) equals its realised half-spread at zero profit; the markouts give at least 0.046 bp and, with the attenuation of section 8.1, plausibly up to three times that. The value 0.08 bp (band 0.05 to 0.15) is an assumption inside that range. The adverse move is then 0.139 - 0.08 = **0.059 bp** per trade on average (range 0 to 0.20: zero if cost absorbs the whole spread; 0.20 = the largest expected winning spread, 0.207, minus the smallest realised spread, 0.004). `python -m ratesresearch.simcal competitor` prints the zero-profit median over the bands; for the central adverse move and cost it is 0.183 bp at log-sd 0.5.

**Comparison with the constants being replaced** (`sim_v2_params.json: old_constants_check`):

| | Old constants | Data |
|---|---|---|
| Winning half-spread, root mean square | 0.209 bp (median 0.25, log-sd 0.5) | 0.103 to 0.227: **inside**, at the upper end |
| Adverse move per trade | 0.20 bp (0.2 x 4 x 0.25), on one instrument, as a jump at 30 s | at most 0.20 and only with the widest spread and the smallest cost; central 0.06; no jump at 30 s visible (change 30 s to 5 min: -0.011 [-0.050, 0.030]) |
| Realised half-spread at 5 min | 0.190 - 0.20 = -0.010 bp | +0.046 [0.004, 0.084], validation +0.086 [0.050, 0.122], both lower figures: **outside** |
| Winner's profit after the 0.086 bp hedge cost the review measured | -0.096 bp | zero by construction |

The old median is not far wrong. The old adverse-selection constants are: they make the average winning dealer lose money before any hedge cost, which a market in equilibrium cannot sustain and which the prints contradict in sign. The calibration interval's lower end (0.004) is close to zero, so on the calibration period alone the contradiction is by a small margin; the validation period confirms it with room.

## 10. Validation period

One read, with the code and `cal.json` frozen at commit `e09e3b8` (`val.json: comparison`).

| Pass mark (from the note) | Result |
|---|---|
| Scale parameters within a factor 1.5 | **30 of 34 pass.** Factor sds: ratios 0.85, 0.79, 0.75. Print volatility: all 8 pass (pooled 0.109 against 0.123, ratio 0.88). Dispersion: pooled eta_long 0.183 against 0.227 (0.81), pooled eta_short 0.097 against 0.103 (0.94); 4 single-tenor failures, all at the thin tenors (3y and 20y eta_long fitted as zero, 7y both about 2.0). Prints per day: all 7 pass (ratios 1.02 to 1.34). |
| Loadings: cosine at least 0.90, explained variance within 5 points | **Pass.** Cosines 0.996, 0.995, 0.936; calibration loadings explain 98.85 % against 98.94 % for the period's own. |
| Markouts at 5 min: intervals overlap, same sign | **4 of 5 pass.** All sizes -0.086 [-0.122, -0.050] against -0.046 [-0.084, -0.004]: pass. Under 5k fails (validation -0.143 [-0.193, -0.090] against -0.023 [-0.077, 0.032]). |

Reading: the calibration describes the validation half-year for everything the simulator uses pooled or at the liquid tenors. It does not for per-tenor dispersion at 3, 7 and 20 years, which is why the parameter file carries one dispersion for all tenors. Every 2026 scale is lower than its calibration value (volatility 12 to 25 % lower): a regime difference of the kind section 4 shows, inside the pass mark. The under-5k markout failure means the small-trade realised spread is not stable; nothing in the parameter file depends on a bucket-level markout. No parameter was changed after the validation read.

## 11. Limitations

- Prints are executions. Nothing here observes a quote, an RFQ, a cover price, a hit rate or a client. The competitor model's dispersion across dealers, the number of dealers, the response probability and the client's reserve are assumptions carried over from the present presets.
- Trade direction is inferred, with an estimated one-in-three error rate. All signed results are attenuated by an unknown factor between about 0.26 and 1.
- Prints of a tenor are minutes apart; horizons under 30 s are not identified, and "the adverse move is complete within seconds" and "there is little adverse move" cannot be told apart.
- The dispersion is bounded (0.10 to 0.23 bp), not estimated. The simulator's economics are sensitive to it and the band must be run.
- Least-squares statistics on this data are dominated by a few percent of odd prints. Where a least-squares number appears above it is labelled; only the event-window multipliers use one, and they are cross-checked against daily data.
- Notionals are capped (0.3 to 1.1 % of prints); the size fit treats caps as censoring. Merged prints sum notionals of pieces at one price and second.
- 2024 is sampled one day a week (the first download's design) and the first 17 files are no longer obtainable.
- One Treasury curve input, fitted, daily. No Treasury trades, no futures, no order book: sections 13 and 14 of the design document mark every such parameter as an assumption with a band.
- Volatility differs across years by a factor of 1.7; a single calibrated level is a convenience.
- The swap-to-bond conversion ignores payment-date detail (under 0.1 bp), which matters for the spread level, not for its changes.

## 12. Tests

`research/tests/test_simcal.py`, 23 tests, no market data: PCA recovers simulated loadings and factor sds; the spread fit separates a known noise sd from a known signal sd; the parser's filter funnel on a hand-written file in the DTCC layout; least-squares and median-based signatures recover a known half-spread and volatility, the median one also with 5 % contaminated prints; the direction-free signature recovers a known drift within its interval and returns zero when there is none; the signed markout is shown to be attenuated towards zero; the classification error rate is recovered; event-window variance is recovered; the order-statistic constants against closed form and simulation; the committed parameter file is complete, sourced and satisfies the zero-profit identity; the new manifest's hashes equal the first download's.
