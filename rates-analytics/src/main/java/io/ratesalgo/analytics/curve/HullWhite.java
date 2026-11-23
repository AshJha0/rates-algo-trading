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

  /**
   * Variance of the integral of the short rate over [0, t] under the risk-neutral measure:
   * V(t) = (sigma^2 / a^2) [t - 2 B(0, t) + (1 - exp(-2 a t)) / (2 a)], and sigma^2 t^3 / 3 as a
   * tends to zero.
   *
   * <p>Computed as sigma^2 t^3 phi(a t) with phi(x) = [x - 2 (1 - e^-x) + (1 - e^-2x) / 2] / x^3.
   * The bracket cancels to third order in x, so below x = 0.1 phi is summed from its power series
   * (coefficients (-1)^n (2 - 2^(n-1)) / n!, n from 3), which is exact to rounding by the
   * fourteenth term; above it the closed form loses under 1e-12.
   */
  public static double integratedVariance(final double a, final double sigma, final double t) {
    if (t <= 0.0) {
      return 0.0;
    }
    final double x = a * t;
    final double phi;
    if (x < 0.1) {
      double sum = 0.0;
      double power = 1.0;
      double factorial = 6.0;
      double twoPower = 4.0;
      for (int n = 3; n <= 16; n++) {
        final double c = (2.0 - twoPower) / factorial;
        sum += (n % 2 == 0 ? c : -c) * power;
        power *= x;
        factorial *= n + 1;
        twoPower *= 2.0;
      }
      phi = sum;
    } else {
      phi = (x + 2.0 * Math.expm1(-x) - 0.5 * Math.expm1(-2.0 * x)) / (x * x * x);
    }
    return sigma * sigma * t * t * t * phi;
  }

  /**
   * Convexity adjustment of a future on the overnight rate compounded in arrears over [t1, t2]
   * (CME three-month SOFR): the futures rate minus the forward rate, decimal, to first order in
   * the period length.
   *
   * <p>Plain idea. {@link #futuresConvexity} is Hull's formula for a rate that is fixed at t1 and
   * continuously compounded. A SOFR future settles on the rate compounded day by day through the
   * period, so the rate keeps moving, and keeps being margined, until t2: the adjustment is
   * larger. In the Ho-Lee limit Hull's value is sigma^2 (t1^2 / 2 + t1 tau / 2) and this one is
   * sigma^2 (t1^2 / 2 + t1 tau + tau^2 / 3), with tau = t2 - t1.
   *
   * <p>Formal statement. The futures rate is E[(exp(I) - 1) / tau] under the risk-neutral measure
   * with I the integral of the short rate over [t1, t2]. I is Gaussian. From E[exp(-integral over
   * [0, t])] = P(0, t), its mean is ln(P(0, t1) / P(0, t2)) + (V(t2) - V(t1)) / 2 with V as in
   * {@link #integratedVariance}; its variance is B(t1, t2)^2 Var[r(t1)] + V(tau), with Var[r(t1)]
   * = sigma^2 (1 - exp(-2 a t1)) / (2 a). So E[exp(I)] is the forward compounding factor times
   * exp(z), z = (V(t2) - V(t1) + Var[I]) / 2, and the futures rate exceeds the forward rate by
   * (exp(z) - 1)(F + 1 / tau). The value returned is z / tau, which leaves out the factor 1 + tau F
   * (about one percent of the adjustment for a quarter at 4 percent) so that the adjustment does
   * not depend on the rate. A period that has started is treated from today: t1 below zero is
   * taken as zero and the result is still divided by the whole period t2 - t1.
   *
   * <p>The golden file compares it with a numerical double integral of the short-rate covariance
   * ({@code PricingV2GoldenTest}).
   *
   * @param a mean reversion per year, non-negative
   * @param sigma short-rate volatility per year
   * @param t1 start of the reference period in years from today (negative if it has started)
   * @param t2 end of the reference period in years, positive and after t1
   */
  public static double compoundedFuturesConvexity(
      final double a, final double sigma, final double t1, final double t2) {
    if (!(t2 > t1) || !(t2 > 0.0)) {
      throw new IllegalArgumentException("need t1 < t2 and t2 > 0, got t1=" + t1 + " t2=" + t2);
    }
    final double from = Math.max(t1, 0.0);
    final double tau = t2 - from;
    final double b = a < 1e-12 ? tau : -Math.expm1(-a * tau) / a;
    final double rateVariance =
        sigma * sigma * (a < 1e-12 ? from : -Math.expm1(-2.0 * a * from) / (2.0 * a));
    final double periodVariance = b * b * rateVariance + integratedVariance(a, sigma, tau);
    final double z =
        0.5 * (integratedVariance(a, sigma, t2) - integratedVariance(a, sigma, from) + periodVariance);
    return z / (t2 - t1);
  }

  /**
   * Convexity adjustment of a future on the arithmetic average of the overnight rate over [t1,
   * t2] (CME one-month SOFR): the futures rate minus the forward rate, decimal. The average is
   * linear in the rate, so only the drift of the short rate under the risk-neutral measure
   * enters: (V(t2) - V(t1)) / (2 tau) with V as in {@link #integratedVariance}; in the Ho-Lee limit
   * sigma^2 (t1^2 + t1 tau + tau^2 / 3) / 2. A started period is treated as in {@link
   * #compoundedFuturesConvexity}.
   */
  public static double averageFuturesConvexity(
      final double a, final double sigma, final double t1, final double t2) {
    if (!(t2 > t1) || !(t2 > 0.0)) {
      throw new IllegalArgumentException("need t1 < t2 and t2 > 0, got t1=" + t1 + " t2=" + t2);
    }
    final double from = Math.max(t1, 0.0);
    return 0.5 * (integratedVariance(a, sigma, t2) - integratedVariance(a, sigma, from)) / (t2 - t1);
  }
}
