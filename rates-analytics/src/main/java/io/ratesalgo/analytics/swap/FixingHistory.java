package io.ratesalgo.analytics.swap;

import io.ratesalgo.analytics.date.Dates;
import java.util.Arrays;

/**
 * Published fixings of an overnight index: dates in increasing order and rates as decimals
 * (0.0531 for 5.31 percent). Lookup is a binary search and does not allocate.
 */
public final class FixingHistory {

  /** The empty history. */
  public static final FixingHistory EMPTY = new FixingHistory(new int[0], new double[0]);

  private final int[] dates;
  private final double[] rates;

  /**
   * Creates the instance from its parts.
   *
   * @param dates strictly increasing fixing dates (the date each rate applies to)
   * @param rates decimal rates, one per date
   */
  public FixingHistory(final int[] dates, final double[] rates) {
    if (dates.length != rates.length) {
      throw new IllegalArgumentException("dates and rates differ in length");
    }
    for (int i = 1; i < dates.length; i++) {
      if (dates[i] <= dates[i - 1]) {
        throw new IllegalArgumentException("fixing dates not increasing at " + Dates.format(
            dates[i]));
      }
    }
    this.dates = Arrays.copyOf(dates, dates.length);
    this.rates = Arrays.copyOf(rates, rates.length);
  }

  /** True if a fixing exists for the date. */
  public boolean has(final int date) {
    return Arrays.binarySearch(dates, date) >= 0;
  }

  /**
   * The fixing for the date.
   *
   * @throws IllegalStateException if none was published (no silent NaN)
   */
  public double rate(final int date) {
    final int i = Arrays.binarySearch(dates, date);
    if (i < 0) {
      throw new IllegalStateException("missing fixing for " + Dates.format(date));
    }
    return rates[i];
  }

  /** Number of fixings. */
  public int size() {
    return dates.length;
  }

  /** Date of fixing i. */
  public int date(final int i) {
    return dates[i];
  }

  /** Rate of fixing i. */
  public double rateAt(final int i) {
    return rates[i];
  }
}
