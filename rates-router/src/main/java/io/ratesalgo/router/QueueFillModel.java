package io.ratesalgo.router;

import java.util.Arrays;

/**
 * Probability that a resting order fills completely within a horizon, given its queue position.
 *
 * <p>A FIFO queue holds {@code qAhead} units ahead of our order of {@code size} units. Unit
 * market orders arrive as a Poisson process of rate {@code mu}; each unit ahead cancels at rate
 * {@code theta}; our units never cancel. The fill time is {@code sum_{k=1}^{qAhead} Exp(mu +
 * theta k) + sum_{i=1}^{size} Exp(mu)}, the pure-death queue of Cont, Stoikov and Talreja
 * (Operations Research 58(3), 2010). The probability is evaluated by uniformisation exactly as
 * pinned in the owner's earlier execution library (AshJha0/electronic-trading, API_SPEC.md
 * Section 13.1, release 2.0.0); this version keeps the algorithm and its tolerance, computes the
 * Poisson weights on the fly instead of in an array, and reuses one state vector so that it does
 * not allocate after construction.
 *
 * <p>Single-threaded: one instance per router.
 */
public final class QueueFillModel {

  /** Upper bound on {@code qAhead + size}. */
  public static final int MAX_QUEUE_UNITS = 5_000;

  /** Upper bound on uniformisation steps. */
  public static final int MAX_UNIFORMISATION_STEPS = 200_000;

  private final double[] state = new double[MAX_QUEUE_UNITS];

  /**
   * Complete-fill probability.
   *
   * @param qAhead units ahead, {@code >= 0}
   * @param size our units, {@code >= 1}, with {@code qAhead + size <= MAX_QUEUE_UNITS}
   * @param mu arrival rate of unit market orders per second, {@code > 0}
   * @param theta cancellation rate per unit ahead per second, {@code >= 0}
   * @param horizonSeconds time allowed, {@code >= 0}
   * @return the probability in [0, 1]
   * @throws IllegalArgumentException on invalid input, with the input in the message
   */
  public double probability(
      final int qAhead,
      final int size,
      final double mu,
      final double theta,
      final double horizonSeconds) {
    if (qAhead < 0 || size < 1 || qAhead > MAX_QUEUE_UNITS - size) {
      throw new IllegalArgumentException(
          "queue units out of range: qAhead=" + qAhead + " size=" + size);
    }
    if (!(mu > 0.0) || !Double.isFinite(mu) || !(theta >= 0.0) || !Double.isFinite(theta)) {
      throw new IllegalArgumentException("rates out of range: mu=" + mu + " theta=" + theta);
    }
    if (!(horizonSeconds >= 0.0) || !Double.isFinite(horizonSeconds)) {
      throw new IllegalArgumentException("horizon out of range: " + horizonSeconds);
    }
    final double lambda = mu + theta * qAhead;
    final double bigL = lambda * horizonSeconds;
    if (bigL <= 0.0) {
      return 0.0;
    }
    final double floorL = Math.floor(bigL);
    final double half = Math.ceil(10.0 * StrictMath.sqrt(bigL) + 10.0);
    if (floorL + half > MAX_UNIFORMISATION_STEPS) {
      throw new IllegalArgumentException(
          "horizon too long: (mu + theta*qAhead)*horizon = " + bigL);
    }
    final int m = (int) floorL;
    final int kLo = Math.max(0, m - (int) half);
    final int kHi = m + (int) half;

    // Poisson weight at kLo relative to the weight at m, which is 1.
    double weight = 1.0;
    for (int k = m; k > kLo; k--) {
      weight = weight * k / bigL;
    }

    final int stages = qAhead + size;
    Arrays.fill(state, 0, stages, 0.0);
    state[0] = 1.0;
    double absorbed = 0.0;
    double acc = 0.0;
    double totalWeight = 0.0;
    for (int k = 0; k <= kHi; k++) {
      if (k > 0) {
        double prevMove = 0.0;
        for (int s = 0; s < stages; s++) {
          final double rate = s < qAhead ? mu + theta * (qAhead - s) : mu;
          final double go = rate / lambda;
          final double move = state[s] * go;
          state[s] = state[s] * (1.0 - go) + prevMove;
          prevMove = move;
        }
        absorbed = absorbed + prevMove;
      }
      if (k >= kLo) {
        if (k > kLo) {
          weight = weight * bigL / k;
        }
        acc += weight * absorbed;
        totalWeight += weight;
      }
    }
    return Math.min(1.0, Math.max(0.0, acc / totalWeight));
  }
}
