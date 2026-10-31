package io.ratesalgo.quoting;

/**
 * Chooses the half-spread (bp of value from fair) that maximises expected profit per request
 * (API_SPEC.md Section 12.2):
 *
 * <pre>
 *   E(s) = Pbar(win | s) * (s - c),   c = adverse selection + winner's curse + hedge cost (all bp)
 *   Pbar(win | s) = E_g[ P(win | s - g) ],   g ~ N(0, coverSigma^2)
 * </pre>
 *
 * The hit-rate model is evaluated at the expected cover; {@code Pbar} averages it over the cover's
 * spread around that point (P&L audit item 11), with 8-point Gauss-Hermite quadrature. The cover
 * spread is learned from the gaps between our winning prices and the disclosed covers, fed back
 * through {@link #observeCoverGap}; until a gap arrives it is the prior {@code coverSigmaBp} given
 * at construction (0 by default, which is the point estimate). Gaps are seen only on wins, so the
 * observed spread is that of a truncated sample and understates the true one; it is a floor, not a
 * fit.
 *
 * <p>With a logistic P and no cover spread the maximiser is unique and satisfies {@code s = c + 1
 * / (-b1 (1 - P(s)))}; this class finds it by golden-section search on [0, maxHalfSpread], which
 * needs no derivative and does not allocate.
 */
public final class SpreadModel {

  private static final double PHI = (Math.sqrt(5.0) - 1.0) / 2.0;
  private static final int ITERATIONS = 48;

  /** Gauss-Hermite nodes (positive half) and weights divided by sqrt(pi), 8 points. */
  private static final double[] GH_NODE = {
    0.3811869902073221, 1.1571937124467802, 1.981656756695843, 2.9306374202572440
  };

  private static final double[] GH_WEIGHT = {
    0.6611470125582413 / 1.7724538509055159,
    0.2078023258148919 / 1.7724538509055159,
    0.0170779830074134 / 1.7724538509055159,
    0.0001996040722114 / 1.7724538509055159
  };

  /** Recent cover gaps the running moments remember. */
  public static final double COVER_WINDOW = 500.0;

  private final HitRateModel hitRate;
  private final double priorCoverSigmaBp;
  private long coverGaps;
  private double gapMean;
  private double gapSquare;

  /** A model at the point estimate of the cover until gaps are observed. */
  public SpreadModel(final HitRateModel hitRate) {
    this(hitRate, 0.0);
  }

  /** A model averaging over a cover spread of {@code priorCoverSigmaBp} until gaps are observed. */
  public SpreadModel(final HitRateModel hitRate, final double priorCoverSigmaBp) {
    if (!(priorCoverSigmaBp >= 0.0) || Double.isInfinite(priorCoverSigmaBp)) {
      throw new IllegalArgumentException("prior cover sigma " + priorCoverSigmaBp);
    }
    this.hitRate = hitRate;
    this.priorCoverSigmaBp = priorCoverSigmaBp;
  }

  /** Win probability at {@code s}, averaged over the cover spread. */
  public double winProbability(final double base, final double s) {
    final double sigma = coverSigmaBp();
    if (sigma == 0.0) {
      return hitRate.winProbability(base, s);
    }
    final double scale = Math.sqrt(2.0) * sigma;
    double p = 0.0;
    for (int i = 0; i < GH_NODE.length; i++) {
      final double d = scale * GH_NODE[i];
      p += GH_WEIGHT[i] * (hitRate.winProbability(base, s - d) + hitRate.winProbability(base, s + d));
    }
    return p;
  }

  /** Expected profit per request, in bp, at half-spread {@code s}. */
  public double expected(final double base, final double costBp, final double s) {
    return winProbability(base, s) * (s - costBp);
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

  /**
   * Feedback: on a win, the distance in bp between our price and the disclosed cover (positive
   * when the cover was worse for the client). Updates the running mean and spread of the gap.
   * Non-finite gaps are ignored. Allocation-free.
   */
  public void observeCoverGap(final double gapBp) {
    if (!Double.isFinite(gapBp)) {
      return;
    }
    coverGaps++;
    final double alpha = 1.0 / Math.min((double) coverGaps, COVER_WINDOW);
    gapMean += alpha * (gapBp - gapMean);
    gapSquare += alpha * (gapBp * gapBp - gapSquare);
  }

  /** The cover spread used in the average: the observed one, or the prior before any gap. */
  public double coverSigmaBp() {
    if (coverGaps < 2) {
      return priorCoverSigmaBp;
    }
    return Math.sqrt(Math.max(0.0, gapSquare - gapMean * gapMean));
  }

  /** Running mean of the observed cover gap, bp; 0 before any gap. */
  public double meanCoverGapBp() {
    return gapMean;
  }

  /** Cover gaps observed. */
  public long coverGaps() {
    return coverGaps;
  }

  public HitRateModel hitRate() {
    return hitRate;
  }
}
