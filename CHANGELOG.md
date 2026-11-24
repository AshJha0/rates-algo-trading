# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. Before 1.0, minor versions may change contracts, and each such change has a decision record.

## [Unreleased]

- Dependencies, one batch of three Dependabot groups: Agrona 2.7.1, SBE 1.42.0, Error Prone 2.53.0, SnakeYAML 2.7, SLF4J 2.0.20 and Maven plugins; GitHub Actions (checkout, setup-java, setup-python, upload-artifact, Pages) at new pinned commits; mkdocs-material 9.8.2 and pymdown-extensions 13.1.2. No source change.
- `deploy/rehearsal.py` refuses a target directory holding jars of several versions; it started the oldest one.
- Test: `RestartRecoveryTest` waits for the prepared next segment before it takes the crash image (a race seen once on the Linux runner). `VenueBindingTest` allows the simulator's own RFQs while the sessions log on again.
- `RingJournal.backlogHighWater` also counts what queued behind a batch that waited; sampled only before a batch, it could miss the stall it is there to record.

## [1.2.0] - 2026-11-24

Rounds 3 to 7 of the plan in docs/reviews/REVIEW_2026-11-23.md Section 9, and their integration into one desk and one process. Review finding ids are in brackets.

Read this first:

- **Nothing in 1.2.0 changes what the default desk quotes or how it hedges.** `DeskProfile.defaults()` builds the recorded desk exactly as 1.1.0 did: `SmokeTest` prints 36 RFQs, 27 quotes, 16 fills, 210 hedge parents, 213 hedge fills and replay hash -4423185950475908182, as before.
- **Every v2 component is opt-in.** Simulator v2, pricing v2, strategy v2 and the ladder router are selected by a desk profile (`DeskProfile.v2()`, config `desk.profile: v2`) or one at a time. No default changes until a registered experiment says it should (DR-V12-D01).
- **No experiment has been run.** The family registration R5-FAM-001 is unsigned and has not been run. The pilot measured timings and one session sd; no P&L of any arm was looked at. The ledger still has 32 rows.
- **No P&L claim is made.** The router benchmark and every count below are simulator results under declared assumptions (DR-008).

Release build: `mvn -o -B -ntp verify` on the merged branch at dc132a3 (later commits change documentation only): 1,484 Java tests in 21 modules, no failures, none skipped. Python: 183 passed, 2 skipped.

### Simulator (Round 3)

- New module `rates-sim`: one market model shared by the venue simulators and the back-test (`MarketModel`, `RfqFlow`, `BookModel`, `SessionSeeds`, a strict parameter loader, `sim_v2_structure.json`). Rates move on three fitted factors; the mid a venue publishes is a rounded reading of an unrounded latent mid; informed clients move the level factor; competitors quote at zero expected profit; order books have other takers, impact and resilience (2.1, 2.2, S7, DR-R3-01, DR-R3-02).
- Calibration from public data: docs/research/SIM_V2_CALIBRATION.md, parameters `data/calibration/sim_v2_params.json`, pre-analysis note `research/prereg/SIM3-CAL-001.yaml`. Three factors explain 98.9 % of daily par-yield variance; intraday swap volatility is 0.123 bp per root minute pooled; competitor median half-spread 0.183 bp (bounded, 0.123 to 0.272); average adverse move 0.059 bp, set by competitor zero profit. The old adverse-selection constants are outside what the data supports. What the data does not identify is listed with an assumption and a band.
- Bindings: back-test `SimDay`, `SyntheticDay.generateSimV2`, `BacktestConfig.forSimV2`, `PairedSessions`; venues `SimV2Session`, `Venues.simV2` and the `sim_v2` presets. Acceptance tests A1 to A12 (SIM_V2_DESIGN.md Section 11). Deviations from the design, D1 to D18, are in its Section 14.
- `SplitMix64` moved to `io.ratesalgo.common.random` (was `io.ratesalgo.backtest.util`); same algorithm, same outputs. Callers must change the import.
- Own impact is a law of the total traded volume, not of each slice (DR-R3-04). Treasury venues show shares of one book (DR-R3-05). Workup is off under simulator v2 (DR-R3-06).

### Pricing (Round 4)

- Pricing v2, selectable (`PricingBindings.Version.V2`): SOFR curve from the overnight rate, FOMC-dated steps from SR1, SR3 with in-arrears Hull-White convexity and swaps at the eight risk tenors (`SofrCurve`); fair value of a quoted swap from a curve built without it plus a half-life residual (`SofrLeaveOneOut`, `FairValueSmoother`); a fitted Treasury spread curve with rich/cheap (`TreasurySpreadCurve`); bond risk at the market z-spread and a second ladder for spread risk (`SpreadSensitivityProvider`, `SpreadRiskBook`); carry and roll-down (`CarrySource`); business date from the clock with a 17:00 New York cut (`BusinessDate`) (A1, A2, A3, A4, A7, DR-R4-01 to DR-R4-08). docs/research/PRICING_V2.md.
- Interpolation chosen on measurement: step then tension spline, because monotone convex gave ladders 4.3 % of DV01 away from a bump (DR-R4-02).
- `data/golden/pricing_v2_golden.json`: QuantLib 1.44 prices every v2 instrument and SciPy solves the curves (A10). `analytics_golden.json` regenerates unchanged.
- FOMC dates checked against the Board's calendar (2027's last decision day is 2027-11-17); SR1 and SR3 terms checked against CME Chapters 461 and 460 as filed with the CFTC.
- `CurveBuilder` tries Newton before Levenberg-Marquardt, with the residual check in rate units (DR-R4-09). `DiscountCurve` keeps weight rows under the spline schemes; `OisSwapPricer` prices a telescoping period as one ratio. Version 1 results are bit for bit unchanged.
- Pricing v2 state is a function of the journaled inputs: no clock is read at construction, and the first timed call fixes the business date (DR-V12-P05). `PricingBinding.setInput` records an input and `repriceIfDirty` applies all recorded inputs with one solve; `advanceTo` applies a held round whole (DR-V12-P06).

### Strategy (Round 5)

- Strategy v2 behind switches, all off by default (`DeskSettings.StrategySwitches`; `DeskSettings.strategyV2()` turns all on): break-even, cover-relative pricing (`CoverModel`, Q1, Q3); one inventory reservation price capped at the hedge cost (Q4); fitted factor covariance and a factor hedge planner (`FactorModel`, `FactorHedgePlanner`, Q2); per-instrument tick hedge cost (`TickHedgeCostModel`, `VenueTicks`, Q5); internalisation budget with a time stop and passive-first hedges (`Hedger.Config.internalise`, `passiveFirst`); persisted multi-horizon client markouts with tier priors (`MarkoutStore`, Q6); time to live by volatility, pull at half the quoted distance and tick-correct prices (Q7, Q8). DR-R5-01 to DR-R5-06. docs/research/STRATEGY_V2.md.
- Arithmetic from the calibration, not a result: at the 10-year the average client's break-even half-spread is 0.107 bp against an expected winning half-spread of 0.139 bp; at the 2-year the break-even is 0.162 bp on its 1/256 tick.

### Router (Round 6)

- Ladder model, opt-in (`RouterPresets.ladder()`, `ExecutionModel.defaults()`): a consolidated, fee-adjusted sweep across price levels (`LadderAllocator`), queue and cancellation rates learned from trade prints and book decrements (`FillCalibration`), a venue scorecard with throttles and outage exclusion (`VenueScorecard`), gamma gaps in child timing, a 2-tick collar, and other strategies' resting orders registered so the router neither crosses nor counts them (`registerOwnOrder`) (S6, S10, S13, DR-R6-R01 to DR-R6-R04). `VenueCapabilities`; `BookView.levelPrice`; `ExecutionListener.onTrade`. The touch model stays the default and what the ledger's runs used.
- SBE: template 4 `TradePrint`, template 83 `RouteDecisionLadder`, template 84 `RouteDecisionModel`, `VenueExclusion` values 7 `Throttled` and 8 `Outage`. Additive; schema version unchanged (DR-R6-R06).
- Fair router benchmark (5.1, DR-R6-R05): docs/research/ROUTER_BENCHMARK.md, scenario fixed before the run, run once, in the router's own simulator. Risk-adjusted cost (mean + 0.5 sd), ticks: router 0.8; sweep on arrival 0.6; schedule on the best venue 1.2; TWAP 1.2; passive with a deadline 0.4. The router ties the sweep, beats the schedule and TWAP by 0.5 ticks [0.3, 0.7], and loses to passive-with-deadline by 0.4 ticks [0.2, 0.5]. The check declared before the run failed against passive-with-deadline and is recorded as failed. The ladder router cannot be told apart from the touch router here. This replaces the 2.94 bp row.

### Latency and journal (Round 6)

- One thread per FIX session and per feed, each with its own idle strategy and CPU, on the default profile too; defaults park 200 us as before. Core pinning through the foreign function API on Linux (`CoreAffinity`) (L3, DR-R6-L01, DR-R6-L02).
- `runtime.profile` (`default` or `lowLatency`) and `runtime.gatewayIdleStrategy`, `gatewayCpu`, `journalMode`, `journalHash`, `journalIdleStrategy`, `lockJournalPages`, `conflateCurveInputs`; `deploy/config/trading-lowlatency.yaml`. The default profile is unchanged.
- Journal: chain hash named in the segment header. Format version 1 is FNV-1a, byte for byte as before, and stays the default; format version 2 names WORD64 at header offset 20 and hashes eight bytes a step. CRC32C was rejected because it keeps 32 bits (L8, DR-R6-L03). Ring journal: a full-ring wait and the segment preparer's retire wait fail after a stall limit instead of spinning for ever; the backlog high-water mark is reported; DIRECT stays the default; a restart works from a ring-mode crash image (L9, DR-R6-L04). Optional mlock of segments on Linux.
- Curve-input conflation on the loop, opt-in (L10, DR-R6-L05). The FIX writer lends its buffer to the transport, so a plain send copies no body (L11, DR-R6-L08). Allocation tests also run with escape analysis off (L13, DR-R6-L07). The Dockerfile's JVM flags are justified one by one; ZGC kept (DR-R6-L06).
- Wired-desk benchmark (`WiredDeskBenchmark`, `WiredDeskSuite`): the real trading process over loopback FIX, scheduled send to quote written, no run discarded (L4). Results in docs/BENCHMARKS.md, from a loaded and a quiet run on one Windows laptop. JMH: `ChainHashBenchmark`, `FixEncodeBenchmark`, `PricingV2RepriceBenchmark`.

