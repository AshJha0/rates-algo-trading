package io.ratesalgo.router;

import java.util.SplittableRandom;

/** Uniform random numbers for anti-gaming jitter, injected so tests are deterministic. */
@FunctionalInterface
public interface RandomSource {

  /** A uniform draw in [0, 1). Must not allocate. */
  double nextDouble();

  /** A seeded source backed by {@link SplittableRandom}. */
  static RandomSource seeded(final long seed) {
    final SplittableRandom random = new SplittableRandom(seed);
    return random::nextDouble;
  }
}
