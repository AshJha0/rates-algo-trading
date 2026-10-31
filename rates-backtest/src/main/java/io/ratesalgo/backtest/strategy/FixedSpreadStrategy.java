package io.ratesalgo.backtest.strategy;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.StrategyContext;
import io.ratesalgo.backtest.util.Yaml;
import java.util.Map;

/**
 * Baseline of experiment P5-001: one fixed half-spread for swaps and one for bonds, whatever the
 * client, size or time of day.
 */
public final class FixedSpreadStrategy extends HedgingQuoter {

  public static final String NAME = "fixed_spread";

  private final double swapHalfSpreadBp;
  private final double bondHalfSpreadBp;
  private final int clientsPerTier;

  public FixedSpreadStrategy(
      final double swapHalfSpreadBp,
      final double bondHalfSpreadBp,
      final double hedgeThresholdDv01,
      final int clientsPerTier) {
    checkThreshold(hedgeThresholdDv01);
    super(hedgeThresholdDv01);
    this.swapHalfSpreadBp = swapHalfSpreadBp;
    this.bondHalfSpreadBp = bondHalfSpreadBp;
    this.clientsPerTier = clientsPerTier;
  }

  /** Reads {@code swap_half_spread_bp}, {@code bond_half_spread_bp}, {@code hedge_threshold_dv01}. */
  public static FixedSpreadStrategy fromConfig(final Map<String, Object> m, final int clientsPerTier) {
    return new FixedSpreadStrategy(
        Yaml.number(m, "swap_half_spread_bp", NAME),
        Yaml.number(m, "bond_half_spread_bp", NAME),
        Yaml.number(m, "hedge_threshold_dv01", NAME),
        clientsPerTier);
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
    return instrument.kind() == Instrument.Kind.SWAP ? swapHalfSpreadBp : bondHalfSpreadBp;
  }
}
