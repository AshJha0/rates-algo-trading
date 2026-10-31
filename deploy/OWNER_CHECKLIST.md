# Owner-side checklist

Settings that cannot be changed from a pull request. Each needs the repository owner in the GitHub web interface (or `gh` with admin rights). Tick each with the date when done.

## Branch protection on `main` (Settings, Branches, Add rule)

- [ ] Require a pull request before merging, with 1 approving review; dismiss stale approvals on new commits.
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

- [ ] Create the label `ai-assisted` (docs/AI_USE_POLICY.md).

## Secrets and variables (Settings, Secrets and variables, Actions)

- [ ] None are needed today. CI uses only the default `GITHUB_TOKEN` with read permissions; the rehearsal generates a random control token per run.
- [ ] If images are later pushed to a registry, add the registry credential as an environment secret on a protected `release` environment with a required reviewer, never as a repository-wide secret.
- [ ] Production control tokens (`RATES_CONTROL_TOKEN`) live in the deployment's secret store, never in the repository or in Actions.

## Security (Settings, Code security)

- [ ] Enable Dependabot alerts and security updates for Maven, pip and GitHub Actions.
- [ ] Enable secret scanning and push protection.
- [ ] Private vulnerability reporting on, matching SECURITY.md.

## Actions (Settings, Actions, General)

- [ ] Workflow permissions: read repository contents by default.
- [ ] Require approval for workflows from outside collaborators.

## Regulatory status (not a GitHub setting)

- [ ] Confirm the commencement position of the FSMA 2023 revocation of UK RTS 6 and the corresponding FCA rules before relying on docs/compliance/RTS6_INVENTORY.md (noted in DR-P6-07).
