# Tutorial 02: run the v2 desk profile in process and read its status

Goal: run the opt-in v2 desk (simulator v2's four venues, pricing v2, strategy v2, the ladder router) on a seeded day, and read every field its status shows.

Background: [LEARN.md Section 9](../../LEARN.md#9-smart-order-routing-in-fixed-income) ("One market, three books") and [Section 11](../../LEARN.md#11-low-latency-java-single-writer-off-heap-replay) (determinism). To operate it, use the [v2 profile runbook](../runbooks/v2-profile.md); the design is [DESK_V2_WIRING.md](../research/DESK_V2_WIRING.md).

Nothing about the default desk changes when you do this, and no recorded figure was made with the v2 profile.

## Step 1. The shortest route: the smoke day

**Not run here.** `SmokeV2Test` starts the whole v2 desk in one JVM on one seed at 09:00 New York, steps thirty simulated minutes, pulls the kill switch, reconciles with every venue and replays the journals.

```bash
mvn -B -ntp -pl rates-app -am test -Dtest=SmokeV2Test -Dsurefire.failIfNoSpecifiedTests=false
```

It has two tests. The first is the ordinary day. The second silences both Treasury feeds after three minutes, so the planner is left with the future. Both print structural counts and no P&L, and a line `status v2:` with the status JSON of the ordinary day. The counts recorded for the first in the changelog for 1.2.0: 20 RFQs, 20 quotes (6 swaps, 14 Treasuries), 9 won, 28 hedge parents, 63 hedge fills (38 on the first Treasury book, 25 on the second, none on futures), 72 of 72 trades reconciled. The test itself asserts 10,996, 10,312 and 7,204 book frames for the three books and 3,600 curve rounds, because a stepped day repeats frame for frame.

Why 09:00 and not 08:25? Strategy v2 pulls its quotes around the 08:30 release and little of the desk would be exercised. The window was chosen for coverage of the wiring.

## Step 2. The whole process, as deployed

**Not run here.** The rehearsal makes throwaway certificates, starts the process from `deploy/config/trading-v2.yaml`, checks it, kills it hard, restarts it on the same journals and replays them.

```bash
mvn -B -ntp -DskipTests package -pl rates-app -am
python deploy/rehearsal.py --mode process --profile v2
```

**Run on 2026-11-24:** `python deploy/rehearsal.py --help`, which lists `--mode`, `--java`, `--classpath`, `--timeout` and `--profile {default,v2}`.

Validate the configuration first. `python tools/validate_config.py` checks every file under `deploy/config`; it needs the `jsonschema` package. **Attempted on 2026-11-24** in an environment without that package, where it stopped with `ModuleNotFoundError`, so no result is recorded here.

The rehearsal prints one `PASS` or `FAIL` line per check. Look for `v2: three order-book venues usable`, `v2: status names the v2 profile`, `v2: every curve input fed`, `v2: restarted process still runs the v2 profile` and `v2: journals replay to the recorded outbound stream under the recorded selection`. The last line is `rehearsal (process mode, v2 profile): passed`, or the number of failed checks.

## Step 3. Read the status

`GET /control/status` needs the `X-Control-Token` header, over HTTPS on the deployed configuration. The fields, from `TradingProcess.statusJson`:

| Field | Read it as |
|---|---|
| `quoting`, `killSwitch` | may the desk quote; has a kill been engaged |
| `liveQuotes`, `liveChildOrders` | what the loops believe is working; after a halt `liveQuotes` is zero only when the withdrawal was confirmed, and otherwise at least the gateways' count of what is unconfirmed |
| `quotesSent`, `fills`, `hedgesRouted`, `groups` | counts, not P&L |
| `simulatedNanos` | how far simulated time has moved since the start |
| `halted`, `withdrawn`, `haltReason` | whether something stopped the process, and whether the withdrawal was confirmed |
| `recovered`, `recoveredFrames`, `journalTornTails`, `outboundCompleted` | what a restart found |
| `reconciling`, `bookSynced`, `ready` | still asking venues what they hold; feeds whole; ready to trade |
| `profile` (v2) | market, pricing and router in use: `SIM_V2`, `V2`, `LADDER` |
| `unfedCurveInputs` (v2) | curve inputs still on their start values; 0 once the first curve round is in |
| `venues` (v2) | each order-book venue (102 and 103 Treasuries, 104 futures) with `usable` and `bookAgeMillis`, -1 when the age is unknown. The D2C venue is a session and has no entry |
| `spreadPv01` (v2) | the Treasury-to-SOFR spread ladder on the eight tenors; displayed only, nothing limits or hedges it |

A monitor that reads only `ready` misses two states: one venue with `usable` false while the others trade, and `unfedCurveInputs` above zero, when part of the curve is still an opening value.

## Step 4. Stop it, and what a confirmed kill means

`POST /control/kill` answers 200 only when all four venues report nothing of ours working. A 503 is an incident: follow the [limit breach](../runbooks/limit-breach.md) or [venue disconnect](../runbooks/venue-disconnect.md) runbook and check open orders at each venue.

## Step 5. The journal remembers the desk

The process writes its selection to `desk.selection` beside the journals before anything is journaled. Two consequences.

A restart that selects another desk is refused: standard error carries `restart refused, process not started:` and both selections (`TradingProcessMain.selectionProblem`).

Replay builds the desk from the file. **Not run here**, with the class path of [tutorial 01](01-first-backtest-day.md) and the journal directory of a run in place of `JOURNAL_DIR`:

```bash
java $OPENS -cp "$CP" io.ratesalgo.app.TradingProcessMain --replay JOURNAL_DIR
```

It prints `desk selection:` and the components, then one line per group ending in `match`, or in `MISMATCH at` and the outbound sequence of the first difference. The exit status is 0 only if every group matches.

## What to expect, and what not

- A threaded live day does not repeat from run to run. Do not compare two live runs frame by frame; replay each against itself.
- A day with no futures hedge is normal at the declared costs.
- A fill of an order the router had released is booked and counted (`DeskGroup.lateFills`; [LEARN.md Section 9](../../LEARN.md#9-smart-order-routing-in-fixed-income)). The count is not in the status JSON.
- None of this says whether the v2 strategy makes money. That question belongs to a registered experiment ([tutorial 03](03-registered-experiment.md)), and none has been run.
