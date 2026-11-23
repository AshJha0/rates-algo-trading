package io.ratesalgo.quoting;

import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ProductType;

/**
 * Probability of winning an RFQ from the distance to the predicted cover (review 2026-11-23, Q1
 * and Q3), and the quote that maximises expected P&amp;L above break-even.
 *
 * <p>Plain idea. A client trading at best price picks the best quote. We win when our distance
 * from fair is inside the best competing dealer's and inside the client's reserve, and the client
 * then trades. The best competing distance B is uncertain; it is modelled as lognormal per cell
 * (tenor, size bucket, number of dealers, swap or bond), starting from the calibrated competitor
 * quotes and learned from our own wins, losses and the covers the venue discloses.
 *
 * <pre>
 *   P(win | d) = fill(tier) * R(d) * ( pi0 + (1 - pi0) * S(d) )
 *   S(d) = P(B &gt; d) = 1 - Phi((ln d - mu) / sigma)     (1 for d &lt;= 0)
 *   R(d) = 1 - Phi((ln d - ln(tolerance * median)) / toleranceSd)   (the client's reserve)
 *   pi0  = (1 - response)^(dealers - 1)                  (no competitor answered)
 *   E(d) = P(win | d) * (d - C),  d* = argmax over d &gt;= C
 * </pre>
 *
 * C is the break-even distance the engine computes (adverse cost, hedge cost and inventory term,
 * bp); a quote is never below it, and when C rises the quote widens rather than passes. The search
 * runs from C to {@code max(C, 0)} plus the cover's 4-sigma upper quantile, so its bounds are set
 * by the cover, not by a fixed spread clamp.
 *
 * <p>Prior. Competitor half-spreads are {@code median * exp(logSd * Z)}, each of the other {@code
 * dealers - 1} answering with probability {@code response} (SIM_V2_DESIGN.md Section 7). Given at
 * least one answer, ln B is the log median plus logSd times the minimum of the answering normals;
 * its mean and variance are computed once by quadrature and the cell starts at that lognormal.
 *
 * <p>Learning (stochastic EM on censored data). Each outcome updates a cell's first two raw
 * moments of ln B with weight 1/min(n + 1, window):
 *
 * <ul>
 *   <li>a win with a disclosed cover gap g: B = d + g exactly (the cover is the best competing
 *       quote when we are best);
 *   <li>a win without a cover: B &gt; d, so the moments of the normal truncated above ln d;
 *   <li>a loss with a cover better than ours by g: B = d - g;
 *   <li>a loss without a cover: B &lt; d, the moments truncated below ln d.
 * </ul>
 *
 * The reserve, pi0 and the tier's fill probability are not learned. Allocation-free.
 */
public final class CoverModel {

  /** Tenor buckets: the calibration's nodes 2, 3, 5, 7, 10, 20 and 30 years. */
  public static final int TENORS = 7;

  /** Size buckets relative to the instrument's typical size: under 0.5, 0.5 to 2, 2 to 5, above. */
  public static final int SIZES = 4;

  /** Dealer buckets: three or fewer, four, five or more. */
  public static final int DEALERS = 3;

  /** Product buckets: swap, other. */
  public static final int PRODUCTS = 2;

  /** Number of cells. */
  public static final int CELLS = TENORS * SIZES * DEALERS * PRODUCTS;

  private static final double[] TENOR_EDGES = {2.5, 4.0, 6.0, 8.5, 15.0, 25.0};
  private static final double[] SIZE_EDGES = {0.5, 2.0, 5.0};
  private static final double PHI = (Math.sqrt(5.0) - 1.0) / 2.0;
  private static final int ITERATIONS = 64;
  private static final double MIN_VARIANCE = 1e-4;
  private static final double UPPER_SDS = 4.0;

  private final double logMedianSwap;
  private final double logMedianOther;
  private final double tolerance;
  private final double toleranceSd;
  private final double window;
  private final double[] pi0 = new double[DEALERS];
  private final double[] m1 = new double[CELLS];
  private final double[] m2 = new double[CELLS];
  private final double[] mu = new double[CELLS];
  private final double[] sigma = new double[CELLS];
  private final long[] n = new long[CELLS];
  private long observations;

