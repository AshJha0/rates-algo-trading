# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. Before 1.0, minor versions may change contracts, and each such change has a decision record.

## [Unreleased]

### Added

Version 1, hedge execution after internalisation (branch `claude/v1-hedge-f17740`, PNL_AUDIT.md items 1 and 10).

- `rates-backtest`: `StrategyContext.cancelOrder`, `touchPrice` and `touchQuantity` (default methods), implemented by `BacktestRunner`; test `OrderCancelTest`.
- `rates-strategy`: `HedgeSlicing` and the slicing path of `DeskQuoter` (depth-sized children, passive first with escalation, implementation shortfall); `CostAwareHedgeBudget` and `HedgeBudget.observeMarket`; `HedgeExperiment`, `HedgeExperimentCli` and `research/hedge/run_hedge_experiment.sh`; test `HedgeExperimentTest`.
- Experiments: pre-registrations V1H-001 and V1H-002, config `data/experiments/V1H.config.yaml`, ledger seq 23 and 24. Slicing was supported and adopted as the desk default (`DeskFactory.deskDefault`, `ADOPTED_SLICING`; `nettingOnly()` is the old default). The cost-aware trigger was not supported and stays opt-in. Simulator consistency checks only (DR-008).
- Docs: PNL_AUDIT.md items 1 and 10, DESK_FEATURES.md, API_SPEC.md Section 19.11, DR-V1H-01 to DR-V1H-03.

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

- Journaling-on latency tail (p99.9 271 ms in the P2 run): new segment files are zero-filled with file-channel writes instead of page stores through the mapping, which under the Parallel collector held every thread at a safepoint for the whole fill; segments are prepared and every page touched off the loop thread, one ahead; a roll maps nothing and allocates nothing on the loop thread; the latency harness no longer creates an object per gateway poll (DR-R2J-01, DR-R2J-02).

### Changed

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
