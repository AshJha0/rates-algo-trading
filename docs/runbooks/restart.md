# Runbook: restart after a crash or a stop

A restart is starting the trading process again on the journal directory a stopped process left, cleanly or not. Since 1.1.0 the process rebuilds its own state from those journals before it connects to any venue (review L6; API_SPEC.md Section 18.9, "Restart"; DR-RV-X01 to X05).

## Detection

- The trading process is down: alert `TradingProcessDown`, or `/health/live` does not answer.
- After a start, `GET /control/status` shows `"recovered":true` when the process found journals, with `recoveredFrames`, `journalTornTails` and `outboundCompleted`.
- Standard output carries one `recovery: ` line per step. Standard error carries `restart refused, process not started:` and the reason when the journals do not allow a restart.

## Immediate action

1. Do not delete, move or edit anything in the journal directory. Start the process again with the same configuration and the same journal directory (`RATES_JOURNAL_DIR` or `journal.directory`).
2. Read the `recovery: ` lines. A torn tail is reported as `TORN TAIL cut in segment N at sequence S (B bytes cleared)`: the last frame was being written when the process died, and it is cut. That is expected after a crash. Each group's line gives the inbound frames replayed, the outbound messages verified and completed, the replay hash and how long the process was down.
3. Wait for `/health/ready` to answer 200, or read the reason it gives. Until then the process is not quoting. The usual reasons, in order: a session not logged on, `order reconciliation with the venue in progress`, `CLOB book has no whole snapshot yet`.
4. Check `GET /control/status` for `killSwitch` and `halted`. A kill switch engaged before the crash is still engaged after it, because the journal says so. Re-enable nothing until the cause of the original stop is understood.
5. If `outboundCompleted` is above zero, the process died between journaling an input and finishing the work it caused. Replay finished that work and journaled it, but did not send it: the sessions dropped with the process and the venues cancelled on disconnect (`LoopRecovery`). Nothing needs doing, but note the count in the incident record.

## If the restart is refused

The process refuses to start when the journals do not prove a state: damage in the middle of the chain, a missing segment, a header that is not a journal header (`JournalRecovery`), a produced message that differs from the recorded one, an outbound journal longer than the inbound one explains, or a journal that ends in a LoopError (`LoopRecovery`). Nothing is truncated automatically.

1. Keep the process down. Engage nothing at the venues from this process: it holds no session.
2. Reconcile at the venues by hand: ask each venue's support desk for our live orders and quotes, and have them cancelled. Collect the venue's fills since the last journaled frame (`python tools/journal_query.py JOURNAL --tail 5`).
3. Move the whole journal directory aside, including `fix/` (sequence stores) and `venues/` (simulator stores). Keep it for the incident record; it is evidence.
4. Agree a sequence reset with each venue, because the new process starts its sessions from 1.
5. Start on an empty journal directory. Book the fills collected in step 2 through the post-trade path, and escalate to the risk manager before quoting opens.

## Journal queries

```bash
# how far each journal got, and that its chain is intact
python tools/journal_query.py JOURNAL --tail 5
# what the restart told the loop: GatewayStatus (template 65003; status 2 down, 4 reconciling, 5 reconciled, 1 up)
python tools/journal_query.py JOURNAL --template 65003 --since RESTART
python tools/journal_query.py JOURNAL --template ExecutionReport --since RESTART
```

## Limits to know

- The FIX sequence store (`<journalDir>/fix/*.seq`, `MappedSequenceStore`) survives a process crash, not a power loss or kernel crash. After one, the outbound number may be behind what the venue expects and the venue refuses the Logon; agree a sequence reset with the venue.
- The route journal is silent during replay, so a routing decision made at the crash instant can be missing from it (`TradingProcess.RouteSink`). The inbound and outbound journals are complete.
- Reconciliation with resting orders is tested at `DeskGroup` level only (`DeskGroupTest.aReturningClobSessionStaysDownUntilItsOrdersAreReconciled`), not against a venue holding orders across a process restart.
- The simulators restart with the process and keep their own file store under `<journalDir>/venues`; moving the journals aside also resets them.

## Escalation

- A refused restart: development lead and head of desk at once; risk manager before quoting reopens.
- A fill at a venue that the journals do not hold: risk manager and the venue (trade review).

## Post-incident

Complete [post-incident-template.md](post-incident-template.md) with the `recovery: ` lines, the status JSON after the restart and, for a refused restart, the reason and where the old journals were kept.
