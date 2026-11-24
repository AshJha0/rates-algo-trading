package io.ratesalgo.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.random.SplitMix64;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import org.junit.jupiter.api.Test;

/** The inverse normal, the distribution function and the one-draw-per-quantity rule (design section 2). */
class DrawsTest {

  /** Quantiles to 16 digits from the tables of the normal distribution. */
  @Test
  void inverseNormalMatchesKnownQuantiles() {
    assertEquals(0.0, Gaussian.inverse(0.5), 0.0);
    assertEquals(1.959963984540054, Gaussian.inverse(0.975), 1e-15);
    assertEquals(-1.2815515655446004, Gaussian.inverse(0.1), 1e-15);
    assertEquals(-3.090232306167813, Gaussian.inverse(0.001), 1e-14);
    assertEquals(-6.361340902404056, Gaussian.inverse(1e-10), 1e-13);
    assertEquals(8.125890664701906, Gaussian.inverse(1.0 - 0x1.0p-52), 1e-9);
    assertThrows(IllegalArgumentException.class, () -> Gaussian.inverse(0.0));
    assertThrows(IllegalArgumentException.class, () -> Gaussian.inverse(1.0));
    assertThrows(IllegalArgumentException.class, () -> Gaussian.inverse(Double.NaN));
  }

  @Test
  void distributionFunctionMatchesKnownValues() {
    assertEquals(0.5, Gaussian.cdf(0.0), 1e-16);
    assertEquals(0.975, Gaussian.cdf(1.959963984540054), 1e-15);
    assertEquals(0.15865525393145707, Gaussian.cdf(-1.0), 1e-15);
    // the approximation's error is absolute, about 1e-15 or better; in the far tail it is not relative
    assertEquals(1.279812543885835e-12, Gaussian.cdf(-7.0), 1e-19);
    assertEquals(6.220960574271785e-16, Gaussian.cdf(-8.0), 1e-22);
    assertEquals(0.0, Gaussian.cdf(-40.0), 0.0);
    assertEquals(1.0, Gaussian.cdf(40.0), 0.0);
    assertEquals(0.3989422804014327, Gaussian.pdf(0.0), 1e-16);
  }

  @Property
  void inverseUndoesTheDistributionFunction(@ForAll @DoubleRange(min = -6.0, max = 6.0) final double x) {
    // cdf has relative error near 1e-15; near |x| = 6 its slope is 6e-9, so the round trip keeps 6 digits
    assertEquals(x, Gaussian.inverse(Gaussian.cdf(x)), 1e-6);
  }

  @Property
  void inverseIsIncreasingAndOdd(
      @ForAll @DoubleRange(min = -4.0, max = -0.31) final double exponent) {
    final double p = Math.pow(10.0, exponent);
    assertTrue(Gaussian.inverse(p) < Gaussian.inverse(p * 1.001 + 1e-13));
    assertEquals(-Gaussian.inverse(p), Gaussian.inverse(1.0 - p), 1e-9 * Math.abs(Gaussian.inverse(p)) + 1e-12);
  }

  /** Each quantity consumes exactly one 64-bit draw, whatever its value. */
  @Property
  void everyQuantityUsesOneDraw(@ForAll final long seed, @ForAll @DoubleRange(min = 0.0, max = 60.0) final double mean) {
    final SplitMix64 a = new SplitMix64(seed);
    final SplitMix64 b = new SplitMix64(seed);
    final double u = Draws.uniform(a);
    assertTrue(u > 0.0 && u < 1.0);
    Draws.normal(a);
    Draws.poisson(mean, a);
    final int k = Draws.between(3, 5, a);
    assertTrue(k >= 3 && k <= 5);
    for (int i = 0; i < 4; i++) {
      b.nextLong();
    }
    assertEquals(b.nextLong(), a.nextLong());
  }

  @Property
  void poissonQuantileIsTheSmallestCountThatReachesU(
      @ForAll @DoubleRange(min = 0.0, max = 30.0) final double mean,
      @ForAll @DoubleRange(min = 0.0, max = 1.0, minIncluded = false, maxIncluded = false) final double u) {
    final int k = Draws.poissonFromUniform(mean, u);
    double cumulative = 0.0;
    double p = Math.exp(-mean);
    for (int i = 0; i <= k; i++) {
      if (i > 0) {
        p *= mean / i;
      }
      if (i < k) {
        cumulative += p;
      }
    }
    assertTrue(cumulative < u || k == 0, "the count below k must not already reach u");
  }

  /**
   * 400,000 draws at mean 7.3: the sample mean has standard error sqrt(7.3 / 400000) = 0.0043 and
   * the sample variance about 0.017; the bounds are 4.5 standard errors.
   */
  @Test
  void poissonHasItsMeanAndVariance() {
    final SplitMix64 s = new SplitMix64(11L);
    final int n = 400_000;
    double sum = 0;
    double squares = 0;
    for (int i = 0; i < n; i++) {
      final int k = Draws.poisson(7.3, s);
      sum += k;
      squares += (double) k * k;
    }
    final double mean = sum / n;
    assertEquals(7.3, mean, 0.02);
    assertEquals(7.3, squares / n - mean * mean, 0.08);
    assertEquals(0, Draws.poissonFromUniform(0.0, 0.999));
    assertThrows(IllegalArgumentException.class, () -> Draws.poissonFromUniform(-1.0, 0.5));
    assertThrows(IllegalArgumentException.class, () -> Draws.poissonFromUniform(Draws.MAX_POISSON_MEAN + 1, 0.5));
  }

  /** 400,000 normals: mean within 4.5 x 0.0016, variance within 4.5 x 0.0022, fourth moment 3 within 4.5 x 0.0155. */
  @Test
  void normalDrawsHaveNormalMoments() {
    final SplitMix64 s = new SplitMix64(5L);
    final int n = 400_000;
    double m1 = 0;
    double m2 = 0;
    double m4 = 0;
    for (int i = 0; i < n; i++) {
      final double z = Draws.normal(s);
      m1 += z;
      m2 += z * z;
      m4 += z * z * z * z;
    }
    assertEquals(0.0, m1 / n, 0.0072);
    assertEquals(1.0, m2 / n, 0.01);
    assertEquals(3.0, m4 / n, 0.07);
  }
}
