package io.ratesalgo.app.pricing;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.SpreadSensitivityProvider;
import io.ratesalgo.core.HotPath;

/**
 * A pricing binding with the universe's Treasury futures added to it, so that the risk engine,
 * the composite book and the hedge planner see a future as one more instrument. Swaps and bonds
 * are the wrapped binding's, untouched; this class only answers for the futures.
 *
 * <p>What a future is here, and what it is not:
 *
 * <ul>
 *   <li>Fair value is the mid of the futures order book. The binding has no model of the basis,
 *       so it has no opinion beyond the book; with one side missing there is no value.
 *   <li>Rate risk is the contract's declared price change per basis point at its maturity node
 *       ({@link UsdUniverse.FutureSpec#priceChangePerBp}), split onto the key-rate tenors. It is
 *       the market model's definition of the contract, not a cheapest-to-deliver calculation;
 *       that belongs to the analytics library and is not done here.
 *   <li>Spread risk is the same ladder: a Treasury future moves with Treasury yields, so it
 *       carries the Treasury-to-SOFR spread as a bond does. The futures basis is a third factor
 *       this ladder does not show.
 * </ul>
 *
 * <p>The directory lists the wrapped binding's instruments first, at the same dense indices, then
 * the futures. Nothing allocates after construction.
 */
@HotPath
public final class FuturesOverlay implements PricingBinding, SpreadSensitivityProvider {

  private static final double USD_PER_MINOR_POINT = 1.0 / 10_000.0;

  private final PricingBinding base;
  private final SpreadSensitivityProvider baseSpread;
  private final InMemoryInstrumentDirectory directory = new InMemoryInstrumentDirectory();
  private final int[] futureIds;
  /** USD per basis point per minor unit of face at each key-rate tenor, per future. */
  private final double[][] unitLadder;
  private BookSource book;

  /**
   * Wraps a binding.
   *
   * @param base the binding for swaps and bonds
   * @param typicalContracts contracts in a typical hedge order, for the instrument's typical size
   */
  public FuturesOverlay(final PricingBinding base, final long typicalContracts) {
    this.base = base;
    this.baseSpread = base instanceof SpreadSensitivityProvider s ? s : null;
    final InstrumentDirectory inner = base.directory();
    for (int i = 0; i < inner.size(); i++) {
      directory.add(inner.byIndex(i));
    }
    futureIds = base.universe().futureIds();
    unitLadder = new double[futureIds.length][KeyRateTenors.COUNT];
    final double[] weights = new double[KeyRateTenors.COUNT];
    for (int j = 0; j < futureIds.length; j++) {
      final UsdUniverse.FutureSpec f = base.universe().future(futureIds[j]);
      directory.add(
          Instrument.future(
              f.instrumentId(), f.securityId(), CurrencyCode.USD, f.nodeYears(), typicalContracts * f.contractMinor()));
      KeyRateTenors.split(f.nodeYears(), weights);
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        // price per 100 of face, face in minor units: 100 x 100 of them move one USD per point
        unitLadder[j][k] = weights[k] * f.priceChangePerBp() * USD_PER_MINOR_POINT;
      }
    }
  }

  private int future(final int instrumentId) {
    for (int j = 0; j < futureIds.length; j++) {
      if (futureIds[j] == instrumentId) {
        return j;
      }
    }
    return -1;
  }

  /** The wrapped binding. */
  public PricingBinding base() {
    return base;
  }

  @Override
  public InstrumentDirectory directory() {
    return directory;
  }

  @Override
  public UsdUniverse universe() {
    return base.universe();
  }

  @Override
  public void book(final BookSource composite) {
    this.book = composite;
    base.book(composite);
  }

  @Override
  public boolean onCurveInput(final int instrumentId, final long valueMantissa, final long timestampNanos) {
    return base.onCurveInput(instrumentId, valueMantissa, timestampNanos);
  }

  @Override
  public boolean setInput(final int instrumentId, final long valueMantissa, final long timestampNanos) {
    return base.setInput(instrumentId, valueMantissa, timestampNanos);
  }

  @Override
  public boolean repriceIfDirty() {
    return base.repriceIfDirty();
  }

  @Override
  public long fairMid(final int instrumentId) {
    if (future(instrumentId) < 0) {
      return base.fairMid(instrumentId);
    }
    if (book == null) {
      return NO_VALUE;
    }
    final long bid = book.bestBid(instrumentId);
    final long ask = book.bestAsk(instrumentId);
    return bid == NO_VALUE || ask == NO_VALUE ? NO_VALUE : bid + (ask - bid) / 2;
  }

  @Override
  public long referenceNanos(final int instrumentId) {
    if (future(instrumentId) < 0) {
      return base.referenceNanos(instrumentId);
    }
    return book == null ? NO_VALUE : book.updateNanos(instrumentId);
  }

  @Override
  public boolean pv01(final int instrumentId, final long quantityMinor, final Dv01Vector out) {
    final int j = future(instrumentId);
    if (j < 0) {
      return base.pv01(instrumentId, quantityMinor, out);
    }
    out.clear();
    out.currency(CurrencyCode.USD);
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      out.set(k, unitLadder[j][k] * quantityMinor);
    }
    return true;
  }

  @Override
  public boolean spreadPv01(final int instrumentId, final long quantityMinor, final Dv01Vector out) {
    if (future(instrumentId) >= 0) {
      return pv01(instrumentId, quantityMinor, out);
    }
    if (baseSpread == null) {
      out.clear();
      return false;
    }
    return baseSpread.spreadPv01(instrumentId, quantityMinor, out);
  }
}
