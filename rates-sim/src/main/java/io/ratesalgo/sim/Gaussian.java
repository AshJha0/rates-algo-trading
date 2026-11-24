package io.ratesalgo.sim;

/**
 * The normal distribution function and its inverse.
 *
 * <p>The inverse is Wichura's algorithm AS 241, routine PPND16 (Applied Statistics 37, 1988,
 * 477-484), accurate to about 1 part in 1e16. The design (section 2) fixes it because it maps one
 * uniform to one normal with no rejection, so the number of draws never depends on the values.
 * The distribution function is Hart's rational approximation as given by G. West, "Better
 * approximations to cumulative normal functions", Wilmott Magazine, May 2005 (double precision).
 */
public final class Gaussian {

  private static final double[] A =
      published(
          "3.3871328727963666080e0", "1.3314166789178437745e+2", "1.9715909503065514427e+3",
          "1.3731693765509461125e+4", "4.5921953931549871457e+4", "6.7265770927008700853e+4",
          "3.3430575583588128105e+4", "2.5090809287301226727e+3");
  private static final double[] B =
      published(
          "1.0", "4.2313330701600911252e+1", "6.8718700749205790830e+2",
          "5.3941960214247511077e+3", "2.1213794301586595867e+4", "3.9307895800092710610e+4",
          "2.8729085735721942674e+4", "5.2264952788528545610e+3");
  private static final double[] C =
      published(
          "1.42343711074968357734e0", "4.63033784615654529590e0", "5.76949722146069140550e0",
          "3.64784832476320460504e0", "1.27045825245236838258e0", "2.41780725177450611770e-1",
          "2.27238449892691845833e-2", "7.74545014278341407640e-4");
  private static final double[] D =
      published(
          "1.0", "2.05319162663775882187e0", "1.67638483018380384940e0",
          "6.89767334985100004550e-1", "1.48103976427480074590e-1", "1.51986665636164571966e-2",
          "5.47593808499534494600e-4", "1.05075007164441684324e-9");
  private static final double[] E =
      published(
          "6.65790464350110377720e0", "5.46378491116411436990e0", "1.78482653991729133580e0",
          "2.96560571828504891230e-1", "2.65321895265761230930e-2", "1.24266094738807843860e-3",
          "2.71155556874348757815e-5", "2.01033439929228813265e-7");
  private static final double[] F =
      published(
          "1.0", "5.99832206555887937690e-1", "1.36929880922735805310e-1",
          "1.48753612908506148525e-2", "7.86869131145613259100e-4", "1.84631831751005468180e-5",
          "1.42151175831644588870e-7", "2.04426310338993978564e-15");

  private Gaussian() {}

  /** The coefficients as printed in the paper (20 digits), parsed to the nearest double. */
  private static double[] published(final String... digits) {
    final double[] out = new double[digits.length];
    for (int i = 0; i < digits.length; i++) {
      out[i] = Double.parseDouble(digits[i]);
    }
    return out;
  }

  private static double poly(final double[] c, final double r) {
    double v = c[7];
    for (int i = 6; i >= 0; i--) {
      v = v * r + c[i];
    }
    return v;
  }

  /** The standard normal quantile of {@code p}, for p strictly between 0 and 1. */
  public static double inverse(final double p) {
    if (!(p > 0.0 && p < 1.0)) {
      throw new IllegalArgumentException("probability must be inside (0, 1): " + p);
    }
    final double q = p - 0.5;
    if (Math.abs(q) <= 0.425) {
      final double r = 0.180625 - q * q;
      return q * poly(A, r) / poly(B, r);
    }
    double r = Math.sqrt(-Math.log(q < 0 ? p : 1.0 - p));
    final double v;
    if (r <= 5.0) {
      r -= 1.6;
      v = poly(C, r) / poly(D, r);
    } else {
      r -= 5.0;
      v = poly(E, r) / poly(F, r);
    }
    return q < 0 ? -v : v;
  }

  /** The standard normal distribution function. */
  public static double cdf(final double x) {
    final double a = Math.abs(x);
    final double tail;
    if (a > 37.0) {
      tail = 0.0;
    } else {
      final double e = Math.exp(-a * a / 2.0);
      if (a < 7.07106781186547) {
        double n = 3.52624965998911e-02 * a + 0.700383064443688;
        n = n * a + 6.37396220353165;
        n = n * a + 33.912866078383;
        n = n * a + 112.079291497871;
        n = n * a + 221.213596169931;
        n = n * a + 220.206867912376;
        double d = 8.83883476483184e-02 * a + 1.75566716318264;
        d = d * a + 16.064177579207;
        d = d * a + 86.7807322029461;
        d = d * a + 296.564248779674;
        d = d * a + 637.333633378831;
        d = d * a + 793.826512519948;
        d = d * a + 440.413735824752;
        tail = e * n / d;
      } else {
        double b = a + 0.65;
        b = a + 4.0 / b;
        b = a + 3.0 / b;
        b = a + 2.0 / b;
        b = a + 1.0 / b;
        tail = e / b / 2.506628274631;
      }
    }
    return x > 0 ? 1.0 - tail : tail;
  }

  /** The standard normal density. */
  public static double pdf(final double x) {
    return Math.exp(-0.5 * x * x) / 2.5066282746310002;
  }
}
