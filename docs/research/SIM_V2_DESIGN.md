# Simulator specification v2 (Round 3)

Date: 2026-11-23. Parameters: `data/calibration/sim_v2_params.json` (called P below; `P.a.b` is the `value` of entry `a.b`). Evidence for each parameter: `docs/research/SIM_V2_CALIBRATION.md`. This document is the whole specification for the Java implementation. Where it says "assumption", the value is not supported by data and its band in P must be run as a sensitivity.

Status of every statement about the present code: read from the source on 2026-11-23 (file and line given), not from a run.

## 1. What changes and why

| Defect (review 2026-11-23) | Present code | Replacement |
|---|---|---|
| No common rate factor; a hedge cannot reduce risk | `D2cRfqSimulator.midNow` (lines 159-171): one independent random walk per instrument | Section 3: three curve factors, a swap-spread factor, a futures basis, small idiosyncratic terms |
| Mid rounded to the tick on every read; volatility depends on the polling interval; draws depend on when the mid is read | same lines; one shared `SimRng` | Sections 2 and 4: unrounded latent state on a fixed 1 s grid, each step's draws fixed by (seed, stream, step) |
| Informed flow is a deterministic jump of 4 half-spreads on one instrument 30 s after a trade, only if the RFQ trades | lines 198, 556-562 | Section 6: information is a drift of the level factor, starts when the informed client arrives, whoever wins |
| Competitors cannot break even | `presets.json` d2c.default | Section 7: median and adverse move tied by the zero-profit condition |
| Our orders have no impact; the book refills every second | `ClobLiquidity` (rates-app), 1 s cancel and repost | Section 8: other takers, square-root impact, exponential resilience |
| A second simulator with other constants | `SyntheticDay`, `data/presets.yaml: usd_synthetic_market` and others | Section 10: one market model class, used by both |

## 2. Time, state and determinism

**Clock.** A session is one trading day, 07:00:00 to 17:00:00 New York (`P.session`), K = 36,000 steps of 1 s. Step k covers [t0 + k s, t0 + (k+1) s). All latent state is constant within a step and changes at step boundaries. A read at time t returns the state of step floor((t - t0) / 1 s). Before 07:00 the state is the initial state; after 17:00 it is the last state.

**Latent state** (IEEE doubles, never rounded):

| Symbol | Meaning | Unit |
|---|---|---|
| F1, F2, F3 | curve factors: level, slope, curvature | bp, factor units |
| S | swap-spread factor | bp |
| B | futures basis (one per futures contract) | bp of yield |
| e_T, T in {2, 3, 5, 7, 10, 20, 30} | idiosyncratic term per tenor node | bp |
| G | level-factor drift not yet released (informed flow, section 6) | bp, factor units |
| X1 | endogenous level-factor offset from our own permanent impact (section 8) | bp, factor units |
| J_i | transient displacement of instrument i's order book | price units of i |

All start at zero. Ticks are applied only where a price leaves the simulator: a competitor's quote, an order-book level, a published reference price. No tick-rounded value is ever written back into the latent state.

**Random numbers.** One 64-bit session seed. Every random quantity belongs to a named stream; stream s of the session is `SplitMix64.forStream(seed, s)` (the class already in `rates-backtest/.../util/SplitMix64.java`; move it to `rates-common` so both modules use the same one). Stream identifiers are fixed constants:

| Stream id | Used for | Draw order |
|---|---|---|
| 1, 2, 3 | F1, F2, F3 increments | one normal per step, in step order |
| 4 | S increments | one normal per step |
| 10 + c | basis of futures contract c | one normal per step |
| 20 + n | e_T for node index n = 0..6 | one normal per step |
| 100 | RFQ arrival counts and times | per 30-minute bin: one Poisson count, then that many uniforms |
| 1,000,000 + r | everything about RFQ number r (0-based in arrival order) | fixed order, section 5 |
| 200 + i | other takers on order-book instrument i | per step: one Poisson count, then per trade one uniform (sign), one normal (size) |
| 300 + i | depth noise of instrument i, if used | one normal per step |

A normal is produced from **one** 64-bit draw: u = (x >>> 11 + 0.5) x 2^-53, z = inverse normal distribution function of u by Wichura's algorithm AS 241 (PPND16). No rejection methods, so that the number of draws never depends on the values. A Poisson count with mean under 30 is produced by inversion from one uniform (cumulative sum of probabilities); larger means are not needed.

**Consequence.** The exogenous path (everything except X1 and J_i) is a function of the seed and P only. The implementation may compute it step by step as the clock passes or all at once at session start; the two must give bit-identical state. The second is recommended, because sections 6 and 8 need the path a few seconds ahead.

## 3. Rate model

**Per step** (dt = 1/60 minute), for j = 1, 2, 3:

    F_j[k+1] = F_j[k] + s_j * sqrt(dt) * sqrt(m[k]) * Z_j[k] + release_j[k]
    S[k+1]   = S[k]   + s_S * sqrt(dt) * sqrt(m[k]) * Z_S[k]
    e_T[k+1] = e_T[k] + s_eT * sqrt(dt) * Z_T[k]
    B[k+1]   = B[k] * exp(-kappa / 60) + sd_B * sqrt(1 - exp(-2 kappa / 60)) * Z_B[k]

