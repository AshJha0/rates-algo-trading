# Runbook: reconciliation break

The daily reconciliation (`DailyReconciliation`, API_SPEC.md Section 17.8) compares our trades and positions with the venues' drop copies, the clearing house and our own books and records. A break is explained when the journal accounts for it (a fill that arrived after the cut, a bust after the cut, a clearing rejection, an audited amendment); otherwise it is unexplained and comes here.

## Detection

- The break report's last line, `unexplained breaks: N`, with N above zero.
- Alert `UnexplainedReconciliationBreaks` from `rates_recon_breaks{explained="false"}`.
- COOKBOOK.md recipe 20 shows how to read the report.

## Immediate action, by break class

| Class | Meaning | First step |
|---|---|---|
| MISSING_OURS | The venue or clearing house has a trade we do not | Ask the venue for the execution report; if it is ours, append it through the gateway, and the keeper books it as a late fill. Check whether quoting limits were computed without it. |
| MISSING_THEIRS | We have a trade the other side does not | Ask the venue whether it busted the trade. If so, journal a PostTradeBust with reason VenueError; if not, the venue's drop copy is incomplete and they must correct it. |
| QUANTITY | Both have it, sizes differ | Compare our execution reports for the order (`recordsByVenueOrderId`) with the venue's. A partial fill missed on either side is the usual cause. |
| PRICE | Sizes agree, prices differ | Agree the correct price with the venue. A correction on our side is a TradeAmend with the operator's party id and reason PriceCorrection. |
| TIMING | Economics agree, times differ by more than a second | Check clock synchronisation on our side (RTS 25 requires UTC traceability) and the venue's timestamp convention. |
| DUPLICATE_CONFLICT | A venue sent two different versions of one execution | Treat as a PRICE or QUANTITY break; the keeper kept the first version and flagged the second. |

A break is closed by a journaled action (late fill, bust, amendment) and a clean re-run of the reconciliation, never by editing a report.

## Journal queries

```bash
# everything about one execution or quote, by its key's trade id
python tools/journal_query.py JOURNAL --template ExecutionReport --template PostTradeBust --template TradeAmend --since START
# order records by venue order id: the archive under the records root, YYYY/MM/DD/order-records.csv
grep ",VENUE_ORDER_ID," RECORDS_ROOT/2026/10/30/order-records.csv
```

## Escalation

- Same day: operations owns every unexplained break; the head of desk sees the report.
- Unresolved at the next start of day: risk manager (positions may be wrong) and compliance officer (books and records under RTS 6 Article 17).
- A break that changes a client trade: compliance officer and the client relationship owner.

## Post-incident

Breaks resolved the same day with a journaled action need only the break report and the actions taken. A break open past the next start of day, or one caused by a defect in our systems, gets a full [post-incident-template.md](post-incident-template.md).
