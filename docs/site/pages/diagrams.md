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
