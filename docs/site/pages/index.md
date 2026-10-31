# rates-algo-trading

An open-source, Java-based principal e-trading platform for interest rate swaps and government bonds, built in the open with public data only.

The design target is a dealer desk's stack in miniature: a low-latency single-writer event core, rates analytics checked against QuantLib, venue simulators that speak FIX 4.4 and an SBE book feed, a quoting engine, a risk engine and hedger, a smart order router, a replay back-tester with pre-registration, post-trade position keeping, and a Python research package.

## Where it stands

!!! note "Phase 0: foundations only"
    As of 2026-10-31 the repository contains a clock and timer wheel, SBE schema v1 with generated codecs, a hash-chained journal with a replayer, a small Python package, this documentation set and CI. There is no pricing, no venue simulator, no quoting logic and no strategy yet. Every later component is described as planned, with the phase that delivers it. [Status](status.md) records estimates against actuals.

## Start here

- [Learn](learn.md): the subject matter, section by section. Sections 1 (rates products) and 11 (low-latency Java) are written; the rest are outlined with their phase.
- [Cookbook](cookbook.md): how-do-I recipes, three of them against the Phase 0 API.
- [API contract](api-spec.md): units, journal format, SBE schema, clock semantics, error behaviour.
- [Architecture](architecture.md): components, the event loop, threading and numerical rules, testing and CI.
- [Benchmarks](benchmarks.md): the latency methodology, with no numbers yet.
- [Decisions](decisions.md): DR-001 to DR-008.
- [Diagrams](diagrams.md): module dependencies, data flow, one RFQ, the journal frame.
- [AI use policy](ai-use-policy.md): what assistants may and may not do here.

## Build

```bash
mvn -q verify                                    # JDK 25, Maven 3.9+
python -m pip install -e "research[dev]"         # Python 3.12
pytest -q research
python -m pip install -r docs/site/requirements.txt
mkdocs build --strict
```

Source: <https://github.com/AshJha0/rates-algo-trading>. Licence: Apache 2.0, copyright 2026 Ashish Jha.

All results are simulated against public data and declared assumptions. Nothing here is investment advice.
