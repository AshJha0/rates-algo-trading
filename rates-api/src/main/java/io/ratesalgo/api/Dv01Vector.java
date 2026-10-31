package io.ratesalgo.api;

import java.util.Arrays;

/**
 * A key-rate PV01 ladder in one currency: for each pillar of {@link KeyRateTenors}, the change in
 * present value, in major units of the currency, when that pillar's zero rate rises by one basis
 * point and the others stay fixed.
 *
 * <p>Sign convention (API_SPEC.md Section 13.1): PV01 is PV(rates + 1bp) minus PV(base). A long
 * bond or long future has negative PV01; a payer swap has positive PV01. The vector is mutable and
 * reused on the hot path; it never allocates after construction. The coordinator adapts P1's
 * {@code io.ratesalgo.analytics.risk.Dv01Ladder} to this type at the boundary.
 */
public final class Dv01Vector {

  private final double[] values = new double[KeyRateTenors.COUNT];
  private CurrencyCode currency;

  /** An all-zero ladder in {@code currency}. */
  public Dv01Vector(final CurrencyCode currency) {
    this.currency = currency;
  }

  /** The ladder's currency. */
  public CurrencyCode currency() {
    return currency;
  }

  /** Sets the currency without touching the values. */
  public void currency(final CurrencyCode newCurrency) {
    this.currency = newCurrency;
  }

  /** PV01 at pillar {@code k}. */
  public double get(final int k) {
    return values[k];
  }

  /** Sets pillar {@code k}. */
  public void set(final int k, final double value) {
    values[k] = value;
  }

  /** Adds to pillar {@code k}. */
  public void add(final int k, final double value) {
    values[k] += value;
  }

  /** Zeroes every pillar. */
  public void clear() {
    Arrays.fill(values, 0.0);
  }

  /** Copies currency and values from {@code other}. */
  public void copyFrom(final Dv01Vector other) {
    currency = other.currency;
    System.arraycopy(other.values, 0, values, 0, values.length);
  }

  /** Adds {@code scale} times {@code other}; the currencies must match. */
  public void addScaled(final Dv01Vector other, final double scale) {
    if (other.currency != currency) {
      throw new IllegalArgumentException("currency " + other.currency + " != " + currency);
    }
    for (int k = 0; k < values.length; k++) {
      values[k] += scale * other.values[k];
    }
  }

  /** Parallel PV01: the sum over pillars. */
  public double parallel() {
    double sum = 0.0;
    for (final double v : values) {
      sum += v;
    }
    return sum;
  }

  /** Gross PV01: the sum of absolute values over pillars. */
  public double gross() {
    double sum = 0.0;
    for (final double v : values) {
      sum += Math.abs(v);
    }
    return sum;
  }

  /** Copies the values into {@code out}, which must have {@link KeyRateTenors#COUNT} slots. */
  public void copyTo(final double[] out) {
    System.arraycopy(values, 0, out, 0, values.length);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(currency.name()).append(" [");
    for (int k = 0; k < values.length; k++) {
      if (k > 0) {
        sb.append(", ");
      }
      sb.append(KeyRateTenors.label(k)).append('=').append(String.format("%.2f", values[k]));
    }
    return sb.append(']').toString();
  }
}
