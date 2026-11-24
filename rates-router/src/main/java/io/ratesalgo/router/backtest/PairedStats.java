package io.ratesalgo.router.backtest;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Paired statistics for strategies run on common random numbers. The router module does not
 * depend on rates-backtest (where {@code Stats.pairedT} lives), so the few routines the router
 * benchmark needs are here, with their own tests.
 */
public final class PairedStats {

  private PairedStats() {}

  /** Arithmetic mean. */
  public static double mean(final double[] x) {
    double sum = 0.0;
    for (final double v : x) {
      sum += v;
    }
    return sum / x.length;
  }

  /** Sample standard deviation, denominator n - 1. */
  public static double sd(final double[] x) {
    if (x.length < 2) {
      return 0.0;
    }
    final double m = mean(x);
    double ss = 0.0;
    for (final double v : x) {
      ss += (v - m) * (v - m);
    }
    return StrictMath.sqrt(ss / (x.length - 1));
  }

  /** Mean plus {@code lambda} standard deviations: a cost charged for its spread. */
  public static double riskAdjusted(final double[] x, final double lambda) {
    return mean(x) + lambda * sd(x);
  }

  /**
   * A paired t interval of the mean of {@code a[i] - b[i]}.
   *
   * @return {mean, standard error, t, two-sided p, lower, upper}
   */
  public static double[] pairedT(final double[] a, final double[] b, final double level) {
    if (a.length != b.length || a.length < 2) {
      throw new IllegalArgumentException("paired samples of equal length >= 2");
    }
    final double[] d = new double[a.length];
    for (int i = 0; i < d.length; i++) {
      d[i] = a[i] - b[i];
    }
    final double m = mean(d);
    final double se = sd(d) / StrictMath.sqrt(d.length);
    final double df = d.length - 1.0;
    final double t = se > 0.0 ? m / se : (m == 0.0 ? 0.0 : Math.copySign(Double.POSITIVE_INFINITY, m));
    final double p = se > 0.0 ? studentTwoSidedP(t, df) : (m == 0.0 ? 1.0 : 0.0);
    final double q = studentQuantile(0.5 + level / 2.0, df);
    return new double[] {m, se, t, p, m - q * se, m + q * se};
  }

  /**
   * Moving-block bootstrap percentile interval of {@code f(a) - f(b)} where {@code f} is the mean
   * plus {@code lambda} standard deviations over the parents of the resampled days; {@code
   * day[i]} is the day of parent {@code i}, days numbered from 0 and in order. With {@code lambda
   * = 0} it is the interval of the mean difference.
   *
   * @return {lower, upper}
   */
  public static double[] pairedBlockBootstrap(
      final double[] a, final double[] b, final int[] day, final double lambda,
      final int blockLength, final int resamples, final double level, final long seed) {
    final int days = day[day.length - 1] + 1;
    if (a.length != b.length || a.length != day.length || blockLength < 1 || blockLength > days
        || resamples < 100) {
      throw new IllegalArgumentException("bootstrap inputs");
    }
    final int[] first = new int[days + 1];
    Arrays.fill(first, -1);
    for (int i = day.length - 1; i >= 0; i--) {
      first[day[i]] = i;
    }
    first[days] = day.length;
    for (int d = days - 1; d >= 0; d--) {
      if (first[d] < 0) {
        first[d] = first[d + 1];
      }
    }
    final SplittableRandom random = new SplittableRandom(seed);
    final double[] stat = new double[resamples];
    final double[] ra = new double[a.length * 2];
    final double[] rb = new double[a.length * 2];
    for (int r = 0; r < resamples; r++) {
      int taken = 0;
      int m = 0;
      while (taken < days) {
        final int start = random.nextInt(days - blockLength + 1);
        for (int j = 0; j < blockLength && taken < days; j++, taken++) {
          final int d = start + j;
          for (int i = first[d]; i < first[d + 1]; i++) {
            if (m == ra.length) {
              break;
            }
            ra[m] = a[i];
            rb[m] = b[i];
            m++;
          }
        }
      }
      final double[] xa = Arrays.copyOf(ra, m);
      final double[] xb = Arrays.copyOf(rb, m);
      stat[r] = riskAdjusted(xa, lambda) - riskAdjusted(xb, lambda);
    }
    Arrays.sort(stat);
    final double alpha = (1.0 - level) / 2.0;
    final int lo = (int) Math.floor(alpha * (resamples - 1));
    final int hi = (int) Math.ceil((1.0 - alpha) * (resamples - 1));
    return new double[] {stat[lo], stat[hi]};
  }

