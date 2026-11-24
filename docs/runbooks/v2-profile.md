# Running the v2 desk profile

The v2 desk profile (1.2.0) runs simulator v2's four venues (a D2C venue, two Treasury CLOBs showing shares of one book, a futures book), pricing v2, strategy v2 and the ladder router on one loop. It is opt-in. The recorded desk (`trading-blue.yaml`, `trading-green.yaml`) is unchanged and is the one every recorded figure was made with; no recorded figure and no registered result comes from the v2 profile. Design: docs/research/DESK_V2_WIRING.md, Sections 1 to 12. Architecture: docs/ARCHITECTURE.md, "Version 1.2.0: the v2 desk profile".

## Start

1. **Configuration.** Use `deploy/config/trading-v2.yaml`, or add a `desk` section to another file: `profile: v2`, and optionally one component at a time on top of it (`market: standard|simV2`, `pricing: v1|v2`, `strategy: v1|v2`, `router: touch|ladder`; API_SPEC.md Section 17.11). Only the two whole profiles are run by tests at process level; a per-component selection is built and started but not tested end to end. Validate it: `python tools/validate_config.py` checks every `deploy/config/*.yaml`, the `desk` section included; the process applies the same check at start.
2. **Journal directory.** A fresh directory for a fresh day. The process writes the selection to `desk.selection` in the journal directory before anything is journaled. A start on a directory whose journals another selection wrote is refused with `configuration refused, process not started:` and the two selections on standard error; journals replay only through the desk that wrote them.
3. **Start** as for the recorded desk ([start-of-day.md](start-of-day.md), step 6). On simulator v2 the market opens at 09:00 New York (`TradingProcessMain.V2_START`), not 08:25.
4. **Check the status** (`/control/status`, token required). It carries `profile` (`market` SIM_V2, `pricing` V2, `router` LADDER), `unfedCurveInputs` (0 once the first curve round is in), `venues` (each order-book venue usable and its book age) and `spreadPv01` (the spread ladder, displayed only: nothing limits or hedges it).
5. **Markout store.** On a fresh start the process moves `markouts.end` (the store the last session wrote) to `markouts.start` in the journal directory and reads it. Do not edit or replace `markouts.start` while that day's journals may still be replayed: a replay builds the desk again and must read the same store.

Rehearsal of the whole cycle (start, kill, hard stop, restart on the same journals, replay with the recorded selection): `python deploy/rehearsal.py --mode process --profile v2`.

## Stop

- **Kill.** `POST /control/kill` halts every session. On the v2 profile it answers 200 only when all four venues report nothing of ours working; otherwise the sessions log out, the sockets are closed if they are still up, and the endpoint answers 503 with the state of each session. A 503 is an incident: follow [limit-breach.md](limit-breach.md) or [venue-disconnect.md](venue-disconnect.md) and check each venue's open orders in the journal.
- **End of day.** Stopping the process ends the session: the desk writes its markout store to `markouts.end`, then the journals close. End-of-day reconciliation runs against all four venues' trade registers (`V2Reconciliation`) in venue units: the futures venue counts contracts, the desk counts face.

## Restart on the same journals

Follow [restart.md](restart.md); on the v2 profile also:

- The configuration must select the same desk as `desk.selection`, or the restart is refused.
- `markouts.end` is **not** promoted on a restart: the desk rebuilt by replay must read the store the stopped process read.
- Every order-book session (CLOB 1, CLOB 2, futures) reconciles before its venue is used; the D2C session resumes its sequence numbers. With `fix.resetOnLogon: false` each venue keeps its FIX store under `journal/venues/{d2c,clob,clob2,fut}`.
- The simulated market after a restart is the seed's market taken alone to the restart time: it does not remember the impact of what the desk traded before it stopped, and the venues' trade registers start again. The end-of-day reconciliation of a restarted day covers the trades since the restart at the venues.

## What to expect, and what not

- A threaded live v2 day is not repeatable run to run: two runs of one seed differ in their inbound frame counts. Each run reconciles and replays exactly. A stepped day repeats frame for frame. Do not compare two live runs frame by frame.
- On a normal day the hedge planner has hedged in cash Treasuries; the future is chosen when the Treasury books are unusable (DESK_V2_WIRING.md Section 5). A day without futures hedges is not a fault.
- The 1-year swap is priced and risk-managed and is listed on no venue, so it is never quoted.
