# Runbook: market data feed gap

A feed gap is a break in a venue's book feed sequence (`BookUpdate.venueSeqNo` not one more than the last), or silence on a feed longer than its heartbeat interval. Quotes priced off a book with a gap are priced off a state that may no longer exist.

## Detection

- The venue gateway (P2) detects the sequence gap and, by design, the event loop engages the kill switch for the affected instrument group with reason `FeedGap` (API_SPEC.md Section 2, `KillReason`).
- Alert `InboundQueueBacklog` when the inbound ring buffer backs up behind a slow or stuttering feed.
- Alert `KillSwitchEngaged` follows from the automatic kill.

## Immediate action

1. Confirm the kill switch has pulled quotes for the group: `GET /control/status` shows `liveQuotes` 0, and alert `QuotesLiveAfterKill` is not firing.
2. Identify the feed and the gap from the journal (queries below).
3. Request a snapshot or replay from the venue through the gateway's recovery channel; the book is rebuilt from the snapshot, never patched.
4. Do not re-enable quoting until the book has been rebuilt and the gateway reports the feed in sequence for at least one heartbeat interval.

## Journal queries

```bash
python tools/journal_query.py JOURNAL --template KillSwitch
python tools/journal_query.py JOURNAL --template BookUpdate --since START --until END --tail 50
python tools/journal_query.py JOURNAL --template Heartbeat --since START --until END
```

Look for the last `BookUpdate` before the gap and the first after it, and for any `Quote` sent between the gap and the kill.

## Escalation

- Immediately: head of desk (quoting is off for the group).
- Within 15 minutes if the venue cannot provide a snapshot: risk manager, and the venue's technical support.
- If any quote was sent after the gap and traded: compliance officer, because the trade may need review against best execution and the client may be owed an explanation.

## Post-incident

Complete [post-incident-template.md](post-incident-template.md). Record the gap's sequence numbers, the time to kill, the time to rebuild, and any trades executed on quotes sent after the gap.
