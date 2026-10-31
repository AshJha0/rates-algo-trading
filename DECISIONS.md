# Decision records

Each record states the context at the time, the decision, and the consequences we accept. Records are never edited after the fact; a later record supersedes an earlier one and says so.

## DR-001: Repository name and licence

Date: 2026-10-31

Context. The project needs a name that says what it is and a licence that lets anyone read, run and reuse it from the first commit, including people assessing the work.

Decision. The repository is `AshJha0/rates-algo-trading`, licensed under Apache License 2.0 with copyright 2026 Ashish Jha.

Amended 2026-10-31 (owner). The repository was created public and switched to private the same day; it stays private until the owner is satisfied with the finished work, then becomes public. Everything is still written as if public: no raw downloaded data, credentials or third-party licensed content is committed (see DR-006), and the word policy (DR-007) applies throughout.

Consequences. GitHub Pages does not publish from a private repository on a free plan, so the Docs workflow builds the site with `--strict` on every push but the deploy step stays skipped until the repository is public and the Pages source is set to GitHub Actions. Apache 2.0's patent grant and notice requirements apply to contributors.

## DR-002: Java for the hot path and venue layer, Python for research

Date: 2026-10-31

Context. The platform needs a low-latency event core and venue gateways, and a separate research workflow for calibration and analysis. Choices for the core were Java, C++ and Rust.

Decision. Java (JDK 25 LTS, DR-003) for the event core, analytics used on the hot path, venue gateways and simulators. Python 3.12 for research, calibration and analysis. No C++ or Rust unless a measured latency gap on the pinned benchmark host (DR-005) demands it, in which case a decision record states the gap and the component.

Consequences. The Java hot path must be allocation-free and single-writer to meet latency goals without native code; this is checked by tests, not assumed. Research code is not on the latency path and may use pandas freely. The Python and Java sides share pinned contracts (API_SPEC.md) rather than code.

## DR-003: JDK 25 LTS toolchain with a JDK 27 forward-compatibility leg

Date: 2026-10-31

Context. The project should build on a long-term-support JDK and learn early about breakage on the next release.

Decision. The gating toolchain is JDK 25 LTS, on Linux and Windows. CI also runs an informational leg on JDK 27 (not gating). The gating toolchain moves to JDK 29 LTS when it ships and the dependencies support it.

Consequences. Language features up to Java 25 may be used. A JDK 27 failure is a tracked issue, not a blocked merge. The move to 29 will need a decision record naming the date and the dependency versions.

## DR-004: USD SOFR swaps and US Treasuries first; EUR and GBP follow with a stated assumption

Date: 2026-10-31

Context. Free transaction-level data exists for USD rates (DTCC SDR public dissemination, FINRA TRACE Treasury aggregates) but there is no comparable free tape for EUR or GBP swaps or government bonds.

Decision. P1 analytics implement USD SOFR swaps and US Treasuries first, with EUR and GBP calendars, day counts and schedule conventions in the same phase. EUR and GBP quoting arrive in P3. Venue-simulator calibration for EUR and GBP reuses the USD-fitted distribution shapes (arrival rates, size distributions, response times) with a scaling assumption stated in `data/presets.yaml`, because no free EUR or GBP transaction tape exists to fit them.

Consequences. Any EUR or GBP result carries a note that the venue parameters are scaled from USD fits. If a free EUR or GBP tape becomes available, a decision record replaces the scaling with a fit.

## DR-005: Where latency is measured

Date: 2026-10-31

Context. Hosted CI runners share hardware with unknown neighbours, so their timings are not comparable run to run.

Decision. For CI, latency jobs run on the development machine (recorded by model, CPU, kernel or OS build, and JVM flags in every report) and are informational. Gating thresholds apply only on a pinned Linux host when one is available. Every report records the machine it ran on.

Consequences. `docs/BENCHMARKS.md` carries no numbers until a report exists, and every number it later carries names its machine and date. A regression seen only on a hosted runner is investigated, not acted on.

## DR-006: Public data licensing

Date: 2026-10-31

Context. The project uses public data from government and market sources, each with its own terms. Redistribution of raw downloads may be prohibited or require attribution.