- s_2, s_3 = `P.curve_factors.sd_per_sqrt_minute[1], [2]` (0.1070, 0.0362); s_S = `P.swap_spread_factor.sd_per_sqrt_minute` (0.0168); s_eT = `P.curve_factors.idiosyncratic_sd_per_sqrt_minute[T]`.
- s_1 is the level factor's **diffusive** part. The calibrated total is `P.curve_factors.sd_per_sqrt_minute[0]` = 0.3178; the information drift of section 6 supplies part of that variance, so s_1^2 = 0.3178^2 - V_inf / 600, with V_inf defined in section 6. At central values V_inf = 11.38 bp^2 and s_1 = 0.2864. If V_inf exceeds 60 % of 0.3178^2 x 600 the configuration is rejected at start-up.
- m[k] = 1, plus for each event that the session's day type has, `P.curve_factors.event_windows.<name>` while step k is inside the window: release 08:30:00 to 08:35:00 (181.4), auction 13:00:00 to 13:05:00 (16.3), FOMC 14:00:00 to 14:30:00 (30.2). Outside these windows the multiplier is flat (`time_of_day_multiplier` = 1). The day type is part of the session configuration (section 12), not random.
- release_1[k] = G[k] x (1 - exp(-1 s / tau)), G[k+1] = G[k] - release_1[k] + (arrivals of informed RFQs in step k, section 6); release_2 = release_3 = 0. tau = `P.informed_flow.drift_time_constant` (10 s).
- kappa = ln 2 / `P.futures_basis.half_life` per minute (half-life 14 min), sd_B = `P.futures_basis.stationary_sd` (0.15 bp). Assumption.

**Yield change at a tenor node** T since the session start:

    dy_T = L[T,1] * (F1 + X1) + L[T,2] * F2 + L[T,3] * F3 + e_T

L = `P.curve_factors.loadings`. For a maturity between nodes, L and e are interpolated linearly in maturity between the two neighbouring nodes; below 2 years use the 2-year node, above 30 the 30-year node.

**Instruments** (latent mid, unrounded):

