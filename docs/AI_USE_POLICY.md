# AI use policy

This repository is built mostly by AI coding assistants. One engineer, the owner, directs parallel assistant sessions, reads their reports and merges their pull requests. This page says what the assistants do, what is actually checked, and what is not. It was rewritten on 2026-11-23 after the review of that date (docs/reviews/REVIEW_2026-11-23.md, finding E2) found that the earlier version described a label, a change log and approvals that were not in use.

## What assistants do here

- Write code, tests and documentation, usually in parallel sessions on separate branches, one combined pull request per round.
- Run the tests and report the commands and results. A number an assistant reports is checked against the file, test or ledger row it names before it goes into a document (CONTRIBUTING.md, "No measured number appears in a document without the file, job and date it came from").
- Review code. The six reviews behind docs/reviews/REVIEW_2026-11-23.md were assistant sessions that read the code without running it. An assistant review is not an independent review: it is produced by the same kind of system that wrote the code.
- Draft decision records, status entries and changelog lines.

## What is checked, and by what

- **The nine required CI checks on the `main` ruleset** are the only merge gate (CONTRIBUTING.md). They run the Java and Python suites on Linux and Windows, the site build, the config lint, the two deployment rehearsals and the repository policy.
- **External goldens** check the pricing library against things the project did not write: QuantLib 1.44, the published 2024 Treasury auction results and the published 2024 SOFR, EUR STR and SONIA indices (README "Measured results").
- **Nothing else.** There is no independent human review. The `main` ruleset requires 0 approving reviews, and pull requests #1 to #9 were merged by the owner with none.

## How assisted work is identified

Each commit written with an assistant ends with a `Co-Authored-By:` trailer naming the model. On 2026-11-23 every non-merge commit on `main` carried one (`git log --no-merges`). That trailer replaces the `ai-assisted` pull request label described in earlier versions of this page; the label was never applied. docs/ai_change_log.md is kept as a historical record and is no longer extended.

## Rules the owner keeps

These are the owner's own practice, not controls enforced by the repository:

- Assistants do not change a risk limit, a quoting parameter or a kill-switch setting without a decision record or a pre-registered experiment.
- Golden files are regenerated only on Linux, by the generator in `research/ratesresearch/goldens`.
- A pre-registration is committed before its evaluation data is read; the ledger records every run whatever its verdict.

The repository does not stop an assistant session from pushing to a branch or from opening and merging a pull request when the owner runs it with that permission.

## Relation to regulation

RTS 6 Article 5 (as retained in UK law and applied through FCA MAR 7A) expects changes to trading algorithms to be tested, approved by a named person and recorded. This project is not a regulated firm and does not meet that expectation: testing is CI, approval is the owner's merge without a second person, and the record is the git history and its trailers. docs/compliance/RTS6_INVENTORY.md rates the change-management rows accordingly. LEARN.md Section 12 explains the regulation.
