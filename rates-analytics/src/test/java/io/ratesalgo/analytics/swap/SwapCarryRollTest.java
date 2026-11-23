package io.ratesalgo.analytics.swap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.date.Dates;
import org.junit.jupiter.api.Test;

/** Swap carry and roll-down: zero on a flat curve, signed by the slope, and the break-even sum. */
class SwapCarryRollTest {

  private static final int TODAY = Dates.of(2026, 11, 2);
  private static final double BP = 1e-4;

  private static DiscountCurve curve(final double shortRate, final double longRate) {
    final int[] pillars = new int[31];
    final double[] dfs = new double[31];
    for (int y = 1; y <= 31; y++) {
      pillars[y - 1] = TODAY + 365 * y;
      final double z = shortRate + (longRate - shortRate) * Math.min(1.0, y / 10.0);
      dfs[y - 1] = Math.exp(-z * y);
    }
    return DiscountCurve.fromDiscountFactors(TODAY, pillars, dfs, Interpolation.MONOTONE_CONVEX, 0.0);
  }

  @Test
  void aFlatCurveHasNeitherCarryNorRollDown() {
    final SwapCarryRoll c = new SwapCarryRoll(SwapConvention.USD_SOFR, TODAY, 60, 30);
    c.compute(curve(0.04, 0.04), FixingHistory.EMPTY, TODAY);
    // flat continuous zero rate: every par rate is the same number up to the compounding of
    // the schedules, which differ by a stub period of a month (a simple rate over eleven months
    // is a little under the simple rate over twelve)
    assertEquals(0.0, c.carry(), 0.3 * BP);
    assertEquals(0.0, c.rollDown(), 0.3 * BP);
    assertEquals(30, c.horizonDays());
    assertTrue(c.spotRate() > 0.040 && c.spotRate() < 0.042);
  }

  @Test
  void anUpwardSlopePaysTheReceiverBothWaysAndTheSumIsTheBreakEven() {
    final DiscountCurve up = curve(0.030, 0.045);
    final SwapCarryRoll c = new SwapCarryRoll(SwapConvention.USD_SOFR, TODAY, 60, 91);
    c.compute(up, FixingHistory.EMPTY, TODAY);
    assertTrue(c.carry() > 1 * BP, "carry " + c.carry() / BP);
    assertTrue(c.rollDown() > 1 * BP, "roll " + c.rollDown() / BP);
    // the same three rates priced directly
    final OisSwapPricer p = new OisSwapPricer();
    final SwapConvention conv = SwapConvention.USD_SOFR;
    final int start = conv.spotDate(TODAY);
    final int end = Dates.addMonths(start, 60);
    final double shorter =
        p.parRate(OisSwap.of(conv, start, end - 91, 1.0, 0.0, true), up, FixingHistory.EMPTY, TODAY);
    assertEquals(c.spotRate() - shorter, c.rollDown(), 1e-15);
    assertEquals(c.spotRate() + c.carry() - shorter, c.carry() + c.rollDown(), 1e-15);
    // an inverted curve reverses both
    c.compute(curve(0.045, 0.030), FixingHistory.EMPTY, TODAY);
    assertTrue(c.carry() < -1 * BP && c.rollDown() < -1 * BP);
  }

  @Test
  void theHorizonMustFitTheSwap() {
    assertThrows(IllegalArgumentException.class,
        () -> new SwapCarryRoll(SwapConvention.USD_SOFR, TODAY, 12, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new SwapCarryRoll(SwapConvention.USD_SOFR, TODAY, 3, 80));
  }
}
