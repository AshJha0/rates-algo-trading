package io.ratesalgo.strategy;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.strategy.ModelSpreadStrategy;

/**
 * The half-spread that maximises expected edge under the hit-rate model, the formula of {@link
 * ModelSpreadStrategy} with one more cost term:
 *
 * <pre>
 *   E[edge](s) = P(s) * (s - adverse(tier) - hedgeCost(kind) - extra)
 * </pre>
 *
 * where {@code extra} is the sum of the feature costs (size premium, inventory penalty) in bp.
 * With {@code extra = 0} the arithmetic is the same as {@link ModelSpreadStrategy}'s, operation
 * for operation, so the baseline reproduces it exactly (DeskQuoterBaselineTest). Allocation-free.
 */
public final class SpreadOptimizer {

  private final HitRateModel hitRate;
  private final CompetitorModel competitors;
  private final double[] adverseBpByTier;
  private final double swapHedgeCostBp;
  private final double bondHedgeCostBp;
  private final double maxBp;
  private final double stepBp;

  public SpreadOptimizer(
      final HitRateModel hitRate,
      final CompetitorModel competitors,
      final double[] adverseBpByTier,
      final double swapHedgeCostBp,
      final double bondHedgeCostBp,
      final double maxBp,
      final double stepBp) {
    if (!(stepBp > 0) || !(maxBp > 0) || adverseBpByTier.length == 0) {
      throw new IllegalArgumentException("spread grid must be positive and tiers non-empty");
    }
    this.hitRate = hitRate;
    this.competitors = competitors;
    this.adverseBpByTier = adverseBpByTier.clone();
    this.swapHedgeCostBp = swapHedgeCostBp;
    this.bondHedgeCostBp = bondHedgeCostBp;
    this.maxBp = maxBp;
    this.stepBp = stepBp;
  }

  private double adverse(final int tier) {
    final int i = Math.min(Math.max(tier, 1), adverseBpByTier.length) - 1;
    return adverseBpByTier[i];
  }

  /** Expected edge in bp of half-spread {@code s}. */
  public double expectedEdgeBp(
      final double s,
      final Instrument instrument,
      final double sizeMillions,
      final int tier,
      final int dealers,
      final boolean inWindow,
      final double extraBp) {
    final double coverHat =
        competitors.medianBp(instrument, sizeMillions)
            * Math.exp(competitors.sigma() * ModelSpreadStrategy.expectedMinNormal(Math.max(1, dealers - 1)));
    final double p =
        hitRate.winProbability(s - coverHat, sizeMillions, instrument.tenorYears(), inWindow, dealers);
    final double hedge = instrument.kind() == Instrument.Kind.SWAP ? swapHedgeCostBp : bondHedgeCostBp;
    return p * (s - adverse(tier) - hedge - extraBp);
  }

  /** The half-spread on the grid with the largest expected edge; ties go to the narrower. */
  public double optimalHalfSpreadBp(
      final Instrument instrument,
      final double sizeMillions,
      final int tier,
      final int dealers,
      final boolean inWindow,
      final double extraBp) {
    double best = 0.0;
    double bestEdge = Double.NEGATIVE_INFINITY;
    final int steps = (int) Math.round(maxBp / stepBp);
    for (int i = 0; i <= steps; i++) {
      final double s = i * stepBp;
      final double edge = expectedEdgeBp(s, instrument, sizeMillions, tier, dealers, inWindow, extraBp);
      if (edge > bestEdge + 1e-12) {
        bestEdge = edge;
        best = s;
      }
    }
    return best;
  }
}
