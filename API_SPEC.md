# API specification: the pinned, cross-module contract

This document is the contract between modules and between the Java and Python sides. Anything stated here is fixed for the schema or format version it names; a change needs a new version number, a decision record in `DECISIONS.md` and a migration note in `CHANGELOG.md`. Where code and this document disagree, the one that was reviewed last is wrong and the pull request that fixes it must say which.

Phase 0 pins Sections 0 to 4. Phase 1 pins Sections 5 to 8 (analytics). Phase 2 pins Section 9 (event loop and ring buffers) and Section 1.5 (journal segments); the P2 venue half pins Sections 10 and 11 (FIX, book feed, simulators, reference data) and Section 2.2. Phase 3 pins Sections 12 to 14 (quoting, risk limits, hedger). Phase 4 pins Section 15 (router). Phase 5 pins Section 16 (back-tester). Round 2 pins Section 18 (process assembly). Phase 6 pins Section 17 (post-trade and operations) and Section 2.1.

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

Format name `RAJ1`. One journal is one or more segment files of equal size; Phase 0 implements a single segment, and P2 adds rolling across segments (1.5).

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

### 1.5 Segments (P2)

A segmented journal named `name` in directory `dir` is the files `name.000001.raj`, `name.000002.raj`, and so on: the name, a dot, the 1-based segment index in six decimal digits, and the suffix `.raj` (at most 999,999 segments). Each segment is a complete RAJ1 file with its own 32-byte header; segments are created with the same `capacityBytes`.

Sequence and chain continue across segments. The first frame of segment *k* + 1 carries the sequence after the last frame of segment *k*, and its chain hash input begins with the last chain hash of segment *k* (not with zero). The writer (`SegmentedJournal`) rolls to the next segment when the next frame does not fit in the current one, so a segment ends either at its terminating zero length or at its end. A frame larger than an empty segment can hold is refused (`JournalFullException`). A writer may create the next segment ahead of time under the name `name.00000k.raj.pending` and rename it to its final name when it is complete; readers ignore `.pending` files, and a complete segment with no frames is valid (a crash after preallocation leaves one).

How `SegmentedJournal` prepares segments when preallocation is on (R2, DECISIONS.md DR-R2J-01 and DR-R2J-02); none of this changes a byte of the format:

- A new segment file is zero-filled with file-channel writes and then mapped. It is not zero-filled by storing through the mapping.
- `create` writes a zero byte into the last byte of every page of the first segment before it returns ("touching" the page), so the operating system has mapped every page before the first append.
- As soon as a segment becomes current, a background thread (`journal-prepare-<name>`) prepares the next one: create as `.pending`, force, rename, map, touch every page. One segment is kept ready ahead of the current one.
- At a roll the appending thread takes the ready writer through a volatile field, continues the chain in it and hands the full segment back to the background thread, which forces and unmaps it. The roll maps nothing, forces nothing and allocates nothing on the appending thread. If the next segment is not ready, the roll waits for it and counts the wait (`rollWaits()`).
- Touching a byte that is already zero leaves the file unchanged; a touched segment with no frames reads as an empty segment.

A reader (`SegmentedJournalReader`) walks the segments in index order up to the highest index present when it opened, carrying sequence and hash across each boundary, and applies 1.4 throughout. A segment read on its own fails the chain check unless the reader is given the previous segment's last sequence and hash, which is deliberate: a segment cannot be swapped for another self-consistent one without detection. A missing segment below the highest index is reported as corruption at the sequence the missing segment should have started with. `SegmentedJournalTest` checks a swapped segment, a missing segment, a flipped byte in a later segment and a truncated earlier segment.

## Section 2. SBE schema v1

Source of truth: `rates-messages/src/main/resources/sbe/rates-schema-v1.xml`. Package `io.ratesalgo.messages.sbe`, schema id 1, version 0, semantic version 1.0.0, little-endian, all messages fixed length (no groups, no var-data) in v1. This section lists the messages so that other documents can refer to them; the XML wins on any difference.

Shared types: `Decimal9` is a composite of `mantissa` int64 and a constant `exponent` of -9; `Ccy` is `char[3]` ASCII; `Tenor` is `char[4]` ASCII, space padded; `EpochNanos`, `Quantity` and `SeqNo` are int64; `InstrumentId`, `PartyId` and `VenueId` are int32. Enums are uint8: `Side` (Buy 1, Sell 2), `BookAction` (Add, Change, Delete, Clear), `QuoteStatus` (Done, TradedAway, Expired, Rejected, Passed), `OrdStatus` (New 0, PartiallyFilled 1, Filled 2, Cancelled 4, Rejected 8), `OrdType` (Market, Limit), `TimeInForce` (Day 0, ImmediateOrCancel 3, FillOrKill 4, GoodTillDate 6), `CurveInputKind` (Fixing, Future, ParSwap, Bond), `LimitKind` (Position, BucketDv01, NetDv01, GrossDv01, DailyLoss, OrderRate, QuoteRate, MaxOrderSize, PriceCollar), `KillReason` (Manual, LimitBreach, FeedGap, SessionLoss, StaleReference). P4 adds the enums `VenueKind` (Clob 1, Futures 2), `RouteReason` (Slice 1, Escalation 2, BookMove 3, Retry 4, Deadline 5, Reconcile 6), `VenueExclusion` (None 0, NoQuote 1, SelfCross 2, BelowMinimum 3, CapReached 4, LimitPrice 5), `ChildState` (New 1, Acknowledged 2, PartiallyFilled 3, Filled 4, CancelRequested 5, Cancelled 6, Replaced 7, Rejected 8) and `TransitionCause` (Sent 1, Ack 2, Fill 3, CancelSent 4, CancelConfirmed 5, Rejected 6, CancelRejected 7, Reconciled 8). Template ids 80 to 89 are reserved for the router. P3 added `LimitKind` values MessageRate (10) and FatFinger (11), and `CancelReason` (Expired 1, StaleInput 2, FeedGap 3, HeartbeatLoss 4, SessionLoss 5, KillSwitch 6, AdverseSignal 7, Requote 8, Manual 9) for QuoteWithdraw and OrderWithdraw (DECISIONS.md DR-P3-04). A Quote with `validUntilNanos` zero is subject, not firm (DR-P3-10).

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
| 60 | QuoteWithdraw (P3) | `venueId`, `rfqId`, `quoteId`, `instrumentId`, `reason` CancelReason, `sentNanos` |
| 61 | OrderWithdraw (P3) | `venueId`, `clOrdId` (of this cancel), `origClOrdId`, `instrumentId`, `side`, `reason` CancelReason, `sentNanos` |
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

### 2.2 Additions by P2 venues (W3)

Added to `rates-schema-v1.xml` on 2026-10-31 without changing any existing field (DR-P2V-02). The schema version stays 0: every addition is a new message or a new enum value, which an existing decoder never meets in a journal it wrote, and no journal has been released.

| Template id | Message | Fields (in order) |
|---|---|---|
| 40 | MarketDataReject | `sourceId` int32, `instrumentId`, `reason` MdRejectReason, `bidPrice` Decimal9, `askPrice` Decimal9, `referencePrice` Decimal9 (0 if none), `sourceSeqNo`, `sourceNanos`, `timestampNanos` |
| 41 | OrderCancel | `venueId`, `clOrdId` int64, `origClOrdId` int64, `instrumentId`, `side`, `quantity`, `sentNanos` |
| 42 | OrderReplace | `venueId`, `clOrdId`, `origClOrdId`, `instrumentId`, `side`, `price` Decimal9, `quantity`, `sentNanos` |
| 43 | QuoteCancel | `venueId`, `rfqId` int64, `quoteId` int64, `instrumentId`, `sentNanos` |
| 44 | TradeBust | `venueId`, `clOrdId`, `execId` (the busted execution), `instrumentId`, `side`, `price` Decimal9, `quantity`, `timestampNanos` |

New enum `MdRejectReason` (uint8): CrossedQuote 1, LockedQuote 2, NonPositivePrice 3, Outlier 4, Stale 5, CrossedComposite 6, LockedComposite 7, FixingRepublicationLate 8, FixingRepublicationUnchanged 9, SequenceGap 10, OutOfOrder 11, UnknownInstrument 12 (meanings in Section 11.5). New value `QuoteStatus.DoneAway` 6: the client traded the RFQ elsewhere. Template ids 40 to 59 are reserved for the venue layer.

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
| Segmented journal: a segment missing below the highest index, or a segment whose first frame does not continue the previous segment's sequence and chain hash | `SegmentedJournalReader.next` throws `JournalCorruptionException` with the sequence where the stream breaks, after delivering every frame before it (Section 1.5). |
| Segmented journal: frame larger than an empty segment | `JournalFullException`; no segment is created. Any other full segment rolls. |
| Event loop: a ring record that breaks the framing of Section 9.2 | The loop throws naming the values; the runner journals a LoopError and stops the loop (Section 9). |
| Event loop: a handler, timer, agent or hook throws | `LoopRunner` journals a LoopError (exception class and message) on the loop thread, skips the end-of-day hook and stops; the failure is kept in `LoopRunner.failure()`. Nothing is retried. |
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

