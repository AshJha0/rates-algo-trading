package io.ratesalgo.backtest.util;

/**
 * SplitMix64 pseudo-random generator (Steele, Lea and Flood, 2014).
 *
 * <p>Chosen because it is a few lines, has no hidden state, and is reproduced bit for bit by
 * {@code research/ratesresearch/stats.py::SplitMix64}, so the block bootstrap draws the same
 * indices in Java and Python and the two sides can be compared exactly on a shared fixture
 * (API_SPEC.md Section 16.7). {@link #nextLong()}, {@link #nextDouble()} and {@link #nextInt(int)}
 * are pinned across languages; {@link #nextGaussian()} uses {@code Math.log} and {@code Math.cos}
 * and is only reproducible within the JVM.
 */
public final class SplitMix64 {

  private static final long GOLDEN = 0x9E3779B97F4A7C15L;
  private static final double TWO_PI = 2.0 * Math.PI;

  private long state;
  private double spareGaussian;
  private boolean hasSpare;

  public SplitMix64(final long seed) {
    this.state = seed;
  }

  /** A generator for one stream derived from a seed and a stream key, such as an RFQ id. */
  public static SplitMix64 forStream(final long seed, final long key) {
    return new SplitMix64(mix(seed ^ mix(key + GOLDEN)));
  }

  /** The SplitMix64 finaliser applied to one value. */
  public static long mix(final long value) {
    long z = value;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  public long nextLong() {
    state += GOLDEN;
    return mix(state);
  }

  /** Uniform on [0, 1) with 53 random bits. */
  public double nextDouble() {
    return (nextLong() >>> 11) * 0x1.0p-53;
  }

  /** Uniform integer on [0, bound): the top 63 bits modulo bound (bias below 2^-40 here). */
  public int nextInt(final int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    return (int) ((nextLong() >>> 1) % bound);
  }

  /** Standard normal by the Box-Muller transform. */
  public double nextGaussian() {
    if (hasSpare) {
      hasSpare = false;
      return spareGaussian;
    }
    final double u1 = 1.0 - nextDouble();
    final double u2 = nextDouble();
    final double radius = Math.sqrt(-2.0 * Math.log(u1));
    spareGaussian = radius * Math.sin(TWO_PI * u2);
    hasSpare = true;
    return radius * Math.cos(TWO_PI * u2);
  }

  /** Exponential with the given mean. */
  public double nextExponential(final double mean) {
    return -mean * Math.log(1.0 - nextDouble());
  }
}
