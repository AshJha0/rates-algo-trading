"""The committed QuantLib golden reproduces when regenerated on this machine.

Golden files are compared numerically, not byte for byte: a libm difference between platforms
may move the last bits, so every number must agree to 1e-12 relative with an absolute floor of
1e-6 (DECISIONS.md DR-P1-06). The floor matters for par-swap PVs, which are sums of cancelling
terms of order 1e6 and land at 1e-8 or so on any platform: the first Linux CI run saw 9.3e-9
against 7.5e-9 from Windows, pure rounding noise, far below one cent. Skipped when QuantLib is not installed; CI installs it through the
``golden`` extra.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / "data" / "golden" / "analytics_golden.json"


def _compare(path: str, a: Any, b: Any, failures: list[str]) -> None:
    if isinstance(a, dict):
        assert isinstance(b, dict), path
        assert a.keys() == b.keys(), path
        for key in a:
            if key in ("python_version", "platform"):
                continue
            _compare(f"{path}.{key}", a[key], b[key], failures)
    elif isinstance(a, list):
        assert isinstance(b, list) and len(a) == len(b), path
        for i, (x, y) in enumerate(zip(a, b, strict=True)):
            _compare(f"{path}[{i}]", x, y, failures)
    elif isinstance(a, float) and not isinstance(a, bool):
        if not math.isclose(a, float(b), rel_tol=1e-12, abs_tol=1e-6):
            failures.append(f"{path}: {a!r} != {b!r}")
    elif a != b:
        failures.append(f"{path}: {a!r} != {b!r}")


def test_golden_regenerates_within_tolerance() -> None:
    ql = pytest.importorskip("QuantLib")
    from ratesresearch.goldens import generate

    committed = json.loads(GOLDEN.read_text(encoding="utf-8"))
    if committed["quantlib_version"] != ql.__version__:
        pytest.skip(f"golden made with QuantLib {committed['quantlib_version']}")
    fresh = json.loads(json.dumps(generate(ROOT / "data" / "fixtures")))
    failures: list[str] = []
    _compare("golden", committed, fresh, failures)
    assert not failures, "\n".join(failures[:20])
    assert fresh["instrument_count"] >= 200


def test_golden_structure() -> None:
    golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
    assert golden["golden_version"] == 1
    assert golden["valuation_date"] == "2025-01-15"
    assert {c["id"] for c in golden["curves"]} == {"USD-OIS", "EUR-OIS", "GBP-OIS", "EUR-6M"}
    for curve in golden["curves"]:
        assert len(curve["pillar_dates"]) == len(curve["instruments"])
        dfs = curve["discount_factors"]
        assert all(0.0 < d < 1.0 for d in dfs)
    count = (
        len(golden["swaps"])
        + len(golden["bonds"])
        + len(golden["sofr_futures"])
        + len(golden["conversion_factors"])
        + sum(len(b["bonds"]) for b in golden["basis"])
    )
    assert count == golden["instrument_count"] >= 200
