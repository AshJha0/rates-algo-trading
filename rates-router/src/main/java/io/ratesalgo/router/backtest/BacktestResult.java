package io.ratesalgo.router.backtest;

import java.util.Locale;

/**
 * Realised cost per strategy per day, in basis points of price, quantity-weighted over the
 * parents of each day. Strategy 0 is the one compared with the others.
 */
public final class BacktestResult {

  private final BacktestScenario scenario;
  private final String[] names;
  private final double[][] dayCost;

  BacktestResult(final BacktestScenario scenario, final String[] names, final double[][] dayCost) {
    this.scenario = scenario;
    this.names = names.clone();
    this.dayCost = new double[dayCost.length][];
    for (int i = 0; i < dayCost.length; i++) {
      this.dayCost[i] = dayCost[i].clone();
    }
  }

  /** Number of strategies. */
  public int strategies() {
    return names.length;
  }

  /** Strategy name. */
  public String name(final int s) {
    return names[s];
  }

  /** Daily costs of a strategy, bps (a copy). */
  public double[] dayCosts(final int s) {
    return dayCost[s].clone();
  }

  /** Mean daily cost, bps. */
  public double meanCost(final int s) {
    return BlockBootstrap.mean(dayCost[s]);
  }

  /** Daily saving of strategy 0 against strategy {@code s}: cost(s) minus cost(0), bps. */
  public double[] savings(final int s) {
    final double[] out = new double[dayCost[0].length];
    for (int d = 0; d < out.length; d++) {
      out[d] = dayCost[s][d] - dayCost[0][d];
    }
    return out;
  }

  /** Mean saving against strategy {@code s}, bps. */
  public double meanSaving(final int s) {
    return BlockBootstrap.mean(savings(s));
  }

  /** 95 percent block-bootstrap interval of the mean saving against strategy {@code s}. */
  public double[] savingInterval(final int s) {
    return BlockBootstrap.meanInterval(
        savings(s), scenario.blockLength(), scenario.resamples(), 0.95, scenario.seed() ^ s);
  }

  /** The result as text. */
  public String format() {
    final StringBuilder sb = new StringBuilder(2_048);
    sb.append(
        String.format(
            Locale.ROOT,
            "scenario %s: %d days x %d parents, %d to %d lots, urgency %.2f%n",
            scenario.name(),
            scenario.days(),
            scenario.parentsPerDay(),
            scenario.minLots(),
            scenario.maxLots(),
            scenario.urgency()));
    for (int s = 0; s < names.length; s++) {
      sb.append(
          String.format(
              Locale.ROOT, "  %-26s mean cost %8.4f bps of price%n", names[s], meanCost(s)));
    }
    for (int s = 1; s < names.length; s++) {
      final double[] ci = savingInterval(s);
      sb.append(
          String.format(
              Locale.ROOT,
              "  saving vs %-24s mean %7.4f bps, 95%% block-bootstrap interval [%.4f, %.4f]"
                  + " (block %d days, %d resamples)%n",
              names[s],
              meanSaving(s),
              ci[0],
              ci[1],
              scenario.blockLength(),
              scenario.resamples()));
    }
    return sb.toString();
  }
}