  /**
   * A model at the prior.
   *
   * @param medianSwapBp competitors' median half-spread on swaps, bp (calibration 0.1827)
   * @param otherMultiple median on bonds as a multiple of the swap median (0.5, an assumption)
   * @param logSd log sd of one competitor's half-spread (0.5)
   * @param response probability that a competitor answers (0.9)
   * @param toleranceMultiple median client reserve as a multiple of the median (2.5)
   * @param toleranceLogSd log sd of the reserve (0.4)
   * @param window effective number of recent outcomes a cell remembers
   */
  public CoverModel(
      final double medianSwapBp,
      final double otherMultiple,
      final double logSd,
      final double response,
      final double toleranceMultiple,
      final double toleranceLogSd,
      final double window) {
    if (!(medianSwapBp > 0.0) || !(otherMultiple > 0.0) || !(logSd > 0.0) || !(response > 0.0 && response <= 1.0)
        || !(toleranceMultiple > 0.0) || !(toleranceLogSd > 0.0) || !(window >= 1.0)) {
      throw new IllegalArgumentException("cover model prior");
    }
    logMedianSwap = Math.log(medianSwapBp);
    logMedianOther = Math.log(medianSwapBp * otherMultiple);
    tolerance = Math.log(toleranceMultiple);
    toleranceSd = toleranceLogSd;
    this.window = window;
    final double[] mv = new double[2];
    for (int b = 0; b < DEALERS; b++) {
      final int competitors = b + 2;
      pi0[b] = Math.pow(1.0 - response, competitors);
      minOfAnswering(competitors, response, mv);
      for (int c = 0; c < CELLS; c++) {
        if (dealerBucket(c) != b) {
          continue;
        }
        final double m = (productBucket(c) == 0 ? logMedianSwap : logMedianOther) + logSd * mv[0];
        final double v = logSd * logSd * mv[1];
        m1[c] = m;
        m2[c] = v + m * m;
        mu[c] = m;
        sigma[c] = Math.sqrt(v);
      }
    }
  }

  /** The Round 3 calibration (data/calibration/sim_v2_params.json, competitors block). */
  public static CoverModel calibrated() {
    return new CoverModel(0.1827, 0.5, 0.5, 0.9, 2.5, 0.4, 500.0);
  }

  /** The cell of a request. */
  public static int cell(final Instrument inst, final double sizeRatio, final int dealers) {
    final double t = inst.tenorYears();
    int tenor = 0;
    while (tenor < TENOR_EDGES.length && t > TENOR_EDGES[tenor]) {
      tenor++;
    }
    int size = 0;
    while (size < SIZE_EDGES.length && sizeRatio >= SIZE_EDGES[size]) {
      size++;
    }
    final int dealer = dealers <= 3 ? 0 : (dealers == 4 ? 1 : 2);
    final int product = inst.type() == ProductType.SWAP ? 0 : 1;
    return ((tenor * SIZES + size) * DEALERS + dealer) * PRODUCTS + product;
  }

  /** Win probability at distance {@code d} bp from fair, for a client of fill probability {@code fill}. */
  public double winProbability(final int cell, final double fill, final double d) {
    if (d <= 0.0) {
      return fill;
    }
    final double ln = Math.log(d);
    final double logMedian = productBucket(cell) == 0 ? logMedianSwap : logMedianOther;
    final double reserve = NormalDist.upper((ln - logMedian - tolerance) / toleranceSd);
    final double beat = NormalDist.upper((ln - mu[cell]) / sigma[cell]);
    final double p0 = pi0[dealerBucket(cell)];
    return fill * reserve * (p0 + (1.0 - p0) * beat);
  }

  /** Expected P&amp;L per request at distance {@code d} with break-even {@code breakEvenBp}, bp. */
  public double expected(final int cell, final double fill, final double breakEvenBp, final double d) {
    return winProbability(cell, fill, d) * (d - breakEvenBp);
  }

  /**
   * The distance that maximises expected P&amp;L, never below {@code breakEvenBp}: a golden-section
   * search on [C, max(C, 0) + exp(mu + 4 sigma)].
   */
  public double optimal(final int cell, final double fill, final double breakEvenBp) {
    double a = breakEvenBp;
    double b = Math.max(breakEvenBp, 0.0) + Math.exp(mu[cell] + UPPER_SDS * sigma[cell]);
    double x1 = b - PHI * (b - a);
    double x2 = a + PHI * (b - a);
    double f1 = expected(cell, fill, breakEvenBp, x1);
    double f2 = expected(cell, fill, breakEvenBp, x2);
    for (int i = 0; i < ITERATIONS; i++) {
      if (f1 < f2) {
        a = x1;
        x1 = x2;
        f1 = f2;
        x2 = a + PHI * (b - a);
        f2 = expected(cell, fill, breakEvenBp, x2);
      } else {
        b = x2;
        x2 = x1;
        f2 = f1;
        x1 = b - PHI * (b - a);
        f1 = expected(cell, fill, breakEvenBp, x1);
      }
    }
    return Math.max(breakEvenBp, 0.5 * (a + b));
  }

