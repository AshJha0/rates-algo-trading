"""Statistics, verdicts and ledger records shared by the edge studies.

The rules are the ones in ``research/prereg/edge_programme.yaml``: moving-block bootstrap over
the unit of analysis (SplitMix64 indices, as ``ratesresearch.stats``), percentile interval,
two-sided centred bootstrap p, bootstrap standard error, Holm over the family of primary tests.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import numpy as np
import numpy.typing as npt
import pandas as pd

from ratesresearch import prereg
from ratesresearch.stats import holm

RESAMPLES = 2000
LEVEL = 0.95
SEED = 20261031
ALPHA = 0.05

FloatArray = npt.NDArray[np.float64]


def bootstrap_means(x: Any, block: int, resamples: int = RESAMPLES, seed: int = SEED) -> FloatArray:
    """Means of ``resamples`` moving-block resamples of ``x`` (time order kept within blocks)."""
    arr = np.asarray(x, dtype=np.float64)
    n = len(arr)
    if n == 0:
        raise ValueError("empty sample")
    block = max(1, min(block, n))
    starts_n = n - block + 1
    nblocks = -(-n // block)
    starts = splitmix_ints(seed, resamples * nblocks, starts_n).reshape(resamples, nblocks)
    idx = (starts[:, :, None] + np.arange(block)[None, None, :]).reshape(resamples, -1)[:, :n]
    out: FloatArray = arr[idx].mean(axis=1)
    return out


def splitmix_ints(seed: int, count: int, bound: int) -> npt.NDArray[np.int64]:
    """``count`` successive ``SplitMix64(seed).next_int(bound)`` values, vectorised."""
    with np.errstate(over="ignore"):
        k = np.arange(1, count + 1, dtype=np.uint64)
        z = np.uint64(seed & ((1 << 64) - 1)) + k * np.uint64(0x9E3779B97F4A7C15)
        z = (z ^ (z >> np.uint64(30))) * np.uint64(0xBF58476D1CE4E5B9)
        z = (z ^ (z >> np.uint64(27))) * np.uint64(0x94D049BB133111EB)
        z = z ^ (z >> np.uint64(31))
    return ((z >> np.uint64(1)) % np.uint64(bound)).astype(np.int64)


def mean_test(
    x: Any,
    block: int,
    null: float = 0.0,
    seed: int = SEED,
) -> dict[str, float]:
    """Mean, 95 percent interval, bootstrap standard error and two-sided p against ``null``."""
    arr = np.asarray(x, dtype=np.float64)
    m = float(arr.mean())
    means = bootstrap_means(arr, block, seed=seed)
    alpha = (1 - LEVEL) / 2
    exceed = int(np.sum(np.abs(means - m) >= abs(m - null)))
    return {
        "n": len(arr),
        "mean": m,
        "ci_low": float(np.quantile(means, alpha)),
        "ci_high": float(np.quantile(means, 1 - alpha)),
        "se": float(means.std(ddof=1)),
        "p": (1 + exceed) / (RESAMPLES + 1),
    }


def equivalence_test(
    x: Any, block: int, low: float, high: float, seed: int = SEED
) -> dict[str, float]:
    """Two one-sided bootstrap tests that the mean lies inside (low, high); p is the larger."""
    arr = np.asarray(x, dtype=np.float64)
    m = float(arr.mean())
    means = bootstrap_means(arr, block, seed=seed)
    centred = means - m
    # one-sided: H0 mean <= low, rejected when the observed m is far above low
    p_low = (1 + int(np.sum(centred >= m - low))) / (RESAMPLES + 1)
    p_high = (1 + int(np.sum(-centred >= high - m))) / (RESAMPLES + 1)
    alpha = (1 - LEVEL) / 2
    return {
        "n": len(arr),
        "mean": m,
        "ci_low": float(np.quantile(means, alpha)),
        "ci_high": float(np.quantile(means, 1 - alpha)),
        "se": float(means.std(ddof=1)),
        "p": max(p_low, p_high),
        "p_low": p_low,
        "p_high": p_high,
    }


def judge(test: dict[str, Any], sign: int, threshold: float, null: float = 0.0) -> str:
    """passed, reversed or failed under the programme test rule (needs ``holm_p`` set)."""
    significant = test["holm_p"] < ALPHA
    if sign > 0:
        right_side = test["mean"] > null and test["ci_low"] > null
        wrong_side = test["mean"] < null and test["ci_high"] < null
        big = test["mean"] - null >= threshold
    else:
        right_side = test["mean"] < null and test["ci_high"] < null
        wrong_side = test["mean"] > null and test["ci_low"] > null
        big = null - test["mean"] >= threshold
    if significant and right_side and big:
        return "passed"
    if significant and wrong_side:
        return "reversed"
    return "failed"


def apply_holm(tests: dict[str, dict[str, Any]]) -> None:
    """Set ``holm_p`` on every test that has a ``p`` (tests without one are not in the family)."""
    names = [k for k, t in tests.items() if t.get("p") is not None]
    adjusted = holm([float(tests[k]["p"]) for k in names])
    for k, a in zip(names, adjusted, strict=True):
        tests[k]["holm_p"] = a


def rounded(obj: Any, digits: int = 8) -> Any:
    """Floats rounded for stable ledger text; numpy scalars converted."""
    if isinstance(obj, dict):
        return {str(k): rounded(v, digits) for k, v in obj.items()}
    if isinstance(obj, list | tuple):
        return [rounded(v, digits) for v in obj]
    if isinstance(obj, np.integer):
        return int(obj)
    if isinstance(obj, float | np.floating):
        v = float(obj)
        return None if not np.isfinite(v) else float(f"{v:.{digits}g}")
    return obj


def files_sha256(paths: Sequence[Path]) -> str:
    """SHA-256 over the sorted (name, file SHA-256) pairs of the data files a study read."""
    h = hashlib.sha256()
    for p in sorted(paths, key=lambda q: q.name):
        h.update(f"{p.name}:{hashlib.sha256(p.read_bytes()).hexdigest()}\n".encode())
    return h.hexdigest()


def git_state(root: Path) -> tuple[str, bool]:
    try:
        commit = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True
        ).stdout.strip()
        dirty = bool(
            subprocess.run(
                ["git", "status", "--porcelain", "--", "research/ratesresearch"],
                cwd=root,
                capture_output=True,
                text=True,
                check=True,
            ).stdout.strip()
        )
    except (OSError, subprocess.CalledProcessError):
        return "unknown", True
    return commit, dirty


def append_ledger(path: Path, record: dict[str, Any]) -> dict[str, Any]:
    """Append ``record`` with ledger_version, seq and prev_sha256, as the Java Ledger does."""
    entries = prereg.read_ledger(path)
    lines = [x for x in path.read_text(encoding="utf-8").splitlines() if x.strip()]
    full = dict(record)
    full["ledger_version"] = 1
    full["seq"] = len(entries) + 1
    full["prev_sha256"] = prereg.sha256_text(lines[-1]) if lines else prereg.GENESIS
    full.setdefault("recorded_utc", datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"))
    line = json.dumps(rounded(full), sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    with path.open("a", encoding="utf-8", newline="\n") as f:
        f.write(line + "\n")
    return full


def epoch_seconds(t: Any) -> FloatArray:
    """Seconds since 1970-01-01 UTC of a tz-aware datetime Series, whatever its resolution."""
    out: FloatArray = (t - pd.Timestamp("1970-01-01", tz="UTC")).dt.total_seconds().to_numpy(float)
    return out
