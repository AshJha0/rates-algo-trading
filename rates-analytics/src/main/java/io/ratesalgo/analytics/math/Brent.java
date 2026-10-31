package io.ratesalgo.analytics.math;

/**
 * Brent's bracketing root finder (Brent 1973, "Algorithms for Minimization without Derivatives",
 * chapter 4; the zeroin algorithm). Combines bisection, the secant rule and inverse quadratic
 * interpolation, and never leaves the bracket.
 *
 * <p>The function is passed as an object implementing {@link Function} so that a pricer can solve
 * for a yield or a spread without allocating a lambda per call.
 */
public final class Brent {

  /** A scalar function of one variable. */
  @FunctionalInterface
  public interface Function {
    double value(double x);
  }

  private Brent() {}

  /**
   * Finds x in [lo, hi] with |f(x)| small, stopping when the bracket is narrower than {@code
   * xTolerance} or f is exactly zero. If f(lo) and f(hi) have the same sign the bracket is widened
   * geometrically up to 50 times before giving up.
   *
   * @param f the function
   * @param lo lower end of the initial bracket
   * @param hi upper end of the initial bracket
   * @param xTolerance absolute tolerance on x
   * @param maxIterations iteration limit
   * @return the root
   * @throws IllegalStateException if no sign change is found or the iteration limit is reached
   */
  public static double solve(
      final Function f,
      final double lo,
      final double hi,
      final double xTolerance,
      final int maxIterations) {
    double a = lo;
    double b = hi;
    double fa = f.value(a);
    double fb = f.value(b);
    int widen = 0;
    while (fa * fb > 0.0) {
      if (++widen > 50) {
        throw new IllegalStateException(
            "Brent: no sign change in [" + lo + ", " + hi + "] after widening; f(lo)=" + fa
                + " f(hi)=" + fb);
      }
      final double width = b - a;
      if (Math.abs(fa) < Math.abs(fb)) {
        a -= 1.6 * width;
        fa = f.value(a);
      } else {
        b += 1.6 * width;
        fb = f.value(b);
      }
    }
    if (fa == 0.0) {
      return a;
    }
    if (fb == 0.0) {
      return b;
    }
    double c = a;
    double fc = fa;
    double d = b - a;
    double e = d;
    for (int iter = 0; iter < maxIterations; iter++) {
      if (fb * fc > 0.0) {
        c = a;
        fc = fa;
        d = b - a;
        e = d;
      }
      if (Math.abs(fc) < Math.abs(fb)) {
        a = b;
        b = c;
        c = a;
        fa = fb;
        fb = fc;
        fc = fa;
      }
      final double tol = 2.0 * Math.ulp(b) + 0.5 * xTolerance;
      final double m = 0.5 * (c - b);
      if (Math.abs(m) <= tol || fb == 0.0) {
        return b;
      }
      if (Math.abs(e) >= tol && Math.abs(fa) > Math.abs(fb)) {
        final double s = fb / fa;
        double p;
        double q;
        if (Double.compare(a, c) == 0) {
          p = 2.0 * m * s;
          q = 1.0 - s;
        } else {
          final double qq = fa / fc;
          final double r = fb / fc;
          p = s * (2.0 * m * qq * (qq - r) - (b - a) * (r - 1.0));
          q = (qq - 1.0) * (r - 1.0) * (s - 1.0);
        }
        if (p > 0.0) {
          q = -q;
        } else {
          p = -p;
        }
        if (2.0 * p < Math.min(3.0 * m * q - Math.abs(tol * q), Math.abs(e * q))) {
          e = d;
          d = p / q;
        } else {
          d = m;
          e = d;
        }
      } else {
        d = m;
        e = d;
      }
      a = b;
      fa = fb;
      b += Math.abs(d) > tol ? d : (m > 0 ? tol : -tol);
      fb = f.value(b);
    }
    throw new IllegalStateException(
        "Brent: no convergence in " + maxIterations + " iterations; last x=" + b + " f=" + fb);
  }
}
