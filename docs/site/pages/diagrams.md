# Diagrams

Mermaid sources live in `docs/diagrams/` and are embedded here unchanged. Dashed nodes and arrows mark components that are not built, or links that are not wired into the trading process.

## Module dependencies

Every module is built; the label gives the phase or round that introduced or last extended it. The arrows are the compile dependencies in the poms. `rates-journal` does not depend on `rates-messages`: the journal stores payload bytes and a template id, and interpretation belongs to the handler.

```mermaid
--8<-- "docs/diagrams/module_deps.mmd"
```

## Data flow

From a venue through the gateway and ring buffer into the event loop, with every input appended to the journal, and from the journal into the replayer, back-tester and Python research. Since 1.1.0 the kill and the supervisor reach the venue sessions directly as well as through the loop (API_SPEC.md Section 18.9).

```mermaid
--8<-- "docs/diagrams/data_flow.mmd"
```

## The v2 desk profile (1.2.0, opt-in)

The second version of each component on one loop: simulator v2's D2C venue, two Treasury CLOBs showing shares of one book and a futures book, each with its own FIX session; three book feeds with trade prints; the curve round that feeds pricing v2 its whole build set; strategy v2; and one ladder-model router per hedge instrument across every venue that lists it. The default desk profile is unchanged and is the one drawn in [Data flow](#data-flow). Design: docs/research/DESK_V2_WIRING.md.

```mermaid
--8<-- "docs/diagrams/v2_desk_flow.mmd"
```

## Threads of one process (1.2.0)

One thread per loop, per FIX session and per book feed, one for the simulated venues and a supervisor over all of them (DECISIONS.md DR-R6-L02). Dashed arrows exist only on the v2 profile. The runtime profile (`default` or `lowLatency`) sets idle strategies and journals; the CPU keys are set on their own (API_SPEC.md Section 17.11).

```mermaid
--8<-- "docs/diagrams/thread_layout.mmd"
```

## The v2 process: venues, sessions and threads (1.2.0, opt-in)

Who may touch what. A venue's FIX thread only fills its inbox; `rates-venues` alone drains the inboxes into the market model, in a fixed order. Four sessions and three feeds each have a thread, and all of them, with the curve feed, publish into the one inbound ring the loop reads ([API contract](api-spec.md) Sections 9.9 and 18.10).

```mermaid
--8<-- "docs/diagrams/v2_topology.mmd"
```

## Pricing v2 data path (1.2.0, opt-in)

One curve input from the feed to a quote: the band check, the round that is held until it is complete or 100 ms old, the curve solve and what happens when it fails, the leave-one-out fair value, the Treasury fit, the two ladders and the push to the risk engine ([API contract](api-spec.md) Section 18.3a, [Pricing v2](pricing-v2.md)).

```mermaid
--8<-- "docs/diagrams/pricing_v2_data_path.mmd"
```

## Simulator v2 components (1.2.0, opt-in)

One market model, built from the parameter files and a session seed, behind both the venue simulators and the back-test ([design](sim-v2-design.md)). The desk changes the market only through its own orders.

```mermaid
--8<-- "docs/diagrams/simulator_v2_components.mmd"
```

## Latency stages of the wired benchmark (1.2.0)

The stamps of `WiredDeskBenchmark`, from the scheduled send T0 to the Quote read by the generator T5, and the interval each pair measures. The headline is T0 to T4. Intervals are named as in its reports; the benchmark's own names stand beside T1 to T3 of the stage table in [Benchmarks](benchmarks.md). Only the default desk profile is measured.

```mermaid
--8<-- "docs/diagrams/wired_latency_stages.mmd"
```

## One RFQ

The sequence for a dealer-to-client request for quote: request, pre-trade risk check, quote, client acceptance with cover price, position update, hedge decision and routed hedge order. The event loop journals every message it receives and sends; the gateway does not. All of these components are built and wired in `rates-app` (API_SPEC.md Section 18); on the default profile the router has one venue, the CLOB; the v2 profile routes over every venue that lists the instrument.

```mermaid
--8<-- "docs/diagrams/rfq_sequence.mmd"
```

## One duty cycle of the event loop

What `EventLoop.doWork()` does on its thread (P2): latch the clock, drain the inbound rings with journal-then-dispatch, publish and journal outbound messages, raise backpressure after the handler, poll the timers and journal a clock tick when any fired, then run the agents. The optional block is curve-input conflation (Section 9.9), off by default. The contract is [API contract](api-spec.md) Section 9.

```mermaid
--8<-- "docs/diagrams/event_loop_cycle.mmd"
```

