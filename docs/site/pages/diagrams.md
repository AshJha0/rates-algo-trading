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
