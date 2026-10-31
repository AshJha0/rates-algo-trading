# Diagrams

Mermaid sources live in `docs/diagrams/` and are embedded here unchanged. Dashed nodes and arrows mark components that are planned, not present.

## Module dependencies

Phase 0 modules in solid outline; planned modules dashed. `rates-journal` does not depend on `rates-messages`: the journal stores payload bytes and a template id, and interpretation belongs to the handler.

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
