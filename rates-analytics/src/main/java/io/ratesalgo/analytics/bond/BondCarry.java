package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.funding.RepoCurve;

/**
 * Repo-financed forward price, carry and roll-down of a bond over a horizon, per 100 face.
 *
 * <p>Plain idea: buying a bond today and financing it in repo until the horizon costs the dirty
 * price plus repo interest; the coupons received on the way reduce that cost. The forward clean
 * price is what breaks even. Carry is spot clean minus forward clean: positive when the coupon
 * income exceeds the financing cost.
 *
 * <pre>
 *   F_dirty = P_dirty (1 + r d / 360) - sum over coupons c_i paid in (settle, horizon] of
 *             c_i (1 + r d_i / 360)
 *   F_clean = F_dirty - accrued(horizon)
 *   carry   = P_clean - F_clean
 * </pre>
 *
 * with r the repo rate (Act/360 simple, GC or special) for d days, d_i the days from coupon i to
 * the horizon. Exact given the inputs.
 */
public final class BondCarry {

  private final BondPricer pricer = new BondPricer();

  /** Creates the calculator. */
  public BondCarry() {}

  /** Repo-implied forward clean price at the horizon settlement date. */
  public double forwardCleanPrice(
      final FixedRateBond b,
      final double cleanPrice,
      final int settlement,
      final int horizon,
      final double repoRate) {
    final double dirty = cleanPrice + pricer.accrued(b, settlement);
    final int d = horizon - settlement;
    double fwd = dirty * (1.0 + repoRate * d / 360.0);
    for (int i = 0; i < b.couponCount(); i++) {
      final int pay = b.couponDate(i);
      if (pay > settlement && pay <= horizon && settlement < b.exDividendDate(i)) {
        fwd -= b.couponAmount(i) * (1.0 + repoRate * (horizon - pay) / 360.0);
      }
    }
    return fwd - pricer.accrued(b, horizon);
  }

  /** Forward clean price with the repo rate read from a repo curve for the term. */
  public double forwardCleanPrice(
      final FixedRateBond b,
      final double cleanPrice,
      final int settlement,
      final int horizon,
      final RepoCurve repo,
      final double specialSpread) {
    return forwardCleanPrice(
        b, cleanPrice, settlement, horizon,
        repo.rate(horizon - settlement) - specialSpread);
  }

  /** Carry in price per 100: spot clean minus forward clean. */
  public double carry(
      final FixedRateBond b,
      final double cleanPrice,
      final int settlement,
      final int horizon,
      final double repoRate) {
    return cleanPrice - forwardCleanPrice(b, cleanPrice, settlement, horizon, repoRate);
  }

  /**
   * Roll-down in price per 100: the change in clean price at the horizon if the bond's yield falls
   * by the fitted curve's slope over the horizon, that is if its spread to the curve is
   * unchanged and the curve is unchanged.
   *
   * @param yield the bond's street yield today
   * @param curveYieldToday fitted curve yield at today's remaining maturity
   * @param curveYieldAtHorizon fitted curve yield at the remaining maturity at the horizon
   */
  public double rollDown(
      final FixedRateBond b,
      final double yield,
      final int horizon,
      final double curveYieldToday,
      final double curveYieldAtHorizon) {
    final double rolled = yield + (curveYieldAtHorizon - curveYieldToday);
    return pricer.cleanPrice(b, rolled, horizon) - pricer.cleanPrice(b, yield, horizon);
  }
}
