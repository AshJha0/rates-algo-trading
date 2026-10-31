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

Phase 2, event core (workstream W2).

- `rates-core` (new): `EventLoop` single-writer duty cycle over Agrona `ManyToOneRingBuffer` inbound rings and one `OneToOneRingBuffer` per gateway, with dispatch by SBE template id, inbound and outbound journaling at a latched cycle time, timer polling with journaled clock ticks, agents and outbound backpressure events; `LoopRunner` with thread naming, optional Linux core pinning (`net.openhft:affinity`, optional), idle strategies, day hooks and LoopError journaling; `LoopReplay` with frame-by-frame outbound chain-hash comparison; `RingBufferContract`, `InboundPublisher`, `ControlMessages` (core control schema 100); `AnchoredClock`, `EventClock`, `LoopCounters`; `chaos.FaultInjector` for drop, duplicate, delay, reorder and gateway disconnect.
- `rates-journal`: `SegmentedJournal` and `SegmentedJournalReader` (segments `name.000001.raj` onward, one chain across segments), `FrameSource`, `Replayer.replay(FrameSource, FrameHandler)`. Public API of the single-file classes unchanged.
- `rates-bench` (new): fixed-rate latency harness with HdrHistogram and coordinated-omission correction, JSON and Markdown reports, `TickToWireProbe` hook; JMH microbenchmarks of the ring publish and the journal append; `exec:exec@latency` and `exec:exec@jmh`.
- Tests: zero-allocation check over one million messages, ArchUnit hot-path rules with a violating fixture, jqwik properties for exactly-once in-order processing under faults and for replay hash equality, segmented journal round trip and cross-segment corruption.
- CI: informational latency job that uploads its JSON report.
- Docs: API_SPEC Section 1.5 and Section 9, LEARN Section 11 subsection on the ring-buffer contract and replay comparison, COOKBOOK recipes 12 and 19, ARCHITECTURE event loop section, BENCHMARKS first results, `docs/diagrams/event_loop_cycle.mmd`, DECISIONS DR-P2C-01 to DR-P2C-10.
