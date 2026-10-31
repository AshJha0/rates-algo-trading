package io.ratesalgo.analytics.bond;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.HolidayCalendar;

/**
 * Market conventions of a fixed-coupon government bond. API_SPEC.md Section 5.4 cites the
 * sources.
 *
 * @param name identifier
 * @param currency ISO 4217 code
 * @param calendar settlement and payment calendar
 * @param settlementDays regular-way settlement lag in business days
 * @param periodMonths coupon period: 6 semi-annual, 12 annual
 * @param dayCount accrual day count
 * @param exDividendDays business days before the coupon date at which the bond goes ex-dividend
 *     (0 for none)
 */
public record BondConvention(
    String name,
    String currency,
    HolidayCalendar calendar,
    int settlementDays,
    int periodMonths,
    DayCount dayCount,
    int exDividendDays) {

  /**
   * US Treasury notes and bonds: semi-annual Act/Act (ICMA), T+1 on the SIFMA calendar, coupon
   * dates unadjusted, payment on the next business day (Treasury, 31 CFR Part 356 Appendix B;
   * SIFMA settlement guidelines).
   */
  public static final BondConvention UST =
      new BondConvention("UST", "USD", Calendars.US_SIFMA, 1, 6, DayCount.ACT_ACT_ICMA, 0);

  /** German Bunds: annual Act/Act (ICMA), T+2 on TARGET (Finanzagentur issuance terms). */
  public static final BondConvention BUND =
      new BondConvention("BUND", "EUR", Calendars.TARGET, 2, 12, DayCount.ACT_ACT_ICMA, 0);

  /**
   * UK Gilts: semi-annual Act/Act (ICMA), T+1 on London, ex-dividend seven business days before
   * each coupon date, when accrued interest becomes negative (UK DMO, "Formulae for Calculating
   * Gilt Prices from Yields", 3rd edition 2005, and the Gilt Market Guide).
   */
  public static final BondConvention GILT =
      new BondConvention("GILT", "GBP", Calendars.LONDON, 1, 6, DayCount.ACT_ACT_ICMA, 7);

  /** Coupons per year. */
  public int frequency() {
    return 12 / periodMonths;
  }
}
