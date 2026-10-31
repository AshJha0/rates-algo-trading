package io.ratesalgo.venues.sim;

import java.util.SplittableRandom;

/**
 * The simulators' only source of randomness: a {@link SplittableRandom} from a fixed seed, so a
 * run with the same seed, inputs and clock reproduces every draw.
 */
public final class SimRng {

  private final SplittableRandom random;

  /** Creates a source from a seed. */
  public SimRng(final long seed) {
    this.random = new SplittableRandom(seed);
  }

  /** Uniform in [0, 1). */
  public double uniform() {
    return random.nextDouble();
  }

  /** Uniform integer in [min, max]. */
  public int between(final int min, final int max) {
    return min + random.nextInt(max - min + 1);
  }

  /** True with probability p. */
  public boolean chance(final double p) {
    return random.nextDouble() < p;
  }

  /** Standard normal draw. */
  public double gaussian() {
    return random.nextGaussian();
  }

  /** Exponential draw with the given mean. */
  public double exponential(final double mean) {
    return random.nextExponential() * mean;
  }

  /** Lognormal draw with the given median and log-space sigma. */
  public double lognormal(final double median, final double sigma) {
    return median * Math.exp(sigma * random.nextGaussian());
  }

  /** Index drawn from a discrete distribution given by weights summing to about 1. */
  public int discrete(final double[] weights) {
    double u = random.nextDouble();
    double total = 0;
    for (final double w : weights) {
      total += w;
    }
    u *= total;
    for (int i = 0; i < weights.length; i++) {
      u -= weights[i];
      if (u < 0) {
        return i;
      }
    }
    return weights.length - 1;
  }
}