## Journal frame layout

The 32-byte file header, the 32-byte frame header and the chain hash definitions of format versions 1 and 2, as pinned in [API contract](api-spec.md) Section 1.

```mermaid
--8<-- "docs/diagrams/journal_frame.mmd"
```

## Curve build (P1)

Quotes and fixings become one instrument per pillar; `CurveBuilder` solves them sequentially or globally (DECISIONS.md DR-P1-03); pricers differentiate their value with respect to the curve and the bootstrap Jacobian turns that into the DV01 ladder (DR-P1-04).

```mermaid
--8<-- "docs/diagrams/curve_build.mmd"
```

## Child order lifecycle

The router's child order states and the reports and actions that move between them, as pinned in [API contract](api-spec.md) Section 15.4. Late fills after a cancel are counted up to the cumulative quantity the venue reported with the cancel. Since 1.1.0 every wait is bounded by `RouterTimeouts`, a cancel reject arrives as template 45, and a child with no answer is flagged unresolved. Since 1.2.0 a fill reported after the router released the child is still booked as a position (DR-V12-D13). `Released` is not a value of `ChildState`: it is the slot leaving the book.

```mermaid
--8<-- "docs/diagrams/child_order_lifecycle.mmd"
```

## A fill that races a cancel (1.2.0)

The four orders in which a fill and the answer to a cancel can arrive, and who books the fill in each ([API contract](api-spec.md) Sections 15.4 and 18.4a, DECISIONS.md DR-V12-D13 and DR-V12-E09).

```mermaid
--8<-- "docs/diagrams/cancel_fill_race.mmd"
```

## Experiment lifecycle

From fitting-period estimates to a pre-registration committed before the run, through the refusals of `ExperimentCli`, to the ledger line that records every run whatever its verdict, and the holdout that is opened once (API_SPEC.md Section 16.8, LEARN.md Section 10). Since 1.2.0 the lower box is the simulator v2 path: a family registration (`prereg_version: 2`), `ExperimentRunner` with checkpoints per session and arm, one ledger row per hypothesis plus the description rows, one look per family run, and Benjamini-Hochberg by `LedgerCli --bh`. The first family, R5-FAM-001, is unsigned and has not run.

```mermaid
--8<-- "docs/diagrams/experiment_lifecycle.mmd"
```

## Family experiment: refusals, checkpoints and failures (1.2.0)

The lower box of the diagram above in detail: every refusal that stops a run before it starts, what is written as sessions finish, what a crash or a failed session leaves, and the one step in which the summary and the ledger rows are written ([API contract](api-spec.md) Section 16.10).

```mermaid
--8<-- "docs/diagrams/family_experiment.mmd"
```

## Trade lifecycle

From an RFQ or an order, through the journal and the position keeper, into the order records, the clearing or settlement state machines, official P&L and the daily reconciliation (API_SPEC.md Section 17).

```mermaid
--8<-- "docs/diagrams/trade_lifecycle.mmd"
```

## Deployment topology

The simulated environment: blue and green trading processes on a shared journal volume (each runs its venue simulators in process), the venue-sim image, which serves health and metrics only, Prometheus, Grafana and the end-of-day report job. The dashed link from blue to green is the warm-standby replication designed in `docs/runbooks/warm-standby-design.md`.

```mermaid
--8<-- "docs/diagrams/deployment_topology.mmd"
```

## Venue RFQ flow

One D2C RFQ through the simulator and the engine-side FIX layer, with the outcome each dealer is told (LEARN.md Section 6, API_SPEC.md Section 10.2).

```mermaid
--8<-- "docs/diagrams/venue_rfq_flow.mmd"
```

## CLOB workup states

The workup state machine of the Treasury CLOB simulator (API_SPEC.md Section 11.2).

```mermaid
--8<-- "docs/diagrams/clob_workup_states.mmd"
```

## RFQ decision flow (P3)

What the quoting engine does with one request for quote, in the order the checks run, and what happens to the quote afterwards (API_SPEC.md Section 12.6).

```mermaid
--8<-- "docs/diagrams/rfq_decision.mmd"
```

## Kill-switch state machine (P3)

The three states of the process-wide kill switch and the four-eyes reset (API_SPEC.md Section 13.4). In the wired process this is the journaled path; the withdrawal is done by the venue sessions on a halt flag (Section 18.9).

```mermaid
--8<-- "docs/diagrams/kill_switch_state.mmd"
```

## System context

