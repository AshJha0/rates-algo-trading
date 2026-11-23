# Runbook: venue disconnect

A venue disconnect is the loss of a FIX session or the SBE feed connection to one venue. While disconnected we cannot amend or withdraw quotes at that venue, and orders may still be working there.

## Detection

- The gateway reports the session down with GatewayStatus DISCONNECTED; the desk's input health pulls every live quote with reason `SessionLoss`, and for the CLOB the routers exclude the venue. No KillSwitch is engaged for a lost session.
- Alert `VenueSimulatorDown` (simulated environment) or the gateway's session metric on a real deployment.
- Missed heartbeats in the journal.

## Immediate action

1. Confirm quoting is disabled for the venue and that other venues are unaffected.
2. Call the venue's support desk and ask them to cancel all our orders and quotes (most venues offer cancel-on-disconnect; confirm it fired).
3. Reconnect. Since 1.1.0 the session does this by itself (API_SPEC.md Section 18.5):
   - A session that was logged on and dropped reconnects with a doubling wait under `fix.reconnect` and gives up after `maxAttempts`. A halted session, or one disconnected on purpose, never reconnects; a halted process needs a restart.
   - The connection is TLS 1.3 with host-name and certificate checks when `fix.transport` is `tls`, and the Logon carries Username (553) and Password (554) when `fix.credentials` is set. A refused handshake or a Logout answering our Logon means a certificate or password problem: see [certificate-rotation.md](certificate-rotation.md).
   - With `fix.resetOnLogon: false` (the deployment files) the Logon resumes the sequence numbers, and the venue resends what it sent while we were down; a fill already booked is dropped by its ExecID. Our own missed messages are gap-filled, never resent. With `resetOnLogon: true` (the simulator preset) the numbers start from 1 and anything the venue sent meanwhile is not resent.
   - The CLOB session reports RECONCILING, sends an OrderMassStatusRequest and reports RECONCILED once the answers reached the loop. The loop keeps the venue down until then; `/health/ready` gives `order reconciliation with the venue in progress`. An unanswered request is repeated twice, 5 s apart, then the connection is made again.
   - If the whole process stopped, follow [restart.md](restart.md) instead.
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
