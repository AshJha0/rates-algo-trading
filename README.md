# rates-algo-trading

[![CI](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml)
[![Licence: Apache 2.0](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

An open-source, Java-based principal e-trading platform for interest rate swaps and government bonds, built in the open with public data only. The design target is a dealer desk's stack in miniature: a low-latency single-writer event core, rates analytics checked against QuantLib, venue simulators that speak FIX 4.4 and an SBE book feed, a quoting engine, a risk engine and hedger, a smart order router, a replay back-tester with pre-registration, post-trade position keeping, and a Python research package.

Documentation site: <https://ashjha0.github.io/rates-algo-trading/>

## Where it stands

Phase 0, foundations only. As of 2026-10-31 the repository contains:

- `rates-common`: a `Clock` interface, `SystemClock`, `ManualClock` and a hashed `TimerWheel`.
- `rates-messages`: SBE schema v1 (ten fixed-length messages) with codecs generated at build time.
- `rates-journal`: an append-only memory-mapped journal with a hash chain, a reader and a `Replayer` that verifies the chain.
- `rates-cookbook`: the COOKBOOK.md recipes as programs, run and checked in CI.
- `research/ratesresearch`: provenance records for the public data sources and the FNV-1a chain hash in Python.
- This documentation set and the CI workflow (Linux and Windows).

There is no pricing, no curve, no venue simulator, no quoting logic, no risk engine and no strategy yet. Nothing has been measured for latency. Every later component is described in the documents below as planned, with the phase that delivers it. `STATUS.md` records estimates against actuals at each gate.

## What it will demonstrate

- Low-latency Java event processing: one writer per instrument group, ring buffers, off-heap flyweights, allocation-free hot path, deterministic replay from a journal.
- Interest rate swap and government bond analytics: multi-curve discounting, par rates, DV01 ladders, bond price and yield, carry and repo, futures CTD and basis, validated against QuantLib.
- Venue protocols: FIX 4.4 RFQ and order flows, an SBE book feed, D2C RFQ, D2D CLOB with workup, SEF rules.
- Quoting and risk: inventory-aware skew, cover-price modelling with declared assumptions, DV01 and key-rate limits, hedging into futures and benchmarks, a kill switch.
- Back-testing with pre-registration: declared hypotheses, holdouts, attribution, and a replayable record of every run.
- Industrial engineering practice: pinned contracts, property-based tests, golden tests, conformance tests, latency jobs with coordinated-omission correction, change records.
- AI-assisted workflow with validation: assistants draft and review, humans approve, every assisted change is labelled and logged (`docs/AI_USE_POLICY.md`).

## Repository layout

```
rates-common/        Clock, ManualClock, TimerWheel                 (exists, P0)
rates-messages/      SBE schema v1 and generated codecs             (exists, P0)
rates-journal/       Journal writer, reader, replayer               (exists, P0)
rates-cookbook/      Cookbook recipes as programs, output checked    (exists, P0)
research/            Python package ratesresearch                   (exists, P0)
docs/                Architecture, benchmarks method, AI policy, diagrams, MkDocs site
data/                SOURCES.md and presets.yaml (no raw data is committed)
.github/             CI workflows and the pull request template
LEARN.md             The learning text, section by section
COOKBOOK.md          Runnable recipes checked in CI
API_SPEC.md          Pinned cross-module contract
DECISIONS.md         Decision records DR-001 onwards
STATUS.md            Phase table, estimate versus actual
CHANGELOG.md         Keep a Changelog
```

## Build

Java: JDK 25 and Maven 3.9 or later.

```bash
mvn -q verify
```

Python 3.12:

```bash
python -m pip install -e "research[dev]"
pytest -q research
```

Docs site (checked in CI with `--strict`):

```bash
python -m pip install -r docs/site/requirements.txt
mkdocs build --strict
```

## Documents

- [LEARN.md](LEARN.md): the subject matter, from rates products to low-latency Java and regulation.
- [COOKBOOK.md](COOKBOOK.md): how-do-I recipes whose output is checked in CI.
- [API_SPEC.md](API_SPEC.md): the pinned contract: units, journal format, SBE schema, clock semantics, errors.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, the event loop, threading and numerical rules, testing and CI.
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): the latency methodology (no numbers yet).
- [DECISIONS.md](DECISIONS.md): decision records.
- [STATUS.md](STATUS.md): phase status, estimates against actuals.
- [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), [CHANGELOG.md](CHANGELOG.md).
- Site: <https://ashjha0.github.io/rates-algo-trading/>

## Disclaimer

All results produced by this project are simulated against public data and declared assumptions. Nothing here is investment advice, a trading recommendation or a representation of any firm's systems.

## Licence

Apache License 2.0. Copyright 2026 Ashish Jha. See [LICENSE](LICENSE).
