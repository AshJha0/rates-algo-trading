# API specification: the pinned, cross-module contract

This document is the contract between modules and between the Java and Python sides. Anything stated here is fixed for the schema or format version it names; a change needs a new version number, a decision record in `DECISIONS.md` and a migration note in `CHANGELOG.md`. Where code and this document disagree, the one that was reviewed last is wrong and the pull request that fixes it must say which.

Phase 0 pins Sections 0 to 4. Sections 5 onward arrive with P1.

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

## Section 5 onward

Sections 5 onward arrive with P1: market conventions (day counts, calendars, business-day rules, settlement lags per currency), curve bootstrap (instruments, interpolation, tolerances), pricing formulas and their tolerances against QuantLib (relative error with an absolute floor, stated per quantity).

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

Synthetic day (`SyntheticDay`, seed 20261030, `Params.DEFAULT`: 60 orders, 40 RFQs, 5 duplicate fills, 2 fills late within the day, 3 fills after the cut, 3 busts of which one arrives before its fill, 1 bust after the cut, 2 duplicate drop-copy lines). Result recorded by `RecipesTest` and COOKBOOK.md recipe 20 on 2026-10-31: 336 frames, 105 fills, four breaks at the cut, all explained (three LATE_ARRIVAL, one BUSTED_AFTER_CUT), no break after every late message has arrived. `ChaosDayTest` asserts zero unexplained breaks and that a genuine price difference in the drop copy is reported as one unexplained PRICE break.

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
