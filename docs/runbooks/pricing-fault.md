# Runbook: pricing fault

A pricing fault is a quote price that does not follow from its inputs: a curve that failed to build, a stale reference (`Quote.referenceNanos` too old), a price outside its collar, or a numerical failure in the analytics (API_SPEC.md Section 4: analytics throw rather than return NaN).

## Detection

- `RiskLimitBreach` with `limitKind` PriceCollar (pre-trade collar refused the quote).
- Kill switch with reason `StaleReference` when the newest input a price depends on is older than its tolerance.
- Alert `PricingStageSlow` (p99 of the pricing stage above 1 ms for five minutes), which often precedes a stale-reference kill.
- Markouts far outside their usual distribution in the router's report (P4).

## Immediate action

1. If quotes priced off the faulty curve are live, engage the kill switch for the affected group.
2. Find the input that changed: the last `CurveInput` frames before the first bad quote.
3. Re-run the pricing for that input set in replay (the journal plus a `ManualClock` reproduces the quote exactly) and compare with QuantLib (P1 golden method) to establish whether the input or the analytics was wrong.
4. A bad input is excluded by the market data quality layer; an analytics fault is a code fix with a test that reproduces it, through a reviewed pull request. Quoting resumes on the excluded input or the fixed release, not before.

## Journal queries

```bash
python tools/journal_query.py JOURNAL --template CurveInput --since START --until END
python tools/journal_query.py JOURNAL --template Quote --since START --until END
python tools/journal_query.py JOURNAL --template RiskLimitBreach --template KillSwitch
```

## Escalation

- Immediately: head of desk; development lead for an analytics fault.
- If a client traded on a faulty price: compliance officer and the client relationship owner. Whether to honour, amend (TradeAmend, audited) or seek to bust (PostTradeBust, with the venue's agreement) is the desk head's decision under the venue's error-trade rules.

## Post-incident

Complete [post-incident-template.md](post-incident-template.md) with the replay that reproduces the fault, the journal sequence range, and the test added.
