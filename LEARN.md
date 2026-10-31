# Learning rates e-trading through this repository

## How to read this document

Each section follows the same shape. First the plain idea, in a few sentences a newcomer can hold in their head. Then the formal statement, with the conventions and formulas a reviewer would check. Then a glossary definition for each new term, repeated in Section 17. Then one worked example with numbers, with the method stated so the number can be reproduced. Where a number comes from code in this repository, the file and test are named.

Sections 1 and 11 were written in Phase 0, Sections 2 to 5 in Phase 1, Section 9 in Phase 4 and Section 10 in Phase 5. Every other section states what it will cover and which phase writes it, because the material should be written alongside the code that exercises it. Where a section depends on code that does not exist yet, this document says so rather than describing the code as if it did.

Nothing here is investment advice. All numbers are illustrative or come from simulations against public data.

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

Let \(d_i\) be the discount factor to payment date \(i\) and \(\alpha_i\) the accrual fraction of period \(i\) on the leg's day count. For a swap with notional \(N\) and fixed rate \(K\):

\[
PV_{\text{fixed}} = N K \sum_i \alpha_i d_i, \qquad
PV_{\text{float}} = N \sum_i \alpha_i F_i d_i
\]

where \(F_i\) is the forward compounded overnight rate for period \(i\), which in a single-curve OIS world equals \((d_{i-1}/d_i - 1)/\alpha_i\) on the same curve. The par rate is the \(K\) that makes the two legs equal. The sum \(A = \sum_i \alpha_i d_i\) is the annuity.

For a bond with coupon \(c\) per period and \(n\) remaining coupons, the dirty price per unit face is \(\sum_{i=1}^{n} c\, d_i + d_n\), and clean price is dirty minus accrued.

### Worked example: DV01 of a 10-year par swap

DV01 is the change in present value for a one basis point parallel move in rates. For a par swap it is, to first order, notional times the annuity times 0.0001, because the fixed leg's sensitivity dominates and the floating leg's value is near par.

Take 100 million USD notional, 10 annual fixed payments, and a flat rate of 4 percent with annual compounding, ignoring the Act/360 accrual adjustment and calendars for the sake of a round example. The discount factors are \(d_i = 1.04^{-i}\), and the annuity is

\[
A = \sum_{i=1}^{10} 1.04^{-i} = \frac{1 - 1.04^{-10}}{0.04} \approx 8.11
\]

so

\[
DV01 \approx 100{,}000{,}000 \times 8.11 \times 0.0001 \approx 81{,}000 \text{ USD per basis point}.
\]

The number is approximate. A production figure would bump the bootstrapped curve by one basis point, reprice both legs with the real schedule and day counts, and difference the two values; the result for a 10-year USD SOFR swap at 4 percent sits in the same region, around 80 to 85 thousand per basis point per 100 million, and moves with the level of rates because the annuity does. Section 3 repeats the example with the Phase 1 swap pricer: 82,367 USD per basis point on the COOKBOOK.md recipe 5 curve.

### Why this matters for the platform

Every one of the conventions above becomes a field in the securities master, a branch in the schedule generator and a test case against QuantLib. The venue simulators must quote in the right units (price for bonds, rate in basis points or percent for swaps). The risk engine must know that a one basis point move on a 10-year swap is about eighty thousand dollars per hundred million, and that a 30-year is roughly double. Getting the five-minute version right is the condition for getting the microsecond version right.

## 2. Discount curves and the multi-curve world

### Plain idea

A discount curve answers one question: what is a dollar paid on a given future date worth today. Every price in this project is a sum of cash flows times those values. The curve is not observed directly; it is inferred from instruments that are, overnight fixings, OIS swaps and futures, by finding the curve under which each of them is worth exactly its quoted price. That inference is the bootstrap.

Before 2008 one LIBOR curve both projected floating rates and discounted cash flows. Collateralised derivatives are now discounted at the overnight rate paid on collateral, so the OIS curve discounts. Where a term index survives (EURIBOR is the main example), it gets its own projection curve, built after the OIS curve with OIS discounting held fixed. That is the multi-curve setup.

### Formal statement

A curve is a set of pillar dates \(T_1 < \dots < T_N\) and log discount factors \(y_i = \ln P(0, T_i)\), with \(y_0 = 0\) at the valuation date. Time is \(t = (T - T_0)/365\). Between pillars the curve is interpolated in \(y(t)\) by one of three schemes (`Interpolation`, DECISIONS.md DR-P1-03):

