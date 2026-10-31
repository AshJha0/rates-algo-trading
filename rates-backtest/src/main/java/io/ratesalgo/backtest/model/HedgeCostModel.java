package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.util.Prices;

/**
 * Fills a marketable hedge order at a simple cost: half the quoted spread plus a linear impact per
 * lot, rounded to the tick against us, plus a fee per lot.
 *
 * <pre>
 *   fill = mid + sign * (halfSpread + impactTicksPerLot * lots * tick), rounded to tick against us
 *   fee  = feePerLot * lots
 * </pre>
 *
 * <p>Parameters are declared assumptions in preset {@value #PRESET}. The half-spread is the
 * book's own when a book exists, so the cost moves with the synthetic market.
 */
public final class HedgeCostModel {

  public static final String PRESET = "usd_hedge_cost";

  /** A fill price (mantissa) and fee (major currency). */
  public record Fill(long priceMantissa, double fee) {}

  private final double impactTicksPerLot;
  private final double feePerLot;
  private final double maxImpactTicks;

  public HedgeCostModel(final double impactTicksPerLot, final double feePerLot, final double maxImpactTicks) {
    if (impactTicksPerLot < 0 || feePerLot < 0 || maxImpactTicks < 0) {
      throw new IllegalArgumentException("hedge cost parameters must be non-negative");
    }
    this.impactTicksPerLot = impactTicksPerLot;
    this.feePerLot = feePerLot;
    this.maxImpactTicks = maxImpactTicks;
  }

  public static HedgeCostModel fromPreset(final Preset p) {
    return new HedgeCostModel(
        p.value("impact_ticks_per_lot"), p.value("fee_per_lot"), p.value("max_impact_ticks"));
  }

  /** Fee for {@code quantity} minor units, major currency. */
  public double fee(final Instrument instrument, final long quantity) {
    return feePerLot * ((double) Math.abs(quantity) / instrument.lotSize());
  }

  /**
   * Fill for buying ({@code buy == true}) or selling {@code quantity} minor units.
   *
   * @param midMantissa current mid
   * @param halfSpreadMantissa current half bid-ask spread (0 if no book)
   */
  public Fill fill(
      final Instrument instrument,
      final boolean buy,
      final long quantity,
      final long midMantissa,
      final long halfSpreadMantissa) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("hedge quantity must be positive: " + quantity);
    }
    final double lots = (double) quantity / instrument.lotSize();
    final double impactTicks = Math.min(maxImpactTicks, impactTicksPerLot * lots);
    final long cost = halfSpreadMantissa + Math.round(impactTicks * instrument.tickMantissa());
    final long raw = buy ? midMantissa + cost : midMantissa - cost;
    final long price = Prices.roundToTick(raw, instrument.tickMantissa(), buy);
    return new Fill(price, feePerLot * lots);
  }
}
