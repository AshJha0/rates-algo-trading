# ratesresearch

Python research package for [rates-algo-trading](../README.md). Python 3.12, pytest, hypothesis, mypy strict, ruff.

## What is here (Phase 0)

- `ratesresearch/provenance.py`: the `DataSource` record, the `SOURCES` tuple of public data sources (nothing downloaded yet), and `sha256_of`, `write_manifest`, `load_manifest` for pinning files.
- `ratesresearch/fnv.py`: `fnv1a64(data, seed_prev_hash)`, the exact chain hash the Java journal uses (see `API_SPEC.md` Section 1), so a Python script can verify a journal file later.
- `tests/`: pytest plus hypothesis properties for both modules.

## What Phase 1 added (analytics, workstream W1)

- `ratesresearch/goldens/`: the QuantLib golden generator. `python -m ratesresearch.goldens` (run in `research/`, with `pip install -e "research[dev,golden]"`) writes `data/golden/analytics_golden.json`; `tests/test_goldens.py` regenerates it and compares every number to 1e-12 (API_SPEC.md Section 8, DECISIONS.md DR-P1-06).
- `ratesresearch/data/`: downloaders for the public sources in `data/SOURCES.md`, each recording URL, licence summary, date, size and SHA-256 in `data/manifests/downloads.json`; raw files go to `data/raw/` (ignored) and only small fixtures are committed to `data/fixtures/` with a provenance file each. `python -m ratesresearch.data --all --raw-dir data/raw --fixtures-dir data/fixtures`.
- `ratesresearch/curves.py`: principal components of daily Treasury par-curve changes (level, slope, curvature) and regression and PCA hedge ratios with rolling stability; results in `data/fixtures/ust_pca_summary.json`.

Calibration of venue-simulator parameters and back-test attribution are planned for P2 onwards.

## Run

From the repository root:

```bash
python -m pip install -e "research[dev]"
ruff check research
ruff format --check research
(cd research && mypy)
pytest -q research
```
