package io.ratesalgo.venues.sim;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Decimal text to and from scaled longs for the simulators. Prices use exponent -9 (API_SPEC.md
 * Section 0); quantities on FIX are currency units. No conversion goes through {@code double}.
 */
public final class Px {

  private Px() {}

  /** Decimal text to a price mantissa (exponent -9), exactly. */
  public static long price(final String text) {
    return new BigDecimal(text).movePointRight(9).longValueExact();
  }

  /** Price mantissa to plain decimal text without trailing zeros. */
  public static String text(final long mantissa) {
    final BigDecimal d = new BigDecimal(BigInteger.valueOf(mantissa), 9).stripTrailingZeros();
    return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
  }

  /** Decimal text quantity in currency units to a long, exactly. */
  public static long qty(final String text) {
    return new BigDecimal(text).longValueExact();
  }
}
