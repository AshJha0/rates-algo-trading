package io.ratesalgo.analytics.swap;

import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.HolidayCalendar;

/**
 * Market conventions of an overnight index swap. API_SPEC.md Section 5.3 lists the three
 * standard ones with their sources.
 *
 * @param name identifier, for example {@code USD-SOFR-OIS}
 * @param currency ISO 4217 code
 * @param scheduleCalendar calendar for accrual dates and the spot date
 * @param paymentCalendar calendar for payment dates (after the payment lag)
 * @param fixingCalendar publication days of the overnight index; daily compounding steps over it
 * @param fixedDayCount fixed leg accrual
 * @param floatDayCount overnight index day count
 * @param fixedPeriodMonths fixed leg period
 * @param floatPeriodMonths floating leg period
 * @param convention accrual date adjustment (also used for the termination date)
 * @param spotLag business days from trade date to spot (start) date on the schedule calendar
 * @param paymentLag business days from period end to payment on the payment calendar
 * @param lookbackDays observation lookback in fixing-calendar business days (0 for none)
 * @param lockoutDays rate cut-off: the last {@code lockoutDays} fixings repeat the one before
 * @param observationShift weight daily rates by the shifted observation period, not the interest
 *     period (only meaningful with a lookback)
 */
public record SwapConvention(
    String name,
    String currency,
    HolidayCalendar scheduleCalendar,
    HolidayCalendar paymentCalendar,
    HolidayCalendar fixingCalendar,
    DayCount fixedDayCount,
    DayCount floatDayCount,
    int fixedPeriodMonths,
    int floatPeriodMonths,
    BusinessDayConvention convention,
    int spotLag,
    int paymentLag,
    int lookbackDays,
    int lockoutDays,
    boolean observationShift) {

  /**
   * USD SOFR OIS: annual both legs, Act/360, modified following on the joint New York Fed and
   * SIFMA calendar, T+2 spot, payment two business days after each period end, SOFR compounded
   * in arrears over SOFR publication days (ISDA 2021 Definitions, "USD-SOFR-OIS Compound"; ARRC
   * SOFR swap conventions).
   */
  public static final SwapConvention USD_SOFR =
      new SwapConvention(
          "USD-SOFR-OIS",
          "USD",
          Calendars.US_NY_SWAP,
          Calendars.US_NY_SWAP,
          Calendars.US_SOFR,
          DayCount.ACT_360,
          DayCount.ACT_360,
          12,
          12,
          BusinessDayConvention.MODIFIED_FOLLOWING,
          2,
          2,
          0,
          0,
          false);

  /**
   * EUR EUR STR OIS: annual both legs, Act/360, modified following on TARGET, T+2 spot, payment
   * one business day after period end (ISDA 2021, "EUR-EuroSTR-OIS Compound").
   */
  public static final SwapConvention EUR_ESTR =
      new SwapConvention(
          "EUR-ESTR-OIS",
          "EUR",
          Calendars.TARGET,
          Calendars.TARGET,
          Calendars.TARGET,
          DayCount.ACT_360,
          DayCount.ACT_360,
          12,
          12,
          BusinessDayConvention.MODIFIED_FOLLOWING,
          2,
          1,
          0,
          0,
          false);

  /**
   * GBP SONIA OIS: annual both legs, Act/365F, modified following on London, T+0 spot, no payment
   * lag (ISDA 2021, "GBP-SONIA-OIS Compound"; Bank of England Working Group on Sterling Risk-Free
   * Reference Rates conventions).
   */
  public static final SwapConvention GBP_SONIA =
      new SwapConvention(
          "GBP-SONIA-OIS",
          "GBP",
          Calendars.LONDON,
          Calendars.LONDON,
          Calendars.LONDON,
          DayCount.ACT_365F,
          DayCount.ACT_365F,
          12,
          12,
          BusinessDayConvention.MODIFIED_FOLLOWING,
          0,
          0,
          0,
          0,
          false);

  /** This convention with a lookback, lockout and observation shift changed. */
  public SwapConvention withObservation(
      final int lookback, final int lockout, final boolean shift) {
    return new SwapConvention(
        name + (lookback > 0 ? "-LB" + lookback : "") + (lockout > 0 ? "-LO" + lockout : "")
            + (shift ? "-OS" : ""),
        currency,
        scheduleCalendar,
        paymentCalendar,
        fixingCalendar,
        fixedDayCount,
        floatDayCount,
        fixedPeriodMonths,
        floatPeriodMonths,
        convention,
        spotLag,
        paymentLag,
        lookback,
        lockout,
        shift);
  }

  /** The spot date for a trade date. */
  public int spotDate(final int tradeDate) {
    return scheduleCalendar.advanceBusinessDays(tradeDate, spotLag);
  }

  /** Looks a convention up by currency code. */
  public static SwapConvention forCurrency(final String ccy) {
    switch (ccy) {
      case "USD":
        return USD_SOFR;
      case "EUR":
        return EUR_ESTR;
      case "GBP":
        return GBP_SONIA;
      default:
        throw new IllegalArgumentException("no OIS convention for " + ccy);
    }
  }
}
