package io.ratesalgo.hedge;

/**
 * Hook for attributing hedge P&amp;L to basis (futures versus cash, swap spread). P5's attribution
 * implements it; the default does nothing.
 */
@FunctionalInterface
public interface BasisAttribution {

  /** Does nothing. */
  BasisAttribution NONE = (instrumentId, signedQuantityMinor, price, arrivalMid) -> {};

  /** Called on every hedge fill with the arrival mid it is measured against. */
  void onHedgeFill(int instrumentId, long signedQuantityMinor, long price, long arrivalMid);
}
