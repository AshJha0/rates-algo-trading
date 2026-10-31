# Runbooks

One runbook per incident class, plus the daily procedures and the warm-standby design. Each incident runbook has the same five parts: how the incident is detected, the immediate action, the journal queries that establish what happened, who is told and when, and the post-incident record. Alert rules in `deploy/prometheus/alerts.yml` link to these files, and `tools/lint_dashboards.py` fails CI if a linked runbook is missing.

| Incident class | Runbook | Alert rules |
|---|---|---|
| Market data feed gap | [feed-gap.md](feed-gap.md) | InboundQueueBacklog; KillSwitch reason FeedGap |
| Venue session lost | [venue-disconnect.md](venue-disconnect.md) | VenueSimulatorDown; KillSwitch reason SessionLoss |
| Risk limit breach | [limit-breach.md](limit-breach.md) | LimitUtilisationHigh, KillSwitchEngaged, QuotesLiveAfterKill |
| Pricing fault | [pricing-fault.md](pricing-fault.md) | PricingStageSlow; KillSwitch reason StaleReference |
| Standby takeover | [standby-takeover.md](standby-takeover.md) | TradingProcessDown |
| Reconciliation break | [reconciliation-break.md](reconciliation-break.md) | UnexplainedReconciliationBreaks |

Daily procedures: [start-of-day.md](start-of-day.md), [end-of-day.md](end-of-day.md). Design: [warm-standby-design.md](warm-standby-design.md). Record template: [post-incident-template.md](post-incident-template.md).

## Conventions

- The journal is the record. Every query below uses `tools/journal_query.py`, which verifies the hash chain as it reads; a chain break is itself an incident (API_SPEC.md Section 4) and is escalated to the development lead before anything else.
- Times are UTC. Party ids identify people in the KillSwitch and TradeAmend messages; the person who acts gives their own id.
- The kill switch is the first action whenever the cause is not understood within the time the runbook allows. Pulling quotes costs a few minutes of business; quoting on a wrong state can cost far more.
- Quoting is re-enabled only by the restart procedure (`RestartProcedure`, API_SPEC.md Section 17.9) or, after a kill, by the head of desk with the risk manager's agreement, recorded in the incident log.
- Roles named below (desk head, risk manager, compliance officer, development lead, operations) are the roles a regulated firm would staff. In this project they are all the owner; the separation is kept on paper so the procedures read as they would on a desk.

## Common journal queries

```bash
# everything in a window
python tools/journal_query.py JOURNAL --since 2026-10-30T14:00:00Z --until 2026-10-30T14:05:00Z

# kill switch, limit breaches, busts and amendments for the day
python tools/journal_query.py JOURNAL --template KillSwitch --template RiskLimitBreach
python tools/journal_query.py JOURNAL --template PostTradeBust --template TradeAmend

# how far the journal got, and that its chain is intact
python tools/journal_query.py JOURNAL --tail 5
```
