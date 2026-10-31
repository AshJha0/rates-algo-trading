package io.ratesalgo.quoting;

/**
 * Chooses the half-spread (bp of value from fair) that maximises expected profit per request
 * (API_SPEC.md Section 12.2):
 *
 * <pre>
 *   E(s) = P(win | s) * (s - c),   c = adverse selection + winner's curse + hedge cost (all bp)
 * </pre>
 *
 * With a logistic P the maximiser is unique and satisfies {@code s = c + 1 / (-b1 (1 - P(s)))};
 * this class finds it by golden-section search on [0, maxHalfSpread], which needs no derivative
 * and does not allocate.
 */
public final class SpreadModel {

  private static final double PHI = (Math.sqrt(5.0) - 1.0) / 2.0;
  private static final int ITERATIONS = 48;

  private final HitRateModel hitRate;

  public SpreadModel(final HitRateModel hitRate) {
    this.hitRate = hitRate;
  }

  /** Expected profit per request, in bp, at half-spread {@code s}. */
  public double expected(final double base, final double costBp, final double s) {
    return hitRate.winProbability(base, s) * (s - costBp);
  }

  /** The maximising half-spread in [0, maxBp]. */
  public double optimal(final double base, final double costBp, final double maxBp) {
    double a = 0.0;
    double b = maxBp;
    double x1 = b - PHI * (b - a);
    double x2 = a + PHI * (b - a);
    double f1 = expected(base, costBp, x1);
    double f2 = expected(base, costBp, x2);
    for (int i = 0; i < ITERATIONS; i++) {
      if (f1 < f2) {
        a = x1;
        x1 = x2;
        f1 = f2;
        x2 = a + PHI * (b - a);
        f2 = expected(base, costBp, x2);
      } else {
        b = x2;
        x2 = x1;
        f2 = f1;
        x1 = b - PHI * (b - a);
        f1 = expected(base, costBp, x1);
      }
    }
    return 0.5 * (a + b);
  }

  public HitRateModel hitRate() {
    return hitRate;
  }
}
