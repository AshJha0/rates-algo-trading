# API specification: the pinned, cross-module contract

This document is the contract between modules and between the Java and Python sides. Anything stated here is fixed for the schema or format version it names; a change needs a new version number, a decision record in `DECISIONS.md` and a migration note in `CHANGELOG.md`. Where code and this document disagree, the one that was reviewed last is wrong and the pull request that fixes it must say which.

Phase 0 pins Sections 0 to 4. Sections 5 onward arrive with P1. Phase 5 pins Section 16 (back-tester).

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

## Section 5 onward

Sections 5 onward arrive with P1: market conventions (day counts, calendars, business-day rules, settlement lags per currency), curve bootstrap (instruments, interpolation, tolerances), pricing formulas and their tolerances against QuantLib (relative error with an absolute floor, stated per quantity).

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
