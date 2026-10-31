# Changelog

All notable changes to this project are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once it reaches 1.0. Before 1.0, minor versions may change contracts, and each such change has a decision record.

## [Unreleased]

### Added

Phase 4 smart order router (W6).

- `rates-router`: venue cost model (half spread, square-root impact, fees, queue fill probability, latency) with versioned parameters and a declared preset in `data/presets.yaml` (`router`); greedy convex allocation across venues with minimum sizes, lots and participation caps; randomised schedule (seeded); passive first with a time limit, escalation, re-routing on book moves, deadline; never crosses its own orders; child order state machine handling duplicate, stale, out-of-order and late reports, with mass status reconciliation; decision journaling; in-memory CLOB venues; back-test harness with two baselines and block-bootstrap intervals.
- `rates-messages`: RouteDecision (80), RouteDecisionVenue (81), ChildOrderTransition (82) and the enums VenueKind, RouteReason, VenueExclusion, ChildState, TransitionCause; schema version unchanged (DR-P4-01).
- COOKBOOK recipe 17 (route a hedge order and read the markout report), LEARN Section 9, API_SPEC Section 15, ARCHITECTURE router section, child order lifecycle diagram, DR-P4-01 to DR-P4-06.

Phase 0 foundations.

- `rates-common`: `Clock` interface (`nanoTime`, `epochNanos`), `SystemClock`, `ManualClock` with `advance`, hashed `TimerWheel` with `schedule`, `cancel`, `poll` firing in deadline-then-id order.
- `rates-messages`: SBE schema v1 (`rates-schema-v1.xml`, schema id 1, version 0) with Heartbeat, BookUpdate, CurveInput, QuoteRequest, Quote, QuoteResponse, NewOrder, ExecutionReport, RiskLimitBreach and KillSwitch; codecs generated at build by sbe-tool.
- `rates-journal`: append-only memory-mapped journal (`RAJ1` format), `JournalWriter`, `JournalReader`, `Replayer` with FNV-1a 64 chain-hash verification.
- `research/ratesresearch` 0.0.1: `DataSource` and `SOURCES`, `sha256_of`, `write_manifest`, `load_manifest`, `fnv1a64` matching the journal chain hash.
- Documentation: README, LEARN (Sections 1 and 11 written, others outlined), COOKBOOK (three P0 recipes), API_SPEC (Sections 0 to 4 pinned), ARCHITECTURE, BENCHMARKS methodology, AI_USE_POLICY and change log, GITHUB_PAGES, four Mermaid diagrams, DECISIONS DR-001 to DR-008, STATUS, CONTRIBUTING, SECURITY, data/SOURCES.md, data/presets.yaml.
- MkDocs Material site built with `--strict` in CI.
- CI on Linux and Windows with JDK 25 gating and a JDK 27 informational leg; Python and docs jobs.
