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
 * The coefficients start at a declared assumption, not a fit: each has a central value and a
 * [low, high] band from the preset. Public data shows no losing quotes (DR-008), so the intercept
 * b0 and the spread slope b1 are learned from the desk's own wins and losses (P&L audit item 2).
 * Each outcome takes one Newton step on the log-likelihood of (b0, b1), using an information
 * matrix that starts at the prior precision {@code 1 / learningRate} and forgets old outcomes with
 * a window of {@link #WINDOW} RFQs, so the first steps equal plain gradient ascent at {@code
 * learningRate} and later ones shrink as outcomes accumulate. The learned values are clamped to
 * learning bounds wider than the declared band ({@link #SLOPE_LEARN_LOW} to {@link
 * #SLOPE_LEARN_HIGH} for the slope), so a market where price decides (b1 near -5) or one where it
 * hardly matters (b1 near -0.5) can be reached; {@link #slopeOutsideDeclaredBand()} says when the
 * desk's own data has left the assumption. Allocation-free.
 *
 * <p>{@link #base} excludes the intercept, so a base stored at quote time stays valid after the
 * intercept has been learned.
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

  /** Steepest spread slope learning may reach (AUDIT-01 sweeps down to -5.0). */
  public static final double SLOPE_LEARN_LOW = -8.0;

  /** Shallowest spread slope learning may reach; stays negative (wider loses more often). */
  public static final double SLOPE_LEARN_HIGH = -0.2;

  /** Intercept learning bounds. */
  public static final double INTERCEPT_LEARN_LOW = -3.0;

  public static final double INTERCEPT_LEARN_HIGH = 5.0;

  /** Effective number of recent outcomes the information matrix remembers. */
  public static final double WINDOW = 5_000.0;

  private static final double DECAY = 1.0 - 1.0 / WINDOW;

  private final double[] beta = new double[COUNT];
  private final double[] low = new double[COUNT];
  private final double[] high = new double[COUNT];
  private final double declaredSlopeLow;
  private final double declaredSlopeHigh;
  private final double learningRate;
  private final double priorPrecision;
  // data part of the 2x2 information matrix of (b0, b1)
  private double info00;
  private double info01;
  private double info11;
  private long updates;
  private long wins;

  /** A model at the preset's central values, learning with the default bounds. */
  public HitRateModel(final HitRatePreset preset, final double learningRate) {
    this(preset, learningRate, SLOPE_LEARN_LOW, SLOPE_LEARN_HIGH);
  }

  /**
   * A model at the preset's central values whose spread slope may be learned within [{@code
   * slopeLow}, {@code slopeHigh}]; the bounds must contain the preset's band and stay negative.
   */
  public HitRateModel(
      final HitRatePreset preset,
      final double learningRate,
      final double slopeLow,
      final double slopeHigh) {
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
    if (!(slopeHigh < 0.0) || !(slopeLow <= low[SPREAD_BP]) || !(slopeHigh >= high[SPREAD_BP])) {
      throw new IllegalArgumentException(
          "slope learning bounds [" + slopeLow + ", " + slopeHigh + "] must contain the band ["
              + low[SPREAD_BP] + ", " + high[SPREAD_BP] + "] and stay negative");
    }
    if (!(learningRate >= 0.0) || Double.isInfinite(learningRate)) {
      throw new IllegalArgumentException("learning rate " + learningRate);
    }
    declaredSlopeLow = low[SPREAD_BP];
    declaredSlopeHigh = high[SPREAD_BP];
    low[SPREAD_BP] = slopeLow;
    high[SPREAD_BP] = slopeHigh;
    low[INTERCEPT] = Math.min(low[INTERCEPT], INTERCEPT_LEARN_LOW);
    high[INTERCEPT] = Math.max(high[INTERCEPT], INTERCEPT_LEARN_HIGH);
    this.learningRate = learningRate;
    this.priorPrecision = learningRate > 0.0 ? 1.0 / learningRate : 0.0;
  }

  /** The part of z that depends on neither the spread nor the learned intercept. */
  public double base(
      final double sizeRatio, final double tenorYears, final double liquidSession, final int dealers) {
    return beta[LOG_SIZE] * Math.log(Math.max(sizeRatio, 1e-6))
        + beta[TENOR_YEARS] * tenorYears
        + beta[LIQUID_SESSION] * liquidSession
        + beta[DEALERS_MINUS_ONE] * Math.max(0, dealers - 1);
  }

  /** P(win) at {@code spreadBp} given the spread-free part {@code base}. */
  public double winProbability(final double base, final double spreadBp) {
    return logistic(beta[INTERCEPT] + base + beta[SPREAD_BP] * spreadBp);
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
   * Feedback: we quoted {@code spreadBp} with spread-free part {@code base} and won or lost. Takes
   * one Newton step on (b0, b1), then clamps both to their learning bounds. A learning rate of 0
   * counts the outcome and learns nothing.
   */
  public void update(final double base, final double spreadBp, final boolean won) {
    updates++;
    if (won) {
      wins++;
    }
    if (learningRate == 0.0) {
      return;
    }
    final double p = winProbability(base, spreadBp);
    final double err = (won ? 1.0 : 0.0) - p;
    final double w = Math.max(p * (1.0 - p), 1e-9);
    info00 = DECAY * info00 + w;
    info01 = DECAY * info01 + w * spreadBp;
    info11 = DECAY * info11 + w * spreadBp * spreadBp;
    final double i00 = info00 + priorPrecision;
    final double i01 = info01;
    final double i11 = info11 + priorPrecision;
    final double det = i00 * i11 - i01 * i01;
    final double g0 = err;
    final double g1 = err * spreadBp;
    final double d0 = (i11 * g0 - i01 * g1) / det;
    final double d1 = (i00 * g1 - i01 * g0) / det;
    beta[INTERCEPT] = clamp(beta[INTERCEPT] + d0, INTERCEPT);
    beta[SPREAD_BP] = clamp(beta[SPREAD_BP] + d1, SPREAD_BP);
  }

  /** The spread slope learned from the desk's own outcomes, for the daily report. */
  public double learnedSlope() {
    return beta[SPREAD_BP];
  }

  /** The intercept learned from the desk's own outcomes. */
  public double learnedIntercept() {
    return beta[INTERCEPT];
  }

  /**
   * Approximate standard error of the learned slope from the information matrix (prior included),
   * or NaN when learning is off.
   */
  public double slopeStandardError() {
    if (priorPrecision == 0.0) {
      return Double.NaN;
    }
    final double i00 = info00 + priorPrecision;
    final double i11 = info11 + priorPrecision;
    return Math.sqrt(i00 / (i00 * i11 - info01 * info01));
  }

  /** Whether the learned slope has left the preset's declared band. */
  public boolean slopeOutsideDeclaredBand() {
    return beta[SPREAD_BP] < declaredSlopeLow || beta[SPREAD_BP] > declaredSlopeHigh;
  }

  /** Lower end of the declared slope band (the preset's assumption). */
  public double declaredSlopeLow() {
    return declaredSlopeLow;
  }

  /** Upper end of the declared slope band. */
  public double declaredSlopeHigh() {
    return declaredSlopeHigh;
  }

  /** Lower learning bound of coefficient {@code i}. */
  public double learnLow(final int i) {
    return low[i];
  }

  /** Upper learning bound of coefficient {@code i}. */
  public double learnHigh(final int i) {
    return high[i];
  }

  /** Feedback updates applied. */
  public long updates() {
    return updates;
  }

  /** Wins among the feedback updates. */
  public long wins() {
    return wins;
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
