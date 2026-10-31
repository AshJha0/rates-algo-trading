# Learning rates e-trading through this repository

## How to read this document

Each section follows the same shape. First the plain idea, in a few sentences a newcomer can hold in their head. Then the formal statement, with the conventions and formulas a reviewer would check. Then a glossary definition for each new term, repeated in Section 17. Then one worked example with numbers, with the method stated so the number can be reproduced. Where a number comes from code in this repository, the file and test are named.

Only Sections 1 and 11 are written in full in Phase 0. Every other section states what it will cover and which phase writes it, because the material should be written alongside the code that exercises it. Where a section depends on code that does not exist yet, this document says so rather than describing the code as if it did.

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

The number is approximate. A production figure would bump the bootstrapped curve by one basis point, reprice both legs with the real schedule and day counts, and difference the two values; the result for a 10-year USD SOFR swap at 4 percent sits in the same region, around 80 to 85 thousand per basis point per 100 million, and moves with the level of rates because the annuity does. The swap pricer that computes this properly is planned for Phase 1, and Section 3 will replace this example with one that cites a golden test.

### Why this matters for the platform

Every one of the conventions above becomes a field in the securities master, a branch in the schedule generator and a test case against QuantLib. The venue simulators must quote in the right units (price for bonds, rate in basis points or percent for swaps). The risk engine must know that a one basis point move on a 10-year swap is about eighty thousand dollars per hundred million, and that a 30-year is roughly double. Getting the five-minute version right is the condition for getting the microsecond version right.

## 2. Discount curves and the multi-curve world

Planned for P1 (analytics). It will cover why OIS discounting replaced LIBOR discounting, how a curve is bootstrapped from OIS swaps and futures with stated interpolation, what a multi-curve setup still means in a post-LIBOR market (OIS discounting plus separate projection where credit-sensitive indices survive), and how the project's bootstrap is checked against QuantLib with named tolerances.

## 3. Swaps: schedules, PV, par rate, DV01

Planned for P1 (analytics). It will cover schedule generation with stubs and business-day conventions, compounding in arrears with payment lags, present value of each leg, the par rate, DV01 by curve bump and by annuity approximation, and the golden tests that pin each number.

## 4. Government bonds: price, yield, carry, repo

Planned for P1 (analytics). It will cover clean and dirty price, yield to maturity by root finding, accrued interest under Act/Act and Act/Act ICMA, ex-dividend handling for gilts, carry and roll-down, and repo financing with general collateral and special rates.

## 5. Futures: CTD, basis, convexity

Planned for P1 (analytics). It will cover Treasury futures contract specifications, conversion factors, the cheapest-to-deliver basket, gross and net basis, implied repo, the delivery option, and the convexity adjustment between futures and forwards.

## 6. How rates trade electronically: RFQ versus CLOB, SEFs, workup

Planned for P2 (venues). It will cover dealer-to-client request for quote with its timers and cover prices, dealer-to-dealer central limit order books with the workup protocol, swap execution facilities under Dodd-Frank, and how the project's venue simulators model each with declared parameters.

## 7. Dealer market making: inventory, skew, cover prices, winner's curse

Planned for P3 (quoting, risk, hedging). It will cover the economics of quoting, inventory-driven skew, how the cover price is observed and modelled, the winner's curse in multi-dealer RFQ, and the assumption band the project declares for win probability (DECISIONS.md DR-008).

## 8. Hedging and the DV01 ladder

Planned for P3 (quoting, risk, hedging). It will cover key-rate DV01, the ladder by tenor bucket, hedging swaps with futures and benchmark bonds, hedge ratios, residual risk, and the hedger's decision rules.

## 9. Smart order routing in fixed income

Planned for P4 (routing). It will cover venue selection for hedges and client fills, splitting across venues, the information cost of showing size, latency-aware routing, and the measurement of execution quality by markout.

## 10. Back-testing honestly: pre-registration, holdouts, attribution

Planned for P5 (back-testing). It will cover pre-registered hypotheses, holdout periods that are opened once, attribution of profit and loss into spread capture, inventory, hedge cost and skew, multiple-comparison discipline, and the replay harness that makes every run reproducible from the journal.

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

## 12. Regulation: UK RTS 6 (FCA MAR 7A), PRA SS5/18, MiFIR transparency, Dodd-Frank SEF rules

Planned for P6 (documentation and compliance). It will cover the organisational requirements for algorithmic trading under UK RTS 6 as applied by FCA MAR 7A (testing, kill functionality, change management, annual self-assessment), PRA SS5/18 expectations on governance and risk controls, MiFIR pre- and post-trade transparency for bonds and derivatives, and the Dodd-Frank SEF execution and reporting rules, each mapped to a control in this repository.

## 13. Pitfalls and numerical issues

Planned for P6. It will collect the mistakes found while building: day-count and calendar errors, stub handling, root-finding failures in yield solvers, interpolation artefacts in bootstraps, floating-point accumulation in compounded rates, int64 mantissa overflow, and the tests that now guard each.

## 14. How a desk would use this and how real desks differ

Planned for P6. It will describe a day in the life of a rates desk using the platform, and state plainly where the project is simplified: no live connectivity, no counterparty credit, no funding valuation adjustments, declared rather than fitted win probabilities, public data only.

## 15. Knowledge check

Planned for P6. A set of questions and worked answers covering Sections 1 to 14, written so a reader can test their understanding of the material and of the code that implements it.

## 16. Further reading

Planned for P6. Primary sources for each section: ISDA definitions and the ARRC and Bank of England conventions documents, QuantLib documentation, Agrona and SBE references, the regulatory texts named in Section 12, and the papers on coordinated omission and pre-registration.

## 17. Glossary

Terms defined in Phase 0. Later sections add to this list.

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
