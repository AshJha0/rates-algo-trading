package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.util.Prices;

/**
 * Fills a marketable hedge order. Two rules, chosen by {@link #depthWalk()}:
 *
 * <ul>
 *   <li><b>Depth walk</b> (audit fix package D, opt in with {@link #withDepthWalk}): the order takes the displayed
 *       quantity at the touch, then the same quantity at each tick beyond it, up to {@code
 *       maxImpactTicks} ticks beyond the touch; anything left fills at that last level. The fill
 *       price is the quantity-weighted average, rounded against us to one mantissa unit. The books
 *       the back-tester sees carry only the top level, so every deeper level is assumed to display
 *       the touch's quantity (a declared assumption).
 *   <li><b>Linear impact</b> (the default, which every ledger row before package D was recorded
 *       with, and the fallback when no displayed quantity is known): half the quoted spread plus a linear impact per lot, rounded to the tick
 *       against us. With the mid on a half tick, any positive impact adds a full tick, which is fair
 *       for blocks far above the touch and overstates the cost of small ones (PNL_AUDIT.md).
 * </ul>
 *
 * <pre>
 *   linear: fill = mid + sign * (halfSpread + min(maxImpact, impactTicksPerLot * lots) * tick), to tick
 *   depth:  fill = touch + sign * tick * sum_k k * min(q_k, touchQty) / q,  k = 0 .. maxImpactTicks
 *   fee     = feePerLot * lots
 * </pre>
 *
 * <p>Parameters are declared assumptions in preset {@value #PRESET}. The half-spread and the
 * displayed quantity are the book's own when a book exists, so the cost moves with the market.
 */
public final class HedgeCostModel {

  public static final String PRESET = "usd_hedge_cost";

  /** A fill price (mantissa) and fee (major currency). */
  public record Fill(long priceMantissa, double fee) {}

  private final double impactTicksPerLot;
  private final double feePerLot;
  private final double maxImpactTicks;
  private final boolean depthWalk;

  /** The linear-impact rule (the constructor before package D, unchanged in behaviour). */
  public HedgeCostModel(final double impactTicksPerLot, final double feePerLot, final double maxImpactTicks) {
    this(impactTicksPerLot, feePerLot, maxImpactTicks, false);
  }

  /**
   * @param depthWalk true to fill level by level against displayed depth when it is known
   */
  public HedgeCostModel(
      final double impactTicksPerLot, final double feePerLot, final double maxImpactTicks, final boolean depthWalk) {
    if (impactTicksPerLot < 0 || feePerLot < 0 || maxImpactTicks < 0) {
      throw new IllegalArgumentException("hedge cost parameters must be non-negative");
    }
    this.impactTicksPerLot = impactTicksPerLot;
    this.feePerLot = feePerLot;
    this.maxImpactTicks = maxImpactTicks;
    this.depthWalk = depthWalk;
  }

  /**
   * The preset's parameters with the linear-impact rule, so recorded ledger rows reproduce; use
   * {@code fromPreset(p).withDepthWalk(true)} for the depth walk.
   */
  public static HedgeCostModel fromPreset(final Preset p) {
    return new HedgeCostModel(
        p.value("impact_ticks_per_lot"), p.value("fee_per_lot"), p.value("max_impact_ticks"), false);
  }

  /** The same parameters with the depth walk on or off. */
  public HedgeCostModel withDepthWalk(final boolean on) {
    return new HedgeCostModel(impactTicksPerLot, feePerLot, maxImpactTicks, on);
  }

  /** True when market orders are filled level by level against displayed depth. */
  public boolean depthWalk() {
    return depthWalk;
  }

  /** Fee for {@code quantity} minor units, major currency. */
  public double fee(final Instrument instrument, final long quantity) {
    return feePerLot * ((double) Math.abs(quantity) / instrument.lotSize());
  }

  /**
   * Fill for buying ({@code buy == true}) or selling {@code quantity} minor units by the
   * linear-impact rule.
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

  /**
   * Fill for buying or selling {@code quantity} minor units. With the depth walk on and a positive
   * {@code touchQuantity}, the order walks the book from the touch; otherwise this is {@link
   * #fill(Instrument, boolean, long, long, long)}.
   *
   * @param touchQuantity displayed quantity at the touch on the side the order takes, minor units
   */
  public Fill fill(
      final Instrument instrument,
      final boolean buy,
      final long quantity,
      final long midMantissa,
      final long halfSpreadMantissa,
      final long touchQuantity) {
    if (!depthWalk || touchQuantity <= 0) {
      return fill(instrument, buy, quantity, midMantissa, halfSpreadMantissa);
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("hedge quantity must be positive: " + quantity);
    }
    final long tick = instrument.tickMantissa();
    final long touch =
        Prices.roundToTick(buy ? midMantissa + halfSpreadMantissa : midMantissa - halfSpreadMantissa, tick, buy);
    final long maxLevel = (long) Math.floor(maxImpactTicks);
    long left = quantity;
    // quantity-weighted sum of levels beyond the touch, in ticks times minor units
    double levelQuantity = 0;
    for (long k = 0; left > 0; k++) {
      final long take = k >= maxLevel ? left : Math.min(left, touchQuantity);
      levelQuantity += (double) k * take;
      left -= take;
    }
    final double beyond = levelQuantity / quantity * tick;
    final long price = buy ? touch + (long) Math.ceil(beyond) : touch - (long) Math.ceil(beyond);
    return new Fill(price, fee(instrument, quantity));
  }
}
