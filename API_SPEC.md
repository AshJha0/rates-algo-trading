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

### 2.1 Additions by P2 venues (W3)

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
| `JournalWriter.create` on an existing path, or a capacity too small for a header and one frame header | `IllegalArgumentException`. |
| Use of a closed `JournalWriter` | `IllegalStateException`; `close()` is idempotent. |
| SBE schema id or version mismatch on decode | The decoding site throws `IllegalStateException` naming expected and actual values. Handlers never decode a payload whose header does not match. |
| Timer handler throws | Every timer due in that `poll` still fires; the wheel then rethrows the first exception with the others attached as suppressed. The failed timer has been removed. |
| `ManualClock.advance` with a negative argument, or `setEpochNanos` earlier than now | `IllegalArgumentException`. Time does not go backwards. |

Exceptions carry enough to reproduce: sequence, expected and actual values. Nothing is logged and swallowed.

## Section 5 onward

Sections 5 onward arrive with P1: market conventions (day counts, calendars, business-day rules, settlement lags per currency), curve bootstrap (instruments, interpolation, tolerances), pricing formulas and their tolerances against QuantLib (relative error with an absolute floor, stated per quantity).

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

Rules: instrument ids are positive and unique across bonds, swaps and futures; ISIN and CUSIP check digits are validated and a CUSIP must equal ISIN characters 3 to 11; dates satisfy announcement before or on auction, auction before or on issue, issue before first coupon, first coupon before maturity. On-the-run: for a market and tenor, the bond with the latest `onTheRunFromDate` on or before the date (and not matured); a bond is when-issued from its announcement date until it is on the run, so the on-the-run rolls on the new issue's issue date. `CalendarSource` is the hook for `rates-analytics` calendars; `FixtureCalendarSource` reads `holidays.csv` and refuses unknown calendar names.

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
