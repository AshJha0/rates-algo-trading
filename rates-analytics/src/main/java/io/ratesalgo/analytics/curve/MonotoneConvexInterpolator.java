package io.ratesalgo.analytics.curve;

import java.util.Arrays;

/**
 * Hagan-West monotone convex interpolation of log discount factors.
 *
 * <p>With discrete forwards fd_k = -(y_k - y_{k-1}) / h_k on segment k and node forwards f_k
 * (interior: the h-weighted average of the neighbouring discrete forwards; ends: extrapolated by
 * half a step), the instantaneous forward on segment k is f(x) = fd_k + g(u), u in [0, 1], where
 * g is the piecewise quadratic of Hagan and West (2006) Section 4 with g(0) = f_{k-1} - fd_k = g0
 * and g(1) = f_k - fd_k = g1, chosen by the region of (g0, g1) so that the forward stays between
 * its neighbours. Then y(x) = y_{k-1} - h_k (fd_k u + G(u)) with G the integral of g from 0 to u,
 * so the curve reprices every node exactly.
 *
 * <p>The gradient with respect to node values uses the closed-form partial derivatives of G with
 * respect to g0 and g1 (including through the region break point eta), chained with the linear
 * dependence of g0, g1 and fd_k on the nodes. {@code MonotoneConvexInterpolatorTest} checks it
 * against central differences to 1e-7 relative.
 */
final class MonotoneConvexInterpolator extends Interpolator {

  private final double[] t;
  private final double[] y;
  private final double[] fd;
  private final double[] f;
  private int count;
  // G, dG/dg0, dG/dg1 from the last call of integral()
  private double gValue;
  private double gD0;
  private double gD1;

  MonotoneConvexInterpolator(final int capacity) {
    this.t = new double[capacity];
    this.y = new double[capacity];
    this.fd = new double[capacity];
    this.f = new double[capacity];
  }

  @Override
  void update(final double[] times, final double[] values, final int n) {
    System.arraycopy(times, 0, t, 0, n);
    System.arraycopy(values, 0, y, 0, n);
    this.count = n;
    final int last = n - 1;
    for (int k = 1; k <= last; k++) {
      fd[k] = -(y[k] - y[k - 1]) / (t[k] - t[k - 1]);
    }
    if (last == 1) {
      f[0] = fd[1];
      f[1] = fd[1];
      return;
    }
    for (int k = 1; k < last; k++) {
      final double hk = t[k] - t[k - 1];
      final double hk1 = t[k + 1] - t[k];
      f[k] = (hk * fd[k + 1] + hk1 * fd[k]) / (hk + hk1);
    }
    f[0] = fd[1] - 0.5 * (f[1] - fd[1]);
    f[last] = fd[last] - 0.5 * (f[last - 1] - fd[last]);
  }

  @Override
  double value(final double x) {
    final int k = segment(t, count, x);
    final double h = t[k] - t[k - 1];
    final double u = (x - t[k - 1]) / h;
    integral(u, f[k - 1] - fd[k], f[k] - fd[k]);
    return y[k - 1] - h * (fd[k] * u + gValue);
  }

  @Override
  void gradient(final double x, final double[] out) {
    Arrays.fill(out, 0, count, 0.0);
    final int k = segment(t, count, x);
    final double h = t[k] - t[k - 1];
    final double u = (x - t[k - 1]) / h;
    integral(u, f[k - 1] - fd[k], f[k] - fd[k]);
    // y = y_{k-1} - h * (fd_k u + G);  g0 = f_{k-1} - fd_k;  g1 = f_k - fd_k
    out[k - 1] += 1.0;
    final double cFd = -h * (u - gD0 - gD1);
    addFd(k, cFd, out);
    addF(k - 1, -h * gD0, out);
    addF(k, -h * gD1, out);
  }

  /** out += c * d fd_k / dy. */
  private void addFd(final int k, final double c, final double[] out) {
    final double inv = c / (t[k] - t[k - 1]);
    out[k] -= inv;
    out[k - 1] += inv;
  }

  /** out += c * d f_k / dy. */
  private void addF(final int k, final double c, final double[] out) {
    final int last = count - 1;
    if (last == 1) {
      addFd(1, c, out);
      return;
    }
    if (k == 0) {
      // f0 = 1.5 fd1 - 0.5 f1
      addFd(1, 1.5 * c, out);
      addInterior(1, -0.5 * c, out);
    } else if (k == last) {
      addFd(last, 1.5 * c, out);
      addInterior(last - 1, -0.5 * c, out);
    } else {
      addInterior(k, c, out);
    }
  }

  private void addInterior(final int k, final double c, final double[] out) {
    final double hk = t[k] - t[k - 1];
    final double hk1 = t[k + 1] - t[k];
    addFd(k + 1, c * hk / (hk + hk1), out);
    addFd(k, c * hk1 / (hk + hk1), out);
  }

