package io.ratesalgo.backtest.util;

/**
 * Conversion between the wire representation (int64 mantissa, exponent -9, API_SPEC.md Section
 * 0) and the doubles used inside the back-tester's accounting.
 *
 * <p>Rounding rule at the boundary: {@link #toMantissa(double)} rounds half away from zero to the
 * nearest mantissa, and {@link #roundToTick(long, long, boolean)} rounds to a tick in the
 * direction that is worse for the dealer (bids down, offers up).
 */
public final class Prices {

  public static final double SCALE = 1_000_000_000.0;
  public static final long ONE = 1_000_000_000L;

  private Prices() {}

  public static double toDouble(final long mantissa) {
    return mantissa / SCALE;
  }

  public static long toMantissa(final double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("price is not finite: " + value);
    }
    return Math.round(value * SCALE);
  }

  /**
   * Rounds a mantissa to a multiple of {@code tick}: down when {@code roundUp} is false, up when
   * true.
   */
  public static long roundToTick(final long mantissa, final long tick, final boolean roundUp) {
    if (tick <= 0) {
      throw new IllegalArgumentException("tick must be positive: " + tick);
    }
    final long floor = Math.floorDiv(mantissa, tick) * tick;
    if (floor == mantissa || !roundUp) {
      return floor;
    }
    return floor + tick;
  }

  /** Rounds a currency amount to cents, half away from zero. */
  public static double cents(final double amount) {
    return Math.round(amount * 100.0) / 100.0;
  }
}
