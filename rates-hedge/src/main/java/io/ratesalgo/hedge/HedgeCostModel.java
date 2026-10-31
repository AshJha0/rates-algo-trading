package io.ratesalgo.hedge;

/**
 * Modelled cost of trading a hedge instrument, in major currency units, as a function of the
 * number of lots {@code x} traded at once: {@code linear(j) * |x| + impact(j) * x^2}. The linear
 * term is the half spread plus any basis or carry cost of using that instrument instead of the
 * risk being hedged; the quadratic term is temporary market impact.
 */
public interface HedgeCostModel {

  /**
   * Cost per lot, independent of size; non-negative. Positive infinity for an instrument the model
   * has no cost for: the planner never trades it.
   */
  double linearPerLot(int instrumentId);

  /** Impact coefficient per lot squared; non-negative. */
  double impactPerLot2(int instrumentId);

  /** Modelled cost of trading {@code lots} lots at once. */
  default double cost(final int instrumentId, final double lots) {
    return linearPerLot(instrumentId) * Math.abs(lots) + impactPerLot2(instrumentId) * lots * lots;
  }
}
