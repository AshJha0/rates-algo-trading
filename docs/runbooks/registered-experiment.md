# Running a registered simulator v2 experiment detached

A registered experiment on simulator v2 runs for hours (about 19 hours for R5-FAM-001 on eight workers, by its own compute budget). It must survive the console that started it, and it must not be looked at before it finishes. The scripts are in `tools/sim-v2/` (Windows; their README has the full steps). The harness: `DeskExperiment` (rates-app), `ExperimentRunner` (rates-backtest); architecture in docs/ARCHITECTURE.md, "Experiment pipeline".

## Before launching

1. **The registration is signed.** The runner refuses a registration whose `status` starts with `draft` (R5-FAM-001 is `draft_unsigned` today and has not run). Signing is the owner's step: remove the draft status, sign, commit, before the run (API_SPEC.md Section 16.8). Nothing is run and nothing is recorded on a refusal.
2. **A clean checkout at the signed commit.** The runner refuses uncommitted changes.
3. **An otherwise idle machine.** Check that free memory exceeds the workers' heaps plus the coordinator (eight workers at the default `--worker-heap` of 1536m plus 1 GB). Other heavy jobs on the same machine slow the run and can starve it.
4. **An output directory outside git.** The runner refuses one inside a work tree. The convention is `C:\Work\Claude\rates-runs\<id>` (tools/sim-v2/README.md).
5. **Build and freeze the classpath** (Git Bash): `tools/sim-v2/build_classpath.sh`, with `MVN` pointing at Maven. The launcher copies the class directories into `OUTDIR\lib`, so rebuilding the checkout cannot change a run in progress.

## Optional: timing-only pilot

`tools\sim-v2\launch_detached.cmd OUTDIR pilot --prereg FILE --pilot 4 --timing-only --workers 4`

It runs on the pilot seed base, prints timings and structural counts, writes and reads no P&L, and makes no ledger row. The pilot base supports no claim.

## Launch

`tools\sim-v2\launch_detached.cmd OUTDIR run --prereg FILE --workers 8`

The launcher starts the run detached from the console: closing the window, a Ctrl-C or a tool restart does not stop it. Write `--workers` out, so a relaunch on another machine does not change it silently.

## Watch

`tools\sim-v2\status.cmd OUTDIR` prints sessions done, parts in progress, hours left, the heartbeat and any failure. It reads file names, timing files and markers only, never a session's results. Do not open `sessions\*.json` or anything else that carries P&L while the run is alive: the registration allows one look, when the run has finished.

| What you see | Meaning | Action |
|---|---|---|
| `OUTDIR\heartbeat.txt` rewritten at least once a minute; `OUTDIR\runner.log` growing | running | none |
| heartbeat older than a few minutes, no marker | the run stopped without exiting (machine sleep, killed process) | relaunch with the same command line |
| `OUTDIR\FAILED` | a session failed twice; no new sessions are started | read `OUTDIR\logs\<seed>.log`, fix the cause if it is not in the code under test, relaunch with `--retry-failed` |
| `OUTDIR\FAILED.runner` | the runner exited with the code inside | read `runner.log`; relaunch with the same command line |
| `OUTDIR\DONE` | finished | go to "After the run" |

## Relaunch

The same command line resumes: finished sessions are kept (`sessions\<seed>.json`), and a session that was in progress resumes at its next arm (`parts\<seed>\`). A changed journal fails that session. A relaunch does not add a look: the summary and the ledger rows are written once, after the last session.

## After the run

The run writes `summary.json` once, after the last session, and appends the ledger rows in the same step: one per hypothesis plus the descriptive rows (D0, D1), each with the family id and one look for the family run. The Benjamini-Hochberg table is in `runner.log`; print it again with `LedgerCli --ledger data/ledger/ledger.jsonl --bh family:ID` using the run's `java.args` (tools/sim-v2/README.md, step 5), or `python -m ratesresearch.prereg bh`. A second run of the same family on the same seed base is refused.

Every number the run produces is a statement about the simulator under its declared assumptions (DR-008). Report it with its interval, its guard metrics and its verdict, whatever the verdict.
