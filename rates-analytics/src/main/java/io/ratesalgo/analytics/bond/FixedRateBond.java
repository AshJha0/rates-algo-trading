package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.Schedule;
import io.ratesalgo.analytics.date.StubRule;

/**
 * A fixed-coupon bond: coupon schedule rolled backward from maturity to the dated date, coupon
 * dates unadjusted (the end-of-month rule applies when maturity is a month end, as for Treasuries
 * maturing on the last day of a month), irregular first coupon handled by Act/Act ICMA reference
 * periods. Amounts are per 100 face.
 *
 * <p>Each coupon has two payment dates: the nominal coupon date (used by the street yield
 * convention) and the date moved to the next business day (used by the true yield). The
 * ex-dividend date, if any, is the convention's number of business days before the nominal
 * coupon date. Built once; {@link BondPricer} prices without allocating.
 */
public final class FixedRateBond {

  private final String name;
  private final BondConvention convention;
  private final double coupon;
  private final int datedDate;
  private final int maturity;
  final int[] accrualStart;
  final int[] accrualEnd;
  final int[] refStart;
  final int[] refEnd;
  final int[] payNominal;
  final int[] payAdjusted;
  final int[] exDate;
  final double[] amount;

  /**
   * Creates the bond.
   *
   * @param name identifier (CUSIP, ISIN or description)
   * @param convention market conventions
   * @param coupon annual coupon rate, decimal (0.04375 for 4 3/8)
   * @param datedDate date interest starts to accrue
   * @param maturity maturity date
   */
  public FixedRateBond(
      final String name,
      final BondConvention convention,
      final double coupon,
      final int datedDate,
      final int maturity) {
    this.name = name;
    this.convention = convention;
    this.coupon = coupon;
    this.datedDate = datedDate;
    this.maturity = maturity;
    final Schedule s =
        Schedule.generate(
            datedDate,
            maturity,
            convention.periodMonths(),
            convention.calendar(),
            BusinessDayConvention.UNADJUSTED,
            BusinessDayConvention.UNADJUSTED,
            StubRule.SHORT_FRONT,
            Dates.isEndOfMonth(maturity));
    final int n = s.periodCount();
    accrualStart = new int[n];
    accrualEnd = new int[n];
    refStart = new int[n];
    refEnd = new int[n];
    payNominal = new int[n];
    payAdjusted = new int[n];
    exDate = new int[n];
    amount = new double[n];
    for (int i = 0; i < n; i++) {
      accrualStart[i] = s.start(i);
      accrualEnd[i] = s.end(i);
      refStart[i] = s.referenceStart(i);
      refEnd[i] = s.referenceEnd(i);
      payNominal[i] = s.end(i);
      payAdjusted[i] =
          convention.calendar().adjust(s.end(i), BusinessDayConvention.FOLLOWING);
      exDate[i] =
          convention.exDividendDays() > 0
              ? convention.calendar().advanceBusinessDays(s.end(i), -convention.exDividendDays())
              : Integer.MAX_VALUE;
      amount[i] =
          100.0 * coupon
              * convention.dayCount().yearFraction(accrualStart[i], accrualEnd[i], refStart[i],
                  refEnd[i]);
    }
  }

  public String name() {
    return name;
  }

  public BondConvention convention() {
    return convention;
  }

  /** Annual coupon rate, decimal. */
  public double coupon() {
    return coupon;
  }

  public int datedDate() {
    return datedDate;
  }

  public int maturity() {
    return maturity;
  }

  /** Number of coupons. */
  public int couponCount() {
    return amount.length;
  }

  /** Nominal (unadjusted) date of coupon i. */
  public int couponDate(final int i) {
    return payNominal[i];
  }

  /** Coupon amount i per 100 face. */
  public double couponAmount(final int i) {
    return amount[i];
  }

  /** Ex-dividend date of coupon i, or {@link Integer#MAX_VALUE} if none. */
  public int exDividendDate(final int i) {
    return exDate[i];
  }

  /** Regular-way settlement date for a trade date. */
  public int settlementDate(final int tradeDate) {
    return convention.calendar().advanceBusinessDays(tradeDate, convention.settlementDays());
  }
}
