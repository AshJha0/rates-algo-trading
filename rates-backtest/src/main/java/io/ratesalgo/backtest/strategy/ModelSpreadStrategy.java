package io.ratesalgo.backtest.strategy;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.model.CompetitorModel;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.backtest.util.Yaml;
import java.util.List;
import java.util.Map;

/**
 * Treatment of experiment P5-001: chooses the half-spread that maximises expected edge per RFQ
 * under the hit-rate model at its central point.
 *
 * <pre>
 *   coverHat(n) = median(instrument, size) * exp(sigma * e(n - 1))
 *   P(s)        = hitRate(s - coverHat, size, tenor, fixingWindow, n)
 *   E[edge](s)  = P(s) * (s - adverse(tier) - hedgeCost(kind))           in bp
 *   s*          = argmax over s in {0, step, 2 step, ..., maxBp}
 * </pre>
 *
 * where {@code e(k)} is the expected minimum of {@code k} standard normals (tabulated exact values
 * for k from 1 to 7), {@code adverse(tier)} the expected adverse markout of the
 * tier in bp and {@code hedgeCost} the expected hedge cost in bp of the hedged DV01.
 * {@code ratesresearch.spread.optimal_half_spread} implements the same formula, so the Python
 * spread tables and this strategy agree.
 */
public final class ModelSpreadStrategy extends HedgingQuoter {

  public static final String NAME = "model_spread";

  private final HitRateModel hitRate;
  private final CompetitorModel competitors;
  private final double[] adverseBpByTier;
  private final double swapHedgeCostBp;
  private final double bondHedgeCostBp;
  private final double maxBp;
  private final double stepBp;
  private final int clientsPerTier;

  public ModelSpreadStrategy(
      final HitRateModel hitRate,
      final CompetitorModel competitors,
      final double[] adverseBpByTier,
      final double swapHedgeCostBp,
      final double bondHedgeCostBp,
      final double maxBp,
      final double stepBp,
      final double hedgeThresholdDv01,
      final int clientsPerTier) {
    super(hedgeThresholdDv01);
    if (!(stepBp > 0) || !(maxBp > 0)) {
      throw new IllegalArgumentException("spread grid must be positive");
    }
    this.hitRate = hitRate;
    this.competitors = competitors;
    this.adverseBpByTier = adverseBpByTier.clone();
    this.swapHedgeCostBp = swapHedgeCostBp;
    this.bondHedgeCostBp = bondHedgeCostBp;
    this.maxBp = maxBp;
    this.stepBp = stepBp;
    this.clientsPerTier = clientsPerTier;
  }

  /**
   * Reads {@code adverse_bp_by_tier} (list), {@code swap_hedge_cost_bp}, {@code
   * bond_hedge_cost_bp}, {@code max_half_spread_bp}, {@code grid_step_bp} and {@code
   * hedge_threshold_dv01}.
   */
  public static ModelSpreadStrategy fromConfig(
      final Map<String, Object> m,
      final HitRateModel hitRate,
      final CompetitorModel competitors,
      final int clientsPerTier) {
    final List<Object> adverse = Yaml.asList(Yaml.require(m, "adverse_bp_by_tier", NAME), NAME);
    final double[] a = new double[adverse.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = Yaml.toDouble(adverse.get(i), NAME + ".adverse_bp_by_tier");
    }
    return new ModelSpreadStrategy(
        hitRate,
        competitors,
        a,
        Yaml.number(m, "swap_hedge_cost_bp", NAME),
        Yaml.number(m, "bond_hedge_cost_bp", NAME),
        Yaml.number(m, "max_half_spread_bp", NAME),
        Yaml.number(m, "grid_step_bp", NAME),
        Yaml.number(m, "hedge_threshold_dv01", NAME),
        clientsPerTier);
  }

  /** Expected minimum of k independent standard normals (exact values to four decimals). */
  static double expectedMinNormal(final int k) {
    return switch (k) {
      case 1 -> 0.0;
      case 2 -> -0.5642;
      case 3 -> -0.8463;
      case 4 -> -1.0294;
      case 5 -> -1.1630;
      case 6 -> -1.2672;
      default -> -1.3522; // k = 7; more dealers than this are rare on D2C venues
    };
  }

  /** Expected edge in bp of quoting half-spread {@code s}. */
  public double expectedEdgeBp(
      final double s,
      final Instrument instrument,
      final double sizeMillions,
      final int tier,
      final int dealers,
      final boolean inWindow) {
    final double coverHat =
        competitors.medianBp(instrument, sizeMillions)
            * Math.exp(sigma() * expectedMinNormal(Math.max(1, dealers - 1)));
    final double p =
        hitRate.winProbability(s - coverHat, sizeMillions, instrument.tenorYears(), inWindow, dealers);
    final double hedge = instrument.kind() == Instrument.Kind.SWAP ? swapHedgeCostBp : bondHedgeCostBp;
    return p * (s - adverse(tier) - hedge);
  }

  private double sigma() {
    return competitors.sigma();
  }

  private double adverse(final int tier) {
    final int i = Math.min(Math.max(tier, 1), adverseBpByTier.length) - 1;
    return adverseBpByTier[i];
  }

  /** The half-spread on the grid with the largest expected edge; ties go to the narrower. */
  public double optimalHalfSpreadBp(
      final Instrument instrument,
      final double sizeMillions,
      final int tier,
      final int dealers,
      final boolean inWindow) {
    double best = 0.0;
    double bestEdge = Double.NEGATIVE_INFINITY;
    final int steps = (int) Math.round(maxBp / stepBp);
    for (int i = 0; i <= steps; i++) {
      final double s = i * stepBp;
      final double edge = expectedEdgeBp(s, instrument, sizeMillions, tier, dealers, inWindow);
      if (edge > bestEdge + 1e-12) {
        bestEdge = edge;
        best = s;
      }
    }
    return best;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  protected int clientsPerTier() {
    return clientsPerTier;
  }

  @Override
  protected double halfSpreadBp(
      final Instrument instrument,
      final double sizeMillions,
      final int tier,
      final int dealers,
      final StrategyContext ctx) {
    return optimalHalfSpreadBp(
        instrument, sizeMillions, tier, dealers, ctx.calendar().inWindow(ctx.nowEpochNanos()));
  }
}
