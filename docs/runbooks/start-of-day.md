# Start-of-day procedure

Run before quoting opens. Each step is checked off in the day's operations log with the time and the person.

1. **Previous day closed.** The end-of-day procedure finished: official P&amp;L produced and reconciled to the back-tester's attribution (`PnlReconciler`, zero differences), the break report has no unexplained breaks, the order records were archived. Any open item from yesterday blocks quoting in the affected instruments until the risk manager accepts it in writing.
2. **Configuration.** The deployed configuration is the reviewed version on `main`: `ConfigValidatorMain` passes and its hash matches the release record. Limits in the configuration match the limits the risk manager approved.
3. **Opening positions.** Load yesterday's closing positions into the position keeper, re-struck at yesterday's official marks (`PositionKeeper.openPosition`, marking policy in API_SPEC.md Section 17.3). Check the totals against the clearing house's and the custodian's start-of-day statements.
4. **Reference data.** The securities master (P1) has today's instruments, calendars and settlement dates; settlement obligations due today are listed with their state (`SettlementObligation`), and yesterday's fails carry their day count and funding cost.
5. **Clock.** The host clock is synchronised to UTC within the RTS 25 tolerance; the check's output is in the log.
6. **Processes.** Start the active colour. `/health/ready` answers 200. The restart procedure runs at start: journal replayed, venue status reconciled, quoting enabled only if nothing blocks.
7. **Kill switch test.** Once a week (Monday), engage and release the kill switch in the simulated environment and confirm every quote is pulled (`python deploy/rehearsal.py --mode process`). RTS 6 Article 12 expects the kill function to be tested.
8. **Open.** The head of desk confirms quoting is open, by instrument group.
