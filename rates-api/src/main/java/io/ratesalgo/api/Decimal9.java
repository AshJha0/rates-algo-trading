package io.ratesalgo.api;

/**
 * Conversions between doubles and the int64 mantissas with exponent -9 used on the wire
 * (API_SPEC.md Section 0). Rounding is stated per call: bids round down and asks round up, so a
 * quote is never better for the client than the price the engine computed.
 */
public final class Decimal9 {

  /** One unit as a mantissa. */
  public static final long ONE = 1_000_000_000L;

  private static final double SCALE = 1e9;
  private static final double MAX = 9.2e9;

  private Decimal9() {}

  /** Mantissa to double. */
  public static double toDouble(final long mantissa) {
    return mantissa / SCALE;
  }

  /** Nearest mantissa, half away from zero. Throws on NaN or out-of-range input. */
  public static long round(final double value) {
    check(value);
    return Math.round(value * SCALE);
  }

  /** Largest mantissa not above {@code value} (for bids). */
  public static long floor(final double value) {
    check(value);
    return (long) Math.floor(value * SCALE + 1e-6);
  }

  /** Smallest mantissa not below {@code value} (for asks). */
  public static long ceil(final double value) {
    check(value);
    return (long) Math.ceil(value * SCALE - 1e-6);
  }

  /** Mantissa for an alert field: rounds and saturates instead of throwing on large values. */
  public static long saturating(final double value) {
    if (Double.isNaN(value)) {
      return Long.MIN_VALUE;
    }
    if (value >= MAX) {
      return Long.MAX_VALUE;
    }
    if (value <= -MAX) {
      return Long.MIN_VALUE + 1;
    }
    return Math.round(value * SCALE);
  }

  private static void check(final double value) {
    if (Double.isNaN(value) || value >= MAX || value <= -MAX) {
      throw new IllegalArgumentException("value not representable as Decimal9: " + value);
    }
  }
}
