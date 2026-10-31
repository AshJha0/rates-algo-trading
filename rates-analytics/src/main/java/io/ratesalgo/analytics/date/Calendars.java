package io.ratesalgo.analytics.date;

import java.util.List;

/**
 * The calendars the analytics use, with their sources.
 *
 * <ul>
 *   <li>{@link #US_SIFMA}: US government securities business days, the SIFMA recommendation
 *       (sifma.org, holiday schedule). Same rule as QuantLib 1.44 {@code UnitedStates(GovernmentBond)}:
 *       Good Friday is a holiday except, from 1996, when it falls on the first seven days of a
 *       month, when SIFMA recommends an early close for the employment report.
 *   <li>{@link #US_SOFR}: days on which the New York Fed publishes SOFR. As {@code US_SIFMA} but
 *       every Good Friday is excluded (QuantLib {@code UnitedStates(SOFR)}). This is the SOFR
 *       fixing calendar.
 *   <li>{@link #US_FED}: Federal Reserve Bank holidays (federalreserve.gov, holidays observed);
 *       a holiday on Saturday is not moved to the Friday. QuantLib {@code
 *       UnitedStates(FederalReserve)}.
 *   <li>{@link #US_NY_SWAP}: the joint {@code US_FED} and {@code US_SIFMA} calendar used for USD
 *       SOFR swap accrual and payment dates.
 *   <li>{@link #TARGET}: TARGET2 closing days (ECB), the EUR STR fixing and EUR settlement
 *       calendar. QuantLib {@code TARGET}.
 *   <li>{@link #LONDON}: England and Wales bank holidays (gov.uk/bank-holidays), the SONIA fixing
 *       and GBP settlement calendar. QuantLib {@code UnitedKingdom(Settlement)}.
 *   <li>{@link #NONE}: every day is a business day (QuantLib {@code NullCalendar}).
 * </ul>
 *
 * <p>One-off closures announced at short notice are ad hoc changes on a {@link
 * VersionedCalendar} with the announcement date, so that the calendar as known on any past day can
 * be recovered. The standing rules otherwise match QuantLib 1.44, and {@code CalendarGoldenTest}
 * checks every day from 2000 to 2060 against holiday lists exported from QuantLib.
 */
public final class Calendars {

  private Calendars() {}

  private static final int JAN = 1;
  private static final int FEB = 2;
  private static final int MAY = 5;
  private static final int JUN = 6;
  private static final int JUL = 7;
  private static final int AUG = 8;
  private static final int SEP = 9;
  private static final int OCT = 10;
  private static final int NOV = 11;
  private static final int DEC = 12;

  static boolean washingtonBirthday(final int y, final int m, final int d, final int w) {
    if (y >= 1971) {
      return d >= 15 && d <= 21 && w == Dates.MONDAY && m == FEB;
    }
    return (d == 22 || (d == 23 && w == Dates.MONDAY) || (d == 21 && w == Dates.FRIDAY))
        && m == FEB;
  }

  static boolean memorialDay(final int y, final int m, final int d, final int w) {
    if (y >= 1971) {
      return d >= 25 && w == Dates.MONDAY && m == MAY;
    }
    return (d == 30 || (d == 31 && w == Dates.MONDAY) || (d == 29 && w == Dates.FRIDAY))
        && m == MAY;
  }

  static boolean laborDay(final int m, final int d, final int w) {
    return d <= 7 && w == Dates.MONDAY && m == SEP;
  }

  static boolean columbusDay(final int y, final int m, final int d, final int w) {
    return d >= 8 && d <= 14 && w == Dates.MONDAY && m == OCT && y >= 1971;
  }

  static boolean veteransDayNoSaturday(final int y, final int m, final int d, final int w) {
    if (y <= 1970 || y >= 1978) {
      return (d == 11 || (d == 12 && w == Dates.MONDAY)) && m == NOV;
    }
    return d >= 22 && d <= 28 && w == Dates.MONDAY && m == OCT;
  }

  static boolean juneteenth(
      final int y, final int m, final int d, final int w, final boolean moveToFriday) {
    return (d == 19 || (d == 20 && w == Dates.MONDAY) || (moveToFriday && d == 18
            && w == Dates.FRIDAY))
        && m == JUN
        && y >= 2022;
  }

  static boolean thanksgiving(final int m, final int d, final int w) {
    return d >= 22 && d <= 28 && w == Dates.THURSDAY && m == NOV;
  }

  static boolean mlk(final int y, final int m, final int d, final int w) {
    return d >= 15 && d <= 21 && w == Dates.MONDAY && m == JAN && y >= 1983;
  }

  private static final VersionedCalendar.Rule SIFMA_RULE =
      (y, m, d, w, dd, em) ->
          ((d == 1 || (d == 2 && w == Dates.MONDAY)) && m == JAN)
              || mlk(y, m, d, w)
              || washingtonBirthday(y, m, d, w)
              || (dd == em - 3 && (y < 1996 || d > 7))
              || memorialDay(y, m, d, w)
              || juneteenth(y, m, d, w, true)
              || ((d == 4 || (d == 5 && w == Dates.MONDAY) || (d == 3 && w == Dates.FRIDAY))
                  && m == JUL)
              || laborDay(m, d, w)
              || columbusDay(y, m, d, w)
              || veteransDayNoSaturday(y, m, d, w)
              || thanksgiving(m, d, w)
              || ((d == 25 || (d == 26 && w == Dates.MONDAY) || (d == 24 && w == Dates.FRIDAY))
                  && m == DEC);

