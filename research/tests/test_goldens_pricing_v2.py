"""The committed pricing v2 golden reproduces when regenerated on this machine.

Compared numerically. The file holds results of iterative solves and of central differences of
prices, so the last digits depend on the platform's libm and BLAS: every number must agree to
1e-7 relative with an absolute floor of 1e-9, loose enough for that noise and far tighter than
any tolerance the Java test applies to the same numbers (1e-10 on discount factors is applied
there to Java against this file, not to this file against itself). Skipped when QuantLib is not
installed.
"""

from __future__ import annotations

import itertools
import json
import math
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / "data" / "golden" / "pricing_v2_golden.json"
NOISE_KEYS = {
    "python_version",
    "platform",
    # residuals of converged solves and a discretisation gap: checked by bounds below
    "max_reprice_error",
    "bond_risk_reprice_error",
    "max_abs_log_df_gap",
}


def _compare(path: str, a: Any, b: Any, failures: list[str]) -> None:
    if isinstance(a, dict):
        assert isinstance(b, dict), path
        assert a.keys() == b.keys(), path
        for key in a:
            if key not in NOISE_KEYS:
                _compare(f"{path}.{key}", a[key], b[key], failures)
    elif isinstance(a, list):
        assert isinstance(b, list) and len(a) == len(b), path
        for i, (x, y) in enumerate(zip(a, b, strict=True)):
            _compare(f"{path}[{i}]", x, y, failures)
    elif isinstance(a, float) and not isinstance(a, bool):
        if not math.isclose(a, float(b), rel_tol=1e-7, abs_tol=1e-9):
            failures.append(f"{path}: {a!r} != {b!r}")
    elif a != b:
        failures.append(f"{path}: {a!r} != {b!r}")


def test_pricing_v2_golden_regenerates_within_tolerance() -> None:
    ql = pytest.importorskip("QuantLib")
    from ratesresearch.goldens.pricing_v2 import generate_v2

    committed = json.loads(GOLDEN.read_text(encoding="utf-8"))
    if committed["quantlib_version"] != ql.__version__:
        pytest.skip(f"golden made with QuantLib {committed['quantlib_version']}")
    fresh = json.loads(json.dumps(generate_v2()))
    failures: list[str] = []
    _compare("golden", committed, fresh, failures)
    assert not failures, "\n".join(failures[:20])
    for curve in fresh["curves"]:
        assert curve["max_reprice_error"] <= 1e-12
    assert fresh["bond_risk_reprice_error"] <= 1e-12


def test_pricing_v2_golden_structure() -> None:
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
    assert golden["golden_version"] == 1
    assert golden["valuation_date"] == "2026-11-02"
    assert [c["interpolation"] for c in golden["curves"]] == [
        "LINEAR_LOG_DF",
        "STEP_THEN_MONOTONE_CONVEX",
        "STEP_THEN_TENSION_SPLINE",
    ]
    assert len(golden["inputs"]) == 14
    for curve in golden["curves"]:
        dfs = curve["discount_factors"]
        assert len(dfs) == len(curve["pillar_dates"]) == 14
        assert all(0.0 < d < 1.0 for d in dfs)
        assert all(a > b for a, b in itertools.pairwise(dfs))
    check = golden["tension_spline_variational_check"]
    assert check["max_abs_log_df_gap"] <= 1e-6 * check["largest_abs_log_df"]
    for row in golden["hull_white_convexity"]:
        assert math.isclose(row["closed_form"], row["numerical"], rel_tol=1e-9)
        assert row["closed_form"] > row["hull_forward_looking"] > 0.0
    assert len(golden["bonds"]) == 4
    for bond in golden["bonds"]:
        assert -0.01 < bond["z_spread"] < 0.0
        assert bond["spread_dv01"] < 0.0
        assert len(bond["quote_dv01"]) == 14
        assert len(bond["risk_ladder"]) == len(golden["risk_tenor_years"]) == 8
