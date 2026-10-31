# Runbook: risk limit breach

A limit breach is a pre-trade check refusing a message (`RiskLimitBreach` with `rejectedMessageSeqNo` set) or a post-trade check finding a position, DV01 or loss above its limit (`rejectedMessageSeqNo` 0). Hard limits engage the kill switch with reason `LimitBreach` (P3 risk engine).

## Detection

- `RiskLimitBreach` frames in the journal; the risk engine publishes `rates_limit_utilisation_ratio` per limit.
- Alerts `LimitUtilisationHigh` (above 0.9 for a minute), `KillSwitchEngaged`, and `QuotesLiveAfterKill` (the kill did not take).

## Immediate action

1. If `QuotesLiveAfterKill` fires, engage the kill switch by hand: `POST /control/kill` with `X-Control-Token` and your party id in `X-Requested-By`. Then call each venue to cancel. This is the most urgent alert the platform raises.
2. Establish which limit, the observed value and the limit value from the breach message.
3. Decide with the risk manager whether the position is to be reduced (hedge) or the limit was wrong. A limit is changed only by a reviewed configuration change (RTS 6 Article 11, docs/AI_USE_POLICY.md); nobody raises a limit to clear an alert.
4. Quoting is re-enabled by the head of desk with the risk manager's agreement, recorded in the incident log.

## Journal queries

```bash
python tools/journal_query.py JOURNAL --template RiskLimitBreach --template KillSwitch
# the message a pre-trade check refused, by its sequence
python tools/journal_query.py JOURNAL --from-seq SEQ --to-seq SEQ
python tools/journal_query.py JOURNAL --template ExecutionReport --template QuoteResponse --since START --until END
```

## Escalation

- Immediately: head of desk and risk manager.
- Within the hour for a hard-limit breach: compliance officer. A hard-limit breach is a reportable event in the firm's internal risk reporting and an input to the annual self-assessment (docs/compliance/SELF_ASSESSMENT_TEMPLATE.md).

## Post-incident

Complete [post-incident-template.md](post-incident-template.md). State whether the pre-trade control stopped the order (it should) or the breach was found post-trade (then why the pre-trade check let it through).
