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

## Journal frame layout

The 32-byte file header, the 32-byte frame header and the chain hash definition, as pinned in [API contract](api-spec.md) Section 1.

```mermaid
--8<-- "docs/diagrams/journal_frame.mmd"
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
