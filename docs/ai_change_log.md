# AI-assisted change log

One line per pull request that carries the `ai-assisted` label (docs/AI_USE_POLICY.md).

| Date | PR | What was assisted | Assistant | Validation | Approved by |
|---|---|---|---|---|---|
| 2026-10-31 | Phase 0 scaffolding | Initial drafts of `rates-common`, `rates-messages`, `rates-journal`, `research/ratesresearch`, documentation set, MkDocs site, CI workflows | Claude Code | `mvn -q verify` (Linux and Windows CI), `ruff check`, `ruff format --check`, `mypy` strict, `pytest -q research` (15 passed, 2026-10-31), `mkdocs build --strict` | Ashish Jha |
| 2026-10-31 | Phase 5 branch `claude/p5-backtest-research-15a31f` (in the coordinator's combined PR) | `rates-backtest` module, P5 research modules and notebooks, presets, pre-registration P5-001 and its ledger record, cookbook recipes 17 to 19, LEARN Section 10, API_SPEC Section 16, DR-P5-01 to DR-P5-07 | Claude Code | `mvn -B -ntp verify` on JDK 25.0.5 (Windows, all modules green), `ruff check`, `ruff format --check`, `mypy` strict, `pytest -q research` (38 passed), `tools/check_cookbook.py`, `tools/run_notebooks.py`, `mkdocs build --strict` | pending owner review |
