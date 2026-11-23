package io.ratesalgo.app.pricing;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.SensitivityProvider;

/**
 * What the desk loop needs from a pricing binding, whichever version is selected: fair values,
 * ladders, the instrument directory, and the entry point for curve market data. {@link
 * AnalyticsPricing} is version 1 and the default; {@link PricingV2} is version 2. {@link
 * PricingBindings#create} picks one.
 */
public interface PricingBinding extends FairValueSource, SensitivityProvider {

  /** The instruments the binding prices. */
  InstrumentDirectory directory();

  /** The universe the binding was built from. */
  UsdUniverse universe();

  /** The composite book the binding reads bond prices from. */
  void book(BookSource composite);

  /**
   * One curve input from the loop. Returns true if the instrument is one the binding takes
   * quotes for, whether or not this quote was accepted.
   */
  boolean onCurveInput(int instrumentId, long valueMantissa, long timestampNanos);

  /**
   * Records one curve input without repricing, for a loop that conflates its inputs and reprices
   * once per burst with {@link #repriceIfDirty}. Version 1 holds nothing: each input is applied as
   * it comes, as {@link #onCurveInput} does, and that is the default.
   */
  default boolean setInput(final int instrumentId, final long valueMantissa, final long timestampNanos) {
    return onCurveInput(instrumentId, valueMantissa, timestampNanos);
  }

  /**
   * Applies every input recorded by {@link #setInput} since the last reprice, with one solve.
   * Returns true if it repriced. Version 1 holds nothing, so the default does nothing.
   */
  default boolean repriceIfDirty() {
    return false;
  }
}
