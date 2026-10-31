package io.ratesalgo.analytics.bond;

import java.util.Arrays;

/**
 * A natural cubic spline through (maturity, yield) points, the simplest fitted yield curve for
 * relative value: a bond's richness is its yield minus the spline at its maturity, with the bond
 * itself left out of the knots. Flat extrapolation outside the knots. Exact interpolation.
 */
public final class CubicSplineCurve {

  private final double[] x;
  private final double[] y;
  private final double[] m;

  /** Builds the spline; maturities strictly increasing, at least two points. */
  public CubicSplineCurve(final double[] maturities, final double[] yields) {
    final int n = maturities.length;
    if (n < 2 || yields.length != n) {
      throw new IllegalArgumentException("need at least two points of equal length");
    }
    for (int i = 1; i < n; i++) {
      if (!(maturities[i] > maturities[i - 1])) {
        throw new IllegalArgumentException("maturities not increasing at " + i);
      }
    }
    this.x = Arrays.copyOf(maturities, n);
    this.y = Arrays.copyOf(yields, n);
    this.m = new double[n];
    if (n > 2) {
      final double[] c = new double[n];
      final double[] d = new double[n];
      for (int i = 1; i < n - 1; i++) {
        final double h0 = x[i] - x[i - 1];
        final double h1 = x[i + 1] - x[i];
        final double a = h0 / 6.0;
        final double b = (h0 + h1) / 3.0;
        final double cc = h1 / 6.0;
        final double r = (y[i + 1] - y[i]) / h1 - (y[i] - y[i - 1]) / h0;
        final double denom = b - a * c[i - 1];
        c[i] = cc / denom;
        d[i] = (r - a * d[i - 1]) / denom;
      }
      for (int i = n - 2; i >= 1; i--) {
        m[i] = d[i] - c[i] * m[i + 1];
      }
    }
  }

  /** Spline value at maturity t. */
  public double value(final double t) {
    final int n = x.length;
    if (t <= x[0]) {
      return y[0];
    }
    if (t >= x[n - 1]) {
      return y[n - 1];
    }
    int k = Arrays.binarySearch(x, t);
    if (k >= 0) {
      return y[k];
    }
    k = -k - 1;
    final double h = x[k] - x[k - 1];
    final double a = (x[k] - t) / h;
    final double b = (t - x[k - 1]) / h;
    return a * y[k - 1] + b * y[k]
        + ((a * a * a - a) * m[k - 1] + (b * b * b - b) * m[k]) * h * h / 6.0;
  }
}
