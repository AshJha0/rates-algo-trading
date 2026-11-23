# Owner-side checklist

Settings that cannot be changed from a pull request. Each needs the repository owner in the GitHub web interface (or `gh` with admin rights). Tick each with the date when done.

## Branch protection on `main` (Settings, Rules, ruleset `main`)

The ruleset `main` exists (created 2026-11-01; CONTRIBUTING.md). It requires a pull request with 0 approving reviews, because this is a solo project with no second reviewer (docs/AI_USE_POLICY.md). The items below are what it should hold.

- [x] Require a pull request before merging. Required approvals: 0 while there is no second reviewer; raise to 1 if one joins.
- [ ] Require status checks to pass, with the branch up to date. Required checks, by job name:
  - `Java 25 / ubuntu-latest`, `Java 25 / windows-latest` (ci.yml)
  - `Python 3.12 / ubuntu-latest`, `Python 3.12 / windows-latest` (ci.yml)
  - `Repository policy` (ci.yml)
  - `Build site (mkdocs --strict)` (docs.yml)
  - `Config schema, dashboards and alert rules`, `Deployment rehearsal (process mode)` (ops.yml)
  - Not required: `Java 27 forward-compatibility (informational)` (DR-003).
- [ ] Require conversation resolution; require linear history; do not allow force pushes or deletions.
- [ ] Apply the rule to administrators (the owner included).

## Pages (Settings, Pages)

- [ ] Source: GitHub Actions. Takes effect when the repository is public (DR-001 amendment); until then docs.yml builds and checks the site without deploying.

## Labels

- No label is needed. Assisted commits are identified by their `Co-Authored-By:` trailer, not by the `ai-assisted` label described in earlier versions of this file (CONTRIBUTING.md, docs/AI_USE_POLICY.md).

## Secrets and variables (Settings, Secrets and variables, Actions)

- [ ] None are needed today. CI uses only the default `GITHUB_TOKEN` with read permissions; the rehearsal generates a random control token per run.
- [ ] If images are later pushed to a registry, add the registry credential as an environment secret on a protected `release` environment with a required reviewer, never as a repository-wide secret.
- [ ] Production control tokens (`RATES_CONTROL_TOKEN`) live in the deployment's secret store, never in the repository or in Actions.

## Security (Settings, Code security)

- [ ] Enable Dependabot alerts and security updates for Maven, pip and GitHub Actions.
- [ ] Enable secret scanning and push protection.
- [ ] Private vulnerability reporting on, matching SECURITY.md.
- [ ] CodeQL "default setup" off, so the CodeQL workflow (`.github/workflows/codeql.yml`, added in 1.1.0) can upload its results.

## Issues (Settings, General, Features)

- [ ] Issues enabled. The forward-log workflow's `alert` job opens, comments on and closes an issue titled "Forward log: a scheduled run failed or a trading day is missing" (`.github/workflows/forward.yml`).

## Actions (Settings, Actions, General)

- [ ] Workflow permissions: read repository contents by default.
- [ ] Require approval for workflows from outside collaborators.

## Regulatory status (not a GitHub setting)

- [ ] Confirm the commencement position of the FSMA 2023 revocation of UK RTS 6 and the corresponding FCA rules before relying on docs/compliance/RTS6_INVENTORY.md (noted in DR-P6-07).
