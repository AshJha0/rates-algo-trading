# ratesresearch

Python research package for [rates-algo-trading](../README.md). Python 3.12, pytest, hypothesis, mypy strict, ruff.

## What is here

Phase 0:

- `ratesresearch/provenance.py`: the `DataSource` record, the `SOURCES` tuple of public data sources, and `sha256_of`, `write_manifest`, `load_manifest` for pinning files.
- `ratesresearch/fnv.py`: `fnv1a64(data, seed_prev_hash)`, the exact chain hash the Java journal uses (`API_SPEC.md` Section 1).

Phase 5 (back-testing and research, `API_SPEC.md` Section 16):

- `presets.py`: read, validate and write `data/presets.yaml` (the schema is a contract with the Java side).
- `assumptions.py`: the declared presets (synthetic market, competing dealers, hedge costs) and the win-probability slope band (DR-008).
- `hitrate.py`: logistic win probability against spread-to-cover, size, tenor, fixing window and dealer count, fitted to labels from the declared simulator model; calibration table and SVG; out-of-sample log-loss against a constant; writes `usd_rfq_hit_rate`.
- `flow.py`: RFQ intensity around fixings and releases (quasi-Poisson regression with event bumps) and censored lognormal sizes, fitted from DTCC SDR public dissemination; writes `usd_rfq_flow`.
- `data/dtcc.py`, `data/finra.py`: downloader and loader for DTCC end-of-day rates files; loader for the FINRA Treasury layout and its synthetic fixture (FINRA needs an account, see `data/SOURCES.md`).
- `markouts.py`: markout curves by tier, size and product; informed-flow share; toxicity table for tiering.
- `spread.py`: expected edge and optimal half-spread per product across the band; sensitivity to volatility and dealer count (the same rule as the Java `ModelSpreadStrategy`).
- `stats.py`: SplitMix64, moving-block bootstrap, Welch, Holm, identical to the Java `Stats` on the shared fixture.
- `prereg.py`: pre-registration validator and ledger reader.
- `backtest_analysis.py`: reads the Java runner's tables, reconciles attribution, recomputes experiment statistics, renders tables.
- `notebooks/`: thin wrappers over the modules, executed by `tools/run_notebooks.py` in CI and rendered into the site.
- `recipes/`: Python cookbook recipes, run by `tools/check_cookbook.py`.

## Run

From the repository root:

```bash
python -m pip install -e "research[dev,notebooks]"
ruff check research
ruff format --check research
(cd research && mypy)
pytest -q research
python tools/run_notebooks.py
```

Refit the presets (the DTCC step needs the downloaded files outside the repository):

```bash
python -c "from ratesresearch import assumptions, hitrate; assumptions.write_all('data/presets.yaml'); hitrate.main('.')"
python -c "from ratesresearch import flow; flow.main('PATH_TO_DTCC_ZIPS', '.')"
```
