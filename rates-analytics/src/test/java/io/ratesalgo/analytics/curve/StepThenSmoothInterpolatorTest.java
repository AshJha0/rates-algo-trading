package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Dates;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The step-then-convex interpolation: its two limits, the join, and its gradient. */
class StepThenSmoothInterpolatorTest {

  private static final int TODAY = Dates.of(2026, 11, 2);
  private static final int[] PILLARS = {
    TODAY + 38, TODAY + 87, TODAY + 119, TODAY + 300, TODAY + 730, TODAY + 1830, TODAY + 3650,
    TODAY + 7300, TODAY + 10_950
  };

  private static DiscountCurve curve(final Interpolation scheme, final int steps, final long seed) {
    final Random rnd = new Random(seed);
    final double[] dfs = new double[PILLARS.length];
    for (int i = 0; i < dfs.length; i++) {
      final double t = (PILLARS[i] - TODAY) / 365.0;
      dfs[i] = Math.exp(-(0.035 + 0.004 * rnd.nextGaussian()) * t);
    }
    final DiscountCurve c = new DiscountCurve(TODAY, PILLARS, scheme, 0.0, steps);
    for (int i = 0; i < dfs.length; i++) {
      c.setPillarLogDf(i, Math.log(dfs[i]));
    }
    c.update();
    return c;
  }

  @Test
  void withNoStepPillarsItIsMonotoneConvexAndWithAllOfThemLinear() {
    final DiscountCurve mixed0 = curve(Interpolation.STEP_THEN_MONOTONE_CONVEX, 0, 3);
    final DiscountCurve convex = curve(Interpolation.MONOTONE_CONVEX, 0, 3);
    final DiscountCurve mixedAll =
        curve(Interpolation.STEP_THEN_MONOTONE_CONVEX, PILLARS.length, 3);
    final DiscountCurve linear = curve(Interpolation.LINEAR_LOG_DF, 0, 3);
    for (int d = TODAY + 1; d < TODAY + 12_000; d += 17) {
      // the same arithmetic on the same nodes: equal to the bit
      assertEquals(convex.df(d), mixed0.df(d), 0.0);
      assertEquals(linear.df(d), mixedAll.df(d), 0.0);
    }
    assertEquals(0, mixed0.stepPillars());
    assertEquals(PILLARS.length, mixedAll.copy().stepPillars());
    assertThrows(IllegalArgumentException.class,
        () -> new DiscountCurve(TODAY, PILLARS, Interpolation.LINEAR_LOG_DF, 0.0, -1));
    assertThrows(IllegalArgumentException.class,
        () -> new DiscountCurve(TODAY, PILLARS, Interpolation.LINEAR_LOG_DF, 0.0, 10));
  }

  @Test
  void theFrontIsLinearTheBackIsSmoothAndEveryNodeReprices() {
    final DiscountCurve mixed = curve(Interpolation.STEP_THEN_MONOTONE_CONVEX, 3, 5);
    final DiscountCurve linear = curve(Interpolation.LINEAR_LOG_DF, 0, 5);
    for (int d = TODAY + 1; d <= PILLARS[2]; d++) {
      assertEquals(linear.df(d), mixed.df(d), 0.0, "step section at day " + (d - TODAY));
    }
    for (int i = 0; i < PILLARS.length; i++) {
      assertEquals(linear.df(PILLARS[i]), mixed.df(PILLARS[i]), 1e-15, "node " + i);
    }
    // past the join the forward is continuous across the interior nodes: a day either side of a
    // node it differs by the slope only, where the linear curve steps
    double mixedJump = 0.0;
    double linearJump = 0.0;
    for (int i = 4; i < PILLARS.length - 1; i++) {
      mixedJump = Math.max(mixedJump,
          Math.abs(forward(mixed, PILLARS[i] + 1) - forward(mixed, PILLARS[i] - 2)));
      linearJump = Math.max(linearJump,
          Math.abs(forward(linear, PILLARS[i] + 1) - forward(linear, PILLARS[i] - 2)));
    }
    // random node values 40 bp apart in zero rate: the linear curve steps by a percent or more
    assertTrue(linearJump > 5 * mixedJump, mixedJump + " " + linearJump);
    // the copy interpolates the same way
    assertEquals(mixed.df(TODAY + 500), mixed.copy().df(TODAY + 500), 0.0);
  }

  /** The tension-spline variant with the given number of step pillars. */
  private static DiscountCurve tension(final int steps) {
    final DiscountCurve c =
        new DiscountCurve(TODAY, PILLARS, Interpolation.STEP_THEN_TENSION_SPLINE, 0.5, steps);
    final DiscountCurve values = curve(Interpolation.LINEAR_LOG_DF, 0, 11);
    for (int i = 0; i < PILLARS.length; i++) {
      c.setPillarLogDf(i, values.pillarLogDf(i));
    }
    c.update();
    return c;
  }

  @Test
  void theTensionVariantRepricesItsNodesAndIsLinearAtTheFront() {
    final DiscountCurve spline = tension(3);
    final DiscountCurve linear = curve(Interpolation.LINEAR_LOG_DF, 0, 11);
    for (int d = TODAY + 1; d <= PILLARS[2]; d++) {
      assertEquals(linear.df(d), spline.df(d), 0.0);
    }
    for (int i = 0; i < PILLARS.length; i++) {
      assertEquals(linear.df(PILLARS[i]), spline.df(PILLARS[i]), 1e-14, "node " + i);
    }
    assertThrows(IllegalArgumentException.class,
        () -> new DiscountCurve(TODAY, PILLARS, Interpolation.STEP_THEN_TENSION_SPLINE, 0.0, 3));
  }

  private static double forward(final DiscountCurve c, final int d) {
    return c.simpleForward(d, d + 1, 1.0 / 365.0);
  }

  @Test
  void gradientMatchesCentralDifferencesOnBothSidesOfTheJoin() {
    for (final int steps : new int[] {0, 1, 3, 8, 9, -1, -3, -8}) {
      final DiscountCurve c =
          steps >= 0 ? curve(Interpolation.STEP_THEN_MONOTONE_CONVEX, steps, 11) : tension(-steps);
      final int n = c.pillarCount();
      final double[] ad = new double[n];
      double worst = 0.0;
      for (int d = TODAY + 5; d < TODAY + 11_500; d += 61) {
        c.logDfGradient(d, ad);
        for (int i = 0; i < n; i++) {
          final double y = c.pillarLogDf(i);
          final double h = 1e-6;
          c.setPillarLogDf(i, y + h);
          c.update();
          final double up = Math.log(c.df(d));
          c.setPillarLogDf(i, y - h);
          c.update();
          final double dn = Math.log(c.df(d));
          c.setPillarLogDf(i, y);
          c.update();
          worst = Math.max(worst, Math.abs((up - dn) / (2 * h) - ad[i]));
        }
      }
      // central differences at h = 1e-6 on a function of order one: the same 1e-6 bound as
      // CurveBuilderTest.logDiscountGradientMatchesCentralDifferences
      assertEquals(0.0, worst, 1e-6, "steps " + steps);
    }
  }
}
