# Status

Updated 2026-10-31.

## Phase table

Plan-days are the unit the plan was written in. Wall-clock days are calendar days with two to three working sessions per day. Gate status is one of: not started, in progress, gate open (work done, acceptance pending), closed.

| Phase | Scope | Planned plan-days | Planned wall-clock days | Actual wall-clock days | Started | Gate status |
|---|---|---|---|---|---|---|
| P0 | Foundations: clock and timers, SBE schema v1, journal and replayer, Python package, docs, CI | 10 | 1 | in progress | 2026-10-31 | in progress |
| P1 | Analytics: conventions, curves, swaps, bonds, futures, QuantLib goldens | to be set at P0 gate | | | | not started |
| P2 | Venues: FIX 4.4, SBE book feed, D2C RFQ, D2D CLOB with workup, SEF rules, event loop | event core half (W2): 9; venue half: set by its session | event core half: 0.75 | event core half: 0.3 (7 h elapsed, about 2.5 h working) | 2026-10-31 | in progress (event core half: gate open) |
| P3 | Quoting engine, risk engine, hedger | to be set at P0 gate | | | | not started |
| P4 | Smart order router | to be set at P0 gate | | | | not started |
| P5 | Back-tester with pre-registration, latency jobs | to be set at P0 gate | | | | not started |
| P6 | Documentation, regulation mapping, knowledge check, release | to be set at P0 gate | | | | not started |

## Estimate versus actual

Filled at each gate. Each entry records the planned and actual figures, the reviewer and CI time separately from working time, and what the difference is attributed to.

### P0 gate

Not yet reached. Planned: 10 plan-days, 1 wall-clock day, started 2026-10-31.

### P2 event core half (workstream W2)

Planned 9 plan-days and about 0.75 wall-clock days. Actual, 2026-10-31: first commit 01:26 UTC, last 07:55 UTC on branch `claude/p2-event-core-37016e`, about 7 hours elapsed of which about 2.5 hours were working time (the session was paused by the coordinator between 01:30 and 06:30), so 0.3 wall-clock days. Measurement runs took 25 minutes of that (four 6-minute latency runs). Acceptance status:

| Gate item | Result | Where |
|---|---|---|
| Zero bytes allocated on the loop thread over one million messages | met: 0 bytes after warm-up | `LoopAllocationTest`, `rates-core` |
| Replay-hash equality property | met | `LoopPropertiesTest`, `LoopReplayTest` |
| ArchUnit hot-path rules | met, each rule also proven to fail on a violating fixture | `HotPathRulesTest` |
| Segmented journal round trip and cross-segment corruption | met | `SegmentedJournalTest` |
| Coverage floor 0.85, SpotBugs clean | met: rates-core 0.98, rates-journal 0.96 lines | `mvn verify` |
| Latency harness in CI with JSON artifact | wired, informational | `.github/workflows/ci.yml` job `latency` |
| p50 under 20 us, p99.9 under 100 us in-process on the laptop | met with journaling off (0.2 us, 6.9 us); with journaling on p50 met (0.5 us), p99.9 not met (271 ms) | docs/BENCHMARKS.md results |

## Open items

- P0: local verification done on 2026-10-31 (mvn verify on JDK 25.0.5 and JDK 27: 50 Java tests, coverage floor met, SpotBugs clean; ruff, mypy, pytest 16 passed; mkdocs build --strict clean; cookbook recipes recorded and checked by tools/check_cookbook.py). The gate closes when the same jobs are green in GitHub Actions on Linux and Windows.
- P0: the repository is private until the owner is satisfied (DR-001 amendment); the site deploys once it is public and the Pages source is set to "GitHub Actions" (docs/GITHUB_PAGES.md).
