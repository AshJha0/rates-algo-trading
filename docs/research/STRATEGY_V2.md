# Strategy v2 (Round 5)

Date: 2026-11-23. Parameters: `data/calibration/sim_v2_params.json` of Round 3 (called P below; `P.a.b` is the `value` of entry `a.b`), the specification `docs/research/SIM_V2_DESIGN.md` and its evidence `docs/research/SIM_V2_CALIBRATION.md`. Findings: `docs/reviews/REVIEW_2026-11-23.md` Sections 2 and 3 (Q1 to Q8, Q11, Q13).

Status. Every capability below is code with tests, behind a switch that is off by default. `DeskSettings.defaults()` quotes and hedges exactly as before; `DeskSettings.strategyV2()` turns everything on together. Nothing here has been run against a simulator and no desk P&L was looked at while designing it: every number in this document is arithmetic from P or from the code, not a result. The pre-registration drafts R5-001 to R5-007 (`research/prereg/`) say what will be run, on which seeds, and what counts as a pass.

Evidence classes, as in the review: [C] read from the code, [P] taken from the calibration file, [A] arithmetic from the two, [U] an assumption.

## 1. Can a dealer make money on the calibrated market?

**Plain idea.** Round 3 calibrated the competitors so that they earn nothing: what a winning dealer earns in spread is exactly what the informed clients take back plus the dealer's own costs. A new dealer makes money only where its costs are lower than theirs, or where it can tell informed clients from the rest better than they do.

**Formal statement.** For one RFQ won at half-spread s (bp of yield from the mid at arrival), with expected adverse move a after the trade, hedge cost h and inventory cost i, all in bp of the trade's DV01:

    expected P&L per unit DV01 = s - a - h - i;   break-even half-spread s_BE = a + h + i

The competitors satisfy the zero-profit identity of SIM_V2_DESIGN.md Section 7: m x E[min] = a_bar + c_all, with m the median competitor half-spread, E[min] the mean of the best answering quote per unit median, a_bar the client-averaged adverse move and c_all the competitors' all-in cost.

**Worked example (10-year swap, hedged with the 10-year note).** [P] m = 0.1827 bp, E[min] = 0.761, so the expected winning half-spread is 0.139 bp. a_bar = 0.059 bp, c_all = 0.080 bp (an assumption, band 0.05 to 0.15). [P, U] Tier informed probabilities 0.25, 0.60, 0.975 with a move of 0.118 bp per informed trade give tier adverse moves of 0.030, 0.071 and 0.115 bp. [A] The note's price change per bp is 0.0815 points, so one 1/128 tick (0.0078125 points) is 0.096 bp of yield and crossing a book one tick wide costs half of it, 0.048 bp. With no inventory (i = 0):

| Client | a (bp) | h (bp) | s_BE (bp) | Edge at the expected winning half-spread 0.139 bp |
|---|---:|---:|---:|---:|
| Tier A | 0.030 | 0.048 | 0.078 | +0.061 |
| Average | 0.059 | 0.048 | 0.107 | +0.032 |
| Tier B | 0.071 | 0.048 | 0.119 | +0.020 |
| Tier C | 0.115 | 0.048 | 0.163 | -0.024 |

So at the 10-year a desk whose only hedge cost is half a tick of the note has 0.032 bp of room on the average client, because its hedge (0.048 bp) is cheaper than the competitors' assumed all-in cost (0.080 bp). It should quote tier C wider than the winning level and win less of that flow.

**The 2-year is different.** [A] The 2-year note moves 0.0190 points per bp, so half a tick is 0.206 bp. s_BE for the average client is 0.059 + 0.206 = 0.265 bp, almost twice the expected winning half-spread. Hedged with TU (half of a 1/8-of-1/32 tick on a 200k contract, about 0.10 bp when a futures book exists) it is 0.16 bp, still above 0.139. A 2-year swap is worth winning only from tier A, or when the risk can be held and offset by later client flow instead of hedged (Section 5). The Treasury RFQ is tick-bound on the 2-year: the competitor median there is 0.5 x 0.1827 = 0.091 bp (an assumption), less than a quarter of one tick.

