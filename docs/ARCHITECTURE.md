# Architecture

This document describes the system as designed and marks, component by component, what exists in Phase 0 and what is planned. Where a row says "planned", nothing in the repository implements it yet.

## Components

| Component | Responsibility | Status |
|---|---|---|
| Venue gateways | Speak FIX 4.4 (RFQ, orders) and the SBE book feed to venues and simulators; translate to internal SBE messages; own session state and sequence numbers | planned P2 |
| Event core | One thread per instrument group draining a ring buffer, applying events to owned state, emitting outputs, appending inputs and outputs to journals | exists from P2 (`rates-core`: `EventLoop`, `LoopRunner`, `LoopReplay`, fault injection); latency harness in `rates-bench` |
| Market state | Books, last trades, curve inputs per instrument group, updated incrementally | planned P2 |
| Pricing | Curves, swap and bond pricers, futures analytics, incremental revaluation on input change, validated against QuantLib | exists in P1 (`rates-analytics`) |
| Quoting engine | Turn an RFQ or book state plus inventory and risk into a two-way quote with skew; manage quote lifetimes with timers | planned P3 |
| Smart order router | Choose venue and size for hedge and client orders; record decision inputs for markout analysis | exists in P4 (`rates-router`) |
| Risk engine | DV01 and key-rate ladders, limits, breaches, kill switch | planned P3 |
| Hedger | Decide when and how to hedge residual DV01 with futures and benchmarks | planned P3 |
| Journal and replay | Append-only hash-chained record of inputs; replayer that drives the same handlers and verifies the chain | exists in P0 (`rates-journal`); segment rolling and the loop's outbound replay comparison from P2 |
| Back-tester | Replay journals or simulated days through the live code path with pre-registered hypotheses and holdouts; attribution | exists from P5 (`rates-backtest`); bound to the P2 loop's replay mode by the coordinator |
| Analytics (research) | Python: provenance, calibration of simulator parameters, result analysis | partly exists in P0 (`research/ratesresearch`: provenance and chain hash); calibration planned P1, P5 |
| Control plane | Start, stop, parameter changes with approval, limit changes, kill switch operation; all changes journaled | planned P3 |
| AI assistants | Draft, review and summarise under `docs/AI_USE_POLICY.md`; never change production parameters without a human-approved PR | policy exists in P0; tooling planned P6 |
| Post-trade, books and records | Positions, fills, P&L by attribution, end-of-day marks, order records, clearing and settlement state, reconciliations, restart procedure, surveillance hooks | exists, P6 (`rates-posttrade`); bound to P1 to P5 at integration |
| Operations | Health, readiness, metrics and control endpoints, configuration validation, stress harness, images, rehearsal, runbooks | exists, P6 (`rates-ops`, `deploy/`, `docs/runbooks/`) |

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
rates-analytics   io.ratesalgo.analytics       (P1) date, curve, swap, bond, futures, funding, risk, math
rates-router      io.ratesalgo.router          SmartOrderRouter, Allocator, ChildOrderBook, DecisionCodec (P4)
rates-posttrade   io.ratesalgo.posttrade       position keeper, records, marks, clearing, settlement, recon (P6)
rates-ops         io.ratesalgo.ops             health and control endpoints, metrics, config, stress harness (P6)
```

`rates-analytics` depends on nothing outside the JDK. Its packages: `date` (epoch-day dates, versioned calendars, day counts, conventions, schedules, IMM dates), `curve` (discount curves, interpolators, bootstrap instruments, `CurveBuilder`, Hull-White convexity), `swap` (OIS conventions, swaps, pricer, fixings), `bond` (bonds, pricer, bills, carry, fitted curves), `futures` (conversion factors, basis, CTD), `funding` (repo curve), `risk` (`Dv01Ladder`, `KeyRateRisk`), `math` (Brent, LU, Levenberg-Marquardt). Pricing calls on built objects do not allocate; builders and schedule generation do. The curve build is drawn in `docs/diagrams/curve_build.mmd`.

`rates-core` depends on `rates-common`, `rates-messages`, `rates-journal` and Agrona, and optionally on `net.openhft:affinity`. `rates-bench` depends on `rates-core`, HdrHistogram and JMH.

Dependencies: `rates-journal` depends on `rates-common` (for `Clock`) and on Agrona for buffers. `rates-messages` depends on the SBE runtime and Agrona. `rates-common` depends on nothing outside the JDK. `rates-journal` does not depend on `rates-messages`: the journal stores payload bytes and a template id, and interpretation belongs to the handler. The Mermaid source is `docs/diagrams/module_deps.mmd`.

`rates-cookbook` holds the COOKBOOK.md recipes as programs; `RecipesTest` runs each and compares its output with the recorded text, and `tools/check_cookbook.py` checks that the cookbook quotes both verbatim.

Agrona 2.x reaches `jdk.internal.misc.Unsafe`, so every JVM that runs these modules, tests included, starts with `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED` (set for Surefire in the parent `pom.xml`).

## Smart order router

`rates-router` (P4) turns a parent order into child orders across CLOB and futures venues for one instrument. It depends on `rates-common` (Clock, TimerWheel) and `rates-messages` (codecs) only; its inputs and outputs are interfaces the coordinator binds (API_SPEC.md Section 15.0): `ParentOrderSink` from the hedger, `BookView` from market state, `OrderGateway` to the venue gateway, `ExecutionListener` back from it, and `FrameSink` to the journal.

```
hedger --ParentOrderSink--> SmartOrderRouter --OrderGateway--> gateway --> venues
                              |  ^                                            |
               BookView ------+  +---------- ExecutionListener <--------------+
                              |
                              +--FrameSink--> journal (RouteDecision 80, RouteDecisionVenue 81,
                                                       ChildOrderTransition 82)
