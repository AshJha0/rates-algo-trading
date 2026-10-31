package io.ratesalgo.analytics.futures;

import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.date.Dates;

/**
 * Conversion factors of bond futures: the price per unit face of the deliverable bond at the
 * contract's notional coupon yield, so that bonds with different coupons and maturities deliver
 * at comparable invoice prices.
 *
 * <ul>
 *   <li>CBOT Treasury futures: the CME closed form ("Calculating U.S. Treasury Futures Conversion
 *       Factors", CME Group), 6 percent semi-annual, remaining term from the first day of the
 *       delivery month rounded down to whole months (2-, 3-, 5-year) or quarters (others), rounded
 *       to four decimals.
 *   <li>Eurex Schatz, Bobl, Bund, Buxl: clean price per 1 face at a 6 percent annual Act/Act
 *       (ICMA) yield on the delivery day, rounded to six decimals (Eurex contract specifications,
 *       conversion factor formula).
 *   <li>ICE Long Gilt: clean price per 1 face at a 4 percent semi-annual yield on the first day of
 *       the delivery month, rounded to seven decimals (ICE Futures Europe Long Gilt contract
 *       specifications).
 * </ul>
 */
public final class ConversionFactors {

  private ConversionFactors() {}

  /** Whole months from {@code from} to {@code to} (day-of-month aware). */
  public static int wholeMonths(final int from, final int to) {
    int months = (Dates.year(to) - Dates.year(from)) * 12 + Dates.month(to) - Dates.month(from);
    if (Dates.dayOfMonth(to) < Dates.dayOfMonth(from)) {
      months--;
    }
    return months;
  }

  /**
   * CBOT conversion factor.
   *
   * @param coupon annual coupon, decimal
   * @param maturity bond maturity
   * @param deliveryYear contract year
   * @param deliveryMonth contract month
   * @param contract the contract, which sets the rounding
   */
  public static double cme(
      final double coupon,
      final int maturity,
      final int deliveryYear,
      final int deliveryMonth,
      final TreasuryFutureContract contract) {
    final int first = Dates.of(deliveryYear, deliveryMonth, 1);
    final int months = wholeMonths(first, maturity);
    final int n = months / 12;
    final int r = contract.roundingMonths();
    final int z = (months % 12) / r * r;
    final int v = z < 7 ? z : z - 6;
    final double a = 1.0 / Math.pow(1.03, v / 6.0);
    final double b = coupon / 2.0 * (6 - v) / 6.0;
    final double c = z < 7 ? 1.0 / Math.pow(1.03, 2 * n) : 1.0 / Math.pow(1.03, 2 * n + 1);
    final double d = coupon / 0.06 * (1.0 - c);
    final double factor = a * (coupon / 2.0 + c + d) - b;
    return Math.round(factor * 1e4) / 1e4;
  }

  /**
   * Conversion factor as the clean price per 1 face at a notional yield, by {@link BondPricer}'s
   * street convention, rounded to {@code decimals}.
   */
  public static double byYield(
      final FixedRateBond bond,
      final int date,
      final double notionalYield,
      final int decimals,
      final BondPricer pricer) {
    final double scale = Math.pow(10, decimals);
    return Math.round(pricer.cleanPrice(bond, notionalYield, date) / 100.0 * scale) / scale;
  }

  /** Eurex conversion factor: 6 percent, delivery day, six decimals. */
  public static double eurex(final FixedRateBond bond, final int deliveryDay, final BondPricer p) {
    return byYield(bond, deliveryDay, 0.06, 6, p);
  }

  /** ICE Long Gilt conversion factor: 4 percent, first day of the delivery month, seven decimals. */
  public static double longGilt(
      final FixedRateBond bond, final int deliveryYear, final int deliveryMonth,
      final BondPricer p) {
    return byYield(bond, Dates.of(deliveryYear, deliveryMonth, 1), 0.04, 7, p);
  }
}
