# rates-algo-trading

An open Java 25 principal e-trading platform for USD interest rate swaps and Treasuries, with a Python research package, built from public data only. Version 1.1.0 (CHANGELOG section dated 2026-11-23). Page updated 2026-11-23.

It is a simulator. Every venue runs in the same process, and every P&L figure comes from simulated clients under declared assumptions (DECISIONS.md DR-008). It was built by parallel AI coding sessions directed by one engineer. Verification is CI plus external goldens: QuantLib, published Treasury auctions and published overnight indices. There has been no independent human review of the code.

!!! warning "Read the review first"
    An in-depth review on 2026-11-23 found that the published P&L figures are fixed by declared simulator constants, that the wired process uses little of the pricing library, and that the safety path can fail open. [Reviewer summary](reviewer-summary.md) is one page; the [full review](review-2026-11-23.md) has the findings with file and line references. A fix round is in progress ([Status](status.md)).

## What is built

- A single-writer event loop with hash-chained journals and replay to an equal hash ([Architecture](architecture.md)).
- Rates analytics: curves, OIS swaps, bonds, futures, DV01 ladders, checked against QuantLib 1.44 on 224 instruments ([API contract](api-spec.md) Section 7.2).
- In-process venue simulators (D2C RFQ, D2D CLOB with workup, SEF) behind FIX 4.4 gateways and an SBE book feed.
- A quoting engine, a risk engine with a kill switch, a hedger and a smart order router, wired into one process, `rates-app`.
- A back-tester that runs only pre-registered experiments and appends every run to a hash-chained ledger (32 rows on 2026-11-23).
- Post-trade position keeping and reconciliation, read from the journals after the run.
- A daily forward log on real end-of-day rates with synthetic clients, on the `forward-log` branch. It is not a track record ([Paper trading plan](https://github.com/AshJha0/rates-algo-trading/blob/main/docs/research/PAPER_TRADING_PLAN.md)).

What is not built or not real is listed under "Limits" in the [README](https://github.com/AshJha0/rates-algo-trading#limits).

## Start here

- [Reviewer summary](reviewer-summary.md): what is real evidence, the biggest limits, where to look in five minutes.
- [Learn](learn.md): the subject, from rates products to low-latency Java and regulation, in 17 sections.
- [Cookbook](cookbook.md): 37 how-do-I recipes; 36 have their output checked in CI.
- [API contract](api-spec.md): units, journal format, SBE schema, clock semantics, each module's interface.
- [Architecture](architecture.md): components, the wired process, the event loop, threading and numerical rules.
- [Benchmarks](benchmarks.md): latency method and every recorded run, laptop and hosted runner.
- [Decisions](decisions.md): decision records.
- [Status](status.md): estimate against actual for every phase and round, and open items.
- [Diagrams](diagrams.md): module dependencies, data flow, the RFQ and hedge paths, the journal frame.
- [AI use policy](ai-use-policy.md): how assistants are used here and what is actually checked.

## Build

```bash
mvn -B -ntp verify                               # JDK 25, Maven 3.9+
python -m pip install -e "research[dev]"         # Python 3.12
pytest -q research
python -m pip install -r docs/site/requirements.txt
mkdocs build --strict
```

Source: <https://github.com/AshJha0/rates-algo-trading>. Licence: Apache 2.0, copyright 2026 Ashish Jha.

All results are simulated against public data and declared assumptions. Nothing here is investment advice.
