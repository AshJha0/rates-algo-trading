package io.ratesalgo.quoting;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;

/**
 * The PV01 of hedge orders already working, which the reservation price adds to the risk
 * engine's ladder (review 2026-11-23, Q4: the skew ignored working hedges). The desk wires it to
 * {@code Hedger.addWorking}. Calls must not allocate.
 */
@FunctionalInterface
public interface WorkingHedges {

  /** No working hedges. */
  WorkingHedges NONE = (currency, ladder) -> {};

  /** Adds the working hedges of {@code currency} to {@code ladder}. */
  void addTo(CurrencyCode currency, Dv01Vector ladder);
}
