# Architecture

This document describes the system as designed and marks, component by component, what exists in Phase 0 and what is planned. Where a row says "planned", nothing in the repository implements it yet.

## Components

| Component | Responsibility | Status |
|---|---|---|
| Venue gateways | Speak FIX 4.4 (RFQ, orders) and the SBE book feed to venues and simulators; translate to internal SBE messages; own session state and sequence numbers | planned P2 |
| Event core | One thread per instrument group draining a ring buffer, applying events to owned state, emitting outputs, appending inputs and outputs to journals | exists from P2 (`rates-core`: `EventLoop`, `LoopRunner`, `LoopReplay`, fault injection); latency harness in `rates-bench` |
| Market state | Books, last trades, curve inputs per instrument group, updated incrementally | planned P2 |
| Pricing | Curves, swap and bond pricers, futures analytics, incremental revaluation on input change, validated against QuantLib | planned P1 |
| Quoting engine | Turn an RFQ or book state plus inventory and risk into a two-way quote with skew; manage quote lifetimes with timers | planned P3 |
| Smart order router | Choose venue and size for hedge and client orders; record decision inputs for markout analysis | planned P4 |
| Risk engine | DV01 and key-rate ladders, limits, breaches, kill switch | planned P3 |
| Hedger | Decide when and how to hedge residual DV01 with futures and benchmarks | planned P3 |
| Journal and replay | Append-only hash-chained record of inputs; replayer that drives the same handlers and verifies the chain | exists in P0 (`rates-journal`); segment rolling and the loop's outbound replay comparison from P2 |
| Back-tester | Replay journals or simulated days through the live code path with pre-registered hypotheses and holdouts; attribution | planned P5 |
| Analytics (research) | Python: provenance, calibration of simulator parameters, result analysis | partly exists in P0 (`research/ratesresearch`: provenance and chain hash); calibration planned P1, P5 |
| Control plane | Start, stop, parameter changes with approval, limit changes, kill switch operation; all changes journaled | planned P3 |
| AI assistants | Draft, review and summarise under `docs/AI_USE_POLICY.md`; never change production parameters without a human-approved PR | policy exists in P0; tooling planned P6 |
| Post-trade, books and records | Positions, fills, P&L by attribution, end-of-day marks, regulatory reporting fields | planned P5 |

## Event loop design

One thread owns one instrument group's state. All inputs for that group (venue messages, gateway status, control-plane commands) arrive on inbound ring buffers; the thread drains them in order, appends each input to the inbound journal, and applies it. Outputs (quotes, orders, risk events) are appended to the outbound journal and written to one outbound ring per gateway. No other thread reads or writes the group's state. The contract a gateway builds to is API_SPEC.md Section 9; the Mermaid sequence of one duty cycle is `docs/diagrams/event_loop_cycle.mmd`.

What exists (P2, `rates-core`):

- `EventLoop.doWork()` is one duty cycle: latch the clock once (`EventClock`), drain each inbound Agrona `ManyToOneRingBuffer` up to a read limit with check, journal, dispatch by SBE template id to a registered `MessageHandler`, then raise backpressure; poll the `TimerWheel` and journal a clock tick if timers fired; call each `Agent`.
- `EventLoop.publish` journals each outbound message and writes it to the gateway's `OneToOneRingBuffer`. A full ring drops, counts and raises a journaled `BackpressureEvent` after the handler returns (DECISIONS.md DR-P2C-05).
- `LoopRunner` runs a loop on a named thread with an Agrona idle strategy (busy spin, yielding or backoff), optional Linux core pinning through `net.openhft:affinity` (optional dependency; skipped elsewhere), start-of-day and end-of-day hooks, and on any exception journals a LoopError and stops.
- `LoopReplay` rebuilds the loop from the same `LoopFactory`, replays the inbound journal with a `ManualClock`, and compares produced outbound messages with the recorded outbound journal by chain hash, reporting the first mismatch (DR-P2C-01).
- `SegmentedJournal` (in `rates-journal`) rolls `name.000001.raj` onward with one chain across segments (DR-P2C-03).
- `io.ratesalgo.core.chaos.FaultInjector` drops, duplicates, delays and reorders inbound messages and publishes gateway disconnects, for the P3 and P5 tests.

Rules that follow from this:

- Cross-thread handoff is only through ring buffers carrying SBE-encoded bytes. No shared mutable objects, no locks, no `volatile` fields outside the buffer implementations, the runner's run flag and the counters.
- No allocation on the hot path after warm-up. Flyweight codecs are reused; collections are pre-sized; primitives are preferred to boxed values. `LoopAllocationTest` checks a zero `ThreadMXBean.getThreadAllocatedBytes` delta on the loop thread over one million messages; a journal segment roll is the one allocating event and segments are sized to make it rare.
- Pricing is incremental. A book update reprices the affected instruments, not the group. A curve input reprices what depends on that curve node.
- Replay is the test. The same handler code runs live and in replay; the inbound journal plus a `ManualClock` reproduces every output, which `LoopReplayTest` and `LoopPropertiesTest` check. Agents and day hooks are not replayed (DR-P2C-04).
- Research is in Python, off the hot path, and consumes journals and reports rather than sharing code with the core.

## Phase 0 module map

