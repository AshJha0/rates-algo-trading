# AI-assisted change log

One line per pull request that carries the `ai-assisted` label (docs/AI_USE_POLICY.md).

| Date | PR | What was assisted | Assistant | Validation | Approved by |
|---|---|---|---|---|---|
| 2026-10-31 | Phase 0 scaffolding | Initial drafts of `rates-common`, `rates-messages`, `rates-journal`, `research/ratesresearch`, documentation set, MkDocs site, CI workflows | Claude Code | `mvn -q verify` (Linux and Windows CI), `ruff check`, `ruff format --check`, `mypy` strict, `pytest -q research` (15 passed, 2026-10-31), `mkdocs build --strict` | Ashish Jha |
| 2026-10-31 | P2 venues (W3), branch claude/p2-venues-3d7318 | rates-refdata, rates-fix, rates-venues, certification scripts, cookbook recipes 10 and 11, LEARN Section 6, API_SPEC Sections 2.1, 10 and 11, DR-P2V-01 to DR-P2V-12 | Claude Code | `mvn -B -ntp verify` on JDK 25.0.5 (Windows 11), `tools/check_cookbook.py`, `mkdocs build --strict` | pending owner review |
