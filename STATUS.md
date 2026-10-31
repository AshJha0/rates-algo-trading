# Status

Updated 2026-10-31.

## Phase table

Plan-days are the unit the plan was written in. Wall-clock days are calendar days with two to three working sessions per day. Gate status is one of: not started, in progress, gate open (work done, acceptance pending), closed.

| Phase | Scope | Planned plan-days | Planned wall-clock days | Actual wall-clock days | Started | Gate status |
|---|---|---|---|---|---|---|
| P0 | Foundations: clock and timers, SBE schema v1, journal and replayer, Python package, docs, CI | 10 | 1 | in progress | 2026-10-31 | in progress |
| P1 | Analytics: conventions, curves, swaps, bonds, futures, QuantLib goldens | 16 | 1 to 1.5 | 0.3 (about 7 hours elapsed on 2026-10-31, including two pauses) | 2026-10-31 | gate open |
| P2 | Venues: FIX 4.4, SBE book feed, D2C RFQ, D2D CLOB with workup, SEF rules, event loop | event core half (W2): 9; venue half: set by its session | event core half: 0.75 | event core half: 0.3 (7 h elapsed, about 2.5 h working) | 2026-10-31 | in progress (event core half: gate open) |
| P2 (W3 venues) | Venue half: FIX 4.4 codec and session, three venue simulators, SBE book feed, reference data, market data quality, conformance suites | 9 | 0.75 | 0.3 | 2026-10-31 | gate open |
| P3 | Quoting engine, risk engine, hedger | 18 | 1.5 | 0.3 | 2026-10-31 | gate open: all P3 gates pass locally on the branch; CI and merge pending |
| P4 | Smart order router | 8 | 0.5 to 1 | 1 (one working session, on branch `claude/p4-router-43caad`) | 2026-10-31 | gate open |
| P5 | Back-tester with pre-registration (W7), Python quant research (W10); latency jobs | 18 | 1.5 | 0.3 (branch work, 2026-10-31) | 2026-10-31 | gate open |
| P6 | Documentation, regulation mapping, knowledge check, release | 16 (this branch: 11) | 1 | 0.3 for the branch share (see below) | 2026-10-31 | in progress: post-trade, compliance inventory, operations and regulation chapters done on the branch; knowledge check, final documentation pass and release after integration |
| R2 | Wiring: one trading process from the phase modules (`rates-app`), smoke test, replay-hash and back-test bindings, deploy, docs | not planned in plan-days | 1 (as briefed) | about 0.1 (one session of about 2.5 hours on 2026-10-31) | 2026-10-31 | gate open: mvn verify, Python, docs and rehearsal green locally; CI pending |

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
- jqwik properties for dates, day counts, conventions and schedules (`DatePropertiesTest`, nine properties, 10,881 tries per run: 200, 181, 1,500, 1,000, 2,000, 2,000, 2,000, 1,000 and 1,000 as printed by jqwik).
- `rates-analytics` line coverage 96.1 percent (floor 85); SpotBugs clean; ruff, mypy strict, pytest (63 passed on the P1 branch at its gate; 86 on main after the R2 review, 2026-10-31) and `mkdocs build --strict` clean.

The difference from plan is attributed to reusing QuantLib's published conventions line by line for calendars, schedules and yields, which removed most of the debugging a fresh implementation needs.

### P4 gate

Planned: 8 plan-days, 0.5 to 1 wall-clock day. Actual: one working session on 2026-10-31 (about 6.5 hours of wall-clock time from 01:00 to 07:30, including a pause for a model switch; build and test time is part of it), so within the planned wall-clock range. Acceptance evidence, all from `mvn -B -ntp verify` on JDK 25.0.5, Windows 11, 2026-10-31: state machine property tests green (`ChildOrderBookPropertiesTest`, `RouterChaosPropertiesTest`); router beats single-venue aggressive by 2.8780 bps (95% interval 2.7210 to 2.9855) and the even split by 2.7065 bps (2.5309 to 2.8272) in scenario `ust10y_three_venue_v1` (`BacktestTest`; after the DR-R2R-04 cost-model fix the same test gives 2.9388 [2.7792, 3.0631] and 2.7673 [2.5802, 2.9026], API_SPEC.md Section 15.6); every decision rebuilt from a replayed journal and re-evaluated to the same mantissas (`DecisionReplayTest`); 0 bytes allocated over 60 parents after warm-up (`RouterAllocationTest`); JaCoCo line floor met and SpotBugs clean for `rates-router`. The gate closes when the same jobs are green in CI on the combined branch.

### P5 gate (W7 back-testing, W10 research)

Planned: 18 plan-days, 1.5 wall-clock days. Actual: one session on 2026-10-31, from about 01:00 to 08:00 UTC including a pause while the coordinating session changed models, so about 0.3 calendar days elapsed; active working time was not measured. Review and CI time are not included: the branch is pushed for the coordinator's combined pull request and has not run in GitHub Actions yet.

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

Actual: started 2026-10-31 01:01 UTC, paused from about 01:25 to 06:25 UTC by the coordinating session (model change and budget pause), finished about 07:45 UTC; working wall-clock about 1 hour 50 minutes, measured from the session's commit times. CI time is not included: the branch has not run in GitHub Actions.

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

Planned: 9 plan-days, 0.75 wall-clock days. Actual: work ran from about 00:55 to 08:30 UTC on 2026-10-31 (first and last commits on the branch `claude/p2-venues-3d7318`), of which about five hours were a pause requested by the owner, so about 2.5 hours of working time, or 0.3 wall-clock days on this table's definition. The difference is attributed to building against fixed contracts (SBE schema, journal, clock) and to the conformance runner, which found the two interoperability faults early (QuoteCancelType 5 is not FIX 4.4; cancel acks must alias the original ClOrdID). Reviewer and CI time are not included and have not happened yet.

