package io.ratesalgo.strategy;

/**
 * Feature 1, internalisation and netting: how much of the book's DV01 to hedge now
 * (API_SPEC.md Section 19.2).
 *
 * <p>Client trades land in the {@link RiskLadder}, where opposing flow across tenors and products
 * nets. The budget decides what is left over to hedge. The default, {@link #threshold}, is
 * today's engine: once net DV01 exceeds a threshold, hedge all of it.
 *
 * <p>Implementations are allocation-free.
 */
public interface HedgeBudget {

  /**
   * DV01 to remove now, with the sign of the exposure being removed (a hedge of {@code -excess}
   * DV01 is sent); zero for none.
   *
   * @param ladder the book after the latest trade
   * @param nanosSinceClientFill time since the latest client fill
   */
  double excessDv01(RiskLadder ladder, long nanosSinceClientFill);

  /**
   * How long after a client fill the budget wants to be asked again even if nothing else
   * happens; zero for never (the default rule is only asked on fills).
   */
  default long holdNanos() {
    return 0L;
  }

  /**
   * The hedge future's market before the budget is asked: half-spread and tick in bp, and the DV01
   * displayed at the touch on the side a hedge would take (zero when unknown). Ignored by default.
   */
  default void observeMarket(final double halfSpreadBp, final double tickBp, final double touchDepthDv01) {}

  /** Today's rule: hedge all of the net DV01 once its absolute value exceeds {@code thresholdDv01}. */
  static HedgeBudget threshold(final double thresholdDv01) {
    return new ThresholdHedgeBudget(thresholdDv01);
  }
}
