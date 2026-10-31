package io.ratesalgo.analytics.curve;

import java.util.Arrays;

/**
 * Exponential tension spline on log discount factors with natural end conditions (second
 * derivative zero at the first and last node).
 *
 * <p>On segment k of length h, with z the second derivatives at the nodes and tension p:
 *
 * <pre>
 *   s(x) = y_{k-1} A + y_k B + z_{k-1} C + z_k D
 *   A = (t_k - x)/h,  B = (x - t_{k-1})/h
 *   C = [sinh(p (t_k - x)) / sinh(p h) - A] / p^2,  D = [sinh(p (x - t_{k-1})) / sinh(p h) - B] / p^2
 * </pre>
 *
 * Continuity of the first derivative gives the tridiagonal system alpha_k z_{k-1} + (beta_k +
 * beta_{k+1}) z_k + alpha_{k+1} z_{k+1} = Delta_{k+1} - Delta_k, with alpha = 1/(p^2 h) - 1/(p
 * sinh(p h)), beta = 1/(p tanh(p h)) - 1/(p^2 h) and Delta_k the slope of segment k. Small p h
 * uses the Taylor series to fifth order to avoid cancellation; large p h uses exponentials to
 * avoid overflow.
 *
 * <p>The spline is linear in the node values, so its gradient is exact: z = M y for a matrix M
 * computed on first use after each update by solving the system for unit vectors.
 */
final class TensionSplineInterpolator extends Interpolator {

  private final double p;
  private final double[] t;
  private final double[] y;
  private final double[] z;
  private final double[] lower;
  private final double[] diag;
  private final double[] upper;
  private final double[] rhs;
  private final double[] work;
  private final double[] column;
  private final double[] m;
  private final int capacity;
  private int count;
  private boolean mStale = true;

  TensionSplineInterpolator(final int capacity, final double tension) {
    if (!(tension > 0.0)) {
      throw new IllegalArgumentException("tension must be positive, got " + tension);
    }
    this.p = tension;
    this.capacity = capacity;
    this.t = new double[capacity];
    this.y = new double[capacity];
    this.z = new double[capacity];
    this.lower = new double[capacity];
    this.diag = new double[capacity];
    this.upper = new double[capacity];
    this.rhs = new double[capacity];
    this.work = new double[capacity];
    this.column = new double[capacity];
    this.m = new double[capacity * capacity];
  }

  private double alpha(final double h) {
    final double x = p * h;
    if (x < 1e-2) {
      final double x2 = x * x;
      return h * (1.0 / 6.0 - 7.0 * x2 / 360.0 + 31.0 * x2 * x2 / 15120.0);
    }
    final double e = Math.exp(-x);
    final double invSinh = 2.0 * e / (1.0 - e * e);
    return (1.0 / x - invSinh) / p;
  }

  private double beta(final double h) {
    final double x = p * h;
    if (x < 1e-2) {
      final double x2 = x * x;
      return h * (1.0 / 3.0 - x2 / 45.0 + 2.0 * x2 * x2 / 945.0);
    }
    final double e2 = Math.exp(-2.0 * x);
    final double coth = (1.0 + e2) / (1.0 - e2);
    return (coth - 1.0 / x) / p;
  }

  /** [sinh(p a) / sinh(p h) - a / h] / p^2 for 0 <= a <= h. */
  private double phi(final double a, final double h) {
    final double x = p * h;
    if (x < 1e-2) {
      final double r = a / h;
      final double a2 = a * a;
      final double h2 = h * h;
      return r * ((a2 - h2) / 6.0 + p * p * ((a2 * a2 - h2 * h2) / 120.0 - h2 * (a2 - h2) / 36.0));
    }
    // sinh(pa)/sinh(ph) = (e^{p(a-h)} - e^{-p(a+h)}) / (1 - e^{-2ph})
    final double ratio = (Math.exp(p * (a - h)) - Math.exp(-p * (a + h))) / (1.0 - Math.exp(
        -2.0 * x));
    return (ratio - a / h) / (p * p);
  }

  @Override
  void update(final double[] times, final double[] values, final int n) {
    System.arraycopy(times, 0, t, 0, n);
    System.arraycopy(values, 0, y, 0, n);
    this.count = n;
    this.mStale = true;
    buildSystem();
    for (int k = 1; k < n - 1; k++) {
      final double d1 = (y[k] - y[k - 1]) / (t[k] - t[k - 1]);
      final double d2 = (y[k + 1] - y[k]) / (t[k + 1] - t[k]);
      rhs[k] = d2 - d1;
    }
    solveInto(z);
  }

  private void buildSystem() {
    for (int k = 1; k < count - 1; k++) {
      final double hk = t[k] - t[k - 1];
      final double hk1 = t[k + 1] - t[k];
      lower[k] = alpha(hk);
      diag[k] = beta(hk) + beta(hk1);
      upper[k] = alpha(hk1);
    }
  }

  /** Thomas algorithm on rows 1..count-2 of rhs; writes out[0..count-1] with natural ends. */
  private void solveInto(final double[] out) {
    final int n = count;
    out[0] = 0.0;
    out[n - 1] = 0.0;
    if (n <= 2) {
      return;
    }
    // forward sweep, work holds the modified upper diagonal
    double denom = diag[1];
    work[1] = upper[1] / denom;
    out[1] = rhs[1] / denom;
    for (int k = 2; k < n - 1; k++) {
      denom = diag[k] - lower[k] * work[k - 1];
      work[k] = upper[k] / denom;
      out[k] = (rhs[k] - lower[k] * out[k - 1]) / denom;
    }
    for (int k = n - 3; k >= 1; k--) {
      out[k] -= work[k] * out[k + 1];
    }
  }

  @Override
  double value(final double x) {
    final int k = segment(t, count, x);
    final double h = t[k] - t[k - 1];
    final double a = (t[k] - x) / h;
    final double b = (x - t[k - 1]) / h;
    return y[k - 1] * a + y[k] * b + z[k - 1] * phi(t[k] - x, h) + z[k] * phi(x - t[k - 1], h);
  }

  @Override
  void gradient(final double x, final double[] out) {
    if (mStale) {
      buildMatrix();
    }
    final int n = count;
    Arrays.fill(out, 0, n, 0.0);
    final int k = segment(t, n, x);
    final double h = t[k] - t[k - 1];
    out[k - 1] += (t[k] - x) / h;
    out[k] += (x - t[k - 1]) / h;
    final double c = phi(t[k] - x, h);
    final double d = phi(x - t[k - 1], h);
    for (int j = 0; j < n; j++) {
      out[j] += c * m[(k - 1) * capacity + j] + d * m[k * capacity + j];
    }
  }

  private void buildMatrix() {
    final int n = count;
    for (int j = 0; j < n; j++) {
      for (int k = 1; k < n - 1; k++) {
        final double hk = t[k] - t[k - 1];
        final double hk1 = t[k + 1] - t[k];
        double r = 0.0;
        // d(Delta_{k+1} - Delta_k)/dy_j
        if (j == k + 1) {
          r += 1.0 / hk1;
        }
        if (j == k) {
          r -= 1.0 / hk1 + 1.0 / hk;
        }
        if (j == k - 1) {
          r += 1.0 / hk;
        }
        rhs[k] = r;
      }
      solveInto(column);
      for (int k = 0; k < n; k++) {
        m[k * capacity + j] = column[k];
      }
    }
    mStale = false;
  }
}
