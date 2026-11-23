# Contributing

## Workflow

This is a solo project. One engineer directs parallel AI coding sessions, which write most of the code, tests and documents; the same engineer merges. There is no independent reviewer, human or otherwise, and pull requests #1 to #9 were merged with no approving review.

`main` is protected by the repository ruleset `main` (checked 2026-11-23 with `gh api`): no deletion, no force push, changes through a pull request, and nine required status checks (the Java and Python jobs on Linux and Windows, the site build, the config and dashboard lint, the two deployment rehearsals and the repository policy). It requires 0 approving reviews, and strict status checks are off, so a branch need not be up to date with `main` before it merges. Those nine checks are the only merge gate. Work happens on short-lived branches.

Commits follow Conventional Commits: `feat(journal): ...`, `fix(common): ...`, `docs: ...`, `test: ...`, `ci: ...`, `chore: ...`. The body says why, not what; the diff says what.

One combined pull request per round of work. The PR description lists its slices (for example "slice 1: TimerWheel", "slice 2: journal reader"). Exceptions: a signed pre-registration (P5) or a change to a frozen contract gets its own PR so its record is clean.

The workflows and the jobs that gate a merge are drawn in [CI and release pipeline](docs/site/pages/diagrams.md#ci-and-release-pipeline).

## Definition of done

A change is done when the same pull request contains:

1. The code.
2. Tests: unit, and property-based where the input space is wide. A bug fix includes the test that would have caught it.
3. Documentation: the affected section of LEARN, COOKBOOK, API_SPEC or docs/ARCHITECTURE updated, and CHANGELOG under Unreleased.
4. A decision record in DECISIONS.md if the change makes a design choice a future reader would ask about.
5. A STATUS.md entry if the change moves a phase or a gate.

The pull request template (`.github/PULL_REQUEST_TEMPLATE.md`) is the checklist.

## Coding rules

- Single writer. Mutable state is owned by one thread. Cross-thread handoff goes through ring buffers only.
- No allocation and no `synchronized` in hot-path packages after warm-up. Hot-path packages are listed in docs/ARCHITECTURE.md and checked by the allocation test (P2).
- Every numerical comparison states its tolerance: relative error with an absolute floor, both named in the test. No bare `assertEquals` on doubles.
- No silent NaN. A computation that cannot produce a value throws with the inputs in the message.
- Golden files (QuantLib comparisons, replay hashes) are regenerated on Linux only, never on Windows, because line endings and locale have corrupted goldens before. CI checks goldens on both platforms.
- Error Prone and SpotBugs run in `mvn verify`; warnings in changed code are fixed, not suppressed, unless the suppression comment explains why.
- Python: ruff and mypy strict are clean; pandas is not imported in modules that do not need it.
- No measured number appears in a document without the file, job and date it came from.
- The word that names a hiring conversation does not appear anywhere in the repository (DECISIONS.md DR-007). Run `git grep -i` for it before pushing.

## Running everything

```bash
# Java (JDK 25, Maven 3.9+)
mvn -q verify

# Python (3.12)
python -m pip install -e "research[dev]"
ruff check research
ruff format --check research
(cd research && mypy)
pytest -q research

# Docs
python -m pip install -r docs/site/requirements.txt
mkdocs build --strict
```

Local tests must finish before a push. A test run that stalls is treated as a hang and investigated, not cancelled and pushed around.

## AI assistance

Assistants draft code, tests and documents under docs/AI_USE_POLICY.md. A commit written with an assistant carries a `Co-Authored-By:` trailer naming the model; that trailer, not a label, is how assisted work is identified. On 2026-11-23 every non-merge commit on `main` carried one. The `ai-assisted` label described in earlier versions of this file was never applied, and docs/ai_change_log.md stopped at 2026-10-31.
