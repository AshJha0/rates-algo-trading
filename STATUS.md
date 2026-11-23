# Status

Updated 2026-11-23. Version 1.1.0 is drafted in CHANGELOG.md (dated 2026-11-23); its tag follows the fix round.

The only tag so far is v1.0.0, on 8e6ae10 (the merge of pull request #6, 2026-11-01). Every phase gate and rounds 1 and 2 are closed, and pull requests #1 to #9 are merged. An in-depth review on 2026-11-23 ([docs/reviews/REVIEW_2026-11-23.md](docs/reviews/REVIEW_2026-11-23.md)) found that the P&L figures are fixed by declared simulator constants, that parts of the safety path fail open, and that this file and the governance documents had gone stale; the fix round of 2026-11-23 (last row of the table) answers the safety, routing, reference-data, limits and ledger findings and leaves the rest to later rounds of the review's plan. What is not done is under "Open items", in README.md "Limits" and in the review's follow-up section.

The gate entries below are historical. Their figures were measured on the date they give and have not been re-run since, unless a later date is given. Where an entry said CI was pending, that was true when it was written; every branch named here was later merged, and CI first went green on main at 4facfef on 2026-10-31 (P0 gate).

## Phase table

Plan-days are the unit the plan was written in. Wall-clock days are calendar days with two to three working sessions per day. Gate status is one of: not started, in progress, gate open (work done, acceptance pending), closed.

| Phase | Scope | Planned plan-days | Planned wall-clock days | Actual wall-clock days | Started | Gate status |
|---|---|---|---|---|---|---|
| P0 | Foundations: clock and timers, SBE schema v1, journal and replayer, Python package, docs, CI | 10 | 1 | 0.3 (merged with round 1, pull request 1, 2026-10-31) | 2026-10-31 | closed: CI green on main at 4facfef |
| P1 | Analytics: conventions, curves, swaps, bonds, futures, QuantLib goldens | 16 | 1 to 1.5 | 0.3 (about 7 hours elapsed on 2026-10-31, including two pauses) | 2026-10-31 | closed (pull request 1) |
| P2 (W2 event core) | Event loop, ring-buffer contract, replay, latency harness | 9 | 0.75 | 0.3 (7 h elapsed, about 2.5 h working) | 2026-10-31 | closed (pull request 1) |
| P2 (W3 venues) | FIX 4.4 codec and session, three venue simulators, SBE book feed, reference data, market data quality, conformance suites | 9 | 0.75 | 0.3 | 2026-10-31 | closed (pull request 1) |
| P3 | Quoting engine, risk engine, hedger | 18 | 1.5 | 0.3 | 2026-10-31 | closed (pull request 1) |
| P4 | Smart order router | 8 | 0.5 to 1 | 0.3 (one working session of about 6.5 hours) | 2026-10-31 | closed (pull request 1) |
| P5 | Back-tester with pre-registration (W7), Python quant research (W10); latency jobs | 18 | 1.5 | 0.3 | 2026-10-31 | closed (pull request 1) |
| P6 | Documentation, regulation mapping, knowledge check, release | 16 | 1 | 0.3 for the branch share (11 plan-days); about 0.1 for the final documentation pass and release (5 plan-days) | 2026-10-31 | closed with v1.0.0 (see "P6 remainder and release") |
| Round 1 integration | P0 to P6 merged into one branch, fixes found at integration | not planned | not planned | about 0.1 (pull requests 1 and 2, opened 08:26 and 11:15 UTC, merged 08:33 and 11:28 UTC) | 2026-10-31 | closed |
| Round 2 | Wiring (`rates-app`), journal write path, LEARN, recipes 23 to 34 and diagrams, desk features (`rates-strategy`), edge research, P&L audit, review, audit fix packages A1, A2, B, C, D, integration | not planned in plan-days; each workstream briefed as one session | 1 | 0.4 (first commit 12:11 UTC, merge ecfc591 at 21:33 UTC on 2026-10-31) | 2026-10-31 | closed: pull request 3 merged; per workstream below |
| Release pass | Documentation pass, band P&L risk, version 1.0.0 | part of P6 | part of P6 | see "P6 remainder and release" | 2026-10-31 | closed: pull request 4, merged 2026-11-01 11:16 UTC |
| Version 1 integration | Hedge slicing (V1H-001, V1H-002), recipes 35 and 36, Docker image build and rehearsal in CI, hosted-runner latency row, DMO finding, documentation pass | not planned | not planned | about 0.1 (pull request 5 opened 12:14, merged 14:06 UTC) | 2026-11-01 | closed: pull request 5, merged 2026-11-01 |
| Journal shutdown fix and v1.0.0 tag | Journal close waits while background threads make progress (DR-INT-10) | not planned | not planned | under 0.1 (pull request 6 opened 16:09, merged 16:44 UTC) | 2026-11-01 | closed: pull request 6; tag v1.0.0 on its merge 8e6ae10; GitHub release published 17:17 UTC |
| Audit re-run | AUDIT-21 to AUDIT-26: the production-desk audit re-run at v1.0.0, combined settings, one holdout look | not planned | not planned | about 0.05 (commits 07:58 to 08:48 UTC) | 2026-11-07 | closed: pull request 7, merged 2026-11-07 11:21 UTC; ledger seq 25 to 30 |
| Hedge carry and forward log | Risk carry, hedge latency sweep, carry-time limits V1C-001 and V1C-002; forward log (paper trading) on the `forward-log` branch | not planned | not planned | about 0.2 (commits 19:24 to 23:59 UTC) | 2026-11-07 | closed: pull request 8, merged 2026-11-08 00:10 UTC; ledger seq 31 and 32 |
| README ledger count | README ledger row count 32 | not planned | not planned | minutes | 2026-11-08 | closed: pull request 9, merged 2026-11-08 00:41 UTC |
| Review 2026-11-23 and fix round | Six read-only reviews; documentation (this file, README, site, governance); code fixes in parallel lanes (safety; routing and market data; pricing and reference data; risk and quoting; research, ledger and CI; restart recovery and transport security) | not planned | not planned | lanes ran on 2026-11-23 | 2026-11-23 | all code lanes merged on the integration branch, restart recovery and transport security last (7ca0342); release build: 887 Java tests in 152 classes across 21 modules and 121 Python tests (1 skipped), no failures, with the line (0.85) and branch (0.75) coverage floors, Error Prone and SpotBugs, from `mvn -o -B -ntp verify` and `pytest -q research` on Windows 11 with JDK 25.0.5 on 2026-11-23; `mypy` was not run locally and is checked in CI; not yet merged to main or tagged. Finding by finding: [docs/reviews/REVIEW_2026-11-23.md](docs/reviews/REVIEW_2026-11-23.md), "Follow-up" |

## Estimate versus actual

Filled at each gate. Each entry records the planned and actual figures, the reviewer and CI time separately from working time, and what the difference is attributed to.

### P0 gate

Planned: 10 plan-days, 1 wall-clock day. Actual: built on 2026-10-31 alongside P1 to P6 and merged with them in pull request 1 (opened 08:26, merged 08:33 UTC). Pull request 2 (4facfef, opened 11:15, merged 11:28 UTC) fixed the loop allocation check for Linux CI (DR-INT-02), and the gate closed when CI went green on main at 4facfef on Linux and Windows. The difference from plan is attributed to the pinned contracts (SBE schema, journal, clock) being written first, so the other phases built against them in parallel.

### P1 gate (workstream W1, analytics)

Planned: 16 plan-days, 1 to 1.5 wall-clock days. Actual: started and finished on 2026-10-31, about 7 hours elapsed from the first commit of the session to the push, including two pauses for a model change. Branch `claude/p1-analytics-df37ab`. Acceptance (P1 gate), measured locally on 2026-10-31 with JDK 25.0.5 on Windows 11 (`mvn -B -ntp verify`); CI on Linux and Windows was pending then and passed on main at 4facfef:

- 224-instrument QuantLib 1.44 golden passes (`AnalyticsGoldenTest`): swap PV within 1.6e-15 of notional, par rates within 3.5e-16, bond yields within 7.3e-16, conversion factors exact; API_SPEC.md Section 7.2 lists every figure.
- Treasury worked examples: all 84 note and bond and 332 bill auctions of 2024 reproduce to the published digits (`PublishedExamplesTest`). No UK DMO golden test: the DMO formulae paper, fetched on 2026-11-01, gives the conventional gilt formulae without a numerical worked example (DR-V1G-02).
- Curve round trip within 1e-10 for all three interpolations (worst 9.3e-14); incremental rebuild equals full rebuild within 1e-10 (worst 7.5e-14) (`CurveBuilderTest`).
- Key-rate sum equals parallel within 1e-9 relative; AD versus 1bp bump within 1e-4 (worst 1.2e-8).
- Compounded legs reproduce the 2024 SOFR, EUR STR and SONIA published indices to the last digit (`CompoundedIndexHoldoutTest`).
- jqwik properties for dates, day counts, conventions and schedules (`DatePropertiesTest`, nine properties, 10,881 tries per run: 200, 181, 1,500, 1,000, 2,000, 2,000, 2,000, 1,000 and 1,000 as printed by jqwik).
- `rates-analytics` line coverage 96.1 percent (floor 85); SpotBugs clean; ruff, mypy strict, pytest (63 passed on the P1 branch at its gate; 86 on main after the R2 review, 2026-10-31) and `mkdocs build --strict` clean.

The difference from plan is attributed to reusing QuantLib's published conventions line by line for calendars, schedules and yields, which removed most of the debugging a fresh implementation needs.

### P4 gate

Planned: 8 plan-days, 0.5 to 1 wall-clock day. Actual: one working session on 2026-10-31 (about 6.5 hours of wall-clock time from 01:00 to 07:30, including a pause for a model switch; build and test time is part of it), so within the planned wall-clock range. Acceptance evidence, all from `mvn -B -ntp verify` on JDK 25.0.5, Windows 11, 2026-10-31: state machine property tests green (`ChildOrderBookPropertiesTest`, `RouterChaosPropertiesTest`); router beats single-venue aggressive by 2.8780 bps (95% interval 2.7210 to 2.9855) and the even split by 2.7065 bps (2.5309 to 2.8272) in scenario `ust10y_three_venue_v1` (`BacktestTest`; after the DR-R2R-04 cost-model fix the same test gives 2.9388 [2.7792, 3.0631] and 2.7673 [2.5802, 2.9026], API_SPEC.md Section 15.6); every decision rebuilt from a replayed journal and re-evaluated to the same mantissas (`DecisionReplayTest`); 0 bytes allocated over 60 parents after warm-up (`RouterAllocationTest`); JaCoCo line floor met and SpotBugs clean for `rates-router`. The gate closed when the same jobs went green in CI on main at 4facfef.

### P5 gate (W7 back-testing, W10 research)

Planned: 18 plan-days, 1.5 wall-clock days. Actual: one session on 2026-10-31, from about 01:00 to 08:00 UTC including a pause while the coordinating session changed models, so about 0.3 calendar days elapsed; active working time was not measured. Review and CI time are not included. When this entry was written the branch had not run in GitHub Actions; it was merged in pull request 1. There was no independent review.

Gate checks, all run locally on 2026-10-31 on Windows 11 with JDK 25.0.5 and Python 3.12.10:

| Check | Where | Result |
|---|---|---|
| Determinism: a second run of a synthetic day reproduces P&L to the cent, same output hash and tables | `DeterminismTest` | passed |
| Ledger refuses an unregistered run and a holdout overlap | `LedgerTest`, recipe 19 | passed |
| Attribution reconciles to position P&L below one cent on every run | `AttributionReconciliationTest`, `test_java_outputs_reconcile` | passed |
| First pre-registered experiment recorded whatever its result | `data/ledger/ledger.jsonl` line 1, `FirstExperimentTest` | recorded: not supported |
| Hit-rate model out-of-sample log-loss below the constant baseline | preset `usd_rfq_hit_rate` | 0.6146 against 0.6900 |
| Daily report runs unattended | `ReportTest` (`ReportCli`) | passed |
| Python: ruff, ruff format, mypy strict, pytest, notebooks, cookbook | CI python job commands | 38 tests passed at the P2 gate on its branch (86 on main after the R2 review, 2026-10-31); 5 notebooks executed; 6 recipes match |
| Coverage floor and SpotBugs on `rates-backtest` | `mvn -B -ntp verify` | floor met, 0 bugs |

What the difference between plan and actual is attributed to: the plan assumed the venue simulators and the event loop would be available; building against synthetic journals and the back-tester's own outcome models removed that integration work from this phase and moved it to the coordinator's binding step.

Deferred from P5, with reasons: the latency job and its histogram (the report reads the harness JSON when it exists; no harness was built in this branch); binding to the P2 loop's replay mode and to the venue simulators (not available to this session); FINRA Treasury data (API needs an account; loader and synthetic fixture shipped); opening the P5-001 holdout (kept unread; it opens once, in a later run).

### P6 branch share (post-trade, compliance, operations)

Planned: 11 of the 16 P6 plan-days (W11 post-trade and books and records 5, compliance inventory with the stress and surveillance hooks 2, deployment and operations 3, LEARN Sections 12 and 14 1). The other 5 plan-days are the knowledge check, pitfalls and further reading, the final documentation pass and the v1.0.0 release, which follow integration.

Actual: started 2026-10-31 01:01 UTC, paused from about 01:25 to 06:25 UTC by the coordinating session (model change and budget pause), finished about 07:45 UTC; working wall-clock about 1 hour 50 minutes, measured from the session's commit times. CI time is not included; the branch was merged in pull request 1.

Attributed to: one session building against its own fixtures without waiting on other phases; the parts that depend on P1 to P5 are interfaces with fixtures (API_SPEC.md Section 17.14).

Local verification on 2026-10-31, Windows 11, JDK 25.0.5: `mvn -B -ntp verify` green with the coverage floor and SpotBugs; `rates-posttrade` 35 tests and `rates-ops` 14 tests; `python deploy/rehearsal.py --mode process` passed all 12 checks; `tools/validate_config.py`, `tools/lint_dashboards.py`, `tools/check_inventory.py` and `tools/check_cookbook.py` clean; `mkdocs build --strict` clean. Docker was not available on the machine, so the images were not built locally.

### P2 event core half (workstream W2)

Planned 9 plan-days and about 0.75 wall-clock days. Actual, 2026-10-31: first commit 01:26 UTC, last 07:55 UTC on branch `claude/p2-event-core-37016e`, about 7 hours elapsed of which about 2.5 hours were working time (the session was paused by the coordinator between 01:30 and 06:30), so 0.3 wall-clock days. Measurement runs took 25 minutes of that (four 6-minute latency runs). Acceptance status:

| Gate item | Result | Where |
|---|---|---|
| Zero bytes allocated on the loop thread over one million messages | met at the gate: 0 bytes after warm-up; since DR-INT-02 the test measures three windows of one million messages, the last must allocate 0 bytes and the second and third at most 1,024 each (the per-window bytes are printed only on failure) | `LoopAllocationTest`, `rates-core` |
| Replay-hash equality property | met | `LoopPropertiesTest`, `LoopReplayTest` |
| ArchUnit hot-path rules | met, each rule also proven to fail on a violating fixture | `HotPathRulesTest` |
| Segmented journal round trip and cross-segment corruption | met | `SegmentedJournalTest` |
| Coverage floor 0.85, SpotBugs clean | met: rates-core 0.98, rates-journal 0.96 lines at the gate; on main at 4facfef (2026-10-31) rates-core 0.985 and rates-journal 0.928 (321 of 346 lines), still above the floor | `mvn verify`, `target/site/jacoco/jacoco.csv` |
| Latency harness in CI with JSON artifact | wired, informational | `.github/workflows/ci.yml` job `latency` |
| p50 under 20 us, p99.9 under 100 us in-process on the laptop | met with journaling off (0.2 us, 6.9 us); with journaling on p50 met (0.5 us), p99.9 not met (271 ms). Met with journaling on after R2 (next section) | docs/BENCHMARKS.md results |

### R2 journal write path (branch `claude/r2-journal-latency-ee2034`)

Task: take the journal write off the event loop's latency path so the P2 latency target holds with journaling on. Started 2026-10-31 about 11:25 UTC (UK time equals UTC on that date), code committed 12:11, pushed after the checks below; one working session, measurement runs included.

| Item | Result | Where |
|---|---|---|
| Cause found with evidence | time to safepoint from Agrona's page-touching zero-fill of each new segment (no safepoint poll in counted loops under ParallelGC), with a young collection about every 0.6 s from the harness's own gateway garbage | DR-R2J-01, docs/BENCHMARKS.md "Cause of the journaling-on tail", `docs/benchmarks/safepoint-evidence-20261031.md` |
| p50 under 20 us, p99.9 under 100 us, journaling on, in-process on the laptop | met in both modes when the generator kept to schedule: direct p50 0.2 us, p99.9 1.6 us; ring p50 0.1 us, p99.9 1.6 us; journaling off p99.9 1.1 us (P2: 271 ms with journaling on). Runs disturbed by other load on the laptop are recorded and discarded by the generator rule; one of them (direct) reached 275 us at p99.9, and a controlled-load run with journaling off reached 11.4 ms, so the remaining tail is operating-system scheduling of unpinned threads | docs/BENCHMARKS.md results |
| Zero bytes on the loop thread in both modes | met: `LoopAllocationTest` runs per mode with rolls inside the measured windows; 0 bytes in the harness runs | `rates-core` |
| Replay-hash equality in both modes | met: frame-for-frame property against the direct mode, `LoopReplayTest` and the replay property per mode | `RingJournalTest`, `LoopReplayTest`, `LoopPropertiesTest` |
| Default mode | `DIRECT` (meets the target; fewer threads; every appended frame survives a process crash) | DR-R2J-04 |
| JMH benchmark of the append path | `JournalModeAppendBenchmark` | docs/BENCHMARKS.md microbenchmarks |

Deferred: the same runs on a pinned Linux host with isolated cores (DR-005); a measured comparison of the modes at rates where one journal thread cannot keep up; locking the touched pages in memory (Windows `VirtualLock`, Linux `mlock`) so that the operating system cannot trim them under memory pressure; durability across an operating-system crash, which needs a decision on how often to force and from which thread.

### P2 venue half (W3)

Planned: 9 plan-days, 0.75 wall-clock days. Actual: work ran from about 00:55 to 08:30 UTC on 2026-10-31 (first and last commits on the branch `claude/p2-venues-3d7318`), of which about five hours were a pause requested by the owner, so about 2.5 hours of working time, or 0.3 wall-clock days on this table's definition. The difference is attributed to building against fixed contracts (SBE schema, journal, clock) and to the conformance runner, which found the two interoperability faults early (QuoteCancelType 5 is not FIX 4.4; cancel acks must alias the original ClOrdID). CI time is not included. There was no independent reviewer.

Evidence for the venue part of the P2 gate, from `mvn -B -ntp verify` on JDK 25.0.5, Windows 11, 2026-10-31: `CertSuiteTest` 16 scripts green across the three venues and every flow; `SyntheticDayTest` 568 RFQs with zero sequence errors; cancel-on-disconnect in `d2c-cancel-on-disconnect`, `clob-cancel-on-disconnect` and `ChaosTest`; gap recovery in `ChaosTest` (session resend and feed snapshot); `MarketDataQualityTest` property test; `AllocationTest` zero bytes over one million messages; JaCoCo line floor 0.85 met per module; SpotBugs clean. The gate closed when the same jobs passed in GitHub Actions on main at 4facfef.

### P3 gate

Planned: 18 plan-days, 1.5 wall-clock days. Actual: 0.3 wall-clock days, about 7 hours on 2026-10-31 from about 01:00 to 08:00 UTC in one working session, including one pause while the session's model was switched. The work ran in parallel with P1, P2, P4, P5 and P6 in separate sessions, against stubs of their interfaces, so binding to the real implementations (coordinator) is not included in the actual. The difference is attributed to the stubs: no time was spent on integration, data or calibration, which the plan-days include.

Gate results, from `mvn -B -ntp verify` on JDK 25.0.5, Windows 11, 2026-10-31, branch `claude/p3-quoting-risk-hedge-70e454`:

| Gate | Result | Source |
|---|---|---|
| One million simulated RFQs, zero limit violations at the sink | 1,000,000 RFQs, 476,215 quotes, 30,933 fills, 4,446 hedge parents, 0 violations | `MillionRfqTest` |
| DV01 ladder equals full revaluation to 1e-6 of notional | 300 random sequences, every pillar within tolerance | `RiskEngineTest.incrementalLadderEqualsFullRevaluation`, also `ChaosTest`, `MillionRfqTest` |
| Chaos test clean | 10,000 scenarios, 9,272 kills and 646 resets exercised, 0 invariant failures | `ChaosTest` |
| Kill switch exercised | state machine, listeners, four-eyes reset, kill during reset | `KillSwitchControllerTest`, `ChaosTest`, `QuotingEngineTest`, recipe 16 |
| Hedger beats immediate full hedging on variance and modelled cost | residual sd 10,352 at cost 62,418 against 647,435 at 111,862 | `HedgePlannerTest.costAwarePlanBeatsImmediateFullHedgeOnVarianceAndCost` |
| Allocation-free per-RFQ path after warm-up | five windows of 20,000 RFQs: 280, 0, 0, 0, 0 bytes (DR-P3-08) | `QuotingAllocationTest` |
| Coverage at or above the floor, SpotBugs clean | JaCoCo line floor 0.85 met and SpotBugs clean in every module | `mvn -B -ntp verify` |

Re-run on 2026-10-31, branch `claude/r2-review-6e9f43`, after the risk-reducing exemption started counting working reservations (DECISIONS.md DR-R2R-09): `MillionRfqTest` 1,000,000 RFQs, 533,266 quotes, 55,600 fills, 3,298 hedge parents, 3,288 risk rejects, 0 violations; `ChaosTest` 10,000 scenarios, 9,269 kills and 643 resets, 0 invariant failures. Hedge parents that would carry the book through zero once other working parents fill are now checked against the limits and some are refused, so less of the envelope is reserved and more RFQs find capacity. The hedger and allocation rows are unchanged.

### Round 2 wiring gate

Planned: one wall-clock day as briefed. Actual: about 2.5 hours in one session on 2026-10-31, of which roughly an hour was reading the phase modules' interfaces and an hour finding why the first smoke runs did not trade (the sync check, the start time, the spread; DR-R2W-09, DR-R2W-13). The work was faster than planned because every phase had built to pinned interfaces with stubs; the bindings were adapters, not redesigns.

| Check | Where | Result |
|---|---|---|
| Whole process for ten simulated minutes, seed 7 | `SmokeTest`, recipe 22 | 36 RFQs, 27 quotes, 16 fills, 210 hedge parents, 213 hedge fills with the review and audit fixes (DR-INT-07); after the review merge alone 39, 34, 22, 302 and 291 (DR-INT-05); before it 43 RFQs, 43 quotes, 25 fills, 261 hedge parents, 266 hedge fills, 29,040 book updates, 7,200 curve messages |
| Kill switch through the control endpoint pulls everything | `SmokeTest` | 0 live quotes, 0 router children, nothing resting at the CLOB, KillSwitch on the control ring |
| Positions from the journals, reconciled with both venues | `SmokeTest` | 291 trades in 9 positions; 291 ours, 291 theirs, 291 matched, 0 breaks |
| Replay hash of the smoke day | `SmokeTest` | 97,044 inbound frames, 308 of 308 outbound messages, hash equal |
| Replay hash of a day run with the loops on their own threads | `BacktestBindingTest` | equal in every run (258, 256 and 103 outbound messages in three runs; the threaded day is not reproducible step for step) |
| Daily report on the recorded journal | `BacktestBindingTest`, recipe 22 | written and deterministic; recipe 22: 36 RFQs, 14 client fills, 171 hedge fills (DR-INT-07) |
| Pricing binding against direct analytics | `PricingBindingTest` | par rate and PV within 1e-12, ladder within 1e-12 of the parallel DV01 |
| Feed gap and lost session pull quotes through the loop | `VenueBindingTest` | pulled, cancelled at the venue, quoting resumed |
| Loop allocation with the real desk | `WiredLoopAllocationTest` | windows after warm-up within 1,024 bytes, at least one 0 (DR-R2W-12) |
| Hot-path rules on the process classes | `AppHotPathRulesTest` | met |
| Process-mode rehearsal with rates-app | `python deploy/rehearsal.py --mode process` | 12 checks passed (2026-10-31, Windows 11) |
| `LoopAllocationTest` and `HotPathRulesTest` in rates-core | `mvn -B -ntp verify` | unchanged, met |

### Round 2 workstreams

No plan-day estimate was written for round 2. Each workstream was briefed as one session, and the round as one wall-clock day. Actuals are the first and last commit times of each branch's own commits (`git log <branch> --not 4facfef`, excluding merges and commits taken from other round 2 branches), 2026-10-31, UTC. They are elapsed time, not working time, and exclude CI and review.

| Workstream | Branch | Briefed | Actual elapsed | Commits | Gate evidence |
|---|---|---|---|---|---|
| Journal write path | `claude/r2-journal-latency-ee2034` | one session | 12:11 to 13:31, 1 h 20 min | 3 | section above; docs/BENCHMARKS.md |
| Process wiring (`rates-app`) | `claude/r2-wiring-c31ae4` | one wall-clock day | 12:49 to 13:50 for the commits; about 2.5 h of session (see "Round 2 wiring gate") | 3 | section below |
| LEARN | `claude/r2-learn-b5137b` | one session | 13:36 to 13:37 (written before the first commit) | 2 | `mkdocs build --strict` |
| Cookbook recipes 23 to 34 and diagrams | `claude/r2-cookbook-diagrams-656067` | one session | 13:36 to 13:57 | 2 | `RecipesTest`, `tools/check_cookbook.py` |
| Desk features (`rates-strategy`) | `claude/r2-business-6cda18` | one session | 13:37 to 14:31 | 4 | ledger seq 12 to 18; `DeskQuoterBaselineTest` |
| Edge research E1 to E6 | `claude/r2-edge-research-6aba2e` | one session | 13:30 to 14:11 | 5 | ledger seq 2 to 7; `research/tests/test_edge.py`; notebooks |
| P&L audit | `claude/r2-pnl-audit-e32be7` | one session | 14:18 to 14:36 | 3 | ledger seq 8 to 11; docs/research/PNL_AUDIT.md |
| Review fixes | `claude/r2-review-6e9f43` | one session | 13:26 to 15:19 | 18 | DR-R2R-01 onward; P3 re-run above |
| Fix package A1 (pricing models) | `claude/fix-a1-models-67fd05` | one session | 14:33 to 15:30 | 2 | `PricingModelsTest` |
| Fix package A2 (engine) | `claude/fix-a2-engine-8168e0` | one session | 14:33 to 18:08 | 4 | `AuditFixesTest` |
| Fix package B (CLOB) | `claude/fix-b-clob-68d6c2` | one session | 14:33 to 17:13 | 2 | CLOB tick and skew tests |
| Fix package C (controls) | `claude/fix-c-controls-1e7782` | one session | 14:33 to 18:51 | 3 | `RiskControlsTest`, `HedgeControlsTest`, `RouterControlsTest`, `DeskWiringTest` |
| Fix package D (back-tester) | `claude/fix-d-backtest-cdd6aa` | one session | 14:33 to 18:06 | 5 | `FixPackageDTest`; ledger seq 19 to 22 |
| Integration | `round2-integration` | part of the round | merges 14:00 to 20:32; pull request 3 merged 21:33 (ecfc591) | merges only | CI on the pull request and on main |

The round took about 9 h 20 min of elapsed time against the one wall-clock day briefed. The difference is attributed to two things. The phase modules had been built to pinned interfaces with stubs, so the wiring was adapters rather than redesigns. And the thirteen workstreams ran in parallel sessions; the fix packages waited on the audit, and the integration waited on the fix packages.

### P6 remainder and release (v1.0.0)

Planned: 5 of the 16 P6 plan-days (knowledge check, pitfalls and further reading, final documentation pass, release). Actual: one session on 2026-10-31, branch `release/v1.0.0`, from the round 2 merge at 21:33 UTC to the v1.0.0 merge. The knowledge check and the pitfalls had already come with LEARN in round 2.

Done in this pass:

- README rewritten, with measured results each tied to a source and a limits list. ARCHITECTURE covers the wired system and `rates-strategy`. CHANGELOG has a v1.0.0 section.
- PNL_AUDIT.md now matches the ledger's `code_dirty` flags. Stale lines in the diagrams, the diagrams page and API_SPEC were fixed. The P&L audit was added to the site.
- Daily-P&L volatility, Sharpe ratio and maximum drawdown across the assumption band (`Stats.pnlRisk`, `BandRisk`, `ratesresearch.stats.pnl_risk`). `ReportCli` and `ExperimentCli` write them, labelled as simulator consistency checks. Tests: `BandRiskTest`, `ReportTest`, `research/tests/test_pnl_risk.py`.
- `Desk` registers the package quoter with the kill switch (DR-FIX-A2 amendment in DECISIONS.md, `AuditFixesTest`).
- Versions 1.0.0 in every pom, `research/pyproject.toml` and `ratesresearch.__version__`.

The difference from plan is attributed to LEARN's knowledge check and pitfalls having come with round 2.

### Version 1 integration (branch `v1-integration`)

Started 2026-11-01 on main d89bfb1; merged to main as pull request 5 at 14:06 UTC the same day. Merged `claude/v1-hedge-f17740` (hedge slicing adopted as the back-tested desk default, V1H-001 supported at every band point; the cost-aware trigger V1H-002 not supported and opt-in; PNL_AUDIT items 1 and 10; ledger seq 23 and 24, no renumbering) and `claude/v1-gaps-ea4320` (COOKBOOK recipes 35 and 36, so every listed recipe is written; CI builds every image in `deploy/` and runs the rehearsal inside the trading image, DR-V1G-03; the CI latency job writes its percentiles to the job summary and BENCHMARKS has a GitHub-hosted Linux row, DR-V1G-04; the DMO finding, DR-V1G-02). The documentation pass in the same branch brought README, CHANGELOG, the diagrams and this file up to date.

### Pull request 6 and the v1.0.0 tag

`RingJournal.close()` and `SegmentedJournal.close()` wait while their background threads make progress and give up only after 60 s with none (DR-INT-10). Pull request 6 merged at 16:44 UTC on 2026-11-01; the tag v1.0.0 is on its merge commit 8e6ae10, and the GitHub release was published at 17:17 UTC. CHANGELOG.md dated 1.0.0 as 2026-10-31 until 2026-11-23; it now says 2026-11-01.

### Audit re-run (pull request 7)

AUDIT-21 to AUDIT-24 re-ran AUDIT-11 to AUDIT-14 at v1.0.0; AUDIT-25 tried six combined settings on the same 20 sessions; AUDIT-26 was the one look at 5 unopened sessions, and the chosen setting failed it. Ledger seq 25 to 30, recorded 2026-11-07 between 08:08 and 08:35 UTC; docs/research/PNL_AUDIT.md. AUDIT-22 to AUDIT-24 carry `code_dirty: true` for the same reason as the earlier audit rows. The review of 2026-11-23 counts these as further reads of the same 20 sessions, each recorded as look 1 (review Section 2.4).

### Hedge carry and forward log (pull request 8)

Risk carry time, an exploratory hedge latency sweep and two carry-time limits, V1C-001 and V1C-002, both not supported (ledger seq 31 and 32, DR-V1C-01 to DR-V1C-03). The forward log (DR-FWD-01 to DR-FWD-04): a scheduled workflow, `.github/workflows/forward.yml`, writes each Treasury business day to the `forward-log` branch. It is not a track record (README "Limits"). The prepared-roll allocation test was made deterministic in the same pull request. README's test count (639 Java, 114 Python) comes from a local run on this branch on 2026-11-07.

### Review 2026-11-23 and fix round

Six read-only reviews of main at 44c4f45 produced docs/reviews/REVIEW_2026-11-23.md. Its fix plan (Section 9) is ordered in rounds. Round 1 (documentation) is in this pull request: the site home page, this file, README "Measured results" and "Limits", the governance documents and the RTS 6 inventory statuses, the reviewer summary, and CHANGELOG 1.1.0. Round 2 (safety and correctness) and parts of rounds 3 and 7 ran as code lanes on 2026-11-23 and are merged on the integration branch: the out-of-band kill and supervisor (L1, L2), bounded FIX writes, connects and resends (L5, L7), the control endpoint (L12), lossless book deltas, health-gated routing, cancel rejects, router timeouts and the reconcile rule (S1 to S5, S8, S9), dated dates, the calendar, curve-input checks and futures specifications (A5, A6, A8, A9, A11), hedge protection, the risk-reducing exemption and busts (Q10, Q12, Q14), per-RFQ and per-client DV01 limits (opt-in), looks per data set and paired methods, the forward summary without a ratio, and supply-chain and coverage items (E6, E7, E8, E10). CHANGELOG.md 1.1.0 lists them; the review's follow-up section gives each finding's status. Restart recovery (L6) and transport security for FIX and the control endpoint (L12) merged last (7ca0342); their limits are in README "Limits". No estimate was written in plan-days; the lanes ran on 2026-11-23. The release build ran 887 Java tests in 152 classes across 21 modules and 121 Python tests (1 skipped), no failures, with the line (0.85) and branch (0.75) coverage floors, Error Prone and SpotBugs, from `mvn -o -B -ntp verify` and `pytest -q research` on Windows 11 with JDK 25.0.5 on 2026-11-23; `mypy` was not run locally and is checked in CI. mypy --strict was not run locally for this round (CI runs it), and the new workflows (CodeQL, mutation, Dependabot) had not run on GitHub.

## Open items

- Repository settings, checked 2026-11-23 with `gh api`: the repository is public; the `main` ruleset is active (created 2026-11-01) with nine required status checks, 0 required approvals, strict status checks off, and deletion and force pushes blocked; the Pages source is GitHub Actions, so `docs.yml` deploys the site from main. The DR-001 amendment that kept the repository private is superseded (DECISIONS.md DR-REV-01). Turning on strict status checks is review item E5.
- Review 2026-11-23, after the fix round (docs/reviews/REVIEW_2026-11-23.md, "Follow-up", has the full table):
  - R3 simulator: P&L set by constants and hedging that cannot help (Sections 2.1 to 2.3), one liquidity provider (S7), paired primary tests for new registrations.
  - R4 pricing: circular swap fair value and the unused library (A1, A2), five pillars on eight buckets (A4), bond risk at market as the default (A3, opt-in now), valuation date (A7), independent goldens (A10), the futures windows and ticks not re-read (A11).
  - R5 strategy: Q1 to Q6 and Q8, the back-test's handling of QuoteWithdraw (Q7), and whether to switch on the opt-in controls of 1.1.0 (cover learning, widening after the clamp, fractional-lot carry, the desk-sized limits), each by a pre-registered run.
  - R6 SOR and latency evidence: the sweep and the 8-tick collar (S6), feed arbitration and heartbeat, `CompositeBuilder`, tick sizes and self-match across routers (S10 to S13), fair router baselines, the wired-desk benchmark and threading (L3, L4), L8 to L10, the FIX send-path copy (L11).
  - R7 assurance: allocation tests at other compilation states (L13), property tests for quoting and strategy (E6), gating on mutation scores, image digests, lock hashes and a bill of materials (E7).
  - Owner settings: strict status checks (E5); CodeQL default setup off and Issues enabled (deploy/OWNER_CHECKLIST.md).
  - Restart recovery (L6) and transport security (L12), merged in 1.1.0 with stated limits: reconciliation with resting orders tested at `DeskGroup` level only; a journal set the replay cannot explain (outbound longer than inbound, or ending in a LoopError) refuses to start with no automatic truncation; the route journal can miss decisions made at the crash instant; the FIX sequence store survives a process crash, not a power loss; the TLS KeyUpdate path is untested; the Docker rehearsal was not run for this change; the simulators restart with the process and keep a file store under `<journalDir>/venues`.
  - Recipe 22's recorded output (`rates-cookbook/src/test/resources/expected/recipe22.txt`, quoted in COOKBOOK.md) was re-recorded with the 1.1.0 status keys in d61bd2f.
- P6: the Section 17.14 interfaces are bound by round 2 except those listed under R2 below; build the Aeron replication and quoting lease (DR-P6-06); APA publication, the Part 43 message and the pre-execution credit check are listed as absent in docs/compliance/RTS6_INVENTORY.md.
- R2 (deferred): bind `MarkSource`, `CarrySource`, `BusinessCalendar`, `VenueStatusPort` and `QuotingControl` (API_SPEC.md 17.14) beyond fixtures; tail the journals into the position keeper live instead of after the run; a futures simulator for the routers and the hedger (DR-R2W-08); a trade feed for the routers' queue model; a second currency and so a second loop; the CLOB quoter is built but not enabled; the SEF simulator is not connected; retire one of the two cancel message pairs (DR-R2W-04).
- R2 (findings outside its scope): `Venues.T0` is documented as 13:30Z, 08:30 New York, but is 14:30Z, 09:30 New York; the D2C arrival peak is at 08:30, so tests starting at `T0` see the base rate only.
- Audit-fix controls ship off by default and are not turned on by the wired process: package A1 (hit-rate learning and cover averaging take effect only once the engine is fed outcomes and cover gaps), package C (router filled-adverse and message costs, on only in `RouterPresets.globalWithControls`), package B (CLOB inventory skew, off until called). Turning them on is a pre-registered experiment, not a config edit.
- AUDIT-11 to AUDIT-14 ran at 04edc03, before DR-INT-05; AUDIT-21 to AUDIT-24 re-ran them at v1.0.0 on 2026-11-07 (docs/research/PNL_AUDIT.md). AUDIT-02 to 04 and AUDIT-12 to 14 carry `code_dirty: true` (docs/research/PNL_AUDIT.md).
- PNL_AUDIT item 10 stays open: the cost-aware hedge trigger (V1H-002, ledger seq 24) was not supported and is opt-in only (DR-V1H-02).
- Hedge slicing is the default of the back-tested desk (`DeskFactory.deskDefault()`, DR-V1H-03); the wired process's hedger and routers are unchanged.
- `VenueSession`'s retry is in the deployment config schema since 1.1.0 (`fix.reconnect`), and a session that was active and then dropped reconnects automatically (DR-INT-09, API_SPEC.md 18.5).
