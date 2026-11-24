package io.ratesalgo.quoting;

/**
 * The standard normal distribution function and density, to double precision in both tails. The
 * distribution function is Hart's rational approximation as given by G. West, "Better
 * approximations to cumulative normal functions", Wilmott Magazine (2005): about 1e-14 absolute
 * and 1e-7 relative or better in the far tails, where the continued fraction takes over beyond
 * 7.07; the censored updates of {@link CoverModel} divide by tail probabilities and need the
 * relative accuracy. Allocation-free.
 */
final class NormalDist {

  private static final double INV_SQRT_2PI = 0.3989422804014327;

  private NormalDist() {}

  /** Density at {@code z}. */
  static double pdf(final double z) {
    return INV_SQRT_2PI * Math.exp(-0.5 * z * z);
  }

  /** P(Z &lt;= z). */
  static double cdf(final double z) {
    final double x = Math.abs(z);
    double c;
    if (x > 37.0) {
      c = 0.0;
    } else {
      final double e = Math.exp(-0.5 * x * x);
      if (x < 7.07106781186547) {
        double b = 3.52624965998911e-02 * x + 0.700383064443688;
        b = b * x + 6.37396220353165;
        b = b * x + 33.912866078383;
        b = b * x + 112.079291497871;
        b = b * x + 221.213596169931;
        b = b * x + 220.206867912376;
        c = e * b;
        b = 8.83883476483184e-02 * x + 1.75566716318264;
        b = b * x + 16.064177579207;
        b = b * x + 86.7807322029461;
        b = b * x + 296.564248779674;
        b = b * x + 637.333633378831;
        b = b * x + 793.826512519948;
        b = b * x + 440.413735824752;
        c = c / b;
      } else {
        double b = x + 0.65;
        b = x + 4.0 / b;
        b = x + 3.0 / b;
        b = x + 2.0 / b;
        b = x + 1.0 / b;
        c = e / b / 2.506628274631;
      }
    }
    return z > 0.0 ? 1.0 - c : c;
  }

  /** P(Z &gt; z), computed from the lower tail so it keeps its precision for large z. */
  static double upper(final double z) {
    return cdf(-z);
  }

  /** E[Z | Z &gt; z] = pdf(z) / P(Z &gt; z), the inverse Mills ratio; z + 1/z beyond 37. */
  static double hazard(final double z) {
    final double q = upper(z);
    return q > 0.0 ? pdf(z) / q : z + 1.0 / z;
  }
}
