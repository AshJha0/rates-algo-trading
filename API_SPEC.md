# API specification: the pinned, cross-module contract

This document is the contract between modules and between the Java and Python sides. Anything stated here is fixed for the schema or format version it names; a change needs a new version number, a decision record in `DECISIONS.md` and a migration note in `CHANGELOG.md`. Where code and this document disagree, the one that was reviewed last is wrong and the pull request that fixes it must say which.

Phase 0 pins Sections 0 to 4. Sections 5 onward arrive with P1. Section 9 (event loop and ring buffers) and Section 1.5 (journal segments) are pinned by P2.

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

A reader (`SegmentedJournalReader`) walks the segments in index order up to the highest index present when it opened, carrying sequence and hash across each boundary, and applies 1.4 throughout. A segment read on its own fails the chain check unless the reader is given the previous segment's last sequence and hash, which is deliberate: a segment cannot be swapped for another self-consistent one without detection. A missing segment below the highest index is reported as corruption at the sequence the missing segment should have started with. `SegmentedJournalTest` checks a swapped segment, a missing segment, a flipped byte in a later segment and a truncated earlier segment.

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

## Section 5 onward

Sections 5 onward arrive with P1: market conventions (day counts, calendars, business-day rules, settlement lags per currency), curve bootstrap (instruments, interpolation, tolerances), pricing formulas and their tolerances against QuantLib (relative error with an absolute floor, stated per quantity).

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

`LoopReplay.run(inbound, recordedOutbound, factory)` replays the inbound journal through a fresh loop built by the same `LoopFactory` that built the live one:

- The `Replayer` sets a `ManualClock` to each frame's `timestampNanos` and the loop latches it, so handlers see the times they saw live.
- The replay loop is built at the first frame, so its timer wheel starts at the replayed time.
- A clock tick polls the timer wheel; a Backpressure calls the listener with the journaled values; a LoopError stops the replay; every other frame is dispatched to its handler. Agents do not run.
- Every outbound message the replay loop produces is chained with `ChainHash` (Section 1.3) from the genesis hash and compared with the next frame of the recorded outbound journal.

The result (`ReplayResult`) gives the produced and recorded counts, both final hashes and `firstMismatch`: the outbound sequence of the first produced message whose chain hash or template id differs from the recorded frame, or the produced count plus one when replay produced fewer messages than were recorded; 0 when the streams are equal. Equal final hashes mean equal outbound byte streams, because the chain covers every payload byte in order.

Worked example: `LoopReplayTest.replayReproducesTheOutboundStreamExactly` runs 480 QuoteRequests with expiry timers, an undrained gateway that causes backpressure, a disconnect and 4096-byte segments, then replays; produced and recorded counts and hashes are equal. Replacing the handler's 2,000,000 spread (0.002 in price units) with 3,000,000 gives `firstMismatch = 1`.

### 9.8 Counters

`LoopCounters`, readable from any thread: inbound, outbound, outboundDropped, backpressureEvents, unhandled, timerTicks, errors. The loop writes them with release stores; readers use volatile loads.
