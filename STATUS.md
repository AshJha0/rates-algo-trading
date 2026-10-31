# Status

Updated 2026-10-31.

## Phase table

Plan-days are the unit the plan was written in. Wall-clock days are calendar days with two to three working sessions per day. Gate status is one of: not started, in progress, gate open (work done, acceptance pending), closed.

| Phase | Scope | Planned plan-days | Planned wall-clock days | Actual wall-clock days | Started | Gate status |
|---|---|---|---|---|---|---|
| P0 | Foundations: clock and timers, SBE schema v1, journal and replayer, Python package, docs, CI | 10 | 1 | in progress | 2026-10-31 | in progress |
| P1 | Analytics: conventions, curves, swaps, bonds, futures, QuantLib goldens | 16 | 1 to 1.5 | 0.3 (about 7 hours elapsed on 2026-10-31, including two pauses) | 2026-10-31 | gate open |
| P2 | Venues: FIX 4.4, SBE book feed, D2C RFQ, D2D CLOB with workup, SEF rules, event loop | to be set at P0 gate | | | | not started |
| P3 | Quoting engine, risk engine, hedger | to be set at P0 gate | | | | not started |
| P4 | Smart order router | 8 | 0.5 to 1 | 1 (one working session, on branch `claude/p4-router-43caad`) | 2026-10-31 | gate open |
| P5 | Back-tester with pre-registration, latency jobs | to be set at P0 gate | | | | not started |
| P6 | Documentation, regulation mapping, knowledge check, release | to be set at P0 gate | | | | not started |

## Estimate versus actual

Filled at each gate. Each entry records the planned and actual figures, the reviewer and CI time separately from working time, and what the difference is attributed to.

### P0 gate

Not yet reached. Planned: 10 plan-days, 1 wall-clock day, started 2026-10-31.

### P1 gate (workstream W1, analytics)

Planned: 16 plan-days, 1 to 1.5 wall-clock days. Actual: started and finished on 2026-10-31, about 7 hours elapsed from the first commit of the session to the push, including two pauses for a model change. Branch `claude/p1-analytics-df37ab`. Acceptance (P1 gate), measured locally on 2026-10-31 with JDK 25.0.5 on Windows 11 (`mvn -B -ntp verify`); CI on Linux and Windows pending:

- 224-instrument QuantLib 1.44 golden passes (`AnalyticsGoldenTest`): swap PV within 1.6e-15 of notional, par rates within 3.5e-16, bond yields within 7.3e-16, conversion factors exact; API_SPEC.md Section 7.2 lists every figure.
- Treasury worked examples: all 84 note and bond and 332 bill auctions of 2024 reproduce to the published digits (`PublishedExamplesTest`). No DMO worked example: the DMO site refused scripted downloads.
- Curve round trip within 1e-10 for all three interpolations (worst 9.3e-14); incremental rebuild equals full rebuild within 1e-10 (worst 7.5e-14) (`CurveBuilderTest`).
- Key-rate sum equals parallel within 1e-9 relative; AD versus 1bp bump within 1e-4 (worst 1.2e-8).
- Compounded legs reproduce the 2024 SOFR, EUR STR and SONIA published indices to the last digit (`CompoundedIndexHoldoutTest`).
- jqwik properties for dates, day counts, conventions and schedules (`DatePropertiesTest`, nine properties, about 11,500 tries per run).
- `rates-analytics` line coverage 96.1 percent (floor 85); SpotBugs clean; ruff, mypy strict, pytest (63 passed) and `mkdocs build --strict` clean.

The difference from plan is attributed to reusing QuantLib's published conventions line by line for calendars, schedules and yields, which removed most of the debugging a fresh implementation needs.
### P4 gate

Planned: 8 plan-days, 0.5 to 1 wall-clock day. Actual: one working session on 2026-10-31 (about 6.5 hours of wall-clock time from 01:00 to 07:30, including a pause for a model switch; build and test time is part of it), so within the planned wall-clock range. Acceptance evidence, all from `mvn -B -ntp verify` on JDK 25.0.5, Windows 11, 2026-10-31: state machine property tests green (`ChildOrderBookPropertiesTest`, `RouterChaosPropertiesTest`); router beats single-venue aggressive by 2.8780 bps (95% interval 2.7210 to 2.9855) and the even split by 2.7065 bps (2.5309 to 2.8272) in scenario `ust10y_three_venue_v1` (`BacktestTest`); every decision rebuilt from a replayed journal and re-evaluated to the same mantissas (`DecisionReplayTest`); 0 bytes allocated over 60 parents after warm-up (`RouterAllocationTest`); JaCoCo line floor met and SpotBugs clean for `rates-router`. The gate closes when the same jobs are green in CI on the combined branch.

## Open items

- P0: local verification done on 2026-10-31 (mvn verify on JDK 25.0.5 and JDK 27: 50 Java tests, coverage floor met, SpotBugs clean; ruff, mypy, pytest 16 passed; mkdocs build --strict clean; cookbook recipes recorded and checked by tools/check_cookbook.py). The gate closes when the same jobs are green in GitHub Actions on Linux and Windows.
- P0: the repository is private until the owner is satisfied (DR-001 amendment); the site deploys once it is public and the Pages source is set to "GitHub Actions" (docs/GITHUB_PAGES.md).