**Scale of the room, per session.** [A, U] 116.4 swap RFQs a session (band 46.6 to 232.8) times the 10-year share 0.337 is 39 10-year RFQs; the mean notional exp(2.584 + 1.223^2 / 2) = 28mm is about 24k USD of DV01 each. Winning a quarter of them at the expected winning level with 0.032 bp of room is 39 x 0.25 x 24,000 x 0.032 = 7.5k USD a session. This is a statement about the size of the opportunity under P, not a forecast: the win rate, the level won at and the client mix all depend on what the desk quotes, and the bands of P move the 0.032 bp from below zero (m at 0.123 bp gives an expected winning half-spread of 0.094 bp) to about 0.10 bp (m at 0.272 bp).

**What this means for the design.** Market making is viable on the calibrated market only where (1) the hedge is cheap per DV01 (long tenors, the right instrument), (2) clients can be told apart (tiers and their own markouts), and (3) the desk never quotes below its break-even. A fixed clamp of 0.02 to 0.08 bp (the present `DeskSettings`, Q1) sits below s_BE for every client and tenor in the table.

## 2. Break-even, cover-relative pricing (Q1, Q3)

**Plain idea.** Price from the bottom up: work out the break-even distance, then quote the distance above it that maximises expected profit given where the best competing quote is likely to be. Learn where that quote is from the venue's answers: a win with a cover shown tells exactly where the next-best quote was; a win without one says it was further out; a loss says it was nearer.

