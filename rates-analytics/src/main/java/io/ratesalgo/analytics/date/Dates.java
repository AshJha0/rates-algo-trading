package io.ratesalgo.analytics.date;

import java.time.LocalDate;

/**
 * Calendar arithmetic on dates held as {@code int} days since 1970-01-01 (the epoch-day count used
 * by {@link LocalDate#toEpochDay()}).
 *
 * <p>Every method on this class is exact integer arithmetic on the proleptic Gregorian calendar
 * and allocates nothing, so it can run on pricing paths. {@link #fromLocalDate}, {@link
 * #toLocalDate}, {@link #parse} and {@link #format} use {@code java.time} and are for builders,
 * tests and messages only.
 *
 * <p>Months are 1 to 12. Days of the week are ISO numbers: 1 is Monday, 7 is Sunday.
 */
public final class Dates {

  public static final int MONDAY = 1;
  public static final int TUESDAY = 2;
  public static final int WEDNESDAY = 3;
  public static final int THURSDAY = 4;
  public static final int FRIDAY = 5;
  public static final int SATURDAY = 6;
  public static final int SUNDAY = 7;

  private static final int[] DAYS_BEFORE_MONTH = {0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273,
      304, 334};

  private Dates() {}

  /** Epoch day of the given year, month (1 to 12) and day of month. */
  public static int of(final int year, final int month, final int day) {
    if (month < 1 || month > 12 || day < 1 || day > lengthOfMonth(year, month)) {
      throw new IllegalArgumentException(
          "invalid date: year=" + year + " month=" + month + " day=" + day);
    }
    // days-from-civil, H. Hinnant, http://howardhinnant.github.io/date_algorithms.html
    final int y = month <= 2 ? year - 1 : year;
    final int era = Math.floorDiv(y, 400);
    final int yoe = y - era * 400;
    final int mp = (month + 9) % 12;
    final int doy = (153 * mp + 2) / 5 + day - 1;
    final int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146_097 + doe - 719_468;
  }

  /** Year of the epoch day. */
  public static int year(final int epochDay) {
    final int z = epochDay + 719_468;
    final int era = Math.floorDiv(z, 146_097);
    final int doe = z - era * 146_097;
    final int yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    final int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    final int mp = (5 * doy + 2) / 153;
    final int m = mp < 10 ? mp + 3 : mp - 9;
    final int y = yoe + era * 400;
    return m <= 2 ? y + 1 : y;
  }

  /** Month (1 to 12) of the epoch day. */
  public static int month(final int epochDay) {
    final int z = epochDay + 719_468;
    final int era = Math.floorDiv(z, 146_097);
    final int doe = z - era * 146_097;
    final int yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    final int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    final int mp = (5 * doy + 2) / 153;
    return mp < 10 ? mp + 3 : mp - 9;
  }

  /** Day of month (1 to 31) of the epoch day. */
  public static int dayOfMonth(final int epochDay) {
    final int z = epochDay + 719_468;
    final int era = Math.floorDiv(z, 146_097);
    final int doe = z - era * 146_097;
    final int yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    final int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    final int mp = (5 * doy + 2) / 153;
    return doy - (153 * mp + 2) / 5 + 1;
  }

  /** ISO day of week: 1 is Monday, 7 is Sunday. 1970-01-01 was a Thursday. */
  public static int dayOfWeek(final int epochDay) {
    return Math.floorMod(epochDay + 3, 7) + 1;
  }

  /** Day of the year, 1 for 1 January. */
  public static int dayOfYear(final int epochDay) {
    final int y = year(epochDay);
    return epochDay - of(y, 1, 1) + 1;
  }

  /** True on Saturday and Sunday. */
  public static boolean isWeekend(final int epochDay) {
    return dayOfWeek(epochDay) >= SATURDAY;
  }

  /** Gregorian leap-year rule. */
  public static boolean isLeap(final int year) {
    return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
  }

