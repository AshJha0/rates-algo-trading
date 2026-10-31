package io.ratesalgo.api;

/**
 * The key-rate pillars every DV01 ladder in P3 is expressed on: 1Y, 2Y, 3Y, 5Y, 7Y, 10Y, 20Y and
 * 30Y. A sensitivity at a maturity between two pillars is split between them linearly in years
 * (API_SPEC.md Section 13.1).
 */
public final class KeyRateTenors {

  /** Number of pillars. */
  public static final int COUNT = 8;

  private static final double[] YEARS = {1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0};
  private static final String[] LABELS = {"1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y"};

  private KeyRateTenors() {}

  /** Maturity of pillar {@code k} in years. */
  public static double years(final int k) {
    return YEARS[k];
  }

  /** Label of pillar {@code k}, for example {@code 10Y}. */
  public static String label(final int k) {
    return LABELS[k];
  }

  /**
   * Writes into {@code weights} the share of a unit sensitivity at {@code maturityYears} that
   * falls on each pillar. Weights are non-negative and sum to one. Maturities before the first
   * pillar or after the last go wholly to that pillar.
   */
  public static void split(final double maturityYears, final double[] weights) {
    if (weights.length != COUNT) {
      throw new IllegalArgumentException("weights length " + weights.length + " != " + COUNT);
    }
    if (!(maturityYears > 0.0) || Double.isInfinite(maturityYears)) {
      throw new IllegalArgumentException("maturity must be positive and finite: " + maturityYears);
    }
    java.util.Arrays.fill(weights, 0.0);
    if (maturityYears <= YEARS[0]) {
      weights[0] = 1.0;
      return;
    }
    if (maturityYears >= YEARS[COUNT - 1]) {
      weights[COUNT - 1] = 1.0;
      return;
    }
    for (int k = 1; k < COUNT; k++) {
      if (maturityYears <= YEARS[k]) {
        final double w = (maturityYears - YEARS[k - 1]) / (YEARS[k] - YEARS[k - 1]);
        weights[k - 1] = 1.0 - w;
        weights[k] = w;
        return;
      }
    }
  }
}