**Formal statement** (`CoverModel`, `QuotingEngine.strategyPrice`). Let B be the best competing distance from fair, lognormal with parameters (mu, sigma) per cell of (tenor node, size bucket relative to the instrument's typical size, dealers asked: 3, 4, 5 or more, swap or bond). With pi0 = (1 - q)^(n - 1) the chance that none of the other n - 1 dealers answers (q = 0.9 [P, U]), R(d) the survival of the client's reserve (median 2.5 m, log sd 0.4 [U]) and f the tier's fill probability (0.95 [U]):

    P(win | d) = f R(d) (pi0 + (1 - pi0) P(B > d))
    d* = argmax over d >= C of P(win | d) (d - C)

C is the break-even distance of Section 1 including the inventory term of Section 3. The search runs over [C, max(C, 0) + exp(mu + 4 sigma)]: bounds set by the cover, never a fixed clamp. When C rises the quote widens; nothing passes on cost (the default path passes with NEGATIVE_EDGE once its cost reaches the cap).

Prior. Each competitor's half-spread is m exp(0.5 Z) [P]. Given that at least one of the n - 1 answers, ln B = ln m + 0.5 min(Z_answering); its mean and variance are computed once by quadrature. [A] For four dealers (three competitors answering with probability 0.9) the mean of the minimum normal is -0.754, so the predicted median cover on a swap is 0.1827 x exp(-0.377) = 0.125 bp.

Learning: a stochastic EM on censored observations, per cell, with weight 1 / min(n + 1, 500) on the first two moments of ln B. An exact cover moves them by ln B and (ln B)^2; a censored one by the moments of the normal truncated at ln d (upper tail for a win without a cover, lower tail for a loss without one). `QuotingEngine` calls it from every Done and TradedAway; this also gives the previously dead `observeCoverGap` path a caller on this route (Q3).

**Worked example.** Ten-year swap, four dealers, average client, no inventory: C = 0.059 + 0.048 = 0.107 bp. The cover prior has a median of 0.125 bp, so a quote at C wins about half the time and earns nothing; the optimiser moves out until the falling win probability balances the margin, a little above C. A tier C client with C = 0.163 bp is quoted above the median cover and wins rarely, which is the point.

**Expected to change.** More P&L per DV01 won, fewer wins from informed clients, no passes on cost. **Could fail if** the cover prior is far from the simulator's competitors and learning is slow (500-outcome memory per cell, 168 cells; thin cells stay near the prior), or if done-away and reserve losses are read as competitor losses (they push the learned cover nearer fair and make the desk quote tighter).

## 3. One inventory reservation price (Q4)

**Plain idea.** Inventory has a cost: the risk of holding it until it is hedged. A trade that adds to it should be priced wider by its marginal cost, one that reduces it tighter by the cost it saves. That cost can never exceed what hedging in the market would cost, because the desk would then hedge instead. One term, from the whole book including hedges already working, replaces the separate skew, size penalty and hedger axe of the default path (which counted the same inventory twice, once through the skew and once through the axe).

**Formal statement.** With p the risk engine's DV01 ladder plus the working hedge parents (`Hedger.addWorking`; USD per bp at each pillar), Delta the trade's own ladder (sign by side), Sigma the key-rate covariance (bp^2 per day), kappa the risk aversion per USD and H(Delta) the cost in USD of the hedge planner's mix for Delta alone:

    dPi = kappa (Delta' Sigma p + Delta' Sigma Delta / 2)               USD, the change in (kappa / 2) x' Sigma x
    inventory term = clamp(dPi, -H(Delta), +H(Delta)) / DV01(Delta)     bp
    reservation offset rho = clamp(kappa u' Sigma p / |U|, -H / DV01, +H / DV01)   bp; reservation price = fair - rho

This is the discrete-trade form of the Avellaneda-Stoikov reservation price r = s - q gamma sigma^2 (T - t) (M. Avellaneda, S. Stoikov, "High-frequency trading in a limit order book", Quantitative Finance 8 (2008) 217-224) for a vector of risk factors: the term (p + Delta / 2) is the (2q + 1) / 2 of the Guéant-Lehalle-Fernandez-Tapia quotes (O. Guéant, C.-A. Lehalle, J. Fernandez-Tapia, "Dealing with the inventory risk: a solution to the market making problem", Mathematics and Financial Economics 7 (2013) 477-507), here applied once per RFQ instead of to a continuous order flow, and capped at the hedge cost.

**Limiting cases** (each a test in `StrategyPathTest`):

- Zero inventory, p = 0: dPi = kappa Delta' Sigma Delta / 2 is the same for a buy and a sell, so the quote is symmetric about fair (`withNoInventoryTheQuoteIsSymmetric`).
- Large inventory: |kappa Delta' Sigma p| exceeds H, so the term is +H for a trade that adds risk and -H for one that removes it; the skew stops at the cost of hedging the offset however large the book grows (`withLargeInventoryTheSkewStopsAtTheCostOfHedging`).
- kappa to 0: dPi to 0, no skew and no size term (`withNoRiskAversionThereIsNoSkew`).
- Working hedges are inventory: a hedge already sent is not hedged again through the price (`workingHedgesCountAsInventoryAndTheAxeIsNotAddedTwice`).
- Bid at or below the reservation price, offer at or above it, because the adverse cost and the size term are non-negative and the clamp is monotone (property test).

**Choice of kappa.** [A] The hedger acts when the residual's daily sd reaches 20k USD. At the 13.3 bp daily sd of the level factor that is about 1.5k USD of level DV01, whose hedge in the 10-year note costs about 0.048 x 1,500 = 72 USD. Setting the penalty (kappa / 2) x 20,000^2 equal to that cost gives kappa = 3.6e-7; the preset uses 4e-7.

**Size curve.** [A] For a 10-year trade of DV01 D the uncapped size term is kappa D sigma_10^2 / 2 bp with sigma_10^2 = 28.6 bp^2: 0.06 bp at 10k of DV01, 0.14 bp at 24k. It meets the hedge cap at D* = 2 x 0.048 / (4e-7 x 28.6) = 8.4k USD of DV01, about 10mm of the 10-year; above that a trade is priced as if hedged at once, and the cap itself grows with size through the impact term of the hedge cost. The default path's size term saturates at about 1.1k of DV01 (Q4).

**Expected to change.** Inventory turned over by clients instead of the hedger when the book leans; no paying more than the hedge cost to do it. **Could fail if** the covariance is wrong (Section 4 replaces the assumed one), or if the hedge planner's cost understates real costs (impact), which makes the cap too low.

## 4. Fitted factor covariance and factor hedging (Q2, Q5)

**Plain idea.** Rates move together. Round 3 fitted three factors to eleven years of par curves; the level factor alone is 13.3 bp a day against 4.5 bp for slope and 1.5 bp for curvature. With that covariance a 10-year hedge removes most of the risk of a 30-year position, which the assumed exp(-0.06 |t_i - t_j|) correlation (10s30s 0.30) said it could not. Hedge level first with one instrument, then slope with a second when the variance removed is worth the second crossing.

**Formal statement** (`FactorModel`, `FactorHedgePlanner`). Sigma = L diag(sd_f^2) L' + W diag(e_n^2) W', with L the loadings at the nodes 2 to 30 years carried to the key-rate pillars by linear interpolation (2-year node below 2 years, as the simulator does) and W the same interpolation of the independent node terms. The idiosyncratic daily sd is the per-sqrt-minute value scaled like the level factor (13.27695 / 0.3178). For a residual r and per-lot ladders h_j with factor exposures e = L'r and g_j = L'h_j, the planner evaluates every single-instrument plan with x_j = -e_level / g_j,level and every pair that zeroes level and slope, and keeps the one with the lowest

    J(x) = (kappa / 2) ((r + Hx)' Sigma (r + Hx) + sum_j (x_j dv01_j sd_basis)^2) + sum_j cost_j(x_j)

within the lot limits. Level is always zeroed; slope only when it pays. Futures carry basis variance (sd 0.15 bp stationary [U], so sqrt(2) x 0.15 over a day). Only instruments that are `HedgeAvailability.available` are used: cash Treasuries now, futures once a futures book exists. The residual is reported by factor (`residualSd(f)`, `residualIdioSd()`).

Hedge cost per instrument (`TickHedgeCostModel`): half-spread in that instrument's ticks times the tick value of one lot, plus basis and impact per lot. [A] Half a 1/128 tick on 1mm face is 39.06 USD: 0.206 bp of the 2-year's DV01 and 0.048 bp of the 10-year's, not one flat 40 USD a lot whatever the tenor (Q5). `squareRootImpactPerLot2` gives the quadratic coefficient that matches the calibration's square-root law at a reference size.

**Worked example.** [A] 10s30s correlation under the fit: cov = 176.3 x 0.134 + 20.0 x 0.102 - 2.28 x 0.076 = 25.5 bp^2, var_10 = 28.6, var_30 = 26.3, rho = 0.93 (`FactorModelTest`). A level-only hedge of 30-year risk with the 10-year note at ratio L30/L10 = 0.863 leaves 3.5 of 26.3 bp^2, 13 %; under the old assumption the best hedge left 91 %. A 2s10s pair against 30-year risk zeroes level and slope but leaves curvature 0.95 times the position, about 8 % of its variance (`FactorHedgePlannerTest`).

**Expected to change.** Hedge in fewer, cheaper instruments with more risk removed per USD of cost; the reservation price sees cross-tenor offsets. **Could fail if** the simulator's swap-spread factor dominates the residual of swap positions hedged with Treasuries (0.7 bp a day, loading 1 on every swap; no Treasury removes it), or the basis assumption is far off.

## 5. Internalisation budget (design change 5)

**Plain idea.** Hedging at once pays the spread every time. If the book is small enough, hold it for a while: a passive hedge resting at our side of the touch may fill and earn the half-spread instead of paying it, and client flow may offset it. Cross only when the risk is too large or has been held too long.

**Formal statement** (`Hedger.Config.internalise`, `passiveFirst`). With s the residual's daily sd measured without passive orders:

- s below the trigger: nothing; any resting hedge is withdrawn (it would now add risk).
- trigger <= s < budget, held less than the time stop: hold; with `passiveFirst`, rest the planned hedge at the touch (bid for a buy, offer for a sell), one wave at a time. Resting orders do not count as hedges, in the residual or in the reservation price.
- s >= budget, or held for the time stop: withdraw resting orders and cross with the default schedule (first tranche, then the half-life).

The rules of DR-RV-Q01 still hold: no order off a mid older than 5 s, and a resting order needs a touch no older than that; nothing is sent while inputs are unhealthy, and the time stop's clock keeps running, so a desk that recovers past it crosses at once; a parent working 60 s is cancelled and stops counting. The time stop (45 s in the preset) is inside the 60 s timeout, so a resting hedge is withdrawn and crossed before the timeout would cancel it.

**Worked example.** [A, U] The preset's budget is 120k USD of daily sd, about two median 10-year swap RFQs (13mm, 11k of DV01, 60k of daily sd each). Over 45 s the intraday diffusive sd of such a book is 120,000 x sqrt(0.344 x 0.75 / 600) = 2.5k USD [P: session variance share 0.344]; the hedge cost saved when a resting order fills is about 22,000 x 0.048 = 1.1k USD. Whether resting orders fill often enough, and how adversely (the simulator's other takers trade with skill 0.1 toward the next 5 s move [U]), is what R5-004 measures.

**Could fail if** resting orders fill mostly when the market moves against them (adverse selection of passive fills), or rarely at all, so the desk carries risk for 45 s and then pays the spread anyway.

## 6. Client toxicity (Q6)

**Plain idea.** Measure each client's markouts at several horizons, by side, keep them across sessions, and charge each client its own adverse move once it has enough history; until then its tier's.

**Formal statement** (`MarkoutStore`, `TierPriors`). For client c, side and horizon h in {1 s, 5 s, 30 s, 60 s, 5 min}:

    adverse(c, side, h) = (n m + n0 prior(tier, h)) / (n + n0),   prior(tier, h) = a_tier (1 - exp(-h / tau))

with m the client's exponentially weighted mean markout (bp in the client's favour from the mid at the trade), n its fills, n0 = 20, tau = 10 s [U] and a_tier from Section 1. The adverse cost of Section 2 uses h = 30 s, three time constants. The store is written at the end of a session to a text file (client, side, horizon order; shortest round-trip numbers; temporary file then atomic move), so the same store gives the same bytes, and read at the next start. Three tiers are wired in the preset (`DeskSettings.strategyV2Tiers`).

**Worked example.** [A] A tier A client (prior 0.028 bp at 30 s) whose first fill is followed by a 2 bp move in its favour is charged (2 + 20 x 0.028) / 21 = 0.12 bp next time, against 0.067 bp for an unknown tier B client (`StrategyPathTest`).

**Could fail if** markouts are dominated by noise. [A] The 10-year yield's diffusive sd is sqrt((0.3945 x 0.3178)^2 + (0.1986 x 0.107)^2 + (0.1959 x 0.0362)^2 + 0.0136^2) = 0.128 bp per sqrt-minute, 0.09 bp over 30 s, against tier differences of about 0.04 bp; a client's mean separates from the next tier's by one sd only after about (0.09 / 0.04)^2 = 5 fills, and noise in the drift time (2 s to 60 s [U]) adds to it. n0 = 20 gives the client's own mean half the weight at 20 fills, deliberately slow.

## 7. Quote protection and ticks (Q7, Q8)

**Plain idea.** A firm quote is a free option for the client. Make the option smaller when the market is fast (shorter time to live), withdraw a quote once half its edge is gone, and quote only prices a venue can show, rounded our way.

**Formal statement.** Time to live T = clamp(T0 (sigma0 / sigma)^2, T_min, T0): the option's value grows with sigma sqrt(T), so this holds it constant (T0 = 5 s, T_min = 1 s; sigma from the fix round's volatility sampling of the 10-year swap, floored at the 6 bp baseline). The pull at a fraction 0.5 of the quoted distance is the fix round's `fairMoveWithdrawFraction`. Prices are rounded to the venue tick, down for a bid and up for an offer (`VenueTickTable.roundForDealer`); with cover pricing the grid point one tick nearer the client is taken when it is still above break-even and has the higher expected P&L, and the outcome reports the distance in ticks. Ticks: 1/128 of a point for Treasuries and futures, 0.0001 percent (0.01 bp) for swaps, as the simulator's venues use.

