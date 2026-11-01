# Learning rates e-trading through this repository

## How to read this document

This is a textbook chapter set about principal electronic trading in interest rate swaps and government bonds, written around the code in this repository. Each section has the same shape. First the plain idea, in a few sentences a newcomer can hold in their head. Then the formal statement, with the conventions and formulas a reviewer would check. Then a glossary of the terms the section introduces, collected alphabetically in [Section 17](#17-glossary). Then at least one worked example with numbers, and next to each number the test, cookbook recipe or published source that reproduces it, or the arithmetic that lets you check it by hand. Dates in parentheses after a test name are the day the test was last seen green with that result.

The sections were first written by the phase that built the code they describe (Phase 0 wrote Sections 1 and 11, Phase 1 Sections 2 to 5, and so on) and were harmonised in round 2 on 2026-10-31: one vocabulary, one sign convention, and the missing sections written. Where code a section describes does not exist yet, the section says so.

Nothing here is investment advice. All market numbers are illustrative, declared in the repository, or come from simulations calibrated to public data.

### Suggested paths

A student new to rates should read in order: [Section 1](#1-rates-products-in-five-minutes) for the products, [Section 2](#2-discount-curves-and-the-multi-curve-world) and [Section 3](#3-swaps-schedules-pv-par-rate-dv01) for curves and swaps, [Section 4](#4-government-bonds-price-yield-carry-repo) for bonds, then [Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup) and [Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse) for how trading happens and how a dealer prices it. Run [COOKBOOK.md recipes 4](COOKBOOK.md#4-how-do-i-bootstrap-a-usd-sofr-discount-curve-from-ois-quotes) to 8 alongside Sections 2 to 5; every number in those sections is printed by one of them. Then [Section 8](#8-hedging-and-the-dv01-ladder), [Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution) and [Section 13](#13-pitfalls-and-numerical-issues), and test yourself with the easy and medium questions of [Section 15](#15-knowledge-check).

A reviewer from a rates desk can go straight to the conventions table below, then the formal statements of Sections 3, 4 and 5 against [API_SPEC.md Sections 5](API_SPEC.md#section-5-market-conventions) to 8, the quoting and hedging objectives of Sections 7 and 8 against [API_SPEC.md Sections 12](API_SPEC.md#section-12-quoting-parameters-and-the-hit-rate-preset-p3) to 14, [Section 13](#13-pitfalls-and-numerical-issues) for what went wrong and how it is now guarded, and [Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ) for the honest list of what a real desk has that this project does not. The hard questions in Section 15 point at the tests a reviewer would want to run.

An engineer interested in the low-latency side can read [Section 11](#11-low-latency-java-single-writer-off-heap-replay), the clock and measurement pitfalls in Section 13, and [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

### Conventions used throughout

These hold in every section; where a source or a desk uses another convention, the text says so at that point.

| Topic | Convention in this document and the code |
|---|---|
| Side | Buy and Sell are from the client's point of view in RFQ and order messages ([API_SPEC.md Section 0](API_SPEC.md#section-0-units-and-conventions)). "Bid" is the price at which the quoting party buys, "ask" or "offer" the price at which it sells; in an RFQ the dealer's bid is the price the client can sell at. |
| Client side and dealer side | A client who buys in an RFQ makes the dealer short. Markouts, P&L and risk are always from our side, the dealer's, unless a sentence says otherwise. |
| DV01 and PV01 | Both mean the change in value for a one basis point rise in rates, computed as $PV(\text{rates} + 1\,\text{bp}) - PV(\text{base})$, in currency per basis point. Positive means the position gains when rates rise: a payer swap, a short bond or future. A long bond is negative. Key-rate DV01 bumps one curve input at a time; parallel DV01 is their sum ([DECISIONS.md DR-P1-04](DECISIONS.md#dr-p1-04-key-rate-dv01-by-algorithmic-differentiation-through-the-bootstrap), [API_SPEC.md Section 8.3](API_SPEC.md#83-dv01ladder-consumed-by-the-p3-risk-engine)). The bond analytics' quoted DV01 per 100 or per million face, as in recipe 6, is the market's unsigned price-sensitivity figure $D \, P \times 10^{-4}$; it equals minus the signed DV01 of a long position. Some desks use "PV01" for the annuity times one basis point; this document calls that the annuity and does not use PV01 for it. |
| Basis point | 0.0001 in rate, 0.01 in a percentage rate, or 0.01 of a point in a futures price quoted as 100 minus rate. |
| Price and rate on the wire | int64 mantissa with fixed exponent $-9$: 99.123456789 is 99123456789; a rate of 4.25 percent is stored as 4.25, mantissa 4250000000 ([API_SPEC.md Section 0](API_SPEC.md#section-0-units-and-conventions)). |
| Quantity on the wire | int64 in the currency's minor unit: 100 million USD is 10,000,000,000 cents. FIX carries currency units and the gateway scales by exactly $10^2$ ([DECISIONS.md DR-P2V-10](DECISIONS.md#dr-p2v-10-fix-quantities-in-currency-units-sbe-quantities-in-minor-units)). |
| Time | Two clocks: `epochNanos()` for time stamps (UTC, nanoseconds since 1970) and `nanoTime()` for intervals (monotonic, no epoch). They are never subtracted from each other ([API_SPEC.md Section 0](API_SPEC.md#section-0-units-and-conventions), Section 13 here). |
| Markout | The change in mid a stated time after a fill, signed so that positive is good for us; a negative mean markout means the counterparty tended to be right. |

## 1. Rates products in five minutes

### Plain idea

A rates desk trades promises about future interest. The two products this project cares about are interest rate swaps, where two parties exchange a fixed stream of payments for a floating one, and government bonds, where a state borrows for a fixed term and pays coupons. Both are priced by discounting known or expected cash flows. Almost everything that looks complicated in rates is bookkeeping about which days count, which calendar applies and when money actually moves.

### Swaps

The modern interest rate swap is an overnight index swap, an OIS. The floating leg pays the compounded overnight rate over each period; the fixed leg pays an agreed rate. Each currency has its own overnight index and its own habits.

USD. The index is SOFR, published by the New York Fed. The standard SOFR swap pays both legs annually. The fixed leg accrues on an Act/360 basis. The floating leg compounds SOFR daily in arrears over the accrual period, also Act/360, and both legs pay two US government securities business days after the period end. That two-day payment lag exists because the last SOFR fixing of a period is published the next morning, so the payment amount is not known on the period end date.

EUR. The index is the euro short-term rate, written here as EUR STR (often typeset with a euro sign). Standard swaps pay annually, Act/360 on both legs, with a one-business-day payment lag on the TARGET calendar.

GBP. The index is SONIA, administered by the Bank of England. Standard swaps pay annually on an Act/365 Fixed basis on both legs, with no payment lag: the payment date is the period end date. This works because SONIA for a given day is published at 09:00 the following London business day and the market accepts same-day settlement of the known amount.

The swap market also uses standard dates. IMM dates, the third Wednesday of March, June, September and December, anchor futures and many swap start dates. Dates roll forward under a business-day convention, usually modified following, which moves a non-business day to the next business day unless that crosses a month end, in which case it moves back.

### Government bonds

A fixed-coupon government bond pays a coupon at a stated frequency and the face value at maturity. Price is quoted per 100 of face, clean of accrued interest. The buyer pays the clean price plus accrued interest, which is the coupon earned since the last payment date computed on the bond's day-count basis.

US Treasuries. Notes and bonds pay semi-annual coupons on an Act/Act basis, where accrued interest is the coupon times days elapsed over days in the coupon period. Regular-way settlement is T+1. Prices are quoted in 32nds and finer fractions, which this project converts to decimal at the boundary.

German Bunds. Annual coupons, Act/Act ICMA (the ICMA rule is the same actual-over-actual logic applied per coupon period), settlement T+2.

UK Gilts. Semi-annual coupons, Act/Act ICMA, settlement T+1. Gilts have an ex-dividend period of seven business days before each coupon date. A buyer who settles inside that period does not receive the forthcoming coupon, so accrued interest is negative and the price convention reflects this. A pricer that ignores the ex-dividend period will be wrong by about one coupon for a week every six months.

### Formal statement

Let $d_i$ be the discount factor to payment date $i$ and $\alpha_i$ the accrual fraction of period $i$ on the leg's day count. For a swap with notional $N$ and fixed rate $K$:

$$
PV_{\text{fixed}} = N K \sum_i \alpha_i d_i, \qquad
PV_{\text{float}} = N \sum_i \alpha_i F_i d_i
$$

where $F_i$ is the forward compounded overnight rate for period $i$, which in a single-curve OIS world equals $(d_{i-1}/d_i - 1)/\alpha_i$ on the same curve. The par rate is the $K$ that makes the two legs equal. The sum $A = \sum_i \alpha_i d_i$ is the annuity.

For a bond with coupon $c$ per period and $n$ remaining coupons, the dirty price per unit face is $\sum_{i=1}^{n} c\, d_i + d_n$, and clean price is dirty minus accrued.

### Worked example: DV01 of a 10-year par swap

DV01 is the change in present value for a one basis point parallel rise in rates, signed from the holder's side (see the conventions table above): a swap that pays fixed gains when rates rise, so its DV01 is positive, and the receiver's is the same number with a minus sign. For a par swap the size is, to first order, notional times the annuity times 0.0001. When rates rise by one basis point, a new swap would pay one basis point more on every fixed date; the payer has locked in the old rate, so it gains the value of that basis point on each payment, while the floating leg stays close to par.

Take 100 million USD notional, 10 annual fixed payments, and a flat rate of 4 percent with annual compounding, ignoring the Act/360 accrual adjustment and calendars for the sake of a round example. The discount factors are $d_i = 1.04^{-i}$, and the annuity is

$$
A = \sum_{i=1}^{10} 1.04^{-i} = \frac{1 - 1.04^{-10}}{0.04} = \frac{1 - 0.675564}{0.04} \approx 8.1109
$$

($1.04^{10} = 1.480244$, which a calculator confirms), so for the payer

$$
DV01 \approx 100{,}000{,}000 \times 8.11 \times 0.0001 \approx 81{,}000 \text{ USD per basis point}.
$$

The number is approximate. A production figure would bump the bootstrapped curve by one basis point, reprice both legs with the real schedule and day counts, and difference the two values; the result for a 10-year USD SOFR swap at 4 percent sits in the same region, around 80 to 85 thousand per basis point per 100 million, and moves with the level of rates because the annuity does. [Section 3](#3-swaps-schedules-pv-par-rate-dv01) repeats the example with the Phase 1 swap pricer: 82,367 USD per basis point on the [COOKBOOK.md recipe 5](COOKBOOK.md#5-how-do-i-price-a-swap-and-get-its-par-rate-and-dv01) curve. The gap from 81,100 is mostly day count: Act/360 accrual fractions are about $365/360$ of a year, which alone lifts the annuity by 1.4 percent.

### Why this matters for the platform

Every one of the conventions above becomes a field in the securities master, a branch in the schedule generator and a test case against QuantLib ([API_SPEC.md Section 5](API_SPEC.md#section-5-market-conventions) lists them as pinned). The venue simulators must quote in the right units (price for bonds, rate in percent for swaps). The risk engine must know that a one basis point move on a 10-year swap is about eighty thousand dollars per hundred million, and that a 30-year is roughly double: at 4 percent the 30-year annuity is $(1 - 1.04^{-30})/0.04 = 17.29$, 2.13 times the 10-year's. Getting the five-minute version right is the condition for getting the microsecond version right.

### Glossary

- **OIS**: overnight index swap; the floating leg pays the overnight rate compounded over each period.
- **SOFR, EUR STR, SONIA**: the USD, EUR and GBP overnight benchmark rates, published by the New York Fed, the ECB and the Bank of England for the previous business day.
- **Day count**: the rule that turns two dates into an accrual fraction: Act/360, Act/365 Fixed, Act/Act ICMA.
- **Business-day convention**: how a date that falls on a holiday moves; modified following moves forward unless that crosses a month end.
- **IMM date**: the third Wednesday of March, June, September and December.
- **Settlement lag**: business days from trade to settlement: T+1 for Treasuries and gilts, T+2 for Bunds.
- **Clean and dirty price**: the quoted bond price without accrued interest, and the amount actually paid, with it.
- **DV01**: the change in value for a one basis point rise in rates, positive when the position gains.
- **Annuity**: $\sum_i \alpha_i d_i$, the value of receiving one unit of fixed rate on every fixed payment date.

## 2. Discount curves and the multi-curve world

### Plain idea

A discount curve answers one question: what is a dollar paid on a given future date worth today. Every price in this project is a sum of cash flows times those values. The curve is not observed directly; it is inferred from instruments that are, overnight fixings, OIS swaps and futures, by finding the curve under which each of them is worth exactly its quoted price. That inference is the bootstrap.

Before 2008 one LIBOR curve both projected floating rates and discounted cash flows. Collateralised derivatives are now discounted at the overnight rate paid on collateral, so the OIS curve discounts. Where a term index survives (EURIBOR is the main example), it gets its own projection curve, built after the OIS curve with OIS discounting held fixed. That is the multi-curve setup.

### Formal statement

A curve is a set of pillar dates $T_1 < \dots < T_N$ and log discount factors $y_i = \ln P(0, T_i)$, with $y_0 = 0$ at the valuation date. Time is $t = (T - T_0)/365$. Between pillars the curve is interpolated in $y(t)$ by one of three schemes (`Interpolation`, [DECISIONS.md DR-P1-03](DECISIONS.md#dr-p1-03-curves-on-log-discount-factors-sequential-or-global-bootstrap)):

- linear in $y$: the instantaneous forward $f(t) = -y'(t)$ is constant between pillars, so a pillar affects only its two neighbouring segments;
- monotone convex (Hagan and West 2006): forwards are continuous and stay within the range of the neighbouring average forwards;
- exponential tension spline: smooth like a cubic spline but with a tension that suppresses overshoot.

Beyond the last pillar the last segment's average forward is extended. Each instrument $k$ has an implied quote $q_k(y)$, a smooth function of the pillar values. The bootstrap solves the square system $q_k(y) = Q_k$ for all $k$, one instrument per pillar, to $|q_k - Q_k| \le 10^{-12}$:

- sequentially, pillar by pillar with Brent's method, when the interpolation is local and no instrument depends on dates beyond its own pillar;
- globally, by Levenberg-Marquardt on all $y_i$ with the analytic Jacobian $J_{kj} = \partial q_k / \partial y_j$, when instruments overlap or the interpolation is non-local.

Instruments: the overnight fixing from today to the next fixing day, $q = (P(t_0)/P(t_1) - 1)/\tau$; meeting-dated OIS between central bank effective dates, with the same formula over the meeting window, so that linear interpolation makes the overnight forward a step function that moves only at meetings; three-month futures, $q = 100(1 - R - CA)$ with $R$ the compounded rate over the IMM period and $CA$ the Hull-White convexity adjustment (Section 5); and par OIS swaps (Section 3). A known year-end turn is an extra spread over the turn window, applied on top of the interpolated curve.

### Glossary

- **Discount factor**: $P(0, T)$, the value today of one unit paid at $T$.
- **Pillar**: a date at which the curve's value is a free parameter, one per bootstrap instrument.
- **Bootstrap**: solving for pillar values so that every input instrument reprices to its quote.
- **OIS discounting**: discounting collateralised cash flows on the curve of the overnight rate paid on collateral.
- **Projection curve**: a curve used only to forecast a term index such as EURIBOR 6M, built with OIS discounting held fixed.
- **Meeting-dated OIS**: an OIS whose period runs between two central bank policy effective dates, quoting the expected overnight rate between those meetings.
- **Turn**: a known jump in the overnight rate over a short window such as year end, added to the curve explicitly.
- **Monotone convex interpolation**: the Hagan-West scheme that keeps instantaneous forwards continuous and within the range of the neighbouring average forwards.
- **Tension spline**: a spline with a tension parameter between a cubic spline (zero tension) and linear interpolation (infinite tension).

### Worked example: a SOFR curve on 15 January 2025

[COOKBOOK.md recipe 4](COOKBOOK.md#4-how-do-i-bootstrap-a-usd-sofr-discount-curve-from-ois-quotes) builds a curve from nine instruments: SOFR overnight at 4.30 percent (an estimate, since 15 January's SOFR is published on 16 January), three meeting-dated OIS to 8 May 2025 at 4.30, 4.29 and 4.22 percent, the September 2025 three-month future at 96.05, and par OIS at 2, 5, 10 and 30 years. The builder chooses the sequential solver. The two-year discount factor is 0.9266847083 (zero rate 3.776 percent) and the ten-year 0.6710374866 (3.983 percent); every instrument reprices within the 1e-12 solver tolerance.

The method is checked twice. Against QuantLib 1.44, the Java bootstrap of the four golden curves (USD, EUR and GBP OIS and a EUR 6M projection curve, 81 instruments) matches the QuantLib-priced bootstrap to 8.3e-16 in discount factor (`AnalyticsGoldenTest.javaBootstrapMatchesQuantLibCurvesAndRepricesInstruments`, 2026-10-31). Against itself, changing the 10Y quote by 7 basis points and rebuilding incrementally gives the same discount factors as a full rebuild to 3.9e-16 (linear), 7.5e-14 (monotone convex) and 6.8e-15 (tension spline) (`CurveBuilderTest.incrementalRebuildEqualsFullRebuild`, 2026-10-31).

## 3. Swaps: schedules, PV, par rate, DV01

### Plain idea

A swap is two schedules of payments. The fixed leg is easy: rate times accrual times discount factor. The overnight leg compounds a daily published rate over each period and pays the result a few days later. Getting either leg wrong by one day is a real error, so the schedule generator, the business-day rules and the separation between the calendar of fixings and the calendar of payments carry most of the work. Once the legs are right, the par rate is the fixed rate that balances them, and the DV01 is how much the value moves when the curve inputs move by one basis point.

### Formal statement

Schedule (`Schedule.generate`, the rules of QuantLib's `Schedule`): roll unadjusted dates by the period from the termination date backward (short front stub, the default) or from the effective date forward (short back stub); apply the end-of-month rule if requested; adjust intermediate dates by the business-day convention; merge a short stub into its neighbour for a long stub.

Compounded in arrears (ISDA 2021 Definitions, Section 7.6). For an accrual period with fixing-calendar business days $d_0 < \dots < d_n$ and published rates $r_i$:

$$
C = \prod_{i=0}^{n-1} \bigl(1 + r_i \, \delta_i\bigr), \qquad \text{coupon} = N (C - 1), \qquad \delta_i = \frac{d_{i+1} - d_i}{360 \text{ or } 365}
$$

paid on the payment calendar $L$ business days after the period end (USD $L = 2$, EUR 1, GBP 0). A lookback of $k$ days observes the rate $k$ fixing days earlier; a lockout of $k$ days repeats the rate observed $k$ days before the end; an observation shift weights each rate by its observation period. Rates not yet published are projected from the curve, $r_i = (P(d_i)/P(d_{i+1}) - 1)/\delta_i$, and runs of such days telescope to one ratio of discount factors.

Present value, par rate, annuity:

$$
PV_{\text{payer}} = \sum_p N (C_p - 1) P(\text{pay}_p) - N K \sum_i \alpha_i P(\text{pay}_i), \qquad K_{\text{par}} = \frac{\sum_p (C_p - 1) P(\text{pay}_p)}{\sum_i \alpha_i P(\text{pay}_i)}.
$$

DV01 by algorithmic differentiation ([DECISIONS.md DR-P1-04](DECISIONS.md#dr-p1-04-key-rate-dv01-by-algorithmic-differentiation-through-the-bootstrap)): the pricer returns $g_j = \partial PV / \partial y_j$; with the bootstrap Jacobian $J$, the sensitivity to the quotes solves $J^{\top} x = g$, and the key-rate DV01 of input $k$ is $x_k \times 10^{-4}$ (or $-100 \times x_k \times 10^{-4}$ for a futures price). The parallel DV01 is their sum by construction.

### Glossary

- **Compounded in arrears**: the floating amount is the compounded daily rate over the period, known only at its end.
- **Payment lag**: business days between the period end and payment, on the payment calendar.
- **Fixing calendar**: the days on which the index is published; distinct from the payment calendar (SOFR is not published on Good Friday even when SIFMA recommends only an early close).
- **Lookback, lockout, observation shift**: variants of the observation window that give the payer time to know the amount before paying.
- **Stub**: a period shorter (or longer) than the regular period at the start or end of a schedule.
- **End-of-month rule**: if the start is the last business day of its month, every date is the last business day of its month.
- **IMM swap**: a swap starting on an IMM date and ending on another.
- **Annuity**: $\sum_i \alpha_i P(\text{pay}_i)$, the value of one unit of fixed rate paid on the fixed leg.
- **Algorithmic differentiation (AD)**: computing exact derivatives by applying the chain rule through the code's operations, here backwards from the price to the curve inputs.

### Worked example: the 10-year swap, revisited

Section 1 estimated the DV01 of a 10-year par swap at 4 percent as about 81,000 USD per basis point per 100 million. [COOKBOOK.md recipe 5](COOKBOOK.md#5-how-do-i-price-a-swap-and-get-its-par-rate-and-dv01) computes it on the recipe 4 curve: a 10-year SOFR swap paying 4 percent on 100 million has par rate 3.990000 percent, annuity 8.232552, present value minus 82,325.52 USD and parallel DV01 82,367.26 USD per basis point, almost all of it on the 10Y pillar because the swap has the dates of the 10Y curve instrument. Bumping each quote one basis point up and down gives 82,367.28.

Against QuantLib 1.44 over 83 swaps (spot, off-market, forward-starting, IMM, seasoned with real 2024 fixings, and USD with lookback, lockout and observation shift), present values agree to 1.6e-15 of notional and par rates to 3.5e-16; parallel DV01 by AD agrees with QuantLib's one-basis-point central bumps to 1.4e-6 relative, the size of the bump's own truncation error (`AnalyticsGoldenTest`, 2026-10-31).

Holdout year 2024: a one-year OIS's daily grid has exactly the publication dates of SOFR, EUR STR and SONIA in 2024, and compounding the published rates with the swap's day weights reproduces every published SOFR Index, EUR STR compounded index and SONIA Compounded Index value from 3 January 2024 to 31 January 2025 to the eighth decimal, from a starting value within the published starting index's rounding interval (`CompoundedIndexHoldoutTest`, 2026-10-31).

## 4. Government bonds: price, yield, carry, repo

### Plain idea

A bond's price is the value of its remaining coupons and principal. Markets quote the clean price and settle the dirty price, which adds the coupon interest earned since the last payment. The yield is the single rate that discounts the cash flows to the dirty price, under a stated convention for how fractions of a period count. Owning a bond costs money to finance, normally in repo; the difference between the coupon income and that financing cost is carry, and the price change from the bond sliding down a sloped curve as it ages is roll-down.

### Formal statement

Accrued interest (Act/Act ICMA): $AI = 100 \, c \, \frac{\text{days since last coupon}}{f \times \text{days in the coupon period}}$; for a gilt inside its ex-dividend period (seven business days before the coupon) $AI = -100 \, c \, \frac{\text{days to next coupon}}{f \times \text{days in the period}}$.

Price from yield, street convention (`BondPricer.dirtyPrice`, QuantLib's `CashFlows::npv`): with times $t_k$ accumulated period by period in Act/Act ICMA from settlement to each nominal coupon date,

$$
P_{\text{dirty}} = \sum_k CF_k \Bigl(1 + \frac{y}{f}\Bigr)^{-f t_k}.
$$

The true yield uses coupon dates moved to business days. The Treasury's auction formula (31 CFR 356, Appendix B) instead discounts the first fractional period with simple interest, $P_{\text{dirty}} = V_{\text{next}} / (1 + \tfrac{r}{s}\tfrac{y}{2})$, and the Treasury rounds the dirty price and the accrued interest to six decimals before subtracting.

Risk: modified duration $D = -P'/P$, convexity $P''/P$, and the quoted DV01 $= D \, P_{\text{dirty}} \times 10^{-4}$ per 100 face, a positive number by market habit. In the risk engine's signed convention a long bond's DV01 is minus this figure times face over 100 ([API_SPEC.md Section 8.3](API_SPEC.md#83-dv01ladder-consumed-by-the-p3-risk-engine)).

Repo forward and carry, for repo rate $r$ (Act/360) over $d$ days and coupons $C_i$ paid $d_i$ days before the horizon:

$$
F_{\text{clean}} = P_{\text{dirty}}\Bigl(1 + \frac{r d}{360}\Bigr) - \sum_i C_i \Bigl(1 + \frac{r d_i}{360}\Bigr) - AI_{\text{horizon}}, \qquad \text{carry} = P_{\text{clean}} - F_{\text{clean}}.
$$

Spreads to the OIS curve: the z-spread $s$ solves $P_{\text{dirty}} = \sum_k CF_k P(t_k) e^{-s t_k} / (P(t_0) e^{-s t_0})$; the par-par asset-swap spread is $(B - P_{\text{dirty}})/(100 A)$, with $B$ the bond's value on the curve and $A$ the floating annuity. Fitted curves for relative value: Nelson-Siegel-Svensson (the Bundesbank's daily form) and a natural cubic spline in yield.

### Glossary

- **Clean and dirty price**: the quoted price without and with accrued interest.
- **Accrued interest**: coupon interest earned since the last coupon date, paid by the buyer to the seller.
- **Ex-dividend period**: for gilts, the seven business days before a coupon date, during which a buyer does not receive the coupon and accrued interest is negative.
- **Street yield**: yield on nominal coupon dates, compounded at the coupon frequency.
- **True yield**: yield on the dates coupons are actually paid.
- **Modified duration**: minus the relative price change per unit change in yield.
- **Convexity**: the relative second derivative of price in yield.
- **Repo**: a sale and repurchase that finances a bond; **general collateral (GC)** is the rate for any eligible bond, a bond **trading special** finances below GC.
- **Carry**: coupon income minus financing cost to a horizon, here in price terms.
- **Roll-down**: the price change from the bond's yield moving along an unchanged curve as its maturity shortens.
- **Z-spread**: the constant continuously compounded spread over the OIS zero curve that reprices the bond.
- **Asset-swap spread**: the spread over the floating index received by a buyer who pays par for the bond and swaps its coupons.
- **Nelson-Siegel-Svensson**: a six-parameter smooth zero curve used by central banks for government yields.

### Worked example: the 4 1/4% Treasury note of November 2034

[COOKBOOK.md recipe 6](COOKBOOK.md#6-how-do-i-price-a-treasury-from-yield-and-get-accrued-interest) prices the note for trade on 15 January 2025, settling 16 January, at a 4.66 percent yield: clean 96.790759, accrued 0.727901, dirty 97.518659, modified duration 7.860048, DV01 766.50 USD per million face. The true yield for the same price is 4.65971296 percent.

Two of those numbers can be checked by hand. The last coupon was 15 November 2024 and the next is 15 May 2025, a period of 181 days, of which 62 have passed at settlement; the half-year coupon is 2.125, so accrued interest is $2.125 \times 62/181 = 0.727901$. The quoted DV01 per million face is $7.860048 \times 97.518659 \times 10^{-4} \times 10{,}000 = 766.50$ USD: duration times dirty price, scaled from per 100 to per million. A desk holding the million long has a signed DV01 of minus 766.50.

Its Appendix B price at the 11 December 2024 reopening auction's high yield of 4.235 percent is 100.114150, the published value.

Checks: all 84 note and bond auctions and all 332 bill auctions of 2024 reproduce the published price to six decimals and the published yield to three (`PublishedExamplesTest`, TreasuryDirect data in `data/fixtures/ust_auctions_2024.csv`, 2026-10-31). Against QuantLib 1.44 for 67 Treasuries, Bunds and gilts (four of the gilts settle inside their ex-dividend period), clean price and accrued interest agree to 3.3e-13 per 100, street and true yields to 7.3e-16, duration, convexity and DV01 to 4.2e-15 relative (`AnalyticsGoldenTest.bondsMatchQuantLib`, 2026-10-31). The UK DMO's gilt reference prices could not be downloaded by script (data/SOURCES.md), so no DMO worked example is in the gate yet.

### Worked example: a gilt across its ex-dividend date

[COOKBOOK.md recipe 7](COOKBOOK.md#7-how-do-i-compute-a-gilts-accrued-interest-inside-the-ex-dividend-period) takes the 4 1/8% Treasury Gilt 2027, which pays 2.0625 on 22 January and 22 July. The coupon period from 22 July 2024 to 22 January 2025 has 184 days, and the bond goes ex-dividend on 13 January 2025, seven London business days before the coupon. Settling on 10 January, the buyer will receive the coupon and pays 172 days of accrued interest, $2.0625 \times 172/184 = 1.927989$. Settling on 13 January, the seller keeps the coupon, so the buyer is owed the 9 days left: $-2.0625 \times 9/184 = -0.100883$. The recipe prints both numbers, and clean prices at a 4.36 percent yield of 99.546230 and 99.550463 that move smoothly across the date, because the dirty price drops by the coupon at the same moment as the accrued interest does. The sign flip is a classic source of errors; [Section 13](#13-pitfalls-and-numerical-issues) lists it.

## 5. Futures: CTD, basis, convexity

### Plain idea

A Treasury bond future is a promise to deliver one of a basket of bonds at a fixed price times a conversion factor. The short chooses which bond, so the future tracks the bond that is cheapest to deliver. The basis is the gap between the bond's price and the futures price times the factor; it shrinks to the delivery option's value at expiry, and after carry what is left is the net basis. A SOFR future pays on the average or compounded SOFR over a reference period, and because it is settled daily its rate sits slightly above the forward rate: the convexity adjustment.

### Formal statement

Conversion factor (CME formula for CBOT contracts): with $n$ whole years and $z$ months (rounded down to a quarter, or a month for the 2-, 3- and 5-year notes) from the first day of the delivery month to maturity, $v = z$ if $z < 7$ else $z - 6$,

$$
a = 1.03^{-v/6}, \quad b = \frac{c}{2} \frac{6 - v}{6}, \quad C = 1.03^{-2n} \text{ or } 1.03^{-(2n+1)}, \quad d = \frac{c}{0.06}(1 - C), \quad CF = a\Bigl(\frac{c}{2} + C + d\Bigr) - b,
$$

rounded to four decimals. Eurex and ICE factors are the clean price per unit face at the contract's notional coupon (6 percent annual, 4 percent semi-annual) on the delivery date.

Basis, carry and implied repo for spot settlement $s$, delivery $D$, futures price $F$: gross basis $= P - F \, CF$; implied repo $= (F \, CF + AI_D + \sum C_i - P_{\text{dirty}}) / (P_{\text{dirty}} d/360 - \sum C_i (D - t_i)/360)$; net basis = gross basis minus carry at the repo rate. The CTD maximises implied repo. Futures DV01 = CTD DV01 / CF.

SOFR futures: price $= 100 (1 - R - CA)$, with $R$ the compounded (three-month) or arithmetic average (one-month) SOFR over the reference period. In the Hull-White model $dr = (\theta(t) - a r)dt + \sigma dW$, with $B(s,t) = (1 - e^{-a(t-s)})/a$,

$$
CA = \frac{B(t_1, t_2)}{t_2 - t_1}\Bigl[B(t_1, t_2)(1 - e^{-2 a t_1}) + 2 a B(0, t_1)^2\Bigr] \frac{\sigma^2}{4a},
$$

with $a = 0.03$ and $\sigma = 0.0085$ ([DECISIONS.md DR-P1-05](DECISIONS.md#dr-p1-05-futures-convexity-by-hull-white-one-factor-with-stated-parameters)).

### Glossary

- **Conversion factor**: the price per unit face of a deliverable bond at the contract's notional coupon yield.
- **Gross basis**: cash price minus futures price times conversion factor.
- **Net basis**: gross basis minus carry to delivery.
- **Implied repo**: the financing rate at which buying the bond and delivering it into the future breaks even.
- **Delivery option**: the short's right to choose the bond and the day of delivery, which the net basis prices.
- **Convexity adjustment**: the excess of a daily-settled futures rate over the forward rate, from the correlation between margin flows and rates.
- **Cheapest to deliver (CTD)**: the deliverable bond with the highest implied repo, equivalently the lowest net basis; the future's price and risk follow it.
- **CTD switch**: a change of the cheapest-to-deliver bond when yields move, which changes the future's duration abruptly.
- **SOFR future**: a CME contract settled on the compounded (three-month) or averaged (one-month) SOFR over a reference period, quoted as 100 minus the rate.
- **Hull-White model**: a one-factor short-rate model with mean reversion $a$ and volatility $\sigma$, used here only for the convexity adjustment.

### Worked example: the March 2025 10-year note future

[COOKBOOK.md recipe 8](COOKBOOK.md#8-how-do-i-find-the-ctd-and-net-basis-for-a-treasury-futures-contract) takes four bonds in the TY March 2025 basket at prices from the golden file's smooth curve, with the future at 108-11+ (108.359375) and repo at 4.30 percent. The 4 1/8% note of November 2031 (CF 0.9003) has the highest implied repo, 4.1674 percent, and the smallest net basis, 0.0267: it is the CTD. The deep-discount 1 3/8% of November 2031 (CF 0.7541) has an implied repo of minus 0.94 percent. The recipe prints the pieces, so the arithmetic can be checked: for the CTD the gross basis is $-0.0016$ (its clean price is 0.0016 below $108.359375 \times 0.9003 = 97.5560$) and the carry to delivery is $-0.0284$, because the 4.30 percent repo costs more than the 4 1/8 coupon earns; net basis is gross minus carry, $-0.0016 + 0.0284 = 0.0268$, printed as 0.0267 from the unrounded inputs. The future's DV01 is the CTD's DV01 divided by 0.9003, which is why a hedger counts futures by the CTD's risk and not by the contract's face (Section 8).

Against the golden file, all 38 conversion factors (CBOT, Eurex and ICE) match exactly at their published rounding, and gross basis, carry, net basis and implied repo agree to 1e-10 with the same CTD in five contracts (`AnalyticsGoldenTest`, 2026-10-31). SOFR futures priced on the golden USD curve match QuantLib's `OvernightIndexFuture` exactly for six one-month and four three-month contracts. For the December 2025 three-month contract the convexity adjustment is 0.38 basis points.

## 6. How rates trade electronically: RFQ versus CLOB, SEFs, workup

### Plain idea

Rates trade on two kinds of electronic venue. On a dealer-to-client (D2C) venue a client asks a few dealers for a price, the dealers answer within seconds, and the client trades with the best answer or with nobody: this is request for quote, RFQ. On a dealer-to-dealer (D2D) venue dealers post anonymous bids and offers in a central limit order book, CLOB, and trade with each other by hitting or lifting them. US Treasuries trade in both places. Interest rate swaps traded by US persons must, for the most liquid tenors, trade on a swap execution facility, SEF, which offers both an order book and RFQ under rules written by the CFTC.

Two details shape almost everything a dealer's quoting engine does. In RFQ, what the venue tells each dealer after the trade differs: the winner usually learns the cover (the second-best price), losers learn only that they lost. In the Treasury CLOB, a trade is followed by a workup: for a few seconds more size can trade at the same price, first among the original parties and then among anyone, so the visible book understates how much trades at a price.

### RFQ

Formal statement. An RFQ names an instrument, a side from the client's point of view, a size and the dealers in competition. Each dealer may answer once (and may replace its answer) before a response window closes. The client then either accepts one quote, passes, or lets the request expire; on some platforms the client may also report that it traded elsewhere. With quotes $p_1, \dots, p_n$ on the client's side, a client buying chooses $\min_i p_i$ and a client selling chooses $\max_i p_i$; ties go to the earlier quote. The cover is the second-best of the same set. What is disclosed after the outcome is a platform choice:

| Outcome | Winner is told | Other quoting dealers are told | Dealers who did not quote |
|---|---|---|---|
| Client trades on the venue | traded, at its price, and on many platforms the cover | traded away (no price) | nothing or expired |
| Client trades elsewhere | done away | done away | nothing or expired |
| Client passes | passed | passed | nothing or expired |
| No quotes in time | expired | expired | expired |

The cover tells the winner how much it left on the table; a winner that repeatedly wins by a wide margin is quoting too aggressively. Losers learn that they lost but not by how much, which is why win-probability models cannot be fitted from a dealer's own records alone ([DECISIONS.md DR-008](DECISIONS.md#dr-008-competition-model-for-rfq-win-probability)).

How `rates-venues` models it. `D2cRfqSimulator` sends each RFQ to every logged-on engine dealer and to simulated competitors, three to five dealers in total. Simulated half spreads are lognormal around a median (0.0078125 in price for Treasuries, 0.0025 in rate for SOFR swaps), response times are lognormal around 800 ms, and the client trades with the best quote if it is within a lognormal tolerance of mid, passes otherwise, and trades elsewhere with probability 0.05. RFQ arrivals follow an intensity that rises around 08:00, 08:30, 10:00, 13:00 and 15:00 New York time (fixings, data releases, auction results, the close). A share of clients is informed: after an informed client trades, mid moves in the client's favour, so the dealer's markout on those trades is worse. Every number is a declared assumption in `rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`, not a fit (DR-P2V-05). On the wire the winner gets QuoteResponse with QuoteRespType 1 and the cover in tag 9502, losers get QuoteRespType 5 with 9503=TA, a done-away RFQ gives 9503=DA ([API_SPEC.md Section 10.2](API_SPEC.md#102-tag-sets-per-flow)).

### CLOB and workup

Formal statement. A CLOB keeps bids and offers by price, and within a price by arrival time (price-time priority). An incoming order that crosses the best opposite price trades against resting orders in that priority until it is filled or no longer crosses; the rest of it rests (or is cancelled, if immediate-or-cancel). Participants are anonymous to each other. A Treasury CLOB adds a minimum size (here 1 million face, in multiples of 1 million), a tick (1/128 of a point here) and iceberg orders, which show only a slice $\min(\text{display}, \text{remaining})$ and go to the back of the queue each time a slice is refilled.

Workup. After a trade at price $P$, the book enters a workup at $P$ for a duration $W$. During the first $E \le W$ seconds (the exclusive window) only the participants of the opening trade may trade more at $P$; others who arrive with marketable orders wait. After $E$, anyone may trade at $P$. Each further trade may restart the timer. When the timer expires, normal matching resumes. Fleming, Mizrach and Nguyen (2018) describe the protocol on BrokerTec and how much of the platform's volume trades in workups; for a quoting engine the consequence is that displayed depth understates what can trade at the touch.

How `rates-venues` models it. `ClobBook` applies these rules with $W = 5$ s and $E = 2$ s by default; during workup a marketable order trades only at $P$ and any remainder rests at $P$, and an order that would trade at another price waits until the workup ends (a simplification stated in [API_SPEC.md Section 11.2](API_SPEC.md#112-venue-rules)). Execution reports carry the workup state in tag 9504: N normal, X exclusive window, W open workup. Self-match prevention cancels the resting order of the same participant by default. Every book change is published on an SBE price-level feed with one sequence number per venue ([API_SPEC.md Section 10.5](API_SPEC.md#105-sbe-book-feed)).

### Swap execution facilities

Formal statement. Under the Dodd-Frank Act, swaps subject to the CFTC clearing mandate that a SEF or designated contract market has made available to trade (MAT) must be executed on a SEF or DCM (17 CFR 37.10 sets the MAT process). These are Required Transactions. 17 CFR 37.9 allows two methods for them: the order book, or an RFQ system that sends the request to at least three participants, operated together with the order book (RFQ-to-3). Every other swap on a SEF is a Permitted Transaction and may use any method, including RFQ to one dealer or voice. Block trades, at or above the appropriate minimum block size published under 17 CFR 43.6, are not bound by the Required Transaction methods. Before a trade is executed, the clearing futures commission merchant (FCM) must be able to screen it against the risk-based limits it sets for its customer (17 CFR 1.73), and the SEF must support that screening (17 CFR 37.702(b)); a trade that would be rejected for clearing should never be executed. The industry term for this is clearing certainty.

How `rates-venues` models it. `SefSimulator` reads the MAT flag and block threshold of each swap template from `data/refdata/v1/swap_templates.csv`. An RFQ for a Required Transaction below block size to fewer than three dealers is refused before any dealer sees it; the QuoteRequest an engine dealer receives carries 9506=Q (required) or P (permitted) and 9507=Y for a block. `FcmLimitHub` assigns each party an FCM with a notional limit; an order whose notional would breach its FCM's limit is rejected at entry (venue reject code 6), and an RFQ acceptance that would breach either side's limit is rejected with code 7. The limits, the party mapping and the block thresholds are declared assumptions, not the published CFTC block sizes, which change with each annual recalculation.

### What each venue discloses

| | D2C RFQ (Tradeweb or Bloomberg style) | D2D CLOB (BrokerTec style) | SEF RFQ |
|---|---|---|---|
| Counterparty identity before the trade | the client is named to the dealers it asks | anonymous | the requester is named to the dealers it asks |
| Number in competition | often shown to dealers (tag 9501 here) | not applicable | shown (tag 9501 here) |
| Prices of other dealers | not shown; the winner may receive the cover | the whole book is visible, without names | not shown; the winner may receive the cover |
| Outcome for losing dealers | traded away or done away, no price | not applicable | traded away |
| Public post-trade information | US Treasuries: FINRA TRACE, published in aggregate | trades are visible on the feed | swaps: public dissemination through a swap data repository |

### Glossary

- **RFQ**: request for quote; a client asks named dealers for a price on a stated size and side.
- **CLOB**: central limit order book; anonymous bids and offers matched by price-time priority.
- **Cover price**: the second-best quote on the client's side of an RFQ.
- **Traded away**: the client traded with another dealer on the same RFQ.
- **Done away**: the client reports trading the RFQ elsewhere.
- **Workup**: a period after a CLOB trade during which more size can trade at the same price, first exclusively for the trade's participants.
- **Iceberg order**: an order that displays only part of its size and refills from the hidden rest.
- **Self-match prevention**: a venue rule that stops a participant's orders from trading with each other.
- **SEF**: swap execution facility, a CFTC-registered venue for swaps.
- **MAT**: made available to trade; a swap a SEF or DCM has certified as required to trade on a SEF or DCM.
- **Required Transaction**: a MAT swap, which must use the order book or RFQ-to-3 unless it is a block.
- **Permitted Transaction**: any other swap on a SEF; any execution method.
- **Block trade**: a swap at or above the appropriate minimum block size; exempt from the Required Transaction methods and given delayed public reporting.
- **FCM**: futures commission merchant; the clearing member that guarantees a customer's cleared trades.
- **Clearing certainty**: assurance before execution that a trade will be accepted for clearing.

### Worked example: what the cover price tells the winner

[COOKBOOK.md recipe 10](COOKBOOK.md#10-how-do-i-start-a-venue-simulator-and-connect-a-fix-44-session) (`rates-cookbook/src/main/java/io/ratesalgo/cookbook/Recipe10VenueSession.java`, output recorded on 2026-10-31) sends a client RFQ to buy 10 million face of the 10-year note `US91282CZG94` to four dealers. Three simulated dealers offer 98.03, 98.02 and 98.05; we offer 98.015. The client buys from us at 98.015, and the venue discloses the cover, 98.02.

Method. Prices are per 100 of face. The money left on the table is the cover minus our price, times face over 100:

$$
(98.02 - 98.015) \times \frac{10{,}000{,}000}{100} = 0.005 \times 100{,}000 = 500 \text{ USD}.
$$

We would still have won at any offer below 98.02, so up to 500 USD more was available on this trade. The cover does not say whether the trade will be profitable: that depends on where mid goes next, which is what the markout measures. In the simulator, `D2cModelTest.informedClientsHaveWorseMarkoutsForTheDealer` (`rates-venues/src/test/java/io/ratesalgo/venues/d2c/D2cModelTest.java`) checks that the mean dealer markout at the five-minute horizon is lower on trades with informed clients than with uninformed ones, which is the winner's curse the quoting engine has to price (Section 7).

### Worked example: a workup

The certification script `rates-venues/src/main/resources/io/ratesalgo/venues/cert/clob-workup.json` (run by `CertSuiteTest`, green on 2026-10-31) rests a 10 million offer at 98 from dealer A, then our 2 million bid at 98 lifts it: execution report with 9504=N, and a workup opens at 98. Dealer B, not a participant, then bids 98 and waits. We bid 98.0078125 inside the exclusive window: as a participant we trade, but at the workup price 98, with 9504=X. After 2.5 seconds the exclusive window has closed: dealer B's bid trades at 98 and our next bid trades with 9504=W. Six seconds later the workup has ended and our next bid at 98 is a normal trade again (9504=N). Five trades printed at 98 although only one order was ever displayed on the offer side.

### Sources

- CFTC, 17 CFR 37.9, methods of execution for Required and Permitted Transactions: <https://www.ecfr.gov/current/title-17/section-37.9>
- CFTC, 17 CFR 37.10, process for a swap to be made available to trade: <https://www.ecfr.gov/current/title-17/section-37.10>
- CFTC, 17 CFR 43.6, appropriate minimum block sizes: <https://www.ecfr.gov/current/title-17/section-43.6>
- CFTC, 17 CFR 1.73, clearing FCM risk management: <https://www.ecfr.gov/current/title-17/section-1.73>
- CFTC, 17 CFR 37.702, general financial integrity requirements for SEFs: <https://www.ecfr.gov/current/title-17/section-37.702>
- FIX Trading Community, FIX 4.4 specification: <https://www.fixtrading.org/standards/fix-4-4/>
- M. Fleming, B. Mizrach and G. Nguyen (2018), "The microstructure of a U.S. Treasury ECN: The BrokerTec platform", Journal of Financial Markets 40.
- T. Hendershott and A. Madhavan (2015), "Click or call? Auction versus search in the over-the-counter market", Journal of Finance 70(1).
- L. Riggs, E. Onur, D. Reiffen and H. Zhu (2020), "Swap trading after Dodd-Frank: Evidence from index CDS", Journal of Financial Economics 137(3).

## 7. Dealer market making: inventory, skew, cover prices, winner's curse

### Plain idea

A dealer answering a request for quote (RFQ) sells immediacy. The client asks several dealers at once; the best price wins; the winner takes the position onto its book and must later get rid of the risk. The dealer's price is fair value plus a margin, and the margin pays for three things: the chance that the client knows something (adverse selection), the cost of hedging the position, and the fact that winning against other dealers is itself bad news about one's own price (the winner's curse). Too wide and the dealer never trades; too tight and it trades at a loss. Inventory changes the answer: a dealer already long duration wants to sell more than to buy, so it shades both prices down. That shading is the skew.

### Formal statement

Let s be the distance of our quote from fair value, in basis points of value (the quote move that changes the position's value by one basis point of PV01). Let P(s) be the probability of winning and c the expected cost of a fill in the same units. The expected profit of one request is

$$
E(s) = P(s)\,(s - c),
$$

and the engine quotes the s that maximises it ([API_SPEC.md Section 12.2](API_SPEC.md#122-spread)). With a logistic win probability $P(s) = 1 / (1 + e^{-(z_0 + b_1 s)})$, where $z_0$ collects size, tenor, time of day and dealer count and $b_1 < 0$, the first-order condition is

$$
s^* = c + \frac{1}{-b_1 \,(1 - P(s^*))}.
$$

The margin above cost is the inverse of how fast the win probability falls, scaled up when the dealer usually loses anyway.

The cost has three parts. Adverse selection is measured per client by markout: the move in fair value, from our side, a fixed time after each fill; a client whose fills are followed by moves against us pays its negative mean markout. The winner's curse grows with the number of competitors n: if each dealer's estimate of fair value carries an independent error, the winning quote is the one with the most favourable error to the client, and the expected maximum of n normal errors grows like $\sqrt{2 \ln n}$. The hedge cost is what it costs to lay the trade's PV01 off in the cheapest hedge instrument.

Inventory enters through a reservation price, as in Avellaneda and Stoikov's market-making model. With book ladder p, the instrument's per-unit ladder u, its parallel PV01 U and the key-rate covariance $\Sigma$, adding one unit changes the book's risk penalty $\tfrac{1}{2}\kappa\, p^\top \Sigma p$ by $\kappa\, u^\top \Sigma p$ to first order, so both sides move by

$$
\text{skew} = \kappa \frac{u^\top \Sigma p}{|U|}
$$

basis points of value, bounded by a maximum, plus a size term $\tfrac{1}{2} \kappa Q\, u^\top \Sigma u / |U|$ that widens large clips on both sides. The skew is positive when the book is long the instrument's risk, which lowers the offer and the bid alike: a long book offers better and bids worse.

After a trade the venue may show the winner the cover, the second-best price. The distance between the winning price and the cover is money left on the table, and it is information: a quote that won by 0.5 bp would also have won 0.5 bp wider. The engine learns from wins, losses and covers by moving the logistic's intercept and slope one gradient step at a time, never outside the declared assumption band ([DECISIONS.md DR-008](DECISIONS.md#dr-008-competition-model-for-rfq-win-probability), DR-P3-02). Losses carry less information, because a losing dealer never sees the winning price.

### Glossary

- **Cover**: the second-best price in a multi-dealer RFQ, shown to the winner by some venues.
- **Markout**: P&L of a fill measured against fair value a fixed time later, from the dealer's side.
- **Adverse selection**: the expected loss from trading with clients who know more than the dealer.
- **Winner's curse**: the bias in a winning quote that comes from winning only when one's own error favours the client.
- **Skew**: a shift of both bid and offer, in the same direction, driven by inventory.
- **Reservation price**: fair value adjusted for the dealer's inventory, around which the spread is set.
- **Firm quote**: a price the client can trade on until it expires, with no last look; a subject quote is indicative.
- **Win probability (hit rate)**: the probability that a quote at a given distance from fair value wins the RFQ; a declared logistic band in this project ([DECISIONS.md DR-008](DECISIONS.md#dr-008-competition-model-for-rfq-win-probability)).
- **Half-spread**: the distance of each side of a two-way quote from the reservation price before skew and size terms.
- **Improve limit**: the furthest a skewed quote may go through fair value, 0.25 bp by default ([API_SPEC.md Section 12](API_SPEC.md#section-12-quoting-parameters-and-the-hit-rate-preset-p3)).

### Worked example

Take a request for 25 million of a 10-year Treasury against four dealers in the New York afternoon, using the shipped default preset (`rates-quoting/src/main/resources/quoting-hit-rate-default.yaml`): $b_0 = 1.0$, spread coefficient $b_1 = -1.6$, size term zero for a typical clip, tenor $-0.01 \times 10$, liquid session $+0.2$, dealers $-0.35 \times 3$. So $z_0 = 1.0 - 0.1 + 0.2 - 1.05 = 0.05$. A quote 0.5 bp from fair wins with probability $1 / (1 + e^{-(0.05 - 0.8)}) = 0.32$.

The winner's curse charge with four dealers is $0.1 \times \sqrt{2 \ln 4} = 0.1 \times 1.665 = 0.167$ bp at the reference volatility (the engine multiplies it by the ratio of current to reference volatility, `QuotingEngine`). The dealer term counts competitors other than us, $n - 1 = 3$, and the preset's log-size term, $-0.15$ per unit of log size, is zero for a typical clip. With a cost of 0.2 bp in total, the optimum is $s^* = 0.965$ bp, where $P = 0.183$ and the expected profit is $0.183 \times (0.965 - 0.2) = 0.140$ bp per request. With no cost it would be 0.806 bp; with 0.5 bp of cost, 1.219 bp. These were computed by grid search on 2026-10-31 and agree with the first-order condition, which `PackageAndClobTest.presetParsingAndSpreadOptimum` checks for the engine's golden-section search to 1e-6.

[COOKBOOK.md recipe 14](COOKBOOK.md#14-how-do-i-quote-an-rfq-through-the-engine-and-see-a-quoting-parameters-effect-on-skew) runs the engine itself on this request (recorded 2026-10-31, `rates-cookbook/src/test/resources/expected/recipe14.txt`). With a flat book and no hedge instruments, so that the cost is the winner's curse alone, the half-spread is 0.938 bp, the size term 0.948 bp, the offer 1.886 bp from fair, and the model win probability 0.049. Long 150 million, the skew reaches its 2 bp bound and the same request is offered 0.114 bp through fair, a better price than fair, with win probability 0.558: the desk pays to reduce risk. A client who wants to sell to that long book is bid 3.886 bp below fair, with win probability 0.002. The recipe's request messages carry the client's side (Buy for the first two, Sell for the third), and its printout shows ours (Sell, Sell, Buy): the two views of one RFQ that the conventions table at the top separates.

The skew arithmetic can be followed in the printout: with a 2 bp skew both prices move 2 bp lower, so the offer goes from $0.938 + 0.948 = 1.886$ bp above fair to $1.886 - 2 = -0.114$ bp, and the bid from 1.886 bp below fair to $1.886 + 2 = 3.886$ bp below.

### How it is tested

`QuotingEngineTest` checks the direction of the skew, its bound, the improve limit, tier spreads, toxicity widening, cover learning, subject quotes and every pass reason. `MillionRfqTest` runs one million RFQs with fills drawn from the model's own win probability and audits every message at the gateway. It asserts zero limit violations, that every RFQ was handled and that no quote is left held; the counts it printed on 2026-10-31 were: it sent 476,215 quotes, won 30,933, passed 509,281 requests for lack of risk capacity and 14,504 for the re-quote throttle, with zero limit violations at the sink (`mvn -B -ntp verify`, JDK 25.0.5, Windows 11).

## 8. Hedging and the DV01 ladder

### Plain idea

A rates book is a list of positions, but its risk is a handful of numbers: how much it gains or loses if each part of the curve moves by one basis point. Those numbers, one per tenor bucket, form the DV01 ladder. Hedging means trading liquid instruments, mostly futures and on-the-run swaps, whose own ladders cancel the book's. Cancelling the total (net DV01) is easy and leaves curve risk; cancelling every bucket costs more. The hedger's job is to buy as much risk reduction as is worth its cost, and to buy it gradually when trading fast is expensive.

### Formal statement

The key-rate PV01 of a position at pillar k is the change in its value when only the zero rate at pillar k rises by one basis point (the pillars are 1Y, 2Y, 3Y, 5Y, 7Y, 10Y, 20Y and 30Y; between pillars the bump is interpolated linearly). In this project PV01 is PV(rates + 1bp) minus PV(base), so a long bond is negative and a payer swap positive, the same sign as the analytics' `Dv01Ladder` ([API_SPEC.md Sections 8.3](API_SPEC.md#83-dv01ladder-consumed-by-the-p3-risk-engine) and 13). The two ladders differ in their buckets: the analytics ladder of Sections 2 and 3 is by curve input (the ON fixing, meeting OIS, futures and par swaps of the bootstrap), while the risk engine's is by these eight zero-rate pillars, so a binding of one to the other re-buckets. The ladder r of a book is the sum of its positions' ladders. If daily rate changes have covariance $\Sigma$ (bp squared), the one-day P&L variance of the book is $r^\top \Sigma r$.

With hedge instruments whose per-lot ladders are the columns of H and x lots traded, the residual is r + Hx. The hedger solves

$$
\min_x \tfrac{1}{2} \kappa\,(r + Hx)^\top \Sigma (r + Hx) + \sum_j \big(a_j |x_j| + b_j x_j^2\big)
$$

with $a_j$ the half spread plus any basis cost of using instrument j and $b_j$ its market impact ([API_SPEC.md Section 14](API_SPEC.md#section-14-hedger-objective-and-solver-tolerances-p3)). Futures are cheap and fast but carry basis: a bond future tracks its cheapest-to-deliver bond, not the key rate exactly, and the cost model charges for that. Swaps hedge a key rate directly but cost more per unit of PV01 in small size and less in large size because their impact is lower. When the basis widens, the futures' linear cost rises and the solution moves to swaps.

Staged hedging sends part of the plan at once and the rest over time. With quadratic impact, trading x lots in two halves costs $2 b (x/2)^2 = b x^2 / 2$ instead of $b x^2$, at the price of carrying some risk for longer. The hedger sends a first tranche and then a fraction $1 - 2^{-\Delta t / t_{1/2}}$ of the remaining plan per timer, so the residual decays with half-life $t_{1/2}$.

### Glossary

- **DV01, PV01**: value change for a one basis point rise in rates; key-rate PV01 bumps one pillar at a time.
- **DV01 ladder**: key-rate PV01 by pillar for a book.
- **Net DV01**: the sum of the ladder; gross DV01, the sum of absolute values.
- **Basis**: the difference between a future's implied yield and the cash or swap rate it hedges.
- **Hedge ratio**: lots of a hedge per unit of the position, here the ratio of PV01s.
- **Residual risk**: the ladder left after hedging, and its variance.
- **Arrival mid**: the mid price when the hedge was decided, against which slippage is measured.
- **Covariance matrix $\Sigma$**: daily covariance of key-rate changes in bp squared; $r^\top \Sigma r$ is the book's one-day P&L variance.
- **Staged hedging**: sending part of a hedge plan now and the rest over time, trading market impact against the risk carried meanwhile.
- **Box-constrained QP**: the hedger's quadratic programme with lower and upper bounds on each instrument's lots, solved by accelerated projected gradient ([DECISIONS.md DR-P3-06](DECISIONS.md#dr-p3-06-the-hedge-is-a-box-constrained-convex-qp-solved-by-accelerated-projected-gradient)).

### Worked example

A curve position: long 200 million 10-year Treasuries, short 400 million 2-year, long 50 million 30-year ([COOKBOOK.md recipe 15](COOKBOOK.md#15-how-do-i-read-the-dv01-ladder-and-compute-a-cost-aware-hedge), recorded 2026-10-31). With PV01 per unit of face of -8.2e-4, -1.9e-4 and -1.7e-3, the ladder is +76,000 at 2Y, -164,000 at 10Y and -85,000 at 30Y; net -173,000 per basis point. Each entry is face times PV01 per unit: $-400{,}000{,}000 \times -1.9 \times 10^{-4} = +76{,}000$ for the short 2-year, $200{,}000{,}000 \times -8.2 \times 10^{-4} = -164{,}000$ and $50{,}000{,}000 \times -1.7 \times 10^{-3} = -85{,}000$. The short 2-year position gains when rates rise, so its entry is positive. Under the default covariance the unhedged one-day standard deviation is 1,135,508 USD.

Hedging the net alone means selling 173,000 / 80 = 2,162 ten-year futures (80 USD of PV01 per contract). Net DV01 goes to zero, but the curve risk remains: the residual standard deviation is still 647,435 USD and the modelled cost 108,079 USD, most of it impact. The cost-aware plan uses six instruments, buying 2-year futures and receiving 2-year swaps against the short 2s, selling 10-year futures and paying 10-year swaps, and covering the 30-year with both futures and swaps. It leaves a standard deviation of 7,634 USD at a modelled cost of 57,307 USD. The test `HedgePlannerTest.costAwarePlanBeatsImmediateFullHedgeOnVarianceAndCost` runs the same position with the test cost table and asserts that the plan beats the immediate hedge on both variance and cost and leaves under 5 percent of the unhedged variance; it printed 10,352 USD residual at 62,418 USD cost against 647,435 USD at 111,862 USD for the immediate full hedge in futures (2026-10-31).

Staging the same plan over time: `HedgerTest.stagedHedgingCostsLessThanImmediateExecution` asserts that staging costs less than immediate execution and leaves a residual standard deviation under 7,500 USD; in its printed run a 300 million 10-year position hedged in tranches cost 37,964 USD in the model against 52,091 USD for executing the plan at once, ending with a residual standard deviation of 4,624 USD (2026-10-31).

The back-tested desk in `rates-strategy` has a hedge path of its own: it nets client flow first and then hedges what exceeds its budget. Since V1H-001 (ledger seq 23, 2026-11-01) it no longer sends that excess as one market order. It sends children sized to the touch quantity, each placed passively at our own touch and sent as a market order after 5 s if unfilled, with market orders at once while net DV01 is above 400,000. Against the single market order this cut the implementation shortfall by +446k, +300k and +71k USD per session at the low, central and high band points. A cost-aware trigger that weighs inventory risk against the cost of trading (V1H-002) was not supported and stays opt-in ([docs/research/DESK_FEATURES.md](docs/research/DESK_FEATURES.md); [DECISIONS.md DR-V1H-03](DECISIONS.md#dr-v1h-03-hedge-slicing-is-the-desk-default)).

### How it is tested

`RiskEngineTest.incrementalLadderEqualsFullRevaluation` replays 300 random sequences of fills and curve moves and requires the incrementally maintained ladder to equal a full revaluation within 1e-6 of gross notional. `BoxQpSolverTest` checks the solver against brute-force enumeration of active sets on 400 random problems. `ChaosTest` repeats the ladder check at the end of each of its 10,000 scenarios.

## 9. Smart order routing in fixed income

### Plain idea

A smart order router takes one order that is too large, or too urgent, to send to a single place and decides where, how and when to work it. For each venue it estimates what a child order would cost: the spread it would pay or earn, the price impact of its size, the fees, the chance that a resting order is filled before the market moves, and the delay to the venue. It then splits the order to minimise the total, sends passive orders first where waiting is cheap, escalates to aggressive orders when time runs out, and re-routes when the book moves away. Everything it decided, with the numbers it decided on, goes into the journal.

### Why bonds differ from equities

Equity routers in the United States work against a consolidated quote and a trade-through rule: the national best bid and offer is public, and a venue may not execute through a better price displayed elsewhere. Treasuries and swaps have neither. There is no consolidated tape of quotes, and each venue's book is visible only to its participants. The dealer-to-dealer market in on-the-run Treasuries trades on a few central limit order books with deep top-of-book size, clip sizes of millions, ticks of 1/128 or 1/64 of a point, and a workup protocol after each trade (Section 6). Off-the-run bonds and most swaps trade by request for quote. Futures are the deepest liquidity for duration risk and are often the hedge instrument of choice, at the cost of basis risk. A rates router therefore chooses among a handful of venues with very different microstructure, and its biggest decision is often which instrument and protocol to use, which in this project belongs to the hedger (Section 8); the router places a parent in one instrument across the venues that list it.

### CLOB versus RFQ for hedging

Hedging on a CLOB is anonymous and immediate. Crossing the spread pays half of it plus the impact of the size; resting earns half the spread but risks not being filled, or being filled just before the price moves through the order (adverse selection). Hedging by RFQ shows the size and the direction to several dealers before any trade, which is information leakage: the losing dealers know a hedge is coming and can trade ahead of it. For the small, frequent hedges a market maker generates, the CLOB with a router is the usual choice; RFQ is for sizes the books cannot absorb. The router in this project routes to CLOB and futures venues only.

### Formal statement

The router's model has three parts, each pinned in [API_SPEC.md Section 15](API_SPEC.md#section-15-smart-order-router-p4): a fill probability for a resting child, a cost per unit for a passive and an aggressive child, and an allocation of each slice across venues.

#### Queue position

A resting limit order joins the back of the queue at its price. It fills when the volume traded at that price exceeds the quantity ahead of it, minus what cancels ahead of it. The router uses the pure-death queue of Cont, Stoikov and Talreja (2010): unit market orders arrive at rate $\mu$, each unit ahead cancels at rate $\theta$, and the probability that an order of $s$ units behind $q$ units fills completely within $T$ is

$$
P = \Pr\Big(\sum_{k=1}^{q} E_k + \sum_{i=1}^{s} F_i \le T\Big),\qquad E_k \sim \mathrm{Exp}(\mu + \theta k),\; F_i \sim \mathrm{Exp}(\mu),
$$

evaluated by uniformisation (`QueueFillModel`, the algorithm pinned in the owner's earlier execution library). Deeper queues, slower flow and shorter horizons all lower $P$; cancellations ahead raise it.

#### Passive first, then escalate

The cost of a passive child of size $q$ on venue $v$, with half spread $h$, impact $I(q)$, latency cost $L$, fees $f_m$ (maker) and $f_t$ (taker), and an expected adverse move $A$ if it is not filled, is

$$
c_{\text{pass}}(q) = P\,(-h + I + f_m) + (1 - P)\,(A + h + I + f_t) + L,
\qquad
c_{\text{aggr}}(q) = h + I + f_t + L,
$$

per unit, in price points. The unfilled part is assumed to cross at the end of the passive time limit, which is why it carries $A + h + f_t$. Impact is charged to both, because the square-root law $I(q) = y\,\sigma_{\text{day}}\sqrt{q/\mathrm{ADV}}$ describes the price pressure of traded volume whatever the order type. The router allocates a slice across venues to minimise $\sum_v q_v\,\min(c_{\text{pass},v}, c_{\text{aggr},v})$ under minimum sizes, lot sizes and participation caps ([API_SPEC.md Section 15.2](API_SPEC.md#152-routing-objective-and-allocation)). Urgency $u \in [0, 1]$ shortens the schedule and the passive time limit in proportion to $1 - u$; at $u = 1$ everything crosses at once. When a passive child reaches its limit, the router cancels it and routes the remainder aggressively. When the touch moves away from a resting child by the threshold, the router cancels it and routes the remainder again, passively if time allows. Quantity is re-routed only after the venue confirms the cancel, so late fills can never make the children exceed the parent.

#### Anti-gaming

A router that sends the same size at the same interval is easy to detect: another participant who sees a 5 million clip every 15 seconds can trade ahead of the next one. The router draws each slice size and each interval from a uniform band around its nominal value (25 and 30 percent half-widths by default), from a seeded random source so that tests and back-tests are reproducible. `ChildRandomiserTest` checks that 2000 draws stay inside the band, show no lag-1 to lag-10 autocorrelation beyond $3/\sqrt{n}$, and pass a runs test about the median, and that a sawtooth sequence fails the same checks.

### Worked example

[COOKBOOK.md recipe 17](COOKBOOK.md#17-how-do-i-route-a-hedge-order-and-read-the-markout-report) routes a 20 million buy of a 10-year note across two simulated CLOBs at urgency 0.5. The first slice is 4 million. On venue 1 the touch is 98.500000 / 98.515625 (one tick of 1/64), 12 million rests on the bid, and the router's estimate of flow is 1 lot per second each side. For the 3 million it evaluated there:

- half spread $h = 0.0078125$;
- impact $I = 0.6 \times 0.5 \times \sqrt{3/46\,800} = 0.002402$ (ADV of 46,800 lots is twice the arrival rate over a 23,400-second day);
- latency cost $L = 1.0 \times (0.5/\sqrt{23\,400}) \times \sqrt{0.00025} = 0.000052$;
- aggressive cost $0.0078125 + 0.002402 + 0.0005 + 0.000052 = 0.010766$, as printed;
- fill probability within 15 seconds $P = 0.8161$, and passive cost $-0.001065$, so the child rests.

The decision table, the journaled RouteDecisionVenue rows and a re-evaluation from those rows agree to the last digit (`DecisionReplayTest`).

### What the back-test shows

Over 40 seeded synthetic days of 15 parents each, the router's mean cost was -0.1957 bps of price, against 2.6822 for sending each parent as one aggressive order to the primary venue and 2.5108 for an even split across three venues. The mean daily saving was 2.8780 bps (95 percent block-bootstrap interval 2.7210 to 2.9855) and 2.7065 bps (2.5309 to 2.8272) respectively (printed by `BacktestTest.routerBeatsBothBaselinesInTheShippedScenario` in rates-router, which asserts that the lower bound of each interval is above its margin; run 2026-10-31 on JDK 25.0.5, Windows 11). The market is synthetic and its assumptions are listed in [API_SPEC.md Section 15.6](API_SPEC.md#156-back-test): a random-walk price with no informed flow beyond the price moves themselves, Poisson market orders, queue cancellations at a constant rate, and permanent impact proportional to the size taken. The number says the router's logic works as designed in that world, not what it would save on a real desk.

### Measuring execution quality

Markout is the change in mid a fixed time after a fill, signed so that positive is good for the order's owner, which is us (the conventions table at the top). A router that rests passively earns spread but is filled more often just before an adverse move; its markouts show it. The post-trade analysis of P5 and P6 reads the journaled decisions next to the fills and their markouts, so a poor result can be traced to the cost estimate that caused it ([COOKBOOK.md recipe 17](COOKBOOK.md#17-how-do-i-route-a-hedge-order-and-read-the-markout-report) prints the markout report).

### Glossary

- **Smart order router**: the component that splits a parent order across venues and over time to minimise expected cost, and journals why.
- **Parent and child orders**: the parent is the quantity to be worked; children are the orders actually sent to venues. The sum of the children never exceeds the parent ([DECISIONS.md DR-P4-02](DECISIONS.md#dr-p4-02-re-route-only-quantity-the-venue-has-confirmed-cancelled)).
- **Queue position**: the quantity resting ahead of an order at its price.
- **Fill probability**: the probability that a passive child fills completely within its time limit, from the queue model.
- **Square-root impact law**: price impact proportional to daily volatility times the square root of size over average daily volume.
- **Urgency**: a number in $[0, 1]$ that shortens the schedule and the passive time limit; 1 crosses at once.
- **Escalation**: cancelling a passive child that has not filled within its limit and sending the remainder aggressively.
- **Participation cap**: the largest fraction of a venue's visible and expected liquidity one routing decision may take.
- **Anti-gaming**: randomising child sizes and timing so that others cannot infer and trade ahead of the next child.
- **Trade-through rule**: the US equity rule against executing at a worse price than one displayed elsewhere; it has no counterpart in Treasuries or swaps.

## 10. Back-testing honestly: pre-registration, holdouts, attribution

### Plain idea

A back-test is a replay of history through a strategy, and it is the easiest place in a trading system to fool yourself. Every extra parameter tried, every look at the evaluation data and every number reported at the one assumption that flatters it makes the result less believable. The back-tester in this repository is built to make those mistakes visible: an experiment is written down and hashed before it runs, the holdout days are opened once, every run is recorded in an append-only ledger whatever it shows, P&L is split into lines that must add up to the cent, and every result is reported at three points of the declared assumption band rather than at one.

### Formal statement

**Replay.** `BacktestRunner` (`rates-backtest`, [API_SPEC.md Section 16.1](API_SPEC.md#161-strategy-interface-and-runner)) reads RAJ1 journals of SBE messages with `JournalReader`, which verifies the hash chain, and drives a `ManualClock` to each frame's timestamp. Events the back-tester creates itself (RFQ outcomes, order fills, strategy timers) wait in a queue ordered by time and then by scheduling order, and run before any later frame. A run is therefore a function of the journals, the configuration, the seed and the strategy. The fingerprint of a run is the FNV-1a chain hash over every output message (Quote, QuoteResponse, NewOrder, ExecutionReport).

**Outcome models.** The public tape shows trades, never the quotes that lost, so the probability of winning an RFQ is an assumption (DR-008). The back-tester draws the best competing quote (the cover) from a declared competitor model and settles our quote with a logistic in spread-to-cover:

$$
P(\text{win}) = \frac{1}{1 + e^{-z}}, \qquad
z = b_0 + b_1 s + b_2 \ln q + b_3 T + b_4 F + b_5 n,
$$

where $s$ is our distance from mid minus the cover's, in basis points, $q$ the size in millions, $T$ the tenor, $F$ the fixing-window flag and $n$ the dealer count. The slope $b_1$ is taken from the band in `data/presets.yaml` (preset `usd_rfq_hit_rate`, low $-1.25$, central $-2.5$, high $-5.0$ per bp). Random draws come from a SplitMix64 stream keyed by the run seed and the RFQ id, so two strategies replayed over the same journal face the same competitors and the same uniform draw on every RFQ (common random numbers), and the comparison between them is paired.

**Pre-registration.** A pre-registration ([API_SPEC.md Section 16.8](API_SPEC.md#168-pre-registration-and-ledger)) states the hypothesis, the metric, the baseline and treatments, the evaluation period, the fitting period, the holdout dates, the success threshold, the assumption band, the author and the date, and pins the SHA-256 of the experiment config. `ExperimentCli` refuses to run when the file is missing or invalid, when a holdout date falls in the fitting or evaluation period, when the evaluation period overlaps the fitting period, when the config has changed since it was hashed, or when the holdout has already been opened.

**Ledger.** `data/ledger/ledger.jsonl` holds one canonical JSON object per run: the pre-registration, config and journal hashes, the code commit, the look number, the results and the verdict. Each line carries the SHA-256 of the line before it, so an edited or deleted past line breaks the chain at that line, and `Ledger.append` refuses to extend a broken chain. A refused run has read no data and is not recorded; a run that started is recorded whatever it shows.

**Attribution.** Valuation is linear in the quoted price, $V = q \cdot p \cdot v$, with $v$ the value per unit (annuity times $10^{-4}$ for swaps, $10^{-4}$ per 100 of face for bonds and futures). Each event updates two views:

$$
\underbrace{\text{cash} + Q\,m\,v}_{\text{position P\&L}}
= \underbrace{\textstyle\sum_{\text{client}} q(m - p)v}_{\text{spread capture}}
+ \underbrace{\textstyle\sum_{\text{mid moves}} Q\,\Delta m\,v}_{\text{inventory}}
+ \underbrace{\textstyle\sum_{\text{hedges}} q(m - p)v}_{\text{hedge cost}}
+ \text{carry} + \text{funding} + \text{fees},
$$

and the residual, the left side minus the right, is checked below one cent on every run. Carry and roll is a hook that returns zero until the P1 analytics are wired.

**Statistics.** The unit of analysis is one session. Confidence intervals use a moving-block bootstrap over days (blocks of consecutive sessions drawn with replacement; percentile interval with linear interpolation). Two parameter sets are compared with Welch's unequal-variance t-test; when more than one test is made, p-values are adjusted with Holm's step-down method. The Java (`Stats`) and Python (`ratesresearch.stats`) implementations draw the same bootstrap indices from the same generator and agree exactly on `data/fixtures/backtest/stats_crosscheck.json`.

### Glossary

- **Pre-registration**: a hashed, dated statement of an experiment written before it runs.
- **Holdout**: dates set aside at registration and opened once, after the evaluation record exists.
- **Look**: one run of an experiment on a set of dates; the ledger counts them.
- **Spread-to-cover**: our quote's distance from mid minus the best competitor's, in bp.
- **Common random numbers**: giving two compared strategies the same random draws so that noise cancels in the difference.
- **Attribution residual**: position-based P&L minus the sum of the attribution lines.
- **Markout**: the change in mid after a trade, from the dealer's side; negative means the client was right.
- **Band point**: one of the low, central and high values of a declared assumption at which every result is reported.
- **Moving-block bootstrap**: resampling blocks of consecutive sessions with replacement to get confidence intervals that respect serial dependence.
- **Holm adjustment**: a step-down correction of several p-values that controls the chance of any false rejection.
- **Look-ahead bias**: using information in a back-test before the time it would have been known.

### Worked example: the first pre-registered experiment, P5-001

The question: does quoting the half-spread that maximises expected edge under the hit-rate model earn more per session than a fixed half-spread of 0.30 bp on swaps and 0.15 bp on Treasuries, with the same hedging rule? The method, step by step, as it happened on 2026-10-31.

1. *Fit on the fitting period.* The fixed-spread strategy was run on the ten synthetic sessions of 2026-01-05 to 2026-01-16 at the central band point. From its fills, `ratesresearch.markouts` estimated the expected adverse move at one minute by client tier as 0.0241, 0.0667 and 0.1300 bp, and the hedge cost as 0.2459 bp of client DV01. The informed-share estimates by tier, 0.08, 0.23 and 0.41, sit within about 1.5 standard errors of the generator's truth, 0.05, 0.17 and 0.47 (computed in the P5 session on 2026-10-31; two of the sessions are committed as `data/fixtures/backtest/fitting/`, and `test_markouts_rank_tiers_and_estimate_informed_share` repeats the check on them).
2. *Register.* Those estimates went into `data/experiments/P5-001.config.yaml`; its SHA-256 went into `data/preregs/P5-001.yaml` with the evaluation period 2026-02-02 to 2026-02-13 and the holdout 2026-02-23 to 2026-02-27; the pre-registration was committed (a382bfa) before the run.
3. *Run once.* `ExperimentCli --prereg data/preregs/P5-001.yaml` generated the ten evaluation sessions, ran both strategies at the three band points and appended the record.

The result (ledger line 1, `data/ledger/ledger.jsonl`, 2026-10-31), mean P&L per session in USD:

| Band point | Fixed spread | Model spread | Difference | 95% interval of difference | Welch p | Holm p | Verdict |
|---|---|---|---|---|---|---|---|
| low | 1,167,115.41 | 2,350,531.16 | 1,183,415.75 | 695,877.77 to 1,583,985.75 | 0.0658 | 0.1973 | inconclusive |
| central | 768,724.15 | 1,832,065.84 | 1,063,341.69 | 244,018.49 to 1,611,698.01 | 0.0832 | 0.1973 | inconclusive |
| high | 402,722.22 | 617,976.25 | 215,254.03 | -602,863.06 to 1,424,573.17 | 0.7200 | 0.7200 | inconclusive |

The pre-registered verdict is **not supported**. The paired bootstrap intervals lie above zero at the low and central points, but the pre-registered test is Welch's, which ignores the pairing and is swamped by the inventory P&L of each session; no Holm-adjusted p-value is below 0.05. At the high point, where clients almost always take the best price, the model spread wins only 3.6 percent of the RFQs it quotes against 30.9 percent for the fixed spread, and the advantage disappears. The record stands as it is. A paired test would have been the stronger choice and is a candidate for a new pre-registration (P5-002), not a re-analysis of this one. `FirstExperimentTest` re-runs the experiment from its pre-registration in CI and checks that it reproduces the recorded results exactly; the holdout has not been opened.

The table carries its own caveats: the sessions are synthetic, the strategy knows the competitor model the world uses, and the band is declared. Those are written in the pre-registration's `limitations` field before the run, not after.

### Why the band, and not one number

The live quoting engine has the same blind spot by construction: it learns only from a win (with its cover, when disclosed) and a traded-away loss; expired, done-away and passed RFQs, and our own passes, do not update it (`QuotingEngine.onQuoteResponse`). A hit-rate slope fitted to the project's own synthetic labels recovers the slope that generated them (`usd_rfq_hit_rate`: $-2.57 \pm 0.04$ per bp against a declared $-2.5$, out-of-sample log-loss 0.6146 against 0.6900 for a constant, `ratesresearch.hitrate.main`, 2026-10-31). That is a consistency check of the pipeline, not a measurement of a market. Reporting at the low, central and high points shows how much of a result depends on the assumption: in P5-001 the treatment's advantage at the high point is a fifth of that at the low point. A result that holds only at one point of the band is a statement about the assumption.

### How the platform uses this

The back-tester reads the same SBE messages the event loop will (P2), so when the loop's replay mode is bound to it, strategies under test run the live code path. The quoting engine (P3) reads the same `usd_rfq_hit_rate` preset, band included. The daily report (`ReportCli`, [API_SPEC.md Section 16.9](API_SPEC.md#169-results-tables-and-daily-report)) runs unattended from a journal and a config and covers P&L by product and line, hit rate by tier, markouts at 1 s, 10 s, 1 min and 5 min, hedge slippage, limit utilisation and client analytics; `docs/templates/weekly_review.md` is the template for the weekly review that reads it.

## 11. Low-latency Java: single writer, off-heap, replay

### Plain idea

A trading system that is fast and correct does one thing at a time, in one place, and writes down what it did. The core of this project is a loop on one thread that reads events from a queue, updates the state it owns, emits outputs, and appends every input to a journal. There are no locks because no one else touches that state. There is no garbage collection pause in the loop because the loop allocates nothing after warm-up. And the system can be re-run from the journal to reproduce any decision exactly.

### The single-writer principle

Mutable state has exactly one writer thread. In this project a writer owns an instrument group: for example all USD SOFR swaps, or all on-the-run Treasuries. Inputs from venue gateways, timers and the control plane arrive on a ring buffer; the owning thread drains the buffer and applies events in order. Outputs leave the same way, on ring buffers consumed by gateway or journaling threads.

The consequence is that a given sequence of inputs produces one and only one sequence of outputs. Concurrency bugs that depend on timing cannot exist inside the loop because there is no concurrency inside it. The cost is that a group's throughput is bounded by one core, and the design accepts that: a rates desk's event rate per instrument group is low by equity standards, and correctness is worth far more than parallelism here.

### Ring buffers

Cross-thread handoff uses pre-allocated ring buffers (Agrona's `OneToOneRingBuffer` and `ManyToOneRingBuffer`). The producer claims a slot, writes bytes, and publishes by advancing a sequence; the consumer reads up to the published sequence. No objects cross the boundary, only bytes in a fixed buffer, and no lock is taken. The buffers are sized at start-up and never grow. When a buffer is full the producer observes back-pressure rather than allocating, and the system records the event so the capacity decision can be revisited with evidence.

### Off-heap flyweights

Messages are encoded with Simple Binary Encoding. The generated codecs (see `rates-messages`) are flyweights: an encoder or decoder object is created once and wrapped over a buffer at an offset; reading a field reads bytes at a fixed position. A `QuoteRequestDecoder` wrapped over a ring-buffer slot reads `rfqId` without copying, deserialising or allocating. The same layout is written to the journal, so a journal frame is the wire message, and replay is a matter of pointing the same decoder at the file.

### Allocation-free hot path, and how it is checked

The hot path is the set of packages that run inside the event loop. The rule is that after warm-up they allocate nothing. This is not a style preference: a young-generation collection takes the loop off the CPU for a time that is unrelated to the current event and shows up as a tail-latency spike.

The rule is checked, not asserted, in two ways. `LoopAllocationTest` (`rates-core`) reads `ThreadMXBean.getThreadAllocatedBytes` for the loop thread, by thread id from the test thread, around windows of messages. After one million warm-up messages it measures three consecutive windows of one million each: the last must allocate exactly zero bytes, the second and third at most 1,024 bytes each, and the first is reported but not bounded ([DECISIONS.md DR-INT-02](DECISIONS.md#dr-int-02-windowed-loop-allocation-check); the story of why it has windows is in [Section 13](#13-pitfalls-and-numerical-issues)). `QuotingAllocationTest` does the same for the per-RFQ quoting path with five windows of 20,000 RFQs (DR-P3-08). And `HotPathRulesTest` checks the compiled bytecode of every class in a package marked `@HotPath` with ArchUnit: no synchronized methods, no locks, no `java.util` collections, no autoboxing and no string building in message-path methods; a source scan rejects `synchronized` blocks, and each rule is shown to fail on a fixture package that breaks it ([DECISIONS.md DR-P2C-06](DECISIONS.md#dr-p2c-06-hot-path-rules-are-checked-with-archunit-over-bytecode-with-string-concatenation-compiled-inline)). The P0 building blocks follow the same discipline: the `TimerWheel` and the journal writer allocate at construction, not on `poll` or `append`.

### The journal and deterministic replay

Every input event is appended to the journal before it is processed. The journal is a memory-mapped, append-only file (`rates-journal`). Each frame carries a sequence number, a timestamp, the SBE template id and version, and a chain hash: FNV-1a 64 over the previous frame's hash and this frame's payload. The format is pinned in `API_SPEC.md` Section 1.

Replay reads the frames back in order and feeds them to the same handler the live loop used. Because the loop is single-writer, and because time is injected through the `Clock` interface (`ManualClock` in replay, `SystemClock` live) and latched once per duty cycle, the outputs are identical byte for byte. (Allocation-freedom is about latency, not determinism: a loop that allocated would still replay exactly, only with pauses.) The chain hash is verified as frames are read; a corrupted or truncated journal fails loudly with the sequence number of the break rather than replaying garbage. Since P2 the `Replayer` drives the real loop handlers; the comparison is described at the end of this section.

This is also the testing strategy. A bug report comes with a journal. The fix is verified by replaying the same journal and comparing outputs. Golden journals are kept for regression.

### Measuring latency

Latency is not measured with JMH, because a microbenchmark of one method does not answer the question the desk asks, which is how long it takes for a book update to become a quote on the wire under realistic load. The methodology, written out in `docs/BENCHMARKS.md`, is:

A load generator sends events at a fixed rate, independent of how fast the system responds. If the system stalls, the generator keeps its schedule and the late responses are measured against the time the event should have been sent. This is the coordinated-omission correction described by Gil Tene; without it, a system that pauses for 100 milliseconds once a second looks better than it is, because fewer samples are taken during the pause. Samples are recorded in an `HdrHistogram` and reported at p50, p99, p99.9, p99.99 and max, with the machine, kernel and JVM flags recorded alongside.

Two boundaries are defined. Core decode-to-encode is the loop's own work, from an input being available to the output being published on the outbound ring. Tick-to-wire adds the gateway on both ends. The in-process harness stamps handler entry (T2 in [docs/BENCHMARKS.md](docs/BENCHMARKS.md)) rather than the moment the input became readable (T1), so the first measurements report decode-to-encode as T3 minus T2, plus scheduled-to-encode, T3 minus the generator's scheduled send time T0, which includes queueing ([DECISIONS.md DR-P2C-08](DECISIONS.md#dr-p2c-08-the-harness-reports-decode-to-encode-t3-minus-t2-and-scheduled-to-encode-t3-minus-t0)). Tick-to-wire is not measured yet. Hosted CI runners produce informational numbers only, because their neighbours are unknown; gating thresholds apply only on a pinned, self-hosted machine ([DECISIONS.md DR-005](DECISIONS.md#dr-005-where-latency-is-measured)).

The first report is dated 2026-10-31, from a Windows laptop with nothing pinned, at 50,000 QuoteRequests a second for 300 seconds after a 60-second warm-up ([docs/BENCHMARKS.md](docs/BENCHMARKS.md), `docs/benchmarks/latency-20261031-073526.json`). With journaling off, core decode-to-encode was 0.2 us at p50 and 6.9 us at p99.9 over 15 million samples, with zero bytes allocated on the loop thread: inside the P2 targets of 20 us and 100 us. With journaling on, p50 was 0.5 us but p99.9 was 271 ms, and the cause was not the loop's own work; [Section 13](#13-pitfalls-and-numerical-issues) tells that story.

### The ring-buffer contract and the replay comparison (P2)

Plain idea. A gateway and the loop agree on three things and nothing more: what a record on the ring looks like, how big it may be, and what happens when the ring is full. With that agreement the venue session can be written without reading the loop's code. Replay then answers one question: given the same inputs, did the loop say exactly the same things?

Formal statement. A record on an inbound or outbound ring is an Agrona record whose type is the SBE template id of its payload, and whose payload is the SBE message header followed by the body, at most 1024 bytes ([API_SPEC.md Section 9.2](API_SPEC.md#92-framing)). The same bytes are appended to the journal. When the inbound ring is full the gateway's publish returns false: market data is dropped and counted, session messages are retried (Section 9.3). When an outbound ring is full the loop drops the message, counts it, and after the handler returns journals a Backpressure record and tells the quoting layer, which pulls its quotes (Section 9.5).

Determinism needs time and timers to be inputs too. The loop reads the clock once per duty cycle, and every handler and journal frame in that cycle sees the same value. When timers fire, the loop journals a clock tick, so that replay fires the same timers between the same two messages ([DECISIONS.md DR-P2C-02](DECISIONS.md#dr-p2c-02-time-is-latched-once-per-duty-cycle-and-timer-firings-are-journaled-as-clock-ticks)). Agents are not replayed, so anything that should change the loop's state reaches it as a message on a ring.

Replay builds a fresh loop from the same factory, feeds it the inbound journal through the `Replayer` and a `ManualClock`, and chains every outbound message it produces with the journal's FNV-1a chain hash. Each produced hash is compared with the recorded outbound journal's frame of the same sequence. Because the chain covers every payload byte in order, equal final hashes mean equal outbound streams, and the first unequal hash names the first message that differs.

Glossary. Duty cycle: one pass of the loop over its rings, timers and agents. Clock tick: a journaled Heartbeat with source id 0 recording that timers fired at the cycle's time. Backpressure event: the record that outbound messages were dropped because a gateway's ring was full. First mismatch: the outbound sequence at which a replay first differs from the recording.

Worked example. Recipe 12 in COOKBOOK.md runs 100 QuoteRequests through a loop that quotes 100 with a half-spread of 0.002 (mantissa 2,000,000), journaling into 4 KiB segments. Replaying with the same handler reproduces the outbound hash exactly. Replaying with a half-spread of 0.0025 reports the first mismatch at outbound sequence 1, the first Quote, because its bid and ask bytes differ. `LoopPropertiesTest` checks the same equality for generated runs with dropped, duplicated, delayed and reordered inputs, timers, backpressure and disconnects (`rates-core`, 2026-10-31).

### Glossary

- **Single-writer principle**: every piece of mutable state has exactly one thread that writes it, so no locks are needed and the order of updates is the order of inputs.
- **Event loop**: the single thread that drains input rings, runs handlers and timers, and publishes outputs for one instrument group.
- **Ring buffer**: a fixed-size circular byte buffer through which one or more producers hand messages to one consumer, without locks or allocation.
- **Backpressure**: what a producer observes when a ring is full; here a counted drop or a retry, never an allocation.
- **SBE**: Simple Binary Encoding, the FIX Trading Community's fixed-layout binary format; fields sit at known offsets.
- **Flyweight**: an object that gives a typed view of a region of a buffer without copying, re-pointed at each message.
- **Hot path**: the code that runs for every message inside the loop, where allocation and locks are forbidden.
- **Journal**: the append-only, memory-mapped record of every input (and, since P2, every output), with sequence numbers and a hash chain.
- **Chain hash**: FNV-1a 64 over the previous frame's hash and this frame's payload, so any change to an earlier frame changes every later hash.
- **Deterministic replay**: re-running a journal through the same handlers and getting the same outputs byte for byte.
- **Warm-up**: the period before measurement in which the JIT compiles and lazy initialisation completes.
- **Percentile (p99.9)**: the latency below which 99.9 percent of samples fall; a percentile needs enough samples to mean anything (p99.99 needs at least 100,000).
- **HdrHistogram**: a histogram with fixed relative precision over a wide range, used for every latency figure here.

## 12. Regulation: UK RTS 6 (FCA MAR 7A), PRA SS5/18, MiFIR transparency, Dodd-Frank SEF rules

### Plain idea

A firm that lets software decide when to send orders and quotes must be able to show that the software is controlled: tested before it runs, limited while it runs, stoppable at once, watched, and recorded well enough that anyone can reconstruct what it did. Separately, the trades a dealer does in bonds and swaps must be made public, with delays for trades large enough that immediate publication would hurt the dealer's ability to hedge. In the US, swaps that are standardised enough must trade on a regulated platform and clear, and a clearing member must check credit before the trade, not after. This section says what each rule asks for and what it means for this platform. The control-by-control mapping, with a link per row, is [docs/compliance/RTS6_INVENTORY.md](docs/compliance/RTS6_INVENTORY.md).

### UK RTS 6 and FCA MAR 7A

Formal statement. MiFID II Article 17 requires an investment firm engaged in algorithmic trading to have effective systems and risk controls. Commission Delegated Regulation (EU) 2017/589, known as RTS 6 and retained in UK law, sets them out in 29 articles and two annexes; the FCA applies them through MAR 7A of its Handbook. legislation.gov.uk now marks UK RTS 6 as revoked by the Financial Services and Markets Act 2023 from a date set by commencement regulations, as the FCA restates retained EU law in its own rules; until then it applies, and the controls are expected to carry over.

The articles fall into groups:

- **Governance and people (Articles 1 to 4).** Clear responsibility for each algorithm, a compliance function that understands them and can use the kill switch, enough competent staff, and responsibility kept for anything outsourced.
- **Testing and deployment (Articles 5 to 8).** A documented testing method, conformance testing with each venue, a testing environment separate from production, and limits on what a newly deployed algorithm may do.
- **After deployment (Articles 9 to 11).** An annual self-assessment against the Annex I criteria with a validation report approved by senior management; stress tests at twice the highest message and trade volume of the previous six months; control of material changes.
- **Means of resilience (Articles 12 to 18).** A kill function that cancels every unexecuted order and quote at once, for all or part of the business; automated surveillance for market manipulation; business continuity; pre-trade controls (price collars, maximum order value and volume, maximum message rate); real-time monitoring with alerts; post-trade controls including reconciliation of trading records with venues, brokers and clearing; security and access limits.
- **DEA and clearing members (Articles 19 to 27).** Obligations on firms giving clients direct electronic access, or acting as general clearing members.
- **Records (Article 28 and Annex II).** The content and format of order records: Table 2 for initial decisions and incoming client orders, Table 3 for outgoing and executed orders, with fields such as the buy-sell indicator, trading capacity, the person or algorithm responsible, prices, quantities, timestamps, the venue's order and transaction ids, the event type and the validity period.

What it means here. The kill switch is a message (`KillSwitch`), an endpoint (`POST /control/kill`) that journals who pressed it, and a CI rehearsal that checks every quote is pulled. Stress testing is a harness that replays a journal with its busiest second at twice its rate and records acceptance and latency, corrected for coordinated omission. Post-trade control is the position keeper plus a daily reconciliation against venue drop copies, the clearing house and our own records, in which every break is either explained by a journaled event or goes to operations. Order records are kept in the Annex II layout, one directory per trading date, for five years, and can be found by venue order id. Testing is CI on every change; change control is the pull request; the separate testing environment is the simulated one. DEA and clearing-member articles do not apply to a principal that offers neither.

MAR 7A adds a notification to the FCA before algorithmic trading starts, record keeping the FCA can call on, and market-making obligations where a strategy meets the definition. The platform is not a regulated firm and notifies no one; the inventory says so row by row.

### PRA SS5/18

PRA Supervisory Statement SS5/18 (June 2018) sets the PRA's expectations of banks and designated investment firms that trade algorithmically. It overlaps RTS 6 and goes further on governance: a complete algorithm inventory with a risk tier per algorithm, a named owner and approver, testing proportionate to risk, approval before deployment, risk controls owned by the risk function, and a framework for change. Here the inventory template is [docs/compliance/ALGORITHM_INVENTORY_TEMPLATE.md](docs/compliance/ALGORITHM_INVENTORY_TEMPLATE.md), and its id is written into every order record's execution-within-firm field, so each order traces to an inventory row.

### MiFIR transparency and deferrals

Formal statement. MiFIR (as onshored) requires trading venues and investment firms to publish trades in bonds and derivatives: pre-trade quotes in some cases, and post-trade price, volume and time as close to real time as possible (Articles 10 and 21), through an approved publication arrangement for OTC trades. Article 11 allows publication to be deferred for trades that are large in scale or in instruments without a liquid market, so that a dealer who has taken on a large position can hedge before the market learns of it. The UK regime for bonds and derivatives was rewritten by the FCA (policy statement PS24/14, November 2024), with deferral periods set by instrument class and trade size; the details belong in the FCA's current text, not in this document.

What it means here. EUR and GBP trades in this platform would be published by the venue when executed on a venue, and by us through an APA when executed OTC. The order record already carries the fields publication needs (identifier, price, notation, quantity, time to the nanosecond). Deferral needs the size thresholds per instrument class from the securities master, read from configuration rather than coded. Neither the APA message nor the deferral rule is built; both are listed in the inventory for integration.

Worked example. A dealer buys a 30-year gilt from a client in a size far above the large-in-scale threshold. Published at once, the trade would tell every other dealer that someone is now long and needs to sell; prices would move against the hedge. Under a deferral the publication waits (for the largest sizes in illiquid classes, weeks rather than minutes), and the dealer hedges in the meantime. The record of the trade is kept from the first minute; only the public print waits.

### Dodd-Frank: SEFs, CFTC Rule 1.73, Part 43

Formal statement. Title VII of the Dodd-Frank Act moved standardised US swaps onto regulated platforms and into clearing.

- **Part 37, swap execution facilities.** A swap that has been made available to trade must be executed on a SEF or a designated contract market, by order book or by a request for quote sent to at least three participants. The SEF must offer impartial access.
- **Clearing and void ab initio.** A swap subject to the clearing mandate is submitted to a derivatives clearing organisation promptly after execution. If the clearing house rejects it, a SEF trade intended to be cleared is void from the start.
- **Rule 1.73, pre-execution credit checks.** A futures commission merchant that clears for a client must have automated means to screen the client's orders against risk-based limits before they reach the SEF, so that a trade is not executed only to be rejected for clearing.
- **Part 43, real-time public reporting.** Swap transaction and pricing data are disseminated publicly as soon as technologically practicable, with time delays for block trades and caps on the notional disclosed. On a SEF, the SEF reports. Part 45 adds regulatory (non-public) reporting to a swap data repository.

What it means here. The RFQ message carries the number of dealers in competition, which the P2 simulator uses to enforce request-to-three. The clearing lifecycle has REJECTED as a terminal state; a rejection becomes a `PostTradeBust` with reason ClearingRejected, and the reconciliation explains the resulting break. The pre-execution credit check belongs with the pre-trade controls (P3); the clearing simulator's limit is the backstop that catches what the check misses. Part 43 dissemination is the SEF's job for on-SEF trades and is listed, not built.

### Glossary

- **Algorithmic trading**: trading in which software decides the timing, price or quantity of orders with little or no human intervention (MiFID II Article 4(1)(39)).
- **RTS 6**: Commission Delegated Regulation (EU) 2017/589 on organisational requirements for algorithmic trading, retained in UK law and applied by FCA MAR 7A.
- **Kill function**: the ability to cancel every unexecuted order and quote at once, for all or part of the business (RTS 6 Article 12).
- **Pre-trade controls**: checks every order must pass before it leaves: price collars, maximum order value and volume, message rate (RTS 6 Article 15).
- **Algorithm inventory**: the register of every algorithm with its owner, approver and risk tier that PRA SS5/18 expects.
- **APA**: approved publication arrangement, the service through which an investment firm publishes its OTC trades under MiFIR.
- **Deferral**: permission to delay post-trade publication of a large or illiquid trade.
- **Large in scale**: a trade size above which pre-trade waivers and post-trade deferrals apply.
- **Void ab initio**: treatment of a SEF trade intended for clearing that the clearing house rejects: it is void from the start.
- **Swap data repository**: the registered entity that receives swap reports and disseminates the public part under Part 43.

### Worked example: one USD swap from request to books

A client asks four dealers, through a SEF, for a price to receive fixed on USD 100mm of a 10-year SOFR swap.

1. Before our quote can execute, the client's clearing member has screened the order against the client's limit (Rule 1.73). Our own pre-trade controls check our quote against the price collar, the maximum size and our DV01 limits (RTS 6 Article 15).
2. We quote; the client trades with us at 4.25 percent. The trade is executed on the SEF by RFQ to at least three (Part 37) and the SEF disseminates it publicly, with a block delay if the size qualifies (Part 43).
3. The trade is submitted to the clearing house: SUBMITTED, then ACCEPTED by both clearing members, then CLEARED by novation. Had it been rejected, it would be void and we would journal a bust.
4. Our journal holds the QuoteRequest, our Quote and the QuoteResponse; the keeper books a pay-fixed trade (the client received fixed); the order records hold the Table 2 request and the Table 3 quote and fill with the algorithm's inventory id (RTS 6 Article 28).
5. At the cut, the trade is reconciled against the SEF's drop copy and the clearing house's report (Article 17); at end of day it is marked at the official close and its P&L reconciles to the back-tester's attribution to the cent.

### Sources

- UK RTS 6: Commission Delegated Regulation (EU) 2017/589, legislation.gov.uk/eur/2017/589 (article list and Annex II layout read on 2026-10-31).
- FCA Handbook, MAR 7A Algorithmic trading.
- PRA Supervisory Statement SS5/18, Algorithmic trading, June 2018.
- UK MiFIR (Regulation (EU) No 600/2014 as onshored), Articles 10, 11, 21 and 26; FCA PS24/14, Improving transparency for bond and derivatives markets, November 2024.
- CFTC regulations: 17 CFR 1.73 (clearing member risk management), Part 37 (swap execution facilities), Part 43 (real-time public reporting), Part 45 (swap data recordkeeping and reporting).
- RTS 25 (Commission Delegated Regulation (EU) 2017/574) on clock synchronisation.

Each control named above is in [docs/compliance/RTS6_INVENTORY.md](docs/compliance/RTS6_INVENTORY.md) with its status and a link to the test, file or runbook that evidences it.

## 13. Pitfalls and numerical issues

### Plain idea

Most losses from rates software are not caused by wrong models. They come from a date that is one day off, a sign flipped for one week in six months, a tolerance that is meaningless for a number near zero, a latency figure that leaves out the slow cases, or a back-test that saw tomorrow's data. Each of these looks right in a demo and fails on a particular day. This section collects the ones that matter for this platform, says how large the error is, and names the test that now guards each. Some of them happened to us while building; those are told with their dates.

### Formal statement

A pitfall here is a pair: a condition under which a plausible implementation gives a wrong number, and a test whose input contains that condition. For each one below we give the condition, the size of the error with a number you can check, and the guard. A pitfall without a guard is listed as open.

### Glossary

- **Cancellation**: loss of relative precision when two nearly equal floating-point numbers are subtracted; the absolute error stays, the result shrinks.
- **Unit in the last place (ulp)**: the gap between a floating-point number and the next representable one; for doubles about $2.2 \times 10^{-16}$ of the value.
- **Truncation error** (of a bump): the error of a finite-difference derivative from the terms of the Taylor series it ignores.
- **Sawtooth forwards**: the step or zig-zag shape of instantaneous forwards produced by linear interpolation on log discount factors or zero rates.
- **Coordinated omission**: the bias of a latency measurement that takes fewer samples while the system is stalled, because the load generator waits for responses.
- **Look-ahead bias**: using, in a back-test, information that was not available at the simulated time.
- **Holdout leakage**: any use of the holdout dates before the single look that the pre-registration allows.
- **Survivorship bias**: drawing conclusions from the records that survived a filter (trades that happened) while the filtered-out records (RFQs that did not trade, quotes that lost) are invisible.
- **Mantissa**: the integer in a fixed-point number; with exponent $-9$, the value times $10^9$.

### Day counts and calendars

A one-day error in a 10-year swap's schedule shifts a payment and its discount factor; a wrong day count scales every coupon. Act/360 against Act/365 Fixed is a factor of $365/360 = 1.0139$, so pricing a SOFR leg on the GBP basis understates every floating coupon by 1.4 percent, about 0.06 percent of notional per year at 4 percent rates.

The subtler traps are calendars. An overnight index is published on its own calendar, which is not the payment calendar: SOFR was not published on Good Friday 2021 (2 April) or 2023 (7 April), although SIFMA recommended only an early close and payments could settle. A swap that compounds over payment days instead of publication days has one day too many in its product in those years. Holidays are also added at short notice: the UK bank holiday for the State Funeral of Queen Elizabeth II on 19 September 2022 was announced on 10 September, so a replay of 9 September 2022 must use the calendar as it was known that day ([DECISIONS.md DR-P1-02](DECISIONS.md#dr-p1-02-versioned-calendars-separate-fixing-and-payment-calendars)).

Guards. `VersionedCalendar.asOf(date)` returns the calendar as known on a date. The standing rules are compared with QuantLib 1.44 on every weekday from 2000 to 2060. `CompoundedIndexHoldoutTest` checks each day of 2021, 2023 and 2024 on which the USD fixing and payment calendars differ (one each in 2021 and 2023, none in 2024). Dates are epoch-day integers, and `DatePropertiesTest` compares every field and `addMonths` with `java.time` for random dates (DR-P1-01). Gilts settle T+1 on the London calendar and Bunds T+2 on TARGET; each bond convention names its own calendar ([API_SPEC.md Section 5](API_SPEC.md#section-5-market-conventions)).

### Compounding in arrears: lookback, lockout and observation shift

The floating coupon of an OIS is a product of daily factors, and a daily rate that applies over a weekend carries three days of weight. With SOFR at 4.30 percent on a Friday, that day's factor is $1 + 0.043 \times 3/360 = 1.00035833$, not $1 + 0.043/360$.

The three conventions that give the payer time to know the amount change which rate is used and which weight it gets:

- With a lookback of $k$ days and no observation shift, the rate is observed $k$ fixing days earlier, but weighted by the number of days in the interest period. A Monday interest day can then be paired with a Thursday rate and a one-day weight while the Thursday rate itself covered one day and the Friday rate three. The weights and the rates belong to different calendars.
- With an observation shift, both the rate and its weight come from the observation period, so the weights add up to the observation period's length rather than the interest period's.
- With a lockout of $k$ days, the rate observed $k$ days before the period end is repeated for the last $k$ days.

Implementing a lookback as an observation shift, or the other way round, gives a coupon that is close but not equal, typically within a fraction of a basis point of the period's rate, which is enough to fail a reconciliation with a counterparty every period.

Floating point matters too. A one-year coupon multiplies about 250 factors. Projected days are not multiplied one at a time: a run of unpublished days telescopes to one ratio of discount factors, $\prod (1 + r_i \delta_i) = P(d_a)/P(d_b)$, which is exact on the curve and has one rounding instead of hundreds (Section 3).

Guards. `AnalyticsGoldenTest` prices USD swaps with lookback, lockout and observation shift against QuantLib 1.44 (present values agree to 1.6e-15 of notional). `CompoundedIndexHoldoutTest` compounds the published 2024 SOFR, EUR STR and SONIA with a one-year swap's day weights and reproduces every published compounded index value to the eighth decimal.

### Curve interpolation artefacts

Linear interpolation on log discount factors makes the instantaneous forward constant between pillars and makes it jump at each pillar. With pillars at 2, 5, 10 and 30 years the forward curve is a staircase; a swap starting just after a pillar sees a different forward from one starting just before it. Linear interpolation on zero rates is worse: the forward is a sawtooth that can dip sharply just before each pillar. Cubic splines on zero rates remove the steps but can overshoot, producing negative or oscillating forwards between widely spaced pillars, and they are non-local: changing the 10-year quote moves forwards in the 2-to-5-year segment.

We use linear on log discount factors where it is right (between central bank meeting dates the overnight rate really is a step function, Section 2), monotone convex where smooth forwards matter, and a tension spline as the middle ground ([DECISIONS.md DR-P1-03](DECISIONS.md#dr-p1-03-curves-on-log-discount-factors-sequential-or-global-bootstrap)). The cost of non-locality shows in the incremental rebuild: after changing the 10-year quote by 7 basis points, an incremental rebuild agrees with a full rebuild to 3.9e-16 in discount factor for the linear scheme but only to 7.5e-14 for monotone convex and 6.8e-15 for the tension spline, because those re-solve more than one pillar (`CurveBuilderTest.incrementalRebuildEqualsFullRebuild`, 2026-10-31).

A year-end turn treated as part of the interpolated curve smears a one-week jump over the whole segment around it; this project adds it as an explicit spread over the turn window instead.

### Bumped versus algorithmic DV01

A bumped DV01 reprices after moving each input by one basis point. It has two errors. A one-sided bump measures the slope of a chord, not the tangent; its error is about half the second derivative times the bump, which for a swap is the convexity term. A central bump cancels that term and leaves an error of order bump squared. It is also slow: thirty inputs, central bumps, means sixty curve builds per position.

Algorithmic differentiation gives the exact derivative of the code in one adjoint pass and maps it to quote sensitivities through the bootstrap Jacobian ([DECISIONS.md DR-P1-04](DECISIONS.md#dr-p1-04-key-rate-dv01-by-algorithmic-differentiation-through-the-bootstrap)). On the recipe 5 swap the two agree to two cents in 82 thousand: 82,367.26 by AD and 82,367.28 by central one-basis-point bumps. Against QuantLib's central bumps over 83 golden swaps, the relative difference is at most 1.4e-6, which is the size of the bump's own truncation error, not a disagreement in the model (`AnalyticsGoldenTest`, 2026-10-31).

A second, quieter trap: bumping zero rates at fixed tenors and bumping the market quotes give different ladders for the same book, both correct. The analytics ladder is by quote; the risk engine's is by eight zero-rate pillars (Section 8). Adding a ladder of one kind to a ladder of the other is a bug that no unit test of either side catches; `Dv01Ladder.plus` refuses mismatched tenors ([API_SPEC.md Section 8.3](API_SPEC.md#83-dv01ladder-consumed-by-the-p3-risk-engine)).

### Cancellation in par-swap present values, and the golden tolerance

A par swap's present value is the difference of two legs that are each large. For 100 million notional at 4 percent over 10 years, each leg is worth about $10^8 \times 0.04 \times 8.2 \approx 3.3 \times 10^7$ USD. A double carries about 16 significant decimal digits, so each leg's value has an absolute rounding error near $3.3 \times 10^7 \times 2.2 \times 10^{-16} \approx 7 \times 10^{-9}$ USD, and a sum over many cash flows accumulates a few times that. The difference, the swap's PV, is near zero, so its relative error can be anything.

This bit us. The golden file was first generated on Windows on 2026-10-31, and the regeneration test compared every number to $10^{-12}$ relative with an absolute floor of $10^{-12}$. The first Linux CI run, the same day, computed a par-swap PV of $9.3 \times 10^{-9}$ USD where Windows had $7.5 \times 10^{-9}$: both are zero to within rounding, and the absolute floor was far below that rounding. The numbers were right on both platforms; the tolerance was wrong for numbers near zero. DR-P1-06 was amended at integration to compare to $10^{-12}$ relative with an absolute floor of $10^{-6}$, one millionth of a currency unit (commit 3e33d21, `research/tests/test_goldens.py`). The rule we took from it: state a tolerance for every compared quantity as relative plus absolute, and choose the absolute part from the size of the terms that cancel, not from the size of the result.

### The gilt ex-dividend sign flip

From seven London business days before a coupon, a gilt trades ex-dividend: the buyer does not receive the coupon and the accrued interest is negative. In recipe 7 the 4 1/8% Treasury Gilt 2027 has accrued interest of $+1.927989$ when settling on 10 January 2025 and $-0.100883$ on 13 January (Section 4 does the arithmetic). A pricer that ignores the rule computes a dirty price about one full coupon, 2.06 per 100, too high for seven business days in every six months. Unit tests on random dates catch it only about one time in twenty (nine calendar days in each half-year), which is why the golden file includes four gilts that settle inside their ex-dividend period (`AnalyticsGoldenTest.bondsMatchQuantLib`).

A related trap is the Treasury's auction formula. Pricing 2024's 84 note and bond auctions with the street yield formula misses 44 published prices by up to 0.0066 per 100; the Appendix B formula, with simple interest over the first fractional period and six-decimal rounding of dirty price and accrued interest before subtracting, reproduces all of them ([DECISIONS.md DR-P1-07](DECISIONS.md#dr-p1-07-three-bond-yield-conventions-named), `PublishedExamplesTest`).

### CTD switches

A bond future's price and risk follow its cheapest-to-deliver bond, and the CTD can change when yields move. As a rule of thumb, when yields are below the contract's notional coupon (6 percent for CBOT contracts), the bond with the shortest duration in the basket tends to be cheapest; well above it, the longest. A hedge sized as CTD DV01 divided by conversion factor is right only while the CTD stays the CTD; after a switch the future's DV01 jumps to the new bond's DV01 over its own factor, and the hedge is wrong by the difference.

In recipe 8 the CTD's net basis is 0.0267 and the next cheapest bond's is 0.8880, so a switch would need a large move; a basket where two bonds have net bases within a few hundredths is one where the future's duration is unstable and the delivery option is worth more. Guard: the golden file computes the CTD of five contracts and the Java code picks the same bond in each (`AnalyticsGoldenTest`). Open: the hedger's cost model charges a basis cost for futures but does not model the switch itself.

### Floating point and int64 mantissa conversions

Prices and rates cross the wire as int64 mantissas with exponent $-9$ ([API_SPEC.md Section 0](API_SPEC.md#section-0-units-and-conventions)). Three things go wrong at that boundary.

Truncating instead of rounding. The double nearest 1.005 is slightly below it, and $1.005 \times 10^9$ evaluates to 1004999999.9999999; a cast to `long` gives 1004999999, one unit low. A rate of 1.005 percent stored by truncation is off by one in the ninth decimal, and two systems that convert differently disagree on equality checks. The conversion must round to nearest. `Decimal9.round` (`rates-api`) computes `Math.round(value * 1e9)`, and `Decimal9` refuses NaN and any value of 9.2 billion units or more. One detail we found while writing this section: `Math.round` breaks ties upward, so $-2.5$ units become $-2$, while the class comment and [API_SPEC.md Section 5](API_SPEC.md#section-5-market-conventions) say half away from zero; the two differ only for negative values exactly halfway between grid points, and the discrepancy is reported for a fix rather than papered over here. For quotes the rounding direction is itself a policy: bids round down and asks round up to the grid, so a quote is never better for the client than computed ([DECISIONS.md DR-P3-10](DECISIONS.md#dr-p3-10-subject-quotes-reference-stamps-and-quote-rounding)).

Overflow. The int64 range is about $\pm 9.22 \times 10^{18}$, so with nine decimals the largest representable value is about 9.2 billion units: enough for any price per 100 or any rate in percent. A product is not safe. A price mantissa of order $10^{11}$ times a quantity of 100 million USD in cents, $10^{10}$, is $10^{21}$, past the range. Notional amounts must be computed in floating point, `BigDecimal` or with an exact multiply that throws on overflow, never as a plain product of two mantissas.

Precision. A double holds integers exactly up to $2^{53} \approx 9.0 \times 10^{15}$. Any price mantissa (at most 12 digits) converts to double exactly, but a quantity in cents above 90 trillion USD would not; no single trade is that large, but sums of them in a report can be. The position keeper keeps money in `BigDecimal` with 34 significant digits and rounds to the minor unit only when it reports ([DECISIONS.md DR-P6-02](DECISIONS.md#dr-p6-02-positions-recomputed-from-live-trades-in-transaction-time-order)).

A last floating-point trap is reproducibility across machines. `Math.exp` and `Math.log` may differ by one ulp between JVM builds or CPU architectures; the back-tester's recorded results depend on them, so determinism is claimed for the same JVM build on x86-64 only ([DECISIONS.md DR-P5-06](DECISIONS.md#dr-p5-06-floating-point-reproducibility-relies-on-hotspots-math-on-x86-64)). A run on ARM is a new look, not a reproduction.

### Mixing clocks

The platform has two clocks: `epochNanos()` for time stamps, UTC nanoseconds since 1970, and `nanoTime()` for intervals, monotonic with no defined origin ([API_SPEC.md Sections 0](API_SPEC.md#section-0-units-and-conventions) and 3). Subtracting one from the other gives a number with no meaning, often off by decades; subtracting two wall-clock readings to measure an interval is wrong whenever the clock is stepped by time synchronisation, and can be negative. The rule is simple and easy to break in a hurry: wall-clock values go in records, monotonic values go in latency measurements, and the two never meet in one subtraction. This pitfall is open in the sense of this section's formal statement: the `Clock` interface separates the two methods, but no test or ArchUnit rule yet stops code from mixing them or calling `System.nanoTime` directly.

Inside the event loop a third trap is reading the clock more than once per event. Two handlers that read the clock separately see different times, and a replay that feeds recorded times cannot reproduce both. The loop reads the clock once per duty cycle and every handler and journal frame in that cycle sees the same value; when timers fire, the loop journals a clock tick so replay fires the same timers between the same two messages ([DECISIONS.md DR-P2C-02](DECISIONS.md#dr-p2c-02-time-is-latched-once-per-duty-cycle-and-timer-firings-are-journaled-as-clock-ticks)). Regulation adds a requirement of its own: under RTS 25, the business clocks of trading venue members that trade algorithmically must be traceable to UTC within stated limits (1 millisecond, or 100 microseconds for high-frequency trading), so the wall clock must be synchronised and its divergence recorded.

### Coordinated omission in latency measurement

A load generator that waits for each response before sending the next request takes fewer samples while the system is stalled, so the stall appears once instead of once per request that should have been handled. [COOKBOOK.md recipe 13](COOKBOOK.md#13-how-do-i-run-the-latency-job-and-read-the-histogram) makes the size concrete: at 50,000 messages a second (one every 20 microseconds), 9,999 messages take 10 microseconds and one stalls for 10 milliseconds. The raw histogram reports p99 of 10.0 microseconds, as if nothing happened. The corrected one, recorded with `recordValueWithExpectedInterval`, adds the 499 messages that would have queued behind the stall (10 ms over 20 us, less one) and reports p99 of 7,921.7 microseconds, almost 800 times higher. The harness measures from the generator's scheduled send time for the same reason ([docs/BENCHMARKS.md](docs/BENCHMARKS.md)).

The first in-process measurements on 2026-10-31 showed what the correction hides when it is absent. With journaling on, the core decode-to-encode p99.9 was 271 milliseconds, against 6.9 microseconds with journaling off ([docs/BENCHMARKS.md](docs/BENCHMARKS.md), report `latency-20261031-072915.json`). Two earlier runs that morning were worse, 1.1 s and 0.79 s, while 512 MiB journal segments were created and forced to disk on the loop thread; moving that work to a background thread ([DECISIONS.md DR-P2C-03](DECISIONS.md#dr-p2c-03-segment-rolling-continues-the-chain-across-segments-the-single-file-classes-gain-package-private-seams-only)) brought it to 271 ms. Every one of those runs would be discarded by our own rule, because the generator itself fell behind its schedule; they are recorded as the first evidence, with the lateness in each report. Round 2 work on the journal write path continues on its own branch, and this document will cite its numbers once they are merged.

A related measurement trap is allocation. On 2026-10-31 `LoopAllocationTest` failed on the Linux CI runner with 296 bytes allocated on the loop thread over a million messages, and passed on Windows. A single before-and-after delta could not tell a one-off cost from a per-message leak. The investigation found that on a two-CPU runner the drainer falls behind, the loop takes its backpressure path for the first time after warm-up, and the first backpressure event loaded a class on the loop thread. The fix, in PR #2 ([DECISIONS.md DR-INT-02](DECISIONS.md#dr-int-02-windowed-loop-allocation-check)), loads that path at construction and measures three windows of one million messages: the last must be exactly zero and the second and third at most 1,024 bytes, so a leak of even one byte per message, a megabyte per window, still fails.

### Look-ahead and holdout leakage in back-tests

Look-ahead is the use of information before it existed. It enters through data more often than through code. SOFR for 15 January 2025 is published on the morning of 16 January, so a curve built on 15 January cannot use it; recipe 4 uses an estimate of 4.30 percent and says so (Section 2). A back-test that marks positions at the day's official close and quotes during the day at the same close has seen the end of the day at its start.

The back-tester is built so that look-ahead through ordering is hard: it drives a `ManualClock` to each journal frame's time stamp, and events it creates itself (RFQ outcomes, fills, timers) wait in a queue ordered by time and run before any later frame (Section 10, [API_SPEC.md Section 16.1](API_SPEC.md#161-strategy-interface-and-runner)). A strategy only sees what the journal has delivered by the clock's time, and `BacktestRunner` stops with "journal time goes backwards" if a frame is stamped earlier than the clock. That guard has no dedicated test yet; the holdout rules do (`LedgerTest.refusesHoldoutOverlap`, `LedgerTest.recordsRunAndOpensHoldoutOnce`, `LedgerTest.refusesUnregisteredRun`).

Holdout leakage is subtler, because it needs no code error at all. Every look at the evaluation data that leads to a change of parameters spends some of its value as evidence; looking at the holdout spends all of it. The defences are procedural and enforced by `ExperimentCli`: the pre-registration pins the SHA-256 of the experiment's configuration; a holdout date inside the fitting or evaluation period is refused; the holdout can be opened once and a second opening is refused; and every run that started is recorded in a hash-chained ledger whatever it shows ([DECISIONS.md DR-P5-03](DECISIONS.md#dr-p5-03-pre-registration-refusals-and-an-append-only-hash-chained-ledger)). [COOKBOOK.md recipe 19](COOKBOOK.md#19-how-do-i-pre-register-an-experiment-and-record-it-in-the-ledger) shows each refusal: a holdout date of 2026-01-09 inside the fitting period 2026-01-05 to 2026-01-16 is refused with exit code 2, and opening the DEMO-001 holdout a second time is refused. P5-001's own fitted parameters came from the fitting period only, and its holdout has not been opened.

### Survivorship of RFQs

Public data shows trades, not requests. The DTCC swap data repository and FINRA's Treasury data report executed transactions; an RFQ that the client passed on, that expired, or that a dealer lost never appears. Two biases follow. A hit-rate model fitted to a dealer's own records sees its wins and the fact of its losses, but not the winning price on a loss, so it cannot separate a quote that lost by a hair from one that lost by a mile ([DECISIONS.md DR-008](DECISIONS.md#dr-008-competition-model-for-rfq-win-probability)). An arrival model fitted to public trades counts only requests that ended in a trade somewhere, so it undercounts requests, by a share that public data cannot tell us.

This project's answers are explicit. Win probability is a declared assumption band, not a fit, and every result is reported at three points of it (Sections 7 and 10). RFQ arrivals are fitted from DTCC trades and scaled by a declared `rfq_share_of_market` ([DECISIONS.md DR-P5-04](DECISIONS.md#dr-p5-04-public-trade-arrivals-stand-in-for-rfq-arrivals-finra-treasury-data-shipped-as-a-fixture)). The back-tester records every RFQ, quoted or not, in `rfqs.csv` with a `quoted` and a `won` column ([API_SPEC.md Section 16.9](API_SPEC.md#169-results-tables-and-daily-report)), so a hit rate computed from it has the right denominator. The live quoting engine has the same blind spot by construction: it learns only from a win (with its cover, when disclosed) and a traded-away loss; expired, done-away and passed RFQs, and our own passes, do not update it (`QuotingEngine.onQuoteResponse`). A hit-rate slope fitted to the project's own synthetic labels recovers the slope that generated them, $-2.57 \pm 0.04$ per bp against a declared $-2.5$ (Section 10): a check of the pipeline, not of a market.

### Worked example: four pitfalls you can check by hand

1. Weekend weight. With SOFR at 4.30 percent on a Friday, the Friday factor is $1 + 0.043 \times 3/360 = 1.00035833$; with a one-day weight it would be $1.00011944$, and the coupon would miss two days of interest.
2. Gilt sign. 4 1/8% gilt, 184-day period, settling 9 days before the coupon inside the ex-dividend period: accrued interest $-2.0625 \times 9/184 = -0.100883$, as recipe 7 prints. Ignoring the period gives $+2.0625 \times 175/184 = +1.961617$, an error of 2.0625, one coupon.
3. Mantissa. $1.005 \times 10^9$ in double arithmetic is 1004999999.9999999; truncation stores 1004999999, rounding stores 1005000000. Try it in any language with IEEE doubles.
4. Coordinated omission. One 10 ms stall at a 20 us interval hides $10{,}000/20 - 1 = 499$ samples; with 10,000 recorded samples, 499 extra samples are 4.75 percent of the corrected total of 10,499, so every percentile above the 95.25th lands on the ramp of delayed samples between 20 us and 10 ms; the 105 highest samples reach down to about $10 - 105 \times 0.02 = 7.9$ ms, which is recipe 13's corrected p99.

## 14. How a desk would use this and how real desks differ

### Plain idea

A rates e-trading desk is a small team of traders, quants, developers and operations staff, surrounded by risk, compliance, credit, clearing and settlement functions it depends on. This platform is a model of the desk's own stack, run against simulated venues and public data. It is useful to read the day as a desk would live it, and then to be plain about everything a real desk has that this project does not.

### A day on the desk with this platform

- **Before the open.** Operations runs the start-of-day procedure ([docs/runbooks/start-of-day.md](docs/runbooks/start-of-day.md)): yesterday closed and reconciled, configuration validated, opening positions loaded at yesterday's official marks and checked against the clearing house, clocks synchronised. The trading process starts, replays its journal, reconciles order and quote status with every venue and only then enables quoting.
- **During the day.** RFQs arrive from D2C venues; the quoting engine prices them off the curve, skews for inventory and quotes within its limits. The hedger keeps residual DV01 inside its band with futures and benchmarks through the router. The trader watches the dashboard (live quotes, limit utilisation, stage latency) and changes parameters only through reviewed configuration. Alerts link to runbooks; the kill switch is one call away, and the deployment rehearsal in `.github/workflows/ops.yml` presses it and checks that every quote is pulled.
- **When something breaks.** A feed gap kills quoting for the group automatically; a venue disconnect does the same for the venue; a limit breach is refused before the order leaves. The runbook says what to check in the journal, who to tell, and what record to write.
- **After the cut.** The report generator books the day's journal, produces official P&L from official closes, archives the order records and writes the surveillance alerts. The daily reconciliation compares our trades and positions with the venues, the clearing house and our own records; every break is explained or goes to operations. Official P&L reconciles to the back-tester's attribution to the cent.

### How real desks differ

**Real flow.** A real desk sees real clients with relationships, history and information; this platform sees simulated arrivals whose rates and sizes are fitted to public data (DR-004) and a win probability that is a declared band, not a fit (DR-008). Real flow carries information that a simulator cannot: who is asking, what they did last week, what the rest of the street is doing. Markouts on a real desk are the first measure of whether a quote was a good idea; here they measure the simulator.

**Credit and clearing.** A real desk trades against counterparties with credit lines, collateral agreements and netting sets, and its clearing members set limits it must respect before execution. Uncleared trades carry counterparty risk, and their price includes credit, funding and capital valuation adjustments (CVA, FVA, KVA) that this project does not compute. Here, clearing is a state machine and a limit per account in a simulator.

**Booking.** On a real desk, trades flow into a trade capture system of record, then to middle office, finance and regulatory reporting, each with its own identifiers and reconciliation. Here the position keeper is both the books and the records, and the order record is a CSV per day. A real desk books to legal entities, handles allocations of block trades to client accounts, novations and compressions, and amends with dual control.

**Surveillance.** A real desk runs a surveillance platform across all its trading, with lexicon search of chat and voice, cross-product patterns and a team that reviews alerts. Here there are two example detectors over one journal, as hooks for that platform.

**Operations.** A real desk has settlement against custodians and central securities depositories with real fails, buy-ins and claims; regulatory transaction reporting to the FCA and swap data repositories; disaster recovery sites; change boards; and audit. Here settlement is a state machine with a cost of fail, reporting is listed but not built, and the warm standby is a design.

**People.** The roles in the runbooks (head of desk, risk manager, compliance officer, development lead, operations) are distinct people on a real desk, and the separation between them is itself a control: the person who writes an algorithm does not approve it, the person who trades does not set the limits. Here one person holds every role, and the procedures keep the separation on paper so that they read as they would on a desk.

### What carries over

The parts of this project that a desk could use as they are: the discipline of journaling every input and replaying it to reproduce a decision; positions recomputed from the trade history so that late fills and busts land where they belong; reconciliations that explain breaks from evidence rather than suppress them; official P&L that refuses to run without official marks; order records laid out to the regulation; a kill switch exercised in CI. What does not carry over is any number produced from the simulated flow, which is a statement about the simulator, not about a market.

### Formal statement: the end-of-day reconciliation

Of the desk's daily procedures, reconciliation is the one with a precise rule ([API_SPEC.md Section 17.8](API_SPEC.md#178-reconciliations-and-break-classes)). For each pair of sources (our trades against each venue's drop copy, our positions against the drop copy's positions, the books against the order records, our cleared trades against the clearing house) records are matched by key: venue, kind and execution id. Duplicates with the same key and content are suppressed and counted. Every unmatched or mismatched record is a break, and each break is classified from the journal (a late arrival, a bust after the cut, a pending bust) or left unexplained; only unexplained breaks go to operations. Positions are recomputed from the live trades in transaction-time order, so a late fill or a bust lands where it belongs in history, not at the moment it arrived ([DECISIONS.md DR-P6-02](DECISIONS.md#dr-p6-02-positions-recomputed-from-live-trades-in-transaction-time-order), DR-P6-04).

### Glossary

- **Drop copy**: the venue's or clearing house's independent copy of our executions, used for reconciliation.
- **Cut**: the time after which the day's books are closed; later messages belong to the next reconciliation.
- **Break**: a record present on one side of a reconciliation and missing or different on the other.
- **Bust**: the cancellation of a trade after execution, by the venue or because clearing rejected it.
- **Official mark**: the end-of-day price used for official P&L, from the marking policy ([DECISIONS.md DR-P6-03](DECISIONS.md#dr-p6-03-marking-policy-for-official-pl)).
- **Warm standby**: a second instance that follows the primary's journal and can take over quoting after a fenced hand-over ([DECISIONS.md DR-P6-06](DECISIONS.md#dr-p6-06-warm-standby-by-journal-replication-with-a-fenced-quoting-lease)).
- **Four-eyes control**: a change that needs a second person's approval, such as the kill-switch reset or a tier-table change.

### Worked example: a chaos day and its break report

[COOKBOOK.md recipe 21](COOKBOOK.md#21-how-do-i-run-the-chaos-test-and-read-the-break-report) (recorded 2026-10-31) builds a trading day of 336 journal frames and 105 fills, then injects the failures a real day brings: 5 duplicate messages, 2 late fills within the day and 3 after the cut, 3 busts of which 1 after the cut, and 2 duplicates in the drop copy. The drop-copy reconciliation reports 99 trades on our side and 101 on the venue's, 98 matched, 2 duplicates suppressed and 4 breaks. The counts check by hand: $99 - 98 = 1$ trade we have and the venue does not (we booked it, then it was busted after the cut), and $101 - 98 = 3$ venue records we do not have (the venue's count is after its 2 duplicates are suppressed), each a fill that reached us after the cut, so $1 + 3 = 4$ breaks. Every one of them is explained from the journal (one `BUSTED_AFTER_CUT`, three `LATE_ARRIVAL`) and the unexplained count is 0. After the late messages are applied, the next reconciliation has no breaks. This is the behaviour a desk would want from its own overnight process: the breaks are real, the explanation comes from evidence, and nothing is suppressed to make the numbers agree.

## 15. Knowledge check

Questions on every section, graded easy (a definition or one line of arithmetic), medium (two steps, or a reason) and hard (a derivation, or a judgement a desk reviewer would make). Each answer is short and points to the section that explains it and the test or recipe that shows it. Try the question before reading the answer; most can be checked by running the cited recipe with `mvn -B -ntp -pl rates-cookbook -am verify` or by hand.

### Easy

**Q1.** A 4 1/4% Treasury note pays coupons on 15 May and 15 November. What is its accrued interest per 100 when it settles on 16 January 2025?
*Answer.* 62 days of a 181-day period: $2.125 \times 62/181 = 0.727901$. *See* Section 4; [COOKBOOK.md recipe 6](COOKBOOK.md#6-how-do-i-price-a-treasury-from-yield-and-get-accrued-interest).

**Q2.** What is the sign of the DV01 of a long 200 million position in 10-year Treasuries in this project, and roughly how large is it with a PV01 of $-8.2 \times 10^{-4}$ per unit of face?
*Answer.* Negative, $-164{,}000$ USD per basis point: a long bond loses when rates rise. *See* the conventions table; Section 8; [COOKBOOK.md recipe 15](COOKBOOK.md#15-how-do-i-read-the-dv01-ladder-and-compute-a-cost-aware-hedge).

**Q3.** What does the price mantissa 4250000000 mean on the wire?
*Answer.* 4.25 at exponent $-9$; for a swap that is a rate of 4.25 percent. *See* the conventions table; [API_SPEC.md Section 0](API_SPEC.md#section-0-units-and-conventions).

**Q4.** How is 100 million USD of notional carried in an SBE message, and in a FIX message?
*Answer.* SBE: 10,000,000,000 (cents). FIX: 100000000 (currency units); the gateway scales by exactly $10^2$. *See* the conventions table; [DECISIONS.md DR-P2V-10](DECISIONS.md#dr-p2v-10-fix-quantities-in-currency-units-sbe-quantities-in-minor-units).

**Q5.** In a QuoteRequest, does `side = Buy` mean the dealer buys?
*Answer.* No. Side is from the client's point of view: the client buys and the dealer sells (offers). Recipe 14 prints our side, Sell, for the client's Buy. *See* Section 7; [COOKBOOK.md recipe 14](COOKBOOK.md#14-how-do-i-quote-an-rfq-through-the-engine-and-see-a-quoting-parameters-effect-on-skew).

**Q6.** What is a pillar of a curve?
*Answer.* A date at which the curve's log discount factor is a free parameter, one per bootstrap instrument. *See* Section 2; [COOKBOOK.md recipe 4](COOKBOOK.md#4-how-do-i-bootstrap-a-usd-sofr-discount-curve-from-ois-quotes).

**Q7.** In an RFQ, what is the cover, and who is told it?
*Answer.* The second-best quote on the client's side; on many platforms the winner is told it and losers are not. *See* Section 6; [COOKBOOK.md recipe 10](COOKBOOK.md#10-how-do-i-start-a-venue-simulator-and-connect-a-fix-44-session).

**Q8.** We offer 98.015 and win on 10 million face; the cover is 98.02. How much did we leave on the table?
*Answer.* $0.005 \times 10{,}000{,}000/100 = 500$ USD. *See* Section 6; [COOKBOOK.md recipe 10](COOKBOOK.md#10-how-do-i-start-a-venue-simulator-and-connect-a-fix-44-session).

**Q9.** Why is a gilt's accrued interest negative when it settles on 13 January 2025 for a 22 January coupon?
*Answer.* The gilt went ex-dividend on 13 January, seven London business days before the coupon; the seller keeps the coupon and owes the buyer the 9 days left: $-2.0625 \times 9/184 = -0.100883$. *See* Sections 4 and 13; [COOKBOOK.md recipe 7](COOKBOOK.md#7-how-do-i-compute-a-gilts-accrued-interest-inside-the-ex-dividend-period).

**Q10.** In recipe 8, which bond is cheapest to deliver into the March 2025 10-year note future, and by what criterion?
*Answer.* The 4 1/8% note of 30 November 2031: the highest implied repo (4.1674 percent) and the smallest net basis (0.0267). *See* Section 5; [COOKBOOK.md recipe 8](COOKBOOK.md#8-how-do-i-find-the-ctd-and-net-basis-for-a-treasury-futures-contract).

**Q11.** What is a workup on a Treasury CLOB?
*Answer.* After a trade at a price, a short window in which more size can trade at that price, first only for the trade's participants. *See* Section 6; `CertSuiteTest` running `cert/clob-workup.json`.

**Q12.** What does a pre-registration pin, and what does `ExperimentCli` do without one?
*Answer.* The hypothesis, metric, periods, holdout, threshold, band and the SHA-256 of the experiment config. Without a valid one it refuses to run, exit code 2. *See* Section 10; [COOKBOOK.md recipe 19](COOKBOOK.md#19-how-do-i-pre-register-an-experiment-and-record-it-in-the-ledger).

**Q13.** What must a kill function do under RTS 6 Article 12, and what happens in recipe 16 when it is pressed?
*Answer.* Cancel every unexecuted order and quote at once. In recipe 16 the listener pulls quotes and cancels orders, the state becomes KILLED, and new orders are refused until a second person approves the reset. *See* Section 12; [COOKBOOK.md recipe 16](COOKBOOK.md#16-how-do-i-set-a-risk-limit-and-exercise-the-kill-switch); [DECISIONS.md DR-P3-07](DECISIONS.md#dr-p3-07-kill-switch-with-a-four-eyes-reset-a-kill-during-a-pending-reset-is-a-full-kill).

**Q14.** Why does the project not use JMH for its headline latency numbers?
*Answer.* JMH measures one method in isolation; the question is how long a book update takes to become a quote under realistic load, measured end to end with a fixed-rate generator. *See* Section 11; [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

**Q15.** What is the annuity of a 10-year swap with annual payments on a flat 4 percent annually compounded curve, ignoring day counts?
*Answer.* $(1 - 1.04^{-10})/0.04 = 8.1109$. *See* Section 1.

### Medium

**Q16.** Section 1 estimates the DV01 of a 100 million 10-year par swap at about 81,100 USD; recipe 5 prints 82,367.26. Where does most of the difference come from?
*Answer.* Day count: Act/360 accrual fractions are about $365/360$ of a year, which lifts the annuity by about 1.4 percent; recipe 5's annuity is 8.232552. *See* Sections 1 and 3; [COOKBOOK.md recipe 5](COOKBOOK.md#5-how-do-i-price-a-swap-and-get-its-par-rate-and-dv01).

**Q17.** Why does linear interpolation on log discount factors make overnight forwards a step function, and when is that what you want?
*Answer.* The instantaneous forward is minus the slope of the log discount factor, constant on each linear segment. Between central bank meetings the overnight rate really is constant, so pillars at meeting dates give the right shape. *See* Sections 2 and 13; [COOKBOOK.md recipe 4](COOKBOOK.md#4-how-do-i-bootstrap-a-usd-sofr-discount-curve-from-ois-quotes).

**Q18.** What is the difference between a lookback without observation shift and one with it?
*Answer.* Both observe the rate $k$ days earlier. Without the shift, each rate is weighted by the interest period's day count; with it, by the observation period's, so rates and weights come from the same calendar. *See* Sections 3 and 13; `AnalyticsGoldenTest` (USD swaps with lookback, lockout and shift).

**Q19.** In recipe 5 the AD DV01 is 82,367.26 and the bumped one 82,367.28. Which is right?
*Answer.* The AD figure is the exact derivative of the pricing code; the central bump differs by its truncation error, of order bump squared. Against QuantLib's bumps the relative gap is at most 1.4e-6 over 83 swaps. *See* Section 13; `AnalyticsGoldenTest`; [DECISIONS.md DR-P1-04](DECISIONS.md#dr-p1-04-key-rate-dv01-by-algorithmic-differentiation-through-the-bootstrap).

**Q20.** Why can a regeneration test not compare a par swap's PV with a purely relative tolerance?
*Answer.* The PV is a difference of two legs of about $3.3 \times 10^7$ USD each; their rounding errors near $10^{-8}$ USD survive while the result is near zero, so relative error is unbounded. DR-P1-06 was amended on 2026-10-31 to add an absolute floor of $10^{-6}$ after Linux and Windows differed at $10^{-9}$. *See* Section 13; `research/tests/test_goldens.py`.

**Q21.** With $z_0 = 0.05$ and slope $b_1 = -1.6$ per bp, what is the win probability of a quote 0.5 bp from fair?
*Answer.* $1/(1 + e^{-(0.05 - 0.8)}) = 1/(1 + e^{0.75}) = 0.32$. *See* Section 7; `quoting-hit-rate-default.yaml`.

**Q22.** Our book is long 150 million of the 10-year. In which direction does the skew move our bid and offer, and what are the distances in recipe 14?
*Answer.* Both down by the 2 bp bound: the offer from 1.886 bp above fair to 0.114 bp through fair, the bid from 1.886 to 3.886 bp below. A long book wants to sell. *See* Section 7; [COOKBOOK.md recipe 14](COOKBOOK.md#14-how-do-i-quote-an-rfq-through-the-engine-and-see-a-quoting-parameters-effect-on-skew).

**Q23.** Hedging recipe 15's curve book with 2,162 ten-year futures makes net DV01 zero. Why is the one-day standard deviation still 647,435 USD?
*Answer.* The book's risk is spread over 2Y, 10Y and 30Y with opposite signs; one 10-year hedge cancels the sum, not the curve exposure, and 2s and 30s do not move one-for-one with 10s. *See* Section 8; [COOKBOOK.md recipe 15](COOKBOOK.md#15-how-do-i-read-the-dv01-ladder-and-compute-a-cost-aware-hedge).

**Q24.** Why does splitting a hedge into two halves halve the quadratic impact cost, and what is the price?
*Answer.* $2 b (x/2)^2 = b x^2/2$ against $b x^2$. The price is carrying residual risk while the second half waits. `HedgerTest.stagedHedgingCostsLessThanImmediateExecution`: 37,964 USD against 52,091. *See* Section 8.

**Q25.** Why does the router hedge on CLOBs and futures rather than by RFQ?
*Answer.* An RFQ shows size and direction to several dealers before any trade, and the losers can trade ahead of the hedge; small, frequent hedges go to anonymous books. *See* Section 9; [DECISIONS.md DR-P4-06](DECISIONS.md#dr-p4-06-the-router-routes-one-instrument-across-venues-the-hedger-chooses-the-instrument).

**Q26.** In the router's passive cost, why is the unfilled part charged $A + h + f_t$?
*Answer.* It is assumed to cross at the end of the passive time limit after an expected adverse move $A$, paying the half spread and the taker fee. *See* Section 9; [COOKBOOK.md recipe 17](COOKBOOK.md#17-how-do-i-route-a-hedge-order-and-read-the-markout-report).

**Q27.** Why does a cover teach the quoting engine more than a loss does?
*Answer.* A win with a cover tells us exactly how much wider we could have quoted and still won; a loss says only that someone was better, by an unknown amount. *See* Section 7; [DECISIONS.md DR-008](DECISIONS.md#dr-008-competition-model-for-rfq-win-probability).

**Q28.** What do common random numbers buy in a back-test comparison?
*Answer.* Each RFQ settles from its own random stream keyed by run seed and RFQ id, so two strategies face the same competitors and uniform draws; the noise cancels in the paired difference. *See* Section 10; [DECISIONS.md DR-P5-02](DECISIONS.md#dr-p5-02-common-random-numbers-keyed-by-rfq-id).

**Q29.** In P5-001 the paired bootstrap intervals of the difference lie above zero at two band points. Why is the verdict still "not supported"?
*Answer.* The pre-registered test was Welch's, which ignores the pairing; no Holm-adjusted p-value was below 0.05. Changing the test after seeing the data would be a new pre-registration, not a re-analysis. *See* Section 10; `FirstExperimentTest`; `data/ledger/ledger.jsonl` line 1.

**Q30.** In recipe 13 the raw histogram's p99 is 10.0 us and the corrected one 7,921.7 us. Which is honest and why?
*Answer.* The corrected one. The 10 ms stall would have delayed 499 messages at a 20 us interval; the raw histogram records it once. *See* Section 13; [COOKBOOK.md recipe 13](COOKBOOK.md#13-how-do-i-run-the-latency-job-and-read-the-histogram).

**Q31.** Why must `epochNanos()` never be subtracted from `nanoTime()`?
*Answer.* `nanoTime()` has no defined origin; the difference is meaningless. Wall-clock values are for records, monotonic ones for intervals. *See* Section 13; [API_SPEC.md Sections 0](API_SPEC.md#section-0-units-and-conventions) and 3.

**Q32.** A client sends a SEF RFQ for a made-available-to-trade swap below block size to two dealers. What does the simulator do?
*Answer.* It refuses the request before any dealer sees it: a Required Transaction by RFQ must go to at least three participants (17 CFR 37.9). *See* Section 6; `SefSimulator`.

### Hard

**Q33.** Show why the key-rate DV01s from algorithmic differentiation sum exactly to the parallel DV01, and how the bootstrap Jacobian enters.
*Answer.* The pricer gives $g = \partial PV/\partial y$. Quotes $Q$ and log discount factors $y$ are tied by $q(y) = Q$, so $\partial PV/\partial Q = J^{-\top} g$, found by solving $J^\top x = g$. A parallel move of every quote by one basis point is the sum of the single-quote moves to first order, and the code defines the parallel DV01 as that sum. Recipe 5's ladder (82,349.03 on 10Y plus small entries) sums to 82,367.26. *See* Section 3; [DECISIONS.md DR-P1-04](DECISIONS.md#dr-p1-04-key-rate-dv01-by-algorithmic-differentiation-through-the-bootstrap); [COOKBOOK.md recipe 5](COOKBOOK.md#5-how-do-i-price-a-swap-and-get-its-par-rate-and-dv01).

**Q34.** Why is a bond future's DV01 the CTD's DV01 divided by its conversion factor, and what happens to a futures hedge at a CTD switch?
*Answer.* At delivery the future converges to the CTD's price over its factor, so the future moves like $1/CF$ units of the CTD. After a switch it moves like a different bond over a different factor, so the hedge ratio jumps. In recipe 8 the next cheapest net basis is 0.8880 against 0.0267, so a switch is far away. *See* Sections 5 and 13; [COOKBOOK.md recipe 8](COOKBOOK.md#8-how-do-i-find-the-ctd-and-net-basis-for-a-treasury-futures-contract).

**Q35.** Derive the optimal quote distance $s^*$ for expected profit $E(s) = P(s)(s - c)$ with a logistic $P$.
*Answer.* $E'(s) = P'(s)(s - c) + P(s) = 0$ and, for the logistic, $P' = b_1 P (1 - P)$. So $b_1 (1 - P)(s - c) + 1 = 0$, $s^* = c + 1/(-b_1 (1 - P(s^*)))$. The engine solves it by golden-section search, checked to 1e-6 by `PackageAndClobTest.presetParsingAndSpreadOptimum`. *See* Section 7.

**Q36.** Why does the winner's curse grow like $\sqrt{2 \ln n}$, and what charge does the default preset give with four dealers?
*Answer.* The winner is the dealer whose independent error most favours the client; the expected maximum of $n$ standard normal errors grows like $\sqrt{2 \ln n}$. With coefficient 0.1 bp, $0.1 \sqrt{2 \ln 4} = 0.167$ bp. *See* Section 7.

**Q37.** A rate of 1.005 percent is converted to a mantissa by `(long)(x * 1e9)`. What is stored, and what rule does the analytics boundary use instead?
*Answer.* 1004999999, because $1.005 \times 10^9$ evaluates to 1004999999.9999999. `Decimal9.round` rounds to nearest with `Math.round` (ties upward; the documented rule is half away from zero, which differs only for negative ties), and quotes use `Decimal9.floor` for bids and `Decimal9.ceil` for asks ([DECISIONS.md DR-P3-10](DECISIONS.md#dr-p3-10-subject-quotes-reference-stamps-and-quote-rounding)). *See* Section 13.

**Q38.** Why does `LoopAllocationTest` measure three windows instead of one delta, and what failed on Linux on 2026-10-31?
*Answer.* A single delta cannot tell a one-off cost from a per-message leak. On the two-CPU Linux runner the loop took its backpressure path for the first time after warm-up and loaded a class on the loop thread (296 bytes measured). The fix (PR #2, DR-INT-02) loads that path at construction and bounds the windows; one byte per message would still be a megabyte per window. *See* Sections 11 and 13.

**Q39.** How does a replay name the first outbound message that differs from the recording?
*Answer.* Each produced message is chained with FNV-1a over the previous hash and its payload and compared with the recorded frame of the same sequence; the chain covers every byte in order, so the first unequal hash is the first differing message. Recipe 12's wider-spread replay reports `firstMismatch=1`. *See* Section 11; [COOKBOOK.md recipe 12](COOKBOOK.md#12-how-do-i-run-the-event-loop-against-a-journal-and-check-determinism); [DECISIONS.md DR-P2C-01](DECISIONS.md#dr-p2c-01-two-journals-per-loop-and-replay-compares-the-outbound-stream-by-chain-hash).

**Q40.** Why does the loop latch the clock once per duty cycle and journal a clock tick when timers fire?
*Answer.* So every handler in a cycle sees the same time, and replay fires the same timers between the same two messages; otherwise timer order would depend on wall-clock timing and replay would diverge. `LoopPropertiesTest` checks replay equality with delayed and reordered inputs and timers. *See* Sections 11 and 13; [DECISIONS.md DR-P2C-02](DECISIONS.md#dr-p2c-02-time-is-latched-once-per-duty-cycle-and-timer-firings-are-journaled-as-clock-ticks).

**Q41.** Why is a hit-rate model fitted to a dealer's own records biased, and what does the back-tester record to keep the denominator right?
*Answer.* Losses come without the winning price, and RFQs that ended without a trade never reach public data, so the records that survive are not a random sample. The back-tester writes every RFQ, quoted or not, to `rfqs.csv` with `quoted` and `won` columns, and the win probability itself is a declared band. *See* Sections 10 and 13; [API_SPEC.md Section 16.9](API_SPEC.md#169-results-tables-and-daily-report); [DECISIONS.md DR-008](DECISIONS.md#dr-008-competition-model-for-rfq-win-probability).

**Q42.** In P5-001 the model spread's advantage at the high band point is about a fifth of that at the low point. What does that tell a reviewer?
*Answer.* That most of the result depends on the declared hit-rate slope: when clients almost always take the best price, the model spread wins 3.6 percent of RFQs against 30.9 percent and its advantage disappears. A result that holds at one band point is a statement about the assumption. *See* Section 10.

**Q43.** What is clearing certainty, which two rules create it, and how is it modelled?
*Answer.* Assurance before execution that a trade will be accepted for clearing. 17 CFR 1.73 requires the clearing FCM to screen orders against risk limits; 17 CFR 37.702(b) requires the SEF to support the screen. `FcmLimitHub` rejects an order breaching its FCM's limit (venue code 6) and an RFQ acceptance breaching either side's (code 7). *See* Sections 6 and 12.

**Q44.** Why do Treasury auction prices need the Appendix B formula rather than the street yield formula?
*Answer.* Appendix B discounts the first fractional period with simple interest and rounds the dirty price and accrued interest to six decimals before subtracting; the street formula misses 44 of the 84 note and bond prices of 2024 by up to 0.0066. *See* Sections 4 and 13; `PublishedExamplesTest`; [DECISIONS.md DR-P1-07](DECISIONS.md#dr-p1-07-three-bond-yield-conventions-named).

**Q45.** After a 7 bp change in the 10-year quote, an incremental rebuild matches a full rebuild to 3.9e-16 with linear interpolation but only 7.5e-14 with monotone convex. Why?
*Answer.* Linear interpolation is local: only the changed pillar is re-solved and the others are untouched. Monotone convex is not, so the global solve is warm-started and converges to the solver tolerance rather than reproducing the same arithmetic. *See* Sections 2 and 13; `CurveBuilderTest.incrementalRebuildEqualsFullRebuild`.

**Q46.** The back-tester's attribution splits P&L into spread capture, inventory, hedge cost, carry, funding and fees. Why check the residual below one cent on every run, and where is it shown?
*Answer.* Because a P&L split that does not add up hides a booking or valuation error in whichever line absorbs it. Recipe 18 prints "residual below a cent: true" at each band point, and recipe 20 reconciles attribution to position P&L in Python. *See* Section 10; [COOKBOOK.md recipes 18](COOKBOOK.md#18-how-do-i-run-the-back-tester-on-one-synthetic-day) and 20.

## 16. Further reading

Primary sources only: the documents that define a convention, the rule texts, the contract specifications, and the papers in which a model first appeared. Each line says why it is worth reading next to this repository. Where a web address is given, it is the publisher's own page; documents behind a membership wall (the ISDA definitions) are named without one. Secondary summaries, including blog posts about this project, are left out on purpose.

### Conventions and benchmark rates (Sections 1 to 3)

- ISDA, *2021 ISDA Interest Rate Derivatives Definitions*. The definitions every OIS confirmation incorporates: compounding in arrears, lookback, lockout and observation shift, payment-lag business days. Section 3's formula follows them, and `SwapConvention` names the choices they leave open.
- ISDA, *2006 ISDA Definitions* with Supplement 70 (IBOR fallbacks, 2020). Still the basis of most legacy trades, and the fallback spread rules that moved LIBOR swaps onto compounded overnight rates.
- Federal Reserve Bank of New York, SOFR reference rate and the SOFR Averages and Index pages, <https://www.newyorkfed.org/markets/reference-rates/sofr>. Publication time, calculation method and the published index that `CompoundedIndexHoldoutTest` reproduces.
- European Central Bank, euro short-term rate (EUR STR) methodology and the compounded EUR STR index, <https://www.ecb.europa.eu/stats/financial_markets_and_interest_rates/euro_short-term_rate/html/index.en.html>. The calculation and publication calendar of the EUR index.
- Bank of England, SONIA benchmark and the SONIA Compounded Index, <https://www.bankofengland.co.uk/markets/sonia-benchmark>. Why SONIA swaps pay with no lag: the rate for a day is published the next London business day at 09:00.
- QuantLib 1.44 reference documentation, <https://www.quantlib.org/>. The golden file is generated with it ([DECISIONS.md DR-P1-06](DECISIONS.md#dr-p1-06-quantlib-golden-generation-and-the-platform-rule)), so its `Schedule`, `OvernightIndexedSwap` and `PiecewiseLogLinearDiscount` are the exact reference our tests compare against.

### Curves and pricing (Sections 2 to 5)

- L. Andersen and V. Piterbarg, *Interest Rate Modeling*, three volumes, Atlantic Financial Press, 2010. The reference for multi-curve construction, Jacobian-based risk and the Hull-White model; the curve-construction and risk chapters of Volume I are the background to DR-P1-03 and DR-P1-04.
- P. Hagan and G. West, "Interpolation methods for curve construction", *Applied Mathematical Finance* 13(2), 2006. The monotone convex scheme implemented in `Interpolation`, and a catalogue of the artefacts other schemes produce (Section 13).
- J. Hull, *Options, Futures, and Other Derivatives*, Section 6.4 in the edition cited by DR-P1-05. The Hull-White convexity adjustment formula used for SOFR futures (DR-P1-05).
- C. Nelson and A. Siegel, "Parsimonious modeling of yield curves", *Journal of Business* 60(4), 1987, and L. Svensson, "Estimating and interpreting forward interest rates: Sweden 1992-1994", NBER Working Paper 4871, 1994. The fitted curve family used for relative value in Section 4.
- US Treasury, 31 CFR Part 356, Appendix B, formulas for auction price and yield, <https://www.ecfr.gov/current/title-31/subtitle-B/chapter-II/subchapter-A/part-356>. The exact rounding rules that make every 2024 auction reproduce (DR-P1-07).
- UK Debt Management Office, *Formulae for calculating gilt prices from yields*, and the DMO's gilt market notes on ex-dividend dates, <https://www.dmo.gov.uk/>. The source of the seven-business-day ex-dividend rule and the negative accrued interest of recipe 7.
- CME Group, contract specifications for 2-, 5-, 10-Year and Ultra Treasury futures and for one-month and three-month SOFR futures, and the CME paper *Calculating U.S. Treasury futures conversion factors*, <https://www.cmegroup.com/>. Deliverable baskets, the conversion factor formula of Section 5, and the SOFR futures final settlement rules.
- Eurex, contract specifications for the Euro-Schatz, Euro-Bobl, Euro-Bund and Euro-Buxl futures, <https://www.eurex.com/>. The 6 percent notional coupon and the conversion factor method for German deliverables.
- G. Burghardt and T. Belton, *The Treasury Bond Basis*, third edition, McGraw-Hill, 2005. Written by practitioners; the clearest account of the delivery option, CTD switches and implied repo.

### Market structure and trading (Sections 6 to 9)

- M. Fleming, B. Mizrach and G. Nguyen, "The microstructure of a U.S. Treasury ECN: The BrokerTec platform", *Journal of Financial Markets* 40, 2018. Measured facts about workup and depth that the CLOB simulator's rules come from.
- T. Hendershott and A. Madhavan, "Click or call? Auction versus search in the over-the-counter market", *Journal of Finance* 70(1), 2015. Evidence on when clients choose RFQ over voice, and the information leaked by asking.
- M. Avellaneda and S. Stoikov, "High-frequency trading in a limit order book", *Quantitative Finance* 8(3), 2008. The reservation price and inventory skew that Section 7 adapts to a DV01 ladder.
- O. Guéant, *The Financial Mathematics of Market Liquidity*, Chapman and Hall/CRC, 2016, and O. Guéant, C.-A. Lehalle and J. Fernandez-Tapia, "Dealing with the inventory risk", *Mathematics and Financial Economics* 7(4), 2013. The closed-form quotes of a dealer with inventory limits, and the bridge from Avellaneda-Stoikov to RFQ markets.
- R. Almgren and N. Chriss, "Optimal execution of portfolio transactions", *Journal of Risk* 3(2), 2001. The trade-off between impact cost and risk that the staged hedger (Section 8) and the router's urgency parameter (Section 9) both make.
- R. Cont, S. Stoikov and R. Talreja, "A stochastic model for order book dynamics", *Operations Research* 58(3), 2010. The queue model behind `QueueFillModel`.
- FIX Trading Community, FIX 4.4 specification, <https://www.fixtrading.org/standards/fix-4-4/>, and the Simple Binary Encoding standard, <https://www.fixtrading.org/standards/sbe/>. The wire formats the venue layer speaks.

### Back-testing and statistics (Section 10)

- D. Bailey, J. Borwein, M. López de Prado and Q. Zhu, "Pseudo-mathematics and financial charlatanism: the effects of backtest overfitting on out-of-sample performance", *Notices of the AMS* 61(5), 2014. Why the ledger counts looks and the holdout opens once.
- C. Harvey, Y. Liu and H. Zhu, "... and the cross-section of expected returns", *Review of Financial Studies* 29(1), 2016. Multiple testing in finance, and the case for adjusted p-values.
- S. Holm, "A simple sequentially rejective multiple test procedure", *Scandinavian Journal of Statistics* 6(2), 1979. The step-down adjustment used in P5-001.
- H. Künsch, "The jackknife and the bootstrap for general stationary observations", *Annals of Statistics* 17(3), 1989. The moving-block bootstrap behind every interval in Section 10.

### Low-latency engineering (Section 11)

- M. Thompson, D. Farley, M. Barker, P. Gee and A. Stewart, "Disruptor: high performance alternative to bounded queues for exchanging data between concurrent threads", LMAX technical paper, 2011, <https://lmax-exchange.github.io/disruptor/>. The single-writer principle and the ring buffer, argued from cache behaviour.
- G. Tene, "How NOT to measure latency" (talk, 2015 and earlier versions), and the HdrHistogram documentation, <http://hdrhistogram.org/>. Coordinated omission, and the expected-interval correction recipe 13 demonstrates.
- Real Logic, Simple Binary Encoding, <https://github.com/real-logic/simple-binary-encoding>, Agrona, <https://github.com/real-logic/agrona>, and Aeron, <https://github.com/real-logic/aeron>. The codec generator, the ring buffers and the messaging transport the event core is built on or designed to adopt.
- D. Goldberg, "What every computer scientist should know about floating-point arithmetic", *ACM Computing Surveys* 23(1), 1991. Cancellation and rounding, the background to the golden tolerance story in Section 13.

### Regulation (Section 12)

- CFTC, 17 CFR Part 37 (swap execution facilities), <https://www.ecfr.gov/current/title-17/chapter-I/part-37>. Required and Permitted Transactions, RFQ-to-3, and the SEF's role in pre-execution credit screening.
- CFTC, 17 CFR Part 43 (real-time public reporting), <https://www.ecfr.gov/current/title-17/chapter-I/part-43>. Block thresholds, dissemination delays and notional caps.
- CFTC, 17 CFR 1.73 (clearing futures commission merchant risk management), <https://www.ecfr.gov/current/title-17/section-1.73>. The pre-execution limit screen behind clearing certainty.
- FCA Handbook, MAR 7A Algorithmic trading, <https://www.handbook.fca.org.uk/handbook/MAR/7A/>. How UK RTS 6 is applied to firms the FCA supervises.
- Commission Delegated Regulation (EU) 2017/589 (UK RTS 6), <https://www.legislation.gov.uk/eur/2017/589>. The 29 articles and Annex II order record layout that `docs/compliance/RTS6_INVENTORY.md` maps.
- Prudential Regulation Authority, Supervisory Statement SS5/18, *Algorithmic trading*, June 2018. Algorithm inventory, risk tiers and the governance of change.
- Regulation (EU) No 600/2014 (MiFIR), <https://eur-lex.europa.eu/eli/reg/2014/600/oj>, read with the UK onshored version and FCA policy statement PS24/14 (November 2024). Post-trade transparency and the deferral regime for bonds and derivatives.

## 17. Glossary

One or two sentences per term, alphabetical, each pointing to the section that uses it most. Where the market uses a word in more than one way, the definition gives the sense used in this repository.

- **Accrued interest**: coupon interest earned since the last coupon date, paid by the buyer to the seller on top of the clean price; negative inside a gilt's ex-dividend period. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Act/360, Act/365 Fixed, Act/Act ICMA**: day counts that divide actual days by 360, by 365, or by the actual days in the coupon period times the frequency. ([Section 1](#1-rates-products-in-five-minutes))
- **Adverse selection**: the expected loss from trading with counterparties who know more than we do, measured by markouts. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Algorithm inventory**: the register of every trading algorithm with its owner, approver and risk tier, as PRA SS5/18 expects; its id is written into each order record. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Algorithmic differentiation (AD)**: computing exact derivatives by applying the chain rule backwards through the code's operations, from the price to the curve inputs. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Algorithmic trading**: trading in which software decides the timing, price or quantity of orders with little or no human intervention (MiFID II Article 4(1)(39)). ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Annuity**: $\sum_i \alpha_i P(\text{pay}_i)$, the value of receiving one unit of fixed rate on every fixed payment date; notional times annuity times $10^{-4}$ is a swap's DV01 to first order. ([Section 1](#1-rates-products-in-five-minutes))
- **Anti-gaming**: randomising child order sizes and timing within a band so that other participants cannot predict the next child. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **APA**: approved publication arrangement, through which an investment firm publishes its OTC trades under MiFIR. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Arrival mid**: the mid price when a hedge or parent order was decided, against which slippage is measured. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **Asset-swap spread**: the spread over the floating index received by a buyer who pays par for a bond and swaps its coupons to floating. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Attribution residual**: position-based P&L minus the sum of the attribution lines; checked below one cent on every back-test run. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Backpressure**: what a producer observes when a ring buffer is full; here a counted drop or a retry, journaled as a Backpressure record, never an allocation. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Band point**: one of the low, central and high values of a declared assumption; every back-test result is reported at all three. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Basis (futures)**: gross basis is the bond's clean price minus the futures price times its conversion factor; net basis subtracts carry to delivery. ([Section 5](#5-futures-ctd-basis-convexity))
- **Basis point (bp)**: one hundredth of a percent, 0.0001 in rate; in a futures price quoted as 100 minus rate, 0.01. ([Conventions](#conventions-used-throughout))
- **Bid and ask (offer)**: the prices at which the quoting party buys and sells; in an RFQ the dealer's bid is where the client can sell. ([Conventions](#conventions-used-throughout))
- **Block trade**: a swap at or above the appropriate minimum block size; exempt from the Required Transaction execution methods and reported publicly with a delay. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Bootstrap**: solving for a curve's pillar values so that every input instrument reprices to its quote, here to $10^{-12}$. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Box-constrained QP**: the hedger's quadratic programme with bounds on each instrument's lots, solved by accelerated projected gradient. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **Break**: a record present on one side of a reconciliation and missing or different on the other; explained from the journal or sent to operations. ([Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ))
- **Business-day convention**: the rule for moving a date that falls on a holiday; modified following moves forward unless that crosses a month end, then back. ([Section 1](#1-rates-products-in-five-minutes))
- **Bust**: the cancellation of a trade after execution, by the venue or because clearing rejected it; journaled as `PostTradeBust`. ([Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ))
- **Cancellation (numerical)**: loss of relative precision when nearly equal floating-point numbers are subtracted, as in a par swap's two legs. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Carry**: coupon income minus financing cost to a horizon, here in price terms; negative when repo costs more than the coupon earns. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Chain hash**: FNV-1a 64 over the previous frame's hash and this frame's payload; any change to an earlier frame changes every later hash. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Cheapest to deliver (CTD)**: the deliverable bond with the highest implied repo, equivalently the lowest net basis; a bond future's price and risk follow it. ([Section 5](#5-futures-ctd-basis-convexity))
- **Clean and dirty price**: a bond's quoted price without accrued interest, and the amount actually paid, with it. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Clearing certainty**: assurance before execution that a trade will be accepted for clearing, from the FCM's pre-trade limit screen. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Client side and dealer side**: the two views of one RFQ; message sides are the client's, our P&L, risk and markouts are the dealer's. ([Conventions](#conventions-used-throughout))
- **CLOB**: central limit order book; anonymous bids and offers matched by price-time priority. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Clock tick**: a journaled Heartbeat with source id 0 recording that timers fired at the duty cycle's time, so replay fires them at the same place. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Common random numbers**: giving two compared strategies the same random draws, here keyed by RFQ id, so that noise cancels in their difference. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Compounded in arrears**: a floating amount equal to the daily overnight rate compounded over the period, known only at its end. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Conversion factor**: a deliverable bond's price per unit face at the contract's notional coupon yield, rounded to four decimals for CBOT contracts. ([Section 5](#5-futures-ctd-basis-convexity))
- **Convexity (bond)**: the relative second derivative of price in yield, $P''/P$. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Convexity adjustment (futures)**: the excess of a daily-settled futures rate over the forward rate; 0.38 bp for the December 2025 three-month SOFR future here. ([Section 5](#5-futures-ctd-basis-convexity))
- **Coordinated omission**: the bias of a latency measurement that takes fewer samples while the system stalls; corrected by measuring from scheduled send times. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Covariance matrix ($\Sigma$)**: daily covariance of key-rate changes in bp squared; $r^\top \Sigma r$ is a book's one-day P&L variance. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **Cover price**: the second-best quote on the client's side of an RFQ, shown to the winner by some platforms. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **CTD switch**: a change of the cheapest-to-deliver bond as yields move, which changes the future's DV01 abruptly. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Cut**: the time after which the day's books are closed; later messages belong to the next reconciliation. ([Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ))
- **Day count**: the rule that turns two dates into an accrual fraction. ([Section 1](#1-rates-products-in-five-minutes))
- **Deferral**: permission under MiFIR to delay post-trade publication of a large or illiquid trade. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Delivery option**: the futures short's choice of which bond to deliver and when, which the net basis prices. ([Section 5](#5-futures-ctd-basis-convexity))
- **Deterministic replay**: re-running a journal through the same handlers and getting the same outputs byte for byte. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Discount factor**: $P(0, T)$, the value today of one unit paid at $T$. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Done away**: an RFQ outcome in which the client reports trading elsewhere. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Drop copy**: a venue's or clearing house's independent copy of our executions, used for reconciliation. ([Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ))
- **Duty cycle**: one pass of the event loop over its rings, timers and agents, during which the clock is read once. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **DV01**: the change in value for a one basis point rise in rates, $PV(+1\,\text{bp}) - PV$, in currency per basis point; positive when the position gains as rates rise (a payer swap, a short bond). ([Conventions](#conventions-used-throughout))
- **DV01 ladder**: a book's key-rate DV01s by pillar or curve input. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **End-of-month rule**: a schedule starting on a month's last business day keeps every date on the last business day; off for swaps here. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Escalation**: cancelling a passive child that has not filled within its time limit and routing the remainder aggressively. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **EUR STR**: the euro short-term rate, the ECB's overnight unsecured rate, published at 08:00 CET for the previous business day. ([Section 1](#1-rates-products-in-five-minutes))
- **Event loop**: the single thread that drains input rings, runs handlers and timers and publishes outputs for one instrument group. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Ex-dividend period**: for gilts, the seven London business days before a coupon date, in which a buyer does not receive the coupon. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **FCM**: futures commission merchant, the clearing member that guarantees a customer's cleared trades and screens them against limits. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Fill probability**: the probability that a passive child fills completely within its time limit, from the queue model. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Firm quote**: a price the client can trade on until it expires, with no last look; a subject quote is indicative. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Fixing calendar**: the days on which an index is published; kept apart from the payment calendar. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Flyweight**: an object that gives a typed view of a region of a byte buffer without copying, re-pointed at each message. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Four-eyes control**: a change that needs a second person's approval, such as resetting the kill switch. ([Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ))
- **Gross DV01**: the sum of the absolute values of a ladder, a measure of how much offsetting risk a book holds. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **Half-spread**: the distance of each side of a two-way quote from the reservation price before skew and size terms. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **HdrHistogram**: a histogram with fixed relative precision over a wide range, used for every latency figure here. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Hedge ratio**: lots of a hedge instrument per unit of the position, here the ratio of DV01s. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **Holdout**: dates set aside at pre-registration and opened once, after the evaluation record exists. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Holdout leakage**: any use of the holdout dates before the single look the pre-registration allows. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Holm adjustment**: a step-down correction of several p-values that controls the probability of any false rejection. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Hot path**: the code that runs for every message inside the event loop, where allocation and locks are forbidden and checked. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Hull-White model**: a one-factor short-rate model with mean reversion $a$ and volatility $\sigma$, used here only for the futures convexity adjustment. ([Section 5](#5-futures-ctd-basis-convexity))
- **Iceberg order**: an order that displays only part of its size and refills from the hidden rest, losing queue priority at each refill. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **IMM date**: the third Wednesday of March, June, September and December. ([Section 1](#1-rates-products-in-five-minutes))
- **IMM swap**: a swap starting on an IMM date and ending on another. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Implied repo**: the financing rate at which buying a bond and delivering it into the future breaks even. ([Section 5](#5-futures-ctd-basis-convexity))
- **Improve limit**: the furthest a skewed quote may sit through fair value on the client's side, 0.25 bp by default. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Journal**: the append-only, memory-mapped record of every input and output of the event loop, with sequence numbers and a chain hash. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Key-rate DV01**: the DV01 for a one basis point move in a single curve input or pillar, others held fixed; the ladder's entries. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Kill function**: the ability to cancel every unexecuted order and quote at once, for all or part of the business (RTS 6 Article 12). ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Large in scale**: a trade size above which pre-trade waivers and post-trade deferrals apply. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Look**: one run of an experiment on a set of dates; the ledger counts them. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Look-ahead bias**: using, in a back-test, information that was not available at the simulated time. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Lookback, lockout, observation shift**: variants of an overnight leg's observation window that give the payer time to know the amount before paying. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Mantissa**: the integer of a fixed-point number; with exponent $-9$, the value times $10^9$. Prices and rates cross the wire this way. ([Conventions](#conventions-used-throughout))
- **Markout**: the change in mid a stated time after a fill, signed so that positive is good for us; a negative mean says the counterparty was informed. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **MAT**: made available to trade; a swap a SEF or DCM has certified as required to trade on a SEF or DCM. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Meeting-dated OIS**: an OIS whose period runs between two central bank policy effective dates. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Modified duration**: minus the relative price change per unit change in yield. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Monotone convex interpolation**: the Hagan-West scheme that keeps instantaneous forwards continuous and within the range of the neighbouring average forwards. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Moving-block bootstrap**: resampling blocks of consecutive sessions with replacement to get confidence intervals that respect serial dependence. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Nelson-Siegel-Svensson**: a six-parameter smooth zero curve used by central banks for government yields, and here for relative value. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Net DV01**: the sum of a ladder; zero net DV01 can leave large curve risk. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **OIS**: overnight index swap, whose floating leg pays the overnight rate compounded over each period. ([Section 1](#1-rates-products-in-five-minutes))
- **OIS discounting**: discounting collateralised cash flows on the curve of the overnight rate paid on collateral. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Par rate**: the fixed rate at which a swap has zero present value. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Parent and child orders**: the parent is the quantity to be worked; children are the orders sent to venues, never summing to more than the parent. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Participation cap**: the largest fraction of a venue's visible and expected liquidity one routing decision may take. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Payment lag**: business days between a period's end and its payment, on the payment calendar: 2 for USD SOFR, 1 for EUR STR, 0 for SONIA. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Percentile (p99.9)**: the latency below which 99.9 percent of samples fall; meaningful only with enough samples. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Permitted Transaction**: any swap on a SEF that is not a Required Transaction; any execution method may be used. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Pillar**: a date at which a curve's value is a free parameter, one per bootstrap instrument. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Pre-registration**: a hashed, dated statement of an experiment's hypothesis, data, test and success criterion, written before it runs. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Pre-trade controls**: checks every order must pass before it leaves: price collars, maximum order value and volume, message rate, risk limits. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Projection curve**: a curve used only to forecast a term index such as EURIBOR 6M, built with OIS discounting held fixed. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **PV01**: in this repository, the same quantity as DV01 (value change for a one basis point rise, signed). Some desks use the word for the annuity times one basis point; we call that the annuity. ([Conventions](#conventions-used-throughout))
- **Queue position**: the quantity resting ahead of an order at its price. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Repo, general collateral, special**: a sale and repurchase that finances a bond; the rate for any eligible bond; a bond whose repo rate is below general collateral. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Required Transaction**: a MAT swap, which must use the SEF's order book or RFQ to at least three participants unless it is a block. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Reservation price**: fair value adjusted for inventory, around which the dealer sets its spread. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Residual risk**: the ladder left after hedging, and its one-day variance. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **RFQ**: request for quote; a client asks named dealers for a price on a stated size and side and trades with one or none. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Ring buffer**: a fixed-size circular byte buffer through which producers hand messages to one consumer without locks or allocation. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Roll-down**: the price change from a bond's yield moving along an unchanged curve as its maturity shortens. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **RTS 6**: Commission Delegated Regulation (EU) 2017/589 on organisational requirements for algorithmic trading, retained in UK law and applied by FCA MAR 7A. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Sawtooth forwards**: the step or zig-zag shape of instantaneous forwards produced by linear interpolation. ([Section 13](#13-pitfalls-and-numerical-issues))
- **SBE**: Simple Binary Encoding, a fixed-layout binary message format whose fields sit at known offsets. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **SEF**: swap execution facility, a CFTC-registered venue for swaps offering an order book and RFQ. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Self-match prevention**: a venue rule that stops a participant's orders trading with each other; here the resting order is cancelled. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Settlement lag**: business days from trade to settlement: T+1 for Treasuries and gilts, T+2 for Bunds. ([Section 1](#1-rates-products-in-five-minutes))
- **Single-writer principle**: each piece of mutable state has exactly one writing thread, so no locks are needed and updates follow input order. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Skew**: a shift of both bid and offer in the same direction, driven by inventory; positive when the book is long the instrument's risk, lowering both prices. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Smart order router**: the component that splits a parent order across venues and over time to minimise expected cost, and journals why. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **SOFR**: Secured Overnight Financing Rate, the USD overnight rate based on Treasury repo, published by the New York Fed at about 08:00 New York time for the previous business day. ([Section 1](#1-rates-products-in-five-minutes))
- **SOFR future**: a CME contract settled on compounded (three-month) or averaged (one-month) SOFR over a reference period, quoted as 100 minus the rate. ([Section 5](#5-futures-ctd-basis-convexity))
- **SONIA**: Sterling Overnight Index Average, the GBP overnight unsecured rate administered by the Bank of England, published at 09:00 London time for the previous business day. ([Section 1](#1-rates-products-in-five-minutes))
- **Spread-to-cover**: our quote's distance from mid minus the best competitor's, in bp; the variable of the back-tester's win probability. ([Section 10](#10-back-testing-honestly-pre-registration-holdouts-attribution))
- **Square-root impact law**: price impact proportional to daily volatility times the square root of size over average daily volume. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Staged hedging**: sending part of a hedge plan now and the rest over time, trading impact cost against the risk carried meanwhile. ([Section 8](#8-hedging-and-the-dv01-ladder))
- **Street yield and true yield**: yield on nominal coupon dates, compounded at the coupon frequency, and yield on the business days coupons are actually paid. ([Section 4](#4-government-bonds-price-yield-carry-repo))
- **Stub**: a period shorter or longer than the regular one at the start or end of a schedule. ([Section 3](#3-swaps-schedules-pv-par-rate-dv01))
- **Survivorship bias**: drawing conclusions from records that survived a filter, such as trades, while the filtered-out ones, such as lost quotes and untraded RFQs, are invisible. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Swap data repository**: the registered entity that receives swap reports and disseminates the public part under CFTC Part 43. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Tension spline**: a spline between a cubic spline (zero tension) and linear interpolation (infinite tension). ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Trade-through rule**: the US equity rule against executing at a worse price than one displayed elsewhere; Treasuries and swaps have no counterpart. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Traded away**: an RFQ outcome in which the client traded with another dealer on the same request. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Truncation error**: the error of a finite-difference derivative from the Taylor terms it ignores; for a central bump, of order bump squared. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Turn**: a known jump in the overnight rate over a short window such as year end, added to the curve as an explicit spread. ([Section 2](#2-discount-curves-and-the-multi-curve-world))
- **Unit in the last place (ulp)**: the gap between a floating-point number and the next representable one, about $2.2 \times 10^{-16}$ of the value for doubles. ([Section 13](#13-pitfalls-and-numerical-issues))
- **Urgency**: a number in $[0, 1]$ that shortens the router's schedule and passive time limit; 1 crosses at once. ([Section 9](#9-smart-order-routing-in-fixed-income))
- **Void ab initio**: treatment of a SEF trade intended for clearing that the clearing house rejects: it is void from the start. ([Section 12](#12-regulation-uk-rts-6-fca-mar-7a-pra-ss518-mifir-transparency-dodd-frank-sef-rules))
- **Warm standby**: a second instance that follows the primary's journal and takes over quoting after a fenced hand-over. ([Section 14](#14-how-a-desk-would-use-this-and-how-real-desks-differ))
- **Warm-up**: the period before a measurement in which the JIT compiles and lazy initialisation completes. ([Section 11](#11-low-latency-java-single-writer-off-heap-replay))
- **Win probability (hit rate)**: the probability that a quote at a given distance from fair value wins the RFQ; a declared logistic band here. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Winner's curse**: the bias in a winning quote from winning only when one's own error favours the client; grows like $\sqrt{2 \ln n}$ with $n$ dealers. ([Section 7](#7-dealer-market-making-inventory-skew-cover-prices-winners-curse))
- **Workup**: a period after a CLOB trade in which more size can trade at the same price, first only for the trade's participants. ([Section 6](#6-how-rates-trade-electronically-rfq-versus-clob-sefs-workup))
- **Z-spread**: the constant continuously compounded spread over the OIS zero curve that reprices a bond. ([Section 4](#4-government-bonds-price-yield-carry-repo))