### Process and desk profiles

- `DeskProfile` (`defaults()`, `v2()`, `withPricing`, `withRouter`, `withMarket`, `withSettings`): each component can be switched alone. The v2 desk trades on one D2C venue, two Treasury CLOBs that show shares of one book, and a futures book, with session, feed, sequence, book age and reconciliation kept per venue (DR-V12-D01, D02). docs/research/DESK_V2_WIRING.md.
- `UsdUniverse.simV2()`: swaps at the eight risk tenors, the on-the-run Treasuries with their own ticks (S12 for the v2 universe), TYZ6 (`FuturesOverlay`, DR-V12-D06). Trade prints reach `LoopBook.applyTrade` and the router (DR-V12-D04). `CurveFeedV2` and `SimV2CurveRound` send the whole pricing v2 build set (DR-V12-D05). The loop converts futures contracts to face where quantities enter and leave it (DR-V12-D03). `AppIds`: `CLOB2_VENUE` 103, `FUTURES_VENUE` 104, gateways 4 and 5, feed status ids 6 and 7, `V2_OUTBOUND_RINGS` 6, `SWAP_1Y` 9012.
- `TradingProcess.Options.withProfile`; the `desk` config section (`profile: default|v2`, `market`, `pricing`, `strategy`, `router`; `ProcessConfig.DeskSelection`, `deploy/config/trading-v2.yaml`). A selection other than the recorded desk is written to `desk.selection` beside the journals; a start on journals another selection wrote is refused; replay follows the file. `python deploy/rehearsal.py --mode process --profile v2`.
- Halt, out-of-band kill, the supervisor and the undelivered-message halt cover every session: a kill is confirmed only when all four venues report nothing working. A restarted v2 process resumes four FIX sessions from their stores.
- The markout store is read from one file and written to another; the next fresh start promotes it (DR-V12-D07).
- Determinism: the simulator v2 venues of a session share a `SimSequencer`, one thread owns the market model, and the stepped process settles each session before the next (`SteppedDelivery`) (DR-V12-P02). Replay is exact; a stepped day repeats frame for frame; a threaded live v2 day is not repeatable run to run (two runs: 43,462 and 43,104 inbound frames, each reconciling with 0 breaks and replaying) (DR-V12-P03).
- Status JSON on the v2 profile adds `profile`, `unfedCurveInputs`, `venues` (usable, book age) and `spreadPv01`.
- v2 smoke day (`SmokeV2Test`, structural counts, no P&L): 20 RFQs, 20 quotes (6 swaps, 14 Treasuries), 9 won, 28 hedge parents, 63 fills (CLOB 1 38, CLOB 2 25, futures 0), 72 of 72 trades reconciled. The planner never chose the TY future at declared costs; the futures path is exercised by an outage test.

### Experiments and ledger

- Desk on simulator v2 in the back-tester (`SimBinding`, `DeskArms`, `DeskExperiment`): every arm of the Round 5 family is a `DeskProfile` built on one configuration; the strategy sees the live model's books with its own impact; experiment sessions publish every second; QuoteWithdraw is honoured in the v2 back-test (Q7) (DR-V12-B01 to B03, E02, E05).
- `prereg_version: 2`: session-seed designs (`SessionDesign`), band rows (`BandParameters`), refusal of spent data (`SpentData`), families with rows and excluded band points. `ExperimentRunner`: checkpointed per session and arm, resumable, worker processes, no ledger row until complete, a timing-only pilot that writes no P&L (DR-V12-E07, E08). `LedgerCli --bh family:ID` computes Benjamini-Hochberg on command (DR-V12-B04). A dated pre-registration stays on the version 1 rules (DR-V12-B05). `SimV2Report`. Python twins in `ratesresearch.prereg`. tools/sim-v2/ (`build_classpath.sh`, `launch_detached.cmd`, `status.cmd`, README).
- `research/prereg/R5-FAM-001.yaml`: one family of eight hypotheses (the v2 strategy against the 1.x strategy, six leave-one-out ablations, cover pricing alone), nine arms on one footing and the 1.1.0 desk as a descriptive tenth, on 2,000 shared sessions of seed base 31,000,000. UNSIGNED and not run (DR-V12-E01, E04, E06). The eight earlier drafts (SIM3-BASE-001, R5-001 to R5-007) are marked superseded before signing; kept, never run.
- Client ids per simulator tier come from one place: 2001, 2002, 2003 (`DeskSettings.V2_CLIENT_ID_BASE`, DR-V12-E03).
- Pilot on the pilot base (not an experiment, no ledger row): about 25 s per full-day session for one arm; session sd about 214k USD (8 sessions, imprecise).

### Assurance (Round 7)

