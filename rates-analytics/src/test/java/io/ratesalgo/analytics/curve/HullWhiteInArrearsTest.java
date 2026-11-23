package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The in-arrears futures adjustments against their Ho-Lee limits, which have closed forms that
 * can be checked by hand, and their behaviour for a period that has started. The golden test
 * compares them with numerical variances and with QuantLib at the default mean reversion.
 */
class HullWhiteInArrearsTest {

  private static final double SIGMA = 0.0085;

  @Test
  void theHoLeeLimitsAreTheHandFormulas() {
    final double t1 = 0.62;
    final double tau = 0.25;
    final double t2 = t1 + tau;
    final double s2 = SIGMA * SIGMA;
    // compounded: sigma^2 (t1^2/2 + t1 tau + tau^2/3); average: sigma^2 (t1^2 + t1 tau + tau^2/3)/2
    final double compounded = s2 * (t1 * t1 / 2 + t1 * tau + tau * tau / 3);
    final double average = s2 * (t1 * t1 + t1 * tau + tau * tau / 3) / 2;
    assertEquals(compounded, HullWhite.compoundedFuturesConvexity(0.0, SIGMA, t1, t2), 1e-18);
    assertEquals(average, HullWhite.averageFuturesConvexity(0.0, SIGMA, t1, t2), 1e-18);
    // Hull's forward-looking value is sigma^2 t1 t2 / 2: smaller by sigma^2 (t1 tau/2 + tau^2/3)
    assertEquals(compounded - s2 * (t1 * tau / 2 + tau * tau / 3),
        HullWhite.futuresConvexity(0.0, SIGMA, t1, t2), 1e-18);
    // a small mean reversion is continuous with the limit: the closed form at a = 1e-4 is within
    // a t of it, relatively
    assertEquals(compounded, HullWhite.compoundedFuturesConvexity(1e-4, SIGMA, t1, t2),
        2e-4 * compounded);
    assertEquals(average, HullWhite.averageFuturesConvexity(1e-4, SIGMA, t1, t2), 2e-4 * average);
    assertEquals(s2 * 8.0 / 3.0, HullWhite.integratedVariance(0.0, SIGMA, 2.0), 1e-18);
    assertEquals(0.0, HullWhite.integratedVariance(0.03, SIGMA, 0.0), 0.0);
    assertEquals(0.0, HullWhite.integratedVariance(0.03, SIGMA, -1.0), 0.0);
  }

  @Test
  void meanReversionLowersTheAdjustmentAndVolatilityScalesItBySquare() {
    final double base = HullWhite.compoundedFuturesConvexity(0.03, SIGMA, 0.62, 0.87);
    assertTrue(base < HullWhite.compoundedFuturesConvexity(0.0, SIGMA, 0.62, 0.87));
    assertTrue(base > HullWhite.compoundedFuturesConvexity(0.3, SIGMA, 0.62, 0.87));
    assertEquals(4.0 * base, HullWhite.compoundedFuturesConvexity(0.03, 2 * SIGMA, 0.62, 0.87),
        1e-18);
    final double average = HullWhite.averageFuturesConvexity(0.03, SIGMA, 0.25, 0.33);
    assertEquals(4.0 * average, HullWhite.averageFuturesConvexity(0.03, 2 * SIGMA, 0.25, 0.33),
        1e-18);
    // the compounded rate carries a Jensen term the average does not
    assertTrue(HullWhite.compoundedFuturesConvexity(0.03, SIGMA, 0.25, 0.33) > average);
  }

  @Test
  void aStartedPeriodIsAdjustedForWhatRemainsOfIt() {
    // twenty days into a 91-day quarter: the rate over the remaining 71 days, spread over the
    // whole quarter
    final double t1 = -20 / 365.0;
    final double t2 = 71 / 365.0;
    final double remaining = HullWhite.compoundedFuturesConvexity(0.03, SIGMA, 0.0, t2);
    assertEquals(remaining * t2 / (t2 - t1),
        HullWhite.compoundedFuturesConvexity(0.03, SIGMA, t1, t2), 1e-18);
    final double average = HullWhite.averageFuturesConvexity(0.03, SIGMA, 0.0, t2);
    assertEquals(average * t2 / (t2 - t1), HullWhite.averageFuturesConvexity(0.03, SIGMA, t1, t2),
        1e-18);
    assertTrue(remaining > 0.0 && remaining < 1e-6);
    assertThrows(IllegalArgumentException.class,
        () -> HullWhite.compoundedFuturesConvexity(0.03, SIGMA, 0.5, 0.5));
    assertThrows(IllegalArgumentException.class,
        () -> HullWhite.compoundedFuturesConvexity(0.03, SIGMA, -0.5, 0.0));
    assertThrows(IllegalArgumentException.class,
        () -> HullWhite.averageFuturesConvexity(0.03, SIGMA, 0.5, 0.4));
    assertThrows(IllegalArgumentException.class,
        () -> HullWhite.averageFuturesConvexity(0.03, SIGMA, -0.5, -0.1));
  }
}