Who and what the platform exchanges messages and files with: the desk roles, the simulated clients and venues, the clearing house, the public data sources through the research package, and monitoring; the dashed box is transparency reporting, which is not built, and the dashed SEF link is certified but not connected to the process (RTS 6 inventory, US and EU flow rows).

```mermaid
--8<-- "docs/diagrams/system_context.mmd"
```

## RFQ end to end with timings

One D2C RFQ from the client through the gateway, the event loop and its journals, the quoting and risk engines, then the hedger, the router and post-trade, with each time limit labelled where the contract pins it ([API contract](api-spec.md) Sections 11.3, 12.1, 14.2 and 15.1; the latency target is from [Benchmarks](benchmarks.md)). The older [One RFQ](#one-rfq) sequence shows the same path without timings.

```mermaid
--8<-- "docs/diagrams/rfq_end_to_end_timed.mmd"
```

## Hedge and route

How a position change becomes a hedge: the hedger's timer measures the residual, the planner solves for lots, a parent passes `checkOrder`, the router allocates and journals its decision before sending children, and fills flow back into the risk engine and the slippage record ([API contract](api-spec.md) Sections 14 and 15). `RouterHedgeSink` in `rates-app` binds `HedgeOrderSink` to the routers (DECISIONS.md DR-R2W-07).

```mermaid
--8<-- "docs/diagrams/hedge_route_sequence.mmd"
```

## Multi-curve bootstrap

The order of a two-curve build: the OIS curve is bootstrapped alone, then held fixed as the discount curve of the projection bootstrap, and each build's Jacobian maps price gradients to quote sensitivities for the DV01 ladder ([API contract](api-spec.md) Section 6, DECISIONS.md DR-P1-03 and DR-P1-04). The single-curve solver choice is in [Curve build](#curve-build-p1).

```mermaid
--8<-- "docs/diagrams/multi_curve_bootstrap.mmd"
```

## Replay and determinism

What is journaled in a live run, how `LoopReplay` rebuilds a loop and feeds it the inbound journal at the recorded times, and how the chain hash of what it produces is compared frame by frame with the recorded outbound journal ([API contract](api-spec.md) Section 9.7). Since 1.1.0 the same path restarts the process: a torn tail is cut, the inbound journal is replayed through the live loop, and the CLOB session reconciles before the venue is used (Section 18.9, DR-RV-X01 to X05). The box added in 1.2.0 states what repeats on the v2 desk profile: replay and a stepped day are exact; a threaded live v2 day is not repeatable run to run, and is not claimed to be.

```mermaid
--8<-- "docs/diagrams/replay_determinism.mmd"
```

## Research to production parameters

The path of a fitted parameter from a notebook to `data/presets.yaml` (value, standard error and assumption band), through a pull request and the back-tester, to `HitRatePreset` and the quoting engine; the dashed edge is the preset `data/presets.yaml` does not hold, so the quoting engine reads the shipped default ([API contract](api-spec.md) Sections 12.3 and 16.2).

```mermaid
--8<-- "docs/diagrams/research_to_production.mmd"
```

## CI and release pipeline

The three workflows a pull request triggers, which jobs gate the merge and which are informational, the docs deployment from main, and the release. The workflows are `.github/workflows/ci.yml`, `docs.yml` and `ops.yml`. The merge gate is the nine required checks of the `main` ruleset; it requires no approving review (CONTRIBUTING.md).

```mermaid
--8<-- "docs/diagrams/ci_pipeline.mmd"
```

## RTS 6 control map

The UK RTS 6 controls grouped as pre-trade, real-time monitoring, kill, post-trade, surveillance and stress, and change management, each with the component that illustrates it in simulation and the journal they share; the article-by-article status, on a scale from "illustrated in simulation" to "absent", is in the [compliance inventory](compliance.md). The change-management controls on the map do not operate as drawn: there is no independent reviewer.

```mermaid
--8<-- "docs/diagrams/rts6_control_map.mmd"
```

## Internalisation flow (R2 desk features)

One client RFQ through the desk features of `rates-strategy`: the size envelope, pricing with the feature costs and the axe, the fill into the DV01 ladder where opposing flow nets, and the hedge budget and timing rule that decide what reaches the hedge instrument (API_SPEC.md Section 19, docs/research/DESK_FEATURES.md).

```mermaid
--8<-- "docs/diagrams/internalisation_flow.mmd"
```

## Desk P&L waterfall (R2 desk features)

The capture-versus-cost waterfall of the desk report: the lines sum to position P&L less the attribution residual, checked to the cent in `DeskReportTest` (API_SPEC.md Section 19.10).

```mermaid
--8<-- "docs/diagrams/desk_pnl_waterfall.mmd"
```
