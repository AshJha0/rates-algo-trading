package io.ratesalgo.analytics.curve;

/**
 * Interpolation of node values y_0..y_N at times t_0 = 0 < t_1 < ... < t_N, evaluated inside
 * [t_0, t_N]. Implementations hold preallocated state sized for a maximum node count and do not
 * allocate in {@link #update}, {@link #value} or {@link #gradient}.
 */
abstract class Interpolator {

  /** Recomputes internal state for the first {@code count} nodes. */
  abstract void update(double[] t, double[] y, int count);

  /** Interpolated value at x, with t_0 <= x <= t_{count-1}. */
  abstract double value(double x);

  /**
   * Writes dy(x)/dy_j for j = 0..count-1 into {@code out} (algorithmic differentiation of the
   * interpolation scheme with respect to its node values).
   */
  abstract void gradient(double x, double[] out);

  /** Index k of the segment [t_{k-1}, t_k] containing x, 1 <= k <= count-1. */
  static int segment(final double[] t, final int count, final double x) {
    int lo = 1;
    int hi = count - 1;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (x <= t[mid]) {
        hi = mid;
      } else {
        lo = mid + 1;
      }
    }
    return lo;
  }
}
