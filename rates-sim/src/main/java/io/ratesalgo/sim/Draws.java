package io.ratesalgo.sim;

import io.ratesalgo.common.random.SplitMix64;

/**
 * How the model turns 64-bit draws into random quantities (design section 2). Every quantity uses
 * a fixed number of draws: one for a uniform, one for a normal, one for a Poisson count. Nothing
 * here rejects and redraws, so the position in a stream never depends on the values drawn.
 */
public final class Draws {

  /** Largest Poisson mean the inversion is used for; exp(-mean) is still far from underflow. */
  public static final double MAX_POISSON_MEAN = 500.0;

  private Draws() {}

  /** Uniform strictly inside (0, 1): the top 53 bits plus one half, times 2^-53. */
  public static double uniform(final SplitMix64 stream) {
    return ((stream.nextLong() >>> 11) + 0.5) * 0x1.0p-53;
  }

  /** Standard normal from one draw, by the inverse distribution function. */
  public static double normal(final SplitMix64 stream) {
    return Gaussian.inverse(uniform(stream));
  }

  /** Poisson count from one draw, by inversion of the cumulative probabilities. */
  public static int poisson(final double mean, final SplitMix64 stream) {
    return poissonFromUniform(mean, uniform(stream));
  }

  /** The Poisson quantile of {@code u}: the smallest k whose cumulative probability reaches u. */
  public static int poissonFromUniform(final double mean, final double u) {
    if (!(mean >= 0.0 && mean <= MAX_POISSON_MEAN)) {
      throw new IllegalArgumentException("Poisson mean outside [0, " + MAX_POISSON_MEAN + "]: " + mean);
    }
    double p = Math.exp(-mean);
    double cumulative = p;
    int k = 0;
    // the cap only matters when rounding leaves the cumulative sum a hair under u
    final int cap = (int) (mean + 40.0 * Math.sqrt(mean + 1.0)) + 40;
    while (u > cumulative && k < cap) {
      k++;
      p *= mean / k;
      cumulative += p;
    }
    return k;
  }

  /** Uniform integer on [min, max] from one draw. */
  public static int between(final int min, final int max, final SplitMix64 stream) {
    return min + (int) (uniform(stream) * (max - min + 1));
  }
}
