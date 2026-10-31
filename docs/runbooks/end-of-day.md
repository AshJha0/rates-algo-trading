# End-of-day procedure

Run after the reconciliation cut (17:00 UTC by default, `recon.cutTimeUtc` in the configuration).

1. **Close quoting** in instruments whose venues have closed; leave quoting open where the desk trades late, recording which.
2. **Marks.** Load the official closes (`MarkSource`): curve closes for swaps (P1), public settlement prices for bonds and futures. A position with a non-zero quantity and no official mark stops the run (`MissingMarkException`); the desk does not substitute its own mid.
3. **Official P&L.** Run the report generator (`EodReportMain`, image `deploy/report`) over the day's journal. It writes `official-pnl-DATE.csv` in the attribution format, archives the order records in the retention layout and writes the surveillance alerts.
4. **P&L reconciliation.** Reconcile official P&L to the back-tester's attribution for the same journal (`PnlReconciler`). The tolerance is zero minor units per component; any difference is investigated before sign-off.
5. **Trade and position reconciliation.** Run `DailyReconciliation` against the venues' drop copies, the clearing house's report and the archived records. Unexplained breaks go to [reconciliation-break.md](reconciliation-break.md).
6. **Clearing and settlement.** Every swap traded today is at least SUBMITTED to clearing; every rejection has been busted. Bonds settling tomorrow are matched; fails are carried with their cost (`FailCost`).
7. **Surveillance.** Review the day's alerts (quote stuffing, self-trade patterns). Each alert is closed with a note or escalated to compliance.
8. **Sign-off.** Head of desk signs the P&L; operations signs the break report; the closing positions become tomorrow's opening positions.
