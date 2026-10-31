# Runbook: venue disconnect

A venue disconnect is the loss of a FIX session or the SBE feed connection to one venue. While disconnected we cannot amend or withdraw quotes at that venue, and orders may still be working there.

## Detection

- The gateway (P2) reports the session down and engages the kill switch for that venue's scope with reason `SessionLoss`.
- Alert `VenueSimulatorDown` (simulated environment) or the gateway's session metric on a real deployment.
- Missed heartbeats in the journal.

## Immediate action

1. Confirm quoting is disabled for the venue and that other venues are unaffected.
2. Call the venue's support desk and ask them to cancel all our orders and quotes (most venues offer cancel-on-disconnect; confirm it fired).
3. Reconnect. On logon the gateway resends from the last confirmed sequence.
4. Run the venue part of the restart procedure: OrderMassStatusRequest and QuoteStatusRequest, compare with the journal (`RestartProcedure`, API_SPEC.md Section 17.9). Cancel anything the venue holds that the journal does not.
5. Quoting at the venue resumes only when the procedure reports no blocking discrepancy.

## Journal queries

```bash
python tools/journal_query.py JOURNAL --template KillSwitch
python tools/journal_query.py JOURNAL --template NewOrder --template ExecutionReport --since START --until END
python tools/journal_query.py JOURNAL --template Quote --template QuoteResponse --since START --until END
```

Every `Quote` sent in the last validity period before the disconnect is a quote that may have been live while we could not withdraw it.

## Escalation

- Immediately: head of desk.
- If any fill arrives for a quote or order we believed cancelled: risk manager (position change) and the venue (trade review).
- If cancel-on-disconnect did not fire: compliance officer; the venue's conformance (RTS 6 Article 6) is in question.

## Post-incident

Complete [post-incident-template.md](post-incident-template.md), including the venue's own incident reference.
