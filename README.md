# rates-algo-trading

[![CI](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml)
[![Licence: Apache 2.0](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

An open Java 25 principal e-trading platform for interest rate swaps and government bonds, with a Python research package, built from public data only. Version 1.0.0 (2026-10-31).

It is a dealer desk's stack in miniature, and it is a simulator: every venue is a simulator in the same process, and every P&L figure below comes from simulated client behaviour under declared assumptions. Those figures check that the parts agree with each other; they are not evidence that the strategy would make money in a real market (DECISIONS.md DR-008).

Documentation site: <https://ashjha0.github.io/rates-algo-trading/> (published once the repository is public; until then build it locally, see Quick start).

## What it does

One process, `rates-app` (`TradingProcessMain`, API_SPEC.md Section 18), runs a USD rates desk for one instrument group:

1. **Venues.** A D2C RFQ simulator and a D2D CLOB simulator with workup run in process. Our FIX 4.4 gateways connect to them. The CLOB publishes an SBE book feed, and a curve feed carries D2C mids.
2. **Event loop.** Every inbound message goes through the ring-buffer contract into one single-writer event loop. The loop journals every input and output to hash-chained journals.
3. **Pricing.** `AnalyticsPricing` rebuilds one pillar of a SOFR OIS curve per curve input. It gives the quoting engine fair values and the risk engine DV01 ladders. The analytics are checked against QuantLib.
4. **Quoting and risk.** The quoting engine answers RFQs with a hit-rate-optimal spread, inventory skew and client tiering. The package quoter prices curve and butterfly RFQs as one package. The risk engine enforces DV01, key-rate, basis and swap-spread limits. A kill switch reaches the quoting engine, the package quoter, the CLOB quoter and every hedger.
5. **Hedging and routing.** On a 250 ms timer the hedger plans hedges of residual DV01. A smart order router per hedge Treasury slices each hedge across the CLOB and records every decision.
6. **Post-trade.** P6's position keeper reads the journals after the run and reconciles them with both venues' trade registers.
7. **Back-testing.** P5's back-tester replays the journals through the same desk code to the same outbound hash. It writes the daily report.
8. **Operations.** `rates-ops` serves health, readiness, metrics and the kill switch over HTTP.

Next to the process:

- **Back-testing with pre-registration.** The back-tester runs only pre-registered experiments. Holdouts may be opened once. Every run is appended, whatever its result, to `data/ledger/ledger.jsonl`, a hash-chained ledger with 22 rows at v1.0.0.
- **Desk features.** `rates-strategy` holds internalisation and netting, hedge slicing (depth-sized children, passive first with escalation; the back-tested desk default since V1H-001), axes, quote sizing, client tiering, hedge timing, a funding penalty and coupon carry, together with a desk P&L waterfall. Each feature sits behind an interface whose default is today's engine.
- **Python research package** (`research/ratesresearch`). It covers statistics mirrored line for line with the Java ones, hit-rate, flow and markout models, the QuantLib golden generator, eleven executed notebooks and six edge studies on public data.

## Measured results

Each figure names its source and the date it was measured. Java figures come from `mvn -B -ntp verify` on JDK 25.0.5 and Windows 11. CI repeats them on Linux and Windows.

| What | Result | Source, date |
|---|---|---|
| Test suite | 620 Java tests across 20 modules (`mvn -B -ntp verify`, coverage floor and SpotBugs green), 106 Python tests, 11 executed notebooks, 35 of the 36 cookbook recipes checked against their recorded output | local run on v1-integration, 2026-11-01 |
| Analytics against QuantLib 1.44, 224 instruments | swap PV within 1.6e-15 of notional, par rates within 3.5e-16, bond yields within 7.3e-16 | `AnalyticsGoldenTest`, API_SPEC.md 7.2, 2026-10-31 |
| Published Treasury auctions of 2024 reproduced | all 84 notes and bonds and 332 bills, to the published digits | `PublishedExamplesTest`, 2026-10-31 |
| Event loop latency, core decode to encode, journaling on, 50,000 msg/s | p50 0.1 to 0.2 us, p99.9 1.6 us, 0 bytes allocated on the loop thread (target: p50 under 20 us, p99.9 under 100 us); laptop, unpinned threads | docs/BENCHMARKS.md, runs at 04d12a503a6b, 2026-10-31 |
| Wired process, ten simulated minutes, seed 7 | 36 RFQs, 27 quoted, 16 filled, 210 hedge parents, 213 hedge fills, 229 trades reconciled with 0 breaks, nothing working after the kill switch, replay hash equal | `SmokeTest`, DR-INT-07, 2026-10-31 |
| Risk limits under load | 1,000,000 simulated RFQs with 0 limit violations at the sink; 10,000 chaos scenarios with 0 invariant failures | `MillionRfqTest`, `ChaosTest`, STATUS.md P3, 2026-10-31 |
| Router against single-venue aggressive hedging (simulated) | 2.9388 bp better (95% interval 2.7792 to 3.0631) in scenario `ust10y_three_venue_v1` | `BacktestTest`, API_SPEC.md 15.6, 2026-10-31 |
| First pre-registered experiment, P5-001 | not supported; recorded as such | ledger seq 1, 2026-10-31 |
| Desk features, R2B-001 to R2B-007 | internalisation supported at every band point and adopted as the desk default; the other six not supported | ledger seq 12 to 18, docs/research/DESK_FEATURES.md, 2026-10-31 |
| Edge studies on public data, E1 to E6 | two supported after their holdouts (E3 release volatility and intensity, E4 trade size and clustering); three not supported; one inconclusive because its data could not be downloaded | ledger seq 2 to 7, docs/research/EDGE_STUDIES.md, 2026-10-31 |
| Hedge slicing against one market order, V1H-001 (simulated, depth-walk fills) | supported at every band point: implementation shortfall better by +446k, +300k and +71k USD per session at low, central and high (Holm p at most 0.0022); adopted as the back-tested desk default. The cost-aware trigger, V1H-002, was not supported and stays opt-in | ledger seq 23 and 24, DR-V1H-01 to 03, docs/research/DESK_FEATURES.md, 2026-11-01 |
| Event loop latency on a GitHub-hosted ubuntu-latest runner (shared 2 vCPU VM, informational) | core decode to encode, journaling on, 50,000 msg/s: p50 0.160 us, p99 5.939 us, p99.9 9.511 us | docs/BENCHMARKS.md, CI run 41290006100 at 8bfe1b8af42f, 2026-11-01 |
| Production desk at default settings, 20 fifteen-minute sessions (simulated) | loses money at every band point: -82k, -72k and -105k USD per session at low, central and high | AUDIT-11, ledger seq 19, docs/research/PNL_AUDIT.md, 2026-10-31 |

### Daily-P&L risk across the assumption band

These are simulator consistency checks, not evidence of edge (DR-008). The table recomputes the volatility, Sharpe ratio (zero risk-free rate) and maximum drawdown of the recorded daily P&L. Run `ratesresearch.stats.pnl_risk`, or `BandRisk.fromExperimentResults` in Java, on the ledger rows named. Computed 2026-10-31.

| Run | Band point | Strategy | Mean daily P&L (USD) | P&L volatility (USD) | Daily Sharpe | Max drawdown (USD) |
|---|---|---|---:|---:|---:|---:|
| P5-001, 10 synthetic days (seq 1) | low | model_spread | 2,350,531 | 1,272,516 | 1.85 | 0 |
| | central | model_spread | 1,832,066 | 1,254,129 | 1.46 | 319,515 |
| | high | model_spread | 617,976 | 847,380 | 0.73 | 311,741 |
| | central | fixed_spread | 768,724 | 1,336,526 | 0.58 | 2,129,307 |
| AUDIT-11, 20 sessions of 15 minutes (seq 19) | low | engine | -82,167 | 167,162 | -0.49 | 1,886,022 |
| | central | engine | -72,476 | 175,064 | -0.41 | 1,692,144 |
| | high | engine | -105,217 | 177,325 | -0.59 | 2,295,318 |

The P5-001 Sharpe ratios are high because the simulated clients follow the declared hit-rate model that the quoter is optimised against. That is the consistency the check is for, not a forecast. An AUDIT-11 session is 15 minutes, so annualising its ratio would mean nothing. The daily report and every experiment run now write this table themselves:

- `ReportCli` writes `band_risk.json` and `band_risk.md`, plus a section in each `report.md`.
- `ExperimentCli` writes `<id>-<scope>-band_risk.json` and `.md`.

The figures come from the same `Stats.pnlRisk` (API_SPEC.md 16.9).

## Limits

Read these before reading any number above.

- **Simulator only.** Every venue is a simulator in the same process. Client behaviour comes from declared models: a hit-rate slope, competitor counts and the share of informed flow. Each result is reported at three points of that band (DR-008).
- **The default desk loses money in the simulator.** At its default settings the production desk lost money at every band point in AUDIT-11. Those runs used commit 04edc03, before DR-INT-05 changed `winnersCurseBp`. Re-run them under new ids before planning on their sizes.
- **Audit-fix controls ship off by default.** None of these is on in the wired process:
  - package A1: the hit-rate learning and cover averaging change engine output only once the engine is fed outcomes and cover gaps;
  - package C: the router's adverse-fill and message costs are on only in `RouterPresets.globalWithControls`;
  - package B: the CLOB inventory skew is off until called.
- **One currency, one loop.** The routers and the hedger trade Treasuries on the CLOB only, because there is no futures simulator (DR-R2W-08). The CLOB quoter is built but not enabled in the process. The SEF simulator is not connected.
- **Post-trade reads the journals after the run** rather than tailing them live. Marks, carry, calendars and the venue status port run on fixtures (API_SPEC.md 17.14).
- **Latency figures are for the core loop**, on a laptop with unpinned threads and, informationally, on a shared GitHub-hosted Linux runner. They are not for the wired process. Gating numbers belong on a pinned Linux host (DR-005), which has not been run.
- **Some data was unavailable or had no example to reproduce.** No UK DMO golden test: the DMO formulae paper gives the conventional gilt formulae without a numerical worked example (DR-V1G-02). No FINRA Treasury data (needs an account). E5's fitting data could not be downloaded.
- **Not built:** Aeron replication and the quoting lease (DR-P6-06), APA publication, the Part 43 message and the pre-execution credit check (docs/compliance/RTS6_INVENTORY.md).
- **Cookbook.** All 36 recipes are written. Recipe 9 is a comparison method checked by the golden tests, so `tools/check_cookbook.py` checks 35 of the 36.
- **Ledger flags.** Four audit rows (AUDIT-02 to 04 and AUDIT-12 to 14 carry `code_dirty: true`) were recorded while the ledger file itself was uncommitted; docs/research/PNL_AUDIT.md explains.

## Quick start

Requirements: JDK 25, Maven 3.9 or later, Python 3.12.

```bash
mvn -B -ntp verify
```

```bash
python -m pip install -e "research[dev]"
```

```bash
pytest -q research
```

To run the trading process on a synthetic day and read its daily report, follow COOKBOOK.md recipe 22.

Documentation site, checked in CI with `--strict`:

```bash
python -m pip install -r docs/site/requirements.txt
```

```bash
mkdocs build --strict
```

Other checks CI runs: `ruff check research`, `mypy research`, `python tools/check_cookbook.py`, `python tools/run_notebooks.py`, and `python deploy/rehearsal.py --mode process` (the process with the kill switch).

## Repository layout

```
rates-common/        Clock, ManualClock, TimerWheel                 (P0)
rates-messages/      SBE schema v1 and generated codecs             (P0)
rates-journal/       Journal writer, reader, segments, replayer     (P0, P2, round 2)
rates-analytics/     Curves, swaps, bonds, futures, DV01 ladders    (P1)
rates-core/          Event loop, ring-buffer contract, replay       (P2)
rates-bench/         Latency harness and JMH                        (P2)
rates-refdata/       Securities master, calendars, parties          (P2)
rates-fix/           FIX 4.4 codec, session and venue gateway       (P2)
rates-venues/        D2C, CLOB and SEF simulators, SBE book feed    (P2)
rates-api/           Interfaces P3 binds to, with stubs             (P3)
rates-risk/          Risk engine, limits, kill switch               (P3)
rates-hedge/         Cost-aware hedger                              (P3)
rates-quoting/       RFQ, package and CLOB quoting, Desk            (P3)
rates-router/        Smart order router                             (P4)
rates-backtest/      Back-tester, pre-registration, daily report    (P5)
rates-posttrade/     Positions, records, marks, reconciliation      (P6)
rates-ops/           Health, metrics, config, stress harness        (P6)
rates-app/           The trading process: bindings and assembly     (round 2)
rates-strategy/      Desk features, DeskQuoter, desk experiments    (round 2)
rates-cookbook/      Cookbook recipes as programs, output checked   (P0 onward)
research/            Python package ratesresearch, notebooks, audit (P0 onward)
deploy/              Images, compose file, rehearsal                (P6, round 2)
data/                Sources, presets, pre-registrations, ledger (no raw data)
docs/                Architecture, benchmarks, research, compliance, runbooks, diagrams, site
tools/               Cookbook, notebook, config and inventory checks
```

## Documents

For a newcomer, in reading order:

- [LEARN.md](LEARN.md): the subject, from rates products to low-latency Java and regulation. Each section gives the plain idea, the formal statement and a worked example.
- [COOKBOOK.md](COOKBOOK.md): how-do-I recipes whose output is checked in CI.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, the wired process, the event loop, threading and numerical rules.

For a desk reviewer:

- [API_SPEC.md](API_SPEC.md): the pinned contract: units, journal format, SBE schema, clock semantics, every module's interface.
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): latency method and results.
- [docs/research/PNL_AUDIT.md](docs/research/PNL_AUDIT.md): where the engine loses money, ranked, and the fix packages.
- [docs/research/DESK_FEATURES.md](docs/research/DESK_FEATURES.md): the desk-feature experiments.
- [docs/research/EDGE_STUDIES.md](docs/research/EDGE_STUDIES.md): the edge studies on public data.
- [docs/compliance/RTS6_INVENTORY.md](docs/compliance/RTS6_INVENTORY.md): the RTS 6 control inventory.
- [docs/runbooks/README.md](docs/runbooks/README.md): operations.
- [DECISIONS.md](DECISIONS.md): decision records.
- [STATUS.md](STATUS.md): estimate versus actual for every phase and round.
- [CHANGELOG.md](CHANGELOG.md).

Also: [docs/AI_USE_POLICY.md](docs/AI_USE_POLICY.md), [docs/GITHUB_PAGES.md](docs/GITHUB_PAGES.md), [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md).

The site (<https://ashjha0.github.io/rates-algo-trading/>) renders all of these, plus the diagrams page (`docs/diagrams`) and the executed research notebooks.

## Disclaimer

All results produced by this project are simulated against public data and declared assumptions. Nothing here is investment advice, a trading recommendation or a representation of any firm's systems.

## Licence

Apache License 2.0. Copyright 2026 Ashish Jha. See [LICENSE](LICENSE).
