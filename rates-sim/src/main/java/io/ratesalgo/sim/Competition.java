package io.ratesalgo.sim;

import io.ratesalgo.sim.SimParams.Scalar;

/**
 * Closed-form quantities of the competitor model (design section 7), computed from the parameters
 * by numerical integration. They are what the start-up check and acceptance test A3 compare the
 * simulation with.
 */
public final class Competition {

  private static final double Z_RANGE = 10.0;
  private static final int INTERVALS = 4000;

  private Competition() {}

  /** Probability that one asked competitor answers inside the response window. */
  public static double answerProbability(final SimParams p) {
    final double inTime =
        Gaussian.cdf(
            Math.log(p.get(Scalar.RESPONSE_WINDOW_MS) / p.get(Scalar.DEALER_DELAY_MEDIAN_MS))
                / p.get(Scalar.DEALER_DELAY_LOG_SD));
    return p.get(Scalar.RESPONSE_PROBABILITY) * inTime;
  }

  /**
   * Mean of the lowest of {@code n} independent half-spreads per unit median, each
   * {@code exp(logSd x Z)}: the integral of n phi(z) (1 - Phi(z))^(n-1) exp(logSd z) dz, by
   * Simpson's rule on [-10, 10].
   */
  public static double expectedMinOf(final int n, final double logSd) {
    final double h = 2.0 * Z_RANGE / INTERVALS;
    double sum = 0.0;
    for (int i = 0; i <= INTERVALS; i++) {
      final double z = -Z_RANGE + i * h;
      final double f = n * Gaussian.pdf(z) * Math.pow(1.0 - Gaussian.cdf(z), n - 1) * Math.exp(logSd * z);
      sum += (i == 0 || i == INTERVALS) ? f : ((i & 1) == 1 ? 4.0 * f : 2.0 * f);
    }
    return sum * h / 3.0;
  }

  /**
   * E[min] of design section 7: the mean of the lowest answering competitor half-spread per unit
   * median, over the number of competitors asked (dealers asked minus the desk, uniform) and the
   * number that answer (binomial), given that at least one answers.
   */
  public static double expectedBestPerMedian(final SimParams p) {
    final double answer = answerProbability(p);
    final double logSd = p.get(Scalar.COMPETITOR_LOG_SD);
    final int min = (int) p.get(Scalar.DEALERS_MIN) - 1;
    final int max = (int) p.get(Scalar.DEALERS_MAX) - 1;
    double weighted = 0.0;
    double anyAnswer = 0.0;
    for (int asked = min; asked <= max; asked++) {
      double binomial = Math.pow(1.0 - answer, asked);
      for (int n = 1; n <= asked; n++) {
        // P(n of asked answer), from P(n - 1 answer)
        binomial *= (asked - n + 1) / (double) n * answer / (1.0 - answer);
        weighted += binomial * expectedMinOf(n, logSd);
        anyAnswer += binomial;
      }
    }
    return weighted / anyAnswer;
  }
}
