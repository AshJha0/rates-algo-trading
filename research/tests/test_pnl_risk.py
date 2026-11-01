"""Daily-P&L volatility, Sharpe ratio and maximum drawdown (mirror of BandRiskTest)."""

from __future__ import annotations

import math

import pytest

from ratesresearch.stats import pnl_risk


def test_hand_worked_series() -> None:
    r = pnl_risk([100.0, -50.0, 30.0, -100.0, 30.0])
    assert r.days == 5
    assert r.mean == pytest.approx(2.0)
    assert r.volatility == pytest.approx(math.sqrt(6070.0))
    assert r.sharpe == pytest.approx(2.0 / math.sqrt(6070.0))
    assert r.annualised_sharpe == pytest.approx(r.sharpe * math.sqrt(252))
    assert r.max_drawdown == pytest.approx(120.0)


def test_edges() -> None:
    assert pnl_risk([-10.0, -20.0, 5.0]).max_drawdown == pytest.approx(30.0)
    assert pnl_risk([1.0, 2.0, 3.0]).max_drawdown == 0.0
    assert math.isnan(pnl_risk([4.0, 4.0]).sharpe)
    with pytest.raises(ValueError):
        pnl_risk([1.0])
