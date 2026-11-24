# ratesresearch

Python research package for [rates-algo-trading](../README.md). Python 3.12, pytest, hypothesis, mypy strict, ruff.

## What is here

Phase 0:

- `ratesresearch/provenance.py`: the `DataSource` record, the `SOURCES` tuple of public data sources, and `sha256_of`, `write_manifest`, `load_manifest` for pinning files.
- `ratesresearch/fnv.py`: `fnv1a64(data, seed_prev_hash)`, the exact chain hash of the Java journal's format version 1, and since 1.2.0 `word64` and `chain_hash` for format version 2 (`API_SPEC.md` Sections 1.3 and 1.6).

Phase 1 (analytics, workstream W1):

- `ratesresearch/goldens/`: the QuantLib golden generator. `python -m ratesresearch.goldens` (run in `research/`, with `pip install -e "research[dev,golden]"`) writes `data/golden/analytics_golden.json`; `tests/test_goldens.py` regenerates it and compares every number to 1e-12 (API_SPEC.md Section 8, DECISIONS.md DR-P1-06).
- `ratesresearch/data/`: downloaders for the public sources in `data/SOURCES.md`, each recording URL, licence summary, date, size and SHA-256 in `data/manifests/downloads.json`; raw files go to `data/raw/` (ignored) and only small fixtures are committed to `data/fixtures/` with a provenance file each. `python -m ratesresearch.data --all --raw-dir data/raw --fixtures-dir data/fixtures`.
- `ratesresearch/curves.py`: principal components of daily Treasury par-curve changes (level, slope, curvature) and regression and PCA hedge ratios with rolling stability; results in `data/fixtures/ust_pca_summary.json`.

Phase 5 (back-testing and research, `API_SPEC.md` Section 16):

- `presets.py`: read, validate and write `data/presets.yaml` (the schema is a contract with the Java side).
- `assumptions.py`: the declared presets (synthetic market, competing dealers, hedge costs) and the win-probability slope band (DR-008).
- `hitrate.py`: logistic win probability against spread-to-cover, size, tenor, fixing window and dealer count, fitted to labels from the declared simulator model; calibration table and SVG; out-of-sample log-loss against a constant; writes `usd_rfq_hit_rate`.
- `flow.py`: RFQ intensity around fixings and releases (quasi-Poisson regression with event bumps) and censored lognormal sizes, fitted from DTCC SDR public dissemination; writes `usd_rfq_flow`.
- `data/dtcc.py`, `data/finra.py`: downloader and loader for DTCC end-of-day rates files; loader for the FINRA Treasury layout and its synthetic fixture (FINRA needs an account, see `data/SOURCES.md`).
- `markouts.py`: markout curves by tier, size and product; informed-flow share; toxicity table for tiering.
- `spread.py`: expected edge and optimal half-spread per product across the band; sensitivity to volatility and dealer count (the same rule as the Java `ModelSpreadStrategy`).
- `stats.py`: SplitMix64, moving-block bootstrap, Welch, Holm, identical to the Java `Stats` on the shared fixture; `paired_t` and `paired_block_bootstrap_ci` for designs where treatment and baseline replay the same sessions, and `benjamini_hochberg` across experiments.
- `prereg.py`: pre-registration validator and ledger reader. `python -m ratesresearch.prereg data/ledger/ledger.jsonl` verifies the hash chain and prints the looks per evaluation data set across pre-registration ids (CI runs it); since 1.2.0 `bh LEDGER FAMILY` adds a family's Benjamini-Hochberg table and `validate FILE...` checks version 2 registrations (`API_SPEC.md` Section 16.10).
- `forward/`: `fetch` downloads a day's real rates, `check` re-derives the forward ledger's arithmetic, `health` reports a business day without a decision (the forward-log workflow opens an issue from it).
- `backtest_analysis.py`: reads the Java runner's tables, reconciles attribution, recomputes experiment statistics, renders tables.
- `notebooks/`: thin wrappers over the modules, executed by `tools/run_notebooks.py` in CI and rendered into the site.
- `recipes/`: Python cookbook recipes, run by `tools/check_cookbook.py`.

Round 2 and later:

- `edge/`: the edge studies E1 to E6 on public data; `python -m ratesresearch.edge.run` prints the summary ([EDGE_STUDIES.md](../docs/research/EDGE_STUDIES.md)).
- `simcal/`: the calibration of simulator v2, SIM3-CAL-001 (`python -m ratesresearch.simcal manifest|calibrate|validate|params|competitor`; the DTCC files and the parsed cache stay outside the repository), which writes `data/calibration/sim_v2_params.json` ([SIM_V2_CALIBRATION.md](../docs/research/SIM_V2_CALIBRATION.md)).

Beside the package: `prereg/` holds the audit, edge, Round 5 and calibration registrations (the back-test ones of P5, R2B, V1H and V1C are under `data/preregs/`); `audit/`, `hedge/` and `forward/` hold the scripts of the P&L audit, the hedge experiments and the forward log. R5-FAM-001 in `prereg/` is unsigned and has not run.

How a fitted preset reaches the quoting engine is drawn in [research to production parameters](../docs/site/pages/diagrams.md#research-to-production-parameters).

## Run

From the repository root:

```bash
python -m pip install -c research/requirements-lock.txt -e "research[dev,notebooks]"
ruff check research
ruff format --check research
(cd research && mypy)
pytest -q research
python tools/run_notebooks.py
```

`requirements-lock.txt` pins every dependency, for all platforms; CI and the forward-log job install with it as a constraints file, and its header says how to regenerate it. `pyproject.toml` keeps the supported ranges.

Refit the presets (the DTCC step needs the downloaded files outside the repository):

```bash
python -c "from ratesresearch import assumptions, hitrate; assumptions.write_all('data/presets.yaml'); hitrate.main('.')"
python -c "from ratesresearch import flow; flow.main('PATH_TO_DTCC_ZIPS', '.')"
```
