# Simulator v2 runs: build, launch detached, watch

Scripts for long simulator v2 experiment runs on Windows. Every run directory is outside git
(the runner refuses one inside a work tree); the convention is `C:\Work\Claude\rates-runs\<id>`.

| Step | Command (from the repository root) | Leaves |
|---|---|---|
| 1. Build and freeze the classpath | `MVN=/c/Users/ashis/tools/apache-maven-3.9.9/bin/mvn tools/sim-v2/build_classpath.sh` (Git Bash) | `rates-app\target\sim-v2-cp.txt` |
| 2. Launch detached | `tools\sim-v2\launch_detached.cmd OUTDIR run\|pilot --prereg FILE [options]` (cmd) | `OUTDIR\lib\` (the frozen classes), `java.args`, `launch.txt` |
| 3. Watch | `tools\sim-v2\status.cmd OUTDIR` | prints sessions done, parts in progress, hours left, heartbeat, failure |

While a run is alive it rewrites `OUTDIR\heartbeat.txt` at least once a minute and appends to
`OUTDIR\runner.log`. On exit it leaves `OUTDIR\DONE`, or `OUTDIR\FAILED.runner` with the exit code;
`OUTDIR\FAILED` is the runner's own marker for a session that failed twice (no new sessions are
started; read `OUTDIR\logs\<seed>.log`, then relaunch with `--retry-failed`). A killed run is
relaunched with the same command line: finished sessions are kept (`sessions\<seed>.json`), and a
session that was in progress resumes at its next arm (`parts\<seed>\`). The status command reads
file names, timing files and markers only, never a session's results.

## R5-FAM-001 (the Round 5 family)

The registration `research/prereg/R5-FAM-001.yaml` is unsigned: the runner refuses it until the
owner removes the draft status and commits it. Steps after signing, on an otherwise idle machine:

```
rem 0. a clean checkout at the signed commit (the runner refuses uncommitted changes)
rem 1. build and freeze (Git Bash):  MVN=/c/Users/ashis/tools/apache-maven-3.9.9/bin/mvn tools/sim-v2/build_classpath.sh
rem 2. optional timing check on the pilot base, no P&L written or read (about 4 minutes for all
rem    ten arms at 4 workers; add --arms baseline,legacy to time only some):
tools\sim-v2\launch_detached.cmd C:\Work\Claude\rates-runs\R5-FAM-001-timing pilot --prereg research/prereg/R5-FAM-001.yaml --pilot 4 --timing-only --workers 4
rem 3. the registered run (24,000 arm-sessions, 120.8 CPU-hours at the pilot's seconds: about 15
rem    to 20 hours with 8 workers on an idle 12-core machine; see the budget in the registration):
tools\sim-v2\launch_detached.cmd C:\Work\Claude\rates-runs\R5-FAM-001 run --prereg research/prereg/R5-FAM-001.yaml --workers 8
rem 4. watch:
tools\sim-v2\status.cmd C:\Work\Claude\rates-runs\R5-FAM-001
rem 5. when DONE is there: the run has appended ten ledger rows (H1 to H8, the description D0 of
rem    the baseline and D1 of legacy) and printed the Benjamini-Hochberg table of H1 to H8 to
rem    runner.log; print it again with
java @C:\Work\Claude\rates-runs\R5-FAM-001\java.args io.ratesalgo.backtest.prereg.LedgerCli --ledger data/ledger/ledger.jsonl --bh family:R5-FAM-001
```

`--workers 8` is the default pool on a 12-core machine (cores less four), written out so that a
relaunch on another machine does not change it silently. Each worker runs one session: one journal
per row, then the row's arms one after the other (ten on the central row; baseline and v2 on the
two sensitivity rows for sessions 0 to 999).

The arms (`DeskArms`): nine stand on one footing, the simulator v2 market and universe with
pricing v2 and the ladder router, and differ only in their strategy switches (`baseline` all off,
`v2` all on, six `v2_minus_*`, `cover_only`). The tenth, `legacy`, is the desk as built in 1.1.0
(recorded universe, pricing v1, touch router); it is run and described (ledger row D1), no
hypothesis may name it, and it is outside the Benjamini-Hochberg table. Each session record holds,
per arm, the structural declines (zero on the common footing; non-zero voids the run), the TY
fills too small to report (`futures_dust_fills`) and the loop's `unrouted_reports`; the status
command never reads them. Memory: the coordinator takes 1 GB, each worker
`--worker-heap` (default 1536m); check that free memory exceeds 8 x 1.5 GB plus the coordinator
before launching.

The run writes `summary.json` once, after the 2,000th session, and appends the ledger rows in the
same step. Nothing earlier reads a P&L.
