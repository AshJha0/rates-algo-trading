# Architecture

This document describes the system as built at v1.0.0 (2026-10-31), with the 1.1.0 fix-round changes and the 1.2.0 additions noted where they change the picture (1.2.0: the opt-in v2 desk profile, its process wiring and threads, and the experiment pipeline, in [Version 1.2.0: the v2 desk profile](#version-120-the-v2-desk-profile)): the phase modules P0 to P6, wired by round 2 into one trading process (`rates-app`), with the desk features of `rates-strategy` beside the quoting engine. The status column says, component by component, what exists and how it is wired. Where a row says "planned", nothing in the repository implements it yet.

## Components

| Component | Responsibility | Status |
|---|---|---|
| Venue gateways | Speak FIX 4.4 (RFQ, orders) and the SBE book feed to venues and simulators; translate to internal SBE messages; own session state and sequence numbers | wired (round 2): P2's `VenueGateway` per FIX session in `rates-app`'s `VenueSession`, inbound through `InboundRouter` into the groups' rings, outbound read from each group's ring; the book feed through `FeedBinding` (API_SPEC.md Section 18.5) |
| Event core | One thread per instrument group draining a ring buffer, applying events to owned state, emitting outputs, appending inputs and outputs to journals | exists from P2 (`rates-core`: `EventLoop`, `LoopRunner`, `LoopReplay`, fault injection); latency harness in `rates-bench` |
| Market state | Books, last trades, curve inputs per instrument group, updated incrementally | wired (round 2): `LoopBook` keeps 16 levels a side per venue and instrument on the loop, gives P3 the composite top of book and each router its venue view; curve inputs move the pricing binding's curve |
| Pricing | Curves, swap and bond pricers, futures analytics, incremental revaluation on input change, validated against QuantLib | P1 (`rates-analytics`); wired (round 2) by `AnalyticsPricing`, P3's `FairValueSource` and `SensitivityProvider`, one pillar rebuild per CurveInput on the loop (Section 18.3) |
| Quoting engine | Turn an RFQ or book state plus inventory and risk into a two-way quote with skew; manage quote lifetimes | P3 (`rates-quoting`); wired (round 2) as `DeskGroup` handlers on the loop, quoting the D2C simulator over FIX |
| Smart order router | Choose venue and size for hedge and client orders; record decision inputs for markout analysis | P4 (`rates-router`); wired (round 2): one router per hedge instrument behind the hedger's `HedgeOrderSink`, children to the CLOB through the outbound ring, decisions to the `route` journal (DR-R2W-07); the v2 profile routes over every venue listing the instrument with the ladder model (1.2.0) |
| Risk engine | DV01 and key-rate ladders, limits, breaches, kill switch | P3 (`rates-risk`); wired (round 2) on the loop with the pricing binding's ladders; the kill switch is reached through `POST /control/kill` as an inbound KillSwitch message (DR-R2W-10); since 1.1.0 the withdrawal itself is done by the venue sessions on a halt flag, without the loop (DR-RV-S01, API_SPEC.md Section 18.9) |
| Hedger | Decide when and how to hedge residual DV01 with futures and benchmarks | P3 (`rates-hedge`); wired (round 2): on the loop's housekeeping timer every 250 ms, Treasuries on the CLOB only on the default profile; the v2 profile adds a futures book (1.2.0) |
| Journal and replay | Append-only hash-chained record of inputs; replayer that drives the same handlers and verifies the chain | exists in P0 (`rates-journal`); segment rolling and the loop's outbound replay comparison from P2 |
| Back-tester | Replay journals or simulated days through the live code path with pre-registered hypotheses and holdouts; attribution | P5 (`rates-backtest`); wired (round 2): `LoopReplay` with the desk's factory reproduces a recorded day's outbound hash, and `DeskStrategy` runs the same desk under the back-tester for the daily report (Section 18.7) |
| Analytics (research) | Python: provenance, calibration of simulator parameters, result analysis | exists (`research/ratesresearch`): provenance and chain hash (P0), QuantLib goldens (P1), statistics, hit-rate, flow and markout research and notebooks (P5), edge studies (round 2, docs/research/EDGE_STUDIES.md) |
| Desk features | Hedge budgets with internalisation and netting, axes, quote sizing, client tiering, hedge timing, funding penalty, coupon carry; desk P&L view and waterfall; desk experiments | round 2 (`rates-strategy`, API_SPEC.md Section 19, docs/research/DESK_FEATURES.md): `DeskQuoter` reproduces `ModelSpreadStrategy` byte for byte at its defaults; runs under the back-tester, not on the wired loop |
| Control plane | Start, stop, parameter changes with approval, limit changes, kill switch operation; all changes journaled | partly wired (round 2): the kill switch, journaled as an input; since 1.1.0 also a halt flag read by each session's thread and a supervisor thread that halts on a dead or stalled loop or gateway thread; parameter and limit changes with approval are planned |
| AI assistants | Draft, review and summarise under `docs/AI_USE_POLICY.md`; never change production parameters without a human-approved PR | policy and change log (`docs/ai_change_log.md`); no in-process tooling |
| Post-trade, books and records | Positions, fills, P&L by attribution, end-of-day marks, order records, clearing and settlement state, reconciliations, restart procedure, surveillance hooks | P6 (`rates-posttrade`); wired (round 2): `JournalFeed` feeds the loop's journals to the position keeper, `VenueReconciliation` checks it against both venues' trade registers; marks, carry, calendars and the venue status port stay on fixtures |
| Operations | Health, readiness, metrics and control endpoints, configuration validation, stress harness, images, rehearsal, runbooks | P6 (`rates-ops`, `deploy/`, `docs/runbooks/`); the images and the rehearsal start `rates-app`'s `TradingProcessMain` (DR-R2W-14) |

Controls added by the round 2 P&L audit fixes ship off by default and are turned on in code or config, not by the wired process: the learned hit-rate slope and intercept and the cover-spread averaging of package A1 change engine output only once the engine feeds them outcomes and cover gaps; the router's filled-adverse and message costs of package C are off in `RouterParams` defaults and on only in `RouterPresets.globalWithControls`; the CLOB quoter's inventory skew (package B) is off until called. The kill switch reaches the quoting engine, the CLOB quoter, the package quoter and every hedger (`Desk`).

## Event loop design

One thread owns one instrument group's state. All inputs for that group (venue messages, gateway status, control-plane commands) arrive on inbound ring buffers; the thread drains them in order, appends each input to the inbound journal, and applies it. Outputs (quotes, orders, risk events) are appended to the outbound journal and written to one outbound ring per gateway. No other thread reads or writes the group's state. The contract a gateway builds to is API_SPEC.md Section 9; the Mermaid sequence of one duty cycle is `docs/diagrams/event_loop_cycle.mmd`.

What exists (P2, `rates-core`):

- `EventLoop.doWork()` is one duty cycle: latch the clock once (`EventClock`), write any queued cancel or withdrawal again, drain each inbound Agrona `ManyToOneRingBuffer` up to a read limit with check, journal, dispatch by SBE template id to a registered `MessageHandler`, then raise backpressure; with conflation on (opt-in, API_SPEC.md Section 9.9) dispatch at most a fixed number of held curve inputs; poll the `TimerWheel` and journal a clock tick if timers fired; call each `Agent`.
- `EventLoop.publish` journals each outbound message and writes it to the gateway's `OneToOneRingBuffer`. A full ring drops, counts and raises a journaled `BackpressureEvent` after the handler returns (DECISIONS.md DR-P2C-05). Since 1.1.0 the templates named with `EventLoop.Builder.retryOnDrop` (the wired process names its four cancel and withdraw messages) are queued and offered again instead; the retry touches the ring only, never the journal (DR-RV-S04).
- `LoopRunner` runs a loop on a named thread with an Agrona idle strategy (busy spin, yielding or backoff), optional Linux core pinning through `CoreAffinity` (the JDK's foreign function API, no dependency; skipped elsewhere), start-of-day and end-of-day hooks, and on any exception journals a LoopError and stops.
- `LoopReplay` rebuilds the loop from the same `LoopFactory`, replays the inbound journal with a `ManualClock`, and compares produced outbound messages with the recorded outbound journal by chain hash, reporting the first mismatch (DR-P2C-01).
- `SegmentedJournal` (in `rates-journal`) rolls `name.000001.raj` onward with one chain across segments (DR-P2C-03).
- Journal write path (R2). `LoopJournals` opens a loop's two journals in one `JournalMode` and `EventLoop.Builder.journals` installs them. In `DIRECT` mode the loop thread appends to the memory-mapped segment itself: a payload copy, the chain hash, the header and the length written last, with no system call, no lock and no allocation. A background thread per journal zero-fills the next segment with channel writes, maps it and touches every page before it is needed, and forces and unmaps full segments, so the loop thread never takes a page fault on a fresh page, never forces and never maps a file (DR-R2J-01, DR-R2J-02). In `RING` mode the loop thread copies each frame into a 64 MiB off-heap Agrona ring and a journal thread per journal writes the mapped segment and computes the chain hash; the files are the same frame for frame (DR-R2J-03). `DIRECT` is the default (DR-R2J-04); API_SPEC.md Section 9.7 has the contract.
- `io.ratesalgo.core.chaos.FaultInjector` drops, duplicates, delays and reorders inbound messages and publishes gateway disconnects, for the P3 and P5 tests.

Rules that follow from this:

- Cross-thread handoff is only through ring buffers carrying SBE-encoded bytes. No shared mutable objects, no locks, no `volatile` fields outside the buffer implementations, the runner's run flag, the counters and the journals' hand-over of prepared and full segments to their own background threads (DR-R2J-02). Since 1.1.0 the process adds a small set of cross-thread holders outside the loop: the halt flag, each venue session's halt state and unconfirmed count (read by the supervisor and the kill call), and the status fields the status agent writes; another thread may close a session's socket but never drive it (DR-RV-S03).
- No allocation on the hot path after warm-up. Flyweight codecs are reused; collections are pre-sized; primitives are preferred to boxed values. `LoopAllocationTest` checks the `ThreadMXBean.getThreadAllocatedBytes` delta on the loop thread over four windows of one million messages after warm-up (each after the first at most 1,024 bytes, at least one of them zero; docs/BENCHMARKS.md, DR-INT-02, DR-V12-T01). It runs once per journal mode, with 256 MiB segments so that each journal rolls inside the measured windows: a roll allocates nothing (DR-R2J-02).
- No thread in the loop's process may make garbage at a steady rate or hold off a safepoint. A collection, or a long time to safepoint on any thread, stops the loop thread as well; the latency harness reports collections over its measured phase (DR-R2J-01).
- Pricing is incremental. A book update reprices the affected instruments, not the group. A curve input reprices what depends on that curve node.
- Replay is the test. The same handler code runs live and in replay; the inbound journal plus a `ManualClock` reproduces every output, which `LoopReplayTest` and `LoopPropertiesTest` check. Agents and day hooks are not replayed (DR-P2C-04).
- Research is in Python, off the hot path, and consumes journals and reports rather than sharing code with the core.

## Process topology (the recorded profile)

`rates-app` assembles the modules into one process (API_SPEC.md Section 18; diagrams `docs/diagrams/module_deps.mmd` and `docs/diagrams/data_flow.mmd`). Since 1.2.0 each FIX session and the book feed have a thread of their own (before, one `rates-gateways` thread drove them all; DR-R6-L02).

```
 gateway threads                                rates-loop-usd thread (one per instrument group)
 ---------------                                -------------------------------------------------
 rates-d2c    VenueSession(101) <-FIX-> D2C  --+                    +--> outbound ring 0 --> rates-d2c
 rates-clob   VenueSession(102) <-FIX-> CLOB --+--> InboundRouter   |    outbound ring 1 --> rates-clob
 rates-feed   FeedBinding <- CLOB book feed ---+    (by instrument) |    outbound ring 2 --> rates-venues (control)
 rates-venues curve feed (D2C mids) -----------+        |           |
              D2C and CLOB simulators' timed       inbound ring --> EventLoop: DeskGroup handlers, timers
              events, CLOB liquidity, SimClock                       journals: in, out, route
 ops-http: OpsHttpServer -- kill() --> halt flag --> each VenueSession withdraws (cancels, then logout)
                                    \-- KillSwitch --> InboundRouter --> loop (journaled, for replay)
 rates-supervisor thread: watches loop duty cycles and every gateway thread; halts, closes sockets
 status agent --> /control/status (token), /metrics
 venue OrderCancelReject (35=9) --> VenueGateway --> InboundRouter --> DeskGroup --> router child
```

Since 1.1.0 (API_SPEC.md Section 18.9): the kill and any supervisor halt act on the session threads first, each of which withdraws from its own record of live quotes and orders and needs neither the loop nor ring space; the journaled KillSwitch keeps replay equal. The feed binding turns a refused or gapped book delta into DISCONNECTED, a snapshot and CONNECTED (Section 18.5). A session message the loop's ring will not take after 2^20 spins halts the process. A venue's cancel reject reaches the router as template 45.

Start-up on existing journals (1.1.0, review L6, API_SPEC.md Section 18.9; diagram `docs/diagrams/replay_determinism.mmd`):

```
 journal dir --> JournalRecovery.repair --> LoopRecovery: inbound journal --> live EventLoop (RecoveryClock)
   (cut torn tail;        |                    produced messages checked against outbound journal
    refuse other damage)  |                    mismatch, outbound past inbound, trailing LoopError --> refuse to start
                          v
 RecoveryClock goes live --> GatewayStatus DISCONNECTED for both sessions (journaled) --> feed rebuild required
   --> sessions log on, resuming sequence numbers (MappedSequenceStore, <journalDir>/fix)
   --> CLOB: RECONCILING (4) --> OrderMassStatusRequest --> answers to the loop --> RECONCILED (5)
   --> snapshot on the feed --> /health/ready 200
```

The loop keeps the CLOB down from DISCONNECTED until RECONCILED. A dropped session reconnects under `fix.reconnect` and goes through the same RECONCILING step; a halted one does not reconnect. FIX runs over TLS 1.3 (`TcpInitiator`, `SSLEngine` on the gateway thread) unless the venue is on loopback; the control endpoint serves HTTPS with `http.tls` (DR-RV-X06).

- One loop per instrument group; a group is a currency (DR-R2W-03), so the USD universe runs one loop, `usd`, journaling to `<journal dir>/usd/in`, `out` and `route`.
- The simulated venues, the curve feed and the control-ring reader share the `rates-venues` thread, which also paces simulated time; like the session and feed threads it reaches loop state only through the rings. The simulators' QuickFIX/J engines have threads of their own.
- The desk's graph on the loop: pricing binding, composite book, P3 desk, one router per hedge instrument (Section 18.4). The status agent is the loop's only agent and only reads.
- Post-trade and the back-test read the journals after the run (`JournalFeed`, `DeskBacktest`); tailing them live is a later step.

The next section covers the v2 profile.

## Version 1.2.0: the v2 desk profile

**What is opt-in.** Everything in this section. A desk is four choices, each with a recorded version and a second one, named together by `DeskProfile` (rates-app `loop/`): market (`Market.STANDARD` or `SIM_V2`), pricing (`PricingBindings.Version.V1` or `V2`), strategy (`DeskSettings.defaults()` or `strategyV2()`), router (`RouterModel.TOUCH` or `LADDER`). `DeskProfile.defaults()` is the recorded desk and is what the process builds unless the configuration's `desk` section says otherwise. With the default profile nothing quotes or hedges differently from 1.1.0: `SmokeTest` prints 36 RFQs, 27 quotes, 16 fills, 210 hedge parents, 213 hedge fills and replay hash -4423185950475908182, as before. No recorded figure was made with the v2 profile and no experiment has run on it. Design and evidence: docs/research/DESK_V2_WIRING.md (Sections 1 to 12), docs/research/SIM_V2_DESIGN.md, docs/research/PRICING_V2.md, docs/research/STRATEGY_V2.md, docs/research/ROUTER_BENCHMARK.md.

### Data flow of the v2 profile

Simulator v2 (`rates-sim`: `MarketModel`, `RfqFlow`, `BookModel`, `SessionSeeds`) is one market model behind four venues in the process (`SimulatedVenuesV2`, rates-app `sim/`; `Venues.simV2`, rates-venues):

| Venue | Venue id | Gateway / outbound ring | Feed status id | Lists |
|---|---|---|---|---|
| D2C | 101 | 0 | - | swaps at eight tenors and Treasuries (RFQ) |
| first Treasury CLOB | 102 | 1 | 3 | the four on-the-run Treasuries |
| control | - | 2 | - | - |
| second Treasury CLOB | 103 | 4 | 6 | the same Treasuries: the two CLOBs show shares of one book |
| futures book | 104 | 5 | 7 | TYZ6 |

Ring 3 is never written: gateway ids and feed status ids share one id space and 3 is the first CLOB's feed (DR-V12-D02). The futures venue counts contracts on its wire; the loop converts to face where a quantity enters or leaves it (DR-V12-D03).

```
 SimulatedVenuesV2 (rates-venues thread, SimSequencer drains every venue's inbox)
   D2C 101  <-FIX->  VenueSession d2c   --+
   CLOB 102 <-FIX->  VenueSession clob  --+
   CLOB 103 <-FIX->  VenueSession clob2 --+--> InboundRouter --> inbound ring --> EventLoop (usd)
   FUT 104  <-FIX->  VenueSession fut   --+                                        DeskGroup:
   book feeds 102/103/104 + TradePrint (template 4)                                 FuturesOverlay over PricingV2
        --> FeedBinding x3 (status 3, 6, 7) --+                                     LoopBook (per venue, prints)
   CurveFeedV2 (SimV2CurveRound: overnight fixing,                                  strategy v2 (DeskSettings.strategyV2)
        SR1 x2, SR3 x3, par swaps at 8 tenors, every 500 ms) --+                   one router per hedge instrument,
                                                                                    ladder model, every venue listing it
   outbound rings 0, 1, 4, 5 --> the four sessions; ring 2 control; journals in, out, route
```

- **Market data.** One `FeedBinding` per order-book venue under the 1.1.0 rules (snapshot first, a gap or refused frame becomes DISCONNECTED, a snapshot and CONNECTED). Trade prints have their own sequence and are not part of the delta stream; a lost print is counted, never a gap (DR-V12-D04). `DeskGroup.onTradePrint` applies a print to `LoopBook.applyTrade` and to the instrument's router (`SmartOrderRouter.onTrade`).
- **Curve.** `CurveFeedV2` sends the whole build set of pricing v2 in one round, so `PricingV2.unfedInputs()` is 0 after the first round. The front end follows the simulator's two-year node and the overnight rate is constant in a session: declared assumptions in `SimV2CurveRound` (DR-V12-D05).
- **Pricing.** `PricingBindings.create` builds `PricingV2`; with futures in the universe it sits under `FuturesOverlay`, which prices TY off its book and risks it at the model's declared sensitivity, not a cheapest-to-deliver calculation (DR-V12-D06). Pricing v2 state is a function of the journaled inputs. `DeskGroup` calls `onCurveInput`, which holds a round until every input ever fed has a new value or one arrives 100 ms after the round's first, and `advanceTo` from the 10 ms housekeeping timer, which applies a round left incomplete. `setInput` and `repriceIfDirty` exist on `PricingBinding` for a loop that conflates on its own; the desk does not call them. After a reprice every fair value goes to the risk engine and the spread ladder is recomputed. The path, with its refusals, is in [Pricing v2 data path](#pricing-v2-data-path). `SpreadRiskBook` is displayed only: nothing limits or hedges spread risk (DR-V12-D11).
- **Strategy and hedging.** Strategy v2 gets a tick per instrument, a `HedgeAvailability` that is true while a venue listing the instrument has its session up, its book whole and an update within 10 s, the futures book through the composite `LoopBook`, and the markout store read from `markouts.start` and written to `markouts.end` (DR-V12-D07). On the smoke day the factor hedge planner never chose the future at the declared tick costs; the futures path is exercised by an outage test (DR-V12-D10, DESK_V2_WIRING.md Section 5).
- **Routing.** One router per hedge instrument with every venue that lists it: both CLOBs for a Treasury, the futures book for TY. The ladder model (`RouterPresets.ladder()`, `ExecutionModel.defaults()`, `LadderAllocator`) reads every level price (`BookView.levelPrice`) and learns queue rates from prints. Orders the quoting side rests are registered with the router (`registerOwnOrder`). Decisions are journaled with templates 83 and 84 beside 80 to 82.
- **Per-venue state.** Kill, gap, staleness, cancel-reject, overfill and reconciliation are per venue (`DeskGroup.venueUsable(venueId)`, `venueBookNanos(venueId)`). A venue that is down, gapped or silent is excluded and the others keep trading. Post-trade reconciles in venue units (`V2Reconciliation`).

Mermaid: `docs/diagrams/v2_desk_flow.mmd`, the threads and the sequencer in `docs/diagrams/v2_topology.mmd`, the simulator's parts in `docs/diagrams/simulator_v2_components.mmd`.

### Pricing v2 data path

1. `CurveFeedV2` publishes one round, every input with the same timestamp, then the curve heartbeat. `DeskGroup.onCurveInput` takes the kinds Fixing, Future and ParSwap.
2. `PricingV2` checks each value: one already fed must lie within `maxQuoteMove` (100 bp) of its previous value, and a first one between -2% and 25%. A refused value is dropped and counted (`rejectedInputs`).
3. A round is applied with one solve of `SofrCurve`. If the solve fails, the last good curve and its quotes stay and the round is counted as rejected. After a 5 bp move of any input since the last exact computation, `SofrLeaveOneOut` is recomputed exactly; a reduced curve that does not solve is counted (`referenceFailures`) and the extrapolation stays.
4. A swap's fair value is its leave-one-out rate plus a residual with a 5 minute half-life (`FairValueSmoother`). A bond's is the spread fitted to the other bonds (`TreasurySpreadCurve`) plus the same kind of residual. A future's is its own book mid (`FuturesOverlay`).
5. Every ladder is on the eight risk tenors. `DeskGroup.afterReprice` gives the curve move and the fair values to the risk engine and recomputes `SpreadRiskBook`, which nothing limits or hedges.
6. Version 1 stays the default. Its path is shorter: each ParSwap re-solves one pillar and bond fair value is the book mid (API_SPEC.md Section 18.3).

Everything above runs on the loop thread; a reprice costs several hundred microseconds and an RFQ behind it waits. Mermaid: `docs/diagrams/pricing_v2_data_path.mmd`. Design and numbers: docs/research/PRICING_V2.md.

### Threads of one process

`TradingProcess.startThreads` runs one thread per loop, one per FIX session, one per book feed, one for the simulated venues and one supervisor (`TradingProcess.GatewayThread`, DR-R6-L02). The journals and the HTTP server bring their own threads:

| Thread | Recorded profile | v2 profile | Work |
|---|---|---|---|
| `rates-loop-usd` | yes | yes | the event loop: DeskGroup, journals in, out, route |
| `rates-d2c`, `rates-clob` | yes | yes | D2C and first CLOB FIX sessions |
| `rates-clob2`, `rates-fut` | - | yes | second CLOB and futures FIX sessions |
| `rates-feed` | yes | yes | first CLOB book feed |
| `rates-feed2`, `rates-feed-fut` | - | yes | second CLOB and futures book feeds |
| `rates-venues` | yes | yes | simulated venues, curve feed, simulated time; on v2 the only thread that touches the market model (`SimSequencer`) |
| `rates-supervisor` | yes | yes | watches loop duty cycles and every session, feed and venues thread; halts, logs out, closes sockets |
| `journal-prepare-*`, one per journal | yes | yes | creates, touches and maps the next segment; forces and unmaps full ones |
| `journal-drain-*`, one per journal, ring mode only | optional | optional | writes frames and computes the chain hash |
| `ops-http` | yes | yes | health, metrics, status and kill; one thread |

The runtime profile sets idle strategies and journals (`runtime.profile`, API_SPEC.md Section 17.11). `default`: the loop backs off, session and feed threads park 200 microseconds, nothing pinned, direct journals with the FNV-1a chain hash. `lowLatency` (`deploy/config/trading-lowlatency.yaml`): loop, session, feed and journal threads busy-spin, ring journals with the WORD64 chain hash (journal format version 2), segments offered for mlock, curve inputs conflated. `loopCpu + g` is loop g's CPU; `gatewayCpu` is the first session's, then the other sessions in order, then the feeds. Pinning goes through `CoreAffinity` (`sched_setaffinity` by the JDK's foreign function API, Linux only; elsewhere the thread runs unpinned and reports it, DR-R6-L01). The venues thread is never pinned and never spins. A spinning thread belongs on a core the host reserves; pinning does not keep other work off it.

A kill or a halt goes over every session. On the v2 profile the kill is confirmed only when all four venues report nothing working (`TradingProcess.kill`, `KillResult`); otherwise the sessions log out and then the sockets are closed. Mermaid: `docs/diagrams/thread_layout.mmd`.

### Order lifecycle, late fills and refused cancels

A hedge parent becomes child orders in the router. `ChildOrderBook` holds the state machine (API_SPEC.md Section 15.4); `DeskGroup` on the loop joins it to the venue's reports. The rules are the same on both desk profiles.

- **A fill counts once**, by venue and execution id, while the child holds a slot. `DeskGroup` keeps its own set for the fills the routers no longer know and for busts.
- **A cancel frees only what cannot have traded.** On `Cancelled` the freed quantity is ordered minus the larger of the fills seen and the cumulative quantity any report has carried. If the venue's figure is ahead of the fills seen, the slot stays until the missing fills arrive, and they get one bounded wait.
- **A refused cancel** (OrderCancelReject, template 45) puts a child that was still CancelRequested back where it was, PartiallyFilled if anything has filled, and gives fills in flight one more `cancelNanos` wait (2 s by default). Then the cancel is sent again, at most `maxCancelAttempts` (3) times in all, and after that the child is flagged unresolved: its quantity stays reserved and the parent ends Failed. In the simulator v2 back-test a cancel that finds the order filled does the same: the last fills are reported `Filled`, `StrategyContext.cancelOrder` returns false, and `DeskStrategy` sends the desk the cancel reject (DR-V12-E09, `SimV2OrdersTest`). Version 1 back-tests are unchanged: their orders fill whole or not at all.
- **A late fill of a released child** is still a position (DR-V12-D13). When the router no longer holds the order, `DeskGroup` books the fill to the risk engine on the router's own instrument, once by execution id, and counts it in `lateFills()` as well as `unroutedReports()`. The hedger sees it in the next residual. The parent's accounting is not reopened. A TradeBust of that execution reverses it. A report without a fill is counted only.
- **A fill after the hedger let go of its parent** (a kill) is booked straight to the risk engine.
- **An overfill** is booked whole and counted; the child's own cumulative quantity stops at what was ordered.

Mermaid: `docs/diagrams/child_order_lifecycle.mmd` and `docs/diagrams/cancel_fill_race.mmd`.

### Determinism on the v2 profile

The venues of one session share one `MarketModel`, and the two Treasury venues share each `BookModel`. A FIX thread only puts what it receives in its venue's inbox; the venues thread drains the inboxes in a fixed order (D2C, CLOB 1, CLOB 2, futures; each first in, first out) and refuses a second draining thread (`SimSequencer`). The stepped process also fixes the order in which a loop receives what the venues send (`SteppedDelivery`). What is promised (DESK_V2_WIRING.md Section 12.2):

- Replay of any journal, stepped or threaded: exact.
- A stepped day (`TradingProcess.step`, `V2DeskProcess`): repeats exactly, frame for frame (`V2SteppedDeterminismTest`, `TradingProcessV2Test`).
- A threaded live v2 day: not repeatable run to run. Two runs of one seed gave 43,462 and 43,104 inbound frames; each reconciled with 0 breaks and each replayed (`SupervisorV2Test`). Nothing more is claimed.

### Configuration and restart

The `desk` section (`profile: default|v2`, then `market`, `pricing`, `strategy`, `router` one at a time; `ProcessConfig.DeskSelection`) picks the profile; `deploy/config/trading-v2.yaml` is the example. A selection other than the recorded one is written to `desk.selection` in the journal directory before anything is journaled. A restart on journals another selection wrote is refused, and `--replay` builds its desk from that file. A process on the simulator v2 market starts at 09:00 New York (`TradingProcessMain.V2_START`). A fresh start promotes `markouts.end` to `markouts.start`; a restart does not, because the desk it rebuilds by replay must read the store the stopped process read. A restarted simulator v2 market is the seed's market taken alone to the restart time: it forgets the impact of what the desk traded before it stopped. Only the two whole profiles are run by tests at process level; a per-component selection is built but not run end to end.

### Experiment pipeline

The back-test runs a desk profile on simulator v2 (`SimBinding`, `DeskStrategy`, `DeskArms`, rates-app `backtest/`); the harness is in rates-backtest `experiment/`:

```
 registration (research/prereg/ID.yaml, prereg_version 2, kind family; unsigned drafts refused)
   --> ExperimentRunner validate (SessionDesign, BandParameters, SpentData: spent seeds refused)
   --> run: per session, one journal written once from the seed; every arm replays it against
       its own fresh model of the same seed (common random numbers)
   --> checkpoints per session and arm (sessions/<seed>.json, parts/<seed>/); a killed run resumes
   --> summary.json once, after the last session; nothing earlier reads a P&L
   --> ledger: one row per hypothesis plus the description rows (D0, and one per descriptive arm),
       one look per family run
   --> LedgerCli --bh family:ID (Benjamini-Hochberg, computed only by command)
```

`DeskExperiment` (rates-app) is the entry point; `tools/sim-v2/` launches it detached and reads its status ([runbook](runbooks/registered-experiment.md)). A timing-only pilot (`--timing-only`) writes and reads no P&L and makes no ledger row. The family registration `research/prereg/R5-FAM-001.yaml` is unsigned and has not run; the runner refuses it while its status is a draft.

What is on disk and what a failure does (`Checkpoints`, `ExperimentRunner`, `SessionExecutor`):

| File in the output directory | Written | Read by |
|---|---|---|
| `manifest.json` | at the start: registration, configuration and parameter hashes, seeds, arms, rows, factory, presets hash, commit | a resume, which must match it in everything but the worker count and the time |
| `parts/<seed>/` | per row journal facts and per (row, arm) | the same session when it is resumed; removed once the session file exists |
| `sessions/<seed>.json` | atomically, when the session is complete | the summary, once, at the end |
| `timing/<seed>.txt`, `logs/<seed>.log`, `heartbeat.txt` | as the run goes | `status`, which never opens a session file |
| `FAILED` | a session failed twice | the next start, which is refused without `--retry-failed` |
| `summary.json` | once, after the last registered session | people |

| Failure | Effect |
|---|---|
| Draft or superseded registration, dirty checkout (unless `--allow-dirty`), output inside git, spent or already read data, undeclared earlier looks, an arm or row that cannot be built, an excluded band point the simulator would start | refused, exit 2, nothing run, nothing recorded |
| Output directory of another run | the resume stops on the manifest difference |
| A worker dies | the session is retried once, from its next missing arm |
| The row's journal, written again, hashes differently | that attempt fails: the session is not deterministic |
| A session fails twice | `FAILED`; running sessions finish, no new one starts; exit 3, nothing recorded |
| The coordinator is killed | finished sessions and arms are kept; the same command line resumes; no look is added |
| The data set was read while the run was going | refused again before the summary, exit 2 |

Mermaid: `docs/diagrams/experiment_lifecycle.mmd` (both the version 1 path and this one) and `docs/diagrams/family_experiment.mmd`.

## Module map

The main classes per package. Packages of `rates-fix`, `rates-venues` and `rates-refdata` are under [Venue layer](#venue-layer-p2-workstream-w3), of `rates-api`, `rates-risk`, `rates-hedge` and `rates-quoting` under [Phase 3 modules](#phase-3-modules-quoting-risk-and-hedging), of `rates-backtest` under [Back-test](#back-test-p5) and of `rates-posttrade` under [Post-trade](#post-trade-books-and-records-p6).

```
rates-common      io.ratesalgo.common.time     Clock, SystemClock, ManualClock, TimerWheel
rates-common      io.ratesalgo.common.random   SplitMix64 (moved from rates-backtest so the simulator and the back-test share it)
rates-sim         io.ratesalgo.sim             SimParams, MarketSpec, MarketModel, RfqFlow, BookModel, SessionSeeds (round 3)
rates-messages    io.ratesalgo.messages.sbe    generated from rates-schema-v1.xml by sbe-tool
rates-journal     io.ratesalgo.journal         JournalWriter, JournalReader, Replayer, exceptions;
                                               SegmentedJournal, SegmentedJournalReader, FrameSource (P2),
                                               RingJournal, ChainHash, JournalRecovery
rates-core        io.ratesalgo.core            EventLoop, LoopRunner, LoopReplay, RingBufferContract,
                                               InboundPublisher, ControlMessages, clocks, counters (P2),
                                               LoopRecovery, DutyCycleRunner, CoreAffinity
                  io.ratesalgo.core.chaos      FaultInjector (P2, test tooling)
rates-bench       io.ratesalgo.bench           LatencyHarness, BenchQuoter, LatencyReport (P2)
                  io.ratesalgo.bench.wired     WiredDeskBenchmark, WiredDeskSuite, StageProbe (round 6)
                  io.ratesalgo.bench.jmh       ring publish, journal, chain hash, FIX encode and reprice microbenchmarks
rates-analytics   io.ratesalgo.analytics       (P1) date, curve, swap, bond, futures, funding, risk, math
rates-router      io.ratesalgo.router          SmartOrderRouter, Allocator, ChildOrderBook, DecisionCodec (P4);
                                               LadderAllocator, FillCalibration, VenueScorecard (round 6)
rates-posttrade   io.ratesalgo.posttrade       position keeper, records, marks, clearing, settlement, recon (P6)
rates-ops         io.ratesalgo.ops             health and control endpoints, metrics, config, stress harness (P6)
rates-app         io.ratesalgo.app             TradingProcess, TradingProcessMain (round 2)
                  io.ratesalgo.app.pricing     AnalyticsPricing, Dv01Adapter, UsdUniverse; PricingBinding,
                                               PricingBindings, PricingV2, FuturesOverlay, SpreadRiskBook
                  io.ratesalgo.app.market      LoopBook
                  io.ratesalgo.app.loop        DeskLoopFactory, DeskGroup, RouterHedgeSink, AppIds,
                                               DeskProfile, DeskSettings
                  io.ratesalgo.app.gateway     VenueSession, InboundRouter, FeedBinding, LiveSet
                  io.ratesalgo.app.sim         SimClock, SimulatedVenues, CurveFeed, ClobLiquidity;
                                               SimulatedVenuesV2, CurveFeedV2, SteppedDelivery,
                                               V2DeskProcess, V2Reconciliation
                  io.ratesalgo.app.posttrade   JournalFeed, VenueReconciliation
                  io.ratesalgo.app.backtest    JournalExport, DeskStrategy, DeskBacktest; SimBinding,
                                               DeskArms, DeskExperiment
rates-strategy    io.ratesalgo.strategy        DeskQuoter and the desk features (round 2)
                  io.ratesalgo.strategy.experiment, .report, .forward   desk experiments, desk report, forward log
```

`rates-analytics` depends on nothing outside the JDK. Its packages: `date` (epoch-day dates, versioned calendars, day counts, conventions, schedules, IMM dates), `curve` (discount curves, interpolators, bootstrap instruments, `CurveBuilder`, Hull-White convexity), `swap` (OIS conventions, swaps, pricer, fixings), `bond` (bonds, pricer, bills, carry, fitted curves), `futures` (conversion factors, basis, CTD), `funding` (repo curve), `risk` (`Dv01Ladder`, `KeyRateRisk`), `math` (Brent, LU, Levenberg-Marquardt). Pricing calls on built objects do not allocate; builders and schedule generation do. The curve build is drawn in `docs/diagrams/curve_build.mmd`.

`rates-core` depends on `rates-common`, `rates-messages`, `rates-journal` and Agrona. `rates-bench` depends on `rates-core`, `rates-fix`, `rates-app`, HdrHistogram and JMH.

Dependencies: `rates-journal` depends on `rates-common` (for `Clock`) and on Agrona for buffers. `rates-messages` depends on Agrona; `sbe-tool` generates its codecs at build time and is not a runtime dependency. `rates-common` depends on Agrona (the timer wheel's map); `rates-sim` on `rates-common` only. `rates-journal` does not depend on `rates-messages` outside its tests: the journal stores payload bytes and a template id, and interpretation belongs to the handler. The Mermaid source is `docs/diagrams/module_deps.mmd`.

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

Inside, `SmartOrderRouter` owns the parent table, a `TimerWheel` for slices, passive time limits, retries and deadlines, a `ChildRandomiser` for the anti-gaming jitter, an `Allocator` (the cost model of API_SPEC.md Section 15.1 and the greedy allocation of 15.2, with `QueueFillModel` for fill probabilities), a `CostTable` reused for every decision, a `DecisionCodec`, and a `ChildOrderBook` with the child state machine of 15.4. All of it runs on one thread and is allocation-free after warm-up (`RouterAllocationTest`), so it can sit on the event loop. `io.ratesalgo.router.sim` holds in-memory venues and `io.ratesalgo.router.backtest` the harness that compares the router with two baselines (15.6). The child lifecycle diagram is `docs/diagrams/child_order_lifecycle.mmd`; what happens around a late fill or a refused cancel is in [Order lifecycle](#order-lifecycle-late-fills-and-refused-cancels). Since 1.2.0 the router has a second, opt-in execution model over book levels (`RouterPresets.ladder()`, `ExecutionModel`, `LadderAllocator`; templates 83 and 84 beside 80 to 82; API_SPEC.md Section 15.9).

The router re-routes quantity only after a venue confirms a cancel, caps aggressive prices one tick inside its own opposite orders (resting or in flight), and journals each decision before sending its children, so every child order can be traced to the cost table that produced it.

## Back-test (P5)

```
rates-backtest    io.ratesalgo.backtest           BacktestRunner, Strategy, StrategyContext, BacktestConfig, ResultsWriter
                  io.ratesalgo.backtest.model     HitRateModel, CompetitorModel, RfqOutcomeModel, HedgeCostModel, ClobFillModel, Presets
                  io.ratesalgo.backtest.pnl       PositionBook, Attribution, CarryModel (hook)
                  io.ratesalgo.backtest.synth     SyntheticDay, SyntheticDayCli
                  io.ratesalgo.backtest.strategy  FixedSpreadStrategy, ModelSpreadStrategy (experiment P5-001), HedgingQuoter
                  io.ratesalgo.backtest.sim       SimDay, PairedSessions (simulator v2)
                  io.ratesalgo.backtest.experiment ExperimentRunner, SessionDesign, SessionExecutor, SessionWorker,
                                                  Checkpoints, ExperimentSummary, BandParameters, SpentData
                  io.ratesalgo.backtest.stats     block bootstrap, Welch, paired tests, Holm, Benjamini-Hochberg
                  io.ratesalgo.backtest.prereg    PreRegistration, Ledger, Experiment, ExperimentCli, LedgerCli,
                                                  FamilyRules, FamilyAdjustment, SessionDesignRules, DataSets
                  io.ratesalgo.backtest.report    DailyReport, ReportCli, BandRisk, SimV2Report
```

Dependencies: `rates-common`, `rates-sim` (the simulator v2 market model, docs/research/SIM_V2_DESIGN.md), `rates-messages`, `rates-journal`, Agrona, and SnakeYAML for presets, pre-registrations and ledger lines (DR-P5-07). The back-tester is off the hot path: it is single-threaded, allocates per event and is not covered by the allocation rule.

Flow of one run. `BacktestRunner` reads journals with `JournalReader` (chain verified), drives a `ManualClock`, decodes the SBE payloads and calls the `Strategy`. The strategy acts through `StrategyContext`: quotes go to `RfqOutcomeModel` (competitors from `CompetitorModel`, win probability from `HitRateModel` at the run's band point, random draws keyed by RFQ id), market orders to `HedgeCostModel`, limit orders to `ClobFillModel`. Fills are booked in `PositionBook`, which keeps cash and position and the attribution lines side by side; their difference is the residual, checked below one cent. Every output message is SBE-encoded and folded into the output chain hash, which is what the determinism test compares (API_SPEC.md Section 16.1).

Inputs: `SyntheticDay` journals, generated from the presets with a seed per date, or a session of the wired process exported by `JournalExport` (rates-app, round 2). Research reads the outputs (`fills.csv`, `rfqs.csv`, `pnl.csv`, `summary.json`) and the ledger, and writes presets; the two sides share files and pinned formats (API_SPEC.md Section 16), not code. Experiments go through pre-registration and the ledger (`docs/diagrams/experiment_lifecycle.mmd`). The daily report (`ReportCli`) runs unattended from a journal and a config.

Binding to the event loop (done in round 2). `JournalExport` exports a recorded session's inbound journal, and `DeskStrategy` runs the production desk as a back-tester `Strategy`; `DeskBacktest` combines them (rates-app, API_SPEC.md Section 18). RFQ outcomes still come from the back-tester's own `RfqOutcomeModel`, not from the venue simulator that recorded the session (paragraph corrected 2026-11-23).

## Threading and memory rules

- Hot-path packages carry the `@HotPath` annotation (`io.ratesalgo.core.HotPath`) and contain no `synchronized`, no `java.util.concurrent` locks, no `java.util` collections, no boxing and no string building in message-path methods; `HotPathRulesTest` checks this with ArchUnit (DR-P2C-06). The package `io.ratesalgo.core` is annotated (P2); in `rates-app` the annotation is on classes (`DeskGroup`, `LoopOrderGateway`, `RouterHedgeSink`, `LoopBook`, `FuturesOverlay` and the gateway classes `VenueSession`, `InboundRouter`, `FeedBinding`, `LiveSet`) and `AppHotPathRulesTest` checks them. The other hot-path modules are held to zero allocation by their own tests, named in their sections.
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
| Conformance | Gateway behaviour against FIX 4.4 and SBE book feed specifications | venue simulators | P2 certification scripts; end to end from round 2 in `SmokeTest` and `VenueBindingTest` |
| Replay-hash determinism | Same journal, same output hash, across runs and platforms | `Replayer` plus output journal hash | P0 for the chain check; back-tester `DeterminismTest` in P5; loop replay comparison and jqwik property from P2 (`LoopPropertiesTest`) |
| Latency and allocation | Fixed-rate load, coordinated-omission correction, zero-allocation delta | HdrHistogram, ThreadMXBean | P2: `LoopAllocationTest` (gating), `rates-bench` harness (informational CI job); P5 adds the rate sweep |
| Chaos | Dropped and duplicated venue messages, late timers, full buffers | `FaultInjector` (P2) and fault-injecting simulators | P2 for the loop; simulators planned P5 |

Coverage is measured with JaCoCo; Error Prone and SpotBugs run in `mvn verify`.

## Continuous integration

- Gating (the nine required checks of the `main` ruleset): JDK 25 on Linux and Windows (`mvn -B -ntp verify`); Python 3.12 on Linux and Windows (ruff, mypy strict, pytest, ledger chain, cookbook, notebooks); the repository policy; the site build (`tools/check_site_current.py`, `mkdocs build --strict`); configuration and dashboards; the two deployment rehearsals.
- Informational: JDK 27 forward-compatibility leg (DR-003); the latency harness and the wired-desk benchmark for a short run, with reports uploaded as an artifact (DR-005); CodeQL; weekly mutation testing (`mutation.yml`).
- Site deploy to GitHub Pages from `main` (docs/GITHUB_PAGES.md). The forward log runs on its own schedule (`forward.yml`).

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
- a blue/green switch that checks the target is ready, kills quoting on the active colour, and records the switch only when that kill answered 200 with zero live quotes, or when the operator overrides with `RATES_OLD_COLOUR_CONFIRMED_DOWN=1`, which is recorded (1.1.0, review E8);
- a deployment rehearsal that starts the processes (as containers, or as local JVMs in CI), checks readiness, exercises the kill switch through the control endpoint and checks every quote is pulled and the kill journaled (`.github/workflows/ops.yml`).

Recovery: the restart procedure replays the journal, reconciles with every venue's order and quote status, and enables quoting only when nothing blocks (API_SPEC.md Section 17.9). Since 1.1.0 the trading process itself restarts on its journals: it cuts a torn tail, rebuilds each loop by replay through the live loop, resumes the FIX sequence numbers and reconciles the CLOB before using it, and refuses to start on journals it cannot reproduce (Process topology above; DR-RV-X01 to X05; `docs/runbooks/restart.md`). The rehearsal's process mode kills the trading JVM hard and checks that it recovers. The warm standby over Aeron is designed in `docs/runbooks/warm-standby-design.md` (DR-P6-06) and built at integration. Runbooks per incident class are under `docs/runbooks/`; the compliance control inventory is `docs/compliance/RTS6_INVENTORY.md`.

## Forward log (paper trading)

`io.ratesalgo.strategy.forward` in `rates-strategy` runs the back-tested desk default forward against real end-of-day rates (docs/research/PAPER_TRADING_PLAN.md). It adds no engine: `ForwardSession` pins `SyntheticDay`'s market to the committed mids with intraday moves switched off, runs `BacktestRunner` with `DeskFactory.deskDefault()`, and returns the end-of-session positions. `ForwardCycle` closes the open decision with the real close of the next Treasury business day and appends the next decision, through the experiment ledger's `Ledger` class, so the two ledgers share one hash-chain format. `ForwardReport` rebuilds the reports from ledger lines only.

```
GitHub Actions (forward.yml, 01:30 UTC Tue-Sat)
  ratesresearch.forward.fetch  -> forward/inputs/<D>.json   (Treasury, NY Fed SOFR, DTCC medians)
  ForwardCli --as-of D         -> forward/ledger.jsonl       (outcome of the open decision, then the next decision)
                                  forward/reports/<S>.md, SUMMARY.md
  ratesresearch.forward.check  -> chain, sessions, P&L arithmetic re-derived in Python
  git push                     -> forward-log branch (main is never written)
```

The look-ahead rule is structural: `ForwardCycle.decision` reads only the input file of day $D$ and the ledger, and `ForwardCycleTest.decisionDoesNotDependOnLaterData` checks that the decision line is byte-identical when later input files exist.

## Venue layer (P2, workstream W3)

Modules added by the venue half of Phase 2:

```
rates-refdata   io.ratesalgo.refdata          securities, swap templates, futures, parties, auctions, CalendarSource
rates-fix       io.ratesalgo.fix.codec        FixParser, FixWriter, FixFramer, FixTime, AsciiNumbers (hot path)
                io.ratesalgo.fix.session      FixSession, SessionStats (hot path)
                io.ratesalgo.fix.gateway      VenueGateway, InstrumentMap, MessageSink, MessageSource (hot path)
                io.ratesalgo.fix.order        OrderStateTracker (hot path)
                io.ratesalgo.fix.net          TcpInitiator
rates-venues    io.ratesalgo.venues.sim       VenueSimulator (QuickFIX/J base), VenueHarness, EngineSession, SimRng, presets
                io.ratesalgo.venues.d2c       D2cRfqSimulator
                io.ratesalgo.venues.clob      ClobSimulator, ClobBook
                io.ratesalgo.venues.sef       SefSimulator, FcmLimitHub
                io.ratesalgo.venues.feed      BookFeedPublisher, BookFeedConsumer, channels
                io.ratesalgo.venues.md        CompositeBuilder, FixingBook, RejectJournal, JournalSink
                io.ratesalgo.venues.cert      CertRunner and the certification scripts
```

Dependencies: `rates-fix` depends on `rates-common`, `rates-messages` and Agrona only. `rates-venues` depends on `rates-fix`, `rates-refdata`, `rates-sim` (simulator v2 presets only), `rates-messages`, `rates-journal`, Agrona, QuickFIX/J 3.0.2 (simulators only) and SLF4J's no-op binding. `rates-refdata` depends on nothing outside the JDK. The engine never links QuickFIX/J: its side of every connection is `rates-fix` (DR-P2V-01).

How the pieces connect to the event core. A gateway thread owns one `TcpInitiator`, its `FixSession` and a `VenueGateway`. Inbound FIX becomes SBE messages offered to a `MessageSink`; outbound SBE messages taken from a `MessageSource` become FIX. The coordinator wires the sink to the inbound ring buffer and the source to the outbound ring buffer of API_SPEC.md Section 9; the gateway never sees the ring buffers directly. The book feed consumer is itself a `MessageSink` of `BookUpdate` frames, and the market data composite writes `MarketDataReject` through a `RejectJournal` whose sink can be a `JournalSink` on the journal.

Hot-path packages added to the list of the threading rules above: `io.ratesalgo.fix.codec`, `io.ratesalgo.fix.session`, `io.ratesalgo.fix.gateway`, `io.ratesalgo.fix.order`. `AllocationTest` checks them with `ThreadMXBean.getThreadAllocatedBytes` over one million messages. The simulators are not hot path: they allocate, use QuickFIX/J's threads, and serialise every entry point on one lock so runs are reproducible.

Determinism of simulator tests. Each test owns a `ManualClock`. After every action, `EngineSession.quiesce()` waits until both sides' expected inbound numbers equal the other's next outbound numbers, so assertions never race the network. Heartbeat timers use the system monotonic clock and stamps use the manual clock.

How to add a venue:

1. Subclass `VenueSimulator` (or `ClobSimulator` for an order-book venue, as `SefSimulator` does) and implement `onMessage` and `onCounterpartyLogout`; cancel-on-disconnect belongs in the latter.
2. Add a preset to `presets.json` and a config record that reads it.
3. Add a case to `Venues.create` and a standalone `...Main` that delegates to `VenueMain`.
4. Write certification scripts for each flow under `cert/` and add them to `CertSuiteTest`.
5. Document new tags in API_SPEC.md Section 10.2 and the venue's rules in Section 11.2, and add a decision record for every rule the simulator simplifies.

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
| `OutboundSink` | P2 gateway and control-plane rings | SBE messages out: Quote, QuoteWithdraw, NewOrder, OrderWithdraw, RiskLimitBreach, KillSwitch |
| `HedgeOrderSink` | P4 router | parent hedge orders, cancel, cancel all |

`Desk` wires one instrument group: kill switch, risk engine, one hedger per currency, fair value, quoting engine, package and CLOB quoters, and `QuotingEventHandler`, which P2's loop calls with each inbound message and its journal sequence. Everything runs on the group's single writer thread; there are no locks and no scheduler. Time comes from the `Clock` the loop provides, and housekeeping (`QuotingEngine.poll`, `Hedger.onTimer`, `ClobQuoter.update`) is called by the loop.

Hot-path packages added in P3: `io.ratesalgo.quoting` (RFQ, response and poll paths), `io.ratesalgo.risk` and the axe query in `io.ratesalgo.hedge`. `QuotingAllocationTest` asserts that the per-RFQ path allocates nothing after warm-up (DR-P3-08). Message flow inside a decision: the engine checks with the risk engine, reserves the quantity, then writes to the gateway sink, so a message that reaches a gateway has always passed the check (API_SPEC.md Section 13.2). The RFQ decision flow and the kill-switch state machine are in docs/diagrams/rfq_decision.mmd and docs/diagrams/kill_switch_state.mmd.

P3 tests by kind: unit tests per class; property tests for the incremental ladder (`RiskEngineTest`), the key-rate split (`ApiStubsTest`) and the solver against brute force (`BoxQpSolverTest`); simulation gates `MillionRfqTest` (one million RFQs, independent sink auditor), `ChaosTest` (10,000 seeded scenarios of fills, curve shocks, disconnects, feed gaps, heartbeat loss, stale inputs, kills and resets) and `QuotingAllocationTest`; and recipes 14 to 16 in `RecipesTest`.
