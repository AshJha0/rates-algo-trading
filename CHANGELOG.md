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

Phase 6, post-trade and operations (branch `claude/p6-posttrade-hardening-3e4054`).

- `rates-messages`: `PostTradeBust` (template 100) and `TradeAmend` (101) with enums `TradeSource`, `BustReason`, `AmendReason`; no existing message changed (DR-P6-01).
- `rates-posttrade`: position keeper over journaled ExecutionReports and RFQ fills with duplicate, late, bust and amendment handling and an audit trail; weighted-average-cost positions per book, account and instrument; order records in the RTS 6 Annex II layout with a five-year retention archive; `MarkSource` and `CarrySource` with fixtures, official end-of-day P&L under a written marking policy and its reconciliation to back-tester attribution; clearing (swaps) and settlement (bonds) state machines with fail cost; daily reconciliation with classified and explained breaks; synthetic chaos day; restart procedure with venue status hooks; quote-stuffing and self-trade surveillance detectors; end-of-day report generator.
- `rates-ops`: health, readiness, metrics and control endpoints on the JDK HTTP server; Prometheus text exporter; YAML configuration validated against a JSON Schema at start; journaled kill switch; stress replayer at twice peak rate with coordinated-omission-corrected latency.
- `deploy/`: Dockerfiles for the trading, venue-simulator and report processes, compose file, Prometheus configuration and alert rules, Grafana dashboard, blue/green switch, deployment rehearsal, owner checklist.
- `.github/workflows/ops.yml`: configuration schema, dashboard and alert lint, RTS 6 inventory check, image builds when Docker is available, process-mode rehearsal with the kill switch.
- Tools: `validate_config.py`, `lint_dashboards.py`, `check_inventory.py`, `journal_query.py`.
- Documentation: LEARN Sections 12 and 14, COOKBOOK recipe 20, API_SPEC Section 2.1 and Section 17, ARCHITECTURE post-trade and operations sections, docs/compliance (RTS 6 inventory, algorithm inventory and self-assessment templates), docs/runbooks (six incident classes, start and end of day, warm-standby design, post-incident template), two Mermaid diagrams, DR-P6-01 to DR-P6-07.
