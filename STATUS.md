# Status

Updated 2026-10-31.

## Phase table

Plan-days are the unit the plan was written in. Wall-clock days are calendar days with two to three working sessions per day. Gate status is one of: not started, in progress, gate open (work done, acceptance pending), closed.

| Phase | Scope | Planned plan-days | Planned wall-clock days | Actual wall-clock days | Started | Gate status |
|---|---|---|---|---|---|---|
| P0 | Foundations: clock and timers, SBE schema v1, journal and replayer, Python package, docs, CI | 10 | 1 | in progress | 2026-10-31 | in progress |
| P1 | Analytics: conventions, curves, swaps, bonds, futures, QuantLib goldens | to be set at P0 gate | | | | not started |
| P2 | Venues: FIX 4.4, SBE book feed, D2C RFQ, D2D CLOB with workup, SEF rules, event loop | to be set at P0 gate | | | | not started |
| P3 | Quoting engine, risk engine, hedger | to be set at P0 gate | | | | not started |
| P4 | Smart order router | to be set at P0 gate | | | | not started |
| P5 | Back-tester with pre-registration, latency jobs | to be set at P0 gate | | | | not started |
| P6 | Documentation, regulation mapping, knowledge check, release | 16 (this branch: 11) | 1 | 0.3 for the branch share (see below) | 2026-10-31 | in progress: post-trade, compliance inventory, operations and regulation chapters done on the branch; knowledge check, final documentation pass and release after integration |

## Estimate versus actual

Filled at each gate. Each entry records the planned and actual figures, the reviewer and CI time separately from working time, and what the difference is attributed to.

### P0 gate

Not yet reached. Planned: 10 plan-days, 1 wall-clock day, started 2026-10-31.

### P6 branch share (post-trade, compliance, operations)

Planned: 11 of the 16 P6 plan-days (W11 post-trade and books and records 5, compliance inventory with the stress and surveillance hooks 2, deployment and operations 3, LEARN Sections 12 and 14 1). The other 5 plan-days are the knowledge check, pitfalls and further reading, the final documentation pass and the v1.0.0 release, which follow integration.

Actual: started 2026-10-31 01:01 UTC, paused from about 01:25 to 06:25 UTC by the coordinating session (model change and budget pause), finished about 07:45 UTC; working wall-clock about 1 hour 50 minutes, measured from the session's commit times. CI time is not included: the branch has not run in GitHub Actions.

Attributed to: one session building against its own fixtures without waiting on other phases; the parts that depend on P1 to P5 are interfaces with fixtures (API_SPEC.md Section 17.14).

Local verification on 2026-10-31, Windows 11, JDK 25.0.5: `mvn -B -ntp verify` green with the coverage floor and SpotBugs; `rates-posttrade` 35 tests and `rates-ops` 14 tests; `python deploy/rehearsal.py --mode process` passed all 12 checks; `tools/validate_config.py`, `tools/lint_dashboards.py`, `tools/check_inventory.py` and `tools/check_cookbook.py` clean; `mkdocs build --strict` clean. Docker was not available on the machine, so the images were not built locally.

## Open items

- P0: local verification done on 2026-10-31 (mvn verify on JDK 25.0.5 and JDK 27: 50 Java tests, coverage floor met, SpotBugs clean; ruff, mypy, pytest 16 passed; mkdocs build --strict clean; cookbook recipes recorded and checked by tools/check_cookbook.py). The gate closes when the same jobs are green in GitHub Actions on Linux and Windows.
- P0: the repository is private until the owner is satisfied (DR-001 amendment); the site deploys once it is public and the Pages source is set to "GitHub Actions" (docs/GITHUB_PAGES.md).
- P6 (branch): bind the interfaces in API_SPEC.md Section 17.14 at integration; map P2 `TradeBust` (44) onto `PostTradeBust`; build the Aeron replication and quoting lease (DR-P6-06); APA publication, the Part 43 message and the pre-execution credit check are listed as planned in docs/compliance/RTS6_INVENTORY.md.