  /** The cover observed exactly at {@code b} bp from fair (b &gt; 0). */
  public void observeExact(final int cell, final double b) {
    if (!(b > 0.0) || Double.isInfinite(b)) {
      return;
    }
    final double x = Math.log(b);
    update(cell, x, x * x);
  }

  /** The cover was further from fair than {@code d} (we won and no cover was shown). */
  public void observeAbove(final int cell, final double d) {
    if (!(d > 0.0) || Double.isInfinite(d)) {
      return;
    }
    final double z = (Math.log(d) - mu[cell]) / sigma[cell];
    final double lambda = NormalDist.hazard(z);
    final double mean = mu[cell] + sigma[cell] * lambda;
    final double var = sigma[cell] * sigma[cell] * Math.max(0.0, 1.0 + z * lambda - lambda * lambda);
    update(cell, mean, var + mean * mean);
  }

  /** The cover was nearer fair than {@code d} (we lost and no cover was shown). */
  public void observeBelow(final int cell, final double d) {
    if (!(d > 0.0) || Double.isInfinite(d)) {
      return;
    }
    final double z = (Math.log(d) - mu[cell]) / sigma[cell];
    // the lower truncation is the upper one of -x
    final double lambda = NormalDist.hazard(-z);
    final double mean = mu[cell] - sigma[cell] * lambda;
    final double var = sigma[cell] * sigma[cell] * Math.max(0.0, 1.0 - z * lambda - lambda * lambda);
    update(cell, mean, var + mean * mean);
  }

  /** Median of the predicted best competing distance in a cell, bp. */
  public double predictedCoverBp(final int cell) {
    return Math.exp(mu[cell]);
  }

  /** Log mean of the predicted cover in a cell. */
  public double mu(final int cell) {
    return mu[cell];
  }

  /** Log sd of the predicted cover in a cell. */
  public double sigma(final int cell) {
    return sigma[cell];
  }

  /** Outcomes learned in a cell. */
  public long observations(final int cell) {
    return n[cell];
  }

  /** Outcomes learned in all cells. */
  public long observations() {
    return observations;
  }

  private void update(final int cell, final double first, final double second) {
    final long k = ++n[cell];
    observations++;
    final double w = 1.0 / Math.min((double) k + 1.0, window);
    m1[cell] += w * (first - m1[cell]);
    m2[cell] += w * (second - m2[cell]);
    mu[cell] = m1[cell];
    sigma[cell] = Math.sqrt(Math.max(MIN_VARIANCE, m2[cell] - m1[cell] * m1[cell]));
  }

  private static int dealerBucket(final int cell) {
    return (cell / PRODUCTS) % DEALERS;
  }

  private static int productBucket(final int cell) {
    return cell % PRODUCTS;
  }

  /**
   * Mean and variance of the minimum of the answering ones among {@code k} standard normals, each
   * answering with probability {@code q}, given that at least one answers. Trapezoidal rule on
   * [-10, 10]; construction only.
   */
  static void minOfAnswering(final int k, final double q, final double[] out) {
    final double h = 0.0005;
    final double none = Math.pow(1.0 - q, k);
    double mass = 0.0;
    double s1 = 0.0;
    double s2 = 0.0;
    for (int i = 0; i <= 40_000; i++) {
      final double z = -10.0 + i * h;
      final double tail = NormalDist.upper(z);
      double density = 0.0;
      double binom = 1.0;
      for (int j = 1; j <= k; j++) {
        binom = binom * (k - j + 1) / j;
        final double pj = binom * Math.pow(q, j) * Math.pow(1.0 - q, k - j);
        density += pj * j * NormalDist.pdf(z) * Math.pow(tail, j - 1);
      }
      density /= 1.0 - none;
      mass += density * h;
      s1 += density * z * h;
      s2 += density * z * z * h;
    }
    final double mean = s1 / mass;
    out[0] = mean;
    out[1] = s2 / mass - mean * mean;
  }
}
