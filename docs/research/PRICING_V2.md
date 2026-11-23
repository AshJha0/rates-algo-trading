# Pricing v2: design and evidence (Round 4)

Date: 2026-11-23. Scope: review findings A1, A2, A3, A4, A7 and A10 of `docs/reviews/REVIEW_2026-11-23.md`. Code: `rates-analytics` (`SofrCurve`, `SofrLeaveOneOut`, `FairValueSmoother`, `TreasurySpreadCurve`, `HullWhite`, `SwapCarryRoll`), `rates-api` (`SpreadSensitivityProvider`, `CarrySource`, `CarryRoll`), `rates-app` `pricing/` (`PricingV2`, `PricingBindings`, `BusinessDate`, `V2InputIds`, `SpreadRiskBook`), `rates-bench` (`PricingV2RepriceBenchmark`), `research/ratesresearch/goldens/pricing_v2.py`.

Status of every number: printed or asserted by the test named beside it, run on 2026-11-23 on JDK 25 under Windows 11. Nothing here comes from a simulated trading day: pricing v2 is selectable and off. Version 1 (`AnalyticsPricing`) is still what the desk loop constructs, so the recorded figures of the simulated desk are untouched. Section 11 says what has to happen before the default changes.

Each section gives the plain idea, the formal statement and a worked example, in that order.

## 1. What changes

| Finding | Version 1 | Version 2 | Section |
|---|---|---|---|
| A1 swap fair value is its own input; bond fair value is the book mid | curve pillars are the quoted swaps | a quoted swap that is an input is valued by a curve built without it; one that is not is priced off the curve; bonds are priced off the SOFR curve plus a fitted spread | 5, 6 |
| A2 the library is unused | five par swaps, no fixing, no futures | overnight rate, FOMC-dated steps from one-month futures, three-month futures with convexity, swaps at eight tenors, turns, repo and carry | 2, 4, 8 |
| A3 bond risk at zero spread, booked as SOFR risk | optional since the fix round, off | at the market z-spread, always; a second ladder for the spread | 7 |
| A4 five pillars on eight risk tenors | 1-year bucket empty, 3 and 7-year spill only | every risk tenor is a pillar; the map is a Jacobian | 7 |
| A7 valuation date is a constant | `Venues.TRADE_DATE` | the clock, with a 17:00 New York cut | 9 |
| A10 goldens partly self-generated | Hull-White, bootstrap retyped | a second golden file in which QuantLib prices every instrument and SciPy solves the curves; what is still a retyping is listed | 10 |

## 2. The SOFR curve

**Plain idea.** Build the curve from what trades at each maturity. At the front the overnight rate moves only at FOMC meetings, so the curve is a staircase with a step on each meeting's effective date. One-month futures say how high each step is. Three-month futures carry the curve to a year. Swaps take it to thirty years.

**Formal statement.** Three lists, declared separately (`SofrCurveSpec`):