```

Inside, `SmartOrderRouter` owns the parent table, a `TimerWheel` for slices, passive time limits, retries and deadlines, a `ChildRandomiser` for the anti-gaming jitter, an `Allocator` (the cost model of API_SPEC.md Section 15.1 and the greedy allocation of 15.2, with `QueueFillModel` for fill probabilities), a `CostTable` reused for every decision, a `DecisionCodec`, and a `ChildOrderBook` with the child state machine of 15.4. All of it runs on one thread and is allocation-free after warm-up (`RouterAllocationTest`), so it can sit on the event loop. `io.ratesalgo.router.sim` holds in-memory venues and `io.ratesalgo.router.backtest` the harness that compares the router with two baselines (15.6). The child lifecycle diagram is `docs/diagrams/child_order_lifecycle.mmd`.

The router re-routes quantity only after a venue confirms a cancel, caps aggressive prices one tick inside its own opposite orders (resting or in flight), and journals each decision before sending its children, so every child order can be traced to the cost table that produced it.
## Back-test (P5)

```
rates-backtest    io.ratesalgo.backtest           BacktestRunner, Strategy, StrategyContext, BacktestConfig, ResultsWriter
                  io.ratesalgo.backtest.model     HitRateModel, CompetitorModel, RfqOutcomeModel, HedgeCostModel, ClobFillModel, Presets
                  io.ratesalgo.backtest.pnl       PositionBook, Attribution, CarryModel (hook)
                  io.ratesalgo.backtest.synth     SyntheticDay, SyntheticDayCli
                  io.ratesalgo.backtest.strategy  FixedSpreadStrategy, ModelSpreadStrategy (experiment P5-001)
                  io.ratesalgo.backtest.stats     block bootstrap, Welch, Holm
                  io.ratesalgo.backtest.prereg    PreRegistration, Ledger, Experiment, ExperimentCli
                  io.ratesalgo.backtest.report    DailyReport, ReportCli
