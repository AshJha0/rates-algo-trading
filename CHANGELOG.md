# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. Before 1.0, minor versions may change contracts, and each such change has a decision record.

## [Unreleased]

### Added

Phase 4 smart order router (W6).

- `rates-router`: venue cost model (half spread, square-root impact, fees, queue fill probability, latency) with versioned parameters and a declared preset in `data/presets.yaml` (`router`); greedy convex allocation across venues with minimum sizes, lots and participation caps; randomised schedule (seeded); passive first with a time limit, escalation, re-routing on book moves, deadline; never crosses its own orders; child order state machine handling duplicate, stale, out-of-order and late reports, with mass status reconciliation; decision journaling; in-memory CLOB venues; back-test harness with two baselines and block-bootstrap intervals.
- `rates-messages`: RouteDecision (80), RouteDecisionVenue (81), ChildOrderTransition (82) and the enums VenueKind, RouteReason, VenueExclusion, ChildState, TransitionCause; schema version unchanged (DR-P4-01).
- COOKBOOK recipe 16 (route a hedge order and read the markout report), LEARN Section 9, API_SPEC Section 15, ARCHITECTURE router section, child order lifecycle diagram, DR-P4-01 to DR-P4-06.

Phase 5, back-testing and research (W7, W10).

- `rates-backtest` (new module): `BacktestRunner` replaying SBE journals through a pluggable `Strategy` with a `ManualClock`; RFQ outcomes from a logistic hit-rate model at a declared band point with simulated competitors and common random numbers per RFQ; queue-position CLOB fills; hedge cost model; results journal, `fills.csv`, `rfqs.csv`, `pnl.csv`, `summary.json`.
- `SyntheticDay`: seeded sessions of QuoteRequests, BookUpdates and CurveInputs with flow clustered at 08:30, 10:00, 11:00 and 15:00 New York, informed and uninformed clients by tier, and a futures-cash basis.
- P&L attribution (spread capture, inventory, hedge cost, carry and roll hook, funding, fees, residual) reconciled to position P&L below one cent on every run.
- Statistics: moving-block bootstrap over days, Welch's test, Holm adjustment; identical to the Python implementation on a shared fixture.
- Pre-registration format and validator (Java and Python), append-only hash-chained ledger `data/ledger/ledger.jsonl`, `ExperimentCli` with refusals; first experiment P5-001 recorded (not supported).
- Daily report (`ReportCli`, Markdown and JSON) and the weekly review template `docs/templates/weekly_review.md`.
- `research/ratesresearch` 0.1.0: `presets`, `stats`, `prereg`, `assumptions`, `hitrate`, `flow`, `markouts`, `spread`, `backtest_analysis`, `data.dtcc`, `data.finra`; five notebooks executed in CI and rendered into the site.
- Presets: `usd_rfq_flow` fitted from DTCC SDR public dissemination (manifest `data/manifests/dtcc_rates_2025-10.json`), `usd_rfq_hit_rate` fitted to synthetic labels with the declared slope band, declared `usd_rfq_competition`, `usd_synthetic_market`, `usd_hedge_cost`. The preset schema gains `assumptions` (DR-P5-01).
- Documentation: LEARN.md Section 10, COOKBOOK.md recipes 17 to 19 (the earlier planned items 17 and 19 move to 21 and 22), API_SPEC.md Section 16, ARCHITECTURE back-test section, experiment lifecycle diagram, DR-P5-01 to DR-P5-07.

Phase 6, post-trade and operations (branch `claude/p6-posttrade-hardening-3e4054`).

- `rates-messages`: `PostTradeBust` (template 100) and `TradeAmend` (101) with enums `TradeSource`, `BustReason`, `AmendReason`; no existing message changed (DR-P6-01).
- `rates-posttrade`: position keeper over journaled ExecutionReports and RFQ fills with duplicate, late, bust and amendment handling and an audit trail; weighted-average-cost positions per book, account and instrument; order records in the RTS 6 Annex II layout with a five-year retention archive; `MarkSource` and `CarrySource` with fixtures, official end-of-day P&L under a written marking policy and its reconciliation to back-tester attribution; clearing (swaps) and settlement (bonds) state machines with fail cost; daily reconciliation with classified and explained breaks; synthetic chaos day; restart procedure with venue status hooks; quote-stuffing and self-trade surveillance detectors; end-of-day report generator.
- `rates-ops`: health, readiness, metrics and control endpoints on the JDK HTTP server; Prometheus text exporter; YAML configuration validated against a JSON Schema at start; journaled kill switch; stress replayer at twice peak rate with coordinated-omission-corrected latency.
- `deploy/`: Dockerfiles for the trading, venue-simulator and report processes, compose file, Prometheus configuration and alert rules, Grafana dashboard, blue/green switch, deployment rehearsal, owner checklist.
- `.github/workflows/ops.yml`: configuration schema, dashboard and alert lint, RTS 6 inventory check, image builds when Docker is available, process-mode rehearsal with the kill switch.
- Tools: `validate_config.py`, `lint_dashboards.py`, `check_inventory.py`, `journal_query.py`.
- Documentation: LEARN Sections 12 and 14, COOKBOOK recipe 20, API_SPEC Section 2.1 and Section 17, ARCHITECTURE post-trade and operations sections, docs/compliance (RTS 6 inventory, algorithm inventory and self-assessment templates), docs/runbooks (six incident classes, start and end of day, warm-standby design, post-incident template), two Mermaid diagrams, DR-P6-01 to DR-P6-07.

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