  /** Two-sided p-value of Student's t: {@code I_{df/(df+t^2)}(df/2, 1/2)}. */
  public static double studentTwoSidedP(final double t, final double df) {
    if (!Double.isFinite(t)) {
      return 0.0;
    }
    return regularizedIncompleteBeta(df / (df + t * t), df / 2.0, 0.5);
  }

  /** Quantile of Student's t by bisection on its distribution function. */
  public static double studentQuantile(final double p, final double df) {
    if (!(p > 0.0 && p < 1.0) || !(df > 0.0)) {
      throw new IllegalArgumentException("p in (0, 1) and df > 0");
    }
    if (p == 0.5) {
      return 0.0;
    }
    final double target = Math.abs(p - 0.5) * 2.0;
    double lo = 0.0;
    double hi = 1.0;
    while (1.0 - studentTwoSidedP(hi, df) < target) {
      hi *= 2.0;
    }
    for (int i = 0; i < 200; i++) {
      final double m = 0.5 * (lo + hi);
      if (1.0 - studentTwoSidedP(m, df) < target) {
        lo = m;
      } else {
        hi = m;
      }
    }
    final double q = 0.5 * (lo + hi);
    return p > 0.5 ? q : -q;
  }

  /** Regularised incomplete beta by Lentz's continued fraction (Numerical Recipes 6.4). */
  static double regularizedIncompleteBeta(final double x, final double a, final double b) {
    if (x <= 0.0) {
      return 0.0;
    }
    if (x >= 1.0) {
      return 1.0;
    }
    final double lnFront =
        logGamma(a + b) - logGamma(a) - logGamma(b) + a * StrictMath.log(x) + b * StrictMath.log(1.0 - x);
    if (x < (a + 1.0) / (a + b + 2.0)) {
      return StrictMath.exp(lnFront) * betaFraction(x, a, b) / a;
    }
    return 1.0 - StrictMath.exp(lnFront) * betaFraction(1.0 - x, b, a) / b;
  }

  private static double betaFraction(final double x, final double a, final double b) {
    final double tiny = 1e-300;
    double c = 1.0;
    double d = 1.0 - (a + b) * x / (a + 1.0);
    d = Math.abs(d) < tiny ? tiny : d;
    d = 1.0 / d;
    double h = d;
    for (int m = 1; m <= 500; m++) {
      final int m2 = 2 * m;
      double aa = m * (b - m) * x / ((a + m2 - 1.0) * (a + m2));
      d = 1.0 + aa * d;
      d = Math.abs(d) < tiny ? tiny : d;
      c = 1.0 + aa / c;
      c = Math.abs(c) < tiny ? tiny : c;
      d = 1.0 / d;
      h *= d * c;
      aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1.0));
      d = 1.0 + aa * d;
      d = Math.abs(d) < tiny ? tiny : d;
      c = 1.0 + aa / c;
      c = Math.abs(c) < tiny ? tiny : c;
      d = 1.0 / d;
      final double del = d * c;
      h *= del;
      if (Math.abs(del - 1.0) < 1e-15) {
        break;
      }
    }
    return h;
  }

  /** ln Gamma(x) for x &gt; 0 by the Lanczos approximation (g = 7, n = 9). */
  static double logGamma(final double x) {
    final double[] g = {
      0.9999999999998099, 676.5203681218851, -1259.1392167224028, 771.3234287776531,
      -176.6150291621406, 12.507343278686905, -0.13857109526572012, 9.984369578019572e-6,
      1.5056327351493116e-7
    };
    if (x < 0.5) {
      return StrictMath.log(Math.PI / Math.abs(StrictMath.sin(Math.PI * x))) - logGamma(1.0 - x);
    }
    final double z = x - 1.0;
    double sum = g[0];
    for (int i = 1; i < 9; i++) {
      sum += g[i] / (z + i);
    }
    final double t = z + 7.5;
    return 0.5 * StrictMath.log(2.0 * Math.PI) + (z + 0.5) * StrictMath.log(t) - t + StrictMath.log(sum);
  }
}
