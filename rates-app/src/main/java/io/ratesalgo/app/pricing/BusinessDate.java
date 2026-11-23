package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.HolidayCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The trade date of an instant, with an explicit New York date cut.
 *
 * <p>Plain idea. A trade done at 16:59 in New York is today's business; one done at 17:01 is
 * tomorrow's. The date a desk prices and settles from is therefore not the calendar date of the
 * clock, in any time zone, but the calendar date in New York moved forward at the cut, and then
 * to a business day.
 *
 * <p>Formal statement. For an instant t and cut hour c: let d be the New York calendar date of t,
 * plus one day if the New York local time of t is at or after c:00. The trade date is d if d is a
 * business day of the calendar, otherwise the next business day. {@link #nextCut} is the first
 * instant strictly after t at which the New York local time is c:00, so the trade date can change
 * only there. Daylight saving is handled by the zone rules: the cut is 22:00 UTC in winter and
 * 21:00 UTC in summer for c = 17.
 *
 * <p>These methods use java.time and allocate. The pricing binding calls them once at start and
 * once per date change, and compares a long against {@link #nextCut} in between.
 */
public final class BusinessDate {

  /** The zone of the cut. */
  public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  /** The cut hour of the US Treasury and swap markets' trade date, New York time. */
  public static final int DEFAULT_CUT_HOUR = 17;

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private BusinessDate() {}

  private static ZonedDateTime newYork(final long epochNanos) {
    return Instant.ofEpochSecond(
            Math.floorDiv(epochNanos, NANOS_PER_SECOND), Math.floorMod(epochNanos, NANOS_PER_SECOND))
        .atZone(NEW_YORK);
  }

  /**
   * Trade date of an instant as analytics epoch days.
   *
   * @param epochNanos the instant
   * @param cutHour hour of the cut in New York, 12 to 23
   * @param calendar business days of the market
   */
  public static int tradeDate(
      final long epochNanos, final int cutHour, final HolidayCalendar calendar) {
    requireHour(cutHour);
    final ZonedDateTime t = newYork(epochNanos);
    LocalDate d = t.toLocalDate();
    if (t.getHour() >= cutHour) {
      d = d.plusDays(1);
    }
    return calendar.adjust(
        Dates.of(d.getYear(), d.getMonthValue(), d.getDayOfMonth()),
        BusinessDayConvention.FOLLOWING);
  }

  /** Epoch nanoseconds of the first cut strictly after the instant. */
  public static long nextCut(final long epochNanos, final int cutHour) {
    requireHour(cutHour);
    final ZonedDateTime t = newYork(epochNanos);
    ZonedDateTime cut = ZonedDateTime.of(t.toLocalDate(), LocalTime.of(cutHour, 0), NEW_YORK);
    if (!cut.isAfter(t)) {
      cut = ZonedDateTime.of(t.toLocalDate().plusDays(1), LocalTime.of(cutHour, 0), NEW_YORK);
    }
    return Math.addExact(
        Math.multiplyExact(cut.toEpochSecond(), NANOS_PER_SECOND), cut.getNano());
  }

  private static void requireHour(final int cutHour) {
    // the cut is in the afternoon or evening; hours 2 and 3 are excluded with the rest of the
    // night because daylight saving makes them ambiguous or absent
    if (cutHour < 12 || cutHour > 23) {
      throw new IllegalArgumentException("cut hour must be 12 to 23 New York time: " + cutHour);
    }
  }
}
