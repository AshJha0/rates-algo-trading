# Diagrams

Mermaid sources live in `docs/diagrams/` and are embedded here unchanged. Dashed nodes and arrows mark components that are planned, not present.

## Module dependencies

Every module is built; the outline style marks the phase that introduced it. `rates-journal` does not depend on `rates-messages`: the journal stores payload bytes and a template id, and interpretation belongs to the handler.

```mermaid
--8<-- "docs/diagrams/module_deps.mmd"
```

## Data flow

From a venue through the gateway and ring buffer into the event loop, with every input appended to the journal, and from the journal into the replayer, back-tester and Python research.

```mermaid
--8<-- "docs/diagrams/data_flow.mmd"
```

## One RFQ

The sequence for a dealer-to-client request for quote: request, pre-trade risk check, quote, client acceptance with cover price, position update, hedge decision and routed hedge order. Every message crossing the gateway is journaled. The gateway, quoting engine, risk engine, hedger and router are planned (P2 to P4); the journal frames exist.

```mermaid
--8<-- "docs/diagrams/rfq_sequence.mmd"
```

## One duty cycle of the event loop

What `EventLoop.doWork()` does on its thread (P2): latch the clock, drain the inbound rings with journal-then-dispatch, publish and journal outbound messages, raise backpressure after the handler, poll the timers and journal a clock tick when any fired, then run the agents. The contract is [API contract](api-spec.md) Section 9.

```mermaid
--8<-- "docs/diagrams/event_loop_cycle.mmd"
```

## Journal frame layout

The 32-byte file header, the 32-byte frame header and the chain hash definition, as pinned in [API contract](api-spec.md) Section 1.

```mermaid
--8<-- "docs/diagrams/journal_frame.mmd"
```

## Curve build (P1)

Quotes and fixings become one instrument per pillar; `CurveBuilder` solves them sequentially or globally (DECISIONS.md DR-P1-03); pricers differentiate their value with respect to the curve and the bootstrap Jacobian turns that into the DV01 ladder (DR-P1-04).

```mermaid
--8<-- "docs/diagrams/curve_build.mmd"
```

## Child order lifecycle

The router's child order states and the reports and actions that move between them, as pinned in [API contract](api-spec.md) Section 15.4. Late fills after a cancel are counted up to the cumulative quantity the venue reported with the cancel.

```mermaid
--8<-- "docs/diagrams/child_order_lifecycle.mmd"
```

## Experiment lifecycle

From fitting-period estimates to a pre-registration committed before the run, through the refusals of `ExperimentCli`, to the ledger line that records every run whatever its verdict, and the holdout that is opened once (API_SPEC.md Section 16.8, LEARN.md Section 10).

```mermaid
--8<-- "docs/diagrams/experiment_lifecycle.mmd"
```

## Trade lifecycle

From an RFQ or an order, through the journal and the position keeper, into the order records, the clearing or settlement state machines, official P&L and the daily reconciliation (API_SPEC.md Section 17).

```mermaid
--8<-- "docs/diagrams/trade_lifecycle.mmd"
```

## Deployment topology

The simulated environment: blue and green trading processes on a shared journal volume, the venue simulator, Prometheus, Grafana and the end-of-day report job. The dashed link from blue to green is the warm-standby replication designed in `docs/runbooks/warm-standby-design.md`.

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

The three states of the process-wide kill switch and the four-eyes reset (API_SPEC.md Section 13.4).

```mermaid
--8<-- "docs/diagrams/kill_switch_state.mmd"
```

## System context

Who and what the platform exchanges messages and files with: the desk roles, the simulated clients and venues, the clearing house, the public data sources through the research package, and monitoring; the dashed box is transparency reporting, planned for integration (RTS 6 inventory, US and EU flow rows).

```mermaid
--8<-- "docs/diagrams/system_context.mmd"
```

## RFQ end to end with timings

One D2C RFQ from the client through the gateway, the event loop and its journals, the quoting and risk engines, then the hedger, the router and post-trade, with each time limit labelled where the contract pins it ([API contract](api-spec.md) Sections 11.3, 12.1, 14.2 and 15.1; the latency target is from [Benchmarks](benchmarks.md)). The older [One RFQ](#one-rfq) sequence shows the same path without timings.

```mermaid
--8<-- "docs/diagrams/rfq_end_to_end_timed.mmd"
```

## Hedge and route

How a position change becomes a hedge: the hedger's timer measures the residual, the planner solves for lots, a parent passes `checkOrder`, the router allocates and journals its decision before sending children, and fills flow back into the risk engine and the slippage record ([API contract](api-spec.md) Sections 14 and 15). The links between `HedgeOrderSink` and the router are bound at integration.

```mermaid
--8<-- "docs/diagrams/hedge_route_sequence.mmd"
```

## Multi-curve bootstrap

The order of a two-curve build: the OIS curve is bootstrapped alone, then held fixed as the discount curve of the projection bootstrap, and each build's Jacobian maps price gradients to quote sensitivities for the DV01 ladder ([API contract](api-spec.md) Section 6, DECISIONS.md DR-P1-03 and DR-P1-04). The single-curve solver choice is in [Curve build](#curve-build-p1).

```mermaid
--8<-- "docs/diagrams/multi_curve_bootstrap.mmd"
```

## Replay and determinism

What is journaled in a live run, how `LoopReplay` rebuilds a loop and feeds it the inbound journal at the recorded times, and how the chain hash of what it produces is compared frame by frame with the recorded outbound journal ([API contract](api-spec.md) Section 9.7).

```mermaid
--8<-- "docs/diagrams/replay_determinism.mmd"
```

## Research to production parameters

The path of a fitted parameter from a notebook to `data/presets.yaml` (value, standard error and assumption band), through review and the back-tester, to `HitRatePreset` and the quoting engine; the dashed edges are the preset the file does not yet hold and the binding to the running process ([API contract](api-spec.md) Sections 12.3 and 16.2).

```mermaid
--8<-- "docs/diagrams/research_to_production.mmd"
```

## CI and release pipeline

The three workflows a pull request triggers, which jobs gate the merge and which are informational, the docs deployment from main, and the release that follows integration (dashed). The workflows are `.github/workflows/ci.yml`, `docs.yml` and `ops.yml`; the review rule is in CONTRIBUTING.md.

```mermaid
--8<-- "docs/diagrams/ci_pipeline.mmd"
```

## RTS 6 control map

The UK RTS 6 controls grouped as pre-trade, real-time monitoring, kill, post-trade, surveillance and stress, and change management, each with the component that implements it and the journal they share; the article-by-article status and evidence are in the [compliance inventory](compliance.md).

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
