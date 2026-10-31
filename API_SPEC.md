# API specification: the pinned, cross-module contract

This document is the contract between modules and between the Java and Python sides. Anything stated here is fixed for the schema or format version it names; a change needs a new version number, a decision record in `DECISIONS.md` and a migration note in `CHANGELOG.md`. Where code and this document disagree, the one that was reviewed last is wrong and the pull request that fixes it must say which.

Phase 0 pins Sections 0 to 4. Phase 1 pins Sections 5 to 8 (analytics). Phase 4 pins Section 15 (router). Phase 5 pins Section 16 (back-tester). Phase 6 pins Section 17 (post-trade and operations).

## Section 0. Units and conventions

| Quantity | Representation | Notes |
|---|---|---|
| Price or rate | `int64` mantissa with fixed exponent `-9` | A price of 99.123456789 is the mantissa 99123456789. A rate of 4.25 percent is quoted as 4.25 and stored as 4250000000. The exponent is a constant in the SBE schema (`priceExponent = -9`), never a per-message field. Range: about plus or minus 9.2 billion units, which covers any price per 100 of face and any rate in percent. |
| Quantity | `int64` | Swaps: notional in the currency's minor unit (USD cents, EUR cents, GBP pence). Bonds and futures: face value in the currency's minor unit. 100 million USD notional is 10,000,000,000. |
| Time stamps | `int64` epoch nanoseconds, UTC | From `Clock.epochNanos()`. Range exceeds year 2200. |
| Durations and intervals | `int64` nanoseconds | From differences of `Clock.nanoTime()`, which is monotonic and has no defined epoch. Never mix the two clocks in one subtraction. |
| Instrument id | `int32` | Assigned by the securities master (planned P1). Zero is never a valid id. |
| Sequence numbers | `int64`, 1-based, strictly increasing by 1 | Journal frames, order ids within a session, RFQ ids within a venue session. Zero means "none". |
| Side | enum `uint8` | 1 = Buy, 2 = Sell, from the client's perspective in RFQ and order messages. |
| Currency | 3-byte ASCII, ISO 4217 | Fixed-length `char[3]`. |
| Byte order | little-endian throughout | SBE schema `byteOrder="littleEndian"`; journal header and frame fields likewise. |

Floating-point values do not appear on the wire or in the journal. Analytics (P1) convert at the boundary and state the rounding rule when they do.

## Section 1. Journal file and frame format

Format name `RAJ1`. One journal is one or more segment files of equal size; Phase 0 implements a single segment.

### 1.1 File header (32 bytes, offset 0)

| Offset | Size | Type | Field | Value or meaning |
|---|---|---|---|---|
| 0 | 4 | `char[4]` | magic | ASCII `RAJ1` (bytes 0x52 0x41 0x4A 0x31; read as a little-endian int32 it is 0x314A4152) |
| 4 | 2 | `uint16` | formatVersion | 1 |
| 6 | 2 | `uint16` | headerLength | 32 |
| 8 | 4 | `int32` | capacityBytes | mapped size of this file in bytes, including the header; a reader refuses a buffer of any other size |
| 12 | 8 | `int64` | createdEpochNanos | `Clock.epochNanos()` when the segment was created |
| 20 | 12 | bytes | reserved | all zero; readers ignore, writers must write zero |

### 1.2 Frame (variable length, frames are contiguous from offset 32)

| Offset in frame | Size | Type | Field | Meaning |
|---|---|---|---|---|
| 0 | 4 | `int32` | frameLength | total bytes of this frame including this field; 0 marks end of data |
| 4 | 8 | `int64` | sequence | 1 for the first frame, then previous plus 1 |
| 12 | 8 | `int64` | timestampNanos | epoch nanoseconds supplied by the writer's caller |
| 20 | 2 | `uint16` | templateId | SBE template id of the payload |
| 22 | 2 | `uint16` | schemaVersion | SBE schema version the payload was encoded with |
| 24 | 8 | `int64` | chainHash | FNV-1a 64 of previous chainHash and this payload (1.3) |
| 32 | frameLength minus 32 | bytes | payload | the SBE message, header included, exactly as it was on the ring buffer |

The frame header is 32 bytes. `frameLength` is at least 32 and counts header plus payload without padding. The next frame starts at the current frame's offset plus `frameLength` rounded up to a multiple of 8, so every frame header is 8-byte aligned; the padding bytes are zero and are not hashed. `timestampNanos` is taken from the writer's `Clock.epochNanos()` at append time.

End of data is the first position at which the `int32` read is zero. A segment is zero-filled on creation so an unwritten region reads as end of data. A writer that cannot fit a frame in the remaining space writes nothing and reports the segment full; it does not write a partial frame.

### 1.3 Chain hash

The hash is FNV-1a, 64-bit:

- offset basis `0xcbf29ce484222325`
- prime `0x100000001b3`
- for each byte: `h = (h XOR byte) * prime`, modulo 2 to the 64

The input bytes for frame *n* are the 8 little-endian bytes of frame *n* minus 1's `chainHash`, followed by frame *n*'s payload bytes (offset 32 to `frameLength`). For the first frame the previous hash is 0, so the input begins with eight zero bytes. The frame header fields other than the previous hash are not hashed; sequence continuity is checked separately by the reader.

Java stores the result in a signed `long`; the bit pattern is what is compared. `research/ratesresearch/fnv.py` implements the same function and its tests (`research/tests/test_fnv.py`) check the definition independently.

### 1.4 Reader obligations

A reader must verify, for each frame: `frameLength >= 32`, `sequence == previous + 1`, `chainHash` equals the recomputed value. On any failure it stops and reports the sequence number of the failing frame (Section 4). A reader does not interpret the payload; that is the handler's job.

## Section 2. SBE schema v1

Source of truth: `rates-messages/src/main/resources/sbe/rates-schema-v1.xml`. Package `io.ratesalgo.messages.sbe`, schema id 1, version 0, semantic version 1.0.0, little-endian, all messages fixed length (no groups, no var-data) in v1. This section lists the messages so that other documents can refer to them; the XML wins on any difference.

Shared types: `Decimal9` is a composite of `mantissa` int64 and a constant `exponent` of -9; `Ccy` is `char[3]` ASCII; `Tenor` is `char[4]` ASCII, space padded; `EpochNanos`, `Quantity` and `SeqNo` are int64; `InstrumentId`, `PartyId` and `VenueId` are int32. Enums are uint8: `Side` (Buy 1, Sell 2), `BookAction` (Add, Change, Delete, Clear), `QuoteStatus` (Done, TradedAway, Expired, Rejected, Passed), `OrdStatus` (New 0, PartiallyFilled 1, Filled 2, Cancelled 4, Rejected 8), `OrdType` (Market, Limit), `TimeInForce` (Day 0, ImmediateOrCancel 3, FillOrKill 4, GoodTillDate 6), `CurveInputKind` (Fixing, Future, ParSwap, Bond), `LimitKind` (Position, BucketDv01, NetDv01, GrossDv01, DailyLoss, OrderRate, QuoteRate, MaxOrderSize, PriceCollar), `KillReason` (Manual, LimitBreach, FeedGap, SessionLoss, StaleReference). P4 adds the enums `VenueKind` (Clob 1, Futures 2), `RouteReason` (Slice 1, Escalation 2, BookMove 3, Retry 4, Deadline 5, Reconcile 6), `VenueExclusion` (None 0, NoQuote 1, SelfCross 2, BelowMinimum 3, CapReached 4, LimitPrice 5), `ChildState` (New 1, Acknowledged 2, PartiallyFilled 3, Filled 4, CancelRequested 5, Cancelled 6, Replaced 7, Rejected 8) and `TransitionCause` (Sent 1, Ack 2, Fill 3, CancelSent 4, CancelConfirmed 5, Rejected 6, CancelRejected 7, Reconciled 8). Template ids 80 to 89 are reserved for the router.

