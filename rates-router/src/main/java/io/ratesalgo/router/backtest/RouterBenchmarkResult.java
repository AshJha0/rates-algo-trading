package io.ratesalgo.router.backtest;

import static io.ratesalgo.router.backtest.RouterBenchmark.one;

import java.util.Locale;

/**
 * Per-parent results of the router benchmark: implementation shortfall in ticks and in basis
 * points of price, time to completion and fill rate, per strategy, with the checks on limits.
 * Strategy 0 is the router; every comparison is a paired one against it.
 */
public final class RouterBenchmarkResult {

  private final RouterBenchmarkScenario scenario;
  private final String[] names;
  private final double[][] ticks;
  private final double[][] bps;
  private final double[][] seconds;
  private final double[][] fill;
  private final int[] day;
  private final long[] violations;
  private final long[] maxPast;
  private final long[] selfCrosses;
  private final long[] orders;

  RouterBenchmarkResult(
      final RouterBenchmarkScenario scenario, final String[] names, final double[][] ticks,
      final double[][] bps, final double[][] seconds, final double[][] fill, final int[] day,
      final long[] violations, final long[] maxPast, final long[] selfCrosses, final long[] orders) {
    this.scenario = scenario;
    this.names = names.clone();
    this.ticks = deep(ticks);
    this.bps = deep(bps);
    this.seconds = deep(seconds);
    this.fill = deep(fill);
    this.day = day.clone();
    this.violations = violations.clone();
    this.maxPast = maxPast.clone();
    this.selfCrosses = selfCrosses.clone();
    this.orders = orders.clone();
  }

  private static double[][] deep(final double[][] x) {
    final double[][] out = new double[x.length][];
    for (int i = 0; i < x.length; i++) {
      out[i] = x[i].clone();
    }
    return out;
  }

  /** Number of strategies. */
  public int strategies() {
    return names.length;
  }

  /** Strategy name. */
  public String name(final int s) {
    return names[s];
  }

  /** Shortfall per parent, ticks (a copy). */
  public double[] ticks(final int s) {
    return ticks[s].clone();
  }

  /** Mean shortfall, ticks. */
  public double meanTicks(final int s) {
    return PairedStats.mean(ticks[s]);
  }

  /** Standard deviation of the shortfall over parents, ticks. */
  public double sdTicks(final int s) {
    return PairedStats.sd(ticks[s]);
  }

  /** Mean plus lambda standard deviations, ticks. */
  public double riskAdjustedTicks(final int s) {
    return PairedStats.riskAdjusted(ticks[s], scenario.lambda());
  }

  /** Mean fill rate. */
  public double fillRate(final int s) {
    return PairedStats.mean(fill[s]);
  }

  /** Mean time to completion, seconds. */
  public double meanSeconds(final int s) {
    return PairedStats.mean(seconds[s]);
  }

  /** Fills outside their order's limit. */
  public long violations(final int s) {
    return violations[s];
  }

  /** Most ticks past the far touch an aggressive order was priced at when sent. */
  public long maxTicksPastTouch(final int s) {
    return maxPast[s];
  }

  /** Aggressive orders that would have traded against our own resting order. */
  public long selfCrosses(final int s) {
    return selfCrosses[s];
  }

  /** Paired t of the baseline's shortfall minus the router's, ticks: positive favours the router. */
  public double[] pairedT(final int s) {
    return PairedStats.pairedT(ticks[s], ticks[0], 0.95);
  }

  /** Day-block bootstrap interval of the baseline's risk-adjusted cost minus the router's, ticks. */
  public double[] riskAdjustedInterval(final int s) {
    return PairedStats.pairedBlockBootstrap(
        ticks[s], ticks[0], day, scenario.lambda(), scenario.blockLength(), scenario.resamples(),
        0.95, scenario.seed() ^ s);
  }

  /** True if the router's risk-adjusted cost is no worse than strategy s's beyond the tolerance. */
  public boolean withinTolerance(final int s) {
    return riskAdjustedTicks(0) - riskAdjustedTicks(s) <= scenario.toleranceTicks();
  }

  /** Day-block bootstrap interval of the mean difference, ticks. */
  public double[] meanInterval(final int s) {
    return PairedStats.pairedBlockBootstrap(
        ticks[s], ticks[0], day, 0.0, scenario.blockLength(), scenario.resamples(), 0.95,
        scenario.seed() ^ (s * 31L));
  }

  private double tickBps() {
    // one tick in basis points of price at this scenario's price level
    return scenario.market().tickMantissa() / 1e9 / (scenario.market().startMidTicks()
        * scenario.market().tickMantissa() / 1e9) * 1e4;
  }