- PIT mutation kill rates measured 2026-11-23: rates-risk 96.2 % (603 of 627), rates-posttrade 97.1 % (639 of 658), rates-core 93.6 % (437 of 467), rates-common 93.6 % (117 of 125), rates-api 90.5 % (190 of 210), rates-ops 88.7 % (306 of 345). Reports are not committed (E6).
- Property tests: `Decimal9PropertiesTest` against BigDecimal, `HeadroomAndMarkPropertiesTest`, `BreachedLimitPropertiesTest`, posttrade keeper and reconciliation properties; the timer-wheel property test now runs (its class name did not match surefire's pattern).
- Python reads journal format version 2 and the WORD64 chain hash (`ratesresearch.fnv`, `tools/journal_query.py`), checked bit for bit against Java-written fixtures.

### Fixes

- `RiskEngine.headroom` returns the exact boundary; its bisection stopped after 48 halvings and could fall up to 2^15 minor units short.
- A PV01 figure already past its limit can no longer grow by even one minor unit: the 1e-9 relative rounding allowance is removed from the pillar, net, gross, basis and swap-spread checks, and the before and after figures are built from the same partial sum.
- `Decimal9`: negative ties round away from zero as documented (`Math.round` took them toward positive infinity); `round`, `floor` and `ceil` are exact from 2^52 units up.
- `DeskGroup`: a fill of a child order the router has already released (one that crossed its cancel, or an order left from before a restart) is booked as a position, once by execution id, and counted (`lateFills`); it was dropped before, so risk and the venue could differ (DR-V12-D13).
- `BacktestRunner`, simulator v2: a cancel that finds the order filled reports `Filled` and is refused; it reported `PartiallyFilled` and then a `Cancelled` with nothing left, which the desk counted as an unrouted report (DR-V12-E09).
- Tests: `LoopAllocationTest` fills the outbound ring in its warm-up and asks for one zero window of four, each after the first within 1,024 bytes; `ChildOrderBookAllocationTest` repeats its long window after a one-off of at most 256 bytes (DR-V12-T01). `ratesresearch.prereg` type guards for mypy strict. `tools/check_cookbook.py` runs the command recipes 38 and 39. Cookbook recipes 40 to 45 (simulator v2 session, leave-one-out fair value, break-even pricing, factor hedging, ladder routing, a fill after release); four tutorials (docs/tutorials).
- Dead code removed in `RiskEngine.onFairValue` and `PositionKeeper`; behaviour unchanged.
- `FeedBinding`: the first snapshot of a book that already has levels is announced between DISCONNECTED and CONNECTED, so it is no longer read as a gap (DR-V12-D09).
- `Venues.simV2`: the three order books publish as venues 102, 103 and 104 (all were 102).
- `ChildOrderBook`: execution-id dedupe is held per child while it is in the book, in a preallocated pool (`ChildExecIds`); a fill beyond the pool is refused, counted and left outstanding. It allocated a 131 kB array after about 4,500 fills. `resetDay()` is removed (DR-V12-P04).
- Simulator v2 venues on separate FIX threads could enter one `BookModel` at once; fixed by the sequencer above.
- Back-test on simulator v2: the desk now sees the live model's books; reference prices every second instead of every 5 s; book tops keyed by price; the desk stands at the v2 session's business date.
- Router benchmark strawman baselines resend a rejected order's quantity (a first attempt hung).

### Documents

- New research documents on the site: SIM_V2_CALIBRATION, SIM_V2_DESIGN, PRICING_V2, STRATEGY_V2, ROUTER_BENCHMARK, DESK_V2_WIRING. API_SPEC, DECISIONS (DR-R3, DR-R4, DR-R5, DR-R6-R, DR-R6-L, DR-V12-B, D, P and E records), LEARN, COOKBOOK (recipes 38 and 39), ARCHITECTURE and diagrams, BENCHMARKS, runbooks (v2 profile, low-latency profile, registered experiment), the RTS 6 and algorithm inventories, SECURITY.md and the review's follow-up table updated for 1.2.0.
- docs/research/PNL_AUDIT.md: the old simulator's figures are superseded for new work, because the calibration puts its adverse-selection constants outside what the data supports. The recorded rows stand as records.

- Documentation audit against the code (2026-11-24): pricing v2 input path, thread and journal threads, core pinning, module dependencies, cancel and late-fill states, the journaling gateway, deployment topology and the registered-run refusals corrected; six new diagrams (pricing v2 data path, v2 topology, cancel and fill race, family experiment, latency stages, simulator v2 components).

## [1.1.0] - 2026-11-23

Pull requests #7, #8 and #9 (merged 2026-11-07 and 2026-11-08) and the fix round after the review of 2026-11-23. The tag is cut when the fix round merges. The controls marked opt-in below are off by default and are not switched on in the wired process. The release build ran 887 Java tests in 152 classes across 21 modules and 121 Python tests (1 skipped), no failures, with the line (0.85) and branch (0.75) coverage floors, Error Prone and SpotBugs, from `mvn -o -B -ntp verify` and `pytest -q research` on Windows 11 with JDK 25.0.5 on 2026-11-23; `mypy` was not run locally and is checked in CI. Two recorded lines moved, both in recipe 22: the status line gained the halt and recovery keys, and the back-test report's spread capture went from 54049.48 to 54049.14 because bonds now accrue from the dated date. The smoke day's counts (36 RFQs, 27 quotes, 16 fills, 210 hedge parents, 213 hedge fills) and its replay hash are unchanged. What the fix round leaves open is listed in docs/reviews/REVIEW_2026-11-23.md, section "Follow-up", and in STATUS.md. Every P&L figure here comes from the simulators under declared assumptions; the review shows that their sign follows from those assumptions (docs/reviews/REVIEW_2026-11-23.md Section 2.1).

### Added

- Risk carry time (`RiskCarry`): time from each client fill to the next hedge fill. An exploratory hedge latency sweep (`LatencySweep`, 0 to 5,000 ms): latency up to 1 s barely matters, and median carry is 20 minutes to over an hour. A carry-time limit (`CarryLimitHedgeBudget`, V1C-001, ledger seq 31): improved risk-adjusted hedge cost at central and high, inconclusive at low, so not supported and not adopted. A flow-aware version with a quiet gap (V1C-002, ledger seq 32) improved at high only, also not supported. docs/research/DESK_FEATURES.md, DR-V1C-01 to DR-V1C-03.
- AUDIT-21 to AUDIT-24: the production-desk audit re-run at v1.0.0. AUDIT-25: six combined settings on the same sessions. AUDIT-26: one holdout look at the chosen setting, which failed its pass marks; `DeskSettings` is unchanged. Ledger seq 25 to 30; docs/research/PNL_AUDIT.md.
- Forward log (paper trading): `io.ratesalgo.strategy.forward` (`ForwardSession`, `ForwardCycle`, `ForwardReport`, `ForwardCli`), `config/forward/desk.yaml`, `ratesresearch.forward.fetch` and `ratesresearch.forward.check`, `research/forward/run_forward.sh`, and the scheduled workflow `.github/workflows/forward.yml`, which commits to the `forward-log` branch. Real rates (US Treasury par curve, NY Fed SOFR, DTCC SOFR OIS prints), synthetic client flow, a hash-chained decision ledger. docs/research/PAPER_TRADING_PLAN.md; DR-FWD-01 to DR-FWD-04.
- COOKBOOK recipe 37: check a forward-log ledger from Python.
- LEARN Section 14: paper trading and the forward log.

Fix round after the review of 2026-11-23 (review ids in brackets):

- **Safety.** `runtime` configuration section (`loopIdleStrategy`, `loopCpu`) and the `RATES_HTTP_BIND` override (L3, L12). `TradingProcess.Tuning`, `KillResult`, status keys `halted`, `withdrawn` and `haltReason`, and four loop counters (`dutyCycles`, `outboundRetryQueued`, `outboundRetried`, `outboundRetryLost`). `EventLoop.Builder.retryOnDrop` for must-deliver cancels and withdrawals (L2, DR-RV-S04). API_SPEC.md 9.5, 9.8, 17.11, 18.9.
- **Routing and market data.** SBE template 45 `OrderCancelReject` and `VenueExclusion.Stale` (6), schema version unchanged (DR-RV-R01). `RouterTimeouts`, `ParentListener` and `ParentOutcome` (Filled, Cancelled, Expired, Failed), `SmartOrderRouter.venueHealth`, `BookView.lastUpdateNanos` (S2, S3, S4, DR-RV-R03). API_SPEC.md 2.3 and 15.0.
- **Pricing and reference data.** `datedDate` column in `bonds.csv` and `Bond.datedDate` (A5, DR-RV-P02). `AnalyticsPricing.Options` (`bondRiskAtMarket`, off by default; `maxQuoteMove`, default 100 bp), `rejectedInputs()`, `failedSpreadSolves()`, `bondSpread(id)`; `BondPricer.marketGradient` (A3, A8, DR-RV-P03, DR-RV-P04). `CalendarConsistencyTest` over every day of 2026 and 2027, `DatedDateTest` against QuantLib 1.44.
- **Risk and quoting.** Per-RFQ and per-client DV01 limits, `RiskLimits.rfqDv01` and `clientDv01`, unlimited until set, reject codes -5 and -6 (Q9, DR-RV-Q04). A desk-sized limit preset, `DeskSettings.deskSized()`, not the default (Q9, DR-RV-Q05). Hedger settings `maxMidAgeNanos` and `parentTimeoutNanos` (Q10). Opt-in and off by default: cover learning (`coverLearning`, Q3), widening after the clamp and a realised-volatility estimate (`widenAfterClamp`, `volatilityReference`, `volatilitySampling`, Q11), fractional-lot carry in the hedger (`Hedger.Config.carryFractionalLots`, Q13), `fairMoveWithdrawFraction` and `rejectMismatchedDone` (Q7). `DeskGroup.hedgesStalled()`. API_SPEC.md 12.1, 13.2, 14.2.
- **Research and ledger.** Looks per evaluation data set across pre-registration ids: `DataSets`, `LedgerCli` and `python -m ratesresearch.prereg`; pre-registrations may declare `prior_looks`, and new ledger rows carry `data_set`, `data_set_look` and `prior_looks` (review Section 2.4, DR-RV-C02). The ledger's 32 rows cover 13 data sets; three were read more than once (11, 9 and 2 looks), each recorded as look 1. `Stats.pairedT`, `pairedBlockBootstrapCi` and `benjaminiHochberg`, with Python twins (DR-RV-C03). Forward log: `ratesresearch.forward.health`, `ForwardCli --summary`, and in `forward.yml` a schedule keepalive and an issue opened when a run fails or a trading day is missing (E10).
- **CI and build.** Ledger hash-chain and looks check in the Python CI job; `.github/dependabot.yml` (Maven, pip, Actions, weekly, grouped); a CodeQL workflow for Java and Python, not gating; every workflow's actions pinned by commit SHA; `research/requirements-lock.txt` used as a pip constraints file (E7, DR-RV-C07). Opt-in PIT mutation testing (`-Ppit`) on rates-risk, rates-quoting, rates-journal and rates-analytics, with a weekly informational workflow (E6, DR-RV-C06). JaCoCo branch-coverage floor of 0.75 per module (E6, DR-RV-C05). Reproducible jar timestamps (`project.build.outputTimestamp`).
- **Documents.** DR-RV-S01 to DR-RV-C07 in DECISIONS.md; API_SPEC.md sections for every contract change above; LEARN material on the out-of-band kill, refused deltas, mass-status reconciliation, paired tests and looks per data set; a follow-up section in docs/reviews/REVIEW_2026-11-23.md.
- **Restart recovery (L6).** A trading process can start on existing journals. `JournalRecovery.repair` cuts a torn tail (a failed last frame followed only by zeros), reports and counts it, and refuses mid-chain damage, a missing segment or a bad header. `LoopRecovery` rebuilds each loop by replaying its inbound journal through the live loop and checking every produced message against the outbound journal; `RecoveryClock` carries time on from the last frame. A divergence, an outbound journal longer than the inbound one explains, or a journal ending in a LoopError refuses to start. Tests: `JournalRecoveryTest` (a file cut at every byte of its last frame), `LoopRecoveryTest`, `RestartRecoveryTest` (state digest and replay hash equal those of the process that stopped). Status keys `recovered`, `recoveredFrames`, `journalTornTails`, `outboundCompleted`; `recovery:` lines on standard output. DR-RV-X01, X02.
- **FIX sequence resume, reconnect and reconciliation.** `MappedSequenceStore` keeps each session's numbers in one memory-mapped page with two checksummed slots; after an unclean stop the inbound number resumes from the last one the loops had journaled. `fix.resetOnLogon` is configurable: the simulator preset resets, the deployment files resume. Fills a venue resends are booked once (`DeskGroup` drops a repeated ExecID). A session that was active and dropped reconnects under `fix.reconnect` (give-up after `maxAttempts`); a halted or deliberately disconnected one does not. A returning CLOB session reports GatewayStatus RECONCILING (4), sends an OrderMassStatusRequest and reports RECONCILED (5); the loop keeps the venue down until then. Status keys `reconciling`, `bookSynced`, `ready`. Tests: `SessionRecoveryTest`, `MappedSequenceStoreTest`, `VenueSessionReconnectTest`, `VenueResumeTest`, `DeskGroupTest`. DR-RV-X03 to X05.
- **FIX TLS 1.3 and Logon credentials; HTTPS control endpoint (L12).** `TcpInitiator` runs FIX over the JDK `SSLEngine`, TLS 1.3 only by default, with host-name and certificate checks, a handshake deadline and bounded writes (`TcpInitiatorTlsTest`). The in-process simulators accept TLS and check Username (553) and Password (554) in constant time; a wrong credential gets a Logout with text and a disconnect (`VenueSecurityTest`). The control endpoint serves HTTPS with `http.tls` (`HttpsEndpointTest`). Secrets come from an environment variable or a file only (`Secret`, `SecurityConfigTest`); `TradingProcessMainTest` scans every file under the journal directory for both passwords. Configuration keys `fix.*` and `http.tls.*` (API_SPEC.md 17.11). TLS allocation is measured, not removed (`TlsAllocationTest`, DR-RV-X06).
- `UndeliveredHaltTest`: with the loop's inbound ring full and only the gateways driven, an undelivered session message halts the process, withdraws, logs out, does not reconnect, and the reason is in the status.
- Runbooks: docs/runbooks/restart.md and docs/runbooks/certificate-rotation.md.

### Changed

- `BandScenario.presets` has an overload that takes an already parsed presets map, used by the forward session to pin the synthetic market to the real close.
- The prepared-roll allocation test in `rates-journal` is paced, warmed up, parks right before each roll and waits until the next segment is ready instead of sleeping (commits 3820801, 4a2f17a, fde7b88, 3b44709 in PR #8).

Fix round after the review of 2026-11-23:

- **Safety.** Every `/control/*` endpoint, status included, needs `X-Control-Token`; with no token configured they answer 403, and a non-loopback bind without a token refuses to start. The shipped configs bind 127.0.0.1 (L12). `POST /control/kill` answers 200 only when the withdrawal is confirmed and 503 with the reason otherwise (L2). `deploy/bluegreen.sh` records a colour switch only after a confirmed kill, unless the operator sets `RATES_OLD_COLOUR_CONFIRMED_DOWN=1` (E8). Status fields are written only when they change (L11).
- **Risk and quoting.** Risk-reducing orders no longer skip the pillar, net and gross PV01 checks; only the daily-loss check is skipped (Q12, DR-RV-Q02). Package quoter: reservations expire on the engine's poll, the inventory term is capped, the same package from the same client is throttled for 250 ms, and every leg counts toward the client DV01 limit. A Done at a price other than the quote is counted and reported, and still booked by default; a zero-quantity Done books nothing and teaches no win (Q7, DR-RV-Q03).
- **Pricing and reference data.** `AnalyticsGoldenTest` names the Hull-White, basis and carry rows as cross-language consistency checks against the project's own Python re-typing, not outside goldens (A10). The golden file has 225 instruments (was 224).
- **Research and ledger.** The forward summary reports the real close mark alone (mean and standard error) and labels session P&L synthetic. No ratio of mean to standard deviation is printed at any sample size (review Section 2.5, DR-RV-C01).
- **Transport.** A control endpoint bound beyond loopback needs both the token and `http.tls`, or the process refuses to start. Plain FIX is for loopback only: `TcpInitiator` refuses a plain connection to any other host. The deployment files set `fix.resetOnLogon: false`, TLS and credentials (deploy/README.md).
- **Documents.** README "Limits", STATUS.md, SECURITY.md, the RTS 6 inventory, the runbooks, deploy/OWNER_CHECKLIST.md and the pull request template updated to the fix round. `docs.yml` actions pinned by SHA.

### Fixed

- README: the documentation site note, and the desk P&L figures and limit, now from AUDIT-21 and AUDIT-26 (PR #7).
- `rates-strategy` prints output paths safely when they are outside the repository (f4bab10, PR #8).
- README ledger row count is 32 after V1C-001 and V1C-002 (PR #9).

Fix round after the review of 2026-11-23:

- **Safety.** A loop that stopped or stalled, or a gateway thread that died or stalled, now leads to a withdrawal of every quote and order from the gateway side and a logout; a supervisor thread watches both (L1, DR-RV-S01, DR-RV-S02). The kill no longer depends on the loop or on inbound-ring space: a halt flag read by the gateway thread withdraws, and the journaled KillSwitch keeps replay (L2). FIX: TCP connect bounded at 2 s, socket writes bounded, ResendRequest timeout of 5 s with two repeats then logout, BodyLength capped at 256 KiB (L5, L7). The process halts and logs out when a session message cannot be delivered to a loop after 2^20 spins, instead of spinning for ever.
- **Routing and market data.** Book feed deltas are never silently lost: a refused or gapped frame forces a snapshot resync announced by GatewayStatus DISCONNECTED and CONNECTED, and the feed binding takes a snapshot before forwarding anything (S1, DR-RV-R02). OrderCancelReject (35=9) reaches the router; children and parents have bounded waits and end Expired or Failed, reported once (S3, S4). Routers exclude a stale or gapped venue (`VenueExclusion.Stale`, journaled) and cancel passive children resting there; the desk marks the CLOB unusable on a feed disconnect, a sequence jump or a lost session, and the routers also after 10 s with no book update (S2). An IOC never rests in a workup (S5, DR-RV-R05). Venue overfills are booked and counted, not dropped (S9). Reconcile no longer frees an order missing from mass status (S8, DR-RV-R04). A TradeBust is applied once, and only for an execution the desk booked (Q14).
- **Pricing and reference data.** The 2-Year T-Note future tick is one eighth of 1/32 (0.00390625), was 0.0078125 (CME SER-8171, cited by CBOT Submission 20-119) (A11). The TY basket excludes notes with exactly 8 years remaining: at most 95 whole months (CBOT Rule 19101.A as amended by Submission 25-099). Z3N and TN windows checked against the rule text. Bonds accrue from the dated date; accrued interest matches QuantLib 1.44 (A5). The US government securities fixture calendar no longer lists 2026-04-03 (Good Friday), a 12:00 early close per SIFMA (A6, DR-RV-P01). A failed curve solve keeps the last good curve, and a quote beyond the band or unsolvable is rejected and counted; a repeated quote skips the rebuild (A8). `OisSwap.withFixedRate` rebuilds from unadjusted dates (A9).
- **Risk and quoting.** Hedger: no hedge off a mid older than 5 s or while inputs are unhealthy; a parent still working after 60 s is cancelled and stops counting as hedged (Q10, DR-RV-Q01). The CLOB quoter resends an unanswered cancel every second and reports it after three. A venue bust of an RFQ trade is matched by quote id (Q14, DR-RV-Q06).
- **Research and ledger.** Journal: a roll no longer spins for ever when the preparing thread has died or hung; a failed `JournalWriter.open` or `create` releases its mapping; a `RingJournal.close` that gave up can be retried (DR-RV-C04). Timing-dependent journal branches are forced in tests by a gating clock, so rates-journal coverage no longer depends on thread timing (E5).
- **Restart.** The RFQ simulator's ids no longer repeat after a restart: a restarted simulator keeps its numbers, its messages and its id space (`VenueResumeTest`). The book feed no longer stays gapped after a rebuild from an empty snapshot (`DeskGroupTest.anEmptyRebuildSeatsTheFeedOnTheNextFrameWhateverItsNumber`).

### Review 2026-11-23

Documentation changes from the review (findings E1, E2, E3, E9, E11 and the documentation part of E7).

- docs/reviews/REVIEW_2026-11-23.md added to the repository and the site; docs/REVIEWER_SUMMARY.md, a one-page summary, linked from the top of README and the site.
- The site home page described "Phase 0: foundations only"; it now describes the project as it is (E1). `tools/check_site_current.py` fails the docs workflow if the site home page, STATUS.md or README names a version or date older than the latest release here, or a phrase from an earlier state.
- README: a first-screen statement of how the project was built and checked (parallel AI sessions directed by one engineer; CI and external goldens; no independent human review). "Measured results" keeps only external checks and findings; plumbing self-checks move to a second table. The router row is relabelled as a regression check against the router's own simulator; the latency row leads with scheduled-to-encode and names the bench quoter, the laptop and the 100 ns clock tick. The daily Sharpe table moves to docs/research/BAND_RISK.md, with AUDIT-21 in place of the superseded AUDIT-11. "Limits" adds what the review found undisclosed: the P&L sign set by the spread clamp and the informed-flow constants, independent instrument walks, the little-used pricing library, one venue, one client tier, limits sized for the smoke day, and the forward log's synthetic income. The ledger-flag note now counts nine `code_dirty` rows, not four.
- STATUS.md brought up to date (E3): V1 integration closed, rows for pull requests #5 to #9, the repository public with a ruleset, CI statements that were pending now marked historical, and a row for this fix round.
- Governance documents state actual practice (E2): CONTRIBUTING.md, docs/AI_USE_POLICY.md and docs/ai_change_log.md say this is a solo project with no independent reviewer, that the nine required CI checks are the only merge gate, and that assisted commits are identified by their `Co-Authored-By` trailer, not by a label.
- docs/compliance/RTS6_INVENTORY.md uses the scale "illustrated in simulation", "document only", "absent" and "out of scope" (E9); rows that relied on review or change-management controls are downgraded. Nothing in the repository claims compliance.
- SECURITY.md describes dependency pinning as it is (E7, documentation part).
- The diagrams page and four diagrams no longer call built components "planned" or the review rule "one approving review".
- The 1.0.0 heading below is dated 2026-11-01, the day the v1.0.0 tag and release were made.

## [1.0.0] - 2026-11-01

The first release. The tag v1.0.0 is on 8e6ae10, the merge of pull request #6, and the GitHub release was published on 2026-11-01; this heading said 2026-10-31 until 2026-11-23. It includes every phase module (P0 to P6, round 1, pull requests 1 and 2), round 2 (pull request 3, merge ecfc591), the release pass (pull request 4), version 1 integration (pull request 5) and the journal shutdown fix (pull request 6): the modules are wired into one trading process (`rates-app`), the journal write path is off the loop's latency path, LEARN is finished, recipes 23 to 34 and the diagrams are added, the desk features are in `rates-strategy`, and the edge research, the P&L audit, the review fixes and audit fix packages A1, A2, B, C and D are merged. From this version on, a change to a pinned contract (API_SPEC.md) needs a major version.

Every P&L, Sharpe ratio or drawdown in this release comes from the simulators under declared assumptions. Each one is a consistency check, not evidence of edge (DECISIONS.md DR-008).

### Added

v1.0.0 release pass (branch `release/v1.0.0`).

- Daily-P&L volatility, Sharpe ratio (daily, and annualised by the square root of 252, zero risk-free rate) and maximum drawdown across the assumption band:
  - `rates-backtest`: `Stats.pnlRisk` and `Stats.PnlRisk`, and `report.BandRisk` (rows, JSON and Markdown, labelled as a simulator consistency check);
  - `ReportCli` writes `band_risk.json` and `band_risk.md` and adds the section "P&L risk across the assumption band" to each `report.md` and `report.json` (one day per journal);
  - `ExperimentCli` writes `<id>-<scope>-band_risk.json` and `.md` to its work directory, derived from the recorded daily P&L; ledger records are unchanged;
  - `ratesresearch.stats.pnl_risk` mirrors the Java figures.
  - Tests: `BandRiskTest`, `ReportTest` (two journals, three band points), `research/tests/test_pnl_risk.py`.
- `PackageQuoter.livePackages()`.
- Site page "P&L audit" (docs/research/PNL_AUDIT.md).

Round 2, edge research on public data (branch `claude/r2-edge-research-6aba2e`).

- `research/ratesresearch/edge`: six pre-registered studies with a shared method (`common.py`: block bootstrap, Holm across the family of eleven tests, verdict rules), source downloads with licence and SHA-256 manifests (`sources.py`), and a runner and report (`run.py`, `report.py`, `build.py`):
  - E1 Treasury auction concession;
  - E2 month-end index extension;
  - E3 swap-print volatility and intensity around releases and fixings;
  - E4 trade size by tenor and clustering;
  - E5 swap spread and futures positioning;
  - E6 carry tilt.
- Pre-registrations `research/prereg/E1.yaml` to `E6.yaml` with configs and `edge_programme.yaml`, committed before the evaluation data was read. Ledger rows seq 2 to 7.
- Results:
  - E3 and E4 supported after their holdouts and promoted to presets;
  - E1 not supported (reversed), E2 and E6 not supported;
  - E5 inconclusive because its fitting data could not be downloaded.
  - Negative results are kept.
- Six notebooks `research/notebooks/edge_e1_auction.ipynb` to `edge_e6_carry.ipynb`, which recompute every number from committed fixtures and check them against the ledger rows; docs/research/EDGE_STUDIES.md and the site's "Edge studies" pages; `research/tests/test_edge.py`.

Round 2, P&L audit (branch `claude/r2-pnl-audit-e32be7`).

- docs/research/PNL_AUDIT.md: 19 ranked leaks and opportunities with evidence, sizes across the band and fix packages A to D. Pre-registrations `research/prereg/AUDIT-01.yaml` to `AUDIT-04.yaml`, the harness `research/audit/` (`PnlAudit.java`, `run_audit.sh`, `summarize.py`), ledger rows seq 8 to 11 with copies in `research/audit/results/`.

Round 2, LEARN (branch `claude/r2-learn-b5137b`).

- LEARN.md chapters harmonised and finished for the wired system, including the knowledge check and the pitfalls.

Version 1, hedge execution after internalisation (branch `claude/v1-hedge-f17740`, PNL_AUDIT.md items 1 and 10).

- `rates-backtest`: `StrategyContext.cancelOrder`, `touchPrice` and `touchQuantity` (default methods), implemented by `BacktestRunner`; test `OrderCancelTest`.
- `rates-strategy`: `HedgeSlicing` and the slicing path of `DeskQuoter` (depth-sized children, passive first with escalation, implementation shortfall); `CostAwareHedgeBudget` and `HedgeBudget.observeMarket`; `HedgeExperiment`, `HedgeExperimentCli` and `research/hedge/run_hedge_experiment.sh`; test `HedgeExperimentTest`.
- Experiments: pre-registrations V1H-001 and V1H-002, config `data/experiments/V1H.config.yaml`, ledger seq 23 and 24. Slicing was supported and adopted as the desk default (`DeskFactory.deskDefault`, `ADOPTED_SLICING`; `nettingOnly()` is the old default). The cost-aware trigger was not supported and stays opt-in. Simulator consistency checks only (DR-008).
- Docs: PNL_AUDIT.md items 1 and 10, DESK_FEATURES.md, API_SPEC.md Section 19.11, DR-V1H-01 to DR-V1H-03.

Version 1, remaining gaps (branch `claude/v1-gaps-ea4320`).

- COOKBOOK.md recipe 35 (verify a committed Java journal's hash chain from Python; the fixture is pinned by a Java test, DR-V1G-01) and recipe 36 (check that a hot-path package allocates nothing). Every listed recipe is now written, and `tools/check_cookbook.py` checks 35 of the 36.
- CI builds every image in `deploy/` and runs the deployment rehearsal inside the trading image (DR-V1G-03). The informational latency job writes its percentiles to the job summary (`tools/latency_summary.py`, DR-V1G-04), and docs/BENCHMARKS.md has a GitHub-hosted ubuntu-latest row.
- No UK DMO golden test: the DMO formulae paper has no conventional worked example (DR-V1G-02).

Round 2, journal write path off the loop's latency path (branch `claude/r2-journal-latency-ee2034`).

- `rates-journal`: `RingJournal`, a journal written by its own thread from an off-heap Agrona ring, with the same frames as a direct append (DR-R2J-03); `JournalWriter.append` and `SegmentedJournal.append` overloads that take the frame stamp; `SegmentedJournal.rollWaits()`.
- `rates-core`: `JournalMode` (`DIRECT`, `RING`), `LoopJournals` (a loop's two journals in one mode) and `EventLoop.Builder.journals(LoopJournals)`.
- `rates-bench`: `--journal-mode` and `--ring-bytes` options, collection counts and journal wait counts in the JSON report, `JournalModeAppendBenchmark` (JMH, the loop thread's append in each mode) and `SafepointStallProbe` (how long segment creation holds up a spinning thread).
- Tests: `RingJournalTest` (frame-for-frame equality with the direct mode as a jqwik property, a ring smaller than the stream, refusals), a zero-allocation roll test and a pretouch test in `SegmentedJournalTest`; `LoopAllocationTest`, `LoopReplayTest` and the replay-hash property now run in both modes.
- Docs: BENCHMARKS cause analysis and before and after rows, API_SPEC Sections 1.5 and 9.7, ARCHITECTURE journal write path, DR-R2J-01 to DR-R2J-05.

Round 2, process wiring (branch `claude/r2-wiring-c31ae4`).

- `rates-app` (new): `TradingProcessMain` and `TradingProcess`, the assembled trading process (API_SPEC.md Section 18). Pricing binding `AnalyticsPricing` (P3's `FairValueSource` and `SensitivityProvider` over one SOFR OIS curve rebuilt one pillar per CurveInput, ladders mapped by `Dv01Adapter`); venue binding `VenueSession`, `InboundRouter` and `FeedBinding` (FIX and SBE book feed to the ring-buffer contract, GatewayStatus on logon, disconnect and snapshot rebuild); the desk on the loop (`DeskLoopFactory`, `DeskGroup`, `LoopBook`, `RouterHedgeSink`, `LoopOrderGateway`); post-trade binding `JournalFeed` and `VenueReconciliation`; back-test binding `JournalExport`, `DeskStrategy` and `DeskBacktest`; simulation support `SimClock`, `SimulatedVenues`, `CurveFeed`, `ClobLiquidity`. A `--replay DIR` mode.
- Tests: `SmokeTest`, `BacktestBindingTest` (replay hash of a threaded day, daily report), `PricingBindingTest` (1e-12 against direct analytics), `VenueBindingTest` (feed gap, lost session, injected faults), `WiredLoopAllocationTest`, `AppHotPathRulesTest`, `TradingProcessMainTest`, unit tests of the book, the inbound router and the hedge sink.
- Adapters in existing modules: `VenueGateway` sends QuoteWithdraw (60) and OrderWithdraw (61) as FIX Z and F (`OrderStateTracker.orderQuantity`); `SmartOrderRouter.cancel`, `cancelAll`, `parentsLive`, `maxParents`; `PositionKeeper` applies a venue `TradeBust` (44) as a VenueError bust (`JournalScript.venueBust`), closing the API_SPEC.md 17.14 item; `TradeRecord` gains `quoteId`, `instrumentId`, `tradeNanos` and `ClobSimulator.executions(owner)` lists the venue's fills, for the reconciliation; `CurveBuilder.maxRepricingError` no longer allocates an iterator.
- COOKBOOK.md recipe 22 (run the trading process on a synthetic day and read the daily report); the earlier planned items 22 and 23 move to 36 and 35 at integration, after the round 2 recipes 23 to 34.
- Docs: API_SPEC.md Section 18; ARCHITECTURE.md component rows and process topology; `module_deps.mmd` and `data_flow.mmd` redrawn; DECISIONS.md DR-R2W-01 to 15.

Round 2, cookbook recipes and diagrams (branch `claude/r2-cookbook-diagrams-656067`).

- COOKBOOK.md recipes 23 to 34: Nelson-Siegel-Svensson fit and rich and cheap Treasuries (23), z-spread and asset-swap spread to the OIS curve (24), carry, roll-down and the repo-implied forward (25), a compounded-in-arrears SOFR leg with lookback and lockout (26), a curve or butterfly RFQ priced as one package (27), quote pulls by the lifecycle controls (28), the router's decision table rebuilt from a journal (29), a CLOB workup and its book feed (30), PCA key-rate hedge ratios (31, Python), hit-rate calibration across the assumption band (32, Python), markout curves by client tier (33, Python) and a deployment config checked against its schema (34).
- Programs `Recipe23NssRichCheap` to `Recipe30ClobWorkup` and `Recipe34ConfigSchema` in `rates-cookbook` with recorded output under `src/test/resources/expected`, checked by `RecipesTest`; Python recipes `research/recipes/recipe31_pca_hedge.py`, `recipe32_hitrate.py` and `recipe33_markouts.py`; every recipe registered in `tools/check_cookbook.py`.
- Diagrams: `system_context`, `ci_pipeline`, `hedge_route_sequence`, `multi_curve_bootstrap`, `replay_determinism`, `research_to_production`, `rfq_end_to_end_timed` and `rts6_control_map` in `docs/diagrams`, shown on the site's diagrams page; `experiment_lifecycle` updated.

R2 desk features (branch `claude/r2-business-6cda18`).

- `rates-strategy` (new): `RiskLadder`; seven desk features behind interfaces whose defaults are today's engine: `HedgeBudget` (`ThresholdHedgeBudget`, `NettingHedgeBudget`), `AxeBook` (an `AxeSource`), `QuoteSizer` (`EnvelopeSizer`), `ClientTiering` (`StaticTiering`, `MarkoutTiering` publishing through `TierTableControl`), `HedgeTiming` (`OptimalStoppingTiming`, `FlowRateEstimator`), `InventoryPenalty` (`FundingPenalty`), `CouponCarry`; `SpreadOptimizer`; `DeskQuoter`, which reproduces `ModelSpreadStrategy` byte for byte at its defaults; `DeskMetrics`, `DeskReport`, `DeskReportCli` (desk P&L view and waterfall); `DeskFactory`, `BandScenario`, `DeskExperiment`, `DeskExperimentCli`.
- Experiments: pre-registrations R2B-001 to R2B-007 (`data/preregs/`), config `data/experiments/R2B.config.yaml`, seven ledger records (R2B-001 to R2B-007, seq 12 to 18). Internalisation and netting supported and adopted as the desk default (`DeskFactory.deskDefault`); axes, sizing, tiering, hedge timing, funding and all six together not supported. Simulator consistency checks only (DR-008).
- Tests: baseline equivalence, feature formulas, allocation-free per-RFQ and per-fill paths, waterfall reconciliation, experiment harness and refusals.
- Docs: docs/research/DESK_FEATURES.md (site page "Desk features"), API_SPEC.md Section 19, DECISIONS DR-R2B-01 to DR-R2B-10, diagrams "Internalisation flow" and "Desk P&L waterfall". SpotBugs exclusion for shared desk state (DR-R2B-08).

Round 2 fixes from the P&L audit, package A1 (branch `claude/fix-a1-models-67fd05`, commit 18da1a1).

- Audit item 2: `HitRateModel` learns the intercept and the spread slope from the desk's own wins and losses, one Newton step per outcome with a 5,000-RFQ forgetting window, within learning bounds of -8.0 to -0.2 for the slope and -3.0 to 5.0 for the intercept instead of the declared band; `learnedSlope()`, `learnedIntercept()`, `slopeStandardError()` and `slopeOutsideDeclaredBand()` for the daily report; `base()` no longer includes the intercept (DR-FIX-A1-01).
- Audit item 5: `TierTable.standard(version, maxQuantity, clients)` builds a three-tier default with nothing blocked and tiers 2 and 3 widened (1.15 and 1.35); `unblocked(version, widening)` turns blocked tiers into widened ones; `blockedTiers()` counts them (DR-FIX-A1-03).
- Audit item 11: `SpreadModel` averages the win probability over a normal cover spread with 8-point Gauss-Hermite quadrature; `observeCoverGap(gapBp)`, `coverSigmaBp()`, `meanCoverGapBp()`, `coverGaps()` and `winProbability(base, s)`; with no gaps and no prior the optimum equals the point estimate (DR-FIX-A1-02).
- Tests: `PricingModelsTest` (learning, bounds, cover averaging, tier defaults).

Round 2 fixes from the P&L audit, package B (branch `claude/fix-b-clob-68d6c2`, commit 52b6df1).

- Audit item 7: `ClobQuoter.inventorySkew(covariance, kappa, maxSkewBp)` moves both CLOB targets by `clamp(kappa u' Sigma p / |U|, maxSkewBp)` bp, the RFQ engine's inventory skew, before the venue tick rounding; off until called; `lastSkewBp()` reads it (DR-FIX-B-01).
- Audit item 6: the tick rounding itself came with the review (DR-R2R-12); this package adds a test that checks every CLOB price against `ClobBook`'s tick rule for 1/32, 1/128 and 1/256 ticks on flat, long and short books.

Round 2 fixes from the P&L audit, package C (branch `claude/fix-c-controls-1e7782`, commits b0521ec and b69d8c1).

- Audit item 14: a breached PV01 limit may shrink but not grow, for quotes, orders and headroom; a one-sided quote whose fill offsets net PV01 skips only the daily-loss check; `RiskEngine.flattenOnly()` is true while the engine's own daily-loss kill holds, and risk-reducing orders still pass then; `Hedger` keeps its parents and keeps hedging under that kill (`flattenKills()`) (DR-FIX-C-01).
- Audit item 16: basis and swap-spread limits, `RiskLimits.basisPv01` and `swapSpreadPv01`, refused with `RiskEngine.REJECT_BASIS` (-3) and `REJECT_SWAP_SPREAD` (-4) and counted post-trade by `basisBreaches()`; `ExposureBasisAttribution` (rates-hedge) attributes hedge fills to basis and swap-spread exposure and slippage, wired per currency by `Desk.basisAttribution(currency)` (DR-FIX-C-02).
- Audit item 15: the router charges a filled passive child an adverse move (`RouterParams.filledAdverseFactor`, in sigma over the expected queue wait) and uses per-instrument volatility (`RouterParams.Volatility`); off by default, `RouterPresets.globalWithControls(volatility)` turns it on at 0.75 sigma (DR-FIX-C-03).
- Audit item 18: the router charges a cost per order message (`RouterParams.messageCostPoints`, 0.0001 points per lot in `globalWithControls`); `QuoteStuffingDetector` counts cancels, replaces and withdrawals with new messages (`messages(id)`, `cancels(id)`, `instruments()`); `EodReportMain` writes per-instrument message and cancel counts (DR-FIX-C-03).
- Tests: `RiskControlsTest`, `HedgeControlsTest`, `RouterControlsTest`, `QuoteStuffingCancelsTest`, `DeskWiringTest`; the desk-level oracle in `SinkAuditor` and `ChaosTest` follows the new breach rule.

Round 2 fixes from the P&L audit, package A2 (branch `claude/fix-a2-engine-8168e0`, commits 78615e5, 2031b7a and ac1bba3).

- Audit item 3 (recording side): every RFQ decision and every response goes to a `QuotingEngine.RfqRecordListener` (`recorder(listener)`) with the signed cover gap and our rank; `coversRecorded()`, `wonBehindCover()` and `meanWonBehindCoverBp()` count wins from behind the cover (DR-FIX-A2-01).
- Audit item 4: client markouts are measured from the fair value at the trade, not the traded price, so the captured half spread no longer hides the adverse move (DR-FIX-A2-02).
- Audit item 8: `poll` withdraws a live quote (AdverseSignal) when fair value moves against it by more than `QuotingConfig.fairMoveWithdrawBp` (default 0.25 bp; 0 turns it off); `fairMovePulls()` counts them (DR-FIX-A2-03).
- Audit item 9: a release calendar is on by default (`QuotingConfig.usReleaseSchedule`: 08:30 and 10:00 New York on weekdays of 2026 and 2027); quotes are pulled and RFQs passed with `PassReason.RELEASE_WINDOW` from 60 s before to 30 s after, then half spreads are multiplied by 2.355 for 5 minutes; `releaseCalendar`, `releasePullNanos`, `releaseWidening`, `releases()`, `inReleasePull(now)` (DR-FIX-A2-04).
- Audit item 12: `PackageQuoter` passes the same gates as a single RFQ (kill, health, release window, throttle, fair-value age, the risk check on every leg), reserves its legs for the time to live, books Dones, late ones included, to risk, and charges its spread on the net ladder's risk-equivalent PV01 (DR-FIX-A2-05).
- Audit item 13: a late single-RFQ Done (review DR-R2R-13) is recorded and reaches the hedger (DR-FIX-A2-06).
- rates-app: the package's own winner's-curse setting (0.01 bp, commit 2031b7a) was superseded in its merge of round2-integration by DR-INT-05's 0.02 bp, which stands (DR-INT-07). COOKBOOK recipes 22 and 27 re-recorded. Tests: `AuditFixesTest`.

Round 2 fixes from the P&L audit, package D (branch `claude/fix-d-backtest-cdd6aa`, commits 503be68, 04edc03 and 8331c41).

- Audit item 3 (back-tester side): `RfqOutcomeModel.ClientChoice.BEST_PRICE` (`withClientChoice`), a client who trades with the best price, with the same random draws as the default `HIT_RATE` (DR-FIX-D-02).
- Audit item 17 (coupon carry): `CarryModel.bondCoupons(annualCouponByInstrument)` accrues bond coupons ACT/365 on the carry line next to funding (DR-FIX-D-03). Balance sheet, initial margin and the CCP basis remain unpriced.
- Back-tester hedge fills: `HedgeCostModel.withDepthWalk(true)` fills market hedges level by level against the displayed touch quantity; `fromPreset` keeps the linear rule so P5-001 and AUDIT-01 to 04 still reproduce (DR-FIX-D-01).
- Experiments: pre-registrations AUDIT-11 to AUDIT-14 (`research/prereg/`), the P&L audit re-run on the production desk (`research/audit/DeskAudit.java`, `run_desk_audit.sh`), ledger rows seq 19 to 22, results and a PNL_AUDIT.md section "Production-engine re-run". Tests: `FixPackageDTest`.

### Fixed

- Journal shutdown: `RingJournal.close()` and `SegmentedJournal.close()` wait while their background threads make progress (consumed position, or preparer pages touched and segments created) and give up only after 60 s with none, reporting the backlog, positions, roll waits and preparer progress. Once closing, the preparer stops pretouching the unused next segment, which is unmapped without `force()` and deleted. Before, two fixed one-minute joins failed `LoopAllocationTest` on a slow windows-latest runner although the drain was making progress (DR-INT-10).
- Review fixes (branch `claude/r2-review-6e9f43`, DR-R2R-01 to DR-R2R-14):
  - coupons for carry and basis selected by record date;
  - a kill completes when a listener throws;
  - a fill after a confirmed cancel no longer corrupts parent accounting;
  - the router's cost model charges a venue's price offset;
  - strict FIX integer, tag and timestamp parsing;
  - holiday fixture covers 2026 and 2027 and refuses other years;
  - fail days stop at the cancel date;
  - no quote at negative edge and one hit-rate label per RFQ;
  - the risk-reducing exemption counts working reservations;
  - an instrument without a hedge cost is not traded;
  - swap golden tolerances tightened;
  - CLOB quotes rounded to the venue tick;
  - late fills on expired quotes and killed hedge parents are booked;
  - SpotBugs exclusions name classes.
- `VenueSession` retries a connection whose Logon the venue did not accept (connect refused, closed before the venue's Logon, or Logon timed out) under a `ReconnectPolicy` (default 5 retries from 100 ms, doubling, capped at 2 s). Each attempt is journaled as GatewayStatus RECONNECTING (status 3, attempt number in byte 13). Before, such a session stayed down until `connect()` was called again (DR-INT-09; `VenueSessionReconnectTest`; v1.0.0 release pass).
- `VenueBindingTest` waits up to 10 s of wall-clock time for both FIX sessions to log on again after a reconnect, as it already did for the venue log, instead of checking after a fixed 500 ms of simulated time. The fixed wait failed on main CI for ecfc591 (Java 25, ubuntu-latest), because the logon runs on the sessions' socket threads (v1.0.0 release pass).
- `LatencyHarnessTest` accounting: the loop's outbound counter includes dropped Quotes, so the test now checks `inbound == outbound` and `outbound - outboundDropped == seen`; the earlier equation failed on any run with backpressure drops (DR-INT-06 amendment; v1.0.0 release pass).
- The first waited segment roll on a loop thread deoptimised compiled code and allocated 176 bytes, once, while trading (confirmed with `-Xlog:deoptimization`). `JournalWarmUp.exerciseWaitPaths` now runs the waited-roll and full-ring paths on scratch journals before `TradingProcess`, `LatencyHarness` and `LoopAllocationTest` start their loops. `LoopAllocationTest` failed 3 of 10 busy-core runs before and 0 of 10 after, with bounds unchanged; it reports roll waits per window (DR-INT-06 amendment).
- `Desk` registers the package quoter with the kill switch, so a kill withdraws every live package at once (v1.0.0 release pass; DR-FIX-A2 amendment in DECISIONS.md).
- docs/research/PNL_AUDIT.md said AUDIT-01 to AUDIT-04 ran on a clean tree. The ledger records `code_dirty: true` for AUDIT-02 to AUDIT-04, and the document now says so and why (v1.0.0 release pass).
- Journaling-on latency tail (p99.9 271 ms in the P2 run): new segment files are zero-filled with file-channel writes instead of page stores through the mapping, which under the Parallel collector held every thread at a safepoint for the whole fill; segments are prepared and every page touched off the loop thread, one ahead; a roll maps nothing and allocates nothing on the loop thread; the latency harness no longer creates an object per gateway poll (DR-R2J-01, DR-R2J-02).

### Changed

- Version 1.0.0 in every Maven module, `research/pyproject.toml`, `ratesresearch.__version__` and the research user agent (v1.0.0 release pass).
- README rewritten for the wired system, with measured results, their sources and a limits list. docs/ARCHITECTURE.md covers `rates-strategy` and the controls that ship off by default. STATUS.md records estimate against actual for every phase and round. `module_deps.mmd` gains `rates-strategy`.
- `deploy/trading/Dockerfile`, `deploy/venue-sim/Dockerfile` and the process-mode rehearsal start `io.ratesalgo.app.TradingProcessMain`; the rehearsal polls for a live quote; `.github/workflows/ops.yml` packages `rates-app`.

### Added

Phase 2, event core (workstream W2).

- `rates-core` (new): `EventLoop` single-writer duty cycle over Agrona `ManyToOneRingBuffer` inbound rings and one `OneToOneRingBuffer` per gateway, with dispatch by SBE template id, inbound and outbound journaling at a latched cycle time, timer polling with journaled clock ticks, agents and outbound backpressure events; `LoopRunner` with thread naming, optional Linux core pinning (`net.openhft:affinity`, optional), idle strategies, day hooks and LoopError journaling; `LoopReplay` with frame-by-frame outbound chain-hash comparison; `RingBufferContract`, `InboundPublisher`, `ControlMessages` (core control schema 100); `AnchoredClock`, `EventClock`, `LoopCounters`; `chaos.FaultInjector` for drop, duplicate, delay, reorder and gateway disconnect.
- `rates-journal`: `SegmentedJournal` and `SegmentedJournalReader` (segments `name.000001.raj` onward, one chain across segments), `FrameSource`, `Replayer.replay(FrameSource, FrameHandler)`. Public API of the single-file classes unchanged.
- `rates-bench` (new): fixed-rate latency harness with HdrHistogram and coordinated-omission correction, JSON and Markdown reports, `TickToWireProbe` hook; JMH microbenchmarks of the ring publish and the journal append; `exec:exec@latency` and `exec:exec@jmh`.
- Tests: zero-allocation check over one million messages, ArchUnit hot-path rules with a violating fixture, jqwik properties for exactly-once in-order processing under faults and for replay hash equality, segmented journal round trip and cross-segment corruption.
- CI: informational latency job that uploads its JSON report.
- Docs: API_SPEC Section 1.5 and Section 9, LEARN Section 11 subsection on the ring-buffer contract and replay comparison, COOKBOOK recipes 12 and 13, ARCHITECTURE event loop section, BENCHMARKS first results, `docs/diagrams/event_loop_cycle.mmd`, DECISIONS DR-P2C-01 to DR-P2C-10.

Phase 2, venue half (workstream W3).

- `rates-refdata`: securities master for Treasuries, Gilts and Bunds with the on-the-run and when-issued rule, swap templates with MAT flags and block thresholds, futures specifications, party master, auction calendar, `CalendarSource` with a fixture implementation; checksummed fixtures in `data/refdata/v1` and their generator `tools/gen_refdata_fixtures.py`.
- `rates-fix`: allocation-free FIX 4.4 codec (`FixParser`, `FixWriter`, `FixFramer`, exact decimal and timestamp conversion), engine-side `FixSession` (logon, heartbeat, test request, resend request answered by gap fill, sequence reset, gap detection), `TcpInitiator`, `VenueGateway` translating RFQ, order, quote status and market data flows to and from SBE, `OrderStateTracker` (duplicates, stale reports, late fills, busts).
- `rates-venues`: QuickFIX/J 3.0.2 simulators for a D2C multi-dealer RFQ venue, a D2D Treasury CLOB with workup, icebergs, minimum size and self-match prevention, and a SEF with RFQ-to-3, block thresholds and an FCM credit hub; cancel-on-disconnect on every venue; SBE book feed publisher and consumer with snapshot recovery; market data composite with crossed, locked, outlier and staleness checks and fixing republication; sixteen certification scripts and their runner; chaos and synthetic-day tests; standalone process entry points.
- SBE schema: messages MarketDataReject (40), OrderCancel (41), OrderReplace (42), QuoteCancel (43), TradeBust (44), enum MdRejectReason, value QuoteStatus.DoneAway (API_SPEC.md Section 2.1). Schema version unchanged (DR-P2V-02).
- Documentation: LEARN.md Section 6, COOKBOOK.md recipes 10 and 11, API_SPEC.md Sections 2.1, 10 and 11, docs/ARCHITECTURE.md venue layer, diagrams of the RFQ flow and the CLOB workup states, DR-P2V-01 to DR-P2V-12.

Phase 3 quoting, risk and hedging (branch `claude/p3-quoting-risk-hedge-70e454`).

- `rates-api`: `InstrumentDirectory`, `FairValueSource`, `BookSource`, `SensitivityProvider`, `HedgeOrderSink`, `OutboundSink`; value types `Dv01Vector`, `Instrument`, `KeyRateTenors`, `Decimal9`; in-memory and recording stubs.
- `rates-risk`: `RiskEngine` with an incremental key-rate PV01 ladder per currency, basis, swap-spread and curve-spread exposures, daily P&L, reservations, pre-trade checks in a fixed order and edge-triggered post-trade alerts; `RiskLimits`; `TokenBucket`; `KillSwitchController` with a four-eyes reset.
- `rates-hedge`: `BoxQpSolver` (accelerated projected gradient), `HedgePlanner` (variance against cost), `Hedger` (staged hedging with a decay target and axes), `HedgeAnalytics` (slippage against arrival mid, residual series, basis attribution hook), `HedgeCostModel` and `TableHedgeCostModel`, `KeyRateCovariance`.
- `rates-quoting`: `QuotingEngine` (RFQ decisions, lifecycle controls, duplicate detection, throttles, feedback learning, toxicity), `FairValueEngine` (microprice, futures signal, validated signals), `HitRateModel` and `HitRatePreset`, `SpreadModel`, client tiers with control-plane review, `PackageQuoter`, `ClobQuoter`, `QuotingEventHandler`, `Desk`.
- SBE schema v1: `QuoteWithdraw` (60), `OrderWithdraw` (61) (written as QuoteCancel and OrderCancel on the P3 branch, renamed at integration, DR-INT-01), `CancelReason`, `LimitKind` MessageRate and FatFinger (DR-P3-04).
- Cookbook recipes 14 to 16; LEARN Sections 7 and 8; API_SPEC Sections 12 to 14; decision records DR-P3-01 to DR-P3-12; diagrams of the RFQ decision flow and the kill-switch state machine.

Phase 4 smart order router (W6).

- `rates-router`: venue cost model (half spread, square-root impact, fees, queue fill probability, latency) with versioned parameters and a declared preset in `data/presets.yaml` (`router`); greedy convex allocation across venues with minimum sizes, lots and participation caps; randomised schedule (seeded); passive first with a time limit, escalation, re-routing on book moves, deadline; never crosses its own orders; child order state machine handling duplicate, stale, out-of-order and late reports, with mass status reconciliation; decision journaling; in-memory CLOB venues; back-test harness with two baselines and block-bootstrap intervals.
- `rates-messages`: RouteDecision (80), RouteDecisionVenue (81), ChildOrderTransition (82) and the enums VenueKind, RouteReason, VenueExclusion, ChildState, TransitionCause; schema version unchanged (DR-P4-01).
- COOKBOOK recipe 17 (route a hedge order and read the markout report), LEARN Section 9, API_SPEC Section 15, ARCHITECTURE router section, child order lifecycle diagram, DR-P4-01 to DR-P4-06.

Phase 5, back-testing and research (W7, W10).

- `rates-backtest` (new module): `BacktestRunner` replaying SBE journals through a pluggable `Strategy` with a `ManualClock`; RFQ outcomes from a logistic hit-rate model at a declared band point with simulated competitors and common random numbers per RFQ; queue-position CLOB fills; hedge cost model; results journal, `fills.csv`, `rfqs.csv`, `pnl.csv`, `summary.json`.
- `SyntheticDay`: seeded sessions of QuoteRequests, BookUpdates and CurveInputs with flow clustered at 08:30, 10:00, 11:00 and 15:00 New York, informed and uninformed clients by tier, and a futures-cash basis.
- P&L attribution (spread capture, inventory, hedge cost, carry and roll hook, funding, fees, residual) reconciled to position P&L below one cent on every run.
- Statistics: moving-block bootstrap over days, Welch's test, Holm adjustment; identical to the Python implementation on a shared fixture.
- Pre-registration format and validator (Java and Python), append-only hash-chained ledger `data/ledger/ledger.jsonl`, `ExperimentCli` with refusals; first experiment P5-001 recorded (not supported).
- Daily report (`ReportCli`, Markdown and JSON) and the weekly review template `docs/templates/weekly_review.md`.
- `research/ratesresearch` 0.1.0: `presets`, `stats`, `prereg`, `assumptions`, `hitrate`, `flow`, `markouts`, `spread`, `backtest_analysis`, `data.dtcc`, `data.finra`; five notebooks executed in CI and rendered into the site.
- Presets: `usd_rfq_flow` fitted from DTCC SDR public dissemination (manifest `data/manifests/dtcc_rates_2025-10.json`), `usd_rfq_hit_rate` fitted to synthetic labels with the declared slope band, declared `usd_rfq_competition`, `usd_synthetic_market`, `usd_hedge_cost`. The preset schema gains `assumptions` (DR-P5-01).
- Documentation: LEARN.md Section 10, COOKBOOK.md recipes 18 to 20, API_SPEC.md Section 16, ARCHITECTURE back-test section, experiment lifecycle diagram, DR-P5-01 to DR-P5-07.

Phase 6, post-trade and operations (branch `claude/p6-posttrade-hardening-3e4054`).

- `rates-messages`: `PostTradeBust` (template 100) and `TradeAmend` (101) with enums `TradeSource`, `BustReason`, `AmendReason`; no existing message changed (DR-P6-01).
- `rates-posttrade`: position keeper over journaled ExecutionReports and RFQ fills with duplicate, late, bust and amendment handling and an audit trail; weighted-average-cost positions per book, account and instrument; order records in the RTS 6 Annex II layout with a five-year retention archive; `MarkSource` and `CarrySource` with fixtures, official end-of-day P&L under a written marking policy and its reconciliation to back-tester attribution; clearing (swaps) and settlement (bonds) state machines with fail cost; daily reconciliation with classified and explained breaks; synthetic chaos day; restart procedure with venue status hooks; quote-stuffing and self-trade surveillance detectors; end-of-day report generator.
- `rates-ops`: health, readiness, metrics and control endpoints on the JDK HTTP server; Prometheus text exporter; YAML configuration validated against a JSON Schema at start; journaled kill switch; stress replayer at twice peak rate with coordinated-omission-corrected latency.
- `deploy/`: Dockerfiles for the trading, venue-simulator and report processes, compose file, Prometheus configuration and alert rules, Grafana dashboard, blue/green switch, deployment rehearsal, owner checklist.
- `.github/workflows/ops.yml`: configuration schema, dashboard and alert lint, RTS 6 inventory check, image builds when Docker is available, process-mode rehearsal with the kill switch.
- Tools: `validate_config.py`, `lint_dashboards.py`, `check_inventory.py`, `journal_query.py`.
- Documentation: LEARN Sections 12 and 14, COOKBOOK recipe 21, API_SPEC Section 2.1 and Section 17, ARCHITECTURE post-trade and operations sections, docs/compliance (RTS 6 inventory, algorithm inventory and self-assessment templates), docs/runbooks (six incident classes, start and end of day, warm-standby design, post-incident template), two Mermaid diagrams, DR-P6-01 to DR-P6-07.

Phase 0 foundations.

- `rates-common`: `Clock` interface (`nanoTime`, `epochNanos`), `SystemClock`, `ManualClock` with `advance`, hashed `TimerWheel` with `schedule`, `cancel`, `poll` firing in deadline-then-id order.
- `rates-messages`: SBE schema v1 (`rates-schema-v1.xml`, schema id 1, version 0) with Heartbeat, BookUpdate, CurveInput, QuoteRequest, Quote, QuoteResponse, NewOrder, ExecutionReport, RiskLimitBreach and KillSwitch; codecs generated at build by sbe-tool.
- `rates-journal`: append-only memory-mapped journal (`RAJ1` format), `JournalWriter`, `JournalReader`, `Replayer` with FNV-1a 64 chain-hash verification.
- `research/ratesresearch` 0.0.1: `DataSource` and `SOURCES`, `sha256_of`, `write_manifest`, `load_manifest`, `fnv1a64` matching the journal chain hash.
- Documentation: README, LEARN (Sections 1 and 11 written, others outlined), COOKBOOK (three P0 recipes), API_SPEC (Sections 0 to 4 pinned), ARCHITECTURE, BENCHMARKS methodology, AI_USE_POLICY and change log, GITHUB_PAGES, four Mermaid diagrams, DECISIONS DR-001 to DR-008, STATUS, CONTRIBUTING, SECURITY, data/SOURCES.md, data/presets.yaml.
- MkDocs Material site built with `--strict` in CI.
- CI on Linux and Windows with JDK 25 gating and a JDK 27 informational leg; Python and docs jobs.

### Added (Phase 1, workstream W1: analytics)

- `rates-analytics` (`io.ratesalgo.analytics`): epoch-day dates, versioned holiday calendars (US SIFMA, SOFR, Fed, TARGET, London, joint) with ad hoc changes and knowledge dates, day counts (Act/360, Act/365F, Act/Act ISDA and ICMA, 30/360 US, 30E/360), business-day conventions, IMM dates, schedules with short and long stubs and the end-of-month rule.
- Discount curves on log discount factors with linear, monotone convex and tension spline interpolation, turns, exact gradients; bootstrap from overnight fixings, meeting-dated OIS, SOFR futures with Hull-White convexity, par OIS and term swaps on a projection curve; sequential Brent or global Levenberg-Marquardt; incremental rebuild.
- OIS swaps for USD SOFR, EUR EUR STR and GBP SONIA with lookback, lockout and observation shift; PV, par rate, annuity, gradients; key-rate and parallel DV01 by algorithmic differentiation and by bumping; `Dv01Ladder` for the P3 risk engine.
- Bonds (Treasuries, Bunds, gilts with ex-dividend), street, true and Treasury auction yields, duration, convexity, DV01, curve price, z-spread, asset-swap spread, carry and roll-down, repo forward price, Treasury bills, Nelson-Siegel-Svensson and cubic spline fitted curves; GC and special repo curve and funding charge.
- Futures: CBOT conversion factors and baskets, Eurex and ICE Long Gilt factors, gross and net basis, implied repo, CTD, futures DV01; SOFR futures.
- `research/ratesresearch/goldens`: QuantLib 1.44 golden generator and `data/golden/analytics_golden.json` (224 priced instruments); `research/ratesresearch/data`: downloaders with licence and SHA-256 records and small fixtures; `research/ratesresearch/curves.py`: PCA of Treasury curve moves and hedge ratios.
- COOKBOOK.md recipes 4 to 9; LEARN.md Sections 2 to 5; API_SPEC.md Sections 5 to 8; DECISIONS.md DR-P1-01 to DR-P1-09; Mermaid diagram of the curve build.

### Changed (Phase 1)

- CI Python job installs the `golden` extra (QuantLib 1.44) so the golden regeneration test runs on Linux and Windows.
- `config/spotbugs-exclude.xml`: the shared mutable curve of `CurveBuilder` and `CurveContext` is excluded from EI_EXPOSE_REP with the reason stated.
