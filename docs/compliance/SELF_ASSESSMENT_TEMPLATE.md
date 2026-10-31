# Annual self-assessment and validation report (RTS 6 Article 9)

Completed once a year and after any material change to the algorithmic trading business. The risk management function drafts it, the compliance function reviews it, and senior management approves the validation report in Part 4. The answers cite evidence: the control inventory ([RTS6_INVENTORY.md](RTS6_INVENTORY.md)), test runs by CI job and date, incidents from the incident log, and the algorithm inventory ([ALGORITHM_INVENTORY_TEMPLATE.md](ALGORITHM_INVENTORY_TEMPLATE.md)).

Period covered: ____ to ____. Prepared by: ____. Reviewed by: ____. Approved by: ____ on ____.

## Part 1. Nature, scale and complexity (Annex I)

| Annex I criterion | Answer and evidence |
|---|---|
| Nature: regulatory status, role (principal, market maker, agent), instruments and venues | |
| Nature: number and kind of algorithms, their autonomy and how often they change | |
| Scale: share of the firm's business, capital at risk, order, quote and trade volumes and their peak rates | |
| Scale: number of venues and connections, use of DEA | |
| Complexity: strategies, speed, dependence on market data and on other algorithms | |
| Complexity: technology, outsourcing, people and the experience of the staff who run it | |

## Part 2. Controls (Articles 1 to 18)

For each row of the control inventory: status at the start of the period, changes during it, test evidence, incidents, and the residual weakness if any.

| Article | Status at start | Changes | Evidence (job, date) | Incidents | Weakness and action |
|---|---|---|---|---|---|

## Part 3. Testing and incidents

- Stress test at twice the highest message and trade volume of the previous six months (Article 10): the source journal, its peak rate, the acceptance rate and latency at twice that rate, from `StressReplayer` with the run's job and date.
- Kill switch tests (Article 12): dates, scope, time to zero live quotes.
- Conformance tests with each venue (Article 6): venue, date, result.
- Business continuity tests (Article 14): restart procedure and standby takeover drills, with their reports.
- Incidents: list with links to post-incident records; the trend over the year.

## Part 4. Validation report for senior management

1. Conclusion: whether the systems and controls are adequate for the nature, scale and complexity in Part 1.
2. Weaknesses found and the remediation plan with owners and dates.
3. Changes to algorithms, limits or venues approved in the period.
4. Statement by senior management approving the report, with names and date.