```

Dependencies: `rates-common`, `rates-messages`, `rates-journal`, Agrona, and SnakeYAML for presets, pre-registrations and ledger lines (DR-P5-07). The back-tester is off the hot path: it is single-threaded, allocates per event and is not covered by the allocation rule.

Flow of one run. `BacktestRunner` reads journals with `JournalReader` (chain verified), drives a `ManualClock`, decodes the SBE payloads and calls the `Strategy`. The strategy acts through `StrategyContext`: quotes go to `RfqOutcomeModel` (competitors from `CompetitorModel`, win probability from `HitRateModel` at the run's band point, random draws keyed by RFQ id), market orders to `HedgeCostModel`, limit orders to `ClobFillModel`. Fills are booked in `PositionBook`, which keeps cash and position and the attribution lines side by side; their difference is the residual, checked below one cent. Every output message is SBE-encoded and folded into the output chain hash, which is what the determinism test compares (API_SPEC.md Section 16.1).

Inputs until the P2 simulators are bound: `SyntheticDay` journals, generated from the presets with a seed per date. Research reads the outputs (`fills.csv`, `rfqs.csv`, `pnl.csv`, `summary.json`) and the ledger, and writes presets; the two sides share files and pinned formats (API_SPEC.md Section 16), not code. Experiments go through pre-registration and the ledger (`docs/diagrams/experiment_lifecycle.mmd`). The daily report (`ReportCli`) runs unattended from a journal and a config.

Binding to the event loop (planned with the coordinator). The P2 loop's replay mode delivers the same SBE flyweights; an adapter that implements `Strategy` over the loop's handlers, or a `BacktestRunner` input that reads the loop's output journal, is the binding point. The outcome models stay in the back-tester until the venue simulators (P2) settle RFQs themselves, at which point the hit-rate model moves into the simulator and the back-tester reads QuoteResponses from the journal.

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
| Golden against QuantLib | Pricing within stated tolerances | `data/golden/analytics_golden.json`, regeneration checked in CI (DR-P1-06) | P1 (`AnalyticsGoldenTest`) |
| Conformance | Gateway behaviour against FIX 4.4 and SBE book feed specifications | venue simulators | planned P2 |
| Replay-hash determinism | Same journal, same output hash, across runs and platforms | `Replayer` plus output journal hash | P0 for the chain check; back-tester `DeterminismTest` in P5; loop replay comparison and jqwik property from P2 (`LoopPropertiesTest`) |
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

## Post-trade, books and records (P6)

`rates-posttrade` reads journals and never writes to the event loop's state. Its packages:

```
io.ratesalgo.posttrade.trade         TradeKey, Trade, PositionKey, BookAssignment, BookMapper
io.ratesalgo.posttrade.position      PositionKeeper, PositionCalculator, InstrumentCatalogue, audit trail
io.ratesalgo.posttrade.records       OrderRecord (RTS 6 Annex II layout), RecordArchive (retention layout)
io.ratesalgo.posttrade.marks         MarkSource, CarrySource, EndOfDayPnl, AttributionLine, PnlReconciler
io.ratesalgo.posttrade.clearing      ClearingLifecycle, ClearingHouseSimulator
io.ratesalgo.posttrade.settlement    SettlementObligation, FailCost, BusinessCalendar
io.ratesalgo.posttrade.recon         Reconciler, KeeperFacts, DailyReconciliation, BreakReport
io.ratesalgo.posttrade.recovery      RestartProcedure, VenueStatusPort, QuotingControl
io.ratesalgo.posttrade.surveillance  SurveillanceDetector, QuoteStuffingDetector, SelfTradeDetector
io.ratesalgo.posttrade.chaos         SyntheticDay, JournalScript
io.ratesalgo.posttrade.report        EodReportMain (the report-generator process)
```

The position keeper is a `FrameHandler`, so the same code books a live journal tail, a replay and a batch job, and its state is a function of the frames. Positions are recomputed from live trades in transaction-time order whenever a trade in the position changes (DR-P6-02), which makes late fills, busts and amendments land where they belong. Official P&L follows the marking policy in API_SPEC.md Section 17.3 (DR-P6-03) and reconciles to the back-tester's attribution with zero tolerance. The daily reconciliation classifies every break and explains it from the journal or sends it to operations (DR-P6-04). The trade lifecycle, from RFQ or order through clearing or settlement into the books, is in `docs/diagrams/trade_lifecycle.mmd`.

The module is off the hot path: it allocates, uses `BigDecimal` for money and sorts collections. The interfaces the coordinator binds to other phases are listed in API_SPEC.md Section 17.14.

## Operations (P6)

`rates-ops` holds what a process needs to be run by people: the health, readiness, metrics and control endpoints on the JDK HTTP server (one thread, no framework), a Prometheus text-format exporter, configuration as versioned YAML validated against a JSON Schema at start, the stress-test harness for RTS 6 Article 10, and `OpsProcessMain`, the entry point of the trading and venue-simulator images (DR-P6-05).

Deployment (`deploy/`, topology in `docs/diagrams/deployment_topology.mmd`):

- one image per process on `eclipse-temurin:25-jre`, running as a non-root user, with the Agrona `--add-opens` flags on the launch line: trading, venue simulator, end-of-day report generator;
- a compose file for the simulated environment with Prometheus, the alert rules and a Grafana dashboard, all versioned in the repository;
- a blue/green switch that checks the target is ready, kills quoting on the active colour and confirms its quotes are gone before recording the switch;
- a deployment rehearsal that starts the processes (as containers, or as local JVMs in CI), checks readiness, exercises the kill switch through the control endpoint and checks every quote is pulled and the kill journaled (`.github/workflows/ops.yml`).

Recovery: the restart procedure replays the journal, reconciles with every venue's order and quote status, and enables quoting only when nothing blocks (API_SPEC.md Section 17.9). The warm standby over Aeron is designed in `docs/runbooks/warm-standby-design.md` (DR-P6-06) and built at integration. Runbooks per incident class are under `docs/runbooks/`; the compliance control inventory is `docs/compliance/RTS6_INVENTORY.md`.
