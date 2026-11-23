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
  // from here on a double product is a whole number of units, and its rounding error can pass 1e-6
  private static final double WHOLE = 0x1p52;

  private Decimal9() {}

  /** Mantissa to double. */
  public static double toDouble(final long mantissa) {
    return mantissa / SCALE;
  }

  /** Nearest mantissa, half away from zero. Throws on NaN or out-of-range input. */
  public static long round(final double value) {
    check(value);
    return nearest(value);
  }

  /** Largest mantissa not above {@code value} (for bids). */
  public static long floor(final double value) {
    check(value);
    final double p = value * SCALE;
    if (Math.abs(p) < WHOLE) {
      return (long) Math.floor(p + 1e-6);
    }
    return (long) p + (long) Math.floor(Math.fma(value, SCALE, -p) + 1e-6);
  }

  /** Smallest mantissa not below {@code value} (for asks). */
  public static long ceil(final double value) {
    check(value);
    final double p = value * SCALE;
    if (Math.abs(p) < WHOLE) {
      return (long) Math.ceil(p - 1e-6);
    }
    return (long) p + (long) Math.ceil(Math.fma(value, SCALE, -p) - 1e-6);
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
    return nearest(value);
  }

  /**
   * Nearest mantissa, ties away from zero (Math.round alone takes ties toward positive infinity,
   * so -0.5 units became 0). From 2^52 the product is a whole number but may be off by up to half
   * its ulp (512 units near the bound); fma gives the product's exact error, which is added back.
   */
  private static long nearest(final double value) {
    final double p = value * SCALE;
    if (Math.abs(p) < WHOLE) {
      return p < 0 ? -Math.round(-p) : Math.round(p);
    }
    final double e = Math.fma(value, SCALE, -p);
    return (long) p + (p < 0 ? -Math.round(-e) : Math.round(e));
  }

  private static void check(final double value) {
    if (Double.isNaN(value) || value >= MAX || value <= -MAX) {
      throw new IllegalArgumentException("value not representable as Decimal9: " + value);
    }
  }
}
