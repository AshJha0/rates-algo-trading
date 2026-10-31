package io.ratesalgo.quoting;

/**
 * Probability of winning an RFQ as a logistic function of the quote's distance from fair and the
 * request's features (API_SPEC.md Section 12.2; DECISIONS.md DR-008 and DR-P3-02):
 *
 * <pre>
 *   z = b0 + b1 * spreadBp + b2 * ln(size / typical size) + b3 * tenorYears
 *         + b4 * liquidSession + b5 * (dealers - 1)
 *   P(win) = 1 / (1 + exp(-z))
 * </pre>
 *
 * The coefficients are a declared assumption band, not a fit: each has a central value and a
 * [low, high] band from the preset. Win and loss feedback moves b0 and b1 by one step of
 * stochastic gradient ascent on the log-likelihood and clamps them to their band, so learning
 * can never leave the declared assumption. Allocation-free.
 */
public final class HitRateModel {

  /** Index of each coefficient. */
  public static final int INTERCEPT = 0;

  public static final int SPREAD_BP = 1;
  public static final int LOG_SIZE = 2;
  public static final int TENOR_YEARS = 3;
  public static final int LIQUID_SESSION = 4;
  public static final int DEALERS_MINUS_ONE = 5;
  public static final int COUNT = 6;

  private final double[] beta = new double[COUNT];
  private final double[] low = new double[COUNT];
  private final double[] high = new double[COUNT];
  private final double learningRate;
  private long updates;

  /** A model at the preset's central values. */
  public HitRateModel(final HitRatePreset preset, final double learningRate) {
    for (int i = 0; i < COUNT; i++) {
      beta[i] = preset.value(i);
      low[i] = preset.low(i);
      high[i] = preset.high(i);
    }
    if (!(beta[SPREAD_BP] < 0.0)) {
      throw new IllegalArgumentException(
          "spread coefficient must be negative (wider loses more often): " + beta[SPREAD_BP]);
    }
    if (high[SPREAD_BP] >= 0.0) {
      throw new IllegalArgumentException("spread band must stay negative: " + high[SPREAD_BP]);
    }
    this.learningRate = learningRate;
  }

  /** The part of z that does not depend on the spread. */
  public double base(
      final double sizeRatio, final double tenorYears, final double liquidSession, final int dealers) {
    return beta[INTERCEPT]
        + beta[LOG_SIZE] * Math.log(Math.max(sizeRatio, 1e-6))
        + beta[TENOR_YEARS] * tenorYears
        + beta[LIQUID_SESSION] * liquidSession
        + beta[DEALERS_MINUS_ONE] * Math.max(0, dealers - 1);
  }

  /** P(win) at {@code spreadBp} given the spread-free part {@code base}. */
  public double winProbability(final double base, final double spreadBp) {
    return logistic(base + beta[SPREAD_BP] * spreadBp);
  }

  /** The spread coefficient (negative). */
  public double spreadCoefficient() {
    return beta[SPREAD_BP];
  }

  /** Coefficient {@code i}. */
  public double coefficient(final int i) {
    return beta[i];
  }

  /**
   * Feedback: we quoted {@code spreadBp} with spread-free part {@code base} and won or lost. Moves
   * the intercept and spread coefficient, then clamps both to their bands.
   */
  public void update(final double base, final double spreadBp, final boolean won) {
    final double p = winProbability(base, spreadBp);
    final double err = (won ? 1.0 : 0.0) - p;
    beta[INTERCEPT] = clamp(beta[INTERCEPT] + learningRate * err, INTERCEPT);
    beta[SPREAD_BP] = clamp(beta[SPREAD_BP] + learningRate * err * spreadBp, SPREAD_BP);
    updates++;
  }

  /** Feedback updates applied. */
  public long updates() {
    return updates;
  }

  static double logistic(final double z) {
    if (z >= 0.0) {
      return 1.0 / (1.0 + Math.exp(-z));
    }
    final double e = Math.exp(z);
    return e / (1.0 + e);
  }

  private double clamp(final double v, final int i) {
    return v < low[i] ? low[i] : (v > high[i] ? high[i] : v);
  }
}
