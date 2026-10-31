package io.ratesalgo.analytics.curve;

import java.util.Arrays;

/** Linear interpolation on log discount factors (piecewise flat instantaneous forwards). */
final class LinearInterpolator extends Interpolator {

  private final double[] t;
  private final double[] y;
  private int count;

  LinearInterpolator(final int capacity) {
    this.t = new double[capacity];
    this.y = new double[capacity];
  }

  @Override
  void update(final double[] times, final double[] values, final int n) {
    System.arraycopy(times, 0, t, 0, n);
    System.arraycopy(values, 0, y, 0, n);
    this.count = n;
  }

  @Override
  double value(final double x) {
    final int k = segment(t, count, x);
    final double u = (x - t[k - 1]) / (t[k] - t[k - 1]);
    return y[k - 1] + u * (y[k] - y[k - 1]);
  }

  @Override
  void gradient(final double x, final double[] out) {
    Arrays.fill(out, 0, count, 0.0);
    final int k = segment(t, count, x);
    final double u = (x - t[k - 1]) / (t[k] - t[k - 1]);
    out[k - 1] = 1.0 - u;
    out[k] = u;
  }
}