  /**
   * Sets gValue = G(u) = integral of g over [0, u] and its partials in g0 and g1 for the
   * Hagan-West regions (i) to (iv).
   */
  private void integral(final double u, final double g0, final double g1) {
    if (g0 == 0.0 && g1 == 0.0) {
      gValue = 0.0;
      gD0 = 0.0;
      gD1 = 0.0;
      return;
    }
    final boolean region1 =
        (g0 < 0 && -0.5 * g0 <= g1 && g1 <= -2.0 * g0)
            || (g0 > 0 && -0.5 * g0 >= g1 && g1 >= -2.0 * g0);
    final boolean region2 = (g0 < 0 && g1 > -2.0 * g0) || (g0 > 0 && g1 < -2.0 * g0);
    final boolean region3 = (g0 > 0 && 0 > g1 && g1 > -0.5 * g0) || (g0 < 0 && 0 < g1
        && g1 < -0.5 * g0);
    if (region1) {
      final double u2 = u * u;
      final double u3 = u2 * u;
      final double a = u - 2.0 * u2 + u3;
      final double b = -u2 + u3;
      gValue = g0 * a + g1 * b;
      gD0 = a;
      gD1 = b;
    } else if (region2) {
      final double dd = g1 - g0;
      final double eta = (g1 + 2.0 * g0) / dd;
      final double e0 = 3.0 * g1 / (dd * dd);
      final double e1 = -3.0 * g0 / (dd * dd);
      if (u <= eta) {
        gValue = g0 * u;
        gD0 = u;
        gD1 = 0.0;
      } else {
        final double w = 1.0 - eta;
        final double v = u - eta;
        final double k = v * v * v / (3.0 * w * w);
        final double kEta = (-3.0 * v * v * w + 2.0 * v * v * v) / (3.0 * w * w * w);
        gValue = g0 * u + dd * k;
        gD0 = u - k + dd * kEta * e0;
        gD1 = k + dd * kEta * e1;
      }
    } else if (region3) {
      final double dd = g1 - g0;
      final double eta = 3.0 * g1 / dd;
      final double e0 = 3.0 * g1 / (dd * dd);
      final double e1 = -3.0 * g0 / (dd * dd);
      if (u < eta) {
        final double r = eta - u;
        final double hh = eta / 3.0 - r * r * r / (3.0 * eta * eta);
        final double hEta = 1.0 / 3.0 - (3.0 * r * r * eta - 2.0 * r * r * r) / (3.0 * eta * eta
            * eta);
        gValue = g1 * u + (g0 - g1) * hh;
        gD0 = hh + (g0 - g1) * hEta * e0;
        gD1 = u - hh + (g0 - g1) * hEta * e1;
      } else {
        gValue = g1 * u + (g0 - g1) * eta / 3.0;
        gD0 = eta / 3.0 + (g0 - g1) * e0 / 3.0;
        gD1 = u - eta / 3.0 + (g0 - g1) * e1 / 3.0;
      }
    } else {
      // region (iv): g0 and g1 of the same sign (or one of them zero)
      final double s = g0 + g1;
      final double eta = g1 / s;
      final double a = -g0 * g1 / s;
      final double e0 = -g1 / (s * s);
      final double e1 = g0 / (s * s);
      final double a0 = -g1 * g1 / (s * s);
      final double a1 = -g0 * g0 / (s * s);
      if (u < eta) {
        final double r = eta - u;
        final double hh = eta / 3.0 - r * r * r / (3.0 * eta * eta);
        final double hEta = 1.0 / 3.0 - (3.0 * r * r * eta - 2.0 * r * r * r) / (3.0 * eta * eta
            * eta);
        gValue = a * u + (g0 - a) * hh;
        gD0 = a0 * u + (1.0 - a0) * hh + (g0 - a) * hEta * e0;
        gD1 = a1 * u - a1 * hh + (g0 - a) * hEta * e1;
      } else {
        final double w = 1.0 - eta;
        final double v = u - eta;
        final double k;
        final double kEta;
        if (w <= 0.0) {
          k = 0.0;
          kEta = 0.0;
        } else {
          k = v * v * v / (3.0 * w * w);
          kEta = (-3.0 * v * v * w + 2.0 * v * v * v) / (3.0 * w * w * w);
        }
        gValue = a * u + (g0 - a) * eta / 3.0 + (g1 - a) * k;
        gD0 = a0 * u + (1.0 - a0) * eta / 3.0 + (g0 - a) * e0 / 3.0 - a0 * k
            + (g1 - a) * kEta * e0;
        gD1 = a1 * u - a1 * eta / 3.0 + (g0 - a) * e1 / 3.0 + (1.0 - a1) * k
            + (g1 - a) * kEta * e1;
      }
    }
  }
}