  private static final List<VersionedCalendar.AdHoc> SIFMA_AD_HOC =
      List.of(
          new VersionedCalendar.AdHoc(
              Dates.of(2004, 6, 11), true, Dates.of(2004, 6, 7), "funeral of President Reagan"),
          new VersionedCalendar.AdHoc(
              Dates.of(2012, 10, 30), true, Dates.of(2012, 10, 29), "Hurricane Sandy"),
          new VersionedCalendar.AdHoc(
              Dates.of(2018, 12, 5), true, Dates.of(2018, 12, 1),
              "national day of mourning, President G. H. W. Bush"));

  /** US government securities business days (SIFMA). */
  public static final VersionedCalendar US_SIFMA =
      new VersionedCalendar("US-SIFMA", SIFMA_RULE, SIFMA_AD_HOC);

  /** SOFR publication days: SIFMA days without any Good Friday. */
  public static final VersionedCalendar US_SOFR =
      new VersionedCalendar(
          "US-SOFR", (y, m, d, w, dd, em) -> dd == em - 3 || SIFMA_RULE.isHoliday(y, m, d, w, dd, em),
          SIFMA_AD_HOC);

  /** Federal Reserve Bank holidays. */
  public static final VersionedCalendar US_FED =
      new VersionedCalendar(
          "US-FED",
          (y, m, d, w, dd, em) ->
              ((d == 1 || (d == 2 && w == Dates.MONDAY)) && m == JAN)
                  || mlk(y, m, d, w)
                  || washingtonBirthday(y, m, d, w)
                  || memorialDay(y, m, d, w)
                  || juneteenth(y, m, d, w, false)
                  || ((d == 4 || (d == 5 && w == Dates.MONDAY)) && m == JUL)
                  || laborDay(m, d, w)
                  || columbusDay(y, m, d, w)
                  || veteransDayNoSaturday(y, m, d, w)
                  || thanksgiving(m, d, w)
                  || ((d == 25 || (d == 26 && w == Dates.MONDAY)) && m == DEC),
          List.of());

  /** USD SOFR swap accrual and payment calendar: New York Fed and SIFMA. */
  public static final HolidayCalendar US_NY_SWAP = new JointCalendar(US_FED, US_SIFMA);

  /** TARGET2 closing days. */
  public static final VersionedCalendar TARGET =
      new VersionedCalendar(
          "TARGET",
          (y, m, d, w, dd, em) ->
              (d == 1 && m == JAN)
                  || (dd == em - 3 && y >= 2000)
                  || (dd == em && y >= 2000)
                  || (d == 1 && m == MAY && y >= 2000)
                  || (d == 25 && m == DEC)
                  || (d == 26 && m == DEC && y >= 2000)
                  || (d == 31 && m == DEC && (y == 1998 || y == 1999 || y == 2001)),
          List.of());

  /** England and Wales bank holidays. */
  public static final VersionedCalendar LONDON =
      new VersionedCalendar(
          "LONDON",
          (y, m, d, w, dd, em) ->
              ((d == 1 || ((d == 2 || d == 3) && w == Dates.MONDAY)) && m == JAN)
                  || dd == em - 3
                  || dd == em
                  // Early May bank holiday, moved to 8 May in 1995 and 2020 for VE day
                  || (d <= 7 && w == Dates.MONDAY && m == MAY && y != 1995 && y != 2020)
                  || (d == 8 && m == MAY && (y == 1995 || y == 2020))
                  // Spring bank holiday, moved for the 2002, 2012 and 2022 jubilees
                  || (d >= 25 && w == Dates.MONDAY && m == MAY && y != 2002 && y != 2012
                      && y != 2022)
                  || ((d == 3 || d == 4) && m == JUN && y == 2002)
                  || ((d == 4 || d == 5) && m == JUN && y == 2012)
                  || ((d == 2 || d == 3) && m == JUN && y == 2022)
                  || (d >= 25 && w == Dates.MONDAY && m == AUG)
                  || ((d == 25 || (d == 27 && (w == Dates.MONDAY || w == Dates.TUESDAY)))
                      && m == DEC)
                  || ((d == 26 || (d == 28 && (w == Dates.MONDAY || w == Dates.TUESDAY)))
                      && m == DEC)
                  || (d == 31 && m == DEC && y == 1999),
          List.of(
              new VersionedCalendar.AdHoc(
                  Dates.of(2011, 4, 29), true, Dates.of(2010, 11, 23), "Royal Wedding"),
              new VersionedCalendar.AdHoc(
                  Dates.of(2022, 9, 19), true, Dates.of(2022, 9, 10),
                  "State Funeral of Queen Elizabeth II"),
              new VersionedCalendar.AdHoc(
                  Dates.of(2023, 5, 8), true, Dates.of(2022, 11, 6),
                  "Coronation of King Charles III")));

  /** Every day is a business day. */
  public static final HolidayCalendar NONE =
      new HolidayCalendar() {
        @Override
        public String name() {
          return "NONE";
        }

        @Override
        public boolean isBusinessDay(final int epochDay) {
          return true;
        }
      };

  /** Looks a calendar up by its {@link HolidayCalendar#name()}; joint names use {@code +}. */
  public static HolidayCalendar byName(final String name) {
    if (name.indexOf('+') >= 0) {
      final String[] parts = name.split("\\+", -1);
      final HolidayCalendar[] members = new HolidayCalendar[parts.length];
      for (int i = 0; i < parts.length; i++) {
        members[i] = byName(parts[i]);
      }
      return new JointCalendar(members);
    }
    switch (name) {
      case "US-SIFMA":
        return US_SIFMA;
      case "US-SOFR":
        return US_SOFR;
      case "US-FED":
        return US_FED;
      case "TARGET":
        return TARGET;
      case "LONDON":
        return LONDON;
      case "NONE":
        return NONE;
      default:
        throw new IllegalArgumentException("unknown calendar " + name);
    }
  }
}
