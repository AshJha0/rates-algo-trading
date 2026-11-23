# rates-algo-trading

[![CI](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml)
[![Licence: Apache 2.0](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

An open Java 25 principal e-trading platform for USD interest rate swaps and Treasuries, with a Python research package, built from public data only. Version 1.1.0 (CHANGELOG section dated 2026-11-23).

**How it was built and checked.** It was built by parallel AI coding sessions directed by one engineer. Verification is CI (nine required checks on Linux and Windows) plus external goldens: QuantLib, published Treasury auctions and published overnight indices. There has been no independent human review. An in-depth review on 2026-11-23 ([docs/reviews/REVIEW_2026-11-23.md](docs/reviews/REVIEW_2026-11-23.md)) found that the P&L figures are fixed by declared simulator constants and that parts of the safety path fail open; a fix round is in progress (STATUS.md). Start with the one-page [reviewer summary](docs/REVIEWER_SUMMARY.md).

It is a dealer desk's stack in miniature, and it is a simulator: every venue is a simulator in the same process, and every P&L figure below comes from simulated client behaviour under declared assumptions. Those figures check that the parts agree with each other; they are not evidence that the strategy would make money in a real market (DECISIONS.md DR-008).

Documentation site: <https://ashjha0.github.io/rates-algo-trading/>.

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

- **Back-testing with pre-registration.** The back-tester runs only pre-registered experiments. Holdouts may be opened once. Every run is appended, whatever its result, to `data/ledger/ledger.jsonl`, a hash-chained ledger with 32 rows.
- **Desk features.** `rates-strategy` holds internalisation and netting, hedge slicing (depth-sized children, passive first with escalation; the back-tested desk default since V1H-001), axes, quote sizing, client tiering, hedge timing, a funding penalty and coupon carry, together with a desk P&L waterfall. Each feature sits behind an interface whose default is today's engine.
- **Forward log (paper trading).** Every Treasury business day a scheduled job fetches the real close (US Treasury par curve, NY Fed SOFR, SOFR OIS swap marks from DTCC public prints), runs the desk default for one session of synthetic client RFQs priced off the previous close, marks the resulting book at the real close, and commits the next day's decision to a hash-chained ledger on the `forward-log` branch before that day's rates exist. Rates and marks are real; clients and fills are synthetic and labelled so. docs/research/PAPER_TRADING_PLAN.md.
- **Python research package** (`research/ratesresearch`). It covers statistics mirrored line for line with the Java ones, hit-rate, flow and markout models, the QuantLib golden generator, eleven executed notebooks and six edge studies on public data.

## Measured results

Rows here are either checks against something outside the project or research findings. Each names its source and date. Plumbing self-checks are in the next table.

| What | Result | Kind | Source, date |
|---|---|---|---|
| Analytics against QuantLib 1.44, 224 instruments | swap PV within 1.6e-15 of notional, par rates within 3.5e-16, bond yields within 7.3e-16 | external golden | `AnalyticsGoldenTest`, API_SPEC.md 7.2, 2026-10-31 |
| Published Treasury auctions of 2024 | all 84 notes and bonds and 332 bills reproduced to the published digits | external golden | `PublishedExamplesTest`, 2026-10-31 |
| Published overnight indices of 2024 | compounded legs reproduce the published SOFR, EUR STR and SONIA indices to the last digit | external golden | `CompoundedIndexHoldoutTest`, STATUS.md P1 gate, 2026-10-31 |
| Edge studies on public data, E1 to E6 | two supported after their holdouts (E3 release volatility and intensity, E4 trade size and clustering); three not supported; one inconclusive because its data could not be downloaded | finding, public data | ledger seq 2 to 7, docs/research/EDGE_STUDIES.md, 2026-10-31 |
| Production desk at default settings, 20 fifteen-minute sessions (simulated) | loses -114k, -109k and -125k USD per session at low, central and high (central 95% interval -168k to -41k). The sign follows from declared constants (see Limits); it is not a market finding. Supersedes AUDIT-11 | simulator property | AUDIT-21, ledger seq 25, docs/research/PNL_AUDIT.md, 2026-11-07 |
| Half-spread cap 0.50 bp with 4x skew, chosen on those 20 sessions (simulated) | failed its one look on 5 unopened sessions: difference +96k, +36k (-30k to +101k) and -10k USD per session, central wins down from 15 to 6 a session. Defaults unchanged | finding, simulator | AUDIT-25, AUDIT-26, ledger seq 29 and 30, 2026-11-07 |

### Self-checks of the plumbing

These show that the parts agree with each other and with the project's own models. They are not evidence about markets.

| What | Result | Source, date |
|---|---|---|
| Test suite | 639 Java tests across 20 modules (`mvn -B -ntp verify`, coverage floor and SpotBugs green), 114 Python tests, 11 executed notebooks, 36 of the 37 cookbook recipes checked against their recorded output | local run on the PR #8 branch `hedge-carry-limit` (README commit 7320610), 2026-11-07; not re-run for this page |
| Wired process, ten simulated minutes, seed 7 | 36 RFQs, 27 quoted, 16 filled, 210 hedge parents, 213 hedge fills, 229 trades reconciled with 0 breaks, nothing working after the kill switch, replay hash equal | `SmokeTest`, DR-INT-07, 2026-10-31 |
| Risk limits under load | 1,000,000 simulated RFQs with 0 limit violations at the sink; 10,000 chaos scenarios with 0 invariant failures | `MillionRfqTest`, `ChaosTest`, STATUS.md P3, 2026-10-31 |
| Router regression check | about 2.9 bp better than single-venue aggressive hedging (95% interval 2.8 to 3.1) in scenario `ust10y_three_venue_v1`. A regression check against the router's own simulator and cost model; the baselines are single-shot orders; not a market finding. The wired process gives the router one venue | `BacktestTest`, API_SPEC.md 15.6, 2026-10-31 |
| Event loop latency, laptop | scheduled-to-encode, journaling on (direct): p50 0.5 us, p99 26 us, p99.9 184 us, max 9.3 ms at 50,000 msg/s; 0 bytes allocated on the loop thread. The handler is `BenchQuoter`, a constant-arithmetic bench quoter, not the wired desk. Windows 11 laptop, unpinned threads; every recorded value is a multiple of 0.1 us, the Windows 100 ns clock tick, so core decode-to-encode (p50 0.2 us) is two ticks. Four of the six journaling-on runs at that commit were discarded by the generator-lateness rule | docs/BENCHMARKS.md, run `latency-20261031-115541.json` at 04d12a503a6b, 2026-10-31 |
| Event loop latency, GitHub-hosted runner (shared 2 vCPU VM, informational) | scheduled-to-encode p50 766 us (mostly generator and scheduling delay); core decode-to-encode p50 0.160 us, p99.9 9.511 us; same bench quoter | docs/BENCHMARKS.md, CI run 41290006100 at 8bfe1b8af42f, 2026-11-01 |
| First pre-registered experiment, P5-001 | not supported; recorded as such | ledger seq 1, 2026-10-31 |
| Desk features, R2B-001 to R2B-007 (simulated) | internalisation supported at every band point and adopted as the desk default; the other six not supported | ledger seq 12 to 18, docs/research/DESK_FEATURES.md, 2026-10-31 |
| Hedge slicing against one market order, V1H-001 (simulated) | supported at every band point and adopted as the back-tested desk default. In this simulator our orders have no impact and the touch refills at once, so the result follows from the fill model (review Section 2.3). V1H-002 not supported, opt-in | ledger seq 23 and 24, DR-V1H-01 to 03, 2026-11-01 |
| Hedge latency sweep and carry-time limit, V1C-001 and V1C-002 (simulated) | hedge order latency up to 1 s barely moves P&L; median time from client fill to hedge is 20 minutes to over an hour; neither carry limit supported, desk default unchanged | ledger seq 31 and 32, docs/research/DESK_FEATURES.md, 2026-11-07 |

The daily Sharpe ratio and drawdown table that used to sit here is in [docs/research/BAND_RISK.md](docs/research/BAND_RISK.md), with AUDIT-21 in place of the superseded AUDIT-11. Its figures are a property of the declared simulator constants. `ReportCli` and `ExperimentCli` still write that table for every run (`band_risk.json`, `band_risk.md`).

## Limits

Read these before reading any number above.

- **Simulator only.** Every venue is a simulator in the same process. Client behaviour comes from declared models: a hit-rate slope, competitor counts and the share of informed flow. Each result is reported at three points of that band (DR-008).
- **The desk's P&L sign is fixed by declared constants.** The half-spread is clamped to 0.02 to 0.08 bp (`rates-app/src/main/java/io/ratesalgo/app/loop/DeskSettings.java:59-60`). In the D2C simulator 20% of RFQs are informed and move the mid by 4 competitor half-spreads of 0.25 bp after 30 s (`rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`), an expected adverse cost of 0.2 x 4 x 0.25 = 0.20 bp per swap trade. A desk that earns at most 0.08 bp loses; AUDIT-21 measures that arithmetic (review Section 2.1).
- **Hedging cannot reduce risk in the session simulator.** Each instrument's mid in the D2C simulator is an independent random walk, rounded to the tick (`D2cRfqSimulator.java:160-171`), so a Treasury hedge does not offset a swap position there. The back-tester's own synthetic day (`SyntheticDay`) does have a common level factor.
- **The wired process uses little of `rates-analytics`.** Swap fair value comes from a curve whose pillars are the quoted swaps themselves, bond fair value is the book mid, and bond DV01 is taken on the SOFR OIS curve with no spread (`rates-app/src/main/java/io/ratesalgo/app/pricing/AnalyticsPricing.java:99-108, 234, 278-283`). Turns, convexity, futures, fitted Treasury curves and repo are not used by the process (review A1, A2).
- **One venue, one client tier, wide limits.** The wired router is given one venue, the CLOB (`DeskGroup.java:95`). There is one client tier with firm quotes to 1bn (`DeskSettings.java:83-85`). Risk limits are sized so that the smoke day never breaches them (`DeskSettings.java:68-79`); no price collar or fat-finger quantity is set there.
- **The default desk lost money on average in the simulator, with large swings.** It lost at every band point over the 20 sessions of AUDIT-21 and made money on the 5 sessions of AUDIT-26, where every interval includes zero. Inventory marking is the largest loss line. A setting that widened the cap failed its holdout, and those 5 sessions are now spent.
- **Audit-fix controls ship off by default.** None of these is on in the wired process:
  - package A1: the hit-rate learning and cover averaging change engine output only once the engine is fed outcomes and cover gaps;
  - package C: the router's adverse-fill and message costs are on only in `RouterPresets.globalWithControls`;
  - package B: the CLOB inventory skew is off until called.
- **One currency, one loop.** The routers and the hedger trade Treasuries on the CLOB only, because there is no futures simulator (DR-R2W-08). The CLOB quoter is built but not enabled in the process. The SEF simulator is not connected.
- **Post-trade reads the journals after the run** rather than tailing them live. Marks, carry, calendars and the venue status port run on fixtures (API_SPEC.md 17.14).
- **Latency figures are for a bench quoter in the core loop**, on a laptop with unpinned threads and, informationally, on a shared GitHub-hosted Linux runner. They are not for the wired process, whose threading differs (review L3, L4). Gating numbers belong on a pinned Linux host (DR-005), which has not been run.
<!-- FIX-ROUND: code items filled at integration -->
- **Some data was unavailable or had no example to reproduce.** No UK DMO golden test: the DMO formulae paper gives the conventional gilt formulae without a numerical worked example (DR-V1G-02). No FINRA Treasury data (needs an account). E5's fitting data could not be downloaded.
- **Not built:** Aeron replication and the quoting lease (DR-P6-06), APA publication, the Part 43 message and the pre-execution credit check (docs/compliance/RTS6_INVENTORY.md).
- **The forward log is not a track record.** Its client flow and fills are synthetic, and the intraday market is held flat at the previous close, so informed clients carry no information and the session's spread capture has no adverse selection in it. Its synthetic income is close to deterministic, so a ratio of mean to standard deviation would be large and mean nothing; only the close mark of the session's book is a real outcome, and it measures no skill (docs/research/PAPER_TRADING_PLAN.md Section 6, review Section 2.5).
- **No independent review.** The code was written by AI sessions and merged by the owner with CI as the only gate (CONTRIBUTING.md). docs/compliance/RTS6_INVENTORY.md describes controls in simulation; it is not a claim of compliance.
- **Cookbook.** All 37 recipes are written. Recipe 9 is a comparison method checked by the golden tests, so `tools/check_cookbook.py` checks 36 of the 37.
- **Ledger flags.** Nine audit rows carry `code_dirty: true`: AUDIT-02 to 04, AUDIT-12 to 14 and AUDIT-22 to 24. Each was recorded at the same commit as the row before it, while the ledger file itself was uncommitted; docs/research/PNL_AUDIT.md explains. The ledger chain has no external anchor (review Section 2.4).

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
rates-strategy/      Desk features, DeskQuoter, desk experiments,   (round 2)
                     forward log (paper trading)
rates-cookbook/      Cookbook recipes as programs, output checked   (P0 onward)
research/            Python package ratesresearch, notebooks, audit (P0 onward)
deploy/              Images, compose file, rehearsal                (P6, round 2)
data/                Sources, presets, pre-registrations, ledger (no raw data)
docs/                Architecture, benchmarks, research, compliance, runbooks, diagrams, site
tools/               Cookbook, notebook, config and inventory checks
```

## Documents

For a reviewer with five minutes: [docs/REVIEWER_SUMMARY.md](docs/REVIEWER_SUMMARY.md), then the review it summarises, [docs/reviews/REVIEW_2026-11-23.md](docs/reviews/REVIEW_2026-11-23.md).

For a newcomer, in reading order:

- [LEARN.md](LEARN.md): the subject, from rates products to low-latency Java and regulation. Each section gives the plain idea, the formal statement and a worked example.
- [COOKBOOK.md](COOKBOOK.md): how-do-I recipes whose output is checked in CI.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): components, the wired process, the event loop, threading and numerical rules.

For a desk reviewer:

- [API_SPEC.md](API_SPEC.md): the pinned contract: units, journal format, SBE schema, clock semantics, every module's interface.
- [docs/BENCHMARKS.md](docs/BENCHMARKS.md): latency method and results.
- [docs/research/PNL_AUDIT.md](docs/research/PNL_AUDIT.md): where the engine loses money, ranked, and the fix packages.
- [docs/research/BAND_RISK.md](docs/research/BAND_RISK.md): daily-P&L volatility, Sharpe ratio and drawdown across the band, a property of the simulator constants.
- [docs/research/DESK_FEATURES.md](docs/research/DESK_FEATURES.md): the desk-feature experiments.
- [docs/research/EDGE_STUDIES.md](docs/research/EDGE_STUDIES.md): the edge studies on public data.
- [docs/research/PAPER_TRADING_PLAN.md](docs/research/PAPER_TRADING_PLAN.md): the forward log (paper trading): data, cadence, ledger, what it can and cannot claim. The log itself is on the `forward-log` branch (`forward/reports/SUMMARY.md`).
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
