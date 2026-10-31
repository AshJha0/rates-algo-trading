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

Phase 2, venue half (workstream W3).

- `rates-refdata`: securities master for Treasuries, Gilts and Bunds with the on-the-run and when-issued rule, swap templates with MAT flags and block thresholds, futures specifications, party master, auction calendar, `CalendarSource` with a fixture implementation; checksummed fixtures in `data/refdata/v1` and their generator `tools/gen_refdata_fixtures.py`.
- `rates-fix`: allocation-free FIX 4.4 codec (`FixParser`, `FixWriter`, `FixFramer`, exact decimal and timestamp conversion), engine-side `FixSession` (logon, heartbeat, test request, resend request answered by gap fill, sequence reset, gap detection), `TcpInitiator`, `VenueGateway` translating RFQ, order, quote status and market data flows to and from SBE, `OrderStateTracker` (duplicates, stale reports, late fills, busts).
- `rates-venues`: QuickFIX/J 3.0.2 simulators for a D2C multi-dealer RFQ venue, a D2D Treasury CLOB with workup, icebergs, minimum size and self-match prevention, and a SEF with RFQ-to-3, block thresholds and an FCM credit hub; cancel-on-disconnect on every venue; SBE book feed publisher and consumer with snapshot recovery; market data composite with crossed, locked, outlier and staleness checks and fixing republication; sixteen certification scripts and their runner; chaos and synthetic-day tests; standalone process entry points.
- SBE schema: messages MarketDataReject (40), OrderCancel (41), OrderReplace (42), QuoteCancel (43), TradeBust (44), enum MdRejectReason, value QuoteStatus.DoneAway (API_SPEC.md Section 2.1). Schema version unchanged (DR-P2V-02).
- Documentation: LEARN.md Section 6, COOKBOOK.md recipes 10 and 11, API_SPEC.md Sections 2.1, 10 and 11, docs/ARCHITECTURE.md venue layer, diagrams of the RFQ flow and the CLOB workup states, DR-P2V-01 to DR-P2V-12.
