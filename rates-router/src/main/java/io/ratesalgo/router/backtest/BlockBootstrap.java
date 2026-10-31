package io.ratesalgo.router.backtest;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Moving-block bootstrap of a mean (Kuensch, Annals of Statistics 17(3), 1989). Days are resampled
 * in contiguous blocks so that dependence between neighbouring days, if any, is kept inside a
 * block; each resample concatenates {@code ceil(n / b)} blocks whose starts are uniform on {@code
 * [0, n - b]} and truncates to {@code n}. The interval is the percentile interval of the
 * resampled means. Seeded, so a reported interval is reproducible.
 */
public final class BlockBootstrap {

  private BlockBootstrap() {}

  /**
   * Percentile interval of the mean.
   *
   * @param x observations in time order, at least 2
   * @param blockLength block length in observations, in [1, x.length]
   * @param resamples number of resamples, at least 100
   * @param level coverage, for example 0.95
   * @param seed seed of the resampling stream
   * @return {lower, upper}
   */
  public static double[] meanInterval(
      final double[] x, final int blockLength, final int resamples, final double level,
      final long seed) {
    final int n = x.length;
    if (n < 2 || blockLength < 1 || blockLength > n || resamples < 100
        || !(level > 0.0 && level < 1.0)) {
      throw new IllegalArgumentException(
          "bootstrap inputs: n=" + n + " block=" + blockLength + " resamples=" + resamples
              + " level=" + level);
    }
    final SplittableRandom random = new SplittableRandom(seed);
    final double[] means = new double[resamples];
    final int starts = n - blockLength + 1;
    for (int r = 0; r < resamples; r++) {
      double sum = 0.0;
      int taken = 0;
      while (taken < n) {
        final int start = random.nextInt(starts);
        for (int j = 0; j < blockLength && taken < n; j++, taken++) {
          sum += x[start + j];
        }
      }
      means[r] = sum / n;
    }
    Arrays.sort(means);
    final double alpha = (1.0 - level) / 2.0;
    final int lo = (int) Math.floor(alpha * (resamples - 1));
    final int hi = (int) Math.ceil((1.0 - alpha) * (resamples - 1));
    return new double[] {means[lo], means[hi]};
  }

  /** Arithmetic mean. */
  public static double mean(final double[] x) {
    double sum = 0.0;
    for (final double v : x) {
      sum += v;
    }
    return sum / x.length;
  }
}
