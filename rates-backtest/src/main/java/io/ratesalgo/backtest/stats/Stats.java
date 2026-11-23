package io.ratesalgo.backtest.stats;

import io.ratesalgo.common.random.SplitMix64;
import java.util.Arrays;

/**
 * Statistics for back-test results (API_SPEC.md Section 16.6), mirrored line for line by
 * {@code research/ratesresearch/stats.py} and cross-checked on a shared fixture
 * ({@code data/fixtures/backtest/stats_crosscheck.json}).
 *
 * <ul>
 *   <li>{@link #blockBootstrapMeanCi}: moving-block bootstrap of the mean over days. Blocks of
 *       {@code L} consecutive days are drawn with replacement until {@code n} values are
 *       collected; the percentile interval uses linear interpolation between order statistics
 *       (Hyndman and Fan type 7, numpy's default). Indices come from {@link SplitMix64#nextInt},
 *       which Python reproduces exactly.
 *   <li>{@link #welch}: Welch's unequal-variance t-test, two-sided, with the Welch-Satterthwaite
 *       degrees of freedom and the exact Student-t tail through the regularised incomplete beta.
 *   <li>{@link #pairedT} and {@link #pairedBlockBootstrapCi}: the test and interval for a
 *       common-random-number design, where treatment and baseline replay the same sessions with
 *       the same seeds. The per-session difference is the unit; Welch's test treats the two arms
 *       as independent, keeps the between-session variance that pairing removes, and so has
 *       almost no power on such a design.
 *   <li>{@link #holm}: Holm's step-down adjustment of a family of p-values.
 *   <li>{@link #benjaminiHochberg}: false-discovery-rate adjustment across experiments.
 *   <li>{@link #pnlRisk}: volatility, Sharpe ratio and maximum drawdown of a daily P&amp;L series.
 * </ul>
 */
public final class Stats {

  private Stats() {}

  /** A two-sided confidence interval and the point estimate. */
  public record Interval(double estimate, double lower, double upper, double level) {}

  /**
   * Risk figures of a daily P&amp;L series: sample volatility (denominator n - 1), the daily Sharpe
   * ratio mean over volatility with a zero risk-free rate, the same ratio times the square root of
   * {@link #TRADING_DAYS_PER_YEAR}, and the largest peak-to-trough fall of cumulative P&amp;L starting
   * from zero (a non-negative number, in the series' unit). Sharpe ratios are NaN when the
   * volatility is zero.
   */
  public record PnlRisk(
      int days, double mean, double volatility, double sharpe, double annualisedSharpe, double maxDrawdown) {}

  /** Trading days per year used to annualise a daily Sharpe ratio. */
  public static final int TRADING_DAYS_PER_YEAR = 252;

  /**
   * Result of a paired t-test: the mean of the per-session differences {@code a[i] - b[i]}, its
   * standard error, {@code t = mean / se} on {@code n - 1} degrees of freedom and the two-sided p.
   */
  public record Paired(int n, double meanDifference, double standardError, double t, double df, double p) {}

  /** Result of a Welch test. */
  public record Welch(double meanA, double meanB, double difference, double t, double df, double p) {}

  public static double mean(final double[] x) {
    requireNonEmpty(x);
    double s = 0;
    for (final double v : x) {
      s += v;
    }
    return s / x.length;
  }

  /** Sample variance with denominator n - 1. */
  public static double variance(final double[] x) {
    if (x.length < 2) {
      throw new IllegalArgumentException("variance needs at least two values, got " + x.length);
    }
    final double m = mean(x);
    double s = 0;
    for (final double v : x) {
      s += (v - m) * (v - m);
    }
    return s / (x.length - 1);
  }

  /** Volatility, Sharpe ratio and maximum drawdown of {@code daily}; needs at least two days. */
  public static PnlRisk pnlRisk(final double[] daily) {
    final double m = mean(daily);
    final double vol = Math.sqrt(variance(daily));
    final double sharpe = vol > 0 ? m / vol : Double.NaN;
    double cumulative = 0;
    double peak = 0;
    double drawdown = 0;
    for (final double v : daily) {
      cumulative += v;
      peak = Math.max(peak, cumulative);
      drawdown = Math.max(drawdown, peak - cumulative);
    }
    return new PnlRisk(daily.length, m, vol, sharpe, sharpe * Math.sqrt(TRADING_DAYS_PER_YEAR), drawdown);
  }

