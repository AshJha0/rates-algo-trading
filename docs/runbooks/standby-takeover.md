# Runbook: standby takeover

The active trading process has failed or must be replaced, and the standby (the other colour) takes over. The design is in [warm-standby-design.md](warm-standby-design.md); this runbook is the operator's procedure.

## Detection

- Alert `TradingProcessDown` (no scrape for 30 seconds).
- Heartbeat silence from the active process on the replication channel (warm standby, design note).
- A planned switch: a release, a host move.

## Immediate action

1. Make sure the failed process cannot come back and quote: stop it (container or process) and, if it is unreachable, ask each venue to cancel all our orders and quotes. Two processes quoting the same book is the failure to avoid above all others.
2. On the standby, run the restart procedure (`RestartProcedure`, API_SPEC.md Section 17.9). It disables quoting, replays the journal to its last sequence (the replicated journal for a warm standby, the shared volume for a cold one), asks every venue for order and quote status, cancels what the journal does not know, withdraws every live quote, and enables quoting only if nothing blocks.
3. If the procedure reports blocking discrepancies (fills at a venue that are missing from the journal), quoting stays off. Obtain the missing fills from the venue drop copy, append them through the gateway, and run the procedure again.
4. For a planned switch use `deploy/bluegreen.sh TARGET_COLOUR`: it checks the target is ready, engages the kill switch on the active colour, confirms its live quotes are zero, and records the switch. The target enables quoting through the restart procedure.

## Journal queries

```bash
# where the failed process's journal ends, and that its chain is intact
python tools/journal_query.py OLD_JOURNAL --tail 5
# orders and quotes in flight at the end
python tools/journal_query.py OLD_JOURNAL --template NewOrder --template ExecutionReport --tail 50
python tools/journal_query.py OLD_JOURNAL --template Quote --template QuoteResponse --tail 50
```

The restart procedure's report lists every action it took; attach it to the incident record.

## Escalation

- Immediately: head of desk and development lead.
- If the standby cannot enable quoting within 30 minutes: risk manager, and inform the venues that we are not quoting.
- If two processes quoted at once at any point: compliance officer.

## Post-incident

Complete [post-incident-template.md](post-incident-template.md) with the restart procedure's report, the time from failure to kill, and the time to quoting.
