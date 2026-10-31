# AI use policy

This repository is built with help from AI coding assistants. This policy says what they may do, what they may never do, and how every assisted change is recorded and checked. It is modelled on the change-management expectations a UK firm running algorithmic trading would apply under RTS 6 (as applied by FCA MAR 7A): documented change, testing before deployment, approval by a named person, and a record that can be audited after the fact.

## What assistants may do

- Draft code, tests and documentation for a human to review.
- Review a change. A review by an assistant must run the tests and cite the results (command, pass or fail, date) in the review; a review that did not run anything says so and carries no weight on its own.
- Summarise incidents from the journal: read frames, reconstruct the sequence of events, propose causes. The summary names the journal file and frame sequence range it used.
- Draft decision records, status entries and changelog lines.

## What assistants may never do

- Change a risk limit, a quoting parameter, a kill-switch setting or any other production control, by any route.
- Merge or push to `main`.
- Change production code without a pull request that a human has approved and that has the validation (test output, benchmark report where relevant) attached.
- Regenerate golden files.
- Sign a pre-registration.

These apply whether the assistant is working interactively or in an automated job.

## Recording

Every pull request with assisted content:

1. Carries the GitHub label `ai-assisted`.
2. Adds a line to `docs/ai_change_log.md`: date, what was assisted, which assistant, how it was validated, who approved.
3. Says in its description which parts were drafted by an assistant and which were written by a person, so a reviewer knows where to look hardest.

## Validation, not trust

Assistant output is treated as a draft from a capable but unaccountable colleague. It is validated by the same means as any other change: the tests run, the numbers are reproduced, the documents are read against the code. A claim an assistant makes about a result is not evidence; the test output is. Where an assistant cites a number, the citation is checked against the named file or job before merge.

## Relation to regulation

RTS 6 Article 5 (as retained in UK law and applied through FCA MAR 7A) requires that changes to trading algorithms are tested, approved and recorded. This policy applies the same discipline to assisted changes in a project that is not itself a regulated firm: documented change (the PR), testing (CI and attached validation), approval (human review), record (label and change log). LEARN.md Section 12 explains the regulation, and docs/compliance/RTS6_INVENTORY.md maps each control, this policy included (RTS 6 Articles 5 and 11), to the regulatory text and to its evidence.