  /** Quantile by linear interpolation of order statistics (type 7). {@code sorted} must be sorted. */
  public static double quantile(final double[] sorted, final double q) {
    requireNonEmpty(sorted);
    if (!(q >= 0 && q <= 1)) {
      throw new IllegalArgumentException("quantile level outside [0, 1]: " + q);
    }
    final double h = (sorted.length - 1) * q;
    final int lo = (int) Math.floor(h);
    final int hi = Math.min(lo + 1, sorted.length - 1);
    return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo]);
  }

  /**
   * Moving-block bootstrap confidence interval for the mean.
   *
   * @param x one value per day, in date order
   * @param blockLength days per block, at least 1 and at most n
   * @param resamples number of bootstrap resamples
   * @param level two-sided coverage, for example 0.95
   * @param seed SplitMix64 seed
   */
  public static Interval blockBootstrapMeanCi(
      final double[] x, final int blockLength, final int resamples, final double level, final long seed) {
    requireNonEmpty(x);
    final int n = x.length;
    if (blockLength < 1 || blockLength > n) {
      throw new IllegalArgumentException("block length " + blockLength + " outside [1, " + n + "]");
    }
    if (resamples < 1 || !(level > 0 && level < 1)) {
      throw new IllegalArgumentException("invalid resamples or level");
    }
    final SplitMix64 rng = new SplitMix64(seed);
    final int starts = n - blockLength + 1;
    final double[] means = new double[resamples];
    for (int b = 0; b < resamples; b++) {
      double sum = 0;
      int filled = 0;
      while (filled < n) {
        final int start = rng.nextInt(starts);
        for (int j = 0; j < blockLength && filled < n; j++) {
          sum += x[start + j];
          filled++;
        }
      }
      means[b] = sum / n;
    }
    Arrays.sort(means);
    final double alpha = (1 - level) / 2;
    return new Interval(mean(x), quantile(means, alpha), quantile(means, 1 - alpha), level);
  }

  /** Welch's two-sided t-test of mean(a) against mean(b). */
  public static Welch welch(final double[] a, final double[] b) {
    final double ma = mean(a);
    final double mb = mean(b);
    final double va = variance(a) / a.length;
    final double vb = variance(b) / b.length;
    final double se2 = va + vb;
    if (!(se2 > 0)) {
      throw new IllegalArgumentException("both samples have zero variance; the test is undefined");
    }
    final double t = (ma - mb) / Math.sqrt(se2);
    final double df = se2 * se2 / (va * va / (a.length - 1) + vb * vb / (b.length - 1));
    return new Welch(ma, mb, ma - mb, t, df, studentTwoSidedP(t, df));
  }

  /** The per-session differences {@code a[i] - b[i]} of two arms run on the same sessions. */
  public static double[] pairedDifferences(final double[] a, final double[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException("paired samples differ in length: " + a.length + " and " + b.length);
    }
    final double[] d = new double[a.length];
    for (int i = 0; i < d.length; i++) {
      d[i] = a[i] - b[i];
    }
    return d;
  }

  /**
   * Paired two-sided t-test of mean(a - b) against zero. {@code a[i]} and {@code b[i]} must come
   * from the same session. Assumes the differences are independent across sessions; use {@link
   * #pairedBlockBootstrapCi} when they may not be.
   */
  public static Paired pairedT(final double[] a, final double[] b) {
    final double[] d = pairedDifferences(a, b);
    final double m = mean(d);
    final double se2 = variance(d) / d.length;
    if (!(se2 > 0)) {
      throw new IllegalArgumentException("the differences have zero variance; the test is undefined");
    }
    final double t = m / Math.sqrt(se2);
    final double df = d.length - 1.0;
    return new Paired(d.length, m, Math.sqrt(se2), t, df, studentTwoSidedP(t, df));
  }

  /** Moving-block bootstrap interval of the mean per-session difference {@code a[i] - b[i]}. */
  public static Interval pairedBlockBootstrapCi(
      final double[] a, final double[] b, final int blockLength, final int resamples, final double level,
      final long seed) {
    return blockBootstrapMeanCi(pairedDifferences(a, b), blockLength, resamples, level, seed);
  }

  /** Two-sided p-value of Student's t with {@code df} degrees of freedom: I_{df/(df+t^2)}(df/2, 1/2). */
  public static double studentTwoSidedP(final double t, final double df) {
    if (!(df > 0)) {
      throw new IllegalArgumentException("degrees of freedom must be positive: " + df);
    }
    final double x = df / (df + t * t);
    return regularizedIncompleteBeta(x, df / 2.0, 0.5);
  }

  /**
   * Holm step-down adjusted p-values, returned in the input order: sort ascending, multiply the
   * i-th smallest (0-based) by m - i, take the running maximum, cap at 1.
   */
  public static double[] holm(final double[] p) {
    final int m = p.length;
    final Integer[] order = new Integer[m];
    for (int i = 0; i < m; i++) {
      if (!(p[i] >= 0 && p[i] <= 1)) {
        throw new IllegalArgumentException("p-value outside [0, 1]: " + p[i]);
      }
      order[i] = i;
    }
    Arrays.sort(order, (i, j) -> {
      final int c = Double.compare(p[i], p[j]);
      return c != 0 ? c : Integer.compare(i, j);
    });
    final double[] adjusted = new double[m];
    double running = 0;
    for (int k = 0; k < m; k++) {
      final int i = order[k];
      running = Math.max(running, Math.min(1.0, (m - k) * p[i]));
      adjusted[i] = running;
    }
    return adjusted;
  }

  /**
   * Benjamini-Hochberg adjusted p-values, returned in the input order: sort ascending, multiply
   * the i-th smallest (1-based) by m / i, take the running minimum from the largest down, cap at
   * 1. Rejecting where the adjusted value is at most q keeps the expected share of false
   * discoveries at or below q for independent or positively dependent tests. Use it across the
   * experiments of a ledger; {@link #holm} stays the rule within one pre-registered family.
   */
  public static double[] benjaminiHochberg(final double[] p) {
    final int m = p.length;
    final Integer[] order = new Integer[m];
    for (int i = 0; i < m; i++) {
      if (!(p[i] >= 0 && p[i] <= 1)) {
        throw new IllegalArgumentException("p-value outside [0, 1]: " + p[i]);
      }
      order[i] = i;
    }
    Arrays.sort(order, (i, j) -> {
      final int c = Double.compare(p[i], p[j]);
      return c != 0 ? c : Integer.compare(i, j);
    });
    final double[] adjusted = new double[m];
    double running = 1.0;
    for (int k = m - 1; k >= 0; k--) {
      final int i = order[k];
      running = Math.min(running, p[i] * m / (k + 1));
      adjusted[i] = running;
    }
    return adjusted;
  }

  /** Regularised incomplete beta I_x(a, b) by Lentz's continued fraction (Numerical Recipes 6.4). */
  public static double regularizedIncompleteBeta(final double x, final double a, final double b) {
    if (!(x >= 0 && x <= 1) || !(a > 0) || !(b > 0)) {
      throw new IllegalArgumentException("incomplete beta outside its domain: x=" + x + " a=" + a + " b=" + b);
    }
    if (x == 0 || x == 1) {
      return x;
    }
    final double lnFront = logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log1p(-x);
    final double front = Math.exp(lnFront);
    if (x < (a + 1) / (a + b + 2)) {
      return front * betaContinuedFraction(x, a, b) / a;
    }
    return 1.0 - front * betaContinuedFraction(1 - x, b, a) / b;
  }

  private static double betaContinuedFraction(final double x, final double a, final double b) {
    final double tiny = 1e-300;
    final double eps = 1e-15;
    double c = 1;
    double d = 1 - (a + b) * x / (a + 1);
    if (Math.abs(d) < tiny) {
      d = tiny;
    }
    d = 1 / d;
    double h = d;
    for (int m = 1; m <= 1000; m++) {
      final int m2 = 2 * m;
      double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
      d = 1 + aa * d;
      d = Math.abs(d) < tiny ? tiny : d;
      c = 1 + aa / c;
      c = Math.abs(c) < tiny ? tiny : c;
      d = 1 / d;
      h *= d * c;
      aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
      d = 1 + aa * d;
      d = Math.abs(d) < tiny ? tiny : d;
      c = 1 + aa / c;
      c = Math.abs(c) < tiny ? tiny : c;
      d = 1 / d;
      final double delta = d * c;
      h *= delta;
      if (Math.abs(delta - 1) < eps) {
        return h;
      }
    }
    throw new IllegalStateException("incomplete beta did not converge: x=" + x + " a=" + a + " b=" + b);
  }

  /** ln Gamma(x) for x &gt; 0 by the Lanczos approximation (g = 7, n = 9), relative error near 1e-15. */
  public static double logGamma(final double x) {
    if (!(x > 0)) {
      throw new IllegalArgumentException("logGamma needs a positive argument: " + x);
    }
    if (x < 0.5) {
      return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
    }
    final double[] g = {
      0.9999999999998099, 676.5203681218851, -1259.1392167224028, 771.3234287776531,
      -176.6150291621406, 12.507343278686905, -0.13857109526572012, 9.984369578019572e-6,
      1.5056327351493116e-7
    };
    final double z = x - 1;
    double sum = g[0];
    for (int i = 1; i < g.length; i++) {
      sum += g[i] / (z + i);
    }
    final double t = z + 7.5;
    return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(sum);
  }

  private static void requireNonEmpty(final double[] x) {
    if (x.length == 0) {
      throw new IllegalArgumentException("empty sample");
    }
  }
}
