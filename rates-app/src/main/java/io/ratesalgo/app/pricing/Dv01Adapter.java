package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.risk.Dv01Ladder;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;

/**
 * Maps P1's {@link Dv01Ladder} onto P3's {@link Dv01Vector} (DECISIONS.md DR-R2W-01).
 *
 * <p>The two types agree on sign: both are the value change for a one basis point rise, positive
 * for a payer swap and negative for a long bond, so no flip is needed. They differ in pillars: a
 * ladder's pillars are the curve instruments (here the SOFR swap tenors 2Y, 5Y, 10Y, 12Y and 30Y),
 * the vector's are the eight {@link KeyRateTenors}. Each ladder pillar's DV01 is split onto the
 * two neighbouring key-rate tenors linearly in years with {@link KeyRateTenors#split}, so the
 * parallel DV01 (the sum) is preserved exactly up to rounding.
 */
public final class Dv01Adapter {

  private final double[] pillarYears;
  private final double[][] weights;

  /** An adapter for ladders whose pillars mature at {@code pillarYears} (curve time in years). */
  public Dv01Adapter(final double[] pillarYears) {
    this.pillarYears = pillarYears.clone();
    this.weights = new double[pillarYears.length][KeyRateTenors.COUNT];
    for (int i = 0; i < pillarYears.length; i++) {
      KeyRateTenors.split(pillarYears[i], weights[i]);
    }
  }

  /** Number of ladder pillars. */
  public int pillars() {
    return pillarYears.length;
  }

  /**
   * Adds {@code scale} times the per-pillar DV01s in {@code ladder} onto {@code out}, which must
   * have {@link KeyRateTenors#COUNT} slots. Allocation-free.
   */
  public void accumulate(final double[] ladder, final double scale, final double[] out) {
    for (int i = 0; i < pillarYears.length; i++) {
      final double v = ladder[i] * scale;
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        out[k] += v * weights[i][k];
      }
    }
  }

  /** The vector of a P1 ladder (allocates; start of day and tests). */
  public Dv01Vector toVector(final Dv01Ladder ladder) {
    if (ladder.size() != pillarYears.length) {
      throw new IllegalArgumentException(
          "ladder has " + ladder.size() + " pillars, adapter " + pillarYears.length);
    }
    final double[] sum = new double[KeyRateTenors.COUNT];
    accumulate(ladder.keyRates(), 1.0, sum);
    final Dv01Vector out = new Dv01Vector(CurrencyCode.valueOf(ladder.currency()));
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      out.set(k, sum[k]);
    }
    return out;
  }
}