**Worked example.** [A] The 2-year note's tick is 0.41 bp; a break-even of 0.27 bp (Section 1) rounds to one tick, 0.41 bp, from fair. The 10-year's tick is 0.096 bp; 0.107 bp rounds to two ticks, 0.19 bp, unless one tick (0.096 bp) is above break-even, which it is not. On swaps the tick (0.01 bp) never binds.

Not done: the back-test still drops QuoteWithdraw (rates-app `DeskStrategy`), so the pull is untested in back-tested P&L until that is wired (Section 10).

## 8. Preset, switches and ablation

`DeskSettings.strategyV2()` = default settings (back-test limits, so only the strategy differs) with `StrategyConfig.allOn()`, the volatility reference at the 10-year, the pull at 0.5 of the quoted distance, three client tiers and the internalising hedger (budget 120k, time stop 45 s, passive first). Rejecting a mismatched Done stays off (DR-RV-Q03). Each capability has its own switch on `StrategyConfig` (or `Hedger.Config` for internalisation), so each can be run alone or left out.

| Draft | Capability | Comparison | Why this comparison |
|---|---|---|---|
| R5-001 | 1. Break-even, cover-relative pricing | default + cover pricing against default | Q1 is about the default desk's clamp; the claim is that pricing from break-even alone changes its result |
| R5-002 | 2. Reservation price | V2 against V2 without it | the term interacts with the cover pricing and the planner's hedge cost; leave-one-out measures it in their presence |
| R5-003 | 3. Factor covariance, factor hedging, tick hedge cost | V2 against V2 with all three off | the three are one change to how risk is measured and hedged |
| R5-004 | 4. Internalisation | V2 against V2 with the default hedger | |
| R5-005 | 5. Client markouts and tiers | V2 against V2 without the store (toxicity table, one prior) | |
| R5-006 | 6. Ticks, time to live, pull at half the distance | V2 against V2 with the three off | |
| R5-007 | All | V2 against default | the headline, read last |

