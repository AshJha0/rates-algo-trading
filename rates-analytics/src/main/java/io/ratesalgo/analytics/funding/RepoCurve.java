package io.ratesalgo.analytics.funding;

import java.util.Arrays;

/**
 * A general collateral repo term curve: simple Act/360 rates by term in calendar days, linear in
 * the term between points and flat outside them. The overnight point comes from the New York
 * Fed's TGCR (tri-party) or BGCR (broad GC) published rates; term points are declared inputs
 * (DR-P1-07), since no free term GC series exists. A bond that trades special finances at GC minus
 * its specialness spread.
 *
 * <p>The funding charge on inventory is the cost of carrying a position in repo: value times
 * rate times days / 360. Exact; no allocation after construction.
 */
public final class RepoCurve {

  private final int[] termDays;
  private final double[] rates;

  /**
   * Creates the curve.
   *
   * @param termDays strictly increasing terms in calendar days, at least one
   * @param rates decimal Act/360 rates, one per term
   */
  public RepoCurve(final int[] termDays, final double[] rates) {
    if (termDays.length == 0 || termDays.length != rates.length) {
      throw new IllegalArgumentException("need equal, non-empty term and rate arrays");
    }
    for (int i = 1; i < termDays.length; i++) {
      if (termDays[i] <= termDays[i - 1]) {
        throw new IllegalArgumentException("terms not increasing at " + i);
      }
    }
    this.termDays = Arrays.copyOf(termDays, termDays.length);
    this.rates = Arrays.copyOf(rates, rates.length);
  }

  /** A flat curve at one rate, for example an overnight TGCR fixing. */
  public static RepoCurve flat(final double rate) {
    return new RepoCurve(new int[] {1}, new double[] {rate});
  }

  /** GC rate for a term in days. */
  public double rate(final int days) {
    if (days <= termDays[0]) {
      return rates[0];
    }
    final int n = termDays.length;
    if (days >= termDays[n - 1]) {
      return rates[n - 1];
    }
    int k = 1;
    while (termDays[k] < days) {
      k++;
    }
    final double w = (double) (days - termDays[k - 1]) / (termDays[k] - termDays[k - 1]);
    return rates[k - 1] + w * (rates[k] - rates[k - 1]);
  }

  /** Repo rate for a bond trading special by {@code specialness} (decimal) below GC. */
  public double specialRate(final int days, final double specialness) {
    return rate(days) - specialness;
  }

  /** Interest on financing {@code value} for {@code days} at {@code rate}, Act/360 simple. */
  public static double fundingCharge(final double value, final int days, final double rate) {
    return value * rate * days / 360.0;
  }
}
