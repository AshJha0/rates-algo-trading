package io.ratesalgo.hedge;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.risk.RiskEngine;

/**
 * Attributes each hedge fill to the basis risk it moves (PNL_AUDIT.md item 16). Hedging swap risk
 * in futures leaves the rate risk flat but opens swap-spread exposure; hedging cash in futures opens
 * futures-versus-cash basis. For every fill this records how much of each exposure the fill
 * itself added (positive) or removed (negative), read from the risk engine's product PV01 before
 * and after the fill, and the fill's slippage against arrival by product family.
 *
 * <p>Called by {@link HedgeAnalytics#onFill} after the risk engine has booked the fill. One per
 * currency; fills in other currencies are ignored. Allocation-free.
 */
public final class ExposureBasisAttribution implements BasisAttribution {

  private final RiskEngine risk;
  private final CurrencyCode currency;
  private final double[] slippageByProduct = new double[ProductType.COUNT];
  private double basisAdded;
  private double swapSpreadAdded;
  private long fills;

  /** Attribution for hedges in {@code currency}, reading exposures from {@code risk}. */
  public ExposureBasisAttribution(final RiskEngine risk, final CurrencyCode currency) {
    this.risk = risk;
    this.currency = currency;
  }

  @Override
  public void onHedgeFill(
      final int instrumentId, final long signedQuantityMinor, final long price, final long arrivalMid) {
    final Instrument inst = risk.directory().byId(instrumentId);
    if (inst == null || inst.currency() != currency) {
      return;
    }
    fills++;
    final double qty = inst.major(signedQuantityMinor);
    final double fillPv01 = risk.unitParallelPv01(instrumentId) * qty;
    final int t = inst.type().index();
    final double fut = risk.productPv01(currency, ProductType.FUTURE);
    final double bond = risk.productPv01(currency, ProductType.BOND);
    final double swap = risk.productPv01(currency, ProductType.SWAP);
    final double futBefore = fut - (t == ProductType.FUTURE.index() ? fillPv01 : 0.0);
    final double bondBefore = bond - (t == ProductType.BOND.index() ? fillPv01 : 0.0);
    final double swapBefore = swap - (t == ProductType.SWAP.index() ? fillPv01 : 0.0);
    basisAdded += Math.abs(offset(fut, bond)) - Math.abs(offset(futBefore, bondBefore));
    swapSpreadAdded +=
        Math.abs(offset(swap, bond + fut)) - Math.abs(offset(swapBefore, bondBefore + futBefore));
    final double slip = (Decimal9.toDouble(price) - Decimal9.toDouble(arrivalMid)) * Math.signum(qty);
    slippageByProduct[t] += slip * inst.quoteValue() * Math.abs(qty);
  }

  /** Futures-versus-cash basis PV01 the hedges added since construction (negative: removed). */
  public double basisAdded() {
    return basisAdded;
  }

  /** Swap-spread PV01 the hedges added since construction (negative: removed). */
  public double swapSpreadAdded() {
    return swapSpreadAdded;
  }

  /** Current futures-versus-cash basis exposure of the currency, from the risk engine. */
  public double basisExposure() {
    return risk.basisExposure(currency);
  }

  /** Current swap-spread exposure of the currency, from the risk engine. */
  public double swapSpreadExposure() {
    return risk.swapSpreadExposure(currency);
  }

  /** Slippage against arrival of hedges in one product family, currency units (positive: cost). */
  public double slippage(final ProductType type) {
    return slippageByProduct[type.index()];
  }

  /** Hedge fills attributed. */
  public long fills() {
    return fills;
  }

  private static double offset(final double a, final double b) {
    if (a * b >= 0.0) {
      return 0.0;
    }
    return Math.signum(a) * Math.min(Math.abs(a), Math.abs(b));
  }
}