Decision. The repository commits fitted parameters (`data/presets.yaml`) and small fixtures needed by tests, never raw downloads. For each source, `data/SOURCES.md` records the licence or terms to verify, and the research package records a SHA-256 and a manifest for each file used (`ratesresearch.provenance`). Fixtures are small enough to be clearly derived works and name their source.

Consequences. Reproducing a fit requires re-downloading the source; the manifest lets a reader confirm they have the same bytes. A source whose terms turn out to forbid even derived parameters is removed and the record says so.

## DR-007: Naming of role-preparation material

Date: 2026-10-31

Context. The learning text includes question-and-answer material useful for preparing for technical discussions. The owner's standing instruction is that public repositories do not use a particular word for such material.

Decision. All such material is titled as learning material or as a knowledge check (LEARN.md Section 15). The repository does not use the word that names a hiring conversation, and the pull request template includes a check for it.

Consequences. Contributors run a grep before pushing. The content is unchanged; only the framing is.

## DR-008: Competition model for RFQ win probability

Date: 2026-10-31

Context. A dealer quoting into a multi-dealer RFQ needs a model of the probability of winning as a function of distance from mid. Fitting that model requires seeing the quotes of competing dealers. Public data (DTCC SDR) shows executed trades only, never the quotes that lost.

Decision. Win probability is a declared assumption band, not a fitted quantity. The quoting engine (P3) takes a parametrised curve (for example logistic in price distance with a stated slope and midpoint) and the back-tester (P5) reports results across the declared band, not at a point. The band and its rationale are written in `data/presets.yaml` and in LEARN.md Section 7.

Consequences. No result from this project can claim a fitted hit rate. Sensitivity to the band is reported with every quoting result. If a data source with quote-level RFQ data becomes available under acceptable terms, a decision record replaces the assumption with a fit.

## DR-P2C-01: Two journals per loop, and replay compares the outbound stream by chain hash

Date: 2026-10-31

Context. The brief asks for every inbound and every outbound message to be journaled, and for replay to compare the outbound messages it produces with the recorded ones. A RAJ1 frame carries a template id and a schema version but no direction, and some messages (Heartbeat, KillSwitch) can travel either way.