| Template id | Message | Fields (in order) |
|---|---|---|
| 1 | Heartbeat | `sourceId` int32, `timestampNanos` |
| 2 | BookUpdate | `venueId`, `instrumentId`, `venueSeqNo`, `side`, `action`, `level` uint8, `price` Decimal9, `quantity`, `orderCount` int32, `timestampNanos` |
| 3 | CurveInput | `curveId` int32, `kind` CurveInputKind, `instrumentId`, `tenor`, `value` Decimal9, `timestampNanos` |
| 10 | QuoteRequest | `venueId`, `rfqId` int64, `clientId`, `instrumentId`, `side` (the client's side), `quantity`, `currency`, `dealerCount` uint8, `receivedNanos`, `expiryNanos` |
| 11 | Quote | `venueId`, `rfqId`, `quoteId` int64, `instrumentId`, `bidPrice`, `askPrice`, `bidQuantity`, `askQuantity`, `referenceNanos` (newest input the price depends on), `validUntilNanos`, `sentNanos` |
| 12 | QuoteResponse | `venueId`, `rfqId`, `quoteId`, `status` QuoteStatus, `tradedPrice`, `tradedQuantity`, `coverPrice` (second best, when disclosed to the winner), `timestampNanos` |
| 20 | NewOrder | `venueId`, `clOrdId` int64, `parentId` int64 (router parent, 0 if none), `instrumentId`, `side`, `ordType`, `timeInForce`, `price`, `quantity`, `expireNanos`, `sentNanos` |
| 21 | ExecutionReport | `venueId`, `clOrdId`, `venueOrderId` int64, `execId` int64, `instrumentId`, `side`, `ordStatus`, `lastPrice`, `lastQuantity`, `leavesQuantity`, `cumQuantity`, `transactNanos` |
| 30 | RiskLimitBreach | `limitKind`, `scopeId` int32, `limitValue` Decimal9, `observedValue` Decimal9, `rejectedMessageSeqNo` (journal sequence of the rejected message, 0 if post-trade), `timestampNanos` |
| 31 | KillSwitch | `reason` KillReason, `scopeId` int32 (0 for the whole process), `requestedBy` PartyId, `timestampNanos` |
| 80 | RouteDecision | `decisionId` int64, `parentId` int64, `instrumentId`, `side`, `reason` RouteReason, `configVersion` int32, `venueCount` uint8, `sliceIndex` int32, `parentQuantity`, `sliceQuantity`, `allocatedQuantity`, `urgency` Decimal9, `passiveHorizonNanos` int64, `sliceIntervalNanos` int64, `arrivalMid` Decimal9, `expectedCost` Decimal9, `timestampNanos` (P4, Section 15.5) |
| 81 | RouteDecisionVenue | `decisionId`, `row` uint8, `venueId`, `kind` VenueKind, `bestBid`, `bestAsk`, `tick` Decimal9, `lotSize`, `minSize`, `sameSideDepth`, `oppositeDepth`, `tradeRate` Decimal9, `takerFee` Decimal9, `makerFee` Decimal9, `latencyNanos` int64, `participationCap`, `passiveAllowed` uint8, `aggressiveAllowed` uint8, `exclusion` VenueExclusion, `evaluatedQuantity`, `allocatedQuantity`, `passive` uint8, `fillProbability` Decimal9, `queueWaitNanos` int64, `impactCost`, `passiveCost`, `aggressiveCost`, `chosenCost` (all Decimal9) (P4) |
| 82 | ChildOrderTransition | `clOrdId` int64, `parentId` int64, `venueId`, `fromState` ChildState, `toState` ChildState, `cause` TransitionCause, `execId` int64, `orderedQuantity`, `cumQuantity`, `leavesQuantity`, `cancelledQuantity`, `timestampNanos` (P4) |

Every message is preceded on the wire by the standard SBE `messageHeader` (blockLength uint16, templateId uint16, schemaId uint16, version uint16), 8 bytes. `QuoteRequest` has block length 49, so its encoded length is 57 (COOKBOOK.md recipe 3).

Versioning rule: adding a field to the end of a message with `sinceVersion` increments the schema version and keeps template ids. Changing a field's type or position requires a new schema id and a new XML file; v1 is never edited in place once a journal written with it exists in a release.

### 2.1 Post-trade messages (P6, template ids 100 to 119)

Added by P6 to `rates-schema-v1.xml` without changing any existing message: new template ids only, schema version unchanged (DR-P6-01). Enums: `TradeSource` (Execution 1, Rfq 2), `BustReason` (VenueError 1, ClearingRejected 2, ErroneousTrade 3, Manual 4), `AmendReason` (PriceCorrection 1, QuantityCorrection 2, Reallocation 3).

| Template id | Message | Fields (in order) |
|---|---|---|
| 100 | PostTradeBust | `venueId`, `tradeSource`, `tradeId` int64 (execId for Execution, quoteId for Rfq), `venueOrderId` int64 (0 for Rfq), `instrumentId`, `reason` BustReason, `timestampNanos` |
| 101 | TradeAmend | `venueId`, `tradeSource`, `tradeId`, `newPrice` Decimal9, `newQuantity`, `amendedBy` PartyId, `reason` AmendReason, `timestampNanos` |

`PostTradeBust` covers every way a trade is cancelled after execution, including RFQ trades and clearing rejections; the P2 venue gateway's `TradeBust` (template 44, a FIX ExecType H from a venue) is mapped onto it at integration. Ids 102 to 119 are free for P6.

## Section 3. Clock and timer semantics

### 3.1 Clock

```java
public interface Clock {
    long nanoTime();    // monotonic, for intervals; no defined epoch
    long epochNanos();  // wall clock, UTC, for stamps
}
```

- `SystemClock.INSTANCE`: `nanoTime()` from `System.nanoTime()`; `epochNanos()` from `Instant.now()` to the precision the platform provides. `Instant.now()` allocates, so the event loop never calls it; stamps are taken on the gateway thread or read from inbound messages.
- `ManualClock(epochNanos)` starts wall time where the constructor says and monotonic time at a fixed, unrelated origin (1 000 000 000 ns), so code that mixes the two clocks is caught in tests. `ManualClock()` starts at 2026-01-01T00:00:00Z. `advance(nanos)` adds the same amount to both readings and rejects negative values; `setEpochNanos(t)` is `advance(t - epochNanos())`.

Intervals are always differences of `nanoTime()`. Stamps written to messages and journals are always `epochNanos()`. In replay the `Replayer` drives a `ManualClock` forward to each frame's `timestampNanos` (never backwards), so the replayed system sees the same stamps as the live one.

### 3.2 TimerWheel

`TimerWheel(tickNanos, wheelSize, initialCapacity, startNanos)`: tick length, number of slots (rounded up to a power of two), timers that can be live before the arrays grow, and the `nanoTime()` reading that is tick zero.

- `long schedule(long deadlineNanos, TimerHandler handler)`: registers a timer and returns an id that is unique for the life of the wheel and strictly increasing. Deadlines are in `nanoTime()` units.
- `boolean cancel(long id)`: removes a pending timer; returns false if it has fired, was already cancelled, or never existed.
- `int poll(long nowNanos)`: fires every pending timer with `deadlineNanos <= nowNanos`, in ascending deadline order and for equal deadlines in ascending id order, and returns the count. The handler receives `(timerId, nowNanos)`. The firing order does not depend on how often `poll` is called; `TimerWheelProperties` checks this for random schedules and poll steps.
- A timer never fires early. A timer whose deadline passed while the loop was busy fires on the next `poll` with that poll's `nowNanos`, so a handler can measure its lateness against the deadline it stored.
- Handlers may schedule and cancel while being fired; a timer scheduled from a handler fires no earlier than the next poll.

There is no scheduler thread. `schedule`, `cancel` and `poll` do not allocate once the wheel has grown to its working size; the P2 allocation job will assert this on the event loop.

## Section 4. Error behaviour

| Condition | Behaviour |
|---|---|
| Journal chain hash mismatch, sequence gap, `frameLength` below 32, or a frame running past the file | `JournalReader.next` and `Replayer.replay` throw `JournalCorruptionException` carrying the sequence number of the failing frame. Frames before it were delivered; none after it are. |
| Journal file header wrong (magic, format version, header length, or declared capacity not equal to the buffer size) | `JournalCorruptionException` with sequence 0 at open. |
| Zero `frameLength` | End of data, not an error. A crash that leaves a partial frame leaves its length zero, because the length is written last. |
| Journal full on `append` | `JournalFullException`; nothing is written and the writer stays usable. Segment rolling is planned for P2. |
| `JournalWriter.create` on an existing path, or a capacity too small for a header and one frame header | `IllegalArgumentException`. |
| Use of a closed `JournalWriter` | `IllegalStateException`; `close()` is idempotent. |
| SBE schema id or version mismatch on decode | The decoding site throws `IllegalStateException` naming expected and actual values. Handlers never decode a payload whose header does not match. |
| Timer handler throws | Every timer due in that `poll` still fires; the wheel then rethrows the first exception with the others attached as suppressed. The failed timer has been removed. |
| `ManualClock.advance` with a negative argument, or `setEpochNanos` earlier than now | `IllegalArgumentException`. Time does not go backwards. |

Exceptions carry enough to reproduce: sequence, expected and actual values. Nothing is logged and swallowed.

## Section 5. Market conventions

Module `rates-analytics`, package `io.ratesalgo.analytics`. Dates are `int` epoch days (days since 1970-01-01, `Dates`); `java.time` appears only in builders, tests and messages (DECISIONS.md DR-P1-01). Rates inside analytics are decimals (0.0425 for 4.25 percent); conversion to the int64 mantissas of Section 0 happens at the boundary, rounding half away from zero to the ninth decimal.

### 5.1 Calendars

| Name | Meaning | Rule source | Used as |
|---|---|---|---|
| `US-SIFMA` | US government securities business days | SIFMA holiday recommendations; QuantLib 1.44 `UnitedStates(GovernmentBond)` | Treasury settlement |
| `US-SOFR` | SOFR publication days: `US-SIFMA` without any Good Friday | NY Fed SOFR publication schedule; QuantLib `UnitedStates(SOFR)` | SOFR fixing calendar |
| `US-FED` | Federal Reserve Bank holidays, Saturday holidays not moved | federalreserve.gov; QuantLib `UnitedStates(FederalReserve)` | joint USD swap calendar |
| `US-FED+US-SIFMA` | joint | ISDA 2021 "USNY" and "USGS" business days | USD SOFR swap accrual and payment |
| `TARGET` | TARGET2 closing days | ECB; QuantLib `TARGET` | EUR STR fixing, EUR settlement |
| `LONDON` | England and Wales bank holidays | gov.uk; QuantLib `UnitedKingdom(Settlement)` | SONIA fixing, gilt and GBP settlement |

Calendars are `VersionedCalendar`s: a standing rule plus ad hoc changes, each with the date it was known (`asOf(date)` returns the calendar as known then). Recorded ad hoc changes: US 2004-06-11, 2012-10-30, 2018-12-05; UK 2011-04-29, 2022-09-19 (State Funeral of Queen Elizabeth II, known from 2022-09-10), 2023-05-08. `AnalyticsGoldenTest.calendarsMatchQuantLibEveryWeekday2000To2060` checks 79,575 weekdays against QuantLib 1.44.

### 5.2 Day counts and business-day conventions

`DayCount`: `ACT_360`, `ACT_365F`, `ACT_ACT_ISDA`, `ACT_ACT_ICMA` (with reference periods; QuantLib's `ActualActual(ISMA)` without a schedule), `THIRTY_360_US` (with the end-of-February rules), `THIRTY_E_360` (ISDA 2006 Definitions Section 4.16; ICMA Rule 251). `BusinessDayConvention`: `UNADJUSTED`, `FOLLOWING`, `MODIFIED_FOLLOWING`, `PRECEDING`, `MODIFIED_PRECEDING` (ISDA 2021 Section 4.12). Schedules: `Schedule.generate` with `StubRule` `SHORT_FRONT` (default), `LONG_FRONT`, `SHORT_BACK`, `LONG_BACK` and the end-of-month rule, as QuantLib `Schedule` with `DateGeneration::Backward` and `Forward`. IMM dates: third Wednesday of March, June, September, December (`ImmDates`).

### 5.3 Overnight index swaps (`SwapConvention`)

| Convention | Fixed leg | Floating leg | Calendars | Spot | Payment lag | Source |
|---|---|---|---|---|---|---|
| `USD_SOFR` | annual, Act/360 | SOFR compounded in arrears, annual, Act/360 | accrual and payment US-FED+US-SIFMA, fixing US-SOFR | T+2 | 2 business days | ISDA 2021 "USD-SOFR-OIS Compound"; ARRC conventions |
| `EUR_ESTR` | annual, Act/360 | EUR STR compounded, annual, Act/360 | TARGET | T+2 | 1 | ISDA 2021 "EUR-EuroSTR-OIS Compound" |
| `GBP_SONIA` | annual, Act/365F | SONIA compounded, annual, Act/365F | LONDON | T+0 | 0 | ISDA 2021 "GBP-SONIA-OIS Compound"; BoE RFR Working Group |

All: modified following, end-of-month rule off (DECISIONS.md DR-P1-09), short front stub. `withObservation(lookback, lockout, shift)` gives the lookback, lockout and observation-shift variants of ISDA 2021 Section 7.6. The EUR 6M projection instrument used in the multi-curve test is fixed annual 30E/360 against 6-month Act/360, TARGET, T+2.

### 5.4 Government bonds (`BondConvention`)

| Convention | Coupon | Day count | Settlement | Ex-dividend | Source |
|---|---|---|---|---|---|
| `UST` | semi-annual | Act/Act (ICMA) | T+1, US-SIFMA | none | 31 CFR Part 356 Appendix B; SIFMA |
| `BUND` | annual | Act/Act (ICMA) | T+2, TARGET | none | Finanzagentur issuance terms |
| `GILT` | semi-annual | Act/Act (ICMA) | T+1, LONDON | 7 business days, accrued negative | UK DMO "Formulae for Calculating Gilt Prices from Yields" (3rd ed., 2005) |

Coupon dates are rolled back from maturity, unadjusted, with the end-of-month rule when maturity is a month end. Treasury bills: bank discount basis and the Appendix B investment rate (`TreasuryBill`).

### 5.5 Futures

CBOT Treasury futures TU, Z3N, FV, TY, TN, TWE, US, UB with deliverable baskets and conversion factors per the CME contract specifications and "Calculating U.S. Treasury Futures Conversion Factors"; Eurex FGBS, FGBM, FGBL (6 percent notional, factor rounded to six decimals, Eurex contract specifications); ICE Long Gilt (4 percent, seven decimals). CME SOFR futures: SR1 (calendar month, arithmetic average) and SR3 (IMM to IMM, compounded), CME Rulebook Chapters 460 and 461.

## Section 6. Curve bootstrap

### 6.1 Representation

`DiscountCurve`: valuation date \(T_0\), pillars \(T_1 < \dots < T_N\), node values \(y_i = \ln P(0, T_i)\), \(y_0 = 0\), time \(t = (T - T_0)/365\). Interpolation (`Interpolation`): `LINEAR_LOG_DF`, `MONOTONE_CONVEX` (Hagan and West 2006, no positivity amendment), `TENSION_SPLINE` (exponential tension spline with natural ends, tension per year). Extrapolation past \(T_N\): the last segment's average forward. Turns: an extra simple overnight spread \(s\) over \([a, b)\) multiplies \(P(T)\) by \(\exp(-s \cdot \text{overlap}(a, b, T)/360)\).

### 6.2 Instruments and pillars

| Instrument | Implied quote | Pillar |
|---|---|---|
| `PeriodRateInstrument` (overnight fixing, deposit, meeting-dated OIS) | \((P(a)/P(b) - 1)/\tau(a, b)\) | \(b\) |
| `OisFutureInstrument` (SR1, SR3) | \(100(1 - R - CA)\), \(R\) as QuantLib `OvernightIndexFuture` | end of reference period |
| `OisSwapInstrument` | par rate of a spot OIS (Section 7.1) | last payment date on either leg |
| `TermSwapInstrument` (projection curve) | par rate with forwards on the target curve, discounting on the OIS curve | last accrual end |

One instrument per pillar; two instruments on the same pillar are rejected.

### 6.3 Solvers and tolerances

Sequential (Brent, local interpolation and no instrument past its pillar) or global (Levenberg-Marquardt with the analytic Jacobian, otherwise); `Method.AUTO` chooses (DECISIONS.md DR-P1-03). Stopping tolerance `CurveBuilder.SOLVER_TOLERANCE` = 1e-12 on \(|q_k - Q_k|\) in quote units; a build that misses it throws. `rebuild(k, q)` re-solves only from pillar \(k\) (sequential) or warm-starts the global solve. Asserted: every instrument reprices within 1e-10; incremental equals full rebuild within 1e-10 on discount factors.

### 6.4 Sensitivities

`DiscountCurve.accumulateLogDfGradient` gives \(\partial \ln P(T) / \partial y_i\) exactly for all three schemes. `CurveBuilder.quoteSensitivities(g)` solves \(J^{\top} x = g\) with \(J_{kj} = \partial q_k / \partial y_j\), turning a gradient in pillar values into one in quotes.

## Section 7. Pricing formulas and tolerances

### 7.1 Formulas

- OIS (`OisSwapPricer`): Section 3 of LEARN.md; fixings dated before today from `FixingHistory`, today's used if published else projected; cash flows paying on or before today excluded.
- Bonds (`BondPricer`): street and true yield as QuantLib `CashFlows::npv(leg, InterestRate)`; Treasury auction formula per Appendix B (simple first fraction); curve price as QuantLib `DiscountingBondEngine` settlement value; z-spread continuous on Act/365F curve time; par-par asset-swap spread.
- Carry (`BondCarry`), basis and implied repo (`BasisAnalytics`), conversion factors (`ConversionFactors`), Hull-White convexity (`HullWhite`, \(a = 0.03\), \(\sigma = 0.0085\)): LEARN.md Sections 4 and 5.
- Risk (`KeyRateRisk`): key-rate DV01 = \(x_k \times\) `quotePerRate` \(\times 10^{-4}\); parallel = sum. Bumped ladder: central, plus and minus one basis point, curve rebuilt incrementally.

### 7.2 Tolerances (asserted) and measured agreement with QuantLib 1.44

Measured by `rates-analytics` `AnalyticsGoldenTest` and the other named tests on 2026-10-31, JDK 25.0.5 on Windows 11, against `data/golden/analytics_golden.json` (224 priced instruments).

| Quantity | Asserted tolerance | Measured worst case | Test |
|---|---|---|---|
| Swap PV (83 swaps), QuantLib curve | 1e-8 of notional | 1.6e-15 of notional | `swapsMatchQuantLibOnBothCurves` |
| Swap PV on the Java bootstrap | 1e-8 of notional | 8.5e-15 of notional | same |
| Par rate | 1e-6 | 3.5e-16 | same |
| Curve discount factors, Java versus QuantLib-priced bootstrap | 1e-10 | 8.3e-16 | `javaBootstrapMatchesQuantLibCurvesAndRepricesInstruments` |
| Curve instrument repricing (round trip) | 1e-10 | 4.3e-14 (golden curves), 9.3e-14 (all interpolations) | same; `CurveBuilderTest` |
| Parallel DV01, AD versus QuantLib 1bp central bump | 1e-5 relative | 1.4e-6 relative | `parallelDv01ByAlgorithmicDifferentiationMatchesQuantLibBumps` |
| Key-rate sum versus parallel | 1e-9 relative | at rounding | same |
| Key-rate DV01, AD versus QuantLib bumps (USD, 30 pillars) | 1e-6 of the ladder's absolute sum | 9.3e-9 | `keyRateLaddersMatchQuantLibBumpsPillarByPillar` |
| Key-rate DV01, AD versus Java 1bp central bump | 1e-4 of parallel | 1.2e-8 (parallel 1.0e-7) | `bumpedLadderAgreesWithAlgorithmicDifferentiation` |
| Bond clean price and accrued (67 bonds) | 1e-9 per 100 | 3.3e-13 | `bondsMatchQuantLib` |
| Street and true yield | 1e-10 | 7.3e-16 | same |
| Duration, convexity, DV01 | 1e-10 relative | 4.2e-15 | same |
| SOFR futures price (10 contracts) | 1e-9 | 0 | `sofrFuturesMatchQuantLib` |
| Conversion factors (38) | equal at published rounding | equal | `conversionFactorsMatchQuantLibExactly` |
| Basis, carry, net basis, implied repo (5 baskets) | 1e-10 | 1.9e-14 | `basisImpliedRepoAndCtdMatch` |
| Treasury auctions 2024 (84 notes and bonds, 332 bills) | published digits | all equal | `PublishedExamplesTest` |
| Compounded indices 2024 (SOFR, EUR STR, SONIA) | last published digit | all equal | `CompoundedIndexHoldoutTest` |

## Section 8. Golden file and the risk interface

### 8.1 Producing the golden file

`python -m ratesresearch.goldens` (from `research/`, with `pip install -e "research[dev,golden]"`, QuantLib pinned at 1.44) writes `data/golden/analytics_golden.json`. The curves are bootstrapped in Python by a sequential Brent search in which every instrument is priced by QuantLib objects built with explicit schedules, calendars and conventions identical to Section 5; the market quotes for 15 January 2025 are declared values (DECISIONS.md DR-P1-08); past fixings are the published ones in `data/fixtures`. `research/tests/test_goldens.py` regenerates the file and compares every number to 1e-12 relative (absolute floor 1e-12); CI runs it on Linux and Windows (DECISIONS.md DR-P1-06).

### 8.2 Format (golden_version 1)

Top level: `golden_version`, `description`, `quantlib_version`, `python_version`, `platform`, `valuation_date`, `hull_white` {`a`, `sigma`}, `instrument_count`, and:

| Key | Content |
|---|---|
| `calendars` | name to the list of weekday holidays, 2000 to 2060, ISO dates |
| `fixings` | currency to {`dates`, `rates`} (decimal), 2024-03-01 to 2025-01-14 |
| `curves` | id, currency, `discount` (id or null), interpolation, `instruments` (kind, label, tenor, quote, pillar, and kind-specific fields), `pillar_dates`, `discount_factors` |
| `swaps` | id, currency, kind, start, unadjusted end, notional, fixed_rate, payer, lookback, lockout, observation_shift, pv, par_rate, fixed_leg_bps, dv01_parallel_bump |
| `key_rate_ladders` | swap id, tenors, key_rate_dv01 (central bumps) |
| `sofr_futures` | code, kind, year, month, convexity, start, end, price |
| `bonds` | id, market, coupon, dated, maturity, trade, settlement, yield, clean, dirty, accrued, street_yield, true_yield, modified_duration, convexity, dv01, and for Treasuries dirty_on_curve and z_spread |
| `conversion_factors` | exchange, contract, delivery month or day, coupon, dated, maturity, factor |
| `basis` | contract, futures_price, repo, settlement, delivery, bonds (clean, conversion_factor, gross_basis, carry, net_basis, implied_repo), ctd_index |

Values are JSON numbers written by Python's shortest round-trip representation; Java reads them with `Double.parseDouble`.

### 8.3 `Dv01Ladder` (consumed by the P3 risk engine)

```java
package io.ratesalgo.analytics.risk;

public final class Dv01Ladder {
  public Dv01Ladder(String currency, List<String> tenors, double[] dv01, double parallel);
  public String currency();          // ISO 4217
  public List<String> tenors();      // curve instrument labels in pillar order, e.g. "2Y", "SR3202603"
  public int size();
  public double dv01(int i);         // key-rate DV01 of pillar i
  public double[] keyRates();        // copy
  public double parallel();          // parallel DV01
  public double keyRateSum();
  public Dv01Ladder scaled(double factor);
  public Dv01Ladder plus(Dv01Ladder other);  // same currency and tenors required
}
```

Units: currency units per basis point. Sign: positive when the position gains as rates rise (a payer swap, a short bond or future). Immutable; constructor rejects mismatched lengths and non-finite values. Producers: `KeyRateRisk.fromGradient` (swaps through `OisSwapPricer.pvGradient`, bonds through `BondPricer.dirtyFromCurveGradient` times face over 100, futures as the CTD's ladder divided by its conversion factor times contract face over 100) and `KeyRateRisk.byBumping`.

## Section 15. Smart order router (P4)

Module `rates-router`, package `io.ratesalgo.router`. Units follow Section 0: prices and fees in price points (per 100 of face), stored on the wire as Decimal9 mantissas; quantities in minor units of face; times in nanoseconds. Costs are per unit of quantity, in price points, positive when paid.

### 15.0 Interfaces the coordinator binds

| Interface | Direction | Contract |
|---|---|---|
| `ParentOrderSink.submit(parentId, instrumentId, side, quantity, urgency, limitPrice)` | in | Parent orders. The P3 `HedgeOrderSink` (rates-api) is bound to it. `quantity` is a positive multiple of the largest venue lot; `urgency` in [0, 1]; `limitPrice` 0 for none. `SmartOrderRouter` implements it. |
| `BookView` | in | `bestBid`, `bestAsk`, `depth(venue, side, level)`, `tradeRatePerSecond(venue)` per venue for the routed instrument. Bound to P2 market state. Must not allocate. |
| `OrderGateway` | out | `newOrder` (fields of NewOrder, template 20) and `cancel(venue, clOrdId)`. Bound to the P2 gateway. Must not call back into the router synchronously. |
| `ExecutionListener` | in | `onExecutionReport` (fields of template 21), `onCancelReject`, `onBookUpdate`. Called on the router's thread. |
| `FrameSink` | out | `append(templateId, schemaVersion, buffer, offset, length)`; bind to `JournalWriter::append`. |
| `SmartOrderRouter.reconcile(venueId, statuses)` | in | Mass order status after a reconnect (15.4). |

`io.ratesalgo.router.sim.SimMarket` implements `BookView` and `OrderGateway` for tests and the back-test until the P2 simulators are bound.

### 15.1 Cost model

Parameters, config version 1 (`RouterParams`, `VenueParams`; defaults in `RouterPresets` and `data/presets.yaml` key `router`, checked equal by `RouterPresetsTest`):

| Parameter | Symbol | Unit | Range | Default |
|---|---|---|---|---|
| `sigmaDailyPoints` | \(\sigma_d\) | price points per day | [1e-6, 10] | 0.5 |
| `tradingSecondsPerDay` | \(D\) | s | [3600, 86400] | 23400 |
| `impactY` | \(y\) | 1 | [0, 5] | 0.6 |
| `impactAlpha` | \(\alpha\) | 1 | (0, 1] | 0.5 |
| `adverseFactor` | \(k_A\) | sigma over \(T\) | [0, 5] | 0.5 |
| `latencyRiskFactor` | \(k_L\) | sigma over latency | [0, 10] | 1.0 |
| `passiveHorizonMaxNanos` | \(T_{max}\) | ns | [0, 600 s] | 30 s |
| `passiveHorizonMinNanos` | \(T_{min}\) | ns | [0, \(T_{max}\)] | 1 s |
| `maxSlices` | \(N\) | 1 | [1, 64] | 8 |
| `scheduleHorizonNanos` | \(H\) | ns | [0, 3600 s] | 120 s |
| `sizeJitter`, `intervalJitter` | \(j_q, j_t\) | fraction | [0, 0.5] | 0.25, 0.3 |
| `bookMoveThresholdTicks` | \(m\) | ticks | [1, 100] | 2 |
| `aggressiveCollarTicks` | \(k\) | ticks | [0, 100] | 8 |
| `retryDelayNanos` | | ns | (0, 60 s] | 0.5 s |
| `maxSteps` | | 1 | [1, 1024] | 32 |
| venue `takerFee`, `makerFee` | \(f_t, f_m\) | price points per unit | [0, 1], [-1, 1] | CLOB 0.0005, 0.0002; futures 0.0015, 0.0015 |
| venue `latencyNanos` | \(\ell\) | ns | [0, 1 s] | CLOB 250 us; futures 500 us |
| venue `cancelRatePerSecond` | \(\theta\) | per unit ahead per s | [0, 10] | CLOB 0.05; futures 0.03 |
| venue `adv` | ADV | quantity per day | > 0 | CLOB 30 bn face; futures 1.5 m contracts |
| venue `participationCap` | \(\rho\) | fraction | (0, 1] | CLOB 0.25; futures 0.2 |
| venue `lotSize`, `minSize`, `tickMantissa`, `depthLevels` | | as named | | CLOB 1 m, 1 m, 1/64, 5; futures 1 contract, 1 contract, 1/64, 5 |

For row \(v\) of a decision and quantity \(q\), with bid \(b\), ask \(a\), same-side displayed depth \(Q\), trade rate \(r\) (quantity per second, both sides), lot \(\lambda\), passive horizon \(T\) (0 when passive is not allowed) and \(\sigma_s = \sigma_d/\sqrt{D}\):

\[
h = \tfrac{a-b}{2},\quad I(q) = y\,\sigma_d\,(q/\mathrm{ADV})^{\alpha},\quad L = k_L\,\sigma_s\sqrt{\ell},\quad A = k_A\,\sigma_s\sqrt{T}
\]
\[
c_{aggr}(q) = h + I(q) + f_t + L,\qquad c_{pass}(q) = P\,(-h + I(q) + f_m) + (1-P)\,(A + h + I(q) + f_t) + L
\]

where \(P\) is `QueueFillModel.probability(ceil(Q/λ), ceil(q/λ), r/(2λ), θ, T)` (complete-fill probability, Cont, Stoikov and Talreja 2010, uniformisation as in the owner's earlier library, API_SPEC.md Section 13.1 there; tolerance 1e-10 against the Poisson tail when \(\theta = 0\), `CostModelTest`). The chosen cost is \(c_{pass}\) if passive is allowed and either aggressive is not allowed or \(c_{pass} < c_{aggr}\); otherwise \(c_{aggr}\). The expected queue wait reported is \((\lceil Q/\lambda\rceil + \lceil q/\lambda\rceil)/(r/2\lambda)\), capped at 1e6 s. All doubles are rounded to Decimal9 with `Math.round(x * 1e9)` when written to the table; inputs that enter the model as doubles (trade rate, fees) are taken from the rounded table values, so a decision re-evaluated from the journal reproduces every mantissa.

### 15.2 Routing objective and allocation

A decision allocates a slice \(S\) to minimise

\[
\sum_v q_v\, c_v(q_v)\quad\text{subject to}\quad \sum_v q_v \le S,\; q_v \in \{0\} \cup [\mathrm{minSize}_v, \mathrm{cap}_v],\; q_v \equiv 0 \pmod{\lambda_v}
\]

with \(\mathrm{cap}_v = \lfloor \rho_v (\text{opposite depth over depthLevels} + r_v \cdot \text{sliceInterval}) \rfloor\) rounded down to the lot. The increment is the largest lot (every lot must divide it), coarsened so that \(S\) takes at most `maxSteps` increments. Greedy: each step gives the next block (the first block of a venue is its minimum size rounded up to the lot) to the venue whose total cost rises least per unit; ties to the lower row. For convex per-venue totals without minimum sizes this is optimal on the increment lattice (`CostModelTest.greedyMatchesBruteForceForConvexCosts` checks it against enumeration for 1 to 20 lots); with minimum sizes it is a heuristic (DR-P4-04). Unallocated quantity is retried after `retryDelayNanos`.

Eligibility per row, before allocation: no two-sided quote gives `NoQuote`. The aggressive limit is the far touch plus \(k\) ticks, capped one tick inside our own most aggressive live order on the other side of that venue (resting or in flight) and at the parent limit; aggressive is allowed only if that limit still reaches the far touch. Passive joins the near touch and is allowed only if \(T > 0\), the join price does not reach our own opposite order, and it respects the parent limit. A row with neither is `SelfCross` (if we have an opposite order there) or `LimitPrice`. The allocator marks `BelowMinimum` and `CapReached`.

### 15.3 Schedule, escalation, re-routing and anti-gaming

At submit, with patience \(p = 1 - u\): slices \(n = 1 + \mathrm{round}((N-1)p)\), schedule \(H_u = \mathrm{round}(H p)\), interval \(H_u/n\), passive horizon \(T_u = \mathrm{round}(T_{max} p)\) set to 0 below \(T_{min}\), deadline at \(H_u + T_u\) + retry delay. Slice sizes are \(b(1 + j_q(2U - 1))\) with \(b\) = remaining over slices left, rounded to the increment and clamped to [increment, remaining]; the last slice takes the rest. Intervals are jittered the same way with \(j_t\). \(U\) comes from the injected `RandomSource`.

| Event | Action | Decision reason |
|---|---|---|
| slice timer | allocate the next slice | Slice |
| passive child reaches \(T_u\) | cancel; on confirmation route the freed quantity aggressively | Escalation |
| touch moves \(m\) ticks or more away from a resting child | cancel; on confirmation route the freed quantity, passive allowed | BookMove |
| IOC remainder cancelled, reject, or quantity unallocated | retry after the delay | Retry |
| deadline | cancel every resting child, route everything aggressively from now on | Deadline |
| reconciliation releases quantity | route it, passive allowed | Reconcile |

Quantity is re-routed only when the venue has confirmed it will not fill. Parent accounting: quantity = filled + outstanding (sum of child leaves) + unrouted, each non-negative, checked after every step by `RouterChaosPropertiesTest`.

### 15.4 Child order state machine

`ChildOrderBook`. Quantities satisfy cum + leaves + cancelled = ordered after every report, where cancelled is quantity the venue has confirmed will never fill and leaves is quantity that may still fill (working, or filled at the venue with the report still in flight). This is the precise form of "leaves plus cumulative equals ordered": for a cancelled order the cancelled quantity is accounted for explicitly. Fills are counted once per (venueId, execId); a fill that would take cum above ordered is refused as an anomaly.

| From | Report or action | To | Effect |
|---|---|---|---|
| (none) | router sends | New | audit Sent |
| New | ER New | Acknowledged | |
| New, Acknowledged, PartiallyFilled | ER with a new fill, cum below ordered | PartiallyFilled | cum += lastQty |
| any | ER with a new fill, cum reaches ordered | Filled | cancelled = 0 |
| New, Acknowledged, PartiallyFilled | router cancels | CancelRequested | remembers the prior state and whether to re-route |
| CancelRequested | ER with a new fill | CancelRequested (Filled if complete) | the fill raced the cancel |
| New, Acknowledged, PartiallyFilled, CancelRequested | ER Cancelled | Cancelled, or Replaced if the cancel was for re-routing | cancelled = ordered - max(cum, venueCum); no change if that is 0 |
| CancelRequested | cancel reject | prior state (PartiallyFilled if anything filled) | |
| New, Acknowledged | ER Rejected | Rejected | cancelled = ordered - max(cum, venueCum) |
| Cancelled, Replaced | ER with a new fill | unchanged | late fill: cum grows, leaves shrinks |
| any | duplicate fill (seen execId) | unchanged | counted as duplicate |
| any | status that would move backwards (New after a fill, Cancelled after Filled) | unchanged | counted as stale |

venueCum is the largest cumQuantity any report carried. A slot is released when the state is terminal and leaves is 0; later reports for it count as unknown. `reconcile(venueId, statuses)`: a live child the venue does not list is closed as cancelled; Cancelled or Rejected closes it; Filled raises venueCum to ordered (the fills are pending); orders the venue lists as working that we do not hold live are cancelled. Every state change and every applied fill writes a ChildOrderTransition (template 82).

Properties (jqwik, `ChildOrderBookPropertiesTest`, 400 tries; `RouterChaosPropertiesTest`, 25 tries against `SimMarket` with delayed, duplicated and reordered reports): no lost quantity (final cum equals the venue's filled quantity), the identity above after every report, cum never above ordered, the venues never fill more than the parent, every parent completes, no anomaly and no self-cross.

### 15.5 Decision messages and reconstruction

One decision is one RouteDecision followed by `venueCount` RouteDecisionVenue frames with rows 0 to venueCount - 1, written to the `FrameSink` before any child order of that decision is sent. Fields are listed in Section 2; together they are exactly `CostTable`. `DecisionCodec.onFrame` rebuilds the table from journal frames and throws `IllegalStateException` on a row out of sequence or a schema mismatch. `DecisionReplayTest` writes a real journal, replays it with `Replayer`, checks that every rebuilt table equals the router's own field by field, and re-runs `Allocator.allocate` on the journaled inputs with the parameters of the journaled config version, reproducing every cost mantissa and the split. The parameters not carried in the journal (\(\sigma_d\), \(y\), \(\alpha\), \(k_A\), \(k_L\), ADV, \(\theta\)) are fixed by `configVersion`.

The messages keep v1's fixed-length rule (a decision is a header plus rows instead of a repeating group) and the schema version stays 0, because they are additions with new template ids (DR-P4-01).

### 15.6 Back-test

`BacktestHarness` runs strategies over seeded synthetic days produced by `SimMarket`; strategy 0 is compared with each other by day. Cost of a parent = side x (average fill price - mid of venue 1 at arrival) + fees per unit, in bps of the arrival mid; cost of a day = quantity-weighted mean over its parents. Interval: moving-block bootstrap of the mean daily saving (`BlockBootstrap`, Kuensch 1989), percentile 95 percent.

Scenario `ust10y_three_venue_v1` (`BacktestScenario.ust10yThreeVenuesV1`, `SimConfig.ust10yThreeVenues`), assumptions:

- One 10-year Treasury on three cash CLOBs, tick 1/64, lot 1 million, starting bid 98.50 (6304 ticks).
- Venue 1: spread 1 tick, depth 12 + 6k lots at level k, 1.0 one-lot market orders per second each side, latency 250 us, fees 0.0005 taker and 0.0002 maker. Venue 2: 1 tick, 6 + 3k lots, 0.5 per second, 400 us, 0.0004 and 0.0001. Venue 3: 2 ticks, 4 + 2k lots, 0.3 per second, 600 us, 0.0003 and 0.
- The common price moves one tick up or down at 0.0219 per second each way (about 0.5 points per day of volatility), every book is rebuilt around it; each lot ahead of our resting orders cancels at 0.05 per second; depleted depth refills at 20 percent per second; our aggressive volume moves the price permanently by 0.02 ticks per lot. No informed flow other than the price moves; resting orders fill from the queue or when the price moves through them.
- 40 days, 15 parents a day, 10 to 60 lots, side random, 30 s apart, seed 20261031 + day; router urgency 0.5 with the default parameters and venue parameters derived from the market (`SimConfig.routerVenues`: same fees and latencies, ADV = 2 x arrival rate x 23400 s, participation cap 0.25, 5 levels).
- Baselines (`AggressiveBaseline`): single-venue aggressive sends the whole parent to venue 1 as an IOC at the far touch plus 8 ticks; even split sends equal lots to the three venues the same way; both resend any remainder after 0.5 s.
- Block length 5 days, 2000 resamples.

Result (`BacktestTest.routerBeatsBothBaselinesInTheShippedScenario` (rates-router), run 2026-10-31 on JDK 25.0.5, Windows 11):

| Strategy | Mean cost (bps of price) | Mean saving of the router (bps) | 95% block-bootstrap interval |
|---|---|---|---|
| router | -0.1957 | | |
| single-venue aggressive | 2.6822 | 2.8780 | [2.7210, 2.9855] |
| even split aggressive | 2.5108 | 2.7065 | [2.5309, 2.8272] |

The gate: the lower end of each interval exceeds 1.0 bps (`BacktestTest.MARGIN_BPS`). The router's cost is below zero because most of its quantity rests and earns part of the spread in a market whose only adverse selection is the random walk; the number measures the router's logic in this declared world and is not a forecast of desk savings. Refitting the impact and queue parameters from P2 simulator data is deferred (DR-P4-03).

### 15.7 Error behaviour

| Condition | Behaviour |
|---|---|
| Parameter outside its range | `IllegalArgumentException` naming the parameter and value, at construction |
| Parent quantity not a positive multiple of the largest lot, urgency outside [0, 1], negative limit, side not Buy or Sell, duplicate live parent id | `IllegalArgumentException` from `submit`; nothing is sent |
| Parent or child capacity exhausted | `IllegalStateException` |
| Report for an unknown clOrdId, duplicate fill, stale status | ignored and counted (`unknown`, `duplicates`, `stale`) |
| Overfill, venue mismatch, execId at or above 2^43, venueId at or above 2^20, null status | refused and counted as an anomaly |
| RouteDecisionVenue out of sequence, or wrong schema id, on decode | `IllegalStateException` |

## Section 16. Back-tester, outcome models, pre-registration, ledger and report (P5)

Module `rates-backtest`, package `io.ratesalgo.backtest`; Python twins in `research/ratesresearch` (`presets`, `stats`, `prereg`, `backtest_analysis`, `markouts`, `spread`, `hitrate`, `flow`). P5 adds no SBE messages: the back-tester reads and writes schema v1 templates 2, 3, 10, 11, 12, 20 and 21, and template ids 90 to 99 stay unused.

### 16.1 Strategy interface and runner

```java
public interface Strategy {
    String name();
    default void onStart(StrategyContext ctx) {}
    void onQuoteRequest(QuoteRequestDecoder rfq, StrategyContext ctx);
    default void onBookUpdate(BookUpdateDecoder update, StrategyContext ctx) {}
    default void onCurveInput(CurveInputDecoder input, StrategyContext ctx) {}
    default void onQuoteResponse(QuoteResponseDecoder response, StrategyContext ctx) {}
    default void onExecutionReport(ExecutionReportDecoder report, StrategyContext ctx) {}
    default void onTimer(long timerId, long nowEpochNanos, StrategyContext ctx) {}
}
```

`StrategyContext` gives `nowEpochNanos()`, `instruments()`, `hasMid(id)`, `mid(id)`, `bookHalfSpread(id)`, `position(id)`, `netDv01()`, `hitRateModel()` (central band point), `calendar()`, and the three actions `quote(rfqId, bidMantissa, askMantissa)`, `sendOrder(instrumentId, side, ordType, quantity, priceMantissa, hedge)` and `scheduleTimer(epochNanos)`. Decoders are flyweights valid only during the call. A strategy must be deterministic given its inputs.

`BacktestRunner.run(strategy)` replays `BacktestConfig.journals` in order as one stream. Rules:

| Rule | Contract |
|---|---|
| Frame reading | `JournalReader` verifies the chain; a corrupt journal throws `JournalCorruptionException` (Section 4). A frame whose SBE header is not schema 1 version 0 throws `IllegalStateException`. Timestamps going backwards throw. |
| Clock | A `ManualClock` is set to each frame's `timestampNanos` and to each internal event's time. |
| Internal events | RFQ outcomes at quote time plus `clientDecisionNanos`; order arrivals at send time plus `orderLatencyNanos`; strategy timers. Ordered by time, then by scheduling order; all events due at or before a frame's time run before the frame. |
| Quotes | Sent at receipt plus `quoteLatencyNanos`; refused after the RFQ's `expiryNanos`, if crossed, or if quoted twice. Only the client's side is settled. Not quoting is a decline. |
| Mids | Swaps: `CurveInput` of kind `ParSwap`. Bonds and futures: mid of the level-0 bid and ask of `BookUpdate` once both exist. |
| Outputs | Quote, QuoteResponse, NewOrder and ExecutionReport are SBE-encoded and folded into an FNV-1a chain hash (`outputChainHash`, the determinism fingerprint); with an output directory they are also appended to `results.raj`. |
| Client tier | `1 + (clientId - 1) / clientsPerTier`. |

Defaults from presets (`BacktestConfig.fromPresets`): quote latency 5 ms, client decision 1.5 s, order latency 2 ms, markout horizons 1 s, 10 s, 60 s, 300 s.

### 16.2 Preset schema (`data/presets.yaml`)

The file is a contract between the Python writers (`ratesresearch.presets.write_preset`) and the Java readers (`io.ratesalgo.backtest.model.Presets`, the P3 quoting engine). Each preset:

| Key | Type | Meaning |
|---|---|---|
| `name` | string | identifier used in code |
| `fitted_from` | string | manifest path under `data/manifests/`, a label-set description, or `assumption` |
| `data_range` | `{start, end}` quoted ISO dates | range of the source data |
| `sample_size` | integer | observations used (0 for assumptions) |
| `method` | string | estimator and the function that ran it |
| `parameters` | map name to `{value: number, standard_error: number or null}` | null when no standard error exists |
| `assumptions` | optional map name to `{band: {low, central, high}, rationale}` | declared assumption bands (DR-008, DR-P5-01) |
| `notes` | optional string | caveats |

Presets written in P5: `usd_rfq_flow` (fitted from DTCC, Section 16.5), `usd_rfq_hit_rate` (fitted to synthetic labels, with the `spread_to_cover_bp` band), `usd_rfq_competition`, `usd_synthetic_market`, `usd_hedge_cost` (declared assumptions).

### 16.3 Outcome models

| Model | Formula | Preset |
|---|---|---|
| Hit rate | \(P = 1/(1+e^{-z})\), \(z = b_0 + b_1 s + b_2 \ln q + b_3 T + b_4 F + b_5 n\); \(s\) spread-to-cover in bp, \(q\) size in millions, \(T\) tenor years, \(F\) fixing-window flag, \(n\) dealers; \(b_1\) from the band point of the run | `usd_rfq_hit_rate` |
| Competitors | each of \(n-1\) dealers quotes \(\text{median}\cdot e^{\sigma Z}\) bp, median \(= m_0 (T/10)^{a} (q/50)^{c}\) (times `bond_multiplier` for bonds); cover = minimum | `usd_rfq_competition` |
| Settlement | stream `SplitMix64.forStream(seed, rfqId)`: first draw \(u\), then the competitors; won if \(u < P\). Won: trade at our price, `QuoteResponse` Done with `coverPrice` = mid plus or minus the cover. Lost: TradedAway at the cover price, `coverPrice` 0. | |
| Hedge cost (market orders) | fill = mid plus or minus (book half-spread + min(maxImpact, impactPerLot x lots) ticks), rounded to tick against us; fee = feePerLot x lots | `usd_hedge_cost` |
| CLOB queue (limit orders) | ahead = displayed size at our price on joining; each decrease of that level reduces ahead by `queue_trade_share` times the decrease; fill in full when ahead reaches zero, when the opposite best crosses our price, or when our level is traded through. No partial fills. | `usd_hedge_cost` |

Spread distance in bp is price distance divided by the absolute price change per bp (0.01 for swaps quoted in percent; modified duration times price times 1e-4 for bonds and futures).

### 16.4 Attribution

Valuation is linear in the quoted price: position \(Q\) minor units at mid \(m\) is worth \(Q m v\) in major units, \(v\) = annuity x 1e-4 for swaps (long = pay fixed), 1e-4 for bonds and futures. Lines, in major currency:

| Line | Definition |
|---|---|
| `spread_capture` | sum over client trades of \(q(m - p)v\), \(q\) our signed quantity, \(p\) trade price, \(m\) mid at the trade |
| `inventory_mtm` | sum over mid changes of \(Q(m' - m)v\) |
| `hedge_cost` | sum over hedge trades of \(q(m - p)v\) |
| `carry_roll` | `CarryModel` hook; zero until P1 |
| `funding` | \(-Q m v \cdot r \cdot \Delta t / 360\) days, accrued at each event; \(r\) = instrument funding rate (bonds 4.25 percent, others 0) |
| `fees` | minus fees paid |
| `residual` | `position_pnl` minus the sum of the lines above |
| `position_pnl` | cash (all trade, fee, funding and carry flows) plus \(Q m v\) at the last mid |

Tolerance: \(|\text{residual}| < 0.01\) on every run, per instrument and in total (`AttributionReconciliationTest`, `ratesresearch.backtest_analysis.reconcile`).

### 16.5 Synthetic sessions

`SyntheticDay.generate(date, seed, journal, truthCsv)`; experiments use seed = `day_seed_base + date.toEpochDay()` (5,000,000 in P5-001). Session 07:00 to 17:00 New York at a fixed UTC offset (`usd_rfq_flow.utc_offset_hours`, -5 for the February sessions). Contents: one `CurveInput` Fixing for the SOFR index (instrument 100) at the open; `CurveInput` ParSwap for 101 to 104 every `curve_input_interval_s`; level-0 `BookUpdate` pairs (bid then ask, action Change, venue 2) for 201, 202, 301 at Poisson times; `QuoteRequest` on venue 1 for 101 to 104, 201, 202 with `rfqId = epochDay x 1,000,000 + n`, expiry 30 s, dealer count uniform 2 to 5. RFQ intensity per minute at minute \(m\) is `rfq_share_of_market x (1 + bond_rfq_ratio) x exp(b0 + sum_k a_k exp(-(m - m_k)^2 / 2w^2))`. Instrument ids: 101 to 104 SOFR 2Y, 5Y, 10Y, 30Y; 201 UST 2Y; 202 UST 10Y; 301 TY. The truth file has columns `rfq_id,instrument_id,client_id,informed`.

### 16.6 Statistics

| Function | Definition |
|---|---|
| `blockBootstrapMeanCi(x, L, B, level, seed)` | B resamples; each draws block starts uniformly from 0 to n - L with `nextInt(n - L + 1)` and concatenates blocks of L until n values; percentile interval of the B means, type 7 quantiles |
| `welch(a, b)` | \(t = (\bar a - \bar b)/\sqrt{s_a^2/n_a + s_b^2/n_b}\), Welch-Satterthwaite df, two-sided p \(= I_{df/(df+t^2)}(df/2, 1/2)\) by Lentz's continued fraction and Lanczos log-gamma |
| `holm(p)` | sort ascending, multiply the k-th smallest (0-based) by m - k, running maximum, cap at 1 |

Java and Python agree on `data/fixtures/backtest/stats_crosscheck.json`: exactly for the bootstrap and generator, to 1e-12 for Welch.

### 16.7 SplitMix64

`state += 0x9E3779B97F4A7C15`; output `mix(state)` with `z = (z ^ z>>>30) * 0xBF58476D1CE4E5B9; z = (z ^ z>>>27) * 0x94D049BB133111EB; z ^ z>>>31`. `nextDouble = (nextLong >>> 11) * 2^-53`; `nextInt(b) = (nextLong >>> 1) mod b`; `forStream(seed, key) = new SplitMix64(mix(seed ^ mix(key + golden)))`. `nextGaussian` (Box-Muller) is reproducible within the JVM only.

### 16.8 Pre-registration and ledger

Pre-registration YAML, all keys required: `prereg_version` (1), `id`, `title`, `hypothesis`, `metric`, `baseline`, `treatments` (list), `period` and `fitting_period` (`{start, end}`), `holdout` (list of at least two dates), `success_threshold`, `assumption_band` (`{preset, parameter, points: [low, central, high]}`), `author`, `date`, `config` (path from the repository root), `config_sha256`. Optional: `limitations`. Validation (Java `PreRegistration.validate`, Python `ratesresearch.prereg.validate`) rejects: a missing or blank key; a non-ISO date; start after end; an evaluation period overlapping the fitting period or with fewer than two business days; a holdout date in the fitting or the evaluation period; band points other than exactly low, central, high; a config file that is missing or whose SHA-256 (text, CRLF normalised to LF) differs.

Ledger `data/ledger/ledger.jsonl`, one canonical JSON object per line (sorted keys, no whitespace):

| Key | Meaning |
|---|---|
| `ledger_version` | 1 |
| `seq` | 1-based line number |
| `prev_sha256` | SHA-256 of the previous line's text; 64 zeros for the first |
| `prereg_id`, `prereg_sha256`, `config_sha256` | what was registered |
| `journal_sha256`, `journals` | SHA-256 of the newline-joined per-session SHA-256 list, and per session `{date, seed, sha256, frames, rfqs}` |
| `code_commit`, `code_dirty` | the commit the run used |
| `scope`, `look` | `evaluation` or `holdout`, and the count of runs of that scope so far |
| `recorded_utc` | wall-clock time of the record |
| `results` | per band point: each strategy's `daily_pnl`, `mean`, bootstrap `ci_low`/`ci_high`, counts, `hit_rate`, `attribution_total`; each comparison's `mean_difference`, `paired_ci_low`/`paired_ci_high`, `welch_t`, `welch_df`, `welch_p`, `holm_p`, `verdict` |
| `verdict` | `supported` or `not supported` |

`ExperimentCli` exit codes: 0 recorded, 1 error, 2 refused (nothing run, nothing recorded).

### 16.9 Results tables and daily report

`ResultsWriter` writes, per run: `fills.csv` (`fill_id,time_nanos,instrument_id,instrument,kind,signed_quantity,price,mid,fee,ref_id,client_id,tier,edge,markout_1s,markout_10s,markout_1min,markout_5min`; currency with six decimals, prices with nine), `rfqs.csv` (`rfq_id,received_nanos,client_id,tier,instrument_id,instrument,client_side,size_mm,dealers,in_fixing_window,quoted,response_nanos,our_price,mid_at_quote,our_spread_bp,cover_bp,spread_to_cover_bp,win_probability,won,traded_price`), `pnl.csv` (one row per instrument and a `TOTAL` row with id 0, columns as Section 16.4) and `summary.json` (`schema` "rates-backtest summary v1", strategy, band point, seed, session date, frame count, input and output chain hashes in hex, counts, `max_abs_net_dv01`, `attribution`).

`ReportCli --config data/experiments/daily_report.yaml --journal FILE[,FILE] --out DIR` writes, per band point, the tables above, `results.raj`, `report.md` and `report.json` (`schema` "rates-backtest daily report v1") with keys `pnl_by_product`, `hit_rate_by_tier`, `markouts_by_tier`, `markouts_by_product` (USD and mean mid-to-mid bp per horizon), `hedge_slippage`, `router_fill_quality` (empty list until P4), `latency` (`available` false, or the harness file's `machine` and `percentiles_ns`), `limit_utilisation`, `client_analytics` (per client: rfqs, won, lost, hit and decline rates, mean money left on the table in bp, near misses within 0.05 bp of the cover), `response_time_vs_win_rate`. The latency harness file is JSON with `machine` and `percentiles_ns` (a map of percentile label to nanoseconds).

## Section 17. Post-trade, books and records, and operations (P6)

Modules `rates-posttrade` (package `io.ratesalgo.posttrade`) and `rates-ops` (package `io.ratesalgo.ops`). Both are off the hot path: they allocate, use `BigDecimal` for money, and run on a post-trade thread, in a batch job, or on the operations thread of a process. They read the journal; they never write to the event loop's state.

### 17.1 Trade identity

A trade is named by `TradeKey(venueId, source, tradeId)`. For an order fill the source is `Execution` and the id is the venue's `execId`; for an RFQ trade the source is `Rfq` and the id is our `quoteId`. The text form, used in records, reports and reconciliation match keys, is `venue:E:execId` or `venue:R:quoteId` (for example `1:E:800050`, `2:R:70024`). Every message about a trade (duplicate, bust, amendment, drop copy line, clearing report) carries this key.

### 17.2 Position keeper

`PositionKeeper` is a `FrameHandler`: its state is a function of the journal frames it has read, live or in replay.

| Input frame | Effect |
|---|---|
| ExecutionReport (21) with `lastQuantity > 0` and status PartiallyFilled or Filled | Books a trade on our side `side` at `lastPrice`, transaction time `transactNanos` |
| ExecutionReport, any status | Updates the order's state (venue order id, leaves, cumulative) and writes an order record |
| QuoteRequest (10) | Remembers instrument, client side and client for the RFQ; writes a Table 2 record |
| Quote (11) | Remembers the live quote; writes two Table 3 records, one per side |
| QuoteResponse (12) status Done | Books a trade on the side opposite the client's, at `tradedPrice`, joined to its QuoteRequest; a response without its request in the journal throws `IllegalStateException` (a contract break) |
| NewOrder (20) | Opens the order's state; writes a NEWO record |
| PostTradeBust (100) | Marks the trade BUSTED; a bust that arrives before its fill is held and applied when the fill arrives |
| TradeAmend (101) | Creates a new version of the trade with the corrected price or quantity |
| Anything else | Ignored. A frame whose SBE schema id is not 1 throws `IllegalStateException` (Section 4) |

Rules:

- **Duplicates.** A second report with a booked key is ignored. If its economics differ it is recorded as `DUPLICATE_CONFLICT`, which the reconciliation does not clear.
- **Late fills.** Positions are computed from live trades in transaction-time order (ties by journal sequence, then key), so a fill that arrives after a later one lands where it belongs. It is flagged `late` and audited `LATE_FILL`.
- **Busts.** A busted trade stays in the records and leaves the position. A second bust is audited `DUPLICATE_BUST` and changes nothing. A bust still waiting for its fill at the end of the day is a `pending-busts` break.
- **Amendments.** Each amendment is a new version (`versions(key)`) with the party id of the person who made it and the reason in the audit trail. An amendment of an unknown or busted trade, or to a non-positive quantity, is refused and audited `AMEND_REJECTED`.
- **Average cost.** Weighted average cost. A trade in the direction of the position adds `price * quantity` to the cost; a trade against it realises `closed * (price - average)` through the valuation convention and removes the closed share of the cost; a trade through zero closes the old position and opens the remainder at its own price. Intermediate values carry 34 significant digits (`MathContext.DECIMAL128`). The property test `PositionCalculatorProperties` checks that realised plus unrealised P&L equals the trades' cash flow plus the remainder at the mark, to 1e-6 minor units, for random trade sequences.
- **Audit.** Every decision, including the decision not to book, leaves an `AuditEntry` (sequence, timestamp, kind, key, actor, detail): BOOKED, DUPLICATE_IGNORED, DUPLICATE_CONFLICT, LATE_FILL, BUSTED, BUST_PENDING, DUPLICATE_BUST, AMENDED, AMEND_REJECTED, OPENING_POSITION.

Positions are kept per book, account and instrument (`PositionKey`). The `BookMapper` assigns each trade a book, an account and the algorithm inventory id.

Intraday P&L (realised from the keeper plus a revaluation at a live mid) is an estimate and is never written as official.

### 17.3 Marks, carry and the marking policy

`ValuationConvention.pnl(instrument, signedQuantity, from, to)` returns minor units, unrounded. The fixture `InstrumentCatalogue` is linear: `q * (to - from) * multiplier / 10^11`, where 10^9 undoes the Decimal9 mantissa and 100 converts a price per 100 or a rate in percent to a fraction. The multiplier is 1 for bonds and futures priced per 100 of face and the annuity in years for a swap quoted as a par rate (8.5 in the fixture). A buyer of a swap pays fixed and gains when the rate rises.

Marking policy (`EndOfDayPnl`):

1. Official P&L is produced only from official closes (`MarkSource`). A position with a non-zero closing quantity and no official close stops the run with `MissingMarkException`; nobody substitutes a mid. Flat positions need no mark.
2. At start of day each position is re-struck at the previous official close (`OpeningPosition`), so the day's P&L is measured from the last official mark.
3. Realised P&L is the keeper's weighted-average-cost figure. Unrealised revalues the closing quantity from its average cost to the close. Carry is the closing quantity times the carry fraction for the date (`CarrySource`: coupon accrual less repo for a bond, fixed less floating accrual for the payer of a swap, over the calendar days to the next business day).
4. Each component is rounded half-even to the minor unit; the total is the sum of the rounded components.

Worked example, the fixture day in `OfficialPnlTest` (2026-10-30, figures in US cents):

| Position | Trades | Close | Realised | Unrealised | Carry | Total |
|---|---|---|---|---|---|---|
| book 1, account 10, Treasury 1001 | opening long 10,000,000.00 face at 99.50; buy 5,000,000.00 at 99.61 amended to 99.60; sell 6,000,000.00 at 99.70 (delivered twice); buy 2,000,000.00 at 98.00 busted | 99.65 | 1,000,000 | 1,050,000 | 31,500 | 2,081,500 |
| book 2, account 20, SOFR 10Y swap 2001 | buy (pay fixed) 100,000,000.00 at 4.25; sell 40,000,000.00 at 4.26 | 4.27 | 3,400,000 | 10,200,000 | 8,400 | 13,608,400 |

For the Treasury: the average cost after the opening and the buy is (10mm x 99.50 + 5mm x 99.60) / 15mm = 99.5333...; selling 6mm at 99.70 realises 6mm x 0.1666... / 100 = 10,000.00 USD; the remaining 9mm at the same average marked at 99.65 is worth 9mm x 0.1166... / 100 = 10,500.00 USD; carry is 9mm face x 0.000035 = 315.00 USD. For the swap: 40mm closed at 0.01 above cost times the 8.5-year annuity is 40mm x 0.0001 x 8.5 = 34,000.00 USD; 60mm open marked 0.02 above cost is 60mm x 0.0002 x 8.5 = 102,000.00 USD; carry 60mm x 0.0000014 = 84.00 USD.

### 17.4 Order records and retention

`OrderRecord` is one event in the layout of UK RTS 6 Article 28 and Annex II. Annex II has two tables: Table 2 for initial decisions and incoming client orders, Table 3 for outgoing and executed orders. The record carries the fields that apply to this flow; options, short selling, waivers and pegged prices do not apply and are not carried.

| Record field | Annex II | Content in this platform |
|---|---|---|
| `record_sequence` | T3.25 | Ascending per keeper |
| `trading_date` | | UTC date of the event |
| `table` | | `T2` (incoming RFQ) or `T3` |
| `event_type` | T3.26 | NEWO, PARF, FILL, CAME, REMO, EXPI; venue-specific BUST and AMND |
| `buy_sell` | T3.1, T2.8 | BUYI or SELL: our side in T3, the client's in T2 |
| `trading_capacity` | T3.2 | DEAL (principal) |
| `liquidity_provision` | T3.3 | true for quotes and RFQ trades |
| `execution_within_firm` | T3.4, T2.6 | Algorithm inventory id |
| `internal_order_id` | T3.5, T2.7 | Our clOrdId or quoteId; the venue's rfqId for T2 |
| `venue_order_id` | T3.6 | Venue order id, 0 when none |
| `order_receiver` | T3.7 | `VENUE-<id>`; the MIC is bound by the P2 gateway |
| `order_type` | T3.8 | LMT, MKT, QUOTE, RFQ |
| `limit_price` | T3.9, T2.10 | Decimal, 9 places |
| `price_currency`, `price_notation` | T3.10 to 11, T2.11, T2.13 | ISO 4217; PERC, YIEL or BAPO |
| `remaining_quantity`, `traded_quantity` | T3.15, T3.17 | Minor units |
| `submission_time`, `receipt_time` | T3.23 to 24, T2.27 | ISO 8601 UTC with nanoseconds |
| `venue_transaction_id` | T3.30 | The venue's execId |
| `validity_period` | T3.31 | GDAY, IOCA, FOKV, GTDT |
| `instrument_identifier` | T2.9 | ISIN or the securities master's placeholder |
| `client_id` | T2.3 | Client party id for T2 records, 0 otherwise |
| `journal_sequence`, `trade_key` | | Link to the journal frame and the trade |

Retention layout (`RecordArchive`): `<root>/<yyyy>/<mm>/<dd>/order-records.csv` with the header above, one directory per trading date, append only, kept for five years (configurable to seven, `retention.years`). `findByVenueOrderId` returns every record for a venue order id across retained dates; `RecordRetentionTest` checks that every record of a synthetic day is retrievable that way and that the archived file round-trips each record exactly.

### 17.5 Reconciliation input from the back-tester

P5 writes, for each replayed journal and business date, a CSV in this format; `PnlReconciler` compares it with official P&L component by component with a tolerance of zero minor units.

```
# run_id=<back-test run id>
# journal_sha256=<hash of the journal replayed>
date,book,account,instrument_id,component,amount_minor
2026-10-30,1,10,1001,realised,1000000
2026-10-30,1,10,1001,unrealised,1050000
2026-10-30,1,10,1001,carry,31500
```

`component` is `realised`, `unrealised` or `carry`; `amount_minor` is a whole number of minor units; lines starting with `#` carry provenance and are skipped; amounts for the same key and component are summed. The fixture file is `rates-posttrade/src/test/resources/fixture/attribution.csv`. `EodReportMain` writes official P&L in the same format, so the two files can be compared directly.

### 17.6 Clearing lifecycle (swaps)

States NEW, SUBMITTED, ACCEPTED, CLEARED, REJECTED; events SUBMIT, ACCEPT, CLEAR, REJECT. Transitions: NEW to SUBMITTED on SUBMIT; SUBMITTED to ACCEPTED on ACCEPT; ACCEPTED to CLEARED on CLEAR; SUBMITTED or ACCEPTED to REJECTED on REJECT. Every other pair is refused: the state does not change and the refusal is kept in the history. CLEARED and REJECTED are terminal. A rejection voids the trade; the caller journals a `PostTradeBust` with reason ClearingRejected. `ClearingHouseSimulator` accepts within a per-account gross notional limit, rejects beyond it, and clears accepted trades at the end of the cycle. Property tests (`ClearingLifecycleProperties`): terminal states absorb every event, a refusal never changes state, CLEARED is reached only through NEW, SUBMITTED, ACCEPTED, and replaying the history through the pure transition function reaches the same state.

### 17.7 Settlement lifecycle (bonds) and the cost of a fail

States PENDING, SETTLED, FAILED, CANCELLED; events SETTLE, FAIL, CANCEL. PENDING goes to SETTLED, FAILED or CANCELLED; FAILED stays FAILED on another FAIL and goes to SETTLED (cured) or CANCELLED; SETTLED and CANCELLED are terminal. SETTLE and FAIL are refused before the settlement date. The settlement date is the trade date plus the settlement lag in business days (`BusinessCalendar`; T+1 for Treasuries and gilts, T+2 for Bunds; P1 binds the real calendars). `daysFailed` counts calendar days from the settlement date to the cure, or to the as-of date while still failing.

Cost of a fail (`FailCost`, actual/360, from our point of view):

- funding: `proceeds * fundingRate * days / 360`, a loss if we are the failing deliverer, a gain if we are failed to;
- TMPG fails charge for Treasuries: `proceeds * max(3% - referenceRate, 0) * days / 360`, paid by the failing deliverer.

Worked example (`SettlementProperties.workedFailExample`): proceeds 9,950,000.00 USD failing for 3 days with funding at 4.30% costs 3,565.42 USD; the fails charge is zero at a 4.00% reference rate and 1,658.33 USD at 1.00%.

### 17.8 Reconciliations and break classes

`Reconciler.trades` reduces each side to one item per match key (an identical repeat is suppressed and counted; a repeat that differs is a break), then compares by key. The first failing test names the break:

| Class | Meaning |
|---|---|
| MISSING_OURS | The other side has it and we do not |
| MISSING_THEIRS | We have it and the other side does not |
| QUANTITY | Both have it; side, instrument or quantity differ |
| PRICE | Price differs by more than the tolerance (zero by default) |
| TIMING | Economics agree; transaction times differ by more than the tolerance (one second by default) |

`KeeperFacts` explains a break from a keeper that has read the whole journal, given the sequence at which the reconciled snapshot was cut: LATE_ARRIVAL (a MISSING_OURS trade booked after the cut), BUSTED_AFTER_CUT (a MISSING_THEIRS trade busted after the cut), CLEARING_REJECTED, AMENDED (PRICE or QUANTITY on an amended trade); DUPLICATE_CONFLICT and NONE do not clear a break.

`DailyReconciliation` runs five reconciliations: `drop-copy` (trades against venue drop copies), `drop-copy-pos` (net quantity per instrument, our side adjusted for explained trade breaks), `books-records` (trades against those rebuilt from the archived order records), `clearing` (net quantity per account and cleared instrument against the clearing house's report) and `pending-busts`. `BreakReport.render()` is the text COOKBOOK.md recipe 20 quotes.

Synthetic day (`SyntheticDay`, seed 20261030, `Params.DEFAULT`: 60 orders, 40 RFQs, 5 duplicate fills, 2 fills late within the day, 3 fills after the cut, 3 busts of which one arrives before its fill, 1 bust after the cut, 2 duplicate drop-copy lines). Result recorded by `RecipesTest` and COOKBOOK.md recipe 21 on 2026-10-31: 336 frames, 105 fills, four breaks at the cut, all explained (three LATE_ARRIVAL, one BUSTED_AFTER_CUT), no break after every late message has arrived. `ChaosDayTest` asserts zero unexplained breaks and that a genuine price difference in the drop copy is reported as one unexplained PRICE break.

### 17.9 Restart procedure

`RestartProcedure.run(journal, venueIds, now, handlers...)`, in this order: disable quoting (`QuotingControl`); replay the journal to its last sequence into the keeper and any other handlers; for each venue, `VenueStatusPort.orderMassStatus` (FIX OrderMassStatusRequest, 35=AF) and `quoteStatus` (QuoteStatusRequest, 35=a); cancel any order the venue holds that the journal does not hold as open; record orders the journal holds that the venue does not (finished while down; fills arrive by drop copy); a venue cumulative quantity above the journal's is a blocking discrepancy (fills missing); withdraw every live quote at the venue; enable quoting only if there is no blocking discrepancy. The report lists every action. `RestartProcedureTest` checks the order of calls and that missing fills keep quoting off.

### 17.10 Surveillance hooks

`SurveillanceDetector` is a `FrameHandler` that accumulates `SurveillanceAlert(detector, instrumentId, journalSequence, timestampNanos, detail)`. `QuoteStuffingDetector(windowNanos, threshold)` counts our Quote and NewOrder frames per instrument over a sliding window and alerts once per episode above the threshold, re-arming below half of it. `SelfTradeDetector(windowNanos)` alerts on a buy fill and a sell fill of ours in the same instrument on the same venue at the same price within the window. Alerts are leads for review, not findings.

### 17.11 Configuration

Each process reads one YAML file under `deploy/config/` (schema version 1), validated at start against `deploy/config/schema/process-config.schema.json`; a process whose configuration does not validate exits with status 2 and lists every violation. Sections: `process` (name, role `trading` or `venue-sim`, colour `blue` or `green`), `http` (bind address, port, the environment variable holding the control token), `journal` (directory, capacity), `quoting` (enabled at start, instruments with venue and maximum quote size), `limits` (maximum order notional, maximum net DV01, maximum messages per second), `recon` (cut time UTC, timing tolerance), `retention` (years, 5 to 7). Environment overrides applied before validation: `RATES_HTTP_PORT`, `RATES_JOURNAL_DIR`. The Java validator (`JsonSchema`) supports the keywords type, properties, required, additionalProperties false, enum, const, minimum, maximum, minLength, pattern, items, minItems and maxItems, and refuses a schema that uses any other; CI also validates the files with the Python `jsonschema` package (`tools/validate_config.py`) and checks that a known-bad fixture is refused.

### 17.12 Operations endpoints and metrics

`OpsHttpServer` on the JDK HTTP server: `GET /health/live`, `GET /health/ready` (503 with the reason when not ready), `GET /metrics` (Prometheus text format 0.0.4), `GET /control/status` (JSON), `POST /control/kill` (requires header `X-Control-Token` when a token is configured; `X-Requested-By` carries the party id journaled with the KillSwitch; 405 for other methods, 403 without the token).

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `rates_queue_depth` | gauge | queue | Messages waiting in a ring buffer |
| `rates_stage_latency_seconds` | histogram | stage | Seconds in one loop stage; buckets 1 microsecond to 100 milliseconds |
| `rates_limit_utilisation_ratio` | gauge | limit | Observed over limit |
| `rates_recon_breaks` | gauge | class, explained | Breaks in the latest reconciliation |
| `rates_quotes_live` | gauge | | Quotes live at venues |
| `rates_kill_switch_engaged` | gauge | | 1 when engaged |
| `rates_quoting_enabled` | gauge | | 1 when quoting |
| `rates_journal_frames_total` | counter | | Frames appended by the operations path |
| `rates_process_info` | gauge | name, role, colour | Always 1 |

The list is pinned in `deploy/prometheus/metrics.txt`; `MetricsRegistryTest` checks it against `MetricNames.ALL` and `tools/lint_dashboards.py` refuses dashboards and alert rules that name anything else.

### 17.13 Stress test harness

`StressReplayer(clock, pacer).run(journal, sink, multiplier, windowNanos)` loads the journal into memory, finds its busiest window, and sends each frame to the `MessageSink` at `start + (ts - ts0) / multiplier`, so the busiest window runs at `multiplier` times its recorded rate (RTS 6 Article 10 asks for twice). Latency is measured from each frame's intended send time to the sink's return, so a stalled sink is charged for the whole stall (coordinated omission corrected). `StressResult` reports frames, accepted, acceptance rate, source and target peak rates, and latency at p50, p99, p99.9 and max in nanoseconds.

### 17.14 Interfaces the coordinator binds

| Interface | Module | Bound at integration to |
|---|---|---|
| `BookMapper` | rates-posttrade | Book and account from the router's parent order (P4) and the quoting engine's book configuration (P3) |
| `ValuationConvention`, `InstrumentCatalogue` | rates-posttrade | P1 securities master and revaluation |
| `MarkSource`, `CarrySource` | rates-posttrade | P1 curve closes and public settlement prices; P1 carry and repo |
| `BusinessCalendar` | rates-posttrade | P1 calendars |
| `VenueStatusPort` | rates-posttrade | P2 FIX gateway (OrderMassStatusRequest, QuoteStatusRequest, cancels) |
| `QuotingControl`, `OpsHttpServer.Control` | rates-posttrade, rates-ops | P3 quoting engine and kill switch |
| `MessageSink` | rates-ops | P2 event loop inbound ring buffer |
| Attribution file (17.5) | rates-posttrade | P5 back-tester output |
| P2 `TradeBust` (44) | rates-posttrade | A case in `PositionKeeper.onFrame` that applies it as a PostTradeBust with reason VenueError |