- the *build set*, the instruments the bootstrap reprices exactly, one per pillar;
- the *quoted set*, the instruments the desk makes prices in (the universe's swap templates), which are priced off the curve and are not assumed to be in the build set;
- the *risk set*, par swaps at 1, 2, 3, 5, 7, 10, 20 and 30 years, on which every ladder is reported.

The build set on a valuation date is fixed by rule (`SofrCurve.Layout`):

1. The overnight estimate, a simple Act/360 rate from today to the next business day. Its pillar is the effective date of the next meeting: the level today holds until policy changes.
2. For each of the next `meetings` FOMC meetings (default 2), one SR1 contract: the contract of the month the step falls in if the effective date is on or before the 15th, otherwise the next month's (a step on the 28th leaves three days of its own month to show it). Its pillar is the next meeting's effective date, or its own month end for the last one. An instrument whose pillar is not its own last date is wrapped in `PillarOverride`; the builder then solves all pillars together.
3. `sr3Contracts` consecutive SR3 contracts (default 3), the first one ending at least 14 days after the last step, none ending within 30 days of the first swap pillar.
4. Par swaps at `swapTenorMonths` (default: the eight risk tenors).

Meeting dates are in `FomcCalendar`. They were typed from memory of the Board's published 2025 and 2026 schedules and its tentative 2027 schedule and are marked UNVERIFIED in the class; a wrong date moves one step by the error in days. Turns are optional: `SofrCurveSpec.withTurn(start, end, spread)` adds a simple Act/360 spread over a window before the bootstrap.

**Worked example** (`SofrCurveTest.theBuildSetOnTheReferenceDate`, and again from a hand-written list in the golden generator, `PricingV2GoldenTest.theBuildSetIsTheOneDeclaredInTheGenerator`). On Monday 2 November 2026:

| # | Instrument | Pillar | Why |
|---|---|---|---|
| 0 | SOFR overnight | 2026-12-10 | effective date of the 8-9 December meeting |
| 1 | SR1 December 2026 | 2027-01-28 | the December step is on the 10th, so December shows it; it runs to the January step |
| 2 | SR1 February 2027 | 2027-03-01 | the January step is on the 28th, so February shows it |
| 3-5 | SR3 December 2026, March 2027, June 2027 | 2027-03-17, 06-16, 09-15 | reference quarter ends |
| 6-13 | swaps 1, 2, 3, 5, 7, 10, 20, 30 years | last payment date | |

With the overnight rate at 3.90 percent and the December contract pricing an easing, the daily forward is 3.90 percent on every day to 9 December and one lower level on every day from 10 December to 27 January, equal to 1e-12 (`theFrontEndIsAStepFunctionOfTheMeetingDates`). A 20 bp year-end turn over 31 December to 3 January raises the forward by 20.00 bp inside the window and lowers the December-January step by 20/22 bp, because the December contract is still repriced (`aYearEndTurnRaisesTheForwardOverItsWindowOnly`).

Every input reprices within 1e-10 and discount factors are positive for random markets (levels 0.5 to 7 percent, slopes to 2.5 percent either way, a hump, 3 bp of noise per quote) on random business days from January 2026 to June 2027 (`SofrCurvePropertiesTest`, jqwik, 60 tries).

## 3. Interpolation: chosen on measurement

**Plain idea.** Between pillars the curve has to be drawn somehow. Three properties matter to a desk. Forwards should not jump where a pillar happens to sit. A swap between two pillars should be priced where a smooth market would put it. And risk should be stable: a ladder should not change when a quote moves a basis point.

**Formal statement.** The step section (pillars 0 to 2 above) is always linear in the log discount factor, which is what makes the forward a staircase. The question is the rest. Three schemes were built on the same quotes (`SofrCurveTest.interpolationEvidence`, declared market of `SofrMarket`):

| Measure | Linear log DF | Step then monotone convex | Step then tension spline (0.5/yr) |
|---|---|---|---|
| Largest forward jump across a swap pillar | 40.3 bp | 0.12 bp | 0.14 bp |
| Worst error on swaps that are not inputs (4, 6, 8, 12, 15, 25 years) | 1.34 bp | 0.78 bp | 0.12 bp |
| Ladder by AD against a 1 bp central bump, share of parallel DV01 | 2.0e-8 | 4.3e-2 | 2.6e-8 |
| Leave-one-out rate, first-order against exact after a 10 bp move | 0.003 bp | 0.295 bp | 0.005 bp |

The brief named monotone convex (Hagan and West 2006) as the candidate. It fixes the forward jumps, but it fails the third and fourth rows, and the reason is structural: the method chooses one of four formulas per segment by the signs and ratios of two forward differences, and on a smooth curve those differences are a few basis points, so a 1 bp move of a quote switches formulas. The value stays continuous; its derivative does not. The AD ladder is the correct derivative (at a 0.01 bp bump it agrees to 7e-9, measured during development), but a hedge sized from it is wrong by 4 percent of DV01 for a 1 bp move, in a bucket that depends on the day. The tension spline (Schweikert 1966, natural ends) is linear in its node values for fixed pillars, so every price off it is a smooth function of the quotes. It is the default: `Interpolation.STEP_THEN_TENSION_SPLINE` with tension 0.5 per year. Monotone convex stays selectable (`STEP_THEN_MONOTONE_CONVEX`) and is golden-tested against QuantLib.

What the spline costs: it is not local (a 30-year quote moves the 5-year forward a little) and it can overshoot. On this market its forwards beyond one year span 3.32 to 4.24 percent against 3.44 to 4.19 for the log-linear steps, asserted within 15 bp (same test). No bound is proved for other markets; the property test covers repricing and positivity, not overshoot.

**Worked example.** Forwards on the declared market, percent, from the same test:

| Date | Linear log DF | Monotone convex | Tension spline |
|---|---|---|---|
| 2027-11-16 | 3.4389 | 3.3167 | 3.3012 |
| 2029-11-15 | 3.7688 | 3.6439 | 3.6430 |
| 2033-11-14 | 4.0996 | 4.0151 | 4.0253 |
| 2037-11-13 | 4.1950 | 4.1548 | 4.2031 |
| 2047-11-11 | 3.7918 | 3.9289 | 3.8920 |

The log-linear column is flat between pillars and steps at them (4.0996 on both sides of 2035, then 4.1950); the other two move continuously.

## 4. Futures convexity

**Plain idea.** A futures rate is above the forward rate it settles on, because the future is margined daily. How far above depends on the rate's volatility and on how long the rate stays uncertain. A SOFR future settles on the rate realised through its reference period, so it stays uncertain to the end of the period, not to the start.

**Formal statement.** The library's `HullWhite.futuresConvexity` is Hull's formula for a continuously compounded rate fixed at the start of the period; DR-P1-05 already called its use for SOFR futures an approximation. Pricing v2 uses the in-arrears values of the same one-factor model. With V(t) the variance of the integral of the short rate over [0, t],

    V(t) = (sigma^2 / a^2) [ t - 2 B(t) + (1 - exp(-2 a t)) / (2 a) ],   B(t) = (1 - exp(-a t)) / a

the futures rate minus the forward rate is, for the compounded contract (SR3) and the average contract (SR1),

    SR3:  [ V(t2) - V(t1) + B(tau)^2 Var r(t1) + V(tau) ] / (2 tau)
    SR1:  [ V(t2) - V(t1) ] / (2 tau)

with tau = t2 - t1 and Var r(t1) = sigma^2 (1 - exp(-2 a t1)) / (2 a) (`HullWhite.compoundedFuturesConvexity`, `averageFuturesConvexity`; derivation in the Javadoc). The SR3 value is first order in tau: it leaves out a factor 1 + tau F, one percent of the adjustment. In the Ho-Lee limit the three formulas are sigma^2 times (t1^2/2 + t1 tau/2) for Hull, (t1^2/2 + t1 tau + tau^2/3) for SR3 and (t1^2 + t1 tau + tau^2/3)/2 for SR1, checked by hand arithmetic in `HullWhiteInArrearsTest`.

The parameters a = 0.03 and sigma = 0.0085 are **assumptions**, not calibrated: the repository holds no option prices or futures prices to calibrate them to.

**Worked example** (`SofrCurveTest.convexityAssumptionSensitivity`, `PricingV2GoldenTest.inArrearsConvexityMatchesNumericalVariancesAndQuantLib`):

| Contract | In arrears | Hull, forward-looking |
|---|---|---|
| SR1 December 2026 | 0.0056 bp | 0.0047 bp |
| SR1 February 2027 | 0.0298 bp | 0.0291 bp |
| SR3 December 2026 | 0.0416 bp | 0.0159 bp |
| SR3 March 2027 | 0.1290 bp | 0.0814 bp |
| SR3 June 2027 | 0.2590 bp | 0.1898 bp |

Sensitivity: sigma 20 percent higher raises every adjustment by 44 percent and moves a 9-month par rate, which is priced off the futures, by 0.061 bp. The whole assumption is therefore worth about a tenth of a basis point at nine months with this build set. It would matter if the strip were carried to two or three years, where the adjustment grows with the square of time.

## 5. Swap fair value

**Plain idea.** A swap that is a curve input reprices to its own quote; the curve has no opinion about it. Take it out, rebuild from the other inputs, and price it: that is what the rest of the market says it is worth. The rest of the market is not the whole story, though: a real curve has shape between pillars that no interpolation knows, so the gap between a swap's quote and that model value is mostly permanent. Carry the gap as a residual that follows the quote slowly. A quote that jumps for one update then moves the fair value by a small stated share; a gap that stays is taken in over minutes.

**Formal statement.** For build swap k let C_k be the curve bootstrapped from the build set without k. The leave-one-out rate is L_k = par rate of swap k on C_k; dL_k/dq_k = 0 by construction. For a quoted swap that is not an input (the 12-year template) the model value is its par rate on the full curve. The fair value is

    fair_k = model_k + r_k,     r_k <- r_k + g (q_k - model_k - r_k),     g = 1 - 2^(-(t - s) / h)

on each new quote q_k at time t after the previous one at s, with half-life h = 300 s (`FairValueSmoother`). The first quote of the day sets r_k to the whole gap, and so does the first reprice after any input is fed for the first time, because that changes what the model is built from. Times are the inputs' own timestamps.

Rebuilding eight reduced curves on every round would cost eight bootstraps. `SofrLeaveOneOut` does the exact rebuilds at a reference set of quotes and keeps dL_k/dq_j; between references it extrapolates to first order. The binding takes a new reference when any input has moved 5 bp. After a 10 bp move with a twist the first-order value is within 0.005 bp of a full rebuild (`leaveOneOutLinearisationError`); this is where the choice of Section 3 pays, since under monotone convex the same error is 0.30 bp.

**Worked example** (`PricingV2Test.aSkewedQuoteBarelyMovesItsOwnFairValue`). After ten rounds of an unchanged market the 5-year quote is sent 2 bp high for one round, half a second after the last. Its model value does not move (asserted to 1e-15). Its fair value moves by g = 1 - 2^(-0.5/300) = 0.115 percent of the skew, 0.0023 bp; the test asserts at most 0.12 percent. The 10-year, of whose reduced curve the 5-year is an input, moves by less than 1 bp. Version 1 on the same quote moves its fair value by 2.000 bp. Held for 300 s the skew is half absorbed, 1.00 bp (asserted within 0.02 bp).

What this does not do. A quote that is wrong and stays wrong becomes the fair value with a 5 minute half-life; that is the price of following a market whose curve shape the model cannot know. The leave-one-out rate of the longest swap is an extrapolation and of the shortest nearly so: on the declared market the gaps are -1.1, +1.3, -0.9, -0.2, -0.3, -0.9, +6.4 and -8.7 bp at 1 to 30 years (`aSwapsOwnQuoteDoesNotReachItsLeaveOneOutRate`), so at 20 and 30 years the residual carries most of the level and the model contributes the day's moves of the neighbours.

## 6. The Treasury curve

**Plain idea.** The desk sees a handful of Treasury books. A yield curve fitted to four prices is an interpolation or it is under-determined: Nelson-Siegel-Svensson has six parameters, and a smoothing spline needs many more points than it has knots. The SOFR curve already has the shape of rates at every maturity; what Treasuries add is the swap spread, which is smooth in maturity. So fit the spread.

**Formal statement.** For bond i with years to maturity T_i, market z-spread s_i to the SOFR curve (continuous, Act/365F), weight w_i and on-the-run flag o_i (`TreasurySpreadCurve`):

    s_i = b0 + b1 f1(T_i) + b2 f2(T_i) + c o_i + e_i
    f1(T) = (1 - exp(-T/tau)) / (T/tau),   f2(T) = f1(T) - exp(-T/tau),   tau = 5 years

the Nelson-Siegel basis with a fixed decay, so the model is linear and the fit is a weighted least squares in closed form. One term is fitted to fewer than four usable bonds, two to four to seven, three to eight or more. The premium c enters when both on-the-run and off-the-run bonds are present and a degree of freedom is left; the reported on-the-run premium is -c. The weight is the inverse of the squared half-width of the bond's book in spread, floored at a quarter of a basis point.

- Bond fair value: the SOFR curve at the z-spread model_i + r_i, with model_i the fitted spread with bond i left out (`leaveOneOut`, from the hat matrix: s_i - e_i / (1 - h_i), exact for fixed weights) and r_i a residual under the same half-life rule as Section 5, observed when the bond's book updates. So a bond's fair value moves with the other Treasuries and not with its own book. It is a Treasury curve: when swaps move and no Treasury book does, the fitted spreads move and Treasury fair values do not.
- Rich or cheap: s_i - model_i in basis points, positive is cheap (`PricingV2.richCheapBp`).
- One bad book. `TreasurySpreadCurve` can drop a bond in the cross-section (the bond with the largest standardised residual, if the fit made without it misses it by more than a limit, with four bonds or more) and then bounds the rest with Huber weights. The binding keeps the Huber weights and turns the dropping off, because a bond that drops in and out of the fit moves the model value of the others in steps. It catches a bad book in time instead: the move common to the bonds since the last reprice is taken out (the curve and the books are not sampled at the same instant), and a bond whose own z-spread then moves more than 3 bp is held: its last accepted spread stays in the fit, and its fair value does not follow its book. On the twentieth consecutive reprice the new level is taken as the market and absorbed at the half-life.

**Worked example.** Seven bonds on a curve of the model's form with a 1.5 bp on-the-run premium: curve and premium are recovered to 1e-10 (`TreasurySpreadCurveTest.recoversACurveOfItsOwnFormAndTheOnTheRunPremium`). Four bonds with half a basis point of noise and one of them 8 bp off, in each of the four positions, with dropping on: the bad bond is dropped, the other three stay within 1 bp of the truth (`oneBadBookIsDroppedAndTheOthersAreFittedAsIfItWereNotThere`). In the binding (`PricingV2Test.bondFairValueFollowsTheOtherTreasuriesAndNotItsOwnBook`): swaps up 5 bp with no book moving leaves the 10-year note's fair value where it was, within a tenth of a basis point of yield, and counts no bad book; the 2, 5 and 30-year books then cheapen by about a basis point of yield and the 10-year, whose own book has not moved, falls 1.13 bp of yield; its own book is then moved a full point, and neither its fair value nor the 30-year's moves (asserted to 1e-6 of a point), where version 1 returns the book mid.

**Weaker than asked, stated plainly.** The universe the process trades has four on-the-run Treasuries. With four bonds the fit has two terms and two degrees of freedom: the on-the-run premium is not identified (it is reported as zero and `premiumIdentified()` is false) and the leave-one-out value of the 30-year is an extrapolation from the other three. The premium and the third term come alive when the venue lists off-the-run issues; the code path is tested, the process does not exercise it. Two bad books at once are not caught: with two of four bonds jumping, the common move is taken as half the jump. The simulators' starting Treasury prices (100 less a step per tenor) are not a curve: their spreads to the starting swap rates run from a few basis points at 2 years to about a point at 30, the two-term fit misses them by tens of basis points, and the residuals carry the levels.

## 7. Risk

**Plain idea.** Two questions per position. How much does it make or lose when swap rates move at each tenor, with Treasury spreads held? And how much when Treasury spreads move at each tenor, with swap rates held? A swap answers only the first. A Treasury answers both. A swap hedged with a Treasury answers zero to the first and the whole bond to the second, and that second number is the risk the version 1 desk could not see.

**Formal statement.** *Swap-rate ladder.* Let q be the N build quotes in rate units, rho(q) the 8 risk par rates, K = d rho/dq from the bootstrap Jacobian, and W the N by 8 matrix that spreads a unit move of risk tenor k onto the build quotes by hat functions of maturity. The move of the build quotes that raises risk rate k alone by one unit is column k of G = W (K W)^-1, since K G = I. For a value with quote gradient x the ladder is d = G^T x, computed as (K W)^T d = W^T x (`SofrCurve.riskLadder`). When every risk tenor is a build swap, K W is the identity (asserted to 1e-9) and each bucket holds its own pillar plus the hat share of the futures. The parallel DV01 is the value change when all eight risk rates rise 1 bp; `riskParallel` computes it by a forward solve where the ladder uses a transposed one.

*Bonds at market.* The gradient is taken at the z-spread that reprices the market dirty price (`BondPricer.dirtyFromCurveGradient` with spread), never at zero spread.

*Spread ladder.* dP/ds of each cash flow, -v_i (t_i - t_settle), placed on the risk tenors by a hat function of the flow's time (`BondPricer.spreadLadder`): the exposure to a spread curve that is piecewise linear with nodes at the tenors, at the point where it is flat at the bond's z-spread. The parts sum to dP/ds exactly. Exposed as `SpreadSensitivityProvider.spreadPv01`; a swap returns zeros.

**Worked examples.**

- Every bucket populated: a par payer swap at each risk tenor has its annuity in its own bucket and zero elsewhere, to 1e-9 of the annuity (`SofrCurveTest.everyRiskBucketIsPopulatedByItsOwnSwap`). The 1-year bucket, empty in version 1, holds the 1-year swap and the futures.
- Sum of buckets equals parallel within 1e-9 relative, and each bucket equals a 1 bp central bump of the generating move within 1e-4 of parallel (measured 2.6e-8), with the Jacobian property checked on the bumped curve: the bumped risk rate moves 1 bp and the other seven move less than 1e-4 bp (`bucketsSumToParallelAndAgreeWithBumping`; random markets in `SofrCurvePropertiesTest`, 40 tries).
- Risk tenors that are not build swaps: with futures to two years in place of the 1 and 2-year swaps, K W is no longer the identity and a 1-year par swap still has its whole DV01 in the 1-year bucket (`riskTenorsThatAreNotBuildSwapsAreMappedByTheJacobian`).
- Swap against Treasury (`PricingV2Test.laddersFillTheirOwnTenorsAndBondsCarrySpreadRisk`): 1 mm of a 10-year payer swap and the amount of the 10-year note that zeroes the swap-rate parallel. Net swap-rate DV01: zero to 1e-6. Spread DV01: -852 per million of swap, 80 percent of it in the 10-year bucket; this is the position's whole risk and version 1 reported none of it. Each bond's spread parallel equals its street-yield DV01 at the market price times 1 + y/2 (a continuous spread against a semi-annual yield) within 0.5 percent.
- The cost of the spline in risk, same test: a swap between pillars does not split cleanly onto its two neighbours. The 12-year, which sits in the ten-year gap between the 10 and 20-year pillars, shows, per million, -0.5, 4.2, -12.0, 59.8, -226.8, 919.7, 267.3 and -40.2 on the eight tenors against a parallel of 971.6: 95 percent on 10 years, 28 on 20, minus 23 on 7. The parallel is right and the generating moves are exact; the small entries of the other sign are what a non-local interpolation means, and a pillar at 12 or 15 years is the remedy if the desk trades there.

`SpreadRiskBook` keeps positions times spread ladders. It is the additive hook: the risk engine is unchanged and nothing limits or quotes on spread risk yet.

## 8. Carry and roll-down

**Plain idea.** What a position earns or costs to hold over a horizon if no curve moves: income against financing (carry), and the change in the curve's rate as the instrument shortens (roll-down).

**Formal statement.** Bonds: `BondCarry.carry` at the repo rate for the term from `RepoCurve`, less a specialness spread for on-the-run issues, divided by the bond's price change per basis point; roll-down is the fall in the curve's yield as the bond shortens, y(T) - y(T - h), with y the par yield of the SOFR curve plus the fitted spread (`TreasurySpreadCurve.parYield`). `BondCarry.rollDown` reprices the bond at the horizon and gives the same number to first order; it is not called per reprice because it needs a yield solve per bond. Swaps: with S(T) the spot par rate and F(h, T) the par rate of the swap starting h days later and ending on the same date, carry = F(h, T) - S(T) and roll-down = S(T) - S(T - h) (`SwapCarryRoll`). Both are reported in basis points of the instrument's own yield, positive when a long bond or a receiver swap earns them, through `CarrySource.carry(instrumentId, CarryRoll)`. They are recomputed on every sixteenth reprice and after a date change, since they move with the slope of the curve and not its level. The quoting engine does not read it; that is for the strategy round.

**Worked example** (`PricingV2Test.carryAndRollDownComeFromTheLibraryAndTheRepoCurve`): 30-day horizon, general collateral 3.64 percent at 30 days, 20 bp special. The 10-year note's carry equals `BondCarry` at 3.44 percent to 1e-9 and lies between 0.3 and 1.0 bp; removing the specialness lowers it by dirty price times 20 bp times 30/360, to 1e-6. The 10-year swap's two numbers equal `SwapCarryRoll` to 1e-12 and are both positive on a curve rising from 3.60 to 3.70.

Defaults: one-day horizon, repo at the overnight rate, no specialness. The repository has no repo term data (DR-P1-07); the repo curve is a declared input.

## 9. Business date

**Plain idea.** A trade at 16:59 in New York is today's business and one at 17:01 is tomorrow's. The date a desk prices and settles from is the New York calendar date moved forward at the cut, then to a business day.

**Formal statement.** `BusinessDate.tradeDate(t, 17, calendar)`: the New York date of t, plus one day if the local time is 17:00 or later, moved to the next business day. `nextCut(t)` is the first 17:00 New York strictly after t (22:00 UTC in winter, 21:00 in summer). `PricingV2` reads the clock once at construction and afterwards compares each input's timestamp with the next cut, a long comparison; a timer may call `advanceTo` so that the date changes without market data. At a change it flushes held inputs, appends the overnight rate of each ended business day to the fixings, and rebuilds the curve, the reduced curves, every swap schedule and every bond's settlement date and accrued interest on the new date, carrying quotes and residuals over. This allocates, once a day.

**Worked example** (`PricingV2Test.theBusinessDateRollsAtTheNewYorkCutAcrossSeveralDays`). Session start Monday 2 November 2026 09:30. At 16:59:59 the date is 2 November and the 10-year note settles 3 November. The first input at 17:00:00 makes it 3 November: settlement 4 November, accrued up by 4.125/2/184 per 100 (asserted to 1e-12), the swap's fair value unchanged to 0.02 bp, Monday's overnight rate now a fixing. A timer call at Friday 17:00 gives Monday 9 November, with a fixing for each of 3 to 6 November and none for the weekend. Tuesday 10 November 17:00 gives Thursday 12 November, because Wednesday is Veterans Day. `BusinessDateTest` covers the cut to the nanosecond, weekends, holidays and the 25-hour day at the end of daylight saving.

Limits: the universe (which Treasuries are on the run, the swap templates) is still built on `Venues.TRADE_DATE`; the on-the-run flag is recomputed per date, the instrument list is not. Fixings are the desk's own overnight input, not a published series.

## 10. Performance and determinism

**Choice.** Reprice on the loop thread, conflated, as a pure function of journaled inputs. The alternative, a worker thread that publishes a snapshot, needs the snapshot to enter the loop as a journaled message, which means a new message template and changes in files this round does not own; and it makes the staleness of a fair value depend on thread scheduling. The on-loop design needs no new message and replays exactly, at the price of running the reprice inside the loop, so its cost has to be small and is measured.

**Conflation rule.** Inputs are held. They are applied, with one solve, when every input that has ever been fed has a new value (a complete round) or when the oldest held input is 100 ms older than the newest, by the inputs' own timestamps. A round that repeats the previous quotes does not solve. The trigger reads no clock, so the state after any input is a function of the inputs and the book as journaled; two runs of 400 random rounds give the same bits (`PricingV2Test.theStateIsAFunctionOfTheInputs`).

**What is incremental.** The solve starts from the previous solution. The leave-one-out rates are first-order between references. Each bond's z-spread is found by Newton from its last accepted spread (three price evaluations with derivatives, where the bracketing search takes about fifty). The interpolation matrices are rebuilt, since a tension spline is global. Bond stage and ladders are recomputed every round, because the books move.

**What makes it affordable.** Three things were measured to matter and were changed in the library; none changes a version 1 result.

- The bootstrap. The global solve was Levenberg-Marquardt on the normal equations, which stalls at the rounding floor of a futures price: on this curve an update took milliseconds and one in a few hundred failed its residual check (`SofrCurveTest.repeatedUpdatesSolveEveryTimeAndCheaply` is the regression test). `CurveBuilder` now tries Newton on the square system first, starting from the Jacobian already factored at the previous solution, so a round of small moves costs three residual evaluations and no Jacobian; Levenberg-Marquardt remains the fallback. The residual check is in rate units (a futures price error divided by 100).
- Discount factors. Under the tension spline the log discount factor at a date is a fixed linear combination of the node values. `DiscountCurve` keeps the weight row of each date it is asked for, so a discount factor is a 14-term dot product and an exponential and a gradient is a copy. In a plain loop without the coverage agent a 30-year par rate went from 26 to 2.4 microseconds and its gradient from 94 to 6.4. Linear log discount factors are left on the direct path so that version 1 is bit for bit what it was.
- Compounding. An OIS period whose days all telescope is one ratio of discount factors; `OisSwapPricer` used to walk every day of the period to find that out (7,500 days for a 30-year swap, per evaluation). The flag is now set when the swap is built. Same arithmetic, same bits.

**Measured.** `PricingV2RepriceBenchmark`, JMH 1 fork, 3 warm-up and 5 measured iterations of 3 s, JDK 25 on the development machine while other builds were running on it (which is why the errors are wide; read the figures as an upper bound for a quiet machine and as a ratio, not as a reference):

| Case | Time per round | Allocation |
|---|---|---|
| Version 2, every quote moves (15 messages, one solve, one reprice) | 562 +/- 390 microseconds | 1.3 +/- 0.9 B/op |
| Version 2, quotes unchanged (no solve) | 148 +/- 151 microseconds | 0.3 +/- 0.3 B/op |
| Version 1, five quotes (five incremental rebuilds and five recomputes) | 873 +/- 304 microseconds | 2.0 +/- 0.7 B/op |

Under the coverage agent in the build the same three cases print 339, 95 and 951 microseconds (`PricingV2RepriceBenchmarkTest`), and the allocation test's own loop 403 microseconds a round with the leave-one-out reference retaken 67 times in 4,000 rounds. So a version 2 round costs between a third and two thirds of a version 1 round, in one piece where version 1 has five pieces of a fifth each. It is not small: a few hundred microseconds on the loop thread twice a second, and an RFQ that arrives during a reprice waits for it. If the loop's latency budget cannot take that, the snapshot design is the next step (DR-R4-07). The byte or two per operation that JMH's profiler reports is its own sampling; the thread-allocated-bytes test below reads zero.

**Allocation.** `PricingV2Test.repriceDoesNotAllocate` asserts zero thread-allocated bytes over 4,000 reprices after warm-up, including rounds in which the leave-one-out reference is retaken. A date change allocates.

## 11. Independent verification

`python -m ratesresearch.goldens` now writes two files. `data/golden/analytics_golden.json` regenerates byte for byte (225 instruments, unchanged). `data/golden/pricing_v2_golden.json` is new (QuantLib 1.44, valuation date 2026-11-02): 14 inputs, 3 curves, 24 off-curve par rates, 9 futures prices, 33 forwards, 13 monotone-convex forwards, 5 convexity rows, 4 bonds with 14 quote DV01s each. `research/tests/test_goldens_pricing_v2.py` regenerates and compares it.

| Check (`PricingV2GoldenTest`) | Other side | Asserted | Measured | Independent? |
|---|---|---|---|---|
| Build set: kinds, contracts, pillars | hand-written list in the generator | equal | equal | yes: a second statement of the rule's result |
| Pillar discount factors, three interpolations | SciPy hybrid Powell solve, every instrument priced by QuantLib on a daily-node curve | 1e-10 | 7.8e-14 | pricing and solver yes; interpolation see below |
| Par rates off the build set (9 months to 25 years) | QuantLib `OvernightIndexedSwap` | 1e-9 | 3.5e-15 | yes |
| Futures off the build set (SR1 January and April 2027, SR3 September 2027) | QuantLib `OvernightIndexFuture` | 1e-7 in price | 1.7e-12 | yes |
| Monotone-convex instantaneous forwards | QuantLib `ConvexMonotone` (quadraticity 0, monotonicity 1) | 1e-8 | 5.2e-12 | yes |
| Tension spline | solved in Python from its differential equation as a dense system, and that against the grid minimiser of its energy | 1e-6 of the log DF (grid) | 2.2e-7 | independent derivation, same definition |
| In-arrears convexity | numerical double integral of the short-rate covariance | 1e-9 relative | within (not printed) | yes |
| SR3 convexity, forward-looking part | QuantLib `HullWhite.convexityBias` (Kirikos-Novak): tau x adjustment - V(tau) = z | 1e-9 relative | within (not printed) | yes |
| Bond accrued, z-spread, curve price | QuantLib `BondFunctions` | 1e-10, 1e-9, 1e-7 | within | yes |
| Bond DV01 to each of 14 build quotes | QuantLib price after a 1 bp central bump and re-solve | 1e-6 of the ladder | 1.7e-8 | yes |
| Bond risk-tenor ladder | the same bumps through the hat weights | 1e-6 of the ladder | within | bumps yes; hat weights retyped |
| Bond spread DV01 | QuantLib 1 bp central bump of the z-spread | 5e-6 relative | 1.1e-6 (30-year, the bump's own third-order term) | yes |

Only a cross-language retyping, which catches a slip in one language and not a formula wrong in both: the closed-form convexity inside the futures quotes (the numerical check above is the independent one), the Hagan-West formula that builds the monotone-convex curve in Python (QuantLib checks its forwards afterwards), linear log discount factors, and the hat weights. Not golden-tested at all: the Treasury spread fit and the residual rule, which are tested against known answers in Java only.

## 12. What pricing v2 needs before it is the default

**From simulator v2.** CurveInput messages (template 3, curve `AppIds.SOFR_CURVE`), one round at a time with one timestamp:

| Input | kind | instrumentId | value (Decimal9 mantissa) |
|---|---|---|---|
| Overnight estimate | Fixing | `V2InputIds.OVERNIGHT` (4000001) | rate in percent |
| SR1 contracts of the build set (two) | Future | `V2InputIds.sr1(yyyymm)` (4200000 + yymm) | price |
| SR3 contracts of the build set (three) | Future | `V2InputIds.sr3(yyyymm)` (4300000 + yymm) | price |
| Par swaps at 1, 2, 3, 5, 7, 10, 20, 30 years | ParSwap | the template id where one exists, else `V2InputIds.parSwap(months)` (4100000 + months) | par rate in percent |
| Any other quoted swap (12 years) | ParSwap | template id | par rate in percent |

`PricingV2.inputId(i)` and `curve().label(i)` list the build set for the current date; contracts change with the date. Treasury prices come from the composite book as now; off-the-run issues on the book would identify the on-the-run premium. The simulator specification has swap nodes at 2, 3, 5, 7, 10, 20 and 30 years and no SOFR futures or overnight rate: until it sends them, the binding seeds each missing input from the ones it has (overnight and futures flat at the overnight setting, a missing swap by linear interpolation in tenor) and counts them (`unfedInputs()`). With today's feed ten of fourteen inputs are seeded, so the front end is flat and the 1, 3, 7 and 20-year pillars are interpolations: the machinery runs, the independent view at those points does not exist yet.

**Wiring in files this round did not edit.**

1. `DeskGroup` (constructor): `this.pricing = PricingBindings.create(version, universe, loop.clock());` and the field and accessor typed `PricingBinding`.
2. `DeskGroup.onCurveInput`: accept kinds Fixing and Future as well as ParSwap when version 2 is selected, and after a call that reprices push every instrument's fair value to the risk engine, not only the one that arrived (`reprices()` tells whether it did).
3. `DeskGroup`: `spreadRisk.onFill(instrumentId, signedQuantityMinor)` where the position keeper is told of a fill, and `spreadRisk.recompute()` after a reprice.
4. A timer or the curve heartbeat calling `pricing.advanceTo(clock.epochNanos())` and `pricing.flush()`.
5. `CurveFeed` (or its simulator v2 successor): the round of the table above.

**Before the switch.** A pre-registered run on simulator v2 with version 2 selected, since every recorded figure changes: SmokeTest's counts and replay hash, recipe 22, the ledger reproduction tests.

## 13. Known limits

- Meeting dates unverified (Section 2). No unscheduled meetings.
- Hull-White parameters assumed (Section 4).
- Four Treasuries: no premium, weak outlier detection (Section 6).
- A persistent wrong quote is absorbed with a 5 minute half-life (Section 5).
- The spline can overshoot; only measured on one market (Section 3). Its ladders for instruments between pillars are not local (Section 7).
- A reprice is several hundred microseconds on the loop thread (Section 10).
- Ten of fourteen inputs seeded with today's feed (Section 12).
- CME contract terms (SR1: arithmetic average over the calendar month; SR3: daily compounding between third Wednesdays) are as the library already had them from the Rulebook chapters it cites; they were not re-read this round, and cmegroup.com refuses automated fetches. UNVERIFIED this round.
- Bond carry uses the overnight rate as repo unless a repo curve is given.
- Not done: swaption or futures-option calibration; a Treasury curve for bills; asset-swap or matched-maturity spread as the spread measure (z-spread is used throughout).

## 14. Text for the documents this round did not edit

See the hand-over report of the round for CHANGELOG lines, DECISIONS records DR-R4-01 to DR-R4-09, and the API_SPEC and LEARN notes; they are reproduced here so that they live in the repository.

### CHANGELOG, Unreleased, Added

- Pricing v2, selectable and off by default (`PricingBindings.Version.V2`; version 1 and every recorded figure unchanged): SOFR curve with overnight rate, FOMC-dated steps from SR1, SR3 with in-arrears Hull-White convexity and swaps at the eight risk tenors (`SofrCurve`); leave-one-out fair value with a half-life residual (`SofrLeaveOneOut`, `FairValueSmoother`); fitted Treasury spread curve with rich/cheap and on-the-run premium (`TreasurySpreadCurve`); bond risk at market and a spread ladder (`SpreadSensitivityProvider`, `SpreadRiskBook`); carry and roll-down (`CarrySource`); business date from the clock with a New York cut (`BusinessDate`). Design and evidence: `docs/research/PRICING_V2.md`.
- `Interpolation.STEP_THEN_TENSION_SPLINE` and `STEP_THEN_MONOTONE_CONVEX`; `CurveBuilder.rebuildAll`; `PillarOverride`; `FomcCalendar` (dates unverified); `HullWhite.compoundedFuturesConvexity`, `averageFuturesConvexity`, `integratedVariance`; `BondPricer.spreadLadder`; `SwapCarryRoll`.
- `data/golden/pricing_v2_golden.json` and `ratesresearch.goldens.pricing_v2`: QuantLib-priced golden for the v2 curve, monotone-convex forwards, in-arrears convexity and bond spread risk. `analytics_golden.json` regenerates unchanged.
- `PricingV2RepriceBenchmark` (JMH) in rates-bench, which now depends on rates-app.

### CHANGELOG, Unreleased, Changed

- `CurveBuilder`: the global solve tries Newton on the square system before Levenberg-Marquardt, and the residual check is in rate units (a futures price error divided by 100). Sequential solves, and so version 1 pricing, are unchanged.
- `DiscountCurve`: weight rows per date under the tension-spline schemes (a curve under those schemes must not be read from two threads). `TensionSplineInterpolator` keeps its system across value updates.
- `OisSwapPricer`: a period whose days all telescope is priced as one ratio without walking its days; results are bit for bit the same.
- `config/spotbugs-exclude.xml`: `SofrCurve` and `SofrLeaveOneOut` added to the classes that hand out the bootstrap's one curve.

### DECISIONS

**DR-R4-01: Pricing v2 is selectable and version 1 stays the default.** Context: every recorded figure depends on version 1. Decision: `PricingBindings.create(version, ...)`; `AnalyticsPricing` gains only `implements PricingBinding`. The default changes with simulator v2 in a pre-registered step.

**DR-R4-02: Step-then-tension-spline, not monotone convex.** Context: `SofrCurveTest.interpolationEvidence`. Monotone convex has continuous forwards but its ladders differ from a 1 bp bump by 4.3 percent of parallel DV01 and a first-order leave-one-out value drifts 0.30 bp in a 10 bp move, because its regions switch within a basis point. Decision: tension spline at 0.5 per year after the meeting steps; monotone convex selectable. Cost: non-local, can overshoot.

**DR-R4-03: Fair value is leave-one-out plus a half-life residual.** Context: A1. Decision: Section 5; half-life 300 s; exact reference every 5 bp. A single skewed quote moves its own fair value by 1 - 2^(-dt/300 s) of the skew. A persistent skew is absorbed: accepted, stated.

**DR-R4-04: The Treasury curve is a fitted z-spread to SOFR on a Nelson-Siegel basis.** Context: four to ten bonds. Decision: Section 6. With four bonds the premium is not identified and one bad book at most is found.

**DR-R4-05: Two risk factors per tenor; the swap-rate ladder by Jacobian.** Context: A3, A4. Decision: Section 7. `Dv01Vector` and `KeyRateTenors` unchanged; `SpreadSensitivityProvider` added beside `SensitivityProvider`.

**DR-R4-06: In-arrears Hull-White convexity for SOFR futures; parameters remain assumptions.** Context: DR-P1-05 used Hull's forward-looking formula. Decision: Section 4. `HullWhite.futuresConvexity` and the first golden file are unchanged.

**DR-R4-07: Reprice on the loop, conflated, as a pure function of journaled inputs.** Context: replay determinism; no new message template. Decision: Section 10. Revisit if the measured cost of a round becomes a problem for the loop's latency budget.

**DR-R4-08: Business date from the clock with a 17:00 New York cut.** Context: A7. Decision: Section 9. The instrument universe is still built on `Venues.TRADE_DATE`.

**DR-R4-09: Newton before Levenberg-Marquardt in the global bootstrap; tolerance in rate.** Context: on the v2 curve the damped solver stalled at the rounding floor of futures prices (milliseconds per update, occasional failed residual checks). Decision: Section 10. The tolerance of 1e-12 now applies to the error in rate, so a futures price may be off by 1e-10.

### API_SPEC

Section 7.1, add: SOFR futures in pricing v2 use `HullWhite.compoundedFuturesConvexity` (SR3) and `averageFuturesConvexity` (SR1); `futuresConvexity` is unchanged and remains what `analytics_golden.json` tests. Section 7.2, add the table of Section 11 above. Section 13.1, add: `SpreadSensitivityProvider.spreadPv01` is a second ladder on the same eight tenors, PV(spread + 1 bp) - PV, negative for a long bond, zero for a swap. Section 18.3, add: `PricingBinding`, `PricingBindings`, the inputs of Section 12 above.

### LEARN

A short section, "Why a quote should not be its own fair value", from Section 5; one, "Swap spread as a risk factor", from Section 7 with the hedged-swap example; and a note in the curve section that the choice between monotone convex and a spline was made on risk stability, with the four-row table of Section 3.
