package io.ratesalgo.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ratesalgo.backtest.InstrumentSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CarryLimitHedgeBudgetTest {

  private static final long S = 1_000_000_000L;

  private static RiskLadder ladder(final long position) {
    final RiskLadder l = Support.ladder();
    l.setPosition(l.indexOf(InstrumentSet.SOFR_10Y), position);
    l.recompute();
    return l;
  }

  @Test
  @DisplayName("exposure below the inner trigger is hedged once it has stayed above the floor for the limit")
  void hedgesAfterMaxCarry() {
    final CarryLimitHedgeBudget b = new CarryLimitHedgeBudget(HedgeBudget.threshold(1e12), 10_000, 0, 900 * S);
    final RiskLadder l = ladder(100 * InstrumentSet.MILLION);
    final double net = l.net();
    assertEquals(0.0, b.excessDv01(l, 0, 1_000 * S));
    assertEquals(1_900 * S, b.nextCheckNanos());
    assertEquals(0.0, b.excessDv01(l, 0, 1_899 * S));
    assertEquals(net, b.excessDv01(l, 0, 1_900 * S), 1e-9);
  }

  @Test
  @DisplayName("the clock resets when the exposure falls to the floor, and the inner budget still wins")
  void resetsAndDefers() {
    final CarryLimitHedgeBudget b = new CarryLimitHedgeBudget(HedgeBudget.threshold(1e12), 10_000, 0, 900 * S);
    b.excessDv01(ladder(100 * InstrumentSet.MILLION), 0, 0);
    b.excessDv01(ladder(0), 0, 500 * S);
    assertEquals(0L, b.nextCheckNanos());
    assertEquals(0.0, b.excessDv01(ladder(100 * InstrumentSet.MILLION), 0, 1_000 * S));
    assertEquals(1_900 * S, b.nextCheckNanos());
    final CarryLimitHedgeBudget inner = new CarryLimitHedgeBudget(HedgeBudget.threshold(1), 10_000, 0, 900 * S);
    final RiskLadder l = ladder(100 * InstrumentSet.MILLION);
    assertEquals(l.net(), inner.excessDv01(l, 0, 0), 1e-9);
  }

  @Test
  @DisplayName("with a quiet gap the limit waits while client fills keep arriving")
  void quietGap() {
    final CarryLimitHedgeBudget b =
        new CarryLimitHedgeBudget(HedgeBudget.threshold(1e12), 10_000, 0, 900 * S, 300 * S);
    assertEquals(300 * S, b.quietNanos());
    final RiskLadder l = ladder(100 * InstrumentSet.MILLION);
    b.excessDv01(l, 0, 0);
    assertEquals(0.0, b.excessDv01(l, 100 * S, 1_000 * S), "a client fill 100 s ago: still busy");
    assertEquals(1_200 * S, b.nextCheckNanos(), "ask again when the flow has been quiet for 300 s");
    assertEquals(l.net(), b.excessDv01(l, 300 * S, 1_200 * S), 1e-9);
  }

  @Test
  @DisplayName("parameters are checked")
  void checks() {
    assertThrows(IllegalArgumentException.class, () -> new CarryLimitHedgeBudget(HedgeBudget.threshold(1), 10, 10, S));
    assertThrows(IllegalArgumentException.class, () -> new CarryLimitHedgeBudget(HedgeBudget.threshold(1), 10, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new CarryLimitHedgeBudget(HedgeBudget.threshold(1), 10, 0, S, -1));
  }
}
