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

Shared types: `Decimal9` is a composite of `mantissa` int64 and a constant `exponent` of -9; `Ccy` is `char[3]` ASCII; `Tenor` is `char[4]` ASCII, space padded; `EpochNanos`, `Quantity` and `SeqNo` are int64; `InstrumentId`, `PartyId` and `VenueId` are int32. Enums are uint8: `Side` (Buy 1, Sell 2), `BookAction` (Add, Change, Delete, Clear), `QuoteStatus` (Done, TradedAway, Expired, Rejected, Passed), `OrdStatus` (New 0, PartiallyFilled 1, Filled 2, Cancelled 4, Rejected 8), `OrdType` (Market, Limit), `TimeInForce` (Day 0, ImmediateOrCancel 3, FillOrKill 4, GoodTillDate 6), `CurveInputKind` (Fixing, Future, ParSwap, Bond), `LimitKind` (Position, BucketDv01, NetDv01, GrossDv01, DailyLoss, OrderRate, QuoteRate, MaxOrderSize, PriceCollar), `KillReason` (Manual, LimitBreach, FeedGap, SessionLoss, StaleReference). P3 added `LimitKind` values MessageRate (10) and FatFinger (11), and `CancelReason` (Expired 1, StaleInput 2, FeedGap 3, HeartbeatLoss 4, SessionLoss 5, KillSwitch 6, AdverseSignal 7, Requote 8, Manual 9) for QuoteCancel and OrderCancel (DECISIONS.md DR-P3-04). A Quote with `validUntilNanos` zero is subject, not firm (DR-P3-10).

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
| 60 | QuoteCancel (P3) | `venueId`, `rfqId`, `quoteId`, `instrumentId`, `reason` CancelReason, `sentNanos` |
| 61 | OrderCancel (P3) | `venueId`, `clOrdId` (of this cancel), `origClOrdId`, `instrumentId`, `side`, `reason` CancelReason, `sentNanos` |

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

## Section 5 onward

Sections 5 onward arrive with P1: market conventions (day counts, calendars, business-day rules, settlement lags per currency), curve bootstrap (instruments, interpolation, tolerances), pricing formulas and their tolerances against QuantLib (relative error with an absolute floor, stated per quantity).

## Section 12. Quoting parameters and the hit-rate preset (P3)

The quoting engine is `io.ratesalgo.quoting.QuotingEngine` in `rates-quoting`. It consumes QuoteRequest (10) and QuoteResponse (12), sends Quote (11) and QuoteCancel (60) to the gateway, and calls the risk engine before every quote. All prices below are in the instrument's own quote convention (price per 100 for bonds and futures, rate in percent for swaps); "bp of value" means the quote move that changes the position's value by one basis point of PV01, which is `|PV01 per unit| / quoteValue` quote units (`RiskEngine.quotePerBp`).

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

### 12.4 Skew

With p the book's PV01 ladder in the instrument's currency, u the instrument's per-unit ladder, U its parallel PV01, \(\Sigma\) the key-rate covariance (14.2) and Q the quoted quantity in major units:

\[
\text{skew} = \text{clamp}\left(\kappa \frac{u^\top \Sigma p}{|U|}, \pm \text{maxSkew}\right), \qquad \text{size} = \min\left(\tfrac{1}{2} \kappa Q \frac{u^\top \Sigma u}{|U|}, \text{maxSkew}\right)
\]

An offer sits \(h - \text{skew} + \text{size} - \text{axe}\) from fair and a bid \(h + \text{skew} + \text{size} - \text{axe}\), each at least \(-\text{maxImprove}\). A book long duration in the instrument's risk direction has positive skew and offers better. The axe is the hedger's improvement for the side that moves the book toward its hedge (14.4). Bids round down and asks round up to the Decimal9 grid.

### 12.5 Fair value

Bonds and futures: microprice of the composite book, \((b \cdot A + a \cdot B) / (A + B)\) with b, a the best prices and B, A their sizes, stamped with the book's update time; without a two-sided book, the pricing source's fair mid. Swaps: the pricing source's par rate plus \(\beta\) times the futures signal, the change in futures-implied yield (bp) between the futures mid and its microprice. Every product: plus the installed `FairValueSignal`, zero unless a `ValidationRecord` permits it (DR-P3-03).

### 12.6 RFQ decision order and lifecycle

