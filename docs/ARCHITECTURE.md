# Architecture

This document describes the system as designed and marks, component by component, what exists in Phase 0 and what is planned. Where a row says "planned", nothing in the repository implements it yet.

## Components

| Component | Responsibility | Status |
|---|---|---|
| Venue gateways | Speak FIX 4.4 (RFQ, orders) and the SBE book feed to venues and simulators; translate to internal SBE messages; own session state and sequence numbers | planned P2 |
| Event core | One thread per instrument group draining a ring buffer, applying events to owned state, emitting outputs, appending inputs to the journal | planned P2 (clock, timers, journal and messages exist in P0) |
| Market state | Books, last trades, curve inputs per instrument group, updated incrementally | planned P2 |
| Pricing | Curves, swap and bond pricers, futures analytics, incremental revaluation on input change, validated against QuantLib | planned P1 |
| Quoting engine | Turn an RFQ or book state plus inventory and risk into a two-way quote with skew; manage quote lifetimes | P3 (`rates-quoting`), against stubs until P1 and P2 are bound |
| Smart order router | Choose venue and size for hedge and client orders; record decision inputs for markout analysis | planned P4 |
| Risk engine | DV01 and key-rate ladders, limits, breaches, kill switch | P3 (`rates-risk`) |
| Hedger | Decide when and how to hedge residual DV01 with futures and benchmarks | P3 (`rates-hedge`) |
| Journal and replay | Append-only hash-chained record of inputs; replayer that drives the same handlers and verifies the chain | exists in P0 (`rates-journal`) |
| Back-tester | Replay journals or simulated days through the live code path with pre-registered hypotheses and holdouts; attribution | planned P5 |
| Analytics (research) | Python: provenance, calibration of simulator parameters, result analysis | partly exists in P0 (`research/ratesresearch`: provenance and chain hash); calibration planned P1, P5 |
| Control plane | Start, stop, parameter changes with approval, limit changes, kill switch operation; all changes journaled | planned P3 |
| AI assistants | Draft, review and summarise under `docs/AI_USE_POLICY.md`; never change production parameters without a human-approved PR | policy exists in P0; tooling planned P6 |
| Post-trade, books and records | Positions, fills, P&L by attribution, end-of-day marks, regulatory reporting fields | planned P5 |

## Event loop design

One thread owns one instrument group's state. All inputs for that group (venue messages, timers, control-plane commands) arrive on one ring buffer; the thread drains it in order, appends each input to the journal, and applies it. Outputs (quotes, orders, risk events) are written to outbound ring buffers consumed by gateway threads. No other thread reads or writes the group's state.

Rules that follow from this:

- Cross-thread handoff is only through ring buffers carrying SBE-encoded bytes. No shared mutable objects, no locks, no `volatile` fields outside the buffer implementations.
- No allocation on the hot path after warm-up. Flyweight codecs are reused; collections are pre-sized; primitives are preferred to boxed values. Checked by a `ThreadMXBean.getThreadAllocatedBytes` delta test (planned P2).
- Pricing is incremental. A book update reprices the affected instruments, not the group. A curve input reprices what depends on that curve node.
- Replay is the test. The same handler code runs live and in replay; the journal plus a `ManualClock` reproduces every output. Determinism is checked by comparing output hashes across two replays and across platforms.
- Research is in Python, off the hot path, and consumes journals and reports rather than sharing code with the core.

## Phase 0 module map

```
rates-common      io.ratesalgo.common.time     Clock, SystemClock, ManualClock, TimerWheel
rates-messages    io.ratesalgo.messages.sbe    generated from rates-schema-v1.xml by sbe-tool
rates-journal     io.ratesalgo.journal         JournalWriter, JournalReader, Replayer, exceptions
```

Dependencies: `rates-journal` depends on `rates-common` (for `Clock`) and on Agrona for buffers. `rates-messages` depends on the SBE runtime and Agrona. `rates-common` depends on nothing outside the JDK. `rates-journal` does not depend on `rates-messages`: the journal stores payload bytes and a template id, and interpretation belongs to the handler. The Mermaid source is `docs/diagrams/module_deps.mmd`.

`rates-cookbook` holds the COOKBOOK.md recipes as programs; `RecipesTest` runs each and compares its output with the recorded text, and `tools/check_cookbook.py` checks that the cookbook quotes both verbatim.

Agrona 2.x reaches `jdk.internal.misc.Unsafe`, so every JVM that runs these modules, tests included, starts with `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED` (set for Surefire in the parent `pom.xml`).

## Threading and memory rules

