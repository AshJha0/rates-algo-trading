# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository (Security tab, "Report a vulnerability"). Do not open a public issue for a security problem. You should receive an acknowledgement within seven days.

## What is in scope

This project runs against simulated venues and public data. It has no live connectivity, no credentials and no customer data. Reports that matter most: journal parsing that can be made to read out of bounds or loop, SBE decoding on malformed input, and anything in the build or CI configuration that could run untrusted code.

## Secrets

There are no secrets in this repository and none are needed to build or run it. CI uses only the default `GITHUB_TOKEN`. A pull request that adds a credential, token or private key is closed; if one is ever committed by mistake it is rotated and the history is rewritten with a note in CHANGELOG.md.

## Dependencies

Java dependencies are pinned in the Maven poms and Python dependencies in `research/pyproject.toml` and `docs/site/requirements.txt`. Dependabot alerts are reviewed when they arrive.