Decision. Each loop writes two journals with the same clock: an inbound journal (inputs, plus the loop's own clock ticks, backpressure events and errors) and an outbound journal (every message the loop produced). Replay feeds the inbound journal to a loop built by the same `LoopFactory` and chains each produced message with `ChainHash` from the genesis hash, comparing frame by frame with the recorded outbound journal (API_SPEC.md Section 9.7).

Consequences. The comparison needs no new format: the outbound journal's own chain hash is the recorded hash. The order between an inbound message and the outbound messages it caused is not in one file; it is reproduced exactly by replay and can be derived from the latched timestamps, which both journals share per duty cycle. A single interleaved audit file, if a reader wants one, is a tool over the two journals.

## DR-P2C-02: Time is latched once per duty cycle, and timer firings are journaled as clock ticks

Date: 2026-10-31

Context. Handlers stamp messages with the current time, and timers fire whenever the loop polls. Live, the loop polls between messages at times no journal records; in replay, time moves only from frame to frame. Without care, outbound stamps and timer outputs differ between live and replay.

Decision. `EventClock` reads the source clock once at the start of each duty cycle; every handler in the cycle and every frame journaled in it see that value. When a timer poll fires at least one timer, the loop journals a clock tick (a schema-1 Heartbeat with `sourceId` 0, reserved for the loop) after the cycle's messages; replay polls the timer wheel only on clock ticks. The live clock is `AnchoredClock`, which derives wall time from the monotonic clock and an anchor taken at start of day, because `SystemClock.epochNanos()` may allocate.

Consequences. Replay reproduces stamps and timer outputs exactly (`LoopReplayTest`, `LoopPropertiesTest`). Messages drained in one cycle share a timestamp, so the journal's resolution is one duty cycle. If a timer handler throws, the tick for that poll is not journaled, so replay of a run that ended in an error is exact up to the last completed input. Wall time drifts from the system clock between anchors; a re-anchor never moves it backwards.

## DR-P2C-03: Segment rolling continues the chain across segments; the single-file classes gain package-private seams only

Date: 2026-10-31

Context. A single RAJ1 file has a fixed capacity, and a day's journal for a busy group does not fit a 2 GiB mapping. The brief asks for rolling with a chain that continues across segments and for the single-file classes to stay unchanged.

Decision. `SegmentedJournal` writes `name.000001.raj` onward and rolls when the next frame does not fit; the first frame of each segment continues the previous segment's sequence and chain hash. `SegmentedJournalReader` walks segments in order. `JournalWriter` and `JournalReader` keep their public API and behaviour; they gain package-private factories that start a segment at a given sequence and previous hash, and `JournalReader` implements a new `FrameSource` interface that the `Replayer` accepts. The file header is unchanged; the segment naming rule is API_SPEC.md Section 1.5.

Consequences. A segment cannot be verified alone, by design: a swapped segment is caught at its first frame. Creating a segment zero-fills it, which took about 340 ms for 512 MiB on the development laptop on 2026-10-31, and the first latency run (`docs/benchmarks/latency-20261031-070816.json`) showed rolls as loop stalls of up to 1.3 s. `SegmentedJournal` therefore offers preallocation: once the current segment is half full, a background thread creates the next one under a `.pending` name and renames it atomically, and the roll only maps the ready file. Rolling still maps a file and allocates a few objects on the loop thread; segments are sized so that rolls are rare (the latency harness uses 512 MiB), and the allocation test journals into one large file.

## DR-P2C-04: Agents and day hooks are not journaled and do not run in replay

Date: 2026-10-31

Context. The duty cycle calls agents after the rings and timers, and the runner calls start-of-day and end-of-day hooks. Live, an agent runs once per cycle, and cycle boundaries depend on load, so agent work cannot be reproduced from the journal.

Decision. Agent work and hooks are outside the replayed function. An agent or hook that needs to change what handlers see publishes a message into the loop's inbound ring, where it is journaled like any other input. Replay does not call agents or hooks.

Consequences. Replay determinism holds for anything that reaches state through a ring. An agent that publishes outbound messages or changes handler state directly breaks replay, and the outbound comparison reports it. API_SPEC.md Section 9.4 states the rule.

## DR-P2C-05: A full outbound ring drops the message and raises a journaled BackpressureEvent after the handler

Date: 2026-10-31

Context. When a gateway drains slowly, the loop must not block and must not allocate. If `publish` returned false, handler control flow would depend on gateway speed, which replay cannot reproduce.

Decision. `publish` returns nothing. A full ring drops the message and counts it. After the handler, timer poll or agent that caused the drops returns, the loop journals one Backpressure control message per affected gateway and calls the `BackpressureListener`. Replay calls the listener at the same point from the journaled message. The outbound journal records every produced message, delivered or not.

Consequences. Replay reproduces the quoting layer's reaction (pulling quotes) exactly. The outbound journal is what the loop decided, not what the venue received; the Backpressure frames say which messages did not leave. A listener that publishes into a still-full ring causes a further event at the next flush point.

## DR-P2C-06: Hot-path rules are checked with ArchUnit over bytecode, with string concatenation compiled inline

Date: 2026-10-31

Context. The hot-path rules (no `synchronized`, no locks, no boxed collections, no string concatenation in message paths) must be checked, not asserted. Since JDK 9, `a + b` compiles to an `invokedynamic` that ArchUnit does not report as a method call, and ArchUnit sees `synchronized` methods but not `synchronized` blocks.

Decision. Packages and classes carry a `@HotPath` annotation (`io.ratesalgo.core` is marked in `package-info.java`). `HotPathRulesTest` imports the compiled classes and checks: no synchronized methods; no dependency on `java.util.concurrent.locks`; no dependency on any `java.util.Collection` or `java.util.Map` type; no calls to a boxed type's `valueOf` (autoboxing); and no `StringBuilder`, `StringConcatFactory`, `String.format` or `String.concat` in methods named `onMessage`, `doWork`, `publish` and the other message-path methods listed in the test. `rates-core` compiles with `-XDstringConcat=inline` so concatenation is visible as `StringBuilder` calls. A source scan rejects `synchronized` blocks. Every rule is also run against a test fixture package that breaks it, and must fail there. `archunit-junit5` is the dependency, as the brief asks; the rules run inside ordinary JUnit tests rather than through ArchUnit's own engine.

Consequences. Error messages on hot paths are built in a separate class (`ContractBreach`), off the message path. The rules apply to every class in a marked package, including configuration code, so builders use arrays instead of lists.

## DR-P2C-07: Loop control messages live in their own schema, not in SBE schema v1

Date: 2026-10-31

Context. The loop needs three messages that schema v1 does not have: an error record, a backpressure record and a gateway connect or disconnect event. Schema v1 is shared with every other session and is versioned by rule (API_SPEC.md Section 2).

Decision. A core control schema with schema id 100, version 0, holds LoopError (65001), Backpressure (65002) and GatewayStatus (65003), hand-encoded in `ControlMessages` with the standard SBE header (API_SPEC.md Section 9.6). Template ids start at 65001 so a frame's template id alone never collides with schema 1.

Consequences. Schema v1 stays untouched by P2. A reader that interprets journal frames must check the schema id in the payload header, which Section 4 already requires. If the control messages grow, they move to an XML schema with generated codecs under the same ids.

## DR-P2C-08: The harness reports decode-to-encode (T3 minus T2) and scheduled-to-encode (T3 minus T0)

Date: 2026-10-31

Context. docs/BENCHMARKS.md defines core decode-to-encode as T3 minus T1, with T1 stamped by the gateway after publishing. In-process, the generator cannot stamp T1 into a message after publishing it, and a side channel written after the publish races the loop. The brief asks for decode-to-encode recorded with `recordValueWithExpectedInterval`.

Decision. The harness records two intervals on the loop thread: decode-to-encode, T3 minus T2 (handler entry, before the SBE decode, to the Quote published and journaled), with `recordValueWithExpectedInterval` at the generator's interval; and scheduled-to-encode, T3 minus T0 (the generator's scheduled send time to the same point), which includes generator lateness, ring queueing and inbound journaling and is free of coordinated omission because it starts from the schedule. T1 is not stamped. Both are reported with the machine, JVM flags and warm-up policy.

