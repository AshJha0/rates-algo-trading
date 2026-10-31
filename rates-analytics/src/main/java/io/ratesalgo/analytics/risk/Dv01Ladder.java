package io.ratesalgo.analytics.risk;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A DV01 ladder: the change in value of a position for a one basis point rise in each curve
 * input (key-rate DV01 by pillar) and in all of them together (parallel DV01). The value type the
 * P3 risk engine consumes for swaps, bonds and futures (API_SPEC.md Section 8).
 *
 * <p>Units: currency units per basis point, positive when the position gains as rates rise (a
 * payer swap, a short bond). The pillar tenors are the labels of the curve instruments, for
 * example {@code 2Y}, {@code 10Y} or a futures code. Immutable; arrays are copied in and out.
 */
public final class Dv01Ladder {

  private final String currency;
  private final List<String> tenors;
  private final double[] dv01;
  private final double parallel;

  /**
   * Creates the ladder.
   *
   * @param currency ISO 4217 code
   * @param tenors pillar labels, in curve order
   * @param dv01 per-pillar DV01, one per tenor
   * @param parallel parallel DV01
   */
  public Dv01Ladder(
      final String currency, final List<String> tenors, final double[] dv01,
      final double parallel) {
    if (tenors.size() != dv01.length) {
      throw new IllegalArgumentException(
          "tenor count " + tenors.size() + " differs from DV01 count " + dv01.length);
    }
    for (final double v : dv01) {
      if (!Double.isFinite(v)) {
        throw new IllegalArgumentException("non-finite key-rate DV01 in " + Arrays.toString(dv01));
      }
    }
    if (!Double.isFinite(parallel)) {
      throw new IllegalArgumentException("non-finite parallel DV01");
    }
    this.currency = Objects.requireNonNull(currency);
    this.tenors = List.copyOf(tenors);
    this.dv01 = Arrays.copyOf(dv01, dv01.length);
    this.parallel = parallel;
  }

  public String currency() {
    return currency;
  }

  /** Pillar labels in curve order. */
  public List<String> tenors() {
    return tenors;
  }

  /** Number of pillars. */
  public int size() {
    return dv01.length;
  }

  /** Key-rate DV01 of pillar i. */
  public double dv01(final int i) {
    return dv01[i];
  }

  /** Copy of the key-rate DV01s. */
  public double[] keyRates() {
    return Arrays.copyOf(dv01, dv01.length);
  }

  /** Parallel DV01. */
  public double parallel() {
    return parallel;
  }

  /** Sum of the key-rate DV01s; equals the parallel DV01 to rounding for a linear model. */
  public double keyRateSum() {
    double s = 0.0;
    for (final double v : dv01) {
      s += v;
    }
    return s;
  }

  /** This ladder times a scalar (position size, hedge ratio, sign). */
  public Dv01Ladder scaled(final double factor) {
    final double[] out = new double[dv01.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = dv01[i] * factor;
    }
    return new Dv01Ladder(currency, tenors, out, parallel * factor);
  }

  /** Pillar-by-pillar sum with another ladder on the same currency and tenors. */
  public Dv01Ladder plus(final Dv01Ladder other) {
    if (!currency.equals(other.currency) || !tenors.equals(other.tenors)) {
      throw new IllegalArgumentException("ladders differ in currency or tenors");
    }
    final double[] out = new double[dv01.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = dv01[i] + other.dv01[i];
    }
    return new Dv01Ladder(currency, tenors, out, parallel + other.parallel);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Dv01Ladder that)) {
      return false;
    }
    return Double.compare(parallel, that.parallel) == 0
        && currency.equals(that.currency)
        && tenors.equals(that.tenors)
        && Arrays.equals(dv01, that.dv01);
  }

  @Override
  public int hashCode() {
    return Objects.hash(currency, tenors, Arrays.hashCode(dv01), parallel);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("Dv01Ladder[").append(currency);
    for (int i = 0; i < dv01.length; i++) {
      sb.append(' ').append(tenors.get(i)).append('=').append(dv01[i]);
    }
    return sb.append(" parallel=").append(parallel).append(']').toString();
  }
}
