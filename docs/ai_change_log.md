# AI-assisted change log

One line per pull request that carries the `ai-assisted` label (docs/AI_USE_POLICY.md).

| Date | PR | What was assisted | Assistant | Validation | Approved by |
|---|---|---|---|---|---|
| 2026-10-31 | Phase 0 scaffolding | Initial drafts of `rates-common`, `rates-messages`, `rates-journal`, `research/ratesresearch`, documentation set, MkDocs site, CI workflows | Claude Code | `mvn -q verify` (Linux and Windows CI), `ruff check`, `ruff format --check`, `mypy` strict, `pytest -q research` (15 passed, 2026-10-31), `mkdocs build --strict` | Ashish Jha |
| 2026-10-31 | Phase 1 W1 analytics (branch `claude/p1-analytics-df37ab`) | `rates-analytics`, QuantLib golden generator, data downloaders and fixtures, curve PCA, cookbook recipes 4 to 9, LEARN 2 to 5, API_SPEC 5 to 8, DR-P1-01 to 09 | Claude Code | `mvn -B -ntp verify` (JDK 25), QuantLib 1.44 golden (224 instruments), ruff, mypy strict, pytest (63 passed), `mkdocs build --strict`, 2026-10-31 | pending owner review |
