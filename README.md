# rates-algo-trading

[![CI](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/rates-algo-trading/actions/workflows/ci.yml)
[![Licence: Apache 2.0](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)

An open Java 25 principal e-trading platform for USD interest rate swaps and Treasuries, with a Python research package, built from public data only. Version 1.2.0 (CHANGELOG section dated 2026-11-24).

**How it was built and checked.** It was built by parallel AI coding sessions directed by one engineer. Verification is CI (nine required checks on Linux and Windows) plus external goldens: QuantLib, published Treasury auctions and published overnight indices. There has been no independent human review. An in-depth review on 2026-11-23 ([docs/reviews/REVIEW_2026-11-23.md](docs/reviews/REVIEW_2026-11-23.md)) found that the P&L figures are fixed by declared simulator constants and that parts of the safety path fail open. The fix round (CHANGELOG.md 1.1.0) changed the safety path, routing, reference data, limits and the research ledger. Release 1.2.0 builds the review's Rounds 3 to 7: a simulator calibrated to public data, pricing v2, strategy v2, a ladder router with a fair benchmark, per-session threads, and a registered experiment family. All of the v2 components are opt-in: **the default desk quotes and hedges exactly as before, no experiment has been run, and no P&L claim is made.** What is open is in STATUS.md and the review's follow-up section. Start with the one-page [reviewer summary](docs/REVIEWER_SUMMARY.md).

It is a dealer desk's stack in miniature, and it is a simulator: every venue is a simulator in the same process, and every P&L figure below comes from simulated client behaviour under declared assumptions. Those figures check that the parts agree with each other; they are not evidence that the strategy would make money in a real market (DECISIONS.md DR-008).

Documentation site: <https://ashjha0.github.io/rates-algo-trading/>.

## Where to start

| You want to | Read |
|---|---|
| judge it in five minutes | [docs/REVIEWER_SUMMARY.md](docs/REVIEWER_SUMMARY.md), then [Limits](#limits) |
| build it and run something | [Quick start](#quick-start) |
| learn the subject | [LEARN.md](LEARN.md) |
| do one specific thing | [COOKBOOK.md](COOKBOOK.md), 39 recipes |
| see how it fits together | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [diagrams](docs/site/pages/diagrams.md) |
| bind to an interface or read a file format | [API_SPEC.md](API_SPEC.md) |
| know why something is the way it is | [DECISIONS.md](DECISIONS.md) |
| operate a process | [docs/runbooks/README.md](docs/runbooks/README.md), [deploy/README.md](deploy/README.md) |
| see what is open | [STATUS.md](STATUS.md) |

The full list is under [Documents](#documents).

## Quick start

Requirements: JDK 25, Maven 3.9 or later, Python 3.12. Run everything from the repository root.

Build the Java modules and run their tests, with the coverage floors, Error Prone and SpotBugs (this is the slow step):

```bash
mvn -B -ntp verify
```

Install and test the Python package:

```bash
python -m pip install -c research/requirements-lock.txt -e "research[dev]"
```

```bash
pytest -q research
```

Run the trading process with its simulated venues, kill it, crash it and restart it, all on loopback (add `--profile v2` for the opt-in v2 desk):

```bash
mvn -B -ntp -DskipTests package -pl rates-app -am
```

```bash
python deploy/rehearsal.py --mode process
```

To run a seeded synthetic day in one program and read its daily report, follow COOKBOOK.md recipe 22.

Build the documentation site as CI does:

```bash
python -m pip install -r docs/site/requirements.txt
```

```bash
mkdocs build --strict
```

Other checks CI runs: `ruff check research`, `ruff format --check research`, `mypy` (in `research/`), `python -m ratesresearch.prereg data/ledger/ledger.jsonl` (ledger chain and looks per data set), `python tools/check_cookbook.py`, `python tools/run_notebooks.py`, `python tools/check_site_current.py`, `python tools/validate_config.py` (needs `jsonschema` and `pyyaml`, versions in `.github/workflows/ops.yml`) and `python tools/lint_dashboards.py`.

## Default and opt-in

The default desk is the recorded one: every recorded figure, recipe and ledger row was made on it.

| Part | Default | Opt-in since 1.2.0 | Selected by |
|---|---|---|---|
| Whole desk | `DeskProfile.defaults()` | `DeskProfile.v2()` | `desk.profile: v2` ([runbook](docs/runbooks/v2-profile.md)) |
| Market | version 1 simulators, one CLOB | simulator v2, two CLOBs and a futures book | `desk.market: simV2` |
| Pricing | `AnalyticsPricing` | `PricingV2` | `desk.pricing: v2` |
| Strategy | `DeskSettings.defaults()` | `DeskSettings.strategyV2()` | `desk.strategy: v2` |
| Router | touch model | ladder model | `desk.router: ladder` |
| Threads and journals | `default`: parked threads, direct journal, FNV-1a hash | `lowLatency`: spinning threads, ring journal, WORD64 hash, curve-input conflation | `runtime.profile: lowLatency` ([runbook](docs/runbooks/low-latency-profile.md)) |
| Experiments | recorded ledger, 32 rows | family harness; R5-FAM-001 is unsigned and has not run | `DeskExperiment` ([runbook](docs/runbooks/registered-experiment.md)) |

Only the two whole desk profiles are run by tests at process level. The 1.1.0 controls that are off by default are listed under [Limits](#limits).

## What it does

One process, `rates-app` (`TradingProcessMain`, API_SPEC.md Section 18), runs a USD rates desk for one instrument group:

1. **Venues.** A D2C RFQ simulator and a D2D CLOB simulator with workup run in process. Our FIX 4.4 gateways connect to them. The CLOB publishes an SBE book feed, and a curve feed carries D2C mids.
2. **Event loop.** Every inbound message goes through the ring-buffer contract into one single-writer event loop. The loop journals every input and output to hash-chained journals.
3. **Pricing.** `AnalyticsPricing` rebuilds one pillar of a SOFR OIS curve per curve input. It gives the quoting engine fair values and the risk engine DV01 ladders. The analytics are checked against QuantLib.
4. **Quoting and risk.** The quoting engine answers RFQs with a hit-rate-optimal spread, inventory skew and client tiering. The package quoter prices curve and butterfly RFQs as one package. The risk engine enforces DV01, key-rate, basis and swap-spread limits. A kill switch reaches the quoting engine, the package quoter, the CLOB quoter and every hedger. Since 1.1.0 the kill also withdraws from the gateway side without the loop, and the control endpoint answers 200 only when the venues confirmed the withdrawal (API_SPEC.md 18.9).
5. **Hedging and routing.** On a 250 ms timer the hedger plans hedges of residual DV01. A smart order router per hedge Treasury slices each hedge across the CLOB and records every decision.
6. **Post-trade.** P6's position keeper reads the journals after the run and reconciles them with both venues' trade registers.
7. **Back-testing.** P5's back-tester replays the journals through the same desk code to the same outbound hash. It writes the daily report.
8. **Operations.** `rates-ops` serves health, readiness, metrics and the kill switch over HTTP.

That is the recorded desk, `DeskProfile.defaults()`, and the default. Since 1.2.0 a second desk profile, `DeskProfile.v2()` (config `desk.profile: v2`), runs every component in its second version: the calibrated simulator v2 market with one D2C venue, two Treasury CLOBs showing shares of one book and a futures book; pricing v2; strategy v2; and the ladder router on each hedge instrument. Each component can also be switched alone. No recorded figure was made with it (docs/research/DESK_V2_WIRING.md, DR-V12-D01).

Next to the process:

- **Back-testing with pre-registration.** The back-tester runs only pre-registered experiments. Holdouts may be opened once. Every run is appended, whatever its result, to `data/ledger/ledger.jsonl`, a hash-chained ledger with 32 rows over 13 data sets; three sets were read more than once (11, 9 and 2 looks), each row recorded as look 1. Since 1.1.0 a new run is refused unless its pre-registration declares the earlier looks at its data (`prior_looks`, DR-RV-C02).
- **Desk features.** `rates-strategy` holds internalisation and netting, hedge slicing (depth-sized children, passive first with escalation; the back-tested desk default since V1H-001), axes, quote sizing, client tiering, hedge timing, a funding penalty and coupon carry, together with a desk P&L waterfall. Each feature sits behind an interface whose default is today's engine.
- **Forward log (paper trading).** Every Treasury business day a scheduled job fetches the real close (US Treasury par curve, NY Fed SOFR, SOFR OIS swap marks from DTCC public prints), runs the desk default for one session of synthetic client RFQs priced off the previous close, marks the resulting book at the real close, and commits the next day's decision to a hash-chained ledger on the `forward-log` branch before that day's rates exist. Rates and marks are real; clients and fills are synthetic and labelled so. The summary reports the real close mark alone, with its mean and standard error, and prints no ratio of mean to standard deviation. docs/research/PAPER_TRADING_PLAN.md.
- **Python research package** (`research/ratesresearch`). It covers statistics mirrored line for line with the Java ones, hit-rate, flow and markout models, the QuantLib golden generator, eleven executed notebooks and six edge studies on public data.

## Measured results

Rows here are either checks against something outside the project or research findings. Each names its source and date. Plumbing self-checks are in the next table.

| What | Result | Kind | Source, date |
|---|---|---|---|
| Analytics against QuantLib 1.44, 225 instruments in the golden file (224 when these figures were measured) | swap PV within 1.6e-15 of notional, par rates within 3.5e-16, bond yields within 7.3e-16 | external golden | `AnalyticsGoldenTest`, API_SPEC.md 7.2, 2026-10-31 |
| Published Treasury auctions of 2024 | all 84 notes and bonds and 332 bills reproduced to the published digits | external golden | `PublishedExamplesTest`, 2026-10-31 |
| Published overnight indices of 2024 | compounded legs reproduce the published SOFR, EUR STR and SONIA indices to the last digit | external golden | `CompoundedIndexHoldoutTest`, STATUS.md P1 gate, 2026-10-31 |
| Edge studies on public data, E1 to E6 | two supported after their holdouts (E3 release volatility and intensity, E4 trade size and clustering); three not supported; one inconclusive because its data could not be downloaded | finding, public data | ledger seq 2 to 7, docs/research/EDGE_STUDIES.md, 2026-10-31 |
| Production desk at default settings, 20 fifteen-minute sessions (simulated) | loses -114k, -109k and -125k USD per session at low, central and high (central 95% interval -168k to -41k). The sign follows from declared constants (see Limits); it is not a market finding. Supersedes AUDIT-11 | simulator property | AUDIT-21, ledger seq 25, docs/research/PNL_AUDIT.md, 2026-11-07 |
| Half-spread cap 0.50 bp with 4x skew, chosen on those 20 sessions (simulated) | failed its one look on 5 unopened sessions: difference +96k, +36k (-30k to +101k) and -10k USD per session, central wins down from 15 to 6 a session. Defaults unchanged | finding, simulator | AUDIT-25, AUDIT-26, ledger seq 29 and 30, 2026-11-07 |
| Simulator calibration to public data (US Treasury par curves, DTCC SOFR swap prints) | three rate factors explain 98.9% of daily par-yield variance; intraday swap volatility 0.123 bp per root minute pooled, where the old simulator declared 0.194 per instrument; competitor median half-spread 0.183 bp (bounded, 0.123 to 0.272). The old simulator's adverse-selection constants (0.20 bp per swap trade) are outside what the data supports: the calibrated average adverse move is 0.059 bp (range 0 to 0.20), set by competitor zero profit because the data cannot identify it directly. Trade side is inferred with about 37% error | finding, public data | SIM3-CAL-001 (pre-analysis note), docs/research/SIM_V2_CALIBRATION.md, `data/calibration/sim_v2_params.json`, 2026-11-23 |
| Router against real baselines (simulator) | ties a sweep on arrival, beats a scheduled single-venue order and TWAP by 0.5 ticks, loses to passive-with-deadline by 0.4 ticks, risk-adjusted (mean + 0.5 sd; router 0.8 ticks, passive-with-deadline 0.4). The check declared before the run (no worse than any baseline by more than 0.25 ticks) failed against passive-with-deadline and is recorded as failed. The ladder router cannot be told apart from the touch router here. One run, 300 parents per strategy, in the router's own simulator; not a market finding. Replaces the 2.9 bp row | finding, simulator | `RouterBenchmarkTest`, docs/research/ROUTER_BENCHMARK.md, DR-R6-R05, 2026-11-23 |

### Self-checks of the plumbing

These show that the parts agree with each other and with the project's own models. They are not evidence about markets.

| What | Result | Source, date |
|---|---|---|
| Test suite, 1.2.0 | `mvn -o -B -ntp verify` on the merged branch at 27b2677, alone on the machine: 1,478 Java tests in 21 modules, no failures, none skipped. Python: 183 passed, 2 skipped | Laptop, Windows 11, Temurin 25.0.5, 2026-11-24; CI repeats both on every pull request |
| Test suite, 1.1.0 | 887 Java tests in 21 modules and 121 Python tests (1 skipped), no failures, run locally on 2026-11-23 (`mvn -o -B -ntp verify`, `pytest -q research`); `mypy` is checked in CI only | the release build of the fix round |
| Wired process, ten simulated minutes, seed 7 (default profile) | 36 RFQs, 27 quoted, 16 filled, 210 hedge parents, 213 hedge fills, 229 trades reconciled with 0 breaks, nothing working after the kill switch, replay hash -4423185950475908182 equal; unchanged by 1.2.0 | `SmokeTest`, DR-INT-07, 2026-10-31, re-run 2026-11-23 |
| Wired v2 profile, 30 simulated minutes, one seed (structural counts, no P&L read) | 20 RFQs, 20 quoted (6 swaps, 14 Treasuries), 9 won, 28 hedge parents, 63 fills (CLOB 1 38, CLOB 2 25, futures 0), 72 of 72 trades reconciled, nothing working on any venue after the kill, replay equal. The hedge planner never chose the TY future at its declared costs; the futures path is exercised by a feed-outage test | `SmokeV2Test`, docs/research/DESK_V2_WIRING.md Section 5, 2026-11-23 |
| Risk limits under load | 1,000,000 simulated RFQs with 0 limit violations at the sink; 10,000 chaos scenarios with 0 invariant failures | `MillionRfqTest`, `ChaosTest`, STATUS.md P3, 2026-10-31 |
| Mutation testing (PIT kill rate) | rates-posttrade 97.1% (639 of 658), rates-risk 96.2% (603 of 627), rates-core 93.6% (437 of 467), rates-common 93.6% (117 of 125), rates-api 90.5% (190 of 210), rates-ops 88.7% (306 of 345). Measured once, on 2026-11-23; reports not committed; rates-api, core, common, ops and posttrade are not in the pom's `pit` modules, so a re-run sets `pit.skip=false` on the command line | Round 7 assurance run, 2026-11-23 |
| Wired desk latency, quiet laptop | The real trading process over loopback FIX, scheduled send to quote written, no run discarded, 1,000 RFQ/s, 20 s warm-up, 60 s measured. `lowLatency` profile, direct journal: plain TCP p50 64 us, p99 164 us, p99.9 347 us, max 6.5 ms; TLS p50 74 us, p99 207 us, p99.9 455 us, max 7.5 ms. Ring journal: p50 64 us (plain) and 78 us (TLS), p99 0.9 and 2.4 ms. Default profile p50 13.4 to 16.6 ms, p99 about 32 ms: its idle threads park, and a park on Windows lasts a timer period. The desk's own work: handler 7 to 8 us at p50, journal append 0.2 to 0.7 us, encode 1.5 to 2.3 us, finish and socket write 22 to 37 us. One unpinned Windows laptop, no network card, no real venue; not a production figure | `WiredDeskBenchmark`, docs/BENCHMARKS.md, commit 27b2677, 2026-11-24 |
| First pre-registered experiment, P5-001 | not supported; recorded as such | ledger seq 1, 2026-10-31 |
| Desk features, R2B-001 to R2B-007 (simulated) | internalisation supported at every band point and adopted as the desk default; the other six not supported | ledger seq 12 to 18, docs/research/DESK_FEATURES.md, 2026-10-31 |
| Hedge slicing against one market order, V1H-001 (simulated) | supported at every band point and adopted as the back-tested desk default. In this simulator our orders have no impact and the touch refills at once, so the result follows from the fill model (review Section 2.3). V1H-002 not supported, opt-in | ledger seq 23 and 24, DR-V1H-01 to 03, 2026-11-01 |
| Hedge latency sweep and carry-time limit, V1C-001 and V1C-002 (simulated) | hedge order latency up to 1 s barely moves P&L; median time from client fill to hedge is 20 minutes to over an hour; neither carry limit supported, desk default unchanged | ledger seq 31 and 32, docs/research/DESK_FEATURES.md, 2026-11-07 |

The daily Sharpe ratio and drawdown table that used to sit here is in [docs/research/BAND_RISK.md](docs/research/BAND_RISK.md), with AUDIT-21 in place of the superseded AUDIT-11. Its figures are a property of the declared simulator constants. `ReportCli` and `ExperimentCli` still write that table for every run (`band_risk.json`, `band_risk.md`). The router's 2.9 bp regression check and the event-loop figures for the constant-arithmetic bench quoter that used to sit in the table above are kept, with their caveats, in API_SPEC.md 15.6 and docs/BENCHMARKS.md as history.

**Registered experiment.** The Round 5 family `research/prereg/R5-FAM-001.yaml` (the v2 strategy against the 1.x strategy on one common footing, six leave-one-out ablations and cover pricing alone, on 2,000 shared simulated sessions, Benjamini-Hochberg across eight hypotheses; the desk as built in 1.1.0 is run as a description only) is unsigned at the 1.2.0 release and has not been run. No row of the ledger comes from simulator v2.

## Limits

Read these before reading any number above.

- **Simulator only.** Every venue is a simulator in the same process. Client behaviour comes from declared models: a hit-rate slope, competitor counts and the share of informed flow. Each result is reported at three points of that band (DR-008). Simulator v2 (1.2.0, opt-in) takes its parameters from public data where the data identifies them and states the rest as assumptions with bands; it is still a simulator.
- **The desk's P&L sign is fixed by declared constants.** The half-spread is clamped to 0.02 to 0.08 bp (`rates-app/src/main/java/io/ratesalgo/app/loop/DeskSettings.java:393-394`). In the D2C simulator 20% of RFQs are informed and move the mid by 4 competitor half-spreads of 0.25 bp after 30 s (`rates-venues/src/main/resources/io/ratesalgo/venues/presets.json`), an expected adverse cost of 0.2 x 4 x 0.25 = 0.20 bp per swap trade. A desk that earns at most 0.08 bp loses; AUDIT-21 measures that arithmetic (review Section 2.1). This is still true in 1.2.0: the default desk on the version 1 simulator, both still the defaults, loses by construction. The calibration of 1.2.0 puts those constants outside what the public data supports (docs/research/SIM_V2_CALIBRATION.md), so the old simulator's figures are superseded for new work (docs/research/PNL_AUDIT.md).
- **Hedging cannot reduce risk in the session simulator.** Each instrument's mid in the D2C simulator is an independent random walk, rounded to the tick (`D2cRfqSimulator.java:196-211`), so a Treasury hedge does not offset a swap position there. The back-tester's own synthetic day (`SyntheticDay`) does have a common level factor. Simulator v2 (opt-in) moves every instrument on three fitted factors, so a hedge reduces variance there.
- **The wired process uses little of `rates-analytics`.** Swap fair value comes from a curve whose pillars are the quoted swaps themselves, and bond fair value is the book mid (`rates-app/src/main/java/io/ratesalgo/app/pricing/AnalyticsPricing.java`). Turns, convexity, futures, fitted Treasury curves and repo are not used by the default process (review A1, A2). Pricing v2 (opt-in, `PricingBindings.Version.V2`) uses them; its limits are listed below.
- **Bond DV01 is at zero spread to the OIS curve by default.** `AnalyticsPricing.Options.bondRiskAtMarket` takes it at the market z-spread instead, but it is off until a pre-registered run (DR-RV-P04). Pricing v2 always takes it at market and adds a spread ladder. On the fix-round run of `PricingRobustnessTest`, which prints and does not assert these figures, the default overstated 30-year DV01 by about 21% and 10-year by about 6%.
- **Five curve pillars feed eight risk buckets.** The 1-year bucket is exactly empty; the 3-year and 7-year buckets hold only the spill of the 2-year and 5-year pillars (asserted below 0.2% of gross; 0.10% and 0.08% on the fix-round run); the 20-year bucket is fed by the 12-year pillar (`PricingRobustnessTest`, review A4). The simulator assigns starting rates in file order (`rates-venues/.../sim/Instruments.java:37-50`), so the 12-year swap starts at 3.80%, above the 30-year at 3.75%. Under pricing v2 every risk tenor is a pillar.
- **Some reference data is unchecked.** The deliverable windows of TU, FV, TWE, US and UB futures are as recalled, not re-read from the exchange (the pages refused an automated fetch); the FV, TY and US ticks were not re-read this round. TY, TN, Z3N and the TU tick were checked against CBOT rule text (`TreasuryFutureContract.java`, `RefDataTest`). Two fixture bonds have issue and dated date 2026-08-15, a Saturday (`data/refdata/v1/bonds.csv`).
- **One venue, one client tier, wide limits (default profile).** The wired router of the default desk is given one venue, the CLOB, and there is one client tier with firm quotes to 1bn. The v2 profile has two Treasury CLOBs and a futures book, and three client tiers. The default risk limits are the back-test preset, sized so that the smoke day never breaches them; they are not controls. The default sets no price collar and no fat-finger quantity; the fat-finger price check (10% from fair) is on (`RiskLimits`). A desk-sized preset exists (`DeskSettings.deskSized()`: daily loss 180k, RFQ DV01 40k, client DV01 200k, net 85k, pillar 60k, gross 340k, collar 10 bp; DR-RV-Q05) but is not the default and not yet recommended: on the seeded day it ended with net DV01 of about -15.7k against about -64 under the default (`DeskSettingsTest`, printed, not asserted), and the difference is not explained.
- **No breach message for the per-RFQ and per-client DV01 limits.** The schema has no `LimitKind` for them; refusals return -5 or -6 and are counted (API_SPEC.md 13.2).
- **The default desk lost money on average in the simulator, with large swings.** It lost at every band point over the 20 sessions of AUDIT-21 and made money on the 5 sessions of AUDIT-26, where every interval includes zero. Inventory marking is the largest loss line. A setting that widened the cap failed its holdout, and those 5 sessions are now spent.
- **Audit-fix controls ship off by default.** None of these is on in the wired process:
  - package A1: the hit-rate learning and cover averaging change engine output only once the engine is fed outcomes and cover gaps;
  - package C: the router's adverse-fill and message costs are on only in `RouterPresets.globalWithControls`;
  - package B: the CLOB inventory skew is off until called;
  - fix round 1.1.0: cover learning, widening after the clamp with a realised-volatility estimate, fractional-lot hedge carry, the fractional fair-move pull, refusal of mismatched Dones and bond risk at market (CHANGELOG.md 1.1.0);
  - 1.2.0: the v2 profile and every component in it, the `lowLatency` runtime profile, the WORD64 journal hash and curve-input conflation (CHANGELOG.md 1.2.0).
- **One currency, one loop.** In the default profile the routers and the hedger trade Treasuries on the CLOB only (DR-R2W-08); the v2 profile adds a futures book for TY. The CLOB quoter is built but not enabled in the process, so its cancel-reject handling has no caller there. The SEF simulator is not connected.
- **Post-trade reads the journals after the run** rather than tailing them live. Marks, carry, calendars and the venue status port run on fixtures (API_SPEC.md 17.14).
- **Latency figures are from one laptop.** The wired-desk benchmark measures the real process over loopback FIX with nothing else heavy running (docs/BENCHMARKS.md), on Windows, unpinned, on the balanced power plan. The default profile's milliseconds are the Windows timer under parked threads. There is no network card, no kernel bypass, no real venue and no pinned Linux host in any figure; the v2 desk profile is not benchmarked.
- **1.2.0 components are opt-in, and the default is unchanged.** Simulator v2, pricing v2, strategy v2 and the ladder router are selected by the v2 profile or one at a time. They stay off until a registered run says otherwise, because switching them changes every recorded figure (DR-V12-D01, DR-R5-01).
- **No experiment on simulator v2 has been run.** The family registration R5-FAM-001 is unsigned at the 1.2.0 release. Its ablations are read at the central parameters only; its two sensitivity rows are both on the side that makes market making harder, because the two high points are refused by the simulator's own consistency checks; the rest of the design's 15-row band is not tested. The pilot that sized N gave a session sd of about 214k USD from 8 sessions, an imprecise figure.
- **Simulator v2 assumes what the data cannot identify.** The level of adverse selection (set by a zero-profit condition from an assumed competitor cost), the time profile of post-trade drift, the split between informed fraction and move size, size and tenor dependence, the swap-spread factor (an upper figure only), the futures basis, and order-book impact and resilience are assumptions with bands (docs/research/SIM_V2_CALIBRATION.md Section 11). One assumption, the informed fraction, was changed from 0.2 to 0.5 after the validation read: it is a sensitivity, not a calibrated value (SIM_V2_CALIBRATION.md Section 10). Treasury competitors do not quite reach zero profit. Deviations from the design are listed as D1 to D18 in SIM_V2_DESIGN.md Section 14.
- **Pricing v2 limits.** With four Treasuries the on-the-run premium is not identified and two bad books at once are not caught. The spline's risk between pillars is not local (a 12-year swap shows on the 7, 10 and 20-year buckets). Hull-White parameters are assumptions. A reprice is several hundred microseconds on the loop thread, and an RFQ that arrives during one waits for it. Bond roll-down is the curve yield difference. On the v2 market the curve's front end follows the two-year node and the overnight rate is constant in a session (docs/research/PRICING_V2.md Section 13).
- **Spread risk is visible, not hedged.** The v2 desk reports a swap-spread ladder; nothing limits, quotes or hedges on it (DR-V12-D11). TY risk is the model's declared sensitivity, not a cheapest-to-deliver calculation. At declared costs the hedge planner never chose the TY future on a normal day.
- **A threaded live v2 day does not repeat.** Replay of any journal is exact and a stepped day repeats frame for frame, but a threaded v2 day is not repeatable run to run: two runs of one seed gave 43,462 and 43,104 inbound frames, each reconciling with 0 breaks and replaying (DR-V12-P03). A restarted v2 market forgets the desk's earlier impact. Per-component selections are built and started but only the two whole profiles are run by tests at process level.
- **Strategy v2 limits.** Done-away, reserve and tier fill probabilities are assumed, not learned; the internalisation budget and time stop are declared, not derived; futures hedge only when a futures book is wired (docs/research/STRATEGY_V2.md).
- **Transport security is TLS 1.3 inside the JVM.** Since 1.1.0 FIX runs over TLS with Logon credentials, and the control endpoint over HTTPS; plain FIX and plain HTTP are for loopback only (`TcpInitiator`, `OpsHttpServer`, API_SPEC.md 17.11, 17.12). The JDK's TLS engine allocates about 1.3 KB per message sent and 1.4 KB per message received; the plain path allocates nothing (`TlsAllocationTest`, DR-RV-X06). The TLS KeyUpdate path is untested. The process-mode rehearsal runs over TLS; the Docker rehearsal was not run for this change.
- **A halt is final and bounded.** After a kill or a supervisor halt the process does not quote again until it is restarted. Each venue session's own record of live business holds 8,192 quotes and 8,192 orders; beyond that a halt logs out (cancel-on-disconnect) instead of confirming each cancel (API_SPEC.md 18.9).
- **Restart recovery has edges.** A process started on existing journals cuts a torn tail, rebuilds its state by replay through the live loop and resumes the FIX sequence numbers (`JournalRecovery`, `LoopRecovery`, `RestartRecoveryTest`, DR-RV-X01 to X03). It refuses to start, and truncates nothing, when the outbound journal runs past what the inbound journal explains or a journal ends in a LoopError; an operator then moves the journals aside (docs/runbooks/restart.md). The route journal is silent during replay, so a routing decision made at the crash instant can be missing from it (`TradingProcess.RouteSink`). The sequence store survives a process crash, not a power loss (`MappedSequenceStore`). Reconciliation with resting orders is tested at `DeskGroup` level only (`DeskGroupTest`). The simulators restart with the process and always keep a file store under `<journalDir>/venues`.
- **The book feed has no heartbeat.** A silent feed is detected only as 10 s without a book update, and that excludes the CLOB from routing without pulling quotes. The feed's sequence numbers are per venue and checked per instrument group, so a second group on the same venue would see false gaps.
- **Router limits.** A child the venue never answers ends unresolved and needs an operator or a reconciliation to settle it. In the default touch model aggressive children still cross up to 8 ticks through the touch; the ladder model (opt-in) limits each child at the last level it needs, with a 2-tick collar (review S6). A busted router fill is reversed in risk but does not reduce the parent's filled quantity.
- **Some data was unavailable or had no example to reproduce.** No UK DMO golden test: the DMO formulae paper gives the conventional gilt formulae without a numerical worked example (DR-V1G-02). No FINRA Treasury data (needs an account). E5's fitting data could not be downloaded.
- **Not built:** Aeron replication and the quoting lease (DR-P6-06), APA publication, the Part 43 message and the pre-execution credit check (docs/compliance/RTS6_INVENTORY.md).
- **The forward log is not a track record.** Its client flow and fills are synthetic, and the intraday market is held flat at the previous close, so informed clients carry no information and the session's spread capture has no adverse selection in it. Its synthetic income is close to deterministic, so a ratio of mean to standard deviation would be large and mean nothing; the summary prints none. Only the close mark of the session's book is a real outcome, and it measures no skill (docs/research/PAPER_TRADING_PLAN.md Section 6, review Section 2.5, DR-RV-C01).
- **No independent review.** The code was written by AI sessions and merged by the owner with CI as the only gate (CONTRIBUTING.md). docs/compliance/RTS6_INVENTORY.md describes controls in simulation; it is not a claim of compliance.
- **Cookbook.** 39 recipes are written. Recipe 9 is a comparison method checked by the golden tests; the other 38 are checked by `tools/check_cookbook.py` against their source and recorded output; recipes 38 and 39 (1.2.0) are shell commands, which it runs.
- **Ledger flags.** Nine audit rows carry `code_dirty: true`: AUDIT-02 to 04, AUDIT-12 to 14 and AUDIT-22 to 24. Each was recorded at the same commit as the row before it, while the ledger file itself was uncommitted; docs/research/PNL_AUDIT.md explains. The ledger chain has no external anchor (review Section 2.4). Three data sets were read 11, 9 and 2 times under different pre-registration ids, and their rows say look 1; `python -m ratesresearch.prereg data/ledger/ledger.jsonl` prints the looks per data set.

## Repository layout

```
rates-common/        Clock, ManualClock, TimerWheel                 (P0)
rates-sim/           Simulator v2 market model, shared by venues and back-test (round 3)
rates-messages/      SBE schema v1 and generated codecs             (P0)
rates-journal/       Journal writer, reader, segments, replayer     (P0, P2, round 2)
rates-analytics/     Curves, swaps, bonds, futures, DV01 ladders    (P1)
rates-core/          Event loop, ring-buffer contract, replay       (P2)
rates-bench/         Latency harness, JMH, wired-desk benchmark     (P2, round 6)
rates-refdata/       Securities master, calendars, parties          (P2)
rates-fix/           FIX 4.4 codec, session and venue gateway       (P2)
rates-venues/        D2C, CLOB and SEF simulators, SBE book feed    (P2)
rates-api/           Interfaces P3 binds to, with stubs             (P3)
rates-risk/          Risk engine, limits, kill switch               (P3)
rates-hedge/         Cost-aware hedger                              (P3)
rates-quoting/       RFQ, package and CLOB quoting, Desk            (P3)
rates-router/        Smart order router                             (P4)
rates-backtest/      Back-tester, pre-registration, daily report,   (P5, 1.2.0)
                     experiment harness
rates-posttrade/     Positions, records, marks, reconciliation      (P6)
rates-ops/           Health, metrics, config, stress harness        (P6)
rates-app/           The trading process: desk profiles, bindings   (round 2, 1.2.0)
                     and assembly
rates-strategy/      Desk features, DeskQuoter, desk experiments,   (round 2)
                     forward log (paper trading)
rates-cookbook/      Cookbook recipes as programs, output checked   (P0 onward)
research/            Python package ratesresearch, notebooks, audit (P0 onward)
deploy/              Images, compose file, rehearsal                (P6, round 2)
config/              SpotBugs exclusions, the forward log desk settings
data/                Sources, presets, calibration, goldens, pre-registrations, ledger (no raw data)
docs/                Architecture, benchmarks, research, compliance, runbooks, diagrams, site
tools/               Cookbook, notebook, config and inventory checks, journal query, simulator v2 runs
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
- Release 1.2.0, the v2 components: [SIM_V2_CALIBRATION.md](docs/research/SIM_V2_CALIBRATION.md) (what the public data supports), [SIM_V2_DESIGN.md](docs/research/SIM_V2_DESIGN.md) (the simulator specification), [PRICING_V2.md](docs/research/PRICING_V2.md), [STRATEGY_V2.md](docs/research/STRATEGY_V2.md), [ROUTER_BENCHMARK.md](docs/research/ROUTER_BENCHMARK.md), [DESK_V2_WIRING.md](docs/research/DESK_V2_WIRING.md) (one desk, one process, and what determinism is promised).
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
