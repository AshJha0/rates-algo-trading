# ratesresearch

Python research package for [rates-algo-trading](../README.md). Python 3.12, pytest, hypothesis, mypy strict, ruff.

## What is here (Phase 0)

- `ratesresearch/provenance.py`: the `DataSource` record, the `SOURCES` tuple of public data sources (nothing downloaded yet), and `sha256_of`, `write_manifest`, `load_manifest` for pinning files.
- `ratesresearch/fnv.py`: `fnv1a64(data, seed_prev_hash)`, the exact chain hash the Java journal uses (see `API_SPEC.md` Section 1), so a Python script can verify a journal file later.
- `tests/`: pytest plus hypothesis properties for both modules.

Calibration of venue-simulator parameters, curve fitting against QuantLib and back-test attribution are planned for P1 onwards.

## Run

From the repository root:

```bash
python -m pip install -e "research[dev]"
ruff check research
ruff format --check research
(cd research && mypy)
pytest -q research
```