Evidence for the venue part of the P2 gate, from `mvn -B -ntp verify` on JDK 25.0.5, Windows 11, 2026-10-31: `CertSuiteTest` 16 scripts green across the three venues and every flow; `SyntheticDayTest` 568 RFQs with zero sequence errors; cancel-on-disconnect in `d2c-cancel-on-disconnect`, `clob-cancel-on-disconnect` and `ChaosTest`; gap recovery in `ChaosTest` (session resend and feed snapshot); `MarketDataQualityTest` property test; `AllocationTest` zero bytes over one million messages; JaCoCo line floor 0.85 met per module; SpotBugs clean. The gate closes when the same jobs pass in GitHub Actions on Linux and Windows.

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

### Round 2 wiring gate

Planned: one wall-clock day as briefed. Actual: about 2.5 hours in one session on 2026-10-31, of which roughly an hour was reading the phase modules' interfaces and an hour finding why the first smoke runs did not trade (the sync check, the start time, the spread; DR-R2W-09, DR-R2W-13). The work was faster than planned because every phase had built to pinned interfaces with stubs; the bindings were adapters, not redesigns.

| Check | Where | Result |
|---|---|---|
| Whole process for ten simulated minutes, seed 7 | `SmokeTest`, recipe 22 | 43 RFQs, 43 quotes, 25 fills, 261 hedge parents, 266 hedge fills, 29,040 book updates, 7,200 curve messages |
| Kill switch through the control endpoint pulls everything | `SmokeTest` | 0 live quotes, 0 router children, nothing resting at the CLOB, KillSwitch on the control ring |
| Positions from the journals, reconciled with both venues | `SmokeTest` | 291 trades in 9 positions; 291 ours, 291 theirs, 291 matched, 0 breaks |
| Replay hash of the smoke day | `SmokeTest` | 97,044 inbound frames, 308 of 308 outbound messages, hash equal |
| Replay hash of a day run with the loops on their own threads | `BacktestBindingTest` | equal in every run (258, 256 and 103 outbound messages in three runs; the threaded day is not reproducible step for step) |
| Daily report on the recorded journal | `BacktestBindingTest`, recipe 22 | written and deterministic; recipe 22: 43 RFQs, 22 client fills, 268 hedge fills |
| Pricing binding against direct analytics | `PricingBindingTest` | par rate and PV within 1e-12, ladder within 1e-12 of the parallel DV01 |
| Feed gap and lost session pull quotes through the loop | `VenueBindingTest` | pulled, cancelled at the venue, quoting resumed |
| Loop allocation with the real desk | `WiredLoopAllocationTest` | windows after warm-up within 1,024 bytes, at least one 0 (DR-R2W-12) |
| Hot-path rules on the process classes | `AppHotPathRulesTest` | met |
| Process-mode rehearsal with rates-app | `python deploy/rehearsal.py --mode process` | 12 checks passed (2026-10-31, Windows 11) |
| `LoopAllocationTest` and `HotPathRulesTest` in rates-core | `mvn -B -ntp verify` | unchanged, met |
Re-run on 2026-10-31, branch `claude/r2-review-6e9f43`, after the risk-reducing exemption started counting working reservations (DECISIONS.md DR-R2R-09): `MillionRfqTest` 1,000,000 RFQs, 533,266 quotes, 55,600 fills, 3,298 hedge parents, 3,288 risk rejects, 0 violations; `ChaosTest` 10,000 scenarios, 9,269 kills and 643 resets, 0 invariant failures. Hedge parents that would carry the book through zero once other working parents fill are now checked against the limits and some are refused, so less of the envelope is reserved and more RFQs find capacity. The hedger and allocation rows are unchanged.

## Open items

- P0: local verification done on 2026-10-31 (mvn verify on JDK 25.0.5 and JDK 27: 50 Java tests at P0, 459 on main at 4facfef, coverage floor met, SpotBugs clean; ruff, mypy, pytest 16 passed; mkdocs build --strict clean; cookbook recipes recorded and checked by tools/check_cookbook.py). The gate closes when the same jobs are green in GitHub Actions on Linux and Windows.
- P0: the repository is private until the owner is satisfied (DR-001 amendment); the site deploys once it is public and the Pages source is set to "GitHub Actions" (docs/GITHUB_PAGES.md).
- P6 (branch): bind the interfaces in API_SPEC.md Section 17.14 at integration; build the Aeron replication and quoting lease (DR-P6-06); APA publication, the Part 43 message and the pre-execution credit check are listed as planned in docs/compliance/RTS6_INVENTORY.md.
- R2 (deferred): bind `MarkSource`, `CarrySource`, `BusinessCalendar`, `VenueStatusPort` and `QuotingControl` (API_SPEC.md 17.14) beyond fixtures; tail the journals into the position keeper live instead of after the run; a futures simulator for the routers and the hedger (DR-R2W-08); a trade feed for the routers' queue model; a second currency and so a second loop; the CLOB quoter is built but not enabled; the SEF simulator is not connected; reconcile the router on reconnect (`SmartOrderRouter.reconcile` with an OrderMassStatusRequest); retire one of the two cancel message pairs (DR-R2W-04).
- R2 (findings outside its scope): `Venues.T0` is documented as 13:30Z, 08:30 New York, but is 14:30Z, 09:30 New York; the D2C arrival peak is at 08:30, so tests starting at `T0` see the base rate only.