```
rates-common      io.ratesalgo.common.time     Clock, SystemClock, ManualClock, TimerWheel
rates-messages    io.ratesalgo.messages.sbe    generated from rates-schema-v1.xml by sbe-tool
rates-journal     io.ratesalgo.journal         JournalWriter, JournalReader, Replayer, exceptions;
                                               SegmentedJournal, SegmentedJournalReader, FrameSource (P2)
rates-core        io.ratesalgo.core            EventLoop, LoopRunner, LoopReplay, RingBufferContract,
                                               InboundPublisher, ControlMessages, clocks, counters (P2)
                  io.ratesalgo.core.chaos      FaultInjector (P2, test tooling)
rates-bench       io.ratesalgo.bench           LatencyHarness, BenchQuoter, LatencyReport (P2)
                  io.ratesalgo.bench.jmh       ring publish and journal append microbenchmarks (P2)
```

`rates-core` depends on `rates-common`, `rates-messages`, `rates-journal` and Agrona, and optionally on `net.openhft:affinity`. `rates-bench` depends on `rates-core`, HdrHistogram and JMH.

Dependencies: `rates-journal` depends on `rates-common` (for `Clock`) and on Agrona for buffers. `rates-messages` depends on the SBE runtime and Agrona. `rates-common` depends on nothing outside the JDK. `rates-journal` does not depend on `rates-messages`: the journal stores payload bytes and a template id, and interpretation belongs to the handler. The Mermaid source is `docs/diagrams/module_deps.mmd`.

`rates-cookbook` holds the COOKBOOK.md recipes as programs; `RecipesTest` runs each and compares its output with the recorded text, and `tools/check_cookbook.py` checks that the cookbook quotes both verbatim.

Agrona 2.x reaches `jdk.internal.misc.Unsafe`, so every JVM that runs these modules, tests included, starts with `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED` (set for Surefire in the parent `pom.xml`).

## Threading and memory rules

- Hot-path packages carry the `@HotPath` annotation (`io.ratesalgo.core.HotPath`) and contain no `synchronized`, no `java.util.concurrent` locks, no `java.util` collections, no boxing and no string building in message-path methods; `HotPathRulesTest` checks this with ArchUnit (DR-P2C-06). Listed so far: `io.ratesalgo.core` (P2). Market state, pricing used in the loop, quoting and risk join the list as they arrive.
- Gateway threads may allocate during session setup, never during message handling.
- Buffers are allocated at start-up with sizes from configuration and never resized. Back-pressure is recorded as an event.
- Time comes from `Clock` only. `System.nanoTime()` and `Instant.now()` appear only inside `SystemClock`.
- Timers are polled by the owning thread; there is no scheduler thread (API_SPEC.md Section 3).

## Numerical rules

- Every comparison states a tolerance: relative error with an absolute floor, both named in the test and, for QuantLib goldens, in API_SPEC.md Section 5 onward (P1).
- No silent NaN. A function that cannot compute throws with its inputs in the message.
- Wire and journal values are int64 mantissas with exponent -9; floating-point appears only inside analytics and is converted at the boundary with a stated rounding rule.
- Golden files are never regenerated on Windows. They are produced on Linux and checked on both platforms.

## Error handling

Errors that indicate corruption or a contract break (journal hash mismatch, schema mismatch, sequence gap) throw and stop the component; they are not logged and skipped. Errors that are part of normal operation (venue reject, quote expiry, limit breach) are messages, journaled and handled by the loop. API_SPEC.md Section 4 pins the P0 behaviours.

## Testing strategy

| Kind | Purpose | Tooling | Status |
|---|---|---|---|
| Unit | Each class against its contract | JUnit 5 | P0 for the three modules |
| Property-based | Wide input spaces: timer ordering, journal round trips, codec round trips | jqwik (Java), hypothesis (Python) | P0 |
| Golden against QuantLib | Pricing within stated tolerances | fixtures generated on Linux | planned P1 |
| Conformance | Gateway behaviour against FIX 4.4 and SBE book feed specifications | venue simulators | planned P2 |
| Replay-hash determinism | Same journal, same output hash, across runs and platforms | `Replayer` plus output journal hash | P0 for the chain check; loop replay comparison and jqwik property from P2 (`LoopPropertiesTest`) |
| Latency and allocation | Fixed-rate load, coordinated-omission correction, zero-allocation delta | HdrHistogram, ThreadMXBean | P2: `LoopAllocationTest` (gating), `rates-bench` harness (informational CI job); P5 adds the rate sweep |
| Chaos | Dropped and duplicated venue messages, late timers, full buffers | `FaultInjector` (P2) and fault-injecting simulators | P2 for the loop; simulators planned P5 |

Coverage is measured with JaCoCo; Error Prone and SpotBugs run in `mvn verify`.

## Continuous integration

- Gating: JDK 25 on Linux and Windows, `mvn -q verify`.
- Informational: JDK 27 forward-compatibility leg (DR-003); the latency harness for a short run, with its JSON report uploaded as an artifact (DR-005).
- Python: ruff, mypy strict, pytest on 3.12.
- Docs: `mkdocs build --strict`; deploy to GitHub Pages once the repository's Pages source is set to GitHub Actions (docs/GITHUB_PAGES.md).

The workflow files live under `.github/workflows/`.

## How to add a module

1. Create `rates-<name>/` with `src/main/java` and `src/test/java`, package `io.ratesalgo.<name>`.
2. Add it to the parent `pom.xml` modules list and declare only the dependencies it needs; hot-path modules must not pull in logging or collection libraries that allocate.
3. State the module's contract in API_SPEC.md if other modules or the Python side depend on it.
4. Add a row to the component table above, the module map, and `docs/diagrams/module_deps.mmd`.
5. Add tests of each kind in the table that apply, and a CHANGELOG entry.
