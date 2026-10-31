# Contributing

## Workflow

Trunk-based. `main` is protected: no direct pushes, required CI, one approving review. Work happens on short-lived branches named `<area>/<short-description>`.

Commits follow Conventional Commits: `feat(journal): ...`, `fix(common): ...`, `docs: ...`, `test: ...`, `ci: ...`, `chore: ...`. The body says why, not what; the diff says what.

One combined pull request per round of work. The PR description lists its slices (for example "slice 1: TimerWheel", "slice 2: journal reader") and reviewers review slice by slice, commenting with the slice name. Exceptions: a signed pre-registration (P5) or a change to a frozen contract gets its own PR so the review record is clean.

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

Assistants may draft code and documents and review changes under the rules in docs/AI_USE_POLICY.md. A pull request with assisted content carries the `ai-assisted` label and a line in docs/ai_change_log.md.