Diagram: [multi-curve bootstrap](docs/site/pages/diagrams.md#multi-curve-bootstrap).

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
| Swap PV (83 swaps), QuantLib curve | 1e-12 of notional (1e-8 before DR-R2R-11) | 1.6e-15 of notional | `swapsMatchQuantLibOnBothCurves` |
| Swap PV on the Java bootstrap | 1e-12 of notional | 8.5e-15 of notional | same |
| Par rate | 1e-10 (1e-6 before DR-R2R-11) | 3.5e-16 | same |
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

`python -m ratesresearch.goldens` (from `research/`, with `pip install -e "research[dev,golden]"`, QuantLib pinned at 1.44) writes `data/golden/analytics_golden.json`. The curves are bootstrapped in Python by a sequential Brent search in which every instrument is priced by QuantLib objects built with explicit schedules, calendars and conventions identical to Section 5; the market quotes for 15 January 2025 are declared values (DECISIONS.md DR-P1-08); past fixings are the published ones in `data/fixtures`. `research/tests/test_goldens.py` regenerates the file and compares every number to 1e-12 relative (absolute floor 1e-6, DR-P1-06 as amended at integration); CI runs it on Linux and Windows (DECISIONS.md DR-P1-06).

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

## Section 9. Event loop and ring-buffer contract

Pinned by P2 (event core) on 2026-10-31. Code: `rates-core`, package `io.ratesalgo.core`. A venue gateway needs only `RingBufferContract`, `InboundPublisher`, `ControlMessages` and the SBE codecs to build to this section.

### 9.1 Threads and ownership

One `EventLoop` owns one instrument group's state and runs on one thread (`LoopRunner`). Gateway threads publish into the loop's inbound rings and read from its outbound rings. Nothing else crosses threads. A loop has one or more inbound rings, drained in the order they were added, and one outbound ring per gateway; the gateway id is the index of the outbound ring, from 0.

### 9.2 Framing

| Rule | Value |
|---|---|
| Inbound ring | Agrona `ManyToOneRingBuffer`: any number of gateway threads, one loop |
| Outbound ring | Agrona `OneToOneRingBuffer`: the loop to one gateway thread |
| Capacity | a power of two, at least 8192 bytes (`RingBufferContract.MIN_CAPACITY`), fixed at start-up; defaults 1 MiB inbound, 256 KiB outbound |
| Record | Agrona's 8-byte record header (length, type), then the payload, padded to 8 bytes |
| Record type | the SBE template id of the payload; the loop throws if they differ |
| Payload | the SBE message header (blockLength, templateId, schemaId, version; 8 bytes) followed by the body, byte for byte as it is journaled (Section 1.2) |
| Maximum payload | 1024 bytes (`RingBufferContract.MAX_MESSAGE_LENGTH`); a QuoteRequest is 57 |
| Accepted schemas | schema 1 version 0 (Section 2) and the core control schema 100 version 0 (9.6) |

A payload that breaks a framing rule (length outside 8 to 1024, record type not equal to the template id, unknown schema id or version, or a message only the loop may write) is a contract break: the loop throws `IllegalStateException` or `IllegalArgumentException` naming the values, the runner journals a LoopError and the loop stops (Section 4).

### 9.3 Inbound backpressure

`InboundPublisher.offer` and `tryClaim` never block. When the inbound ring is full they return false or `RingBuffer.INSUFFICIENT_CAPACITY` and increment the publisher's `rejectedCount`. What the gateway does next depends on the message:

| Message | On a full ring |
|---|---|
| BookUpdate, CurveInput, Heartbeat | drop and count; a dropped BookUpdate leaves the book gapped, so the gateway marks the book stale and requests a snapshot through its venue session |
| QuoteRequest, QuoteResponse, ExecutionReport, GatewayStatus | retry with the gateway's idle strategy until accepted; count the retries; never drop |

A full inbound ring means the loop is behind. The counts belong in the gateway's monitoring and in the capacity review that sizes the ring.

### 9.4 Duty cycle, time and journaling

`EventLoop.doWork()` runs one duty cycle:

1. Latch the clock once (`EventClock.latch()`). Every handler in the cycle reads the same `nanoTime()` and `epochNanos()`, and every frame journaled in the cycle carries that `epochNanos()`.
2. Drain each inbound ring, at most `readLimit` messages per ring (default 256). For each message: check the framing (9.2), append it to the inbound journal with its template id and schema version, dispatch it to the `MessageHandler` registered for its template id (unregistered template ids are journaled and counted, not dispatched), then raise any backpressure the handler caused (9.5).
3. Poll the timer wheel at the latched `nanoTime()`. If any timer fired, append a clock tick to the inbound journal (a Heartbeat with `sourceId` 0 and the latched `epochNanos()`), then raise any backpressure the timers caused.
4. Call each `Agent.doWork()`, raising backpressure after each.

Handlers send with `EventLoop.publish(gatewayId, buffer, offset, length)`. The loop appends the message to the outbound journal, then writes it to the gateway's ring. The outbound journal therefore records every message the loop produced, whether or not the ring accepted it.

Gateways must not publish a Heartbeat with `sourceId` 0; that value is the loop's clock tick. Agents and the runner's start-of-day and end-of-day hooks are not journaled and do not run in replay, so they must not change state that handlers read or publish outbound messages; their effects reach the loop as messages on an inbound ring.

### 9.5 Outbound backpressure

When a gateway's outbound ring is full, `publish` drops the message, counts it (`LoopCounters.OUTBOUND_DROPPED`) and returns normally; a handler cannot see the ring state, so its control flow is the same live and in replay. After the handler, the timer poll or the agent that caused the drops returns, the loop appends one Backpressure control message per affected gateway to the inbound journal (gateway id, number dropped, template id of the first dropped message, latched time) and calls the `BackpressureListener` with a `BackpressureEvent` carrying the same values. The quoting layer registers the listener and is expected to pull its live quotes on that gateway, because a quote the venue did not receive cannot be relied on.

### 9.6 Core control schema

Schema id 100, version 0, little-endian, standard 8-byte SBE header. Fixed-length messages; offsets are from the start of the body.

| Template id | Message | Written by | Body |
|---|---|---|---|
| 65001 | LoopError | the loop, into its inbound journal, when a duty cycle or hook throws | 0 `timestampNanos` int64, 8 `inboundCount` int64, 16 `textLength` uint16, 18 `text` char[238] ASCII (exception class and message, cut to 238); block length 256 |
| 65002 | Backpressure | the loop, into its inbound journal (9.5) | 0 `timestampNanos` int64, 8 `droppedCount` int64, 16 `gatewayId` int32, 20 `firstTemplateId` uint16, 22 two zero bytes; block length 24 |
| 65003 | GatewayStatus | a gateway, on the inbound ring, when its venue session connects (status 1) or disconnects (status 2) | 0 `timestampNanos` int64, 8 `gatewayId` int32, 12 `status` uint8, 13 three zero bytes; block length 16 |

Template ids start at 65001 so that a journal frame's template id never collides with schema 1, whose ids are below 100. The loop refuses LoopError and Backpressure from a ring.

### 9.7 Journals and replay comparison

A live loop writes two journals, each a `SegmentedJournal` (Section 1.5) built with the loop's `EventClock`: the inbound journal holds every accepted inbound message, every clock tick, every Backpressure and the LoopError if there is one; the outbound journal holds every message the loop produced. Both carry the latched cycle time in `timestampNanos`.

Journal modes (R2, DECISIONS.md DR-R2J-03). `LoopJournals.create(dir, inboundName, outboundName, segmentBytes, clock, mode)` opens both journals, with preallocation on, in one `JournalMode`, and `EventLoop.Builder.journals(LoopJournals)` gives them to the loop. `inboundJournal(JournalSink)` and `outboundJournal(JournalSink)` still accept any sink, so a caller that wires `SegmentedJournal::append` itself is unaffected.

| Mode | What the loop thread does per message | Who writes the mapped file | Chain hash computed on |
|---|---|---|---|
| `DIRECT` (default, DR-R2J-04) | `SegmentedJournal.append`: copy the payload into the touched segment, hash, header, length written last with an ordered store | the loop thread | the loop thread |
| `RING` | `RingJournal.append`: claim a record in an off-heap Agrona `OneToOneRingBuffer` (64 MiB by default), write the latched `epochNanos()`, the schema version and the payload, commit | the journal thread `journal-drain-<name>`, through `SegmentedJournal.append` with the stamp from the record | the journal thread |

Both modes write the same frames: the same `JournalWriter` code assigns the sequence and computes the chain hash from the same payload bytes in the order the loop appended them, and the stamp is the latched time read on the loop thread in both. `RingJournalTest.ringModeWritesTheSameFramesAsTheDirectMode` checks sequence, stamp, template id, schema version, payload and chain hash frame by frame. Replay (below) reads either. In ring mode:

- a full ring makes the loop thread wait until the journal thread makes room, and the wait is counted (`producerWaits()`); a frame is never dropped;
- a frame too large for an empty segment is refused on the loop thread (`JournalFullException`), as in direct mode;
- if the journal thread fails, the next append on the loop thread throws `IllegalStateException` with the cause; the runner stops the loop as for any other failure (Section 4), and its attempt to journal a LoopError through the failed journal fails too and is attached to the failure as suppressed;
- a process crash loses the frames still in the ring (normally microseconds of traffic); in direct mode every appended frame is already in the mapping and survives a process crash. Neither mode forces to disk on the loop thread; durability across an operating-system crash is the background force at each roll plus the operating system's write-back.
- `LoopJournals.close()` drains each ring before closing its journal; call it after the loop thread has stopped.

`LoopReplay.run(inbound, recordedOutbound, factory)` replays the inbound journal through a fresh loop built by the same `LoopFactory` that built the live one:

- The `Replayer` sets a `ManualClock` to each frame's `timestampNanos` and the loop latches it, so handlers see the times they saw live.
- The replay loop is built at the first frame, so its timer wheel starts at the replayed time.
- A clock tick polls the timer wheel; a Backpressure calls the listener with the journaled values; a LoopError stops the replay; every other frame is dispatched to its handler. Agents do not run.
- Every outbound message the replay loop produces is chained with `ChainHash` (Section 1.3) from the genesis hash and compared with the next frame of the recorded outbound journal.

The result (`ReplayResult`) gives the produced and recorded counts, both final hashes and `firstMismatch`: the outbound sequence of the first produced message whose chain hash or template id differs from the recorded frame, or the produced count plus one when replay produced fewer messages than were recorded; 0 when the streams are equal. Equal final hashes mean equal outbound byte streams, because the chain covers every payload byte in order.

Worked example: `LoopReplayTest.replayReproducesTheOutboundStreamExactly` runs 480 QuoteRequests with expiry timers, an undrained gateway that causes backpressure, a disconnect and 4096-byte segments, then replays; produced and recorded counts and hashes are equal. Replacing the handler's 2,000,000 spread (0.002 in price units) with 3,000,000 gives `firstMismatch = 1`.

Diagram: [replay and determinism](docs/site/pages/diagrams.md#replay-and-determinism).

### 9.8 Counters

`LoopCounters`, readable from any thread: inbound, outbound, outboundDropped, backpressureEvents, unhandled, timerTicks, errors. The loop writes them with release stores; readers use volatile loads.

## Section 10. FIX 4.4 layer and SBE book feed (P2 venues)

Code: `rates-fix` (package `io.ratesalgo.fix`, engine side, no QuickFIX/J) and `rates-venues` (simulators on QuickFIX/J 3.0.2, feed in `io.ratesalgo.venues.feed`). Decisions DR-P2V-01 to DR-P2V-12.

### 10.1 Engine-side session

`FixSession` is a FIX 4.4 initiator. One thread calls `onMessage`, `poll` and the send methods; nothing is allocated per message (`rates-fix/src/test/java/io/ratesalgo/fix/AllocationTest.java`: zero bytes over one million messages after 200,000 warm-up messages, 2026-10-31, JDK 25.0.5, Windows 11).

| Condition | Behaviour |
|---|---|
| Connect | Logon (35=A) with EncryptMethod 0, HeartBtInt, and ResetSeqNumFlag=Y when configured; both sequence numbers start at 1 |
| Inbound MsgSeqNum equal to expected | processed; expected increases by 1 |
| Inbound MsgSeqNum higher than expected | one ResendRequest (7=expected, 16=0); messages are dropped until resent messages (PossDup=Y) or a SequenceReset-GapFill close the gap. Logon, ResendRequest and Logout are acted on even when they arrive in a gap. Counted in `gapsDetected` |
| Lower, PossDup=Y | ignored as a duplicate (`duplicatesIgnored`) |
| Lower, no PossDup | Logout and disconnect (`sequenceTooLow`) |
| SequenceReset, GapFillFlag absent or N | expected set to NewSeqNo if higher, regardless of MsgSeqNum |
| ResendRequest received | one SequenceReset-GapFill (43=Y, 122, 123=Y, 36=next outbound) at MsgSeqNum BeginSeqNo; application messages are never resent (DR-P2V-03) |
| TestRequest received | Heartbeat echoing TestReqID |
| Nothing sent for HeartBtInt | Heartbeat |
| Nothing received for 1.2 HeartBtInt | TestRequest with TestReqID `TEST<n>` |
| Nothing received for 2 HeartBtInt, or no Logon reply within HeartBtInt | disconnect (`timeouts`) |
| BodyLength or CheckSum wrong, or a field is not tag=value | message dropped, sequence unchanged (`garbled`) |
| A field cannot be read as its type | session Reject (35=3) with RefSeqNum, RefTagID and SessionRejectReason 5 |
| SenderCompID or TargetCompID wrong | Reject, then Logout |

`SessionStats.sequenceErrors()` is gaps plus too-low numbers; the synthetic day requires it to be zero (10.7).

### 10.2 Tag sets per flow

Standard FIX 4.4 tags plus simulator-defined tags in the user range:

| Tag | Name | Values |
|---|---|---|
| 9501 | DealerCount | dealers in competition on an RFQ |
| 9502 | CoverPrice | second-best price, sent to the RFQ winner |
| 9503 | RfqOutcome | TA traded away, DA done away; on QuoteStatusReport also WN won, PS passed, EX expired, CX cancelled by us, CD cancelled on disconnect |
| 9504 | WorkupState | N normal, X workup exclusive window, W workup |
| 9505 | ExecMethod | R request for quote (SEF) |
| 9506 | TransactionClass | Q Required Transaction, P Permitted Transaction (SEF) |
| 9507 | BlockTrade | Y or N (SEF) |
| 9510 | VenueRejectCode | 11.5 |

Instruments are named by SecurityID (48) with SecurityIDSource (22) 4 = ISIN for bonds and 8 = exchange symbol for swap templates (`USD-SOFR-10Y`); Symbol (55) carries the same text. Ids that the gateway converts to int64 (QuoteReqID, QuoteID, ClOrdID, OrigClOrdID, OrderID, ExecID, ExecRefID) must be numeric text.

RFQ flow (D2C and SEF; the engine is a dealer):

| Direction | MsgType | Tags used |
|---|---|---|
| venue to engine | QuoteRequest R | 131; NoRelatedSym 146 with 55, 48, 22, 54 (client side), 38, 15, 126 ExpireTime; NoPartyIDs 453 with 448 client id, 447 D, 452 3; 9501; SEF adds 9505, 9506, 9507 |
| engine to venue | Quote S | 131, 117, 55, 48, 22, 132 and 134 (bid), 133 and 135 (offer), 62 ValidUntilTime, 60 |
| venue to engine | QuoteStatusReport AI | 131, 117, 297 (0 accepted, 5 rejected, 6 removed, 7 expired, 9 not found), 58, 9503, 9510 |
| engine to venue | QuoteCancel Z | 131, 117, 298=1, 295=1 with 55, 48, 22. FIX 4.4 has no QuoteCancelType 5 (by QuoteID; added in FIX 5.0), so type 1 with the QuoteID names the quote (DR-P2V-04) |
| engine to venue | QuoteStatusRequest a | 649, 117, 55, 48, 22; answered by AI echoing 649 |
| venue to engine | QuoteResponse AJ | 693, 131, 117, 694 (1 hit or lift, 3 expired, 5 done away with 9503, 6 pass), 44 and 38 on a hit, 9502 on a hit when there was a second quote, 54, 60 |
| venue to engine | ExecutionReport 8 | after a hit: 37, 17, 150=F, 39=2, 117, 131, 55, 48, 22, 54, 38, 32, 31, 151, 14, 6, 60; no ClOrdID |

Ids in the annotated messages below (QuoteRespID, OrderID, ExecID) are illustrative; every other value is what the named script produces.

One annotated hit (`d2c-rfq-hit-cover.json`, header fields 8, 9, 34, 49, 52, 56 and trailer 10 omitted):

```
35=AJ            QuoteResponse
693=12           QuoteRespID, unique per venue
131=1            QuoteReqID of the RFQ
117=1            our QuoteID
694=1            QuoteRespType 1: the client hit or lifted our quote
55=US91282CZG94  Symbol
48=US91282CZG94  SecurityID
22=4             SecurityIDSource 4: ISIN
54=1             client side: buy
44=98.015        traded price, per 100 face; becomes mantissa 98015000000
38=10000000      face in USD; becomes 1000000000 minor units
9502=98.02       cover price: second-best offer
60=20261102-13:30:00.000 TransactTime
```

Order flow (CLOB and SEF order book):

| Direction | MsgType | Tags used |
|---|---|---|
| engine to venue | NewOrderSingle D | 11, 55, 48, 22, 54, 60, 38, 40=2 (limit only), 44, 59 (0 day or 3 immediate-or-cancel), 1138 DisplayQty for an iceberg |
| engine to venue | OrderCancelRequest F | 41, 11, 55, 48, 22, 54, 60, 38 |
| engine to venue | OrderCancelReplaceRequest G | 41, 11, 55, 48, 22, 54, 60, 38, 40=2, 44 |
| engine to venue | OrderMassStatusRequest AF | 584, 585=7 |
| venue to engine | ExecutionReport 8 | 37, 11, 41 (cancel and replace), 17, 150 (0 new, F trade, 4 cancelled, 5 replaced, 8 rejected, H trade cancel with 19 ExecRefID, I status), 39, 55, 48, 22, 54, 38, 40, 44, 32, 31, 151, 14, 6, 60, 1138, 9504; rejects add 103=99, 58, 9510; mass status adds 584 and 911 TotNumReports (911=0 with no orders) |
| venue to engine | OrderCancelReject 9 | 37=NONE, 11, 41, 39=8, 434 (1 cancel, 2 replace), 102=1, 58 |

One annotated workup fill (`clob-workup.json`):

```
35=8             ExecutionReport
37=3             OrderID
11=2             our ClOrdID
17=4             ExecID, unique per venue
150=F            ExecType: trade
39=2             OrdStatus: filled
55=US91282CZG94  48=US91282CZG94  22=4
54=1             buy
38=1000000       order quantity, face
40=2  44=98      limit order; the price is the workup price, not our 98.0078125 limit
32=1000000       LastQty
31=98            LastPx
151=0  14=1000000  6=98
9504=X           traded in the workup exclusive window
```

Market data flow (CLOB and SEF):

| Direction | MsgType | Tags used |
|---|---|---|
| engine to venue | MarketDataRequest V | 262, 263 (1 subscribe, 2 unsubscribe), 264, 265=1, 267=2 with 269=0 and 269=1, 146=1 with 55, 48, 22 |
| venue to engine | MarketDataSnapshotFullRefresh W | 262, 55, 48, 22, 83, 268 with 269, 270, 271, 346, 1023 per level |
| venue to engine | MarketDataIncrementalRefresh X | 262, 268 with 279 (0 new, 1 change, 2 delete), 269, 55, 48, 22, 83 RptSeq per instrument, 270, 271, 346, 1023 |
| venue to engine | MarketDataRequestReject Y | 262, 281=0, 58 |

One annotated incremental (`clob-market-data.json`):

```
35=X             MarketDataIncrementalRefresh
262=1            MDReqID
268=1            one entry
279=1            MDUpdateAction: change
269=0            bid
55=US91282CZG94  48=US91282CZG94  22=4
83=3             RptSeq for this instrument
270=97.984375    price level
271=4000000      visible face at the level
346=2            orders at the level
1023=1           level rank, best = 1
```

### 10.3 Translation to and from SBE

`VenueGateway` (one per venue session) translates without floating point and without allocation:

| FIX | SBE | Notes |
|---|---|---|
| QuoteRequest R | QuoteRequest (10) | rfqId from 131, clientId from 448, dealerCount from 9501, expiryNanos from 126, receivedNanos from the gateway clock |
| QuoteResponse AJ | QuoteResponse (12) | 694=1 Done with tradedPrice, tradedQuantity, coverPrice; 3 Expired; 5 TradedAway or DoneAway by 9503; 6 Passed |
| QuoteStatusReport AI with 297=5 | QuoteResponse (12) Rejected | other AI values are acknowledgements, counted in `GatewayStats.quoteAcks` |
| ExecutionReport 8 | ExecutionReport (21) after `OrderStateTracker` | an RFQ trade (no 11) uses QuoteID as clOrdId; cumQuantity and leavesQuantity are the tracker's |
| ExecutionReport 8 with 150=H | TradeBust (44) | execId is the busted ExecID, price and quantity those of the busted fill |
| W | BookUpdate (2) Clear, then Add per level | venueSeqNo from 83 or the message's MsgSeqNum |
| X | BookUpdate (2) Add, Change or Delete per entry | trade entries (269=2) are not book levels and are skipped |
| Quote (11) | S | a side is sent only when its quantity is positive |
| QuoteCancel (43) | Z | |
| NewOrder (20) | D | Market maps to 40=1, Limit to 40=2 with 44; TimeInForce value is written as the FIX character; GoodTillDate adds 126 |
| OrderCancel (41) | F | |
| OrderReplace (42) | G | |

Units: FIX prices are decimal text parsed digit by digit into an int64 with exponent -9; a value with more than nine decimal places that are not zero is refused (`FixFormatException`). FIX quantities are face or notional in currency units and become minor units by an exact scale of 2. Times are UTCTimestamp with 0, 3, 6 or 9 decimals; the engine writes milliseconds.

### 10.4 Order state from execution reports

`OrderStateTracker` keeps fixed-capacity order state:

| Report | Rule |
|---|---|
| Fill (150=F) with an ExecID already seen | duplicate, dropped |
| Fill | quantity added to the order; cumQty is the sum of distinct fills, so fills arriving out of order still total correctly |
| Fill on an order already cancelled | late fill: applied and counted |
| Status report that would move a Filled, Cancelled or Rejected order back to New or PartiallyFilled | stale, dropped |
| Status report with an ExecID already seen | duplicate, dropped |
| PendingNew, PendingCancel, PendingReplace (150 = A, 6, E) | dropped |
| Cancel ack (150=4 with 41) | the cancel's ClOrdID becomes a second name for the order |
| Replace ack (150=5 with 41) | the order moves to the new ClOrdID |
| Trade cancel (150=H, 19=ExecID) | the fill's quantity is removed and a TradeBust emitted; an unknown ExecID is counted and ignored |

### 10.5 SBE book feed

Each CLOB (and the SEF order book) publishes `BookUpdate` (template 2) frames, SBE header included, one per changed price level among the top five per side:

- `venueSeqNo` starts at 1 and increases by 1 per frame across all instruments of the venue.
- Levels are keyed by price: Add (new price), Change (new quantity or order count at a price), Delete (price gone). `level` is the price's rank, 1 best, and is informational.
- `quantity` is visible face in minor units (currency units times 100), `orderCount` the orders at the price.
- Snapshot service: a request returns, for each instrument, one Clear then one Add per level, every frame carrying the sequence number of the last incremental already published.

Consumer rules (`BookFeedConsumer`): apply a frame whose sequence is the last plus one; ignore a frame at or below the last; on a frame above the last plus one, buffer it, mark the books stale, request a snapshot, apply it, then replay buffered frames above the snapshot's sequence in sequence order. Transport: in-process channel with fault injection for tests, one UDP datagram per frame for the standalone process.

### 10.6 Certification scripts

Each simulator ships JSON scripts under `rates-venues/src/main/resources/io/ratesalgo/venues/cert/`, in the shape venue certification documents use: an ordered list of request and expected response messages. Format:

```json
{"venue": "d2c", "flow": "rfq", "description": "...", "seed": 1, "steps": [
  {"venue": {"action": "rfq", "instrument": "US91282CZG94", "side": "1", "qty": "10000000", "dealers": 4,
             "competitors": ["98.03", "98.02"], "decision": "BEST"}},
  {"expect": {"35": "R", "131": "$rfq", "9501": "4", "126": "*"}},
  {"send": {"35": "S", "131": "$131", "117": "1", "133": "98.015", "135": "10000000"}},
  {"advance": 5000},
  {"do": "disconnect"},
  {"expectNone": true}
]}
```

`send` writes the fields in order (a list value repeats the tag); `expect` checks the next inbound message, where `"*"` means present and `null` absent; `$<tag>` takes the tag from the newest received message, `$rfq` is the last injected RFQ, `@now+<ms>` a timestamp. Venue actions: `rfq`, `decide`, `sefRfq`, `internal` (an order from a simulated participant), `bust`, `duplicateNextFill`, `reverseNextFills`, `lateFillOnNextCancel`. `CertRunner` connects the engine-side session as `RALGO`, and after every step waits until each side's next expected sequence number equals the other's next outbound number, so a check sees exactly the messages its step caused. Session-level rejects are recorded as inbound messages, so a message the venue's dictionary refuses fails the script.

Scripts and what they certify (all green in `CertSuiteTest`, 2026-10-31):

| Venue | Script | Flow |
|---|---|---|
| D2C | d2c-rfq-hit-cover, d2c-rfq-traded-away, d2c-rfq-done-away, d2c-rfq-passed, d2c-rfq-no-quote | RFQ outcomes |
| D2C | d2c-quote-status-cancel | quote validation, QuoteStatusRequest, QuoteCancel, unsupported message |
| D2C | d2c-cancel-on-disconnect | cancel-on-disconnect |
| CLOB | clob-order-lifecycle, clob-iceberg-size-selfmatch | orders, size and tick rules, iceberg, self-match prevention |
| CLOB | clob-market-data | MarketDataRequest, snapshot, incremental |
| CLOB | clob-workup | workup and its exclusive window |
| CLOB | clob-cancel-on-disconnect | cancel-on-disconnect |
| SEF | sef-rfq-required, sef-rfq-rules | RFQ-to-3, permitted, block |
| SEF | sef-credit-check | FCM pre-execution check |
| SEF | sef-order-book | order book execution |

### 10.7 Synthetic day

`rates-venues/src/test/java/io/ratesalgo/venues/SyntheticDayTest.java` runs 06:00 to 17:00 New York on 2026-11-02 in compressed time: the D2C arrival model generated 568 RFQs, the engine-side gateway quoted each one (536 done, 29 done away, 3 passed), and the CLOB took an engine order every quarter hour with its feed rebuilt by a consumer. Result on 2026-10-31 (JDK 25.0.5, Windows 11): zero sequence errors on both engine sessions, zero garbled messages, zero rejected quotes, feed consumer at the venue's last sequence with no gaps.

## Section 11. Venue simulators and reference data (P2 venues)

### 11.1 Common behaviour

- Each simulator is a QuickFIX/J 3.0.2 acceptor (`SocketAcceptor`, FIX 4.4 dictionary validation on, user-defined fields allowed, latency check off, messages persisted in memory for resends) with one session per engine CompID (`RALGO`, `RALGO2`).
- Time comes from a `Clock`; randomness only from `SimRng`, a `SplittableRandom` with a fixed seed. Every entry point (message, logon, logout, time) runs under the simulator's lock, so with the same seed, the same messages and the same clock readings a run repeats exactly (`D2cModelTest.sameSeedSameDay`).
- `VenueHarness` runs a simulator in-process on a free port with a `ManualClock` it alone moves; `EngineSession` connects our engine-side session to it. `VenueMain` (and `D2cRfqSimulatorMain`, `ClobSimulatorMain`, `SefSimulatorMain`) runs one as a standalone process on the system clock: `--venue d2c|clob|sef --port N --seed S --duration-millis T --feed-host H --feed-port P --auto-flow true|false`.
- Cancel-on-disconnect, every venue: when an engine session logs out or its connection drops, its live quotes are removed (QuoteStatus 6, RfqOutcome CD), its open RFQ participations end, its orders (resting and held) are cancelled and its market data subscriptions dropped.

### 11.2 Venue rules

D2C RFQ (`D2cRfqSimulator`): each RFQ goes to the logged-on engine dealers and to simulated dealers (named DLR-A to DLR-E), 3 to 5 in total. An engine quote is rejected (AI 297=5) if the RFQ is unknown, closed, not addressed to that dealer, has no price on the client's side, or has bid not below offer; a new quote replaces the dealer's previous one. The client decides when the window closes, or as soon as every dealer has answered for model-driven RFQs. Scripted RFQs decide only on `decide` or at the window. Outcomes and disclosure: LEARN.md Section 6.

D2D CLOB (`ClobSimulator`, `ClobBook`): limit orders only, day or immediate-or-cancel; quantity a positive multiple of the minimum size; price on the tick grid; iceberg display a multiple of the minimum size not above the quantity. Price-time priority; an iceberg slice refill goes to the back of its level. Self-match prevention: cancel the resting order (default) or the incoming one. Workup: a trade at P opens a workup at P for `workupMillis`; during the first `exclusiveMillis` only the opening trade's participants may trade, marketable orders from others are held; during workup a marketable order trades only at P and its remainder rests at P, and an order that would trade at another price is held until the workup ends; each workup trade restarts the timer when `extendOnTrade` is set; held orders are processed in arrival order when their window ends. A quantity reduction at the same price keeps priority; any other replace loses it.

SEF (`SefSimulator`): the order book of the CLOB without workup, plus RFQ. An RFQ for a swap whose template is MAT and whose notional is below the template's block threshold must go to at least `requiredMinDealers` dealers or it is refused (`Outcome.REFUSED`); other RFQs may go to any number. Pre-execution check (`FcmLimitHub`): an order reserves its notional against its owner's FCM limit at entry and releases the unfilled part when it ends; an RFQ acceptance reserves the client's and the winning engine dealer's notional; any breach rejects the order or the acceptance. Simulated dealers are assumed to have clearing capacity.

### 11.3 Parameters and presets

`rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`, preset `default` per venue. All values are declared assumptions (DR-P2V-05); EUR and GBP instruments, when added, reuse the shapes with the scaling of DR-004.

| Venue | Parameter | Default | Meaning |
|---|---|---|---|
| d2c | responseWindowMillis | 5000 | dealer response window |
| d2c | minDealers, maxDealers | 3, 5 | dealers in competition |
| d2c | dealerResponseProbability | 0.9 | chance a simulated dealer answers |
| d2c | dealerDelayMillis median, sigma | 800, 0.6 | lognormal response time |
| d2c | halfSpread bond median, sigma | 0.0078125, 0.5 | lognormal half spread, price |
| d2c | halfSpread swap median, sigma | 0.0025, 0.5 | lognormal half spread, rate in percent |
| d2c | clientToleranceMultiple median, sigma | 2.5, 0.4 | client accepts the best quote within this many median half spreads of mid |
| d2c | doneAwayProbability | 0.05 | client trades elsewhere |
| d2c | informedFraction, informedJumpMultiple, informedLagMillis | 0.2, 4.0, 30000 | informed clients and the mid move after their trades |
| d2c | markoutHorizonMillis | 300000 | markout horizon |
| d2c | midVolPerSqrtHour bond, swap | 0.06, 0.015 | random-walk volatility of mid |
| d2c | arrivals basePerHour | 30 | baseline RFQs per hour |
| d2c | arrivals events | 08:00 +60/h (10 min), 08:30 +240/h (6 min), 10:00 +120/h (6 min), 13:00 +90/h (8 min), 15:00 +120/h (20 min) | Gaussian intensity bumps, New York time |
| d2c | sizes bond | 1mm 35%, 5mm 35%, 10mm 20%, 25mm 8%, 50mm 2% | size preset |
| d2c | sizes swap | 10mm 30%, 25mm 30%, 50mm 25%, 100mm 15% | size preset |
| clob | tickSize, minQty | 0.0078125, 1000000 | 1/128 and 1mm |
| clob | workupMillis, exclusiveMillis, extendOnTrade | 5000, 2000, true | workup |
| clob | selfMatch, depth | CANCEL_RESTING, 5 | self-match mode, levels published |
| sef | requiredMinDealers, responseWindowMillis | 3, 5000 | RFQ-to-3 |
| sef | tickSize, minQty | 0.0001, 1000000 | order book |
| sef | fcmLimits | FCM-1 500mm, FCM-2 200mm | notional limits |
| sef | partyFcm | RALGO, 2001, 2005 on FCM-1; 2002 to 2004 on FCM-2 | clearing relationships |

### 11.4 Reference data schema

`rates-refdata` loads `data/refdata/v1/`: CSV files with `#` comment lines and one header row, and `MANIFEST.sha256` with one `<sha256>  <file>` line per file; the loader refuses a missing file or a digest mismatch, and every error names the file and line. Identifiers are synthetic with valid check digits (generated by `tools/gen_refdata_fixtures.py`; DR-006 provenance in `data/refdata/README.md`).

| File | Columns |
|---|---|
| bonds.csv | id, isin, cusip, market (UST, GILT, BUND), currency, tenor, coupon (percent), frequency (1 or 2), maturity, firstCouponDate, issueDate, firstSettlementDate, calendar, announcementDate, auctionDate, onTheRunFromDate |
| swap_templates.csv | id, market, currency, index, tenor, fixedFrequency, fixedDayCount, floatFrequency, floatDayCount, paymentLagDays, calendar, spotLagDays, madeAvailableToTrade, blockThreshold (currency units) |
| futures.csv | id, symbol, exchange, underlying, currency, contractSize, tickSize, notionalCoupon, deliveryMonth, firstDeliveryDate, lastTradingDate |
| parties.csv | id, code, name, type (DEALER, CLIENT, VENUE, FCM), tier |
| auctions.csv | market, tenor, isin, announcementDate, auctionDate, issueDate (must agree with bonds.csv) |
| holidays.csv | calendar (US-GS, UK, TARGET), date, name |

Rules: instrument ids are positive and unique across bonds, swaps and futures; ISIN and CUSIP check digits are validated and a CUSIP must equal ISIN characters 3 to 11; dates satisfy announcement before or on auction, auction before or on issue, issue before first coupon, first coupon before maturity. On-the-run: for a market and tenor, the bond with the latest `onTheRunFromDate` on or before the date (and not matured); a bond is when-issued from its announcement date until it is on the run, so the on-the-run rolls on the new issue's issue date. `CalendarSource` is the hook for `rates-analytics` calendars; `FixtureCalendarSource` reads `holidays.csv` and refuses unknown calendar names and dates outside the calendar years the file lists (2026 and 2027).

### 11.5 Market data quality and reject reason codes

`CompositeBuilder` checks each source quote in this order and journals a `MarketDataReject` (template 40) for each failure, keeping the source's previous quote:

| Code | MdRejectReason | Condition |
|---|---|---|
| 3 | NonPositivePrice | bid or ask not positive |
| 1 | CrossedQuote | bid above ask |
| 2 | LockedQuote | bid equal to ask |
| 11 | OutOfOrder | sequence number not above the source's last |
| 4 | Outlier | absolute distance of mid from the reference above the band |
| 10 | SequenceGap | sequence number jumped; journaled, quote still used |
| 5 | Stale | source's last quote older than the staleness limit; journaled once per transition |
| 6 | CrossedComposite | best bid above best ask across fresh sources; previous composite kept |
| 7 | LockedComposite | best bid equal to best ask; previous composite kept |
| 8 | FixingRepublicationLate | a changed fixing published after the day's cutoff |
| 9 | FixingRepublicationUnchanged | a republication with the same value |
| 12 | UnknownInstrument | reserved for sources naming an instrument not in the securities master |

The reference is a `ReferencePrice`; until the P1 fitted curve is wired it is a `BandReference` set per instrument. Per-source metrics: updates, rejects, stale transitions, last update time, largest age seen. `MarketDataQualityTest.injectedBadTicksNeverReachTheComposite` (jqwik, 200 tries of 50 to 400 ticks with crossed, locked, non-positive and outlier ticks injected) checks that the composite never takes a price from a rejected tick and is never crossed or locked.

Venue reject codes (tag 9510):

| Code | Meaning |
|---|---|
| 1 | unknown instrument |
| 2 | duplicate ClOrdID |
| 3 | order type not supported (limit only) |
| 4 | time in force not supported |
| 5 | size, tick or display rule broken |
| 6 | pre-execution credit check failed at order entry (SEF) |
| 7 | pre-execution credit check failed at RFQ acceptance (SEF) |
| 8 | SEF quote not accepted (unknown or closed RFQ, not addressed, no price on the client's side) |

## Section 12. Quoting parameters and the hit-rate preset (P3)

The quoting engine is `io.ratesalgo.quoting.QuotingEngine` in `rates-quoting`. It consumes QuoteRequest (10) and QuoteResponse (12), sends Quote (11) and QuoteWithdraw (60) to the gateway, and calls the risk engine before every quote. All prices below are in the instrument's own quote convention (price per 100 for bonds and futures, rate in percent for swaps); "bp of value" means the quote move that changes the position's value by one basis point of PV01, which is `|PV01 per unit| / quoteValue` quote units (`RiskEngine.quotePerBp`).

### 12.1 Parameters

Every default is a declared assumption, set in `QuotingConfig` and changed through the control plane.

| Parameter | Default | Unit | Meaning |
|---|---|---|---|
| `firmTtlNanos` | 5,000,000,000 | ns | validity of a firm quote; capped by the request's expiry |
| `maxInputAgeNanos` | 2,000,000,000 | ns | oldest input a quote may be priced from; older inputs pull live quotes |
| `heartbeatTimeoutNanos` | 3,000,000,000 | ns | silence from a required heartbeat source that pulls every quote |
| `requoteThrottleNanos` | 250,000,000 | ns | minimum interval between quotes to one client on one instrument and side |
| `minHalfSpreadBp`, `maxHalfSpreadBp` | 0.10, 10.0 | bp of value | bounds on the half-spread after widening and tiering |
| `kappa` | 2e-6 | per currency unit | inventory risk aversion in the skew (12.4) |
| `maxSkewBp` | 2.0 | bp of value | bound on the inventory skew, the size term and the axe each |
| `maxImproveBp` | 0.25 | bp of value | furthest a quote may sit on the client's side of fair |
| `baselineVolBp` | 6.0 | bp per day | realised volatility at which no widening applies |
| `volWideningPerBp` | 0.05 | bp per bp | half-spread added per bp of daily volatility above the baseline |
| `winnersCurseBp` | 0.10 | bp | scale of the winner's-curse charge (12.2) |
| `learningRate` | 0.01 | | step of the feedback update of the hit-rate model |
| `minQuoteQuantity` | 100,000,000 | minor units | quote step; sizes round down to a multiple |
| `maxLiveQuotes` | 1,024 | | quotes held at once (live and withdrawn) |
| `duplicateWindow` | 4,096 | | RFQ ids remembered for duplicate detection |
| `markoutHorizonNanos` | 60,000,000,000 | ns | delay after a fill at which its markout is measured |
| event windows | none | epoch ns, multiplier | half-spread multiplier during scheduled events (at most eight) |

The client tier table (`TierTable`, versioned, changed by proposal and second-person approval in `TierTableControl`) gives per tier a spread multiplier, a maximum quantity, firm or subject, and blocked. The toxicity table holds an exponentially weighted mean markout per client (weight 0.1) and charges `max(0, -mean) * n / (n + 5)` bp.

### 12.2 Spread

With s the distance of the quote from fair in bp of value, the win probability is

\[
z = b_0 + b_1 s + b_2 \ln(q / q_{typ}) + b_3 T + b_4 L + b_5 (n - 1), \qquad P(\text{win} \mid s) = \frac{1}{1 + e^{-z}}
\]

with q the quantity, \(q_{typ}\) the instrument's typical clip, T the tenor in years, L one inside the currency's liquid session (USD 12:00 to 21:00 UTC, EUR and GBP 07:00 to 16:30 UTC) and n the dealer count (3 when the venue does not disclose it). The cost of a fill in bp is

\[
c = \text{toxicity}(client) + w \sqrt{2 \ln n} \cdot \frac{\sigma}{\sigma_0} + h(q)
\]

where the second term is the winner's curse (the expected maximum of n normal pricing errors grows like \(\sqrt{2 \ln n}\)), \(\sigma / \sigma_0\) is realised over baseline volatility, and h is the cheapest single-instrument hedge cost of the trade's PV01 under the hedge cost model, in bp. The engine chooses

\[
s^* = \arg\max_{0 \le s \le s_{max}} P(\text{win} \mid s) (s - c)
\]

by golden-section search (48 iterations, no allocation); the maximiser satisfies \(s^* = c + 1 / (-b_1 (1 - P(s^*)))\), checked in `PackageAndClobTest`. The half-spread is then \(\text{clamp}((s^* m_{event} + v (\sigma - \sigma_0)^+) m_{tier}, s_{min}, s_{max})\).

### 12.3 Preset schema

The engine reads a preset in the `data/presets.yaml` schema; parameters are one-line flow mappings with two extra fields, `band_low` and `band_high`, and every `value` must lie inside its band. Required keys: `intercept`, `spread_bp` (negative, band negative), `log_size`, `tenor_years`, `liquid_session`, `dealers_minus_one`.

```yaml
presets:
  - name: quoting_hit_rate_default
    fitted_from: assumption
    parameters:
      intercept: { value: 1.0, standard_error: null, band_low: 0.0, band_high: 2.0 }
      spread_bp: { value: -1.6, standard_error: null, band_low: -2.4, band_high: -1.0 }
      log_size: { value: -0.15, standard_error: null, band_low: -0.4, band_high: 0.0 }
      tenor_years: { value: -0.01, standard_error: null, band_low: -0.03, band_high: 0.0 }
      liquid_session: { value: 0.2, standard_error: null, band_low: 0.0, band_high: 0.5 }
      dealers_minus_one: { value: -0.35, standard_error: null, band_low: -0.6, band_high: -0.15 }
```

A preset named `quoting_hit_rate_default` in `data/presets.yaml` takes precedence; otherwise the copy in `rates-quoting/src/main/resources/quoting-hit-rate-default.yaml` is used (DR-P3-02). Feedback moves `intercept` and `spread_bp` and clamps them to their bands.

Diagram: [research to production parameters](docs/site/pages/diagrams.md#research-to-production-parameters).

### 12.4 Skew

With p the book's PV01 ladder in the instrument's currency, u the instrument's per-unit ladder, U its parallel PV01, \(\Sigma\) the key-rate covariance (14.2) and Q the quoted quantity in major units:

\[
\text{skew} = \text{clamp}\left(\kappa \frac{u^\top \Sigma p}{|U|}, \pm \text{maxSkew}\right), \qquad \text{size} = \min\left(\tfrac{1}{2} \kappa Q \frac{u^\top \Sigma u}{|U|}, \text{maxSkew}\right)
\]

An offer sits \(h - \text{skew} + \text{size} - \text{axe}\) from fair and a bid \(h + \text{skew} + \text{size} - \text{axe}\), each at least \(-\text{maxImprove}\). A book long duration in the instrument's risk direction has positive skew and offers better. The axe is the hedger's improvement for the side that moves the book toward its hedge (14.4). Bids round down and asks round up to the Decimal9 grid.

### 12.5 Fair value

Bonds and futures: microprice of the composite book, \((b \cdot A + a \cdot B) / (A + B)\) with b, a the best prices and B, A their sizes, stamped with the book's update time; without a two-sided book, the pricing source's fair mid. Swaps: the pricing source's par rate plus \(\beta\) times the futures signal, the change in futures-implied yield (bp) between the futures mid and its microprice. Every product: plus the installed `FairValueSignal`, zero unless a `ValidationRecord` permits it (DR-P3-03).

### 12.6 RFQ decision order and lifecycle

For each QuoteRequest, after `poll`: duplicate (venue, rfqId) within the window; kill switch ACTIVE; inputs healthy (heartbeats, sessions, feed sequence); instrument known and quantity positive; request not expired; client tier not blocked; re-quote throttle; fair value present; oldest input within `maxInputAgeNanos`; size reduced to `min(request, tier max, risk headroom)` rounded down to the step, pass if zero; capacity of the live book; expected adverse cost below `maxHalfSpreadBp`, else no spread up to the cap has positive expected value and the engine passes with `NEGATIVE_EDGE`; spread, skew, axe; risk pre-trade check (Section 13.2). A failure at any step is a PASS with the `PassReason` in `RfqOutcome`. Otherwise the quantity is reserved and a Quote sent: FIRM with `validUntilNanos = min(now + firmTtl, request expiry)`, or SUBJECT with `validUntilNanos = 0` (DR-P3-10). The diagram is docs/diagrams/rfq_decision.mmd.

`poll(now)` expires quotes with `validUntilNanos <= now` and releases their reservation; sends QuoteWithdraw (reason StaleInput) for any live quote whose oldest input is older than `maxInputAgeNanos`; sends QuoteWithdraw for every live quote when inputs turn unhealthy (reasons HeartbeatLoss, SessionLoss, FeedGap); and measures due markouts. A withdrawn quote keeps its reservation until answered or expired. On QuoteResponse: Done books the fill at the traded price, teaches one win (a disclosed cover on the client's side of the trade adds to the mean cover distance and is not a second observation, DR-R2R-08); TradedAway teaches a loss; every status releases and frees the quote.

Diagram: [RFQ end to end with timings](docs/site/pages/diagrams.md#rfq-end-to-end-with-timings).

### 12.7 Package RFQs

`PackageQuoter` prices curves, butterflies, spread-overs, invoice spreads, IMM rolls and switches as one trade with DV01-neutral leg ratios (IMM rolls one for one). The charge is the sum of the legs' spread charges, minus the netted-hedge saving (sum of the legs' hedge costs minus the hedge cost of the net ladder's parallel PV01), plus the inventory charge on the net ladder; floored at `minHalfSpreadBp` times the largest leg PV01. The charge is applied as an equal bp distance on every leg. Each leg passes a one-sided quote check. Schema v1 has no package message; the wire form is P2's.

### 12.8 CLOB quoting

`ClobQuoter` rests a bid and an ask at fair plus or minus `halfSpreadBp` (default 0.25 bp), the bid rounded down and the ask up to the venue tick given to `enable(instrumentId, tickMantissa)` (DR-R2R-12; `enable(instrumentId)` keeps the Decimal9 grid). It keeps an order while the target is within `requoteThresholdBp` (0.10 bp) to keep queue priority, and tracks the quantity ahead from the displayed size at its level. It cancels both sides for `cooldownNanos` (1 s) when the futures signal moves more than `futuresJumpBp` (0.5 bp) between updates, and the threatened side when book imbalance \((B - A) / (B + A)\) exceeds 0.6 in absolute value. It replaces at most once per side per `minRequoteNanos` (50 ms), rests at most `participation` (0.25) of the displayed size at the best price and never more than the risk headroom, and never places a buy at or above its own resting sell or a sell at or below its own resting buy. Orders are NewOrder (20), Limit, Day; cancels are OrderWithdraw (61). Reservations are released on the venue's ExecutionReport.

### 12.9 Learned hit rate, cover averaging and tier defaults (round 2 fix A1)

`HitRateModel(preset, learningRate)` and `HitRateModel(preset, learningRate, slopeLow, slopeHigh)`: the coefficients start at the preset's central values. `update(base, spreadBp, won)` takes one Newton step on (b0, b1) of the logistic log-likelihood, with an information matrix that starts at the prior precision `1 / learningRate` and forgets with a window of `WINDOW` (5,000) outcomes. The coefficients are then clamped to `[INTERCEPT_LEARN_LOW, INTERCEPT_LEARN_HIGH]` (-3.0, 5.0) and `[slopeLow, slopeHigh]` (default `SLOPE_LEARN_LOW` -8.0 to `SLOPE_LEARN_HIGH` -0.2). The slope bounds must contain the preset's band and stay negative. A learning rate of 0 counts outcomes and learns nothing. `base(...)` is the part of z that depends on neither the spread nor the intercept. Report accessors: `learnedSlope()`, `learnedIntercept()`, `slopeStandardError()` (NaN when learning is off), `slopeOutsideDeclaredBand()`, `declaredSlopeLow()`, `declaredSlopeHigh()`, `learnLow(i)`, `learnHigh(i)`, `wins()`. The preset schema (12.3) is unchanged; its band bounds the starting assumption, not learning.

`SpreadModel(hitRate)` and `SpreadModel(hitRate, priorCoverSigmaBp)`: `winProbability(base, s)` is the hit-rate model averaged over a cover distributed normally around the expected cover, with 8-point Gauss-Hermite quadrature, and `optimal(base, cost, maxHalfSpread)` maximises `winProbability * (s - cost)` by golden-section search on [0, maxHalfSpread]. `observeCoverGap(gapBp)` feeds the gap between a winning price and the disclosed cover into running moments with a window of `COVER_WINDOW` (500). `coverSigmaBp()`, `meanCoverGapBp()` and `coverGaps()` read them. Gaps are seen only on wins, so the observed spread is a floor, not a fit. With no gaps and a prior of 0 the result is the point estimate.

`TierTable.standard(version, maxQuantity, clients)`: three firm tiers, none blocked, spread multipliers 1.0, 1.15 and 1.35 (`standardMultiplier(tier)`); unmapped clients get tier 2. `unblocked(newVersion, widening)` returns a copy in which every blocked tier quotes, with its multiplier times `widening` (at least 1; `DEFAULT_BLOCK_WIDENING` 1.5). `blockedTiers()` counts tiers that refuse every quote.

### 12.10 CLOB inventory skew (round 2 fix B)

`ClobQuoter.inventorySkew(covariance, kappa, maxSkewBp)` turns on inventory skew with the RFQ engine's parameters (`QuotingConfig.kappa()`, `maxSkewBp()`) and its key-rate covariance. At each update both targets move by `skew = clamp(kappa * u' Sigma p / |U|, -maxSkewBp, maxSkewBp)` bp: the bid target is `fair - (halfSpreadBp + skew)` and the ask target `fair + (halfSpreadBp - skew)`, each then rounded to the venue tick (12.8). A long book (positive skew) lowers both sides. Kappa 0 turns it off; it is off until called. `lastSkewBp()` is the skew at the last update.

### 12.11 RFQ recording, fair-move withdraw, release calendar and package controls (round 2 fix A2)

`QuotingEngine.recorder(RfqRecordListener)` (default `RfqRecordListener.NONE`). `onDecision(RfqOutcome)` is called for every RFQ decision, pass or quote, and the outcome object is reused after the call. `onResult(rfqId, quoteId, status, ourPrice, coverPrice, coverGapBp, rank)` is called for every venue outcome of our quotes, matched or booked late. `coverGapBp` is how far our price sat behind the cover for the client (positive when the cover was better; NaN without a cover). `rank` is 1 when our price was at least as good as the cover (or a Done without a cover), 2 when the cover was better, and 0 when unknown. `RfqOutcome` exposes `venueId()`, `rfqId()`, `clientId()`, `instrumentId()` and the decision. Counters: `coversRecorded()`, `wonBehindCover()`, `meanWonBehindCoverBp()`, `fairMovePulls()`.

Client markouts are measured from the fair value at the trade, not from the traded price.

`QuotingConfig.fairMoveWithdrawBp(v)` (default 0.25 bp; 0 off): `poll` withdraws a live quote with `CancelReason.AdverseSignal` when fair value has moved against it by more than `v`.

Release calendar, on by default: `QuotingConfig.usReleaseSchedule(from, to)` returns the epoch nanos of 08:30 and 10:00 New York on weekdays in the range, and the default calendar covers 2026 and 2027. `releaseCalendar(releaseNanos)` replaces it, `releasePullNanos(before, after)` sets the pull window (default 60 s before, 30 s after) and `releaseWidening(afterNanos, multiplier)` sets the widening after it (default 5 minutes at 2.355, the E3 holdout). In the pull window live quotes are pulled and RFQs are passed with `PassReason.RELEASE_WINDOW`. `releases()` and `inReleasePull(nowNanos)` read the calendar.

`PackageQuoter` (Section 12.7) applies the single-RFQ gates in the same order: kill switch, input health, release pull window, instrument and currency, client re-quote throttle, fair value with input age, and `RiskEngine.checkQuote` on every leg. A quoted package reserves every leg until answered or expired. `onResponse(packageId, done)` books a Done to risk, including one that arrives after expiry, `poll(nowNanos)` expires packages, and `onKill(reason, epochNanos)` withdraws them. The spread is charged on the net ladder's risk-equivalent PV01, `R = sqrt(L' Sigma L) * sum P_i / sum sqrt(d_i' Sigma d_i)`, so a DV01-neutral curve trade pays for its curve risk, not for the sum of its legs. `Result` exposes `reason()`, `packageId()`, `validUntilNanos()` and `netRiskPv01()`. Counters: `heldPackages()`, `packagesQuoted()`, `packageFills()`, `lateFills()`, `expiries()`, `unmatchedResponses()`. The desk does not register the quoter with the kill switch yet; until it does, `quote` reads the kill switch itself.

## Section 13. Risk limit semantics and check order (P3)

`io.ratesalgo.risk.RiskEngine` in `rates-risk`. PV01 is PV(rates + 1bp) minus PV(base) in major currency units, per pillar of `KeyRateTenors` (1Y, 2Y, 3Y, 5Y, 7Y, 10Y, 20Y, 30Y); a long bond is negative.

### 13.1 State

Per instrument: position (minor units), reserved buy and sell quantity, the PV01 contribution of the position, and the per-unit ladder at the current curve. Per currency: the ladder (sum of contributions), PV01 by product family, cash and mark value for daily P&L. A fill revalues one instrument; `onCurveMove(currency)` revalues that currency's instruments and reservations; `onFairValue` re-marks. `fullLadder` recomputes from positions alone; the property test `RiskEngineTest.incrementalLadderEqualsFullRevaluation` (300 random sequences of fills and curve moves) asserts agreement within 1e-6 of gross traded notional, as do `ChaosTest` and `MillionRfqTest`. Exposures: basis (futures against cash bonds, the PV01 by which they offset, signed as futures), swap spread (swaps against bonds plus futures), curve spread (P&L of a 2s10s steepener of 1 bp, -0.5 bp at 2Y and +0.5 bp at 10Y, linear between and flat outside).

### 13.2 Pre-trade check order

`checkQuote` (both sides, either may be zero) and `checkOrder` run, in order, stopping at the first failure:

1. Kill switch not ACTIVE: `REJECT_KILLED` (-1), no breach message.
2. Instrument, sensitivity or fair value unknown: `REJECT_UNKNOWN` (-2), no breach message.
3. MaxOrderSize: quantity above `maxOrderSize`.
4. FatFinger: quantity above `fatFingerQuantity`, or price more than `fatFingerFraction` (relative) from fair.
5. PriceCollar: price more than `priceCollarBp` bp of value from the pricing source's fair.
6. Position: position plus reserved plus requested beyond plus or minus `maxPosition` on either side.
7. BucketDv01: on any pillar, the ladder plus the worst case of every reservation filling or not beyond plus or minus the pillar limit (scope is the pillar index).
8. NetDv01: the same for parallel PV01 (scope is the currency index).
9. GrossDv01: sum over pillars of the larger absolute extreme beyond `grossPv01`.
10. DailyLoss: P&L since `startOfDay` below minus `dailyLoss`.
11. QuoteRate or OrderRate, then MessageRate: token buckets of `rate * burstSeconds` tokens.

A failure at 3 to 11 sends one RiskLimitBreach (30) to the control sink with the limit kind, scope (instrument id, pillar index or currency index), limit and observed values (Decimal9, saturated), the journal sequence of the rejected message and the time, and returns the `LimitKind` value. Orders whose parallel PV01 offsets the currency's net PV01 without overshooting, counting every other working reservation on the same side as filled, skip 7 to 10 (DR-P3-05, DR-R2R-09). Cancels and quote withdrawals are counted against the message rate but never refused. On success, one token is taken from each rate bucket involved.

### 13.3 Post-trade alerts

After every fill, curve move and re-mark the engine evaluates position, pillar, net, gross and daily loss on the actual state and sends one RiskLimitBreach (with `rejectedMessageSeqNo` 0) when a limit becomes breached; it sends nothing more until the limit clears and is breached again. A new daily-loss breach triggers the kill switch with reason LimitBreach when `killOnDailyLoss` is set (the default).

### 13.4 Kill switch

`KillSwitchController`: ACTIVE, KILLED, RESET_PENDING (docs/diagrams/kill_switch_state.mmd). `trigger(reason, scope, requestedBy)` from ACTIVE or RESET_PENDING calls every registered listener in registration order (quoting engine: QuoteWithdraw for every live quote; CLOB quoter: OrderWithdraw for every resting order; hedgers: `HedgeOrderSink.cancelAll` and release of reservations) and then sends one KillSwitch (31); from KILLED it only counts. `requestReset(operator)` moves KILLED to RESET_PENDING; `approveReset(approver, inputsHealthy)` moves to ACTIVE only for a different person with healthy inputs.

### 13.5 Breaches, flatten-only and basis limits (round 2 fix C)

A PV01 limit that is already breached does not freeze the book. A quote or order passes the pillar, net and gross checks when none of those worst-case figures moves further past its limit (each may stay where it is, with a relative tolerance for rounding). Flow that reduces a breach is accepted; flow that adds to it, or overshoots through zero into a new breach, is refused. A one-sided quote whose fill offsets net PV01 skips only the daily-loss check.

`flattenOnly()` is true while the kill switch is not ACTIVE because of this engine's own post-trade daily-loss breach (KILLED, or RESET_PENDING after it) and no other kill has happened since. In that state risk-reducing orders still pass `checkOrder`, so hedgers keep flattening, and every other order and every quote is refused with `REJECT_KILLED`.

`RiskLimits.basisPv01(limit)` and `swapSpreadPv01(limit)` bound basis and swap-spread exposure on positions plus the checked quantity at either extreme (other reservations are left out). An exposure already past its limit may shrink but not grow. A refusal returns `REJECT_BASIS` (-3) or `REJECT_SWAP_SPREAD` (-4) without a breach message, since the schema has no limit kind for them; post-trade breaches are counted by `basisBreaches()`. Risk-reducing orders skip the PV01 envelope but not these two limits.

## Section 14. Hedger objective and solver tolerances (P3)

`io.ratesalgo.hedge` in `rates-hedge`.

### 14.1 Objective

With r the residual ladder (book plus working hedge parents), H the per-lot ladders of the hedge instruments, \(\Sigma\) the daily key-rate covariance (bp squared) and x the lots to trade:

\[
\min_x \; \tfrac{1}{2} \kappa (r + Hx)^\top \Sigma (r + Hx) + \sum_j \left( a_j |x_j| + b_j x_j^2 \right), \qquad |x_j| \le X_j
\]

with \(a_j\) the half spread plus basis cost per lot and \(b_j\) the impact per lot squared from `HedgeCostModel`. Instruments in another currency have \(X_j = 0\). With \(x = p - m\), \(p, m \ge 0\), this is \(\min \tfrac{1}{2} y^\top Q y + c^\top y\) over a box, \(Q = \kappa [G, -G; -G, G] + 2\,\text{diag}(b, b)\), \(G = H^\top \Sigma H\), \(c = [\kappa H^\top \Sigma r + a; -\kappa H^\top \Sigma r + a]\).

### 14.2 Assumptions

Default covariance: daily volatilities 6.0, 6.5, 6.8, 7.0, 7.0, 6.8, 6.2, 5.8 bp for 1Y to 30Y and correlation \(e^{-0.06 |t_i - t_j|}\). Default hedge risk aversion \(\kappa = 10^{-5}\) per currency unit (`Desk.DEFAULT_HEDGE_KAPPA`). Hedger defaults: first tranche 0.5 of the plan, half-life 30 s, trigger one-day residual standard deviation 10,000, axe at most 0.25 bp, parent limit 2 bp of value from arrival mid.

### 14.3 Solver

`BoxQpSolver`: FISTA (accelerated projected gradient) with gradient-based restart, step 1/L with L the largest absolute row sum of Q. Stop when the projected-gradient residual \(\max_i |x_i - P(x_i - g_i / L)| \cdot L\) is at most `tolerance` times \(\max(1, \max_i |c_i|)\); the desk uses tolerance 1e-9 and at most 20,000 iterations. `BoxQpSolverTest` compares it with brute-force enumeration of all \(3^n\) active sets on 400 random strictly convex problems of 1 to 6 variables: objective within 1e-7 relative to \(\max(1, |f^*|)\), solution within 1e-4.

### 14.4 Staging, axes and analytics

On each timer the hedger measures r; below the trigger nothing happens and the episode ends. Otherwise it plans and sends the first-tranche fraction of each instrument's planned lots (urgency 0, immediate), then on later timers \(1 - 2^{-\Delta t / t_{1/2}}\) of the current plan (urgency now plus the half-life), at least one lot while any is planned. Every parent passes `checkOrder` and reserves its quantity until filled or done. While an episode is active, `axeBp(instrument, side)` is `axeMax * min(1, -corr)` where corr is the \(\Sigma\)-correlation between the trade's PV01 and r, and zero when the trade adds variance. `HedgeAnalytics` records slippage of each fill against the arrival mid (positive is a cost, in currency and PV01-weighted bp), the residual variance and net PV01 at each timer, and calls the `BasisAttribution` hook.

Diagram: [hedge and route](docs/site/pages/diagrams.md#hedge-and-route).

### 14.5 Hedging under the daily-loss kill and basis attribution (round 2 fix C)

A kill cancels every parent of the `Hedger`, except a kill from the risk engine's daily-loss limit (`RiskEngine.flattenOnly()`): then the parents keep working and timers keep sending risk-reducing tranches, each passing `checkOrder`. `flattenKills()` counts such kills.

`ExposureBasisAttribution(risk, currency)` implements `BasisAttribution`. `onHedgeFill(...)` books each hedge fill; `basisAdded()`, `swapSpreadAdded()`, `basisExposure()`, `swapSpreadExposure()`, `slippage(productType)` and `fills()` read the totals. `Desk.basisAttribution(currency)` returns the instance wired for that currency.

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
o = s\,\big(\tfrac{a+b}{2} - m\big),\quad h = \tfrac{a-b}{2},\quad I(q) = y\,\sigma_d\,(q/\mathrm{ADV})^{\alpha},\quad L = k_L\,\sigma_s\sqrt{\ell},\quad A = k_A\,\sigma_s\sqrt{T}
\]
\[
c_{aggr}(q) = o + h + I(q) + f_t + L,\qquad c_{pass}(q) = o + P\,(-h + I(q) + f_m) + (1-P)\,(A + h + I(q) + f_t) + L
\]

where \(s\) is +1 for a buy and -1 for a sell, \(m\) is the consolidated mid (the mid of the highest bid and lowest ask over the decision's rows with a two-sided quote), so the venue offset \(o\) charges a venue whose book sits at a worse price than the others (zero when all mids agree; DR-R2R-04, `CostModelTest.aVenueQuotingAtABetterPriceWinsTheSlice`), \(P\) is `QueueFillModel.probability(ceil(Q/λ), ceil(q/λ), r/(2λ), θ, T)` (complete-fill probability, Cont, Stoikov and Talreja 2010, uniformisation as in the owner's earlier library, API_SPEC.md Section 13.1 there; tolerance 1e-10 against the Poisson tail when \(\theta = 0\), `CostModelTest`). The chosen cost is \(c_{pass}\) if passive is allowed and either aggressive is not allowed or \(c_{pass} < c_{aggr}\); otherwise \(c_{aggr}\). The expected queue wait reported is \((\lceil Q/\lambda\rceil + \lceil q/\lambda\rceil)/(r/2\lambda)\), capped at 1e6 s. All doubles are rounded to Decimal9 with `Math.round(x * 1e9)` when written to the table; inputs that enter the model as doubles (trade rate, fees) are taken from the rounded table values, so a decision re-evaluated from the journal reproduces every mantissa.

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
| router | -0.2565 | | |
| single-venue aggressive | 2.6822 | 2.9388 | [2.7792, 3.0631] |
| even split aggressive | 2.5108 | 2.7673 | [2.5802, 2.9026] |

Re-run 2026-10-31 after the venue offset of DR-R2R-04 was added to the cost model; the P4 gate run before it gave a router cost of -0.1957 and savings of 2.8780 [2.7210, 2.9855] and 2.7065 [2.5309, 2.8272] bps.

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

### 15.8 Filled-child adverse cost, message cost and per-instrument volatility (round 2 fix C)

`RouterParams` gains three components. `filledAdverseFactor` is the expected adverse move after a passive child fills, in units of sigma over its expected queue wait (capped at the passive horizon), in [0, 5]. `messageCostPoints` is the cost of one order message (new order or cancel) in price points on one lot of the venue, in [0, 1]. `volatility` (`RouterParams.Volatility.of(instrumentIds, sigmaDailyPoints)`, `NONE` by default) is the daily volatility per instrument; unlisted instruments use `sigmaDailyPoints`. `sigmaDailyPoints(id)` and `sigmaPerSqrtSecond(id)` read it. The constructor without these components sets them to 0 and `NONE`, which is the previous behaviour. `withControls(configVersion, filledAdverseFactor, messageCostPoints, volatility)` returns a copy with them set. `RouterPresets.globalWithControls(volatility)` is `global()` with 0.75 sigma and 0.0001 points per lot per message at `CONTROLS_CONFIG_VERSION` (2). These are assumptions, not in `data/presets.yaml` and not the default.

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

### 16.6a Depth-walk hedge fills, best-price client and coupon carry (round 2 fix D)

`HedgeCostModel.withDepthWalk(on)` returns a copy that fills a market hedge level by level against the displayed quantity at each price; `depthWalk()` reads the setting. The default, and `fromPreset`, keep the linear cost rule, so earlier ledger rows reproduce. `RfqOutcomeModel.ClientChoice` is `HIT_RATE` (default: the soft logistic of the hit-rate model) or `BEST_PRICE` (the client trades with the best price: we win inside the cover, lose outside, and a tie is a fair coin). `withClientChoice(c)` returns a copy with the other rule and `clientChoice()` reads it. Both rules draw the same numbers in the same order. `CarryModel.bondCoupons(annualCouponByInstrument)` accrues each bond's coupon ACT/365 on the carry line next to funding; `CarryModel.NONE` books nothing.

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

Diagram: [experiment lifecycle](docs/site/pages/diagrams.md#experiment-lifecycle).

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
- **Average cost.** Weighted average cost. A trade in the direction of the position adds `price * quantity` to the cost; a trade against it realises `closed * (price - average)` through the valuation convention and removes the closed share of the cost; a trade through zero closes the old position and opens the remainder at its own price. Intermediate values carry 34 significant digits (`MathContext.DECIMAL128`). The property test `PositionCalculatorPropertiesTest` checks that realised plus unrealised P&L equals the trades' cash flow plus the remainder at the mark, to 1e-6 minor units, for random trade sequences.
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

States NEW, SUBMITTED, ACCEPTED, CLEARED, REJECTED; events SUBMIT, ACCEPT, CLEAR, REJECT. Transitions: NEW to SUBMITTED on SUBMIT; SUBMITTED to ACCEPTED on ACCEPT; ACCEPTED to CLEARED on CLEAR; SUBMITTED or ACCEPTED to REJECTED on REJECT. Every other pair is refused: the state does not change and the refusal is kept in the history. CLEARED and REJECTED are terminal. A rejection voids the trade; the caller journals a `PostTradeBust` with reason ClearingRejected. `ClearingHouseSimulator` accepts within a per-account gross notional limit, rejects beyond it, and clears accepted trades at the end of the cycle. Property tests (`ClearingLifecyclePropertiesTest`): terminal states absorb every event, a refusal never changes state, CLEARED is reached only through NEW, SUBMITTED, ACCEPTED, and replaying the history through the pure transition function reaches the same state.

### 17.7 Settlement lifecycle (bonds) and the cost of a fail

States PENDING, SETTLED, FAILED, CANCELLED; events SETTLE, FAIL, CANCEL. PENDING goes to SETTLED, FAILED or CANCELLED; FAILED stays FAILED on another FAIL and goes to SETTLED (cured) or CANCELLED; SETTLED and CANCELLED are terminal. SETTLE and FAIL are refused before the settlement date. The settlement date is the trade date plus the settlement lag in business days (`BusinessCalendar`; T+1 for Treasuries and gilts, T+2 for Bunds; P1 binds the real calendars). `daysFailed` counts calendar days from the settlement date to the cure, or to the as-of date while still failing.

Cost of a fail (`FailCost`, actual/360, from our point of view):

- funding: `proceeds * fundingRate * days / 360`, a loss if we are the failing deliverer, a gain if we are failed to;
- TMPG fails charge for Treasuries: `proceeds * max(3% - referenceRate, 0) * days / 360`, paid by the failing deliverer.

Worked example (`SettlementPropertiesTest.workedFailExample`): proceeds 9,950,000.00 USD failing for 3 days with funding at 4.30% costs 3,565.42 USD; the fails charge is zero at a 4.00% reference rate and 1,658.33 USD at 1.00%.

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

`DailyReconciliation` runs five reconciliations: `drop-copy` (trades against venue drop copies), `drop-copy-pos` (net quantity per instrument, our side adjusted for explained trade breaks), `books-records` (trades against those rebuilt from the archived order records), `clearing` (net quantity per account and cleared instrument against the clearing house's report) and `pending-busts`. `BreakReport.render()` is the text COOKBOOK.md recipe 21 quotes.

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

The 17.14 bindings stand as follows after round 2 (Section 18): `TradeBust` (44) is applied by `PositionKeeper.onFrame` (DR-R2W-06 for the RFQ case); `BookMapper`, `InstrumentCatalogue` and `ValuationConvention` are bound by `io.ratesalgo.app.posttrade.JournalFeed` (catalogue from the pricing binding's directory, books by product); `OpsHttpServer.Control` is bound to `TradingProcess.kill` and `statusJson` (DR-R2W-10); `MessageSink` (stress harness) takes `InboundRouter::offer`. `MarkSource`, `CarrySource`, `BusinessCalendar`, `VenueStatusPort`, `QuotingControl` and the attribution file stay on their fixtures (deferred, STATUS.md).

### 17.15 Message and cancel counts (round 2 fix C)

`QuoteStuffingDetector` counts outbound Quote and NewOrder frames together with the cancels and replaces that go with them (OrderCancel, OrderReplace, QuoteCancel, QuoteWithdraw, OrderWithdraw) per instrument over its sliding window. `messages(instrumentId)` and `cancels(instrumentId)` give the day's totals and `instruments()` lists the instruments seen. `EodReportMain` writes per-instrument message and cancel counts in the end-of-day report.

## Section 18. Process assembly (round 2)

Module `rates-app`, package `io.ratesalgo.app`. It wires the phase modules into one trading process. Nothing in it re-implements a phase: every binding adapts one module's interface to another's.

### 18.1 Entry point and run modes

`TradingProcessMain --config FILE --schema FILE [--seed N] [--time-scale X]` validates the rates-ops process configuration (Section 17.11; exit 2 if refused). Role `trading` starts `TradingProcess` with its simulators, runs each instrument group's loop on its own `LoopRunner` thread and the gateways, feed, curve feed and venues on one driver thread (`rates-gateways`) that advances simulated time by `X` (default 10) times real time from 2026-11-02T13:25Z, and serves the rates-ops endpoints (Section 17.12). Role `venue-sim` serves health and metrics only. `TradingProcessMain --replay DIR` replays every group directory under `DIR` through `LoopReplay` with the desk's factory and prints one line per group; exit 0 if every outbound hash matches.

`TradingProcess.step(nanos, stepNanos)` runs the same assembly on the calling thread: per step it moves the `SimClock` under both venues' locks, runs the venues' due events, the curve feed and the CLOB liquidity, then pumps gateways, feed, control rings and loops until three consecutive passes do no work and each venue's QuickFIX/J sequence numbers equal the engine session's (`getExpectedSenderNum` equals our `expectedInSeq`, `getExpectedTargetNum` equals our `nextOutSeq`), or two seconds pass (DR-R2W-13).

### 18.2 Fixed identifiers (`AppIds`)

| Name | Value | Meaning |
|---|---|---|
| D2C venue id | 101 | stamped by the D2C gateway on its SBE messages |
| CLOB venue id | 102 | the CLOB's FIX gateway and its SBE book feed (`ClobSimulator.VENUE_ID`) |
| Outbound gateway ids | 0 D2C, 1 CLOB, 2 control | in every loop, in the order the rings are added (Section 9.2) |
| GatewayStatus ids | 0 D2C session, 1 CLOB session, 3 CLOB feed | inbound template 65003; id 3 CONNECTED means the feed was rebuilt from a snapshot |
| Curve heartbeat source | 900 | Heartbeat `sourceId` of the curve feed; the desk requires it |
| SOFR curve id | 1 | CurveInput `curveId` |
| Router child ids | from `2^52 + (j << 40)` | router `j`; a cancel request's ClOrdID is the child id with bit 61 set |
| Engine CompID | RALGO | on every venue |

### 18.3 Pricing binding

`AnalyticsPricing` implements `FairValueSource` and `SensitivityProvider` (Section 12) over one USD SOFR OIS curve bootstrapped by `CurveBuilder` (linear in log discount factor) from the par rates of the swap templates 2001 to 2005 (2Y, 5Y, 10Y, 30Y, 12Y). A CurveInput of kind ParSwap whose instrument is a template moves that pillar: value is the par rate in percent as a Decimal9 mantissa, converted to a decimal by multiplying by 1e-11; `CurveBuilder.rebuild(pillar, quote)` re-solves it and the later pillars; every swap's par rate and every instrument's unit key-rate ladder are then recomputed in place.

- Swap fair mid: `Decimal9.round(parRate * 100)`; reference time: the newest CurveInput stamp, `NO_VALUE` before the first.
- Bond fair mid: the mid of the composite book, `NO_VALUE` without both sides; reference time: the book's update stamp.
- Unit ladder: value change of one major unit (one currency unit of notional or face) for a one basis point rise in each curve quote, mapped onto the eight `KeyRateTenors` by `Dv01Adapter` (DR-R2W-01). `pv01(id, quantityMinor, out)` scales it by `quantityMinor / 100`.
- Tolerance: par rates and PVs equal a fresh bootstrap of the same quotes to 1e-12; tenor DV01s equal `KeyRateRisk.fromGradient` mapped by the adapter to 1e-12 of the parallel DV01 (`PricingBindingTest`).

### 18.4 The desk on the loop and agent registration order

`DeskLoopFactory` is the `LoopFactory` of the live loop and of `LoopReplay`. It sets the timer wheel to 1 ms ticks, 1024 slots, 4096 timers, builds the loop and one `DeskGroup`, which constructs, in this order: `AnalyticsPricing`; `LoopBook` (composite book over the CLOB); one `SmartOrderRouter` per hedge instrument with its `LoopOrderGateway`; `RouterHedgeSink`; P3's `Desk` (kill switch, risk engine, a hedger per currency, fair value, quoting engine, package and CLOB quoters; DR-P3 order of kill-switch listeners: quoting, CLOB quoter, hedgers). The desk's input health tracks venues 101 and 102.

Handlers, registered in this order:

| Template | Handler |
|---|---|
| 1 Heartbeat, 10 QuoteRequest, 12 QuoteResponse | `Desk.handler()` (input health, quoting engine) |
| 2 BookUpdate | `LoopBook.apply`, `Desk.handler()`, `RiskEngine.onFairValue` (bonds with a two-sided book), the instrument's router `onBookUpdate`; a quoting poll if input is unhealthy |
| 3 CurveInput | `AnalyticsPricing.onCurveInput`; on a moved pillar `RiskEngine.onCurveMove(USD)` and `onFairValue` |
| 21 ExecutionReport | from the RFQ venue: counted, not booked (DR-R2W-06); router child ids: the router, then the hedger (`onHedgeFill`, `onParentDone`); other ids: the CLOB quoter |
| 31 KillSwitch | `KillSwitchController.trigger(reason, scope, requestedBy)` |
| 44 TradeBust | reverses the fill in the risk engine |
| 65003 GatewayStatus | input health `onSession` (ids 0, 1) or `onFeedRecovered` (id 3), then a quoting poll |

The first dispatched message of any template starts the desk: it requires the curve heartbeat, calls `RiskEngine.startOfDay` and schedules the housekeeping timer, which every 10 ms polls the quoting engine and each router and every 250 ms calls `Hedger.onTimer`, then reschedules itself from the timer's own deadline. Starting on the first message, not at construction, makes every deadline relative to an input, so live and replay agree. The only agent is the status agent the process adds (`TradingProcess.Group.publishStatus`), which copies counters into volatile fields; agents do not run in replay (DR-P2C-04).

`OutboundSink` (gateway) publishes to the gateway id of the message's venue (its first field); `OutboundSink` (control) publishes to gateway 2.

### 18.5 Venue binding and instrument-group partitioning

`VenueSession` owns a `TcpInitiator`, a `FixSession` (heartbeat 30 s, reset on logon) and P2's `VenueGateway`, whose inbound sink is an `InboundRouter`. Its duty cycle reads the socket, runs the session timers and drains this venue's outbound ring in every group into `VenueGateway.offer`. Logon and disconnect publish GatewayStatus with the session's id.

`InboundRouter` (one per publishing thread) sends a message naming an instrument (BookUpdate, CurveInput, QuoteRequest, ExecutionReport, TradeBust) to that instrument's group; a QuoteResponse to the group that received the QuoteRequest with the same venue and RFQ id, forgotten at the first final response that is not Rejected; everything else (Heartbeat, GatewayStatus, KillSwitch) to every group. A full ring drops and counts BookUpdate, CurveInput and Heartbeat and retries every other message until the loop takes it (Section 9.3). An instrument group is a currency (DR-R2W-03).

`FeedBinding` forwards every BookUpdate from the venue's feed channel, including the first after a sequence gap; on a gap it requests the venue's snapshot, forwards its frames, skips buffered frames the snapshot covers and publishes GatewayStatus CONNECTED with id 3 (DR-R2W-05).

### 18.6 Post-trade binding

`JournalFeed.feed(dir, "in", "out", handler)` merges a group's inbound and outbound journals by timestamp (inbound first at equal stamps, journal order within each), drops control-schema frames and the RFQ venue's fill confirmations, and numbers the merged frames from 1. `JournalFeed.keeper` feeds a `PositionKeeper` with books 1 (RFQ trades) and 2 (hedges) and the catalogue of the pricing binding (bonds multiplier 1, swaps the annuity). `VenueReconciliation.run(keeper, d2c, clob)` reconciles the keeper's live trades with the D2C simulator's trades won by RALGO (key `101:R:quoteId`) and the CLOB simulator's unbusted fills to RALGO (key `102:E:execId`), venue quantities times 100, with `Reconciler.Tolerance.DEFAULT`.

### 18.7 Back-test binding

`JournalExport.export(dir, "in", target, capacity)` writes one RAJ1 file: QuoteRequest, CurveInput and the curve feed's Heartbeat copied unchanged with their stamps, and every change of an instrument's top of book as BookUpdates Clear, Add bid, Add ask at level 0 with venue sequence numbers from 1. `DeskStrategy` is a P5 `Strategy` that runs a `DeskGroup` on its own loop per callback; a Quote becomes `ctx.quote(rfqId, px, px)`, a NewOrder becomes a market order (`ctx.sendOrder`), the back-tester's QuoteResponse and ExecutionReport return with the desk's quote and child ids, and the curve heartbeat is restored with each curve input (DR-R2W-11). `DeskBacktest.run(groupDir, presets, outDir, seed)` runs it under `data/presets.yaml` at the central band point and writes `results.raj`, the tables of Section 16 and `report.md`, `report.json`.

### 18.8 Acceptance tests

| Test | Asserts |
|---|---|
| `SmokeTest` | ten simulated minutes from 08:25 on seed 7: quotes sent, RFQ fills, hedge parents routed and filled, book updates through the feed; after `POST /control/kill` no live quote, no router child, nothing resting at the CLOB, a KillSwitch on the control ring; positions from the journals; reconciliation with no break; replay hash equal |
| `BacktestBindingTest` | a day recorded with the loops on their own threads replays to the same outbound hash; the daily report runs on its journal and is deterministic |
| `PricingBindingTest` | Section 18.3 tolerances |
| `VenueBindingTest` | a feed gap and a lost session pull quotes through the loop and the venue; quoting resumes after the snapshot and after logon; injected GatewayStatus faults |
| `WiredLoopAllocationTest`, `AppHotPathRulesTest` | DR-R2W-12 |

## Section 19. Desk features (rates-strategy, R2)

Module `rates-strategy`, package `io.ratesalgo.strategy`. Section 18 is reserved for `rates-app` (branch `claude/r2-wiring-c31ae4`); this section is numbered 19 so the two merge without renumbering. Each feature sits behind an interface whose default reproduces today's engine; the measured results are in docs/research/DESK_FEATURES.md and the decisions in DR-R2B-01 to DR-R2B-10. Units: DV01 in major currency per 1 bp rise (positive gains when rates rise), positions in minor units, spreads and premiums in bp, times in epoch nanoseconds. Every method named allocation-free allocates nothing after warm-up (`DeskQuoterAllocationTest`).

### 19.1 RiskLadder

`RiskLadder(List<Instrument>)`: positions per instrument and their DV01 ladder. Buckets: 0 = 2Y (tenor up to 3 years), 1 = 5Y (up to 7), 2 = 10Y (up to 15), 3 = 30Y. `setPosition(index, minor)`, `recompute()`, `net()`, `bucket(b)`, `ladderNorm()` (Euclidean norm of the buckets), `tradeDv01(index, weBuy, quantity)`, `indexOf(instrumentId)` (dense index or -1). Swaps, bonds and futures share one ladder, so opposing flow nets across products before any hedge. Allocation-free.

### 19.2 HedgeBudget (feature 1)

`double excessDv01(RiskLadder, long nanosSinceClientFill)`: DV01 to remove now with the sign of the exposure (the hedge sent is `-excess`), zero for none. `long holdNanos()`: when to ask again after a client fill, zero for never. Default `HedgeBudget.threshold(t)` (`ThresholdHedgeBudget`): `|net| > t` gives `net`. `NettingHedgeBudget(soft, hard, retain, holdNanos)`, `0 <= retain <= soft <= hard`: `|net| > hard` gives `net - sign(net) * soft`; `|net| > soft` and age at least `hold` gives `net - sign(net) * retain`; otherwise zero.

### 19.3 Axes (feature 2)

`AxeBook(RiskLadder, maxBp, budgetDv01, bucketWeight)` implements `io.ratesalgo.hedge.AxeSource`: `axeBp(instrumentId, ourSide) = maxBp * min(1, |e| / budget)` when trading `ourSide` reduces `e = (1 - w) * net + w * bucket(instrument)`, else 0; `axeQuantity` is `|e| / |dv01PerUnit|` on the axed side; `publish()` returns `List<Axe(instrumentId, ourSide, improveBp, quantity)>` in ascending instrument order (allocates). Default `AxeSource.NONE`. No SBE message: template ids 120 to 129 stay unused (DR-R2B-06).

### 19.4 QuoteSizer (feature 3)

`long maxQuantity(RiskLadder, index, tradeDv01, requested)` and `double premiumBp(RiskLadder, index, tradeDv01, sizeMillions)` (never negative). A D2C RFQ is all-or-none in the back-tester, so a maximum below the request is a decline. Default `QuoteSizer.UNLIMITED`. `EnvelopeSizer(limitDv01, softUtil, hardUtil, riskPremiumBp, blockSizeMm, blockPremiumBp)`: with `post = |net + tradeDv01|` and `u = post / limit`, a risk-reducing request (`post <= |net|`) gets full size and no risk premium; `u > hardUtil` declines; otherwise the premium is `riskPremiumBp * min(1, (u - softUtil) / (hardUtil - softUtil))^2` when `u > softUtil`, plus `blockPremiumBp * ln(size / blockSizeMm)` when `size > blockSizeMm`. `lognormalQuantileMm(mu, sigma, z) = exp(mu + z sigma)`.

### 19.5 ClientTiering (feature 4)

`int tier(clientId)` (1 best, allocation-free), `boolean wantsMarkouts()`, `void onMarkout(clientId, markoutBp, now)` (allocation-free), `void review(now)` (may publish a table version and allocate; runs at the review cadence). The markout is `sign(q) * (mid(t + h) - mid(t)) / |priceChangePerBp|`, bp of value from our side. Default `StaticTiering(clientsPerTier)`: `1 + (clientId - 1) / clientsPerTier`. `MarkoutTiering(tiers, demoteBelow[], promoteAbove[], minSample, alpha, startIndex, reviewIntervalNanos, proposerId, approverId, capacity)`: requires `promoteAbove[t] > demoteBelow[t - 1]` and `proposerId != approverId`; publishes through `io.ratesalgo.quoting.TierTableControl.propose` and `approve`, versions strictly increasing from 1.

### 19.6 HedgeTiming (feature 5)

`Action decide(excessDv01, now)` with `Action` IMMEDIATE, STAGED (send `1 / slices()` now, ask again after `sliceIntervalNanos()`) and WAIT (ask again after `waitNanos()`); `onClientFill(tradeDv01, now)`; `reset()`. Default `HedgeTiming.ALWAYS_IMMEDIATE`. `OptimalStoppingTiming(FlowRateEstimator, gamma, sigmaBpPerSqrtSecond, hedgeCostPerDv01, impactPerDv01Squared, waitStepNanos, maxWaitNanos, slices, sliceIntervalNanos)`: WAIT when `c * min(a, lambda dt) > gamma / 2 * sigma^2 * a^2 * dt` and the wait so far plus `dt` is within `maxWait`; else STAGED when `k a^2 (1 - 1/n) > gamma / 2 * sigma^2 * a^2 * ds * sum_{j=1}^{n-1} ((n - j) / n)^2`; else IMMEDIATE. `FlowRateEstimator(tauSeconds, priorDv01PerSecond)`: decaying rates of DV01-raising and DV01-lowering client fills; `opposingRate(exposure, now)`.

### 19.7 InventoryPenalty (feature 6)

`double penaltyBp(RiskLadder, index, weBuy, quantity)`: cost in bp of the trade's DV01, negative for a trade that reduces inventory. Default `InventoryPenalty.NONE`. `FundingPenalty(ladder, couponByIndex[], bondBsPerYear, swapBsPerYear, holdingDays)`: bonds `((repo - coupon) * dPos + bsBond * dGross) * 100 * v * H / 360`, swaps `bsSwap * dGross * 0.01 * H / 360`, futures 0, divided by `quantity * |dv01PerUnit|`; `repo` is the instrument's funding rate. `CouponCarry(Map<Integer, Double>)` implements the back-tester's `CarryModel`: bonds accrue `position * 100 * v * coupon * dt / 360 days`.

### 19.8 DeskQuoter

`DeskQuoter(name, Features)` implements the back-tester's `Strategy`; `Features(ladder, SpreadOptimizer, HedgeBudget, HedgeTiming, AxeSource, QuoteSizer, ClientTiering, InventoryPenalty, markoutHorizonNanos)`. `baselineFeatures(ladder, spread, threshold, clientsPerTier, horizon)` sets every default. Per RFQ: refresh the ladder from the context; decline if the sizer's maximum is below the request; `extra = premiumBp + penaltyBp`; `s = SpreadOptimizer.optimalHalfSpreadBp(instrument, size, tier, dealers, inWindow, extra)` with `E[edge](s) = P(s) (s - adverse(tier) - hedgeCost - extra)`; half-spread `max(0, s - axeBp)` around mid, rounded to tick outward. Per client fill: feed the timing, schedule a markout when the tiering wants one, then ask the budget and the timing; one hedge in flight at a time, into the 10-year note future. With the defaults the output chain hash equals that of `ModelSpreadStrategy` on the same journal. Counters: `quotes`, `sizeDeclines`, `clientFills`, `hedgesSent`, `waits`, `stagedSlices`, `markoutsRecorded`.

### 19.9 Experiments

`DeskFactory(config, presets, instruments)`: `baseline()`, `treatment(name, tiering)` for `internalisation`, `axes`, `sizing`, `tiering`, `timing`, `funding` and `desk_all`, and `deskDefault()` (the baseline with the adopted features, `ADOPTED`). `DeskExperiment(repoRoot, configFile).run(days, workDir)` and `DeskExperimentCli --preregs a.yaml,...,g.yaml [--ledger] [--repo] [--work] [--scope evaluation|holdout] [--commit] [--dirty]`: refuses (exit 2, nothing recorded) unless every pre-registration is valid (Section 16.8), they share config, period and holdout, each has baseline `baseline` and one known treatment, and a holdout has not been opened; then runs once and appends one ledger record per pre-registration. Config `data/experiments/R2B.config.yaml`: `band` (`dealers_min`, `dealers_max`, `informed_share_multiplier` at `low`, `central`, `high`; the slope comes from preset `usd_rfq_hit_rate` at the same point), `limits`, `carry`, `baseline`, one section per feature, `statistics`. Record `results`: `treatment`, `baseline`, `metric`, `metric_unit`, `orientation`, `days`, `band.<point>` (`baseline_mean`, `treatment_mean`, `difference_mean`, `improvement_mean`, `improvement_ci_low`, `improvement_ci_high`, `paired_p`, `holm_p`, `verdict` improved, worse or inconclusive, `pnl_difference_mean`, `pnl_difference_ci_low`, `pnl_difference_ci_high`, `pnl_guard`, `limit_breach_days`, `new_limit_breach_days`, `secondary_means`), `statistics`, `guards`, `adopted_as_default`; the record's `verdict` is `supported` or `not supported`.

### 19.10 Desk report (feature 7)

`DeskMetrics.of(BacktestResult, InstrumentSet, Settings(limitDv01, positionLimitMm, horizonIndex, gamma, sigma, bondBsPerYear, swapBsPerYear))`: scalar metrics by name (`pnl`, `client_dv01_traded`, `hedge_dv01_traded`, `pnl_per_dv01`, `capture_per_dv01`, `hedge_ratio`, `spread_capture`, `adverse_selection`, `other_inventory`, `hedge_cost`, `carry`, `funding`, `fees`, `residual`, `hedges`, `hedge_slippage`, `hedge_slippage_per_dv01`, `risk_charge`, `risk_adjusted_hedge_cost`, `mean_abs_net_dv01`, `max_abs_net_dv01`, `limit_utilisation`, `balance_sheet_charge`, `funding_net`, `inventory_half_life_s`, `limit_breach`, `adverse_tier_1` to `adverse_tier_3`) and `hitRateCounts()` by tier, then product and tenor bucket. Waterfall: `spread_capture + adverse_selection + other_inventory + hedge_cost + carry + funding + fees = position P&L - residual`. `DeskReport(date, strategy, metrics)`: `toMap`, `toJson`, `toMarkdown`. `DeskReportCli --config --date [--strategy] [--band] [--repo] [--out]` writes `desk_report.json` and `desk_report.md`.
