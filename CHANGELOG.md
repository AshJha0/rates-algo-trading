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

Phase 3 quoting, risk and hedging (branch `claude/p3-quoting-risk-hedge-70e454`).

- `rates-api`: `InstrumentDirectory`, `FairValueSource`, `BookSource`, `SensitivityProvider`, `HedgeOrderSink`, `OutboundSink`; value types `Dv01Vector`, `Instrument`, `KeyRateTenors`, `Decimal9`; in-memory and recording stubs.
- `rates-risk`: `RiskEngine` with an incremental key-rate PV01 ladder per currency, basis, swap-spread and curve-spread exposures, daily P&L, reservations, pre-trade checks in a fixed order and edge-triggered post-trade alerts; `RiskLimits`; `TokenBucket`; `KillSwitchController` with a four-eyes reset.
- `rates-hedge`: `BoxQpSolver` (accelerated projected gradient), `HedgePlanner` (variance against cost), `Hedger` (staged hedging with a decay target and axes), `HedgeAnalytics` (slippage against arrival mid, residual series, basis attribution hook), `HedgeCostModel` and `TableHedgeCostModel`, `KeyRateCovariance`.
- `rates-quoting`: `QuotingEngine` (RFQ decisions, lifecycle controls, duplicate detection, throttles, feedback learning, toxicity), `FairValueEngine` (microprice, futures signal, validated signals), `HitRateModel` and `HitRatePreset`, `SpreadModel`, client tiers with control-plane review, `PackageQuoter`, `ClobQuoter`, `QuotingEventHandler`, `Desk`.
- SBE schema v1: `QuoteCancel` (60), `OrderCancel` (61), `CancelReason`, `LimitKind` MessageRate and FatFinger (DR-P3-04).
- Cookbook recipes 14 to 16; LEARN Sections 7 and 8; API_SPEC Sections 12 to 14; decision records DR-P3-01 to DR-P3-12; diagrams of the RFQ decision flow and the kill-switch state machine.