Consequences. Decode-to-encode is the handler's own cost; scheduled-to-encode is the figure to compare with a desk's latency budget for the core. The difference between them is queueing plus inbound journaling. Tick-to-wire stays a hook (`TickToWireProbe`) for the FIX codec.

## DR-P2C-09: The allocation test warms up until a round allocates nothing

Date: 2026-10-31

Context. On 2026-10-31 the allocation test measured 296 bytes and, in another run, 72 bytes on the loop thread in the first million messages after a 300,000-message warm-up, and exactly 0 in each of three later windows of one million. The pattern points to late JIT activity at the start of measurement rather than allocation in the steady state; JFR could not be dumped from inside the Surefire fork to confirm it.

Decision. `LoopAllocationTest` warms up with one million messages, then runs rounds of 100,000 until a round allocates nothing (at most 20), then asserts exactly zero bytes over the next one million.

Consequences. The assertion is unchanged: zero bytes, not a tolerance. A real steady-state allocation still fails the test, because every settle round would allocate and the measured million would too. If the settle rounds start to need more than a few rounds, that is investigated with JFR outside Surefire.

## DR-P2C-10: Cookbook numbers for the event-core recipes follow the cookbook's plan

Date: 2026-10-31

Context. The brief for this work names the event-core recipes 8 and 9. COOKBOOK.md's plan assigns 8 and 9 to P1 (Treasury futures CTD and QuantLib comparison) and assigns the event-loop determinism recipe to 12 and the latency histogram recipe to 19.

Decision. The repository wins: the two recipes are 12 (run the event loop against a journal and check determinism) and 19 (run the latency job and read the histogram). Recipe 19 was planned for P5; the harness exists now, so P2 writes it and P5 extends it with the latency job's rate sweep.

Consequences. No collision with the P1 recipes. The P5 session should extend recipe 19 rather than write a new one.