- linear in \(y\): the instantaneous forward \(f(t) = -y'(t)\) is constant between pillars, so a pillar affects only its two neighbouring segments;
- monotone convex (Hagan and West 2006): forwards are continuous and stay within the range of the neighbouring average forwards;
- exponential tension spline: smooth like a cubic spline but with a tension that suppresses overshoot.

Beyond the last pillar the last segment's average forward is extended. Each instrument \(k\) has an implied quote \(q_k(y)\), a smooth function of the pillar values. The bootstrap solves the square system \(q_k(y) = Q_k\) for all \(k\), one instrument per pillar, to \(|q_k - Q_k| \le 10^{-12}\):

- sequentially, pillar by pillar with Brent's method, when the interpolation is local and no instrument depends on dates beyond its own pillar;
- globally, by Levenberg-Marquardt on all \(y_i\) with the analytic Jacobian \(J_{kj} = \partial q_k / \partial y_j\), when instruments overlap or the interpolation is non-local.

Instruments: the overnight fixing from today to the next fixing day, \(q = (P(t_0)/P(t_1) - 1)/\tau\); meeting-dated OIS between central bank effective dates, with the same formula over the meeting window, so that linear interpolation makes the overnight forward a step function that moves only at meetings; three-month futures, \(q = 100(1 - R - CA)\) with \(R\) the compounded rate over the IMM period and \(CA\) the Hull-White convexity adjustment (Section 5); and par OIS swaps (Section 3). A known year-end turn is an extra spread over the turn window, applied on top of the interpolated curve.

### Glossary

- **Discount factor**: \(P(0, T)\), the value today of one unit paid at \(T\).
- **Pillar**: a date at which the curve's value is a free parameter, one per bootstrap instrument.
- **Bootstrap**: solving for pillar values so that every input instrument reprices to its quote.
- **OIS discounting**: discounting collateralised cash flows on the curve of the overnight rate paid on collateral.
- **Projection curve**: a curve used only to forecast a term index such as EURIBOR 6M, built with OIS discounting held fixed.
- **Meeting-dated OIS**: an OIS whose period runs between two central bank policy effective dates, quoting the expected overnight rate between those meetings.
- **Turn**: a known jump in the overnight rate over a short window such as year end, added to the curve explicitly.
- **Monotone convex interpolation**: the Hagan-West scheme that keeps instantaneous forwards continuous and within the range of the neighbouring average forwards.
- **Tension spline**: a spline with a tension parameter between a cubic spline (zero tension) and linear interpolation (infinite tension).

### Worked example: a SOFR curve on 15 January 2025

COOKBOOK.md recipe 4 builds a curve from nine instruments: SOFR overnight at 4.30 percent (an estimate, since 15 January's SOFR is published on 16 January), three meeting-dated OIS to 8 May 2025 at 4.30, 4.29 and 4.22 percent, the September 2025 three-month future at 96.05, and par OIS at 2, 5, 10 and 30 years. The builder chooses the sequential solver. The two-year discount factor is 0.9266847083 (zero rate 3.776 percent) and the ten-year 0.6710374866 (3.983 percent); every instrument reprices within the 1e-12 solver tolerance.

The method is checked twice. Against QuantLib 1.44, the Java bootstrap of the four golden curves (USD, EUR and GBP OIS and a EUR 6M projection curve, 81 instruments) matches the QuantLib-priced bootstrap to 8.3e-16 in discount factor (`AnalyticsGoldenTest.javaBootstrapMatchesQuantLibCurvesAndRepricesInstruments`, 2026-10-31). Against itself, changing the 10Y quote by 7 basis points and rebuilding incrementally gives the same discount factors as a full rebuild to 3.9e-16 (linear), 7.5e-14 (monotone convex) and 6.8e-15 (tension spline) (`CurveBuilderTest.incrementalRebuildEqualsFullRebuild`, 2026-10-31).

## 3. Swaps: schedules, PV, par rate, DV01

### Plain idea

A swap is two schedules of payments. The fixed leg is easy: rate times accrual times discount factor. The overnight leg compounds a daily published rate over each period and pays the result a few days later. Getting either leg wrong by one day is a real error, so the schedule generator, the business-day rules and the separation between the calendar of fixings and the calendar of payments carry most of the work. Once the legs are right, the par rate is the fixed rate that balances them, and the DV01 is how much the value moves when the curve inputs move by one basis point.

### Formal statement

Schedule (`Schedule.generate`, the rules of QuantLib's `Schedule`): roll unadjusted dates by the period from the termination date backward (short front stub, the default) or from the effective date forward (short back stub); apply the end-of-month rule if requested; adjust intermediate dates by the business-day convention; merge a short stub into its neighbour for a long stub.

Compounded in arrears (ISDA 2021 Definitions, Section 7.6). For an accrual period with fixing-calendar business days \(d_0 < \dots < d_n\) and published rates \(r_i\):

\[
C = \prod_{i=0}^{n-1} \bigl(1 + r_i \, \delta_i\bigr), \qquad \text{coupon} = N (C - 1), \qquad \delta_i = \frac{d_{i+1} - d_i}{360 \text{ or } 365}
\]

paid on the payment calendar \(L\) business days after the period end (USD \(L = 2\), EUR 1, GBP 0). A lookback of \(k\) days observes the rate \(k\) fixing days earlier; a lockout of \(k\) days repeats the rate observed \(k\) days before the end; an observation shift weights each rate by its observation period. Rates not yet published are projected from the curve, \(r_i = (P(d_i)/P(d_{i+1}) - 1)/\delta_i\), and runs of such days telescope to one ratio of discount factors.

Present value, par rate, annuity:

\[
PV_{\text{payer}} = \sum_p N (C_p - 1) P(\text{pay}_p) - N K \sum_i \alpha_i P(\text{pay}_i), \qquad K_{\text{par}} = \frac{\sum_p (C_p - 1) P(\text{pay}_p)}{\sum_i \alpha_i P(\text{pay}_i)}.
\]

DV01 by algorithmic differentiation (DECISIONS.md DR-P1-04): the pricer returns \(g_j = \partial PV / \partial y_j\); with the bootstrap Jacobian \(J\), the sensitivity to the quotes solves \(J^{\top} x = g\), and the key-rate DV01 of input \(k\) is \(x_k \times 10^{-4}\) (or \(-100 \times x_k \times 10^{-4}\) for a futures price). The parallel DV01 is their sum by construction.

### Glossary

- **Compounded in arrears**: the floating amount is the compounded daily rate over the period, known only at its end.
- **Payment lag**: business days between the period end and payment, on the payment calendar.
- **Fixing calendar**: the days on which the index is published; distinct from the payment calendar (SOFR is not published on Good Friday even when SIFMA recommends only an early close).
- **Lookback, lockout, observation shift**: variants of the observation window that give the payer time to know the amount before paying.
- **Stub**: a period shorter (or longer) than the regular period at the start or end of a schedule.
- **End-of-month rule**: if the start is the last business day of its month, every date is the last business day of its month.
- **IMM swap**: a swap starting on an IMM date and ending on another.
- **Annuity**: \(\sum_i \alpha_i P(\text{pay}_i)\), the value of one unit of fixed rate paid on the fixed leg.
- **Algorithmic differentiation (AD)**: computing exact derivatives by applying the chain rule through the code's operations, here backwards from the price to the curve inputs.

### Worked example: the 10-year swap, revisited

Section 1 estimated the DV01 of a 10-year par swap at 4 percent as about 81,000 USD per basis point per 100 million. COOKBOOK.md recipe 5 computes it on the recipe 4 curve: a 10-year SOFR swap paying 4 percent on 100 million has par rate 3.990000 percent, annuity 8.232552, present value minus 82,325.52 USD and parallel DV01 82,367.26 USD per basis point, almost all of it on the 10Y pillar because the swap has the dates of the 10Y curve instrument. Bumping each quote one basis point up and down gives 82,367.28.

Against QuantLib 1.44 over 83 swaps (spot, off-market, forward-starting, IMM, seasoned with real 2024 fixings, and USD with lookback, lockout and observation shift), present values agree to 1.6e-15 of notional and par rates to 3.5e-16; parallel DV01 by AD agrees with QuantLib's one-basis-point central bumps to 1.4e-6 relative, the size of the bump's own truncation error (`AnalyticsGoldenTest`, 2026-10-31).

Holdout year 2024: a one-year OIS's daily grid has exactly the publication dates of SOFR, EUR STR and SONIA in 2024, and compounding the published rates with the swap's day weights reproduces every published SOFR Index, EUR STR compounded index and SONIA Compounded Index value from 3 January 2024 to 31 January 2025 to the eighth decimal, from a starting value within the published starting index's rounding interval (`CompoundedIndexHoldoutTest`, 2026-10-31).

## 4. Government bonds: price, yield, carry, repo

### Plain idea

A bond's price is the value of its remaining coupons and principal. Markets quote the clean price and settle the dirty price, which adds the coupon interest earned since the last payment. The yield is the single rate that discounts the cash flows to the dirty price, under a stated convention for how fractions of a period count. Owning a bond costs money to finance, normally in repo; the difference between the coupon income and that financing cost is carry, and the price change from the bond sliding down a sloped curve as it ages is roll-down.

### Formal statement

Accrued interest (Act/Act ICMA): \(AI = 100 \, c \, \frac{\text{days since last coupon}}{f \times \text{days in the coupon period}}\); for a gilt inside its ex-dividend period (seven business days before the coupon) \(AI = -100 \, c \, \frac{\text{days to next coupon}}{f \times \text{days in the period}}\).

Price from yield, street convention (`BondPricer.dirtyPrice`, QuantLib's `CashFlows::npv`): with times \(t_k\) accumulated period by period in Act/Act ICMA from settlement to each nominal coupon date,

\[
P_{\text{dirty}} = \sum_k CF_k \Bigl(1 + \frac{y}{f}\Bigr)^{-f t_k}.
\]

The true yield uses coupon dates moved to business days. The Treasury's auction formula (31 CFR 356, Appendix B) instead discounts the first fractional period with simple interest, \(P_{\text{dirty}} = V_{\text{next}} / (1 + \tfrac{r}{s}\tfrac{y}{2})\), and the Treasury rounds the dirty price and the accrued interest to six decimals before subtracting.

Risk: modified duration \(D = -P'/P\), convexity \(P''/P\), DV01 \(= D \, P \times 10^{-4}\) per 100 face.

Repo forward and carry, for repo rate \(r\) (Act/360) over \(d\) days and coupons \(C_i\) paid \(d_i\) days before the horizon:

\[
F_{\text{clean}} = P_{\text{dirty}}\Bigl(1 + \frac{r d}{360}\Bigr) - \sum_i C_i \Bigl(1 + \frac{r d_i}{360}\Bigr) - AI_{\text{horizon}}, \qquad \text{carry} = P_{\text{clean}} - F_{\text{clean}}.
\]

Spreads to the OIS curve: the z-spread \(s\) solves \(P_{\text{dirty}} = \sum_k CF_k P(t_k) e^{-s t_k} / (P(t_0) e^{-s t_0})\); the par-par asset-swap spread is \((B - P_{\text{dirty}})/(100 A)\), with \(B\) the bond's value on the curve and \(A\) the floating annuity. Fitted curves for relative value: Nelson-Siegel-Svensson (the Bundesbank's daily form) and a natural cubic spline in yield.

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

COOKBOOK.md recipe 6 prices the note for trade on 15 January 2025, settling 16 January, at a 4.66 percent yield: clean 96.790759, accrued 0.727901, dirty 97.518659, modified duration 7.860048, DV01 766.50 USD per million face. The true yield for the same price is 4.65971296 percent. Its Appendix B price at the 11 December 2024 reopening auction's high yield of 4.235 percent is 100.114150, the published value.

Checks: all 84 note and bond auctions and all 332 bill auctions of 2024 reproduce the published price to six decimals and the published yield to three (`PublishedExamplesTest`, TreasuryDirect data in `data/fixtures/ust_auctions_2024.csv`, 2026-10-31). Against QuantLib 1.44 for 67 Treasuries, Bunds and gilts (four of the gilts settle inside their ex-dividend period), clean price and accrued interest agree to 3.3e-13 per 100, street and true yields to 7.3e-16, duration, convexity and DV01 to 4.2e-15 relative (`AnalyticsGoldenTest.bondsMatchQuantLib`, 2026-10-31). The UK DMO's gilt reference prices could not be downloaded by script (data/SOURCES.md), so no DMO worked example is in the gate yet; recipe 7 shows the gilt ex-dividend rule.

## 5. Futures: CTD, basis, convexity

### Plain idea

A Treasury bond future is a promise to deliver one of a basket of bonds at a fixed price times a conversion factor. The short chooses which bond, so the future tracks the bond that is cheapest to deliver. The basis is the gap between the bond's price and the futures price times the factor; it shrinks to the delivery option's value at expiry, and after carry what is left is the net basis. A SOFR future pays on the average or compounded SOFR over a reference period, and because it is settled daily its rate sits slightly above the forward rate: the convexity adjustment.

### Formal statement

Conversion factor (CME formula for CBOT contracts): with \(n\) whole years and \(z\) months (rounded down to a quarter, or a month for the 2-, 3- and 5-year notes) from the first day of the delivery month to maturity, \(v = z\) if \(z < 7\) else \(z - 6\),

\[
a = 1.03^{-v/6}, \quad b = \frac{c}{2} \frac{6 - v}{6}, \quad C = 1.03^{-2n} \text{ or } 1.03^{-(2n+1)}, \quad d = \frac{c}{0.06}(1 - C), \quad CF = a\Bigl(\frac{c}{2} + C + d\Bigr) - b,
\]

rounded to four decimals. Eurex and ICE factors are the clean price per unit face at the contract's notional coupon (6 percent annual, 4 percent semi-annual) on the delivery date.

Basis, carry and implied repo for spot settlement \(s\), delivery \(D\), futures price \(F\): gross basis \(= P - F \, CF\); implied repo \(= (F \, CF + AI_D + \sum C_i - P_{\text{dirty}}) / (P_{\text{dirty}} d/360 - \sum C_i (D - t_i)/360)\); net basis = gross basis minus carry at the repo rate. The CTD maximises implied repo. Futures DV01 = CTD DV01 / CF.

SOFR futures: price \(= 100 (1 - R - CA)\), with \(R\) the compounded (three-month) or arithmetic average (one-month) SOFR over the reference period. In the Hull-White model \(dr = (\theta(t) - a r)dt + \sigma dW\), with \(B(s,t) = (1 - e^{-a(t-s)})/a\),

\[
CA = \frac{B(t_1, t_2)}{t_2 - t_1}\Bigl[B(t_1, t_2)(1 - e^{-2 a t_1}) + 2 a B(0, t_1)^2\Bigr] \frac{\sigma^2}{4a},
\]

with \(a = 0.03\) and \(\sigma = 0.0085\) (DECISIONS.md DR-P1-05).

### Glossary

- **Conversion factor**: the price per unit face of a deliverable bond at the contract's notional coupon yield.
- **Gross basis**: cash price minus futures price times conversion factor.
- **Net basis**: gross basis minus carry to delivery.
- **Implied repo**: the financing rate at which buying the bond and delivering it into the future breaks even.
- **Delivery option**: the short's right to choose the bond and the day of delivery, which the net basis prices.
- **Convexity adjustment**: the excess of a daily-settled futures rate over the forward rate, from the correlation between margin flows and rates.

### Worked example: the March 2025 10-year note future

COOKBOOK.md recipe 8 takes four bonds in the TY March 2025 basket at prices from the golden file's smooth curve, with the future at 108-11+ (108.359375) and repo at 4.30 percent. The 4 1/8% note of November 2031 (CF 0.9003) has the highest implied repo, 4.1674 percent, and the smallest net basis, 0.0267: it is the CTD. The deep-discount 1 3/8% of November 2031 (CF 0.7541) has an implied repo of minus 0.94 percent.

Against the golden file, all 38 conversion factors (CBOT, Eurex and ICE) match exactly at their published rounding, and gross basis, carry, net basis and implied repo agree to 1e-10 with the same CTD in five contracts (`AnalyticsGoldenTest`, 2026-10-31). SOFR futures priced on the golden USD curve match QuantLib's `OvernightIndexFuture` exactly for six one-month and four three-month contracts. For the December 2025 three-month contract the convexity adjustment is 0.38 basis points.

## 6. How rates trade electronically: RFQ versus CLOB, SEFs, workup

### Plain idea

Rates trade on two kinds of electronic venue. On a dealer-to-client (D2C) venue a client asks a few dealers for a price, the dealers answer within seconds, and the client trades with the best answer or with nobody: this is request for quote, RFQ. On a dealer-to-dealer (D2D) venue dealers post anonymous bids and offers in a central limit order book, CLOB, and trade with each other by hitting or lifting them. US Treasuries trade in both places. Interest rate swaps traded by US persons must, for the most liquid tenors, trade on a swap execution facility, SEF, which offers both an order book and RFQ under rules written by the CFTC.

Two details shape almost everything a dealer's quoting engine does. In RFQ, what the venue tells each dealer after the trade differs: the winner usually learns the cover (the second-best price), losers learn only that they lost. In the Treasury CLOB, a trade is followed by a workup: for a few seconds more size can trade at the same price, first among the original parties and then among anyone, so the visible book understates how much trades at a price.

### RFQ

Formal statement. An RFQ names an instrument, a side from the client's point of view, a size and the dealers in competition. Each dealer may answer once (and may replace its answer) before a response window closes. The client then either accepts one quote, passes, or lets the request expire; on some platforms the client may also report that it traded elsewhere. With quotes \(p_1, \dots, p_n\) on the client's side, a client buying chooses \(\min_i p_i\) and a client selling chooses \(\max_i p_i\); ties go to the earlier quote. The cover is the second-best of the same set. What is disclosed after the outcome is a platform choice:

| Outcome | Winner is told | Other quoting dealers are told | Dealers who did not quote |
|---|---|---|---|
| Client trades on the venue | traded, at its price, and on many platforms the cover | traded away (no price) | nothing or expired |
| Client trades elsewhere | done away | done away | nothing or expired |
| Client passes | passed | passed | nothing or expired |
| No quotes in time | expired | expired | expired |

The cover tells the winner how much it left on the table; a winner that repeatedly wins by a wide margin is quoting too aggressively. Losers learn that they lost but not by how much, which is why win-probability models cannot be fitted from a dealer's own records alone (DECISIONS.md DR-008).

How `rates-venues` models it. `D2cRfqSimulator` sends each RFQ to every logged-on engine dealer and to simulated competitors, three to five dealers in total. Simulated half spreads are lognormal around a median (0.0078125 in price for Treasuries, 0.0025 in rate for SOFR swaps), response times are lognormal around 800 ms, and the client trades with the best quote if it is within a lognormal tolerance of mid, passes otherwise, and trades elsewhere with probability 0.05. RFQ arrivals follow an intensity that rises around 08:00, 08:30, 10:00, 13:00 and 15:00 New York time (fixings, data releases, auction results, the close). A share of clients is informed: after an informed client trades, mid moves in the client's favour, so the dealer's markout on those trades is worse. Every number is a declared assumption in `rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`, not a fit (DR-P2V-05). On the wire the winner gets QuoteResponse with QuoteRespType 1 and the cover in tag 9502, losers get QuoteRespType 5 with 9503=TA, a done-away RFQ gives 9503=DA (API_SPEC.md Section 10.2).

### CLOB and workup

Formal statement. A CLOB keeps bids and offers by price, and within a price by arrival time (price-time priority). An incoming order that crosses the best opposite price trades against resting orders in that priority until it is filled or no longer crosses; the rest of it rests (or is cancelled, if immediate-or-cancel). Participants are anonymous to each other. A Treasury CLOB adds a minimum size (here 1 million face, in multiples of 1 million), a tick (1/128 of a point here) and iceberg orders, which show only a slice \(\min(\text{display}, \text{remaining})\) and go to the back of the queue each time a slice is refilled.

Workup. After a trade at price \(P\), the book enters a workup at \(P\) for a duration \(W\). During the first \(E \le W\) seconds (the exclusive window) only the participants of the opening trade may trade more at \(P\); others who arrive with marketable orders wait. After \(E\), anyone may trade at \(P\). Each further trade may restart the timer. When the timer expires, normal matching resumes. Fleming, Mizrach and Nguyen (2018) describe the protocol on BrokerTec and how much of the platform's volume trades in workups; for a quoting engine the consequence is that displayed depth understates what can trade at the touch.

How `rates-venues` models it. `ClobBook` applies these rules with \(W = 5\) s and \(E = 2\) s by default; during workup a marketable order trades only at \(P\) and any remainder rests at \(P\), and an order that would trade at another price waits until the workup ends (a simplification stated in API_SPEC.md Section 11.2). Execution reports carry the workup state in tag 9504: N normal, X exclusive window, W open workup. Self-match prevention cancels the resting order of the same participant by default. Every book change is published on an SBE price-level feed with one sequence number per venue (API_SPEC.md Section 10.5).

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

- RFQ: request for quote; a client asks named dealers for a price on a stated size and side.
- CLOB: central limit order book; anonymous bids and offers matched by price-time priority.
- Cover price: the second-best quote on the client's side of an RFQ.
- Traded away: the client traded with another dealer on the same RFQ.
- Done away: the client reports trading the RFQ elsewhere.
- Workup: a period after a CLOB trade during which more size can trade at the same price, first exclusively for the trade's participants.
- Iceberg order: an order that displays only part of its size and refills from the hidden rest.
- Self-match prevention: a venue rule that stops a participant's orders from trading with each other.
- SEF: swap execution facility, a CFTC-registered venue for swaps.
- MAT: made available to trade; a swap a SEF or DCM has certified as required to trade on a SEF or DCM.
- Required Transaction: a MAT swap, which must use the order book or RFQ-to-3 unless it is a block.
- Permitted Transaction: any other swap on a SEF; any execution method.
- Block trade: a swap at or above the appropriate minimum block size; exempt from the Required Transaction methods and given delayed public reporting.
- FCM: futures commission merchant; the clearing member that guarantees a customer's cleared trades.
- Clearing certainty: assurance before execution that a trade will be accepted for clearing.

### Worked example: what the cover price tells the winner

COOKBOOK.md recipe 10 (`rates-cookbook/src/main/java/io/ratesalgo/cookbook/Recipe10VenueSession.java`, output recorded on 2026-10-31) sends a client RFQ to buy 10 million face of the 10-year note `US91282CZG94` to four dealers. Three simulated dealers offer 98.03, 98.02 and 98.05; we offer 98.015. The client buys from us at 98.015, and the venue discloses the cover, 98.02.

Method. Prices are per 100 of face. The money left on the table is the cover minus our price, times face over 100:

\[
(98.02 - 98.015) \times \frac{10{,}000{,}000}{100} = 0.005 \times 100{,}000 = 500 \text{ USD}.
\]

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

Planned for P3 (quoting, risk, hedging). It will cover the economics of quoting, inventory-driven skew, how the cover price is observed and modelled, the winner's curse in multi-dealer RFQ, and the assumption band the project declares for win probability (DECISIONS.md DR-008).

## 8. Hedging and the DV01 ladder

Planned for P3 (quoting, risk, hedging). It will cover key-rate DV01, the ladder by tenor bucket, hedging swaps with futures and benchmark bonds, hedge ratios, residual risk, and the hedger's decision rules.

## 9. Smart order routing in fixed income

### Plain idea

A smart order router takes one order that is too large, or too urgent, to send to a single place and decides where, how and when to work it. For each venue it estimates what a child order would cost: the spread it would pay or earn, the price impact of its size, the fees, the chance that a resting order is filled before the market moves, and the delay to the venue. It then splits the order to minimise the total, sends passive orders first where waiting is cheap, escalates to aggressive orders when time runs out, and re-routes when the book moves away. Everything it decided, with the numbers it decided on, goes into the journal.

### Why bonds differ from equities

Equity routers in the United States work against a consolidated quote and a trade-through rule: the national best bid and offer is public, and a venue may not execute through a better price displayed elsewhere. Treasuries and swaps have neither. There is no consolidated tape of quotes, and each venue's book is visible only to its participants. The dealer-to-dealer market in on-the-run Treasuries trades on a few central limit order books with deep top-of-book size, clip sizes of millions, ticks of 1/128 or 1/64 of a point, and a workup protocol after each trade (Section 6). Off-the-run bonds and most swaps trade by request for quote. Futures are the deepest liquidity for duration risk and are often the hedge instrument of choice, at the cost of basis risk. A rates router therefore chooses among a handful of venues with very different microstructure, and its biggest decision is often which instrument and protocol to use, which in this project belongs to the hedger (Section 8); the router places a parent in one instrument across the venues that list it.

### CLOB versus RFQ for hedging

Hedging on a CLOB is anonymous and immediate. Crossing the spread pays half of it plus the impact of the size; resting earns half the spread but risks not being filled, or being filled just before the price moves through the order (adverse selection). Hedging by RFQ shows the size and the direction to several dealers before any trade, which is information leakage: the losing dealers know a hedge is coming and can trade ahead of it. For the small, frequent hedges a market maker generates, the CLOB with a router is the usual choice; RFQ is for sizes the books cannot absorb. The router in this project routes to CLOB and futures venues only.

### Queue position

A resting limit order joins the back of the queue at its price. It fills when the volume traded at that price exceeds the quantity ahead of it, minus what cancels ahead of it. The router uses the pure-death queue of Cont, Stoikov and Talreja (2010): unit market orders arrive at rate \(\mu\), each unit ahead cancels at rate \(\theta\), and the probability that an order of \(s\) units behind \(q\) units fills completely within \(T\) is

\[
P = \Pr\Big(\sum_{k=1}^{q} E_k + \sum_{i=1}^{s} F_i \le T\Big),\qquad E_k \sim \mathrm{Exp}(\mu + \theta k),\; F_i \sim \mathrm{Exp}(\mu),
\]

evaluated by uniformisation (`QueueFillModel`, the algorithm pinned in the owner's earlier execution library). Deeper queues, slower flow and shorter horizons all lower \(P\); cancellations ahead raise it.

### Passive first, then escalate

The cost of a passive child of size \(q\) on venue \(v\), with half spread \(h\), impact \(I(q)\), latency cost \(L\), fees \(f_m\) (maker) and \(f_t\) (taker), and an expected adverse move \(A\) if it is not filled, is

\[
c_{\text{pass}}(q) = P\,(-h + I + f_m) + (1 - P)\,(A + h + I + f_t) + L,
\qquad
c_{\text{aggr}}(q) = h + I + f_t + L,
\]

per unit, in price points. The unfilled part is assumed to cross at the end of the passive time limit, which is why it carries \(A + h + f_t\). Impact is charged to both, because the square-root law \(I(q) = y\,\sigma_{\text{day}}\sqrt{q/\mathrm{ADV}}\) describes the price pressure of traded volume whatever the order type. The router allocates a slice across venues to minimise \(\sum_v q_v\,\min(c_{\text{pass},v}, c_{\text{aggr},v})\) under minimum sizes, lot sizes and participation caps (API_SPEC.md Section 15.2). Urgency \(u \in [0, 1]\) shortens the schedule and the passive time limit in proportion to \(1 - u\); at \(u = 1\) everything crosses at once. When a passive child reaches its limit, the router cancels it and routes the remainder aggressively. When the touch moves away from a resting child by the threshold, the router cancels it and routes the remainder again, passively if time allows. Quantity is re-routed only after the venue confirms the cancel, so late fills can never make the children exceed the parent.

### Anti-gaming

A router that sends the same size at the same interval is easy to detect: another participant who sees a 5 million clip every 15 seconds can trade ahead of the next one. The router draws each slice size and each interval from a uniform band around its nominal value (25 and 30 percent half-widths by default), from a seeded random source so that tests and back-tests are reproducible. `ChildRandomiserTest` checks that 2000 draws stay inside the band, show no lag-1 to lag-10 autocorrelation beyond \(3/\sqrt{n}\), and pass a runs test about the median, and that a sawtooth sequence fails the same checks.

### Worked example

COOKBOOK.md recipe 17 routes a 20 million buy of a 10-year note across two simulated CLOBs at urgency 0.5. The first slice is 4 million. On venue 1 the touch is 98.500000 / 98.515625 (one tick of 1/64), 12 million rests on the bid, and the router's estimate of flow is 1 lot per second each side. For the 3 million it evaluated there:

- half spread \(h = 0.0078125\);
- impact \(I = 0.6 \times 0.5 \times \sqrt{3/46\,800} = 0.002402\) (ADV of 46,800 lots is twice the arrival rate over a 23,400-second day);
- latency cost \(L = 1.0 \times (0.5/\sqrt{23\,400}) \times \sqrt{0.00025} = 0.000052\);
- aggressive cost \(0.0078125 + 0.002402 + 0.0005 + 0.000052 = 0.010766\), as printed;
- fill probability within 15 seconds \(P = 0.8161\), and passive cost \(-0.001065\), so the child rests.

The decision table, the journaled RouteDecisionVenue rows and a re-evaluation from those rows agree to the last digit (`DecisionReplayTest`).

### What the back-test shows

Over 40 seeded synthetic days of 15 parents each, the router's mean cost was -0.1957 bps of price, against 2.6822 for sending each parent as one aggressive order to the primary venue and 2.5108 for an even split across three venues. The mean daily saving was 2.8780 bps (95 percent block-bootstrap interval 2.7210 to 2.9855) and 2.7065 bps (2.5309 to 2.8272) respectively (`BacktestTest.routerBeatsBothBaselinesInTheShippedScenario` (rates-router), run 2026-10-31 on JDK 25.0.5, Windows 11). The market is synthetic and its assumptions are listed in API_SPEC.md Section 15.6: a random-walk price with no informed flow beyond the price moves themselves, Poisson market orders, queue cancellations at a constant rate, and permanent impact proportional to the size taken. The number says the router's logic works as designed in that world, not what it would save on a real desk.

### Measuring execution quality

Markout is the change in mid a fixed time after a fill, signed so that positive is good for the order (Section 17). A router that rests passively earns spread but is filled more often just before an adverse move; its markouts show it. The post-trade analysis of P5 and P6 reads the journaled decisions next to the fills and their markouts, so a poor result can be traced to the cost estimate that caused it.

## 10. Back-testing honestly: pre-registration, holdouts, attribution

### Plain idea

A back-test is a replay of history through a strategy, and it is the easiest place in a trading system to fool yourself. Every extra parameter tried, every look at the evaluation data and every number reported at the one assumption that flatters it makes the result less believable. The back-tester in this repository is built to make those mistakes visible: an experiment is written down and hashed before it runs, the holdout days are opened once, every run is recorded in an append-only ledger whatever it shows, P&L is split into lines that must add up to the cent, and every result is reported at three points of the declared assumption band rather than at one.

### Formal statement

**Replay.** `BacktestRunner` (`rates-backtest`, API_SPEC.md Section 16.1) reads RAJ1 journals of SBE messages with `JournalReader`, which verifies the hash chain, and drives a `ManualClock` to each frame's timestamp. Events the back-tester creates itself (RFQ outcomes, order fills, strategy timers) wait in a queue ordered by time and then by scheduling order, and run before any later frame. A run is therefore a function of the journals, the configuration, the seed and the strategy. The fingerprint of a run is the FNV-1a chain hash over every output message (Quote, QuoteResponse, NewOrder, ExecutionReport).

**Outcome models.** The public tape shows trades, never the quotes that lost, so the probability of winning an RFQ is an assumption (DR-008). The back-tester draws the best competing quote (the cover) from a declared competitor model and settles our quote with a logistic in spread-to-cover:

\[
P(\text{win}) = \frac{1}{1 + e^{-z}}, \qquad
z = b_0 + b_1 s + b_2 \ln q + b_3 T + b_4 F + b_5 n,
\]

where \(s\) is our distance from mid minus the cover's, in basis points, \(q\) the size in millions, \(T\) the tenor, \(F\) the fixing-window flag and \(n\) the dealer count. The slope \(b_1\) is taken from the band in `data/presets.yaml` (preset `usd_rfq_hit_rate`, low \(-1.25\), central \(-2.5\), high \(-5.0\) per bp). Random draws come from a SplitMix64 stream keyed by the run seed and the RFQ id, so two strategies replayed over the same journal face the same competitors and the same uniform draw on every RFQ (common random numbers), and the comparison between them is paired.

**Pre-registration.** A pre-registration (API_SPEC.md Section 16.8) states the hypothesis, the metric, the baseline and treatments, the evaluation period, the fitting period, the holdout dates, the success threshold, the assumption band, the author and the date, and pins the SHA-256 of the experiment config. `ExperimentCli` refuses to run when the file is missing or invalid, when a holdout date falls in the fitting or evaluation period, when the evaluation period overlaps the fitting period, when the config has changed since it was hashed, or when the holdout has already been opened.

**Ledger.** `data/ledger/ledger.jsonl` holds one canonical JSON object per run: the pre-registration, config and journal hashes, the code commit, the look number, the results and the verdict. Each line carries the SHA-256 of the line before it, so an edited or deleted past line breaks the chain at that line, and `Ledger.append` refuses to extend a broken chain. A refused run has read no data and is not recorded; a run that started is recorded whatever it shows.

**Attribution.** Valuation is linear in the quoted price, \(V = q \cdot p \cdot v\), with \(v\) the value per unit (annuity times \(10^{-4}\) for swaps, \(10^{-4}\) per 100 of face for bonds and futures). Each event updates two views:

\[
\underbrace{\text{cash} + Q\,m\,v}_{\text{position P\&L}}
= \underbrace{\textstyle\sum_{\text{client}} q(m - p)v}_{\text{spread capture}}
+ \underbrace{\textstyle\sum_{\text{mid moves}} Q\,\Delta m\,v}_{\text{inventory}}
+ \underbrace{\textstyle\sum_{\text{hedges}} q(m - p)v}_{\text{hedge cost}}
+ \text{carry} + \text{funding} + \text{fees},
\]

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

A hit-rate slope fitted to the project's own synthetic labels recovers the slope that generated them (`usd_rfq_hit_rate`: \(-2.57 \pm 0.04\) per bp against a declared \(-2.5\), out-of-sample log-loss 0.6146 against 0.6900 for a constant, `ratesresearch.hitrate.main`, 2026-10-31). That is a consistency check of the pipeline, not a measurement of a market. Reporting at the low, central and high points shows how much of a result depends on the assumption: in P5-001 the treatment's advantage at the high point is a fifth of that at the low point. A result that holds only at one point of the band is a statement about the assumption.

### How the platform uses this

The back-tester reads the same SBE messages the event loop will (P2), so when the loop's replay mode is bound to it, strategies under test run the live code path. The quoting engine (P3) reads the same `usd_rfq_hit_rate` preset, band included. The daily report (`ReportCli`, API_SPEC.md Section 16.9) runs unattended from a journal and a config and covers P&L by product and line, hit rate by tier, markouts at 1 s, 10 s, 1 min and 5 min, hedge slippage, limit utilisation and client analytics; `docs/templates/weekly_review.md` is the template for the weekly review that reads it.

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

The rule is checked, not asserted. A test wraps a burst of events, reads `ThreadMXBean.getThreadAllocatedBytes(threadId)` before and after on the loop thread, and asserts the difference is zero. The test runs after a warm-up pass so that lazy initialisation and JIT compilation have already happened. The P0 modules are built with this discipline in mind (the `TimerWheel` and the journal writer allocate at construction, not on `poll` or `append`), and the allocation test itself arrives with the event loop in P2, when there is a hot path to measure.

### The journal and deterministic replay

Every input event is appended to the journal before it is processed. The journal is a memory-mapped, append-only file (`rates-journal`). Each frame carries a sequence number, a timestamp, the SBE template id and version, and a chain hash: FNV-1a 64 over the previous frame's hash and this frame's payload. The format is pinned in `API_SPEC.md` Section 1.

Replay reads the frames back in order and feeds them to the same handler the live loop used. Because the loop is single-writer and allocation-free, and because time is injected through the `Clock` interface (`ManualClock` in replay, `SystemClock` live), the outputs are identical byte for byte. The chain hash is verified as frames are read; a corrupted or truncated journal fails loudly with the sequence number of the break rather than replaying garbage. In P0 the `Replayer` and its hash check exist and are tested; the handlers it will drive arrive with P2.

This is also the testing strategy. A bug report comes with a journal. The fix is verified by replaying the same journal and comparing outputs. Golden journals are kept for regression.

### Measuring latency

Latency is not measured with JMH, because a microbenchmark of one method does not answer the question the desk asks, which is how long it takes for a book update to become a quote on the wire under realistic load. The methodology, written out in `docs/BENCHMARKS.md`, is:

A load generator sends events at a fixed rate, independent of how fast the system responds. If the system stalls, the generator keeps its schedule and the late responses are measured against the time the event should have been sent. This is the coordinated-omission correction described by Gil Tene; without it, a system that pauses for 100 milliseconds once a second looks better than it is, because fewer samples are taken during the pause. Samples are recorded in an `HdrHistogram` and reported at p50, p99, p99.9, p99.99 and max, with the machine, kernel and JVM flags recorded alongside.

Two boundaries are measured. Core decode-to-encode is the time from the first byte of an input event being readable in the ring buffer to the last byte of the output being published. Tick-to-wire adds the gateway on both ends. Hosted CI runners produce informational numbers only, because their neighbours are unknown; gating thresholds apply only on a pinned, self-hosted machine. In Phase 0 nothing has been measured, and this document will cite the first report by file name and date when one exists.

### The ring-buffer contract and the replay comparison (P2)

Plain idea. A gateway and the loop agree on three things and nothing more: what a record on the ring looks like, how big it may be, and what happens when the ring is full. With that agreement the venue session can be written without reading the loop's code. Replay then answers one question: given the same inputs, did the loop say exactly the same things?

Formal statement. A record on an inbound or outbound ring is an Agrona record whose type is the SBE template id of its payload, and whose payload is the SBE message header followed by the body, at most 1024 bytes (API_SPEC.md Section 9.2). The same bytes are appended to the journal. When the inbound ring is full the gateway's publish returns false: market data is dropped and counted, session messages are retried (Section 9.3). When an outbound ring is full the loop drops the message, counts it, and after the handler returns journals a Backpressure record and tells the quoting layer, which pulls its quotes (Section 9.5).

Determinism needs time and timers to be inputs too. The loop reads the clock once per duty cycle, and every handler and journal frame in that cycle sees the same value. When timers fire, the loop journals a clock tick, so that replay fires the same timers between the same two messages (DECISIONS.md DR-P2C-02). Agents are not replayed, so anything that should change the loop's state reaches it as a message on a ring.

Replay builds a fresh loop from the same factory, feeds it the inbound journal through the `Replayer` and a `ManualClock`, and chains every outbound message it produces with the journal's FNV-1a chain hash. Each produced hash is compared with the recorded outbound journal's frame of the same sequence. Because the chain covers every payload byte in order, equal final hashes mean equal outbound streams, and the first unequal hash names the first message that differs.

Glossary. Duty cycle: one pass of the loop over its rings, timers and agents. Clock tick: a journaled Heartbeat with source id 0 recording that timers fired at the cycle's time. Backpressure event: the record that outbound messages were dropped because a gateway's ring was full. First mismatch: the outbound sequence at which a replay first differs from the recording.

Worked example. Recipe 12 in COOKBOOK.md runs 100 QuoteRequests through a loop that quotes 100 with a half-spread of 0.002 (mantissa 2,000,000), journaling into 4 KiB segments. Replaying with the same handler reproduces the outbound hash exactly. Replaying with a half-spread of 0.0025 reports the first mismatch at outbound sequence 1, the first Quote, because its bid and ask bytes differ. `LoopPropertiesTest` checks the same equality for generated runs with dropped, duplicated, delayed and reordered inputs, timers, backpressure and disconnects (`rates-core`, 2026-10-31).

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

Planned for P6. It will collect the mistakes found while building: day-count and calendar errors, stub handling, root-finding failures in yield solvers, interpolation artefacts in bootstraps, floating-point accumulation in compounded rates, int64 mantissa overflow, and the tests that now guard each.

## 14. How a desk would use this and how real desks differ

### Plain idea

A rates e-trading desk is a small team of traders, quants, developers and operations staff, surrounded by risk, compliance, credit, clearing and settlement functions it depends on. This platform is a model of the desk's own stack, run against simulated venues and public data. It is useful to read the day as a desk would live it, and then to be plain about everything a real desk has that this project does not.

### A day on the desk with this platform

- **Before the open.** Operations runs the start-of-day procedure ([docs/runbooks/start-of-day.md](docs/runbooks/start-of-day.md)): yesterday closed and reconciled, configuration validated, opening positions loaded at yesterday's official marks and checked against the clearing house, clocks synchronised. The trading process starts, replays its journal, reconciles order and quote status with every venue and only then enables quoting.
- **During the day.** RFQs arrive from D2C venues; the quoting engine prices them off the curve, skews for inventory and quotes within its limits. The hedger keeps residual DV01 inside its band with futures and benchmarks through the router. The trader watches the dashboard (live quotes, limit utilisation, stage latency) and changes parameters only through reviewed configuration. Alerts link to runbooks; the kill switch is one call away and is tested weekly.
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

## 15. Knowledge check

Planned for P6. A set of questions and worked answers covering Sections 1 to 14, written so a reader can test their understanding of the material and of the code that implements it.

## 16. Further reading

Planned for P6. Primary sources for each section: ISDA definitions and the ARRC and Bank of England conventions documents, QuantLib documentation, Agrona and SBE references, the regulatory texts named in Section 12, and the papers on coordinated omission and pre-registration.

## 17. Glossary

Terms defined in Phase 0 and Phase 1 (Sections 2 to 5). Later sections add to this list.

- **DV01**: the change in present value of a position for a one basis point parallel increase in the yield curve, usually quoted in currency per basis point. Sign convention: a payer swap (paying fixed) gains when rates rise.
- **PV01**: the present value of one basis point of coupon on the fixed leg, equal to notional times the annuity times 0.0001. For a par swap PV01 and DV01 are close but not identical; PV01 is the annuity measure, DV01 the sensitivity measure.
- **Par rate**: the fixed rate at which a swap has zero present value at inception.
- **OIS**: overnight index swap, a swap whose floating leg pays a compounded overnight rate (SOFR, EUR STR, SONIA).
- **SOFR**: Secured Overnight Financing Rate, the USD overnight rate based on Treasury repo transactions, published by the Federal Reserve Bank of New York each business day at about 08:00 New York time for the previous day.
- **EUR STR**: the euro short-term rate, the ECB's overnight unsecured wholesale borrowing rate for euro area banks, published at 08:00 CET for the previous day. Often typeset with a euro sign.
- **SONIA**: Sterling Overnight Index Average, the GBP overnight unsecured rate administered by the Bank of England, published at 09:00 London time for the previous day.
- **IMM date**: the third Wednesday of March, June, September and December, the standard delivery date for short-term interest rate futures and a common anchor for swap start dates.
- **RFQ**: request for quote, a dealer-to-client protocol in which a client asks a chosen set of dealers for a price in a given size and direction and picks one within a time limit.
- **Cover price**: in a multi-dealer RFQ, the second-best quote received by the client. A dealer who wins learns the cover and so learns by how much they beat the field.
- **Workup**: a protocol on dealer-to-dealer central limit order books in which, after a trade at a price, the two counterparties and then others may trade additional size at the same price for a short window.
- **CTD**: cheapest to deliver, the bond in a futures contract's deliverable basket that is most economical for the short to deliver, found by comparing implied repo rates or net basis.
- **Basis** (futures): the difference between a bond's cash price and the futures price times the bond's conversion factor. Gross basis ignores carry; net basis subtracts it.
- **Key-rate DV01**: the DV01 attributable to a one basis point move at a single tenor point of the curve, with other points held fixed. The set of key-rate DV01s is the ladder.
- **Skew**: the deliberate shift of a dealer's two-way quote away from mid to attract flow that reduces inventory, or to discourage flow that adds to it.
- **Markout**: the change in mid price a stated time after a trade, measured from the trade price, used to assess whether a fill was informed against the dealer.
- **Pre-registration**: writing down a hypothesis, the data to be used, the test and the success criterion before looking at the results, so that the result cannot be shaped by the analysis.
- **Holdout**: a portion of data set aside before research starts and used once, at the end, to confirm or reject a result found on the rest.
- **Journal**: the append-only record of every input event to the event loop, with sequence numbers and a hash chain, from which the system's state and outputs can be reproduced.
- **Flyweight**: an object that provides a typed view over a region of a byte buffer without copying the data, so that one instance can be re-pointed at many messages without allocation.
- **Discount factor**: \(P(0, T)\), the value today of one unit paid at \(T\).
- **Pillar**: a date at which a curve's value is a free parameter, one per bootstrap instrument.
- **Bootstrap**: solving for pillar values so that every input instrument reprices to its quote.
- **OIS discounting**: discounting collateralised cash flows on the curve of the overnight rate paid on collateral.
- **Projection curve**: a curve used only to forecast a term index, built with OIS discounting held fixed.
- **Meeting-dated OIS**: an OIS between two central bank policy effective dates.
- **Turn**: a known jump in the overnight rate over a short window such as year end.
- **Monotone convex interpolation**: the Hagan-West scheme keeping forwards continuous and between their neighbours.
- **Tension spline**: a spline between a cubic spline and linear interpolation, set by a tension parameter.
- **Compounded in arrears**: a floating amount equal to the compounded daily rate over the period, known at its end.
- **Payment lag**: business days between period end and payment, on the payment calendar.
- **Fixing calendar**: the days an index is published; kept apart from the payment calendar.
- **Lookback, lockout, observation shift**: variants of an overnight leg's observation window.
- **Stub**: an irregular first or last period of a schedule.
- **End-of-month rule**: a schedule starting on a month's last business day keeps every date on the last business day.
- **IMM swap**: a swap starting and ending on IMM dates.
- **Annuity**: the value of one unit of fixed rate paid on the fixed leg.
- **Algorithmic differentiation**: exact derivatives by the chain rule through the code's operations.
- **Clean and dirty price**: a bond price without and with accrued interest.
- **Accrued interest**: coupon interest earned since the last coupon date.
- **Ex-dividend period**: for gilts, seven business days before a coupon date with negative accrued interest.
- **Street yield** and **true yield**: yield on nominal coupon dates, and on the dates actually paid.
- **Modified duration**: minus the relative price change per unit change in yield.
- **Convexity** (bond): the relative second derivative of price in yield.
- **Repo**, **general collateral**, **special**: secured financing of a bond; the rate for any eligible bond; a bond financing below it.
- **Carry**: coupon income minus financing cost to a horizon.
- **Roll-down**: the price change from a bond's yield moving along an unchanged curve as it ages.
- **Z-spread**: the constant spread over the zero curve that reprices a bond.
- **Asset-swap spread**: the spread over the floating index for a par asset swap of a bond.
- **Nelson-Siegel-Svensson**: a six-parameter smooth zero curve.
- **Conversion factor**: a deliverable bond's price per unit face at the contract's notional coupon yield.
- **Net basis**: gross basis minus carry to delivery.
- **Implied repo**: the financing rate at which a cash-and-carry delivery breaks even.
- **Delivery option**: the futures short's choice of bond and delivery day.
- **Convexity adjustment** (futures): the excess of a daily-settled futures rate over the forward rate.
- **Smart order router**: the component that splits a parent order across venues and over time to minimise expected cost, and records why.
- **Parent and child orders**: the parent is the quantity to be worked; children are the orders actually sent to venues. The sum of the children never exceeds the parent.
- **Queue position**: the quantity resting ahead of an order at its price; with the flow and the cancellation rate it determines the fill probability of a passive order.
- **Escalation**: cancelling a passive child that has not filled within its time limit and sending the remainder as an aggressive order.
- **Participation cap**: the largest fraction of a venue's visible and expected liquidity one routing decision may take.
- **Anti-gaming**: randomising child sizes and timing so that other participants cannot infer and trade ahead of the next child.
