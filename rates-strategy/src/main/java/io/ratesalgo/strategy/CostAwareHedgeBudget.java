package io.ratesalgo.strategy;

/**
 * Audit item 10 (DR-V1H-02): a cost-aware hedge trigger in place of a fixed DV01 threshold. Hedge
 * all of the net DV01 when the expected cost of holding it over the next interval exceeds the
 * expected cost of hedging it now.
 *
 * <pre>
 *   X        net DV01 of the ladder, major currency per bp; a = |X|
 *   hold(a)  = gamma / 2 * sigma^2 * a^2 * horizon                      variance charge, currency
 *   hedge(a) = (h + fee) * a + t * sum_k k * min(D, a - k * D)+          cost of a market order walking depth
 *   hedge all of X when hold(a) &gt; hedge(a), or when a &gt; cap
 * </pre>
 *
 * where {@code h} is the live half-spread of the hedge future in bp, {@code t} its tick in bp,
 * {@code D} the DV01 displayed at the touch on the side a hedge would take, and the walk goes at
 * most {@code maxLevels} ticks beyond the touch, as the back-tester's depth-walk fill does
 * ({@code HedgeCostModel}, audit fix package D). The market inputs come from {@link
 * #observeMarket}; until the first observation the declared fallbacks are used. {@code gamma} is
 * the risk aversion of the risk-adjusted metric (per currency unit), {@code sigma} the rate
 * volatility in bp per sqrt(second), {@code horizon} the interval in seconds, {@code cap} a hard
 * bound in DV01 that keeps the rule inside the risk limit. Allocation-free.
 */
public final class CostAwareHedgeBudget implements HedgeBudget {

  private final double gamma;
  private final double sigmaBpPerSqrtSecond;
  private final double horizonSeconds;
  private final double feeBp;
  private final int maxLevels;
  private final double capDv01;
  private double halfSpreadBp;
  private double tickBp;
  private double depthDv01;

  /**
   * @param fallbackHalfSpreadBp half-spread in bp before the book has been seen
   * @param fallbackTickBp tick in bp before the book has been seen
   * @param fallbackDepthDv01 displayed DV01 at the touch before the book has been seen
   */
  public CostAwareHedgeBudget(
      final double gamma,
      final double sigmaBpPerSqrtSecond,
      final double horizonSeconds,
      final double feeBp,
      final int maxLevels,
      final double capDv01,
      final double fallbackHalfSpreadBp,
      final double fallbackTickBp,
      final double fallbackDepthDv01) {
    if (!(gamma > 0) || !(sigmaBpPerSqrtSecond > 0) || !(horizonSeconds > 0) || !(feeBp >= 0) || maxLevels < 1
        || !(capDv01 > 0) || !(fallbackHalfSpreadBp >= 0) || !(fallbackTickBp >= 0) || !(fallbackDepthDv01 > 0)) {
      throw new IllegalArgumentException("cost-aware hedge budget needs positive gamma, sigma, horizon, cap, depth and at least one level");
    }
    this.gamma = gamma;
    this.sigmaBpPerSqrtSecond = sigmaBpPerSqrtSecond;
    this.horizonSeconds = horizonSeconds;
    this.feeBp = feeBp;
    this.maxLevels = maxLevels;
    this.capDv01 = capDv01;
    this.halfSpreadBp = fallbackHalfSpreadBp;
    this.tickBp = fallbackTickBp;
    this.depthDv01 = fallbackDepthDv01;
  }

  @Override
  public void observeMarket(final double halfSpreadBp, final double tickBp, final double touchDepthDv01) {
    if (halfSpreadBp > 0) {
      this.halfSpreadBp = halfSpreadBp;
    }
    if (tickBp > 0) {
      this.tickBp = tickBp;
    }
    if (touchDepthDv01 > 0) {
      this.depthDv01 = touchDepthDv01;
    }
  }

  @Override
  public double excessDv01(final RiskLadder ladder, final long nanosSinceClientFill) {
    final double net = ladder.net();
    final double a = Math.abs(net);
    if (a > capDv01 || holdCost(a) > hedgeCost(a)) {
      return net;
    }
    return 0.0;
  }

  /** Expected variance charge of holding {@code a} DV01 over the horizon, currency. */
  public double holdCost(final double a) {
    return 0.5 * gamma * sigmaBpPerSqrtSecond * sigmaBpPerSqrtSecond * a * a * horizonSeconds;
  }

  /** Expected cost of hedging {@code a} DV01 now with one market order, currency. */
  public double hedgeCost(final double a) {
    double cost = (halfSpreadBp + feeBp) * a;
    double left = a - depthDv01;
    for (int k = 1; left > 0; k++) {
      final double take = k >= maxLevels ? left : Math.min(left, depthDv01);
      cost += tickBp * k * take;
      left -= take;
    }
    return cost;
  }

  public double capDv01() {
    return capDv01;
  }
}
