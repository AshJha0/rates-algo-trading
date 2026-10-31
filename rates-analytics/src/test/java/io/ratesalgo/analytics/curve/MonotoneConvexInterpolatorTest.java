package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** The Hagan-West integral G(u; g0, g1) and its partial derivatives in g0 and g1. */
class MonotoneConvexInterpolatorTest {

  @Test
  void partialDerivativesOfTheIntegralMatchCentralDifferences() {
    final MonotoneConvexInterpolator m = new MonotoneConvexInterpolator(4);
    final Random rnd = new Random(7);
    final double h = 1e-10;
    for (int k = 0; k < 20_000; k++) {
      final double g0 = rnd.nextGaussian() * 0.01;
      final double g1 = rnd.nextGaussian() * 0.01;
      final double u = rnd.nextDouble();
      m.integral(u, g0, g1);
      final double d0 = m.gD0;
      final double d1 = m.gD1;
      m.integral(u, g0 + h, g1);
      final double a = m.gValue;
      m.integral(u, g0 - h, g1);
      final double b = m.gValue;
      m.integral(u, g0, g1 + h);
      final double c = m.gValue;
      m.integral(u, g0, g1 - h);
      final double d = m.gValue;
      assertEquals((a - b) / (2 * h), d0, 2e-5, "dG/dg0 at g0=" + g0 + " g1=" + g1 + " u=" + u);
      assertEquals((c - d) / (2 * h), d1, 2e-5, "dG/dg1 at g0=" + g0 + " g1=" + g1 + " u=" + u);
    }
  }
}
