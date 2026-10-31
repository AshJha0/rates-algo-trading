package io.ratesalgo.analytics.curve;

/**
 * Hull-White one-factor convexity adjustment between an interest rate future and the forward rate
 * it references.
 *
 * <p>For short-rate dynamics dr = (theta(t) - a r) dt + sigma dW, the futures rate exceeds the
 * forward rate over [t1, t2] by (Hull, Options, Futures and Other Derivatives, 10th ed., Section
 * 6.4; Kirikos and Novak 1997):
 *
 * <pre>
 *   CA = B(t1, t2) / (t2 - t1) * [ B(t1, t2) (1 - exp(-2 a t1)) + 2 a B(0, t1)^2 ] * sigma^2 / (4 a)
 *   B(s, t) = (1 - exp(-a (t - s))) / a
 * </pre>
 *
 * with rates continuously compounded. The project applies it to SOFR futures with t1 the start
 * and t2 the end of the reference period, an approximation for in-arrears averaging that
 * DECISIONS.md DR-P1-05 states. As a tends to zero it becomes the Ho-Lee value sigma^2 t1 t2 / 2.
 * Default parameters (DR-P1-05): a = 0.03, sigma = 0.0085 per year.
 */
public final class HullWhite {

  /** Default mean reversion per year (DR-P1-05). */
  public static final double DEFAULT_A = 0.03;

  /** Default short-rate volatility per year (DR-P1-05). */
  public static final double DEFAULT_SIGMA = 0.0085;

  private HullWhite() {}

  /**
   * Futures-minus-forward convexity adjustment, decimal.
   *
   * @param a mean reversion per year, positive
   * @param sigma short-rate volatility per year
   * @param t1 start of the reference period in years from today, non-negative
   * @param t2 end of the reference period in years, after t1
   */
  public static double futuresConvexity(
      final double a, final double sigma, final double t1, final double t2) {
    if (!(t2 > t1) || t1 < 0.0) {
      throw new IllegalArgumentException("need 0 <= t1 < t2, got t1=" + t1 + " t2=" + t2);
    }
    if (a < 1e-8) {
      return 0.5 * sigma * sigma * t1 * t2;
    }
    final double b12 = (1.0 - Math.exp(-a * (t2 - t1))) / a;
    final double b01 = (1.0 - Math.exp(-a * t1)) / a;
    return b12 / (t2 - t1) * (b12 * (1.0 - Math.exp(-2.0 * a * t1)) + 2.0 * a * b01 * b01)
        * sigma * sigma / (4.0 * a);
  }
}
