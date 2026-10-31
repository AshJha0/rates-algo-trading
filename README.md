# rates-algo-trading

[![CI](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml)
[![Licence: Apache 2.0](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

An open-source, Java-based principal e-trading platform for interest rate swaps and government bonds, built in the open with public data only. The design target is a dealer desk's stack in miniature: a low-latency single-writer event core, rates analytics checked against QuantLib, venue simulators that speak FIX 4.4 and an SBE book feed, a quoting engine, a risk engine and hedger, a smart order router, a replay back-tester with pre-registration, post-trade position keeping, and a Python research package.

Documentation site: <https://ashjha0.github.io/rates-algo-trading/>

## Where it stands

As of 2026-10-31 every phase module exists (P0 to P6, merged in pull request 1) and round 2 has wired them into one running trading process, `rates-app` (API_SPEC.md Section 18). `TradingProcessMain` starts P2's D2C RFQ and CLOB simulators in process, connects our FIX gateways to them, feeds the CLOB's SBE book feed and a curve feed through the ring-buffer contract into one event loop for the USD instrument group, and runs on that loop the pricing binding over `rates-analytics`, P3's quoting engine, risk engine and hedger, and one P4 router per hedge Treasury. Every input and output is journaled; the journals feed P6's position keeper and reconciliation and P5's back-tester and daily report, and replay through a fresh desk to the same outbound hash. The control endpoints of `rates-ops` serve health, readiness, metrics and the kill switch.

`SmokeTest` runs ten simulated minutes on seed 7 (2026-10-31 with the round 2 review and audit fixes, Windows 11, JDK 25.0.5; DR-INT-07): 36 RFQs, 27 quoted, 16 filled, 210 hedge parents routed and 213 hedge fills on the CLOB, 229 trades reconciled with both venues without a break, no quote or order working after the kill switch, and an equal replay hash. Recipe 22 in COOKBOOK.md runs the same day and prints the daily report.

What it does not do yet: the venues are simulators only, there is one currency and so one loop, the routers trade on the CLOB only (P2 has no futures simulator), post-trade reads the journals after the run rather than tailing them, and the latency figures in docs/BENCHMARKS.md are for the core loop, not the wired process. `STATUS.md` records estimates against actuals and the open items.

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
rates-common/        Clock, ManualClock, TimerWheel                 (P0)
rates-messages/      SBE schema v1 and generated codecs             (P0)
rates-journal/       Journal writer, reader, segments, replayer     (P0, P2)
rates-analytics/     Curves, swaps, bonds, futures, DV01 ladders    (P1)
rates-core/          Event loop, ring-buffer contract, replay       (P2)
rates-bench/         Latency harness and JMH                        (P2)
rates-refdata/       Securities master, calendars, parties          (P2)
rates-fix/           FIX 4.4 codec, session and venue gateway       (P2)
rates-venues/        D2C, CLOB and SEF simulators, SBE book feed    (P2)
rates-api/           Interfaces P3 binds to, with stubs             (P3)
rates-risk/          Risk engine, limits, kill switch               (P3)
rates-hedge/         Cost-aware hedger                              (P3)
rates-quoting/       RFQ, package and CLOB quoting                  (P3)
rates-router/        Smart order router                             (P4)
rates-backtest/      Back-tester, pre-registration, daily report    (P5)
rates-posttrade/     Positions, records, marks, reconciliation      (P6)
rates-ops/           Health, metrics, config, stress harness        (P6)
rates-app/           The trading process: bindings and assembly     (round 2)
rates-cookbook/      Cookbook recipes as programs, output checked    (P0 onward)
research/            Python package ratesresearch                   (P0 onward)
deploy/              Images, compose file, rehearsal                (P6, round 2)
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
