# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository (Security tab, "Report a vulnerability"). Do not open a public issue for a security problem. You should receive an acknowledgement within seven days.

## What is in scope

This project runs against simulated venues and public data. It has no live connectivity, no credentials and no customer data. Reports that matter most: journal parsing that can be made to read out of bounds or loop, SBE decoding on malformed input, and anything in the build or CI configuration that could run untrusted code.

## Secrets

There are no secrets in this repository and none are needed to build or run it. CI uses only the default `GITHUB_TOKEN`. A pull request that adds a credential, token or private key is closed; if one is ever committed by mistake it is rotated and the history is rewritten with a note in CHANGELOG.md.

## Dependencies

As checked on 2026-11-23:

- Java: every dependency and plugin version is a fixed version in the Maven poms. There is no checksum verification beyond Maven Central's.
- Python research package: `research/pyproject.toml` gives lower bounds only (for example `numpy>=1.26`), and there is no lock file, so two installs on different days can resolve different versions. The one exact pin is `QuantLib==1.44` in the `golden` extra, so the committed golden file regenerates exactly (DR-P1-06).
- Documentation site: `docs/site/requirements.txt` pins exact versions.
- GitHub Actions are referenced by major-version tag (for example `actions/checkout@v4`), not by commit SHA.
- Container images start from `eclipse-temurin:25-jre`, a moving tag, not a digest.
- There is no software bill of materials.

<!-- FIX-ROUND: the research-ci lane is adding Dependabot and CodeQL. If they merge, replace the line below with "Dependency updates and code scanning: see .github/dependabot.yml and the CodeQL workflow." and confirm both files exist on main. -->
Dependency updates and code scanning: Dependabot alerts are enabled on the repository (checked with `gh api`), but no Dependabot version-update configuration and no CodeQL workflow are in the repository as of 2026-11-23. This is review finding E7.

One person maintains the project; security reports and dependency alerts are read by that person, with no second reviewer.
