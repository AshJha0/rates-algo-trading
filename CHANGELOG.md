# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. Before 1.0, minor versions may change contracts, and each such change has a decision record.

## [Unreleased]

### Added

Phase 0 foundations.

- `rates-common`: `Clock` interface (`nanoTime`, `epochNanos`), `SystemClock`, `ManualClock` with `advance`, hashed `TimerWheel` with `schedule`, `cancel`, `poll` firing in deadline-then-id order.
- `rates-messages`: SBE schema v1 (`rates-schema-v1.xml`, schema id 1, version 0) with Heartbeat, BookUpdate, CurveInput, QuoteRequest, Quote, QuoteResponse, NewOrder, ExecutionReport, RiskLimitBreach and KillSwitch; codecs generated at build by sbe-tool.
- `rates-journal`: append-only memory-mapped journal (`RAJ1` format), `JournalWriter`, `JournalReader`, `Replayer` with FNV-1a 64 chain-hash verification.
- `research/ratesresearch` 0.0.1: `DataSource` and `SOURCES`, `sha256_of`, `write_manifest`, `load_manifest`, `fnv1a64` matching the journal chain hash.
- Documentation: README, LEARN (Sections 1 and 11 written, others outlined), COOKBOOK (three P0 recipes), API_SPEC (Sections 0 to 4 pinned), ARCHITECTURE, BENCHMARKS methodology, AI_USE_POLICY and change log, GITHUB_PAGES, four Mermaid diagrams, DECISIONS DR-001 to DR-008, STATUS, CONTRIBUTING, SECURITY, data/SOURCES.md, data/presets.yaml.
- MkDocs Material site built with `--strict` in CI.
- CI on Linux and Windows with JDK 25 gating and a JDK 27 informational leg; Python and docs jobs.

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
