package io.ratesalgo.router;

/**
 * Anti-gaming jitter for slice sizes and slice intervals (API_SPEC.md Section 15.3).
 *
 * <p>A slice of nominal size {@code b = remaining / slicesLeft} becomes {@code b * (1 + j * (2u -
 * 1))} rounded to the nearest increment and clamped to {@code [increment, remaining]}, with
 * {@code u} uniform on [0, 1) from the injected {@link RandomSource} and {@code j} the configured
 * jitter; intervals are jittered the same way. Independent uniform draws give sequences with no
 * serial correlation and no runs structure for another participant to latch on to, while every
 * value stays within {@code [(1 - j) b, (1 + j) b]} up to rounding (checked by {@code
 * ChildRandomiserTest}). Nothing here allocates.
 */
public final class ChildRandomiser {

  private final RandomSource random;
  private final double sizeJitter;
  private final double intervalJitter;

  /**
   * @param random seeded in tests and back-tests for determinism
   * @param sizeJitter half-width of the relative size jitter, in [0, 0.5]
   * @param intervalJitter half-width of the relative interval jitter, in [0, 0.5]
   */
  public ChildRandomiser(
      final RandomSource random, final double sizeJitter, final double intervalJitter) {
    if (!RouterParams.in(sizeJitter, 0.0, 0.5) || !RouterParams.in(intervalJitter, 0.0, 0.5)) {
      throw new IllegalArgumentException(
          "jitter must be in [0, 0.5]: size " + sizeJitter + ", interval " + intervalJitter);
    }
    this.random = random;
    this.sizeJitter = sizeJitter;
    this.intervalJitter = intervalJitter;
  }

  /**
   * Size of the next slice.
   *
   * @param remaining quantity left to schedule, a multiple of {@code increment}
   * @param slicesLeft slices left including this one; the last slice takes everything
   * @param increment quantity granularity
   */
  public long sliceSize(final long remaining, final int slicesLeft, final long increment) {
    if (remaining <= increment || slicesLeft <= 1) {
      return remaining;
    }
    return jitteredSize(remaining / slicesLeft, increment, remaining);
  }

  /** A jittered size around {@code base}, rounded to {@code increment}, within [inc, max]. */
  public long jitteredSize(final long base, final long increment, final long max) {
    final double factor = 1.0 + sizeJitter * (2.0 * random.nextDouble() - 1.0);
    final long units = Math.round(base * factor / increment);
    return Math.min(max, Math.max(increment, units * increment));
  }

  /** A jittered interval around {@code baseNanos}, at least one nanosecond. */
  public long interval(final long baseNanos) {
    final double factor = 1.0 + intervalJitter * (2.0 * random.nextDouble() - 1.0);
    return Math.max(1L, Math.round(baseNanos * factor));
  }

  /** Shortest and longest gap, as multiples of the mean, of {@link #gammaGap}. */
  public static final double GAP_MIN = 0.1;

  /** See {@link #GAP_MIN}. */
  public static final double GAP_MAX = 3.0;

  /**
   * A slice gap of the ladder model: {@code meanNanos * G / shape} with {@code G} gamma
   * distributed with that shape (mean {@code meanNanos}, coefficient of variation {@code
   * 1 / sqrt(shape)}, 0.71 at the default shape 2), clamped to {@code [0.1, 3]} times the mean.
   * A uniform jitter of plus or minus a quarter around a fixed period leaves the period visible
   * in the gaps (every gap within 25% of it); gamma gaps have no period to find, a long right
   * tail, and independent draws, while the router re-centres the mean on the time left in the
   * schedule at each slice, so the parent still finishes near its horizon. Seeded, so replayable.
   */
  public long gammaGap(final long meanNanos, final double shape) {
    final double g = gamma(shape) / shape;
    final double clamped = Math.min(GAP_MAX, Math.max(GAP_MIN, g));
    return Math.max(1L, Math.round(meanNanos * clamped));
  }

  /**
   * A gamma variate of unit scale by Marsaglia and Tsang (ACM TOMS 26(3), 2000), with the
   * {@code U^(1/shape)} boost below shape 1. Normals by the polar method from the same source
   * (StrictMath.cos allocates on JDK 25, and Math.cos may differ by platform).
   */
  double gamma(final double shape) {
    if (shape < 1.0) {
      final double u = Math.max(Double.MIN_NORMAL, random.nextDouble());
      return gamma(shape + 1.0) * StrictMath.pow(u, 1.0 / shape);
    }
    final double d = shape - 1.0 / 3.0;
    final double c = 1.0 / StrictMath.sqrt(9.0 * d);
    while (true) {
      final double x = normal();
      final double v0 = 1.0 + c * x;
      if (v0 <= 0.0) {
        continue;
      }
      final double v = v0 * v0 * v0;
      final double u = Math.max(Double.MIN_NORMAL, random.nextDouble());
      if (StrictMath.log(u) < 0.5 * x * x + d - d * v + d * StrictMath.log(v)) {
        return d * v;
      }
    }
  }

  private double normal() {
    double u;
    double s;
    do {
      u = 2.0 * random.nextDouble() - 1.0;
      final double v = 2.0 * random.nextDouble() - 1.0;
      s = u * u + v * v;
    } while (s >= 1.0 || s == 0.0);
    return u * StrictMath.sqrt(-2.0 * StrictMath.log(s) / s);
  }
}
