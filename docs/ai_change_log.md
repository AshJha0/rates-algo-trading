# AI-assisted change log

One line per pull request that carries the `ai-assisted` label (docs/AI_USE_POLICY.md).

| Date | PR | What was assisted | Assistant | Validation | Approved by |
|---|---|---|---|---|---|
| 2026-10-31 | Phase 0 scaffolding | Initial drafts of `rates-common`, `rates-messages`, `rates-journal`, `research/ratesresearch`, documentation set, MkDocs site, CI workflows | Claude Code | `mvn -q verify` (Linux and Windows CI), `ruff check`, `ruff format --check`, `mypy` strict, `pytest -q research` (15 passed, 2026-10-31), `mkdocs build --strict` | Ashish Jha |
| 2026-10-31 | P6 branch `claude/p6-posttrade-hardening-3e4054` (in the round PR) | Drafts of `rates-posttrade`, `rates-ops`, `deploy/`, ops.yml, tools, docs/compliance, docs/runbooks, LEARN Sections 12 and 14, COOKBOOK recipe 20, API_SPEC Sections 2.1 and 17, DR-P6-01 to DR-P6-07 | Claude Code | `mvn -B -ntp verify` (JDK 25.0.5, Windows 11), process-mode rehearsal, config, dashboard, inventory and cookbook checks, `mkdocs build --strict`, all on 2026-10-31 | pending owner review |
