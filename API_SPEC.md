# API specification: the pinned, cross-module contract

This document is the contract between modules and between the Java and Python sides. Anything stated here is fixed for the schema or format version it names; a change needs a new version number, a decision record in `DECISIONS.md` and a migration note in `CHANGELOG.md`. Where code and this document disagree, the one that was reviewed last is wrong and the pull request that fixes it must say which.

Phase 0 pins Sections 0 to 4. Phase 1 pins Sections 5 to 8 (analytics).

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

Shared types: `Decimal9` is a composite of `mantissa` int64 and a constant `exponent` of -9; `Ccy` is `char[3]` ASCII; `Tenor` is `char[4]` ASCII, space padded; `EpochNanos`, `Quantity` and `SeqNo` are int64; `InstrumentId`, `PartyId` and `VenueId` are int32. Enums are uint8: `Side` (Buy 1, Sell 2), `BookAction` (Add, Change, Delete, Clear), `QuoteStatus` (Done, TradedAway, Expired, Rejected, Passed), `OrdStatus` (New 0, PartiallyFilled 1, Filled 2, Cancelled 4, Rejected 8), `OrdType` (Market, Limit), `TimeInForce` (Day 0, ImmediateOrCancel 3, FillOrKill 4, GoodTillDate 6), `CurveInputKind` (Fixing, Future, ParSwap, Bond), `LimitKind` (Position, BucketDv01, NetDv01, GrossDv01, DailyLoss, OrderRate, QuoteRate, MaxOrderSize, PriceCollar), `KillReason` (Manual, LimitBreach, FeedGap, SessionLoss, StaleReference).

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

Every message is preceded on the wire by the standard SBE `messageHeader` (blockLength uint16, templateId uint16, schemaId uint16, version uint16), 8 bytes. `QuoteRequest` has block length 49, so its encoded length is 57 (COOKBOOK.md recipe 3).

Versioning rule: adding a field to the end of a message with `sinceVersion` increments the schema version and keeps template ids. Changing a field's type or position requires a new schema id and a new XML file; v1 is never edited in place once a journal written with it exists in a release.

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
