package io.ratesalgo.strategy;

/**
 * The baseline hedge rule of the back-test strategies ({@code HedgingQuoter}): when the absolute
 * net DV01 exceeds the threshold, hedge all of it.
 */
public final class ThresholdHedgeBudget implements HedgeBudget {

  private final double thresholdDv01;

  public ThresholdHedgeBudget(final double thresholdDv01) {
    if (!(thresholdDv01 > 0)) {
      throw new IllegalArgumentException("hedge threshold must be positive: " + thresholdDv01);
    }
    this.thresholdDv01 = thresholdDv01;
  }

  @Override
  public double excessDv01(final RiskLadder ladder, final long nanosSinceClientFill) {
    final double net = ladder.net();
    return Math.abs(net) > thresholdDv01 ? net : 0.0;
  }

  public double thresholdDv01() {
    return thresholdDv01;
  }
}
