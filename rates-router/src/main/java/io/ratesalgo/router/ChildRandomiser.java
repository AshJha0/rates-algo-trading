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
}