  /** Number of days in the month. */
  public static int lengthOfMonth(final int year, final int month) {
    if (month == 2) {
      return isLeap(year) ? 29 : 28;
    }
    return (month == 4 || month == 6 || month == 9 || month == 11) ? 30 : 31;
  }

  /** Number of days in the year: 365 or 366. */
  public static int lengthOfYear(final int year) {
    return isLeap(year) ? 366 : 365;
  }

  /** Last calendar day of the month containing the epoch day. */
  public static int endOfMonth(final int epochDay) {
    final int y = year(epochDay);
    final int m = month(epochDay);
    return of(y, m, lengthOfMonth(y, m));
  }

  /** True if the epoch day is the last calendar day of its month. */
  public static boolean isEndOfMonth(final int epochDay) {
    return dayOfMonth(epochDay + 1) == 1;
  }

  /**
   * Adds whole months, clamping the day of month to the length of the target month (31 January
   * plus one month is 28 or 29 February). This is the rule QuantLib's {@code Date + n*Months} uses.
   */
  public static int addMonths(final int epochDay, final int months) {
    final int y = year(epochDay);
    final int m = month(epochDay);
    final int d = dayOfMonth(epochDay);
    final int total = y * 12 + (m - 1) + months;
    final int ny = Math.floorDiv(total, 12);
    final int nm = Math.floorMod(total, 12) + 1;
    return of(ny, nm, Math.min(d, lengthOfMonth(ny, nm)));
  }

  /** Adds whole years with the same clamping rule as {@link #addMonths}. */
  public static int addYears(final int epochDay, final int years) {
    return addMonths(epochDay, 12 * years);
  }

  /**
   * Day of the year of Easter Monday in the Gregorian calendar (anonymous Gregorian algorithm,
   * Meeus). Good Friday is three days earlier.
   */
  public static int easterMondayDayOfYear(final int year) {
    final int a = year % 19;
    final int b = year / 100;
    final int c = year % 100;
    final int d = b / 4;
    final int e = b % 4;
    final int f = (b + 8) / 25;
    final int g = (b - f + 1) / 3;
    final int h = (19 * a + b - d - g + 15) % 30;
    final int i = c / 4;
    final int k = c % 4;
    final int l = (32 + 2 * e + 2 * i - h - k) % 7;
    final int m = (a + 11 * h + 22 * l) / 451;
    final int month = (h + l - 7 * m + 114) / 31;
    final int day = ((h + l - 7 * m + 114) % 31) + 1;
    return DAYS_BEFORE_MONTH[month] + (month > 2 && isLeap(year) ? 1 : 0) + day + 1;
  }

  /** The n-th (1-based) given weekday of a month, for example the third Wednesday. */
  public static int nthWeekday(final int n, final int dayOfWeek, final int year, final int month) {
    if (n < 1 || n > 5) {
      throw new IllegalArgumentException("n must be 1 to 5, got " + n);
    }
    final int first = of(year, month, 1);
    final int offset = Math.floorMod(dayOfWeek - dayOfWeek(first), 7);
    final int result = first + offset + 7 * (n - 1);
    if (month(result) != month) {
      throw new IllegalArgumentException(
          "no " + n + "th weekday " + dayOfWeek + " in " + year + "-" + month);
    }
    return result;
  }

  /** Converts from {@link LocalDate}; for builders and tests, not pricing paths. */
  public static int fromLocalDate(final LocalDate date) {
    return Math.toIntExact(date.toEpochDay());
  }

  /** Converts to {@link LocalDate}; for builders and tests, not pricing paths. */
  public static LocalDate toLocalDate(final int epochDay) {
    return LocalDate.ofEpochDay(epochDay);
  }

  /** Parses an ISO date such as {@code 2026-10-30}; for builders and tests. */
  public static int parse(final String iso) {
    return fromLocalDate(LocalDate.parse(iso));
  }

  /** Formats as an ISO date; for messages and tests. */
  public static String format(final int epochDay) {
    return LocalDate.ofEpochDay(epochDay).toString();
  }
}