All seven are paired, common-random-number comparisons on full-day sessions under the Round 3 protocol (SIM_V2_DESIGN.md Section 12): primary metric the per-session P&L difference in USD and in bp of the baseline's client DV01, two-sided paired t (`Stats.pairedT`) and a moving-block bootstrap interval of the paired difference (`Stats.pairedBlockBootstrapCi`, blocks of 21 sessions, 2,000 resamples), Benjamini-Hochberg across the confirmation set's family. Guard metrics stop a capability winning by not trading: quoted share, hit rate and client DV01 traded must stay within stated fractions of the baseline's. Each draft names the bands of P at which its sign must hold.

## 9. What could make it fail overall

- The calibration's competitor cost c_all (0.08 bp) is an assumption with a band of 0.05 to 0.15. At 0.05, with the winning half-spread held, the adverse move re-solves to 0.089 bp and the 10-year room on the average client falls to 0.002 bp.
- Adverse selection of our own fills may exceed the client average if the simulator's informed clients concentrate where we quote tightest (they do not choose by price in P, but tiers do differ).
- Hedging the swap-spread factor is impossible with Treasuries; a desk long swaps and short Treasuries carries 0.7 bp a day of spread risk per DV01 [P].
- Thin learning: 168 cover cells and five markout horizons per client and side learn slowly at 233 RFQs a session; most of the first sessions run on priors.
- The default path's `MARKOUTS` ring drops the oldest markout when full, which on a busy desk starves long horizons; the strategy path drops the newest instead (`markoutsDropped`). The default path is unchanged by design.

