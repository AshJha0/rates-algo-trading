# Security

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository (Security tab, "Report a vulnerability"). Do not open a public issue for a security problem. You should receive an acknowledgement within seven days.

## What is in scope

This project runs against simulated venues and public data. It has no live connectivity and no customer data. Since 1.1.0 the deployment files use FIX Logon credentials and TLS key stores for the simulated venues; their passwords come from environment variables or files at deploy time and are never in the repository (deploy/README.md, "Secrets"). Reports that matter here: a secret written to a log or journal, a plain connection accepted beyond loopback, or a TLS check that can be skipped. Other reports that matter: journal parsing that can be made to read out of bounds or loop, SBE decoding on malformed input, and anything in the build or CI configuration that could run untrusted code.

## Secrets

There are no secrets in this repository and none are needed to build it or to run the simulator preset. The rehearsal makes throwaway TLS material and passwords for each run and deletes them at the end; `TradingProcessMainTest` checks that neither password is in any file under the journal directory. CI uses only the default `GITHUB_TOKEN`. A pull request that adds a credential, token or private key is closed; if one is ever committed by mistake it is rotated and the history is rewritten with a note in CHANGELOG.md.

## Changes in 1.2.0

- The v2 desk profile's simulated venues (`SimulatedVenuesV2`) take the same acceptor options as the version 1 simulators: TLS, Logon credentials and a FIX store per venue. `deploy/config/trading-v2.yaml` sets TLS and credentials as the other deployment files do.
- CPU pinning (`CoreAffinity`, DR-R6-L01) and journal page locking call `sched_setaffinity` and `mlock` through the JDK's foreign function API, a restricted method. The trading image starts the JVM with `--enable-native-access=ALL-UNNAMED`. No native library of the project's own is loaded; off Linux, or when the call is refused, the thread runs unpinned.
- Journals written with `journalHash: word64` (journal format version 2) are tamper-evident in the same sense as FNV-1a journals: a chain of 64-bit non-cryptographic hashes that detects accidental damage and naive edits, not a deliberate forger who can recompute the chain. Readers refuse a version-2 header that names any other algorithm.

## Dependencies

As of 1.2.0 (2026-11-24):

- Java: every dependency and plugin version is a fixed version in the Maven poms. There is no checksum verification beyond Maven Central's.
- Python: `research/requirements-lock.txt` pins every dependency of the research package and its extras. CI and the forward-log job install with it as a pip constraints file (`ci.yml`, `forward.yml`); `research/pyproject.toml` keeps the supported ranges. The lock has no hashes. `QuantLib==1.44` stays pinned in the `golden` extra, so the committed golden file regenerates exactly (DR-P1-06). The documentation site pins exact versions in `docs/site/requirements.txt`.
- GitHub Actions are pinned by commit SHA in every workflow, with the version tag in a trailing comment (DR-RV-C07).
- Container images start from `eclipse-temurin:25-jre`, a moving tag, not a digest.
- There is no software bill of materials.

Dependency updates and code scanning: `.github/dependabot.yml` proposes grouped weekly updates for Maven, pip (`/research`, `/docs/site`) and Actions. `.github/workflows/codeql.yml` scans Java and Python on every pull request, on push to `main` and weekly; it reports and does not gate. CodeQL has run on pull requests since 1.1.0: its finding on the rehearsal's TLS context (a fallback without the TLS 1.3 floor) was fixed in 222e9c1 and 7fee46c. CodeQL "default setup" must be off in the repository settings for the workflow to upload results (owner item, deploy/OWNER_CHECKLIST.md). This answers review finding E7 except the image digests and the bill of materials.

One person maintains the project; security reports and dependency alerts are read by that person, with no second reviewer.