- Hot-path packages (to be listed here as they appear from P2: the event core, market state, pricing used in the loop, quoting, risk) contain no `synchronized`, no `java.util.concurrent` locks, and no allocation after warm-up.
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
| Replay-hash determinism | Same journal, same output hash, across runs and platforms | `Replayer` plus output journal hash | P0 for the chain check; end-to-end planned P2 |
| Latency and allocation | Fixed-rate load, coordinated-omission correction, zero-allocation delta | HdrHistogram, ThreadMXBean | planned P2 (allocation), P5 (latency job) |
| Chaos | Dropped and duplicated venue messages, late timers, full buffers | fault-injecting simulators | planned P5 |

Coverage is measured with JaCoCo; Error Prone and SpotBugs run in `mvn verify`.

## Continuous integration

- Gating: JDK 25 on Linux and Windows, `mvn -q verify`.
- Informational: JDK 27 forward-compatibility leg (DR-003).
- Python: ruff, mypy strict, pytest on 3.12.
- Docs: `mkdocs build --strict`; deploy to GitHub Pages once the repository's Pages source is set to GitHub Actions (docs/GITHUB_PAGES.md).

The workflow files live under `.github/workflows/`.

## How to add a module

1. Create `rates-<name>/` with `src/main/java` and `src/test/java`, package `io.ratesalgo.<name>`.
2. Add it to the parent `pom.xml` modules list and declare only the dependencies it needs; hot-path modules must not pull in logging or collection libraries that allocate.
3. State the module's contract in API_SPEC.md if other modules or the Python side depend on it.
4. Add a row to the component table above, the module map, and `docs/diagrams/module_deps.mmd`.
5. Add tests of each kind in the table that apply, and a CHANGELOG entry.

## Phase 3 modules: quoting, risk and hedging

```
rates-api       io.ratesalgo.api       seams to P1, P2 and P4, with in-memory stubs
rates-risk      io.ratesalgo.risk      RiskEngine, RiskLimits, TokenBucket, KillSwitchController
rates-hedge     io.ratesalgo.hedge     BoxQpSolver, HedgePlanner, Hedger, HedgeAnalytics, cost model
rates-quoting   io.ratesalgo.quoting   QuotingEngine, FairValueEngine, SpreadModel, HitRateModel,
                                       PackageQuoter, ClobQuoter, QuotingEventHandler, Desk
```

Dependencies run one way: `rates-quoting` on `rates-hedge` on `rates-risk` on `rates-api`, which depends on `rates-common` and `rates-messages`. Nothing in P3 depends on P1, P2 or P4 directly; `rates-api` declares what P3 needs from them (DECISIONS.md DR-P3-01):

| Interface | Provided by | What P3 calls |
|---|---|---|
| `InstrumentDirectory` | P1 securities master | static data and a dense index per instrument |
| `FairValueSource` | P1 pricing | fair mid (Decimal9) and the stamp of its newest input |
| `SensitivityProvider` | P1 risk analytics (`Dv01Ladder`, adapted) | key-rate PV01 of a signed quantity at current curves |
| `BookSource` | P2 market state | top of the composite book and its update stamp |
| `OutboundSink` | P2 gateway and control-plane rings | SBE messages out: Quote, QuoteCancel, NewOrder, OrderCancel, RiskLimitBreach, KillSwitch |
| `HedgeOrderSink` | P4 router | parent hedge orders, cancel, cancel all |

`Desk` wires one instrument group: kill switch, risk engine, one hedger per currency, fair value, quoting engine, package and CLOB quoters, and `QuotingEventHandler`, which P2's loop calls with each inbound message and its journal sequence. Everything runs on the group's single writer thread; there are no locks and no scheduler. Time comes from the `Clock` the loop provides, and housekeeping (`QuotingEngine.poll`, `Hedger.onTimer`, `ClobQuoter.update`) is called by the loop.

Hot-path packages added in P3: `io.ratesalgo.quoting` (RFQ, response and poll paths), `io.ratesalgo.risk` and the axe query in `io.ratesalgo.hedge`. `QuotingAllocationTest` asserts that the per-RFQ path allocates nothing after warm-up (DR-P3-08). Message flow inside a decision: the engine checks with the risk engine, reserves the quantity, then writes to the gateway sink, so a message that reaches a gateway has always passed the check (API_SPEC.md Section 13.2). The RFQ decision flow and the kill-switch state machine are in docs/diagrams/rfq_decision.mmd and docs/diagrams/kill_switch_state.mmd.

P3 tests by kind: unit tests per class; property tests for the incremental ladder (`RiskEngineTest`), the key-rate split (`ApiStubsTest`) and the solver against brute force (`BoxQpSolverTest`); simulation gates `MillionRfqTest` (one million RFQs, independent sink auditor), `ChaosTest` (10,000 seeded scenarios of fills, curve shocks, disconnects, feed gaps, heartbeat loss, stale inputs, kills and resets) and `QuotingAllocationTest`; and recipes 14 to 16 in `RecipesTest`.
