# Runbook: market data feed gap

A feed gap is a break in a venue's book feed sequence (`BookUpdate.venueSeqNo` not one more than the last), or a book delta the loop's inbound ring refused. Since 1.1.0 the two are the same event (DECISIONS.md DR-RV-R02): either way the loop's book may hold a level that no longer exists. The feed has no heartbeat; a silent feed shows only as 10 s without a book update, which stops routing to the venue but does not pull quotes.

## Detection

- The feed binding (`FeedBinding`, API_SPEC.md Section 18.5) stops forwarding and publishes GatewayStatus DISCONNECTED for the feed (status id 3; on the v2 profile also 6 for the second CLOB and 7 for the futures book, each on its own). The desk's input health pulls every live quote with reason `FeedGap`, and the routers exclude the venue (`VenueExclusion.Stale`) and cancel children resting there. No KillSwitch is engaged for a gap.
- The binding then delivers a full snapshot and publishes GatewayStatus CONNECTED; quoting resumes on its own once the inputs are healthy again. A gap that keeps recurring shows as repeated DISCONNECTED and CONNECTED pairs.
- Alert `InboundQueueBacklog` when the inbound ring buffer backs up behind a slow loop; a full ring is now a cause of gaps, not only a symptom.

## Immediate action

1. Confirm quotes were pulled: `GET /control/status` with the `X-Control-Token` header shows `liveQuotes` 0 while the feed is down. If it does not, engage the kill switch (`POST /control/kill` with the token and your party id in `X-Requested-By`) and check that it answered 200, not 503.
2. Identify the feed and the gap from the journal (queries below).
3. Watch for the CONNECTED that ends the rebuild. If it does not come within a few seconds, the binding is retrying a snapshot the ring keeps refusing: the loop is behind, so treat it as a capacity problem, not a venue problem.
4. Do not override the binding: the book is rebuilt from a snapshot, never patched.

## Journal queries

```bash
python tools/journal_query.py JOURNAL --template KillSwitch
python tools/journal_query.py JOURNAL --template BookUpdate --since START --until END --tail 50
python tools/journal_query.py JOURNAL --template Heartbeat --since START --until END
```

Look for the last `BookUpdate` before the gap and the first after it, the GatewayStatus frames (template 65003) with the feed's id, and any `Quote` sent between the gap and the pull.

## Escalation

- Immediately: head of desk (quoting is off for the group).
- Within 15 minutes if the venue cannot provide a snapshot: risk manager, and the venue's technical support.
- If any quote was sent after the gap and traded: compliance officer, because the trade may need review against best execution and the client may be owed an explanation.

## Post-incident

Complete [post-incident-template.md](post-incident-template.md). Record the gap's sequence numbers, the time to the quote pull, the time to rebuild, and any trades executed on quotes sent after the gap.