For each QuoteRequest, after `poll`: duplicate (venue, rfqId) within the window; kill switch ACTIVE; inputs healthy (heartbeats, sessions, feed sequence); instrument known and quantity positive; request not expired; client tier not blocked; re-quote throttle; fair value present; oldest input within `maxInputAgeNanos`; size reduced to `min(request, tier max, risk headroom)` rounded down to the step, pass if zero; capacity of the live book; spread, skew, axe; risk pre-trade check (Section 13.2). A failure at any step is a PASS with the `PassReason` in `RfqOutcome`. Otherwise the quantity is reserved and a Quote sent: FIRM with `validUntilNanos = min(now + firmTtl, request expiry)`, or SUBJECT with `validUntilNanos = 0` (DR-P3-10). The diagram is docs/diagrams/rfq_decision.mmd.

`poll(now)` expires quotes with `validUntilNanos <= now` and releases their reservation; sends QuoteCancel (reason StaleInput) for any live quote whose oldest input is older than `maxInputAgeNanos`; sends QuoteCancel for every live quote when inputs turn unhealthy (reasons HeartbeatLoss, SessionLoss, FeedGap); and measures due markouts. A withdrawn quote keeps its reservation until answered or expired. On QuoteResponse: Done books the fill at the traded price, teaches a win and, with a disclosed cover, a win at the cover distance; TradedAway teaches a loss; every status releases and frees the quote.

### 12.7 Package RFQs

`PackageQuoter` prices curves, butterflies, spread-overs, invoice spreads, IMM rolls and switches as one trade with DV01-neutral leg ratios (IMM rolls one for one). The charge is the sum of the legs' spread charges, minus the netted-hedge saving (sum of the legs' hedge costs minus the hedge cost of the net ladder's parallel PV01), plus the inventory charge on the net ladder; floored at `minHalfSpreadBp` times the largest leg PV01. The charge is applied as an equal bp distance on every leg. Each leg passes a one-sided quote check. Schema v1 has no package message; the wire form is P2's.

### 12.8 CLOB quoting

`ClobQuoter` rests a bid and an ask at fair plus or minus `halfSpreadBp` (default 0.25 bp). It keeps an order while the target is within `requoteThresholdBp` (0.10 bp) to keep queue priority, and tracks the quantity ahead from the displayed size at its level. It cancels both sides for `cooldownNanos` (1 s) when the futures signal moves more than `futuresJumpBp` (0.5 bp) between updates, and the threatened side when book imbalance \((B - A) / (B + A)\) exceeds 0.6 in absolute value. It replaces at most once per side per `minRequoteNanos` (50 ms), rests at most `participation` (0.25) of the displayed size at the best price and never more than the risk headroom, and never places a buy at or above its own resting sell or a sell at or below its own resting buy. Orders are NewOrder (20), Limit, Day; cancels are OrderCancel (61). Reservations are released on the venue's ExecutionReport.

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

A failure at 3 to 11 sends one RiskLimitBreach (30) to the control sink with the limit kind, scope (instrument id, pillar index or currency index), limit and observed values (Decimal9, saturated), the journal sequence of the rejected message and the time, and returns the `LimitKind` value. Orders whose parallel PV01 offsets the currency's net PV01 without overshooting skip 7 to 10 (DR-P3-05). Cancels and quote withdrawals are counted against the message rate but never refused. On success, one token is taken from each rate bucket involved.

### 13.3 Post-trade alerts

After every fill, curve move and re-mark the engine evaluates position, pillar, net, gross and daily loss on the actual state and sends one RiskLimitBreach (with `rejectedMessageSeqNo` 0) when a limit becomes breached; it sends nothing more until the limit clears and is breached again. A new daily-loss breach triggers the kill switch with reason LimitBreach when `killOnDailyLoss` is set (the default).

### 13.4 Kill switch

`KillSwitchController`: ACTIVE, KILLED, RESET_PENDING (docs/diagrams/kill_switch_state.mmd). `trigger(reason, scope, requestedBy)` from ACTIVE or RESET_PENDING calls every registered listener in registration order (quoting engine: QuoteCancel for every live quote; CLOB quoter: OrderCancel for every resting order; hedgers: `HedgeOrderSink.cancelAll` and release of reservations) and then sends one KillSwitch (31); from KILLED it only counts. `requestReset(operator)` moves KILLED to RESET_PENDING; `approveReset(approver, inputsHealthy)` moves to ACTIVE only for a different person with healthy inputs.

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
