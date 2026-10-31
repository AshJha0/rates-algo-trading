# Post-incident record

Copy this file to the incident log (outside the repository for a real desk; `docs/incidents/` here) as `YYYY-MM-DD-short-name.md` within one business day of the incident. Each statement of fact names the journal file and sequence range, the log line or the metric it comes from.

## Summary

- Incident class: feed gap, venue disconnect, limit breach, pricing fault, standby takeover or reconciliation break
- Start and end (UTC):
- Detected by: alert name, person, or reconciliation
- Trading impact: quotes pulled (from, to), orders cancelled, trades affected (trade keys)
- Client impact: RFQs not answered, quotes withdrawn, trades busted or amended
- P&L impact: amount and how it was measured (official P&L line, not the intraday estimate)

## Timeline

| Time (UTC) | Journal sequence | What happened | Source |
|---|---|---|---|

## Cause

What failed, and why the controls did or did not catch it first. Distinguish the trigger from the underlying cause.

## Controls

- Which RTS 6 controls acted (kill switch, pre-trade limit, real-time monitoring, reconciliation), with evidence.
- Which should have acted and did not.

## Actions

| Action | Owner | Due | Tracking issue |
|---|---|---|---|

## Notifications

Who was told, when, and by what route: head of desk, risk, compliance, venues, clearing members, the FCA where the incident is notifiable (for example a material failure of an algorithmic trading system).

## Sign-off

Head of desk, risk manager, compliance officer: name and date.
