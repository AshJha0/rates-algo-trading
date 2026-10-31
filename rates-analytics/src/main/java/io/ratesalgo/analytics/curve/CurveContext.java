package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.swap.FixingHistory;

/**
 * What an instrument needs to compute its implied quote during a bootstrap.
 *
 * @param target the curve being bootstrapped (projection curve for the index, or the OIS curve)
 * @param discount the discounting curve; the same object as {@code target} for an OIS curve
 * @param fixings published fixings of the target's index
 * @param today the valuation date
 */
public record CurveContext(
    DiscountCurve target, DiscountCurve discount, FixingHistory fixings, int today) {

  /** True when the target curve also discounts (single-curve OIS bootstrap). */
  public boolean singleCurve() {
    return target == discount;
  }
}
