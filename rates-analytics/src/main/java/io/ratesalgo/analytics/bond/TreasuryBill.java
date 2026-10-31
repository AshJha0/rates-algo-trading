package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.date.Dates;

/**
 * US Treasury bill arithmetic on the bank discount basis (31 CFR Part 356, Appendix B, Section
 * II). Prices are per 100 face; rates are decimals.
 *
 * <ul>
 *   <li>Price from discount rate: P = 100 (1 - d r / 360), r the days from issue (or settlement)
 *       to maturity. Exact; the Treasury rounds the published price to six decimals.
 *   <li>Investment rate (coupon-equivalent yield): for r not more than half a year, i = (100 - P)
 *       / P times y / r; for longer bills, the root of the Appendix B quadratic. y is 366 if the
 *       year following settlement contains 29 February, otherwise 365.
 * </ul>
 */
public final class TreasuryBill {

  private TreasuryBill() {}

  /** Price per 100 from a discount rate. */
  public static double price(final double discountRate, final int settlement, final int maturity) {
    return 100.0 * (1.0 - discountRate * (maturity - settlement) / 360.0);
  }

  /** Discount rate from a price per 100. */
  public static double discountRate(final double price, final int settlement, final int maturity) {
    return (100.0 - price) / 100.0 * 360.0 / (maturity - settlement);
  }

  /** Days in the year for the investment rate: 366 if the next twelve months contain 29 Feb. */
  static int yearBasis(final int settlement) {
    final int oneYear = Dates.addYears(settlement, 1);
    for (int y = Dates.year(settlement); y <= Dates.year(oneYear); y++) {
      if (Dates.isLeap(y)) {
        final int feb29 = Dates.of(y, 2, 29);
        if (feb29 > settlement && feb29 <= oneYear) {
          return 366;
        }
      }
    }
    return 365;
  }

  /** Investment rate (coupon-equivalent yield) from a price per 100. */
  public static double investmentRate(
      final double price, final int settlement, final int maturity) {
    final double r = maturity - settlement;
    final double y = yearBasis(settlement);
    if (r <= y / 2.0) {
      return (100.0 - price) / price * y / r;
    }
    final double a = r / (2.0 * y) - 0.25;
    final double b = r / y;
    final double c = (price - 100.0) / price;
    return (-b + Math.sqrt(b * b - 4.0 * a * c)) / (2.0 * a);
  }
}
