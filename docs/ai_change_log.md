# AI-assisted change log

One line per pull request that carries the `ai-assisted` label (docs/AI_USE_POLICY.md).

| Date | PR | What was assisted | Assistant | Validation | Approved by |
|---|---|---|---|---|---|
| 2026-10-31 | Phase 0 scaffolding | Initial drafts of `rates-common`, `rates-messages`, `rates-journal`, `research/ratesresearch`, documentation set, MkDocs site, CI workflows | Claude Code | `mvn -q verify` (Linux and Windows CI), `ruff check`, `ruff format --check`, `mypy` strict, `pytest -q research` (15 passed, 2026-10-31), `mkdocs build --strict` | Ashish Jha |