## 10. Wiring left to other areas

- rates-app `DeskGroup` (not owned here): call `Desk.saveMarkouts()` at session end; give the hedger the futures book's `BookSource` and a `HedgeAvailability` that turns TU, FV, TY, US on when that book is live; give the tick cost model each future's own tick and impact (a `VenueTicks` per contract, read from the exchange's contract specifications; the fix round re-read the TU tick and left FV, TY and US open).
- rates-app `DeskStrategy` (back-test): forward QuoteWithdraw so the pull is in back-tested P&L (Q7).
- Round 3 simulator (`MarketModel`): the venue's response should carry the cover on wins and the winning level on losses where the venue discloses them, and fix client ids per tier; `DeskSettings.V2_CLIENT_TIERS` then follows them.
- Round 4 pricing (carry): the reservation price and hedge planner take the ladder as given; a carry term would enter as a drift in dPi and is not modelled here.
- rates-risk (additive, not edited): a `LimitKind` for the per-client DV01 limit is already noted in DR-RV-Q04; nothing new is needed by this round.

## 11. Where it is tested

`FactorModelTest`, `FactorHedgePlannerTest`, `TickHedgeCostModelTest`, `HedgerInternalisationTest` (rates-hedge); `StrategyModelsTest`, `StrategyPathTest`, `StrategyPropertyTest` (jqwik: never below break-even, our side of the reservation price, on the tick in our favour, monotone in inventory and in size, no quote when killed or unhealthy), `StrategyAllocationTest` (the per-RFQ path with every capability on allocates nothing after warm-up) (rates-quoting); `DeskSettingsStrategyTest` (rates-app: the preset's switches, and the wired process builds and runs it, with no P&L read).
