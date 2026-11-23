# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository (Security tab, "Report a vulnerability"). Do not open a public issue for a security problem. You should receive an acknowledgement within seven days.

## What is in scope

This project runs against simulated venues and public data. It has no live connectivity, no credentials and no customer data. Reports that matter most: journal parsing that can be made to read out of bounds or loop, SBE decoding on malformed input, and anything in the build or CI configuration that could run untrusted code.

## Secrets

There are no secrets in this repository and none are needed to build or run it. CI uses only the default `GITHUB_TOKEN`. A pull request that adds a credential, token or private key is closed; if one is ever committed by mistake it is rotated and the history is rewritten with a note in CHANGELOG.md.

## Dependencies

As of the 1.1.0 draft (2026-11-23):

- Java: every dependency and plugin version is a fixed version in the Maven poms. There is no checksum verification beyond Maven Central's.
- Python: `research/requirements-lock.txt` pins every dependency of the research package and its extras. CI and the forward-log job install with it as a pip constraints file (`ci.yml`, `forward.yml`); `research/pyproject.toml` keeps the supported ranges. The lock has no hashes. `QuantLib==1.44` stays pinned in the `golden` extra, so the committed golden file regenerates exactly (DR-P1-06). The documentation site pins exact versions in `docs/site/requirements.txt`.
- GitHub Actions are pinned by commit SHA in every workflow, with the version tag in a trailing comment (DR-RV-C07).
- Container images start from `eclipse-temurin:25-jre`, a moving tag, not a digest.
- There is no software bill of materials.

Dependency updates and code scanning: `.github/dependabot.yml` proposes grouped weekly updates for Maven, pip (`/research`, `/docs/site`) and Actions. `.github/workflows/codeql.yml` scans Java and Python on every pull request, on push to `main` and weekly; it reports and does not gate. Neither has run on GitHub as of this draft. CodeQL "default setup" must be off in the repository settings for the workflow to upload results (owner item, deploy/OWNER_CHECKLIST.md). This answers review finding E7 except the image digests and the bill of materials.

One person maintains the project; security reports and dependency alerts are read by that person, with no second reviewer.