| Instrument | Latent mid | Notes |
|---|---|---|
| Treasury i, remaining maturity T_i | P_i = P_i0 + pcpb_i x dy_{T_i} | pcpb_i = price change per bp, negative, from reference data at session start (the back-test's `InstrumentSet` values -0.0190 for 2y, -0.0815 for 10y are of this kind). Linear in yield: for a 20 bp move the neglected convexity is under 0.02 points at 10 years |
| SOFR swap i, tenor T_i | r_i = r_i0 + dy_{T_i} + S | in the rate unit of the instrument (percent: divide bp by 100). The spread loading is 1 at every tenor (`P.swap_spread_factor.loading_by_tenor`). The 1 to 2 percent difference between a bp of annual Act/360 swap rate and a bp of semi-annual bond yield is ignored |
| Treasury future c on node T_c | Q_c = Q_c0 + pcpb_c x (dy_{T_c} + B_c) | TY on the 7-year node (pcpb -0.0715 as in `InstrumentSet.TY_FUTURE`); TU on the 2-year node. Other contracts are optional |

Cross-instrument consistency follows: a swap and a Treasury of one tenor differ only by S and by nothing else; a future and its node differ only by B. The theoretical variance reduction of any hedge is computable from P in closed form, which acceptance test A4 uses.

## 4. What a reader of the mid gets

- `latentMid(i, t)`: the unrounded value above at step floor(t). Used by competitor quotes, by the book builder, and by P&L marking in research harnesses.
- `referencePrice(i, t)`: `latentMid` rounded to the instrument's tick, half away from zero. This is what a venue publishes when it must publish a mid. It is derived on each read and never stored.

Marking in every research report uses `latentMid`. This removes the rounding noise that the present marking carries and makes P&L invariant to tick size where no trade occurs.

## 5. RFQ arrivals and attributes

**Count and times** (stream 100). N_swap = `P.rfq.swap_rfqs_per_day` (116.4); N_tsy = N_swap x `P.rfq.treasury_rfqs` (assumption, 1.0). For each 30-minute bin b = 0..19: count ~ Poisson((N_swap + N_tsy) x `P.rfq.time_of_day_share[b]`), then for each RFQ a uniform position inside the bin, truncated to the millisecond. Sort all arrivals by time; ties keep generation order. The shape is the average over all days and already contains release days; a separate arrival bump on release days is not identified and is not modelled.

**Attributes of RFQ r** (stream 1,000,000 + r, draws in exactly this order):

1. asset class: swap with probability N_swap / (N_swap + N_tsy).
2. tenor: swaps from `P.rfq.tenor_mix`, mapped to the instrument of that tenor; Treasuries uniform over the on-the-runs.
3. client side: buy or sell, 50/50.
4. size: swaps, notional = exp(N(log_mean_mm, log_sd^2)) from `P.rfq.size_lognormal_by_tenor`, redrawn if above cap_mm (at most 20 redraws, then cap), rounded to 1 mm, minimum 1 mm. Treasuries: the present ladder (1, 5, 10, 25, 50 mm with weights .35, .35, .20, .08, .02), assumption.
5. client tier: A, B, C with weights .5, .3, .2; client id fixed per tier.
6. informed: yes with probability .25, .60, .975 for tier A, B, C (mean = `P.informed_flow.informed_fraction` = 0.5). Assumption.
7. number of dealers asked: uniform integer 3 to 5; the desk is one of them.
8. for each competing dealer d in order: answers (probability 0.9); delay (lognormal, median 800 ms, log-sd 0.6; 5 s or more means no answer); half-spread (section 7).
9. client reserve (section 7).

All of step 8's draws are made whether or not the dealer answers, so the stream position does not depend on outcomes.

## 6. Client types and information

An uninformed client's side is independent of everything. An informed client knows which way the level factor will drift. Mechanism:

- When informed RFQ r arrives in step k, define d = +1 if the client's side gains from **higher** yields (pays fixed on a swap; sells a bond) and -1 otherwise. Add to the pending drift: G += d x A / L[T_r, 1], with A = `P.informed_flow.move_per_informed_trade` (0.118 bp at the traded tenor) and T_r the RFQ's tenor.
- G is released into F1 at rate 1 - exp(-1 s / tau) per step (section 3). After a few tau the level factor has moved by d x A / L[T_r,1], so the traded tenor has moved by A and every other tenor by A x L[T,1] / L[T_r,1].
- This happens **whether or not the RFQ trades and whoever wins**. The information is about the market; the trade does not cause it. A dealer who passes avoids the loss; a dealer who wins and hedges the level exposure within a few seconds avoids most of it; a dealer who hedges in a correlated instrument is protected to the extent of the correlation. These are the behaviours Round 5 is meant to study.
- No size dependence: none is identified (calibration document, section 8).

Consistency with the measured markouts. The expected move against the winning dealer, averaged over trades, is 0.5 x 0.118 = 0.059 bp = `P.informed_flow.expected_adverse_move`, complete to 95 % after 3 tau = 30 s. The data cannot tell 2 s from 60 s (band in P), and shows no build-up after the first seconds, so tau is short and an assumption.

Variance bookkeeping. Informed arrivals add variance to the level factor:

    V_inf = N_inf x A^2 x sum over RFQ tenors of mix_T / L[T,1]^2,   N_inf = informed_fraction x (N_swap + N_tsy)

with Treasuries using a uniform mix over the on-the-runs. With swaps only in the mix and N_swap + N_tsy = 232.8 this is 116.4 x 0.0977 = 11.38 bp^2 per session, 19 % of the level factor's 60.6 bp^2. The implementation computes V_inf from P at start-up and sets s_1 as in section 3, so that the total stays at the calibrated value. This is why the informed fraction is 0.5 and not the old 0.2: at 0.2 with the same average the desk's own inquiries would explain 47 % of the market's level variance, which is not credible for one dealer.

## 7. Competitor quotes and the client's decision

- Competitor d's half-spread in bp of yield: c_d = m x exp(s x Z), m = `P.competitors.median_half_spread` (0.183) for swaps and m x `treasury_half_spread_multiple` (0.5, assumption) for Treasuries, s = `P.competitors.log_sd` (0.5, assumption). The same m at every tenor and size (`size_and_tenor_scaling` = 0).
- Quote = latentMid at the RFQ's arrival step, plus or minus c_d converted to the instrument's unit (swaps: bp / 100 in percent; bonds: c_d x |pcpb_i| in points), then rounded to the instrument's tick **away from the mid** (a competitor never quotes better than its unrounded price). The conversion and rounding are the only place ticks enter.
- Client decision, headline rule **best price**: at the decision time (all answers in, or the response window) the client trades with the best live quote if its distance from the latentMid at arrival is at most the reserve; ties go to the earliest quote. Reserve = `clientToleranceMultiple` x m with the present lognormal (median 2.5, log-sd 0.4), assumption; done-away probability 0.05, assumption. The cover is the second-best live quote.
- The logistic hit-rate rule (`HitRateModel`) remains available as a sensitivity only and must not be the headline of any result.
- Zero-profit identity, checked at start-up and in test A3: m x E[min] = expected_adverse_move + all_in_cost, with E[min] the mean of the lowest answering quote per unit median under the dealer-count distribution of section 5 (0.761 at s = 0.5). At central values 0.183 x 0.761 = 0.139 = 0.059 + 0.080. The simulator does not charge competitors a cost; `all_in_cost` is the realised half-spread a break-even competitor needs, and it is the benchmark the desk's own realised spread net of its own hedge cost is compared with.

## 8. Order books: liquidity, other takers, impact, resilience

Applies to each on-the-run Treasury and each future. Nothing in this section is calibrated; every number is an assumption with a band in `P.clob`. A swap order book is out of scope for Round 3.

**Displaced mid.** D_i = latentMid_i + J_i.

**Liquidity providers' quotes.** At each step the provider side of the book is defined, not cancelled and reposted: best bid = tick x (ceil(D_i / tick) - 1), best ask = tick x (floor(D_i / tick) + 1) (one tick wide unless D_i is exactly on a tick). Levels 2 to 5 are one tick apart. Target size at level l: q_i x (1, 1.5, 2, 2, 2), q for the 10-year note = `P.clob.touch_depth_usd_mm` (30 mm), for other instruments q x V_i / V_10y. Displayed provider size at a price = target minus the unreplenished part of what takers consumed there; the consumed amount at each price decays by exp(-ln 2 x 1 s / `impact_half_life`) per step. When the best prices move, levels are re-keyed by price: a price that enters the five levels starts at its target.

**Our resting orders** keep price-time priority against the provider size displayed when they were entered. Quantity ahead falls by other takers' volume at that price and by provider cancellations in proportion when the level's target leaves the book.

**Other takers** (stream 200 + i). Trades per step ~ Poisson(lambda_i[k]), lambda_i[k] = V_i x tod[k] / mean size, tod from `P.rfq.time_of_day_share` (assumption that book volume follows the swap print profile), V_i = `P.clob.daily_volume_usd_bn` or `futures_daily_volume_contracts`. Size lognormal, median `taker_trade_size_usd_mm`, log-sd 1.0, rounded up to the minimum quantity. Sign: buy with probability 0.5 + theta x sign(latentMid_i[k + 5] - latentMid_i[k]) (price terms), theta = `taker_sign_skill` (0.1). They trade against the book in price-time order, our resting orders included. This is what fills our passive orders, and theta makes those fills adversely selected.

**Impact of any marketable order of quantity Q** (ours or another taker's), after it has been matched against the book at the displayed prices:

    I = Y x sigma_i x sqrt(Q / V_i)        (price units of i)

Y = `P.clob.impact_coefficient_Y` (0.7); sigma_i = |pcpb_i| x daily yield sd at the node (from P: sqrt of the sum of (L[T,j] x daily_sd[j])^2, 5.3 bp at 10 years); V_i in the same unit as Q.

- Transient part: J_i += side x (1 - pi) x I, and J_i decays by exp(-ln 2 x 1 s / half_life) per step. pi = `impact_permanent_share` (0.5), half-life = `impact_half_life` (30 s).
- Permanent part, **our orders only**: X1 += side_in_yield x pi x I / (|pcpb_i| x L[T_i,1]). It moves every instrument through the level loading. Other takers' permanent information is already in the exogenous path and is not added again.
- Workup and the exclusive window stay as they are in `ClobBook`.

Sources and their limits. The square-root form and a coefficient of order one: B. Toth, Y. Lemperiere, C. Deremble, J. de Lataillade, J. Kockelkoren, J.-P. Bouchaud, "Anomalous price impact and the latent liquidity of financial markets", Physical Review X 1, 021006 (2011), measured on futures metaorders. Exponential resilience of the book: A. Obizhaeva, J. Wang, "Optimal trading strategy and supply/demand dynamics", Journal of Financial Markets 16 (2013) 1-32, a model, not a Treasury estimate. Neither gives Y, pi or the half-life for on-the-run Treasuries; the values are assumptions and the bands (Y 0.3 to 1.5, pi 0.25 to 0.75, half-life 5 to 120 s) are the sensitivity to run. Scale check at central values: a 50 mm market order in the 10-year note against 40 bn a day gives I = 0.7 x 0.43 x sqrt(0.00125) = 0.0107 points, about 0.13 bp, half of which decays in 30 s.

## 9. Replacement of `presets.json` fields

`rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`, block `d2c.default`:

| Old field (value) | New parameter | Source |
|---|---|---|
| `halfSpread.swap.median` (0.0025 % = 0.25 bp) | `competitors.median_half_spread` 0.183 bp | bounded by DTCC dispersion, zero-profit condition |
| `halfSpread.bond.median` (1/128 point at every maturity) | `median_half_spread` x `treasury_half_spread_multiple` in bp of yield, converted with pcpb | assumption |
| `halfSpread.*.sigma` (0.5; only the bond value is read, `D2cConfig.java:100`) | `competitors.log_sd` 0.5, one value | assumption (kept) |
| `minDealers`, `maxDealers` (3, 5) | `competitors.dealers_min_max` | assumption (kept) |
| `dealerResponseProbability` (0.9) | `competitors.response_probability` | assumption (kept) |
| `dealerDelayMillis`, `responseWindowMillis` | unchanged | assumption (kept) |
| `clientToleranceMultiple`, `doneAwayProbability` | unchanged; tolerance now a multiple of the new median | assumption (kept) |
| `informedFraction` (0.2) | `informed_flow.informed_fraction` 0.5 with tier probabilities | assumption; product with the move bounded by data |
| `informedJumpMultiple` (4.0 half-spreads = 1 bp on the traded instrument) | `informed_flow.move_per_informed_trade` 0.118 bp, on the level factor | bounded: zero-profit value, DTCC markouts |
| `informedLagMillis` (30,000, a jump) | `informed_flow.drift_time_constant` 10 s, exponential, from arrival | assumption; not identified |
| `markoutHorizonMillis` (300,000) | unchanged (a reporting horizon); also report 30 s and 60 s | - |
| `midVolPerSqrtHour.swap` (1.5 bp), `.bond` (0.06 points) | `curve_factors.*`, `swap_spread_factor.*` | fitted: par curve PCA, DTCC intraday volatility |
| `arrivals.basePerHour` (30) and `arrivals.events` (five Gaussian bumps) | `rfq.swap_rfqs_per_day`, `rfq.time_of_day_share`, `rfq.treasury_rfqs` | shape fitted from DTCC; level bounded |
| `sizes.swap` (10 to 100 mm ladder) | `rfq.size_lognormal_by_tenor` | fitted from DTCC |
| `sizes.bond` | unchanged | assumption |
| uniform choice of instrument (code, line 187 onward) | `rfq.tenor_mix` | fitted from DTCC |

Block `clob.default`: `tickSize`, `minQty`, `workupMillis`, `exclusiveMillis`, `extendOnTrade`, `selfMatch`, `depth` unchanged; new `P.clob.*`. The `ClobLiquidity` constants in rates-app (three levels at 1 to 3 ticks, sizes 25/50/100 mm, 1 s refresh) are deleted. Block `sef.default`: unchanged, not wired.

`data/presets.yaml` presets that the single specification supersedes: `usd_synthetic_market`, `usd_rfq_flow`, `usd_rfq_competition` (competitor part), `usd_sofr_ois_size_by_tenor`, `usd_time_of_day_spread`, `usd_hedge_cost` (replaced by the section 8 book). `usd_rfq_hit_rate` stays for the sensitivity rule only.

## 10. One specification for the venue simulator and the back-test day

There is one class that owns sections 2 to 8, here called `MarketModel` (new, in `rates-common` or a new `rates-sim` module that both `rates-venues` and `rates-backtest` depend on), constructed from (P, session seed, day type). It exposes the latent path, the RFQ list with all attributes, competitor quotes, client decisions, the provider book and other takers' trades.

Identical between the two users, by construction because both call the same object: P; the stream table and draw order; the 1 s grid; the factor recursion; instrument mapping; RFQ arrival times and attributes; competitor quotes and the decision rule; book construction, taker flow and impact.

Allowed to differ: transport (FIX sessions and a venue clock against in-process calls), the desk's own latency, and nothing else. `SyntheticDay` becomes a thin adapter that asks `MarketModel` for the day and writes it in the back-test's record format; its own generators and constants are removed. Acceptance test A7 compares the two.

## 11. Acceptance tests for the implementation

Each test is a JUnit test or a small harness with a fixed seed list; tolerances are stated so that a correct implementation passes with probability above 99 %. "Sessions" are full days with no desk attached unless stated. Seeds for these tests: base 28,000,000.

| Id | Test | Pass condition |
|---|---|---|
| A1 | Replay | Two runs of one seed give bit-identical latent state at all 36,000 steps, identical RFQ lists, quotes and taker trades |
| A2 | Polling invariance | For one seed, read every instrument's `latentMid` (a) every step, (b) every 10 ms, (c) at 500 random times, (d) once at the end; the state at each common time is bit-identical. A desk run with a 10 ms step and with a 100 ms step sees the same RFQs, competitor quotes and client reserves |
| A3 | Zero-profit competitor | 400 sessions, no desk. For every traded RFQ record the winning half-spread w (bp of yield) and the signed move of the traded instrument's latentMid over 5 minutes, a. Mean of (w - a) over trades must equal `all_in_cost` within plus or minus 0.01 bp (about 90,000 trades; standard error near 0.004), and mean of a must equal `expected_adverse_move` within 0.01 bp. The same with informed_fraction 0 gives mean a within 0.01 of 0 |
| A4 | Factor moments | 400 sessions, day type "none". Session-end changes of the 7 node yields: each standard deviation within 8 % of sqrt(600) x sqrt(sum_j (L[T,j] x sd_per_sqrt_minute[j])^2 + idio^2) with the total level value 0.3178; each pairwise correlation within 0.03 of the model value; first three principal components' cosines with L above 0.98. On day type "release" the variance is (600 + 5 x 181.4) / 600 times larger, within 15 % |
| A5 | Hedge ratio | In the same sessions: a 10-year swap position hedged with the 10-year note at ratio 1 has session-end yield-difference variance equal to 600 x s_S^2 (0.169 bp^2) within 15 %, a reduction of at least 97 % of the unhedged variance (model value 98.3 %); hedged with TY at the ratio L[10,1]/L[7,1] the residual variance equals the closed-form value from L, the factor sds, the idiosyncratic terms and sd_B within 15 %; a hedge at half the correct ratio leaves at least 25 % of the variance |
| A6 | No tick dependence | Doubling every instrument's tick changes no latent value, and changes competitor quotes only by rounding (each quote within one new tick of its unrounded price, never inside it) |
| A7 | One specification | For 20 seeds the back-test adapter and the venue simulator produce the same latent path, RFQ list, competitor quotes and decisions for a desk that never quotes |
| A8 | Information is about the market | With a desk that never quotes: mean signed 5-minute move after informed RFQs that did not trade equals the mean after those that traded, within 0.02 bp; the move at other tenors equals A x L[T,1]/L[T_r,1] within 0.02 bp |
| A9 | Impact | A single 50 mm market order in the 10-year note at 10:00 on a quiet seed: displaced mid moves by I within 1 %; after 30 s the transient part is half; after 10 minutes the latent mids of all instruments differ from the no-order run by exactly the permanent part through L; twenty 2.5 mm orders one second apart cost less in total than the single order (sub-additive, square-root) |
| A10 | Book resilience and taker volume | Depth consumed at the touch is half restored after one half-life with no further trades; other takers' session volume per instrument is within 5 % of V_i over 100 sessions; fraction of taker trades in the direction of the next 5 s move is 0.5 + theta x (share of steps with a non-zero move) within 0.01 |
| A11 | Arrivals and sizes | Over 400 sessions: RFQs per session within 2 % of N_swap + N_tsy; tenor mix and 30-minute shares within 0.01 of P; median swap notional per tenor within 5 % of exp(log_mean_mm) |
| A12 | Start-up checks | Configuration is rejected if the zero-profit identity fails by more than 0.001 bp, if V_inf is above 60 % of the level variance, if loadings are not unit length within 0.001, or if any P entry is missing |

## 12. Experiment protocol after implementation

**Spent data.** The 20 recorded sessions used by AUDIT-11 to AUDIT-25, the five April 2026 "holdout" sessions read by AUDIT-26, and every session under seed bases 5,000,000 and 8,000,000 are spent. They were generated by the old simulators, have been read many times, and must not be reused for any claim, including as a "replication". Results on them are not comparable with results on this specification.

**Seeds.** Session n of a data set has seed base + n, n = 0, 1, .... Bases: 28,000,000 acceptance tests; 29,000,000 pilot; 30,000,000 exploration set E; 31,000,000 confirmation set C; 32,000,000 and above reserved, each new confirmation set takes the next million. The label of a session is its seed. Day types are assigned by n modulo 21: residues 0 to 3 "release", 4 "FOMC", 5 to 10 "auction", the rest "none" (4/21 release days, as in the calibration sample).

**Session.** One full day, 07:00 to 17:00, 36,000 steps. Not 15 minutes.

**How many sessions.** The review measured a session standard deviation near 157k USD (15-minute sessions on the old simulator) and needs to resolve effects near 30k. For a paired comparison with correlation rho between arms, sd of the difference is sigma x sqrt(2 (1 - rho)), and the sessions needed for a two-sided test at level alpha with power 1 - beta are n = (z_{alpha/2} + z_beta)^2 x 2 (1 - rho) x (sigma / delta)^2. With sigma / delta = 157 / 30 = 5.23:

| rho (common random numbers) | alpha 0.05, power 0.8 | alpha 0.005, power 0.9 |
|---:|---:|---:|
| 0 (unpaired design, per arm) | 430 | 916 |
| 0.5 | 215 | 458 |
| 0.9 | 43 | 92 |

The stricter column is the one to plan for, because several experiments will be read on one set (false-discovery control below). The new simulator's sigma for a full day is unknown, and so is rho. Therefore: (1) a **pilot** of 200 sessions on base 29,000,000 with the default desk and one perturbed arm measures sigma_d, the sd of the paired difference, and is then discarded for inference; (2) the confirmation set has N = max(2,000, ceil(16.7 x sigma_d^2 / delta^2)) sessions, delta being the smallest effect the pre-registration says it cares about, stated in USD per session before the pilot is read. 2,000 is twice the worst case of the table and is the floor.

**Design.** Paired, common random numbers: every arm of an experiment runs the same seeds, so the exogenous path, RFQs, competitor quotes and client reserves are identical across arms; only what the desk does differs (and, through X1 and J, what its own orders cause). The primary test, registered before the run, is on the per-session difference d_n = PnL_treatment(n) - PnL_baseline(n): mean, a two-sided paired t-test, and a 95 percent interval from a moving-block bootstrap with blocks of 21 sessions (one cycle of day types), 2,000 resamples. The unpaired Welch test is not used. An effect is reported in USD per session and in bp of client DV01.

**Exploration and confirmation.** Set E (2,000 sessions) may be read freely to design a change. A claim is made only from set C, on a pre-registration committed before the run, and each (pre-registration, data set) pair is read once.

**Counting looks.** A look is counted per data set, not per pre-registration id. The ledger row records the SHA-256 of the sorted list of session journal hashes it read; rows with the same hash share one look counter, and the counter is printed on every report that uses the set. A confirmation set whose counter exceeds the number of pre-registered primary tests planned for it is spent.

**False-discovery control.** All primary tests ever read on one confirmation set form one family. Benjamini-Hochberg at q = 0.05 is applied to the family each time a test is added, and a result is "supported" only while its adjusted p stays below 0.05 as the family grows; the ledger report prints the current adjusted values. Within one pre-registration with several primary endpoints, Holm at 0.05 as now.

**Sensitivities that every headline must carry.** Because much of P is bounded or assumed, a headline result is reported at the central P and at: winner half-spread band (m at 0.123 and 0.272 with the adverse move re-solved for zero profit), all-in cost 0.05 and 0.15, informed fraction 0.2 and 1.0 at fixed average move, drift time constant 2 s and 60 s, level volatility at the 2015-2018 and 2022-2025 values (factor sds scaled by 0.76 and 1.29), spread factor 0.3 and 1.3, impact Y 0.3 and 1.5. A result that changes sign inside these bands is reported as not robust. A sensitivity run uses the same seeds and counts as part of the same look.

**Wording.** Every number from this simulator is a statement about the simulator under P. The calibration ties P to public prints where it can; it does not make simulated P&L an estimate of desk P&L.

## 13. Files the implementation touches

New: `MarketModel` and its parts (factor path, RFQ generator, competitor and client model, book model, parameter loader for `sim_v2_params.json`), an inverse-normal routine, a futures instrument and book in rates-venues.

rates-venues: `d2c/D2cRfqSimulator.java` (remove `midNow` random walk, arrival thinning, size and dealer draws, informed jump; delegate to `MarketModel`), `d2c/D2cConfig.java` (fields replaced by P), `resources/io/ratesalgo/venues/presets.json` (d2c block reduced to transport timings), `sim/Instruments.java` (pcpb per instrument, futures, tenor to node mapping), `sim/SimRng.java` (replaced by stream-based draws), `sim/VenueSimulator.java` (constructs `MarketModel`; seed handling), `clob/ClobSimulator.java` and `clob/ClobBook.java` (provider book, other takers, impact hooks; the unused seed parameter becomes used), `sim/Px.java` (conversion from double at the boundary only).

rates-app: `app/sim/ClobLiquidity.java` (deleted or reduced to an adapter), `app/sim/SimulatedVenues.java` (seed + 1 convention removed), `app/TradingProcess.java` (liquidity interval removed; step size no longer affects the market), `app/Venues.java` (the T0 comment at line 31 is wrong and should be corrected when the session start moves to 07:00).

rates-backtest: `synth/SyntheticDay.java` (adapter over `MarketModel`), `synth/SyntheticDayCli.java` (seed base), `model/CompetitorModel.java`, `model/RfqOutcomeModel.java` (best price as default; same draws as the venue), `model/HitRateModel.java` (sensitivity only), `model/HedgeCostModel.java` and `model/ClobFillModel.java` (replaced by the section 8 book), `model/Presets.java`, `InstrumentSet.java`, `util/SplitMix64.java` (moved to a shared module), `prereg/Experiment.java` and `prereg/ExperimentCli.java` (seed = base + n, paired primary test, day types), `prereg/PreRegistration.java` (paired test and data-set fields), `prereg/Ledger.java` (look counter keyed by journal-hash digest, Benjamini-Hochberg across a set), `stats/Stats.java` (paired t, block bootstrap of differences, Benjamini-Hochberg), `report/DailyReport.java` (latent-mid marking, 30 s and 60 s markouts).

research/audit: `DeskAudit.java`, `run_desk_audit.sh` (full-day sessions, new bases). Data: `data/presets.yaml` (superseded presets marked), `data/calibration/sim_v2_params.json` (read-only input).

## 14. Deviations and clarifications (implementation, 2026-11-23)

Written while implementing sections 2 to 12 in `rates-sim` and binding the back-test and the venue simulators to it. Each item says what the text above leaves open or gets wrong, what the code does, and where a test pins it. Items marked **owner** need a decision before a pre-registration is signed.

**D1. Constants that P does not carry.** Sections 5 to 8 fix numbers in their text that `sim_v2_params.json` has only in a unit string or not at all (tier informed probabilities, the Treasury size ladder, dealer delay, reserve, done-away probability, depth multiples, taker log-sd and look-ahead, event window times, the three start-up limits, and the instrument universe). They live in one resource, `rates-sim/src/main/resources/io/ratesalgo/sim/sim_v2_structure.json`, loaded as strictly as P. No simulation constant is declared in Java. P itself is packaged from `data/calibration/` at build time, not copied.

**D2. Strict loading.** A missing, unknown or duplicate key refuses the file; so does a unit text that does not start with the expected unit, a status other than fitted, bounded or assumption, loadings not of unit length within 0.001, shares that do not sum to one, and `move_per_informed_trade` x `informed_fraction` different from `expected_adverse_move`. Entries the model does not read (`session_variance_share`, `winner_half_spread_rms` and the other evidence entries) must still be present. `time_of_day_multiplier` other than 1 and `size_and_tenor_scaling` other than 0 are refused, because only those cases are specified. Tier weights are read as `value`, `interval[0]`, `interval[1]` of `tier_weights_and_informed_probability`; `dealers_min_max` as `value` and `interval[1]`.

**D3. Universe.** Seven SOFR swaps (one per node of `tenor_mix`), four on-the-run Treasuries (2, 5, 10, 30 years) and TY on the 7-year node. The back-test and the reference data had swaps at four tenors only; the back-test now builds its instruments from the shared universe (`InstrumentSet.simV2`), and the venue session gives a model swap with no reference-data template a synthetic instrument. The desk's own universe in rates-app still has four swap tenors (see "left to wire"). TU is not built: P has a volume for the 10-year future only.

**D4. V_inf uses the universe's own mix.** Section 6 evaluates V_inf with the swap mix for all RFQs (11.38 bp^2). With Treasuries uniform over 2, 5, 10 and 30 years, as the same section says, it is 11.71 bp^2 and s_1 = 0.2854. The code computes it from the universe, so the total level variance stays at the calibrated 0.3178^2 x 600 exactly (test A4).

**D5. Draws that are always made.** So that the position in an RFQ's stream never depends on values: the size step always makes 1 + 20 draws (the first in-cap one is used; a Treasury uses the first as its ladder uniform), and step 8 always draws for the most competitors there can be (4). Step 9 draws the reserve (normal), then done-away (uniform). A session arrival bin with a Poisson mean above 500 is refused.

**D6. Own permanent impact takes effect at the order's time**, not at the next step boundary, so that the displaced mid moves by I at once as A9 requires. The exogenous state still changes only at step boundaries.

**D7. Impact of the desk's own orders is a law of the total, not of each slice (deviation from section 8; DR-R3-04). owner.** Section 8 applies I = Y sigma sqrt(Q / V) to every marketable order. Read literally, twenty 2.5 mm slices leave sqrt(20) = 4.5 times the permanent impact of one 50 mm order, which contradicts A9's last clause ("cost less in total ... sub-additive") and would make "never slice" as mechanical a result as "always slice" was before. The source the section cites measures the law on metaorders. The code keeps two volumes per book for the desk: W_day (signed marketable volume today) and W_recent (the same, decaying to a quarter per half-life). X1 is pi x f(W_day) / (pcpb x L) and the desk's part of J is (1 - pi) x f(W_recent), with f(w) = sign(w) Y sigma sqrt(|w| / V). A single order moves the book by exactly I, pi of it for good, and the transient part halves in one half-life, as specified. Slices add up to the impact of their total; buying and selling the same quantity leaves no permanent move. Other takers keep the per-trade law of section 8. In A9 "cost" is measured as impact. In execution price the comparison goes the other way in this book and that is not an artefact of D7: a 50 mm block is matched against five displayed levels that have not reacted (30 mm at the touch, the rest one tick up), while each slice pays the touch of a book its predecessors moved. Whether displayed depth should thin as a sweep proceeds is open.

**D8. Queue rule details.** A resting order of the desk that the providers' opposite best reaches is filled at its own price. The quantity ahead of it is capped at the provider size displayed at its price and is zero once the price is outside the five levels. A market order larger than the five levels is filled for what is displayed; the back-test adapter fills the rest one tick beyond the last level and charges its impact, to keep one fill per order.

**D9. Consumed depth is forgotten when a price leaves the five levels.** This is section 8 as written ("a price that enters the five levels starts at its target"). A consequence: when the touch moves one tick away and back within seconds, the depth taken there is whole again. **owner** if depth memory by price is wanted.

**D10. Two Treasury venues show shares of one book.** Section 8 has one book per instrument and the task asks for two order-book venues. The venues show 0.6 and 0.4 of the model's provider size at each level (`treasury_venue_shares`, an assumption) and receive the same shares of each other taker's trade, in whole lots. Depth taken on one venue is consumed in the model and so gone from both: consolidated depth is the model's, never more. The venue's own matching engine (`ClobBook`) holds the orders; when the desk's resting order takes part of a taker's trade the model still counts the whole trade against provider depth (conservative by the size of that fill).

**D11. Workup is off under the `sim_v2` order-book preset.** `ClobBook` is unchanged, but with 0.13 taker trades a second and a 5 s workup the book would be in workup almost always and immediate-or-cancel takers would be cancelled in every exclusive window. The taker flow of section 8 is specified against a book that matches continuously. **owner** if workup should return with a taker model built for it.

**D12. D2C decision time and engines.** The venue decides when every competitor that will answer has answered and every logged-on engine has quoted, else at the window; the model's `decide` is given the best engine quote as "the desk". Quotes are ordered by price and then by arrival in both, so the winner is the same. Client ids on the wire are 2001, 2002, 2003 for tiers A, B, C; the back-test journal uses 1, 2, 3.

**D13. Zero-profit identity holds for swaps, not for Treasuries. owner.** With `treasury_half_spread_multiple` 0.5 a Treasury competitor's expected winning half-spread is half the swap one, 0.070 bp against an adverse move of 0.059 bp, so it does not cover the 0.08 bp all-in cost the identity assumes. A Treasury tick is also as wide as the half-spread (0.096 bp at 10 years against a 0.091 bp median), so several quotes round to one price, the earliest wins, and the measured unrounded winner is 0.076 bp. A3 is asserted on swap trades with the unrounded winning half-spread. Measured there (1,000 sessions): winner 0.1364 bp, 0.003 under median x E[min] because the reserve removes wide lone quotes; winner minus adverse move 0.0755 bp against 0.08; adverse move 0.0602 bp against 0.059.

**D14. The upper winner band of section 12 does not start.** Median 0.2719 with the adverse move re-solved needs V_inf = 54 bp^2 of the level factor's 60.6, above the 60 percent limit of section 3, at informed fraction 0.5. It starts at fraction 1.0 (27 bp^2). The largest median that starts at fraction 0.5 is about 0.24. **owner** (listed in the SIM3-BASE-001 draft).

**D15. Sample sizes and tolerances of section 11.** The tolerances are the design's. The session counts are raised where 400 sessions do not give a correct implementation the stated 99 percent pass rate: second moments (A4, A5) use 1,000 sessions (8 percent on a standard deviation is 3.6 standard errors there, 2.3 at 400); pairwise correlations use 0.03 or 3.5 standard errors, whichever is larger (0.03 needs over 5,000 sessions for the 2-year against 30-year pair); A11 uses 6,000 RFQ lists, and compares the median of the size as drawn (rounding to whole millions alone moves the 20-year median by 5.8 percent); the release-day statement of A4 is tested as the variance across the window against the closed form of the recursion (the design's ratio 2.51 scales the whole variance, the recursion scales only the diffusive part and gives 2.22 at 10 years). Every test states its standard error.

**D16. Transport and desk-side settings that stay in `data/presets.yaml`.** The back-test adapter still reads the publication interval of swap reference prices, the SOFR fixing level, the desk's latencies, the venue fee per lot and the fixing calendar from the old presets. They are not market parameters. The superseded market presets (section 9) are still in the file because the version 1 path reads them for the ledger's recorded experiments; they are not marked in the file itself, which the Python writer owns.

**D17. Version 1 stays the default.** `SyntheticDay.generate`, `BacktestConfig.fromPresets`, `D2cConfig.defaults()` and `ClobConfig.defaults()` are unchanged, so every recorded figure and every ledger reproduction test is untouched. Simulator v2 is reached through `SyntheticDay.generateSimV2`, `BacktestConfig.forSimV2`, `Venues.simV2` and the `sim_v2` presets. Section 13's deletions (`ClobLiquidity`, the `d2c.default` market fields, `SimRng`) wait for the wiring in rates-app.

**D18. The back-test journal shows the book with no desk in it.** A journal is written before the run, so its book updates are the model's book as it would be with no desk. The desk's own fills, impact and marks come from the live model during the run, and marks are the unrounded latent mid, refreshed at every step and whenever the desk's impact moves the level factor. A limit order is booked as one execution when its whole quantity has traded, at its limit price; a partial fill is booked when the order is cancelled.

**Acceptance tests.** A1 to A6 and A8 to A12 are in `rates-sim` (`MarketModelAcceptanceTest`, `RfqFlowTest`, `BookModelTest`, `SimParamsTest`). A7 is in two halves that both compare with the shared model: `SimV2BacktestTest` (journal and settlement) and `SimV2VenuesTest` (20 seeds, a desk that never quotes). The desk-level clause of A2 (the same RFQs, quotes and reserves at a 10 ms and a 100 ms step) is tested on the venues without the desk; with the desk it waits for the wiring.
