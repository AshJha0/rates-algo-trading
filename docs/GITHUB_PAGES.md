# Publishing the documentation site

The site at <https://ashjha0.github.io/rates-algo-trading/> is built with MkDocs (Material theme) from `mkdocs.yml`, the pages under `docs/site/pages/` and the root documents pulled in by `docs/site/hooks.py`.

## How it is published

The `Docs` workflow in `.github/workflows/docs.yml` runs `mkdocs build --strict` on every push and pull request. The build fails on any broken link, missing snippet file or unrecognised page, so the site cannot drift from the documents.

Deployment uses GitHub Actions as the Pages source. Until the repository's Pages setting is switched to "GitHub Actions" (Settings, Pages, Build and deployment, Source), the deploy step has nowhere to publish and the site is built and checked only. Once the source is set, the workflow uploads the built `site/` directory as a Pages artifact and deploys it on pushes to `main`. No `gh-pages` branch is used.

## Build locally

From the repository root:

```bash
python -m pip install -r docs/site/requirements.txt
mkdocs build --strict
mkdocs serve
```

`mkdocs serve` opens the site at <http://127.0.0.1:8000/rates-algo-trading/>. The `--strict` flag is the same one CI uses; run it before pushing a documentation change.

## How the root documents appear on the site

`docs/site/hooks.py` maps each site page (for example `learn.md`) to a root file (`LEARN.md`) and registers it as a generated file in MkDocs's `on_files` hook. The root files stay where GitHub shows them, and the site reads them in place, so there is one copy of each document. Diagrams are embedded from `docs/diagrams/*.mmd` with the `pymdownx.snippets` extension inside Mermaid fences (`docs/site/pages/diagrams.md`).

The compliance control inventory (`docs/compliance/RTS6_INVENTORY.md`) and the runbook index (`docs/runbooks/README.md`) are published the same way, as the pages Compliance inventory and Runbooks; the individual runbooks and templates they link to open on GitHub.

Relative links inside the root documents (for example `docs/ARCHITECTURE.md` from the README) are rewritten by the hook to their site equivalents where a mapping exists; links to files that are not on the site point at the GitHub tree.
