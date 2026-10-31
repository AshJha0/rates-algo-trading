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
| P6 | Documentation, regulation mapping, knowledge check, release | to be set at P0 gate | | | | not started |

## Estimate versus actual

Filled at each gate. Each entry records the planned and actual figures, the reviewer and CI time separately from working time, and what the difference is attributed to.

### P0 gate

Not yet reached. Planned: 10 plan-days, 1 wall-clock day, started 2026-10-31.

## Open items

- P0: local verification done on 2026-10-31 (mvn verify on JDK 25.0.5 and JDK 27: 50 Java tests, coverage floor met, SpotBugs clean; ruff, mypy, pytest 16 passed; mkdocs build --strict clean; cookbook recipes recorded and checked by tools/check_cookbook.py). The gate closes when the same jobs are green in GitHub Actions on Linux and Windows.
- P0: GitHub Pages source must be set to "GitHub Actions" in the repository settings before the site deploys (docs/GITHUB_PAGES.md).