  /** The report body: tables and plain conclusions. */
  public String markdown(final String command, final String scenarioCommit) {
    final double toBps = tickBps();
    final StringBuilder sb = new StringBuilder(8_192);
    final double lambda = scenario.lambda();
    sb.append("## Results\n\n");
    sb.append(String.format(Locale.ROOT,
        "Scenario `%s`, %d days x %d parents = %d parents per strategy; parameters fixed in commit `%s`"
            + " before the comparison was run. Shortfall is against the consolidated mid at arrival,"
            + " fees included; positive is a cost. One tick is %s bp of price here. Risk-adjusted cost"
            + " is mean + %s x sd over parents.%n%n",
        scenario.name(), scenario.days(), scenario.parentsPerDay(), ticks[0].length, scenarioCommit,
        String.format(Locale.ROOT, "%.2f", toBps), one(lambda)));
    sb.append("| strategy | mean (ticks) | sd (ticks) | risk-adj. (ticks) | mean (bp) | sd (bp) |"
        + " risk-adj. (bp) | fill rate | mean time (s) | orders | max ticks past touch |\n");
    sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    for (int s = 0; s < names.length; s++) {
      final double mb = PairedStats.mean(bps[s]);
      final double sdb = PairedStats.sd(bps[s]);
      sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %d | %d |%n",
          names[s], one(meanTicks(s)), one(sdTicks(s)), one(riskAdjustedTicks(s)), one(mb), one(sdb),
          one(mb + lambda * sdb), String.format(Locale.ROOT, "%.3f", fillRate(s)), one(meanSeconds(s)),
          orders[s], maxPast[s]));
    }
    sb.append("\n## Paired comparisons with the router\n\n");
    sb.append("Differences are baseline minus router, in ticks (bp in brackets): positive means the router"
        + " was cheaper. Paired t over parents; bootstrap over days in blocks of "
        + scenario.blockLength() + " (" + scenario.resamples() + " resamples), 95%.\n\n");
    sb.append("| baseline | mean diff | paired-t 95% | p | block-bootstrap 95% | risk-adj. diff |"
        + " risk-adj. 95% |\n");
    sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
    for (int s = 1; s < names.length; s++) {
      final double[] t = pairedT(s);
      final double[] mi = meanInterval(s);
      final double ra = riskAdjustedTicks(s) - riskAdjustedTicks(0);
      final double[] ri = riskAdjustedInterval(s);
      sb.append(String.format(Locale.ROOT, "| %s | %s (%s) | [%s, %s] | %s | [%s, %s] | %s (%s) | [%s, %s] |%n",
          names[s], one(t[0]), one(t[0] * toBps), one(t[4]), one(t[5]),
          String.format(Locale.ROOT, "%.3f", t[3]), one(mi[0]), one(mi[1]), one(ra), one(ra * toBps),
          one(ri[0]), one(ri[1])));
    }
    sb.append("\n## What this says\n\n");
    for (int s = 1; s <= Math.min(RouterBenchmark.REAL_BASELINES, names.length - 1); s++) {
      final double ra = riskAdjustedTicks(s) - riskAdjustedTicks(0);
      final double[] ri = riskAdjustedInterval(s);
      final String verdict;
      if (ri[0] > 0.0) {
        verdict = "the router's risk-adjusted cost is lower, and the interval excludes zero";
      } else if (ri[1] < 0.0) {
        verdict = "the router's risk-adjusted cost is HIGHER: it does not beat this baseline";
      } else {
        verdict = "no difference the interval can separate from zero: the router does not beat this baseline";
      }
      sb.append(String.format(Locale.ROOT, "- Against %s: %s (difference %s ticks, 95%% [%s, %s]).%n",
          names[s], verdict, one(ra), one(ri[0]), one(ri[1])));
    }
    sb.append(String.format(Locale.ROOT,
        "%n## The check declared before the run%n%nThe router's risk-adjusted cost was to be no worse"
            + " than each real baseline's by more than %s ticks.%n%n",
        String.format(Locale.ROOT, "%.2f", scenario.toleranceTicks())));
    for (int s = 1; s <= Math.min(RouterBenchmark.REAL_BASELINES, names.length - 1); s++) {
      final double worse = riskAdjustedTicks(0) - riskAdjustedTicks(s);
      sb.append(String.format(Locale.ROOT, "- %s: router worse by %s ticks: %s.%n", names[s],
          String.format(Locale.ROOT, "%.2f", worse), withinTolerance(s) ? "holds" : "FAILS"));
    }
    sb.append("\nThe strawmen (single shot on one venue and the even split, both with the old 8-tick collar)"
        + " are kept only to show what the earlier 2.94 bp claim was measured against.\n");
    sb.append("\nReproduce with:\n\n```\n").append(command).append("\n```\n");
    return sb.toString();
  }
}
