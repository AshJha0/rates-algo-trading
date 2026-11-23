package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The New York date cut: before and after 17:00, weekends, holidays and daylight saving. */
class BusinessDateTest {

  private static final ZoneId NY = BusinessDate.NEW_YORK;

  private static long newYork(final int y, final int m, final int d, final int h, final int min) {
    return LocalDateTime.of(y, m, d, h, min).atZone(NY).toEpochSecond() * 1_000_000_000L;
  }

  private static String date(final long nanos) {
    return Dates.format(BusinessDate.tradeDate(nanos, 17, Calendars.US_SOFR));
  }

  @Test
  void theDateChangesAtFiveInTheAfternoonNewYorkTime() {
    assertEquals("2026-11-02", date(newYork(2026, 11, 2, 9, 30)));
    assertEquals("2026-11-02", date(newYork(2026, 11, 2, 16, 59)));
    assertEquals("2026-11-02", date(newYork(2026, 11, 2, 17, 0) - 1));
    assertEquals("2026-11-03", date(newYork(2026, 11, 2, 17, 0)));
    assertEquals("2026-11-03", date(newYork(2026, 11, 2, 23, 59)));
    assertEquals("2026-11-03", date(newYork(2026, 11, 3, 0, 0)));
    // an instant that is already the next calendar day in UTC but before the cut in New York
    final long utcEvening = LocalDateTime.of(2026, 11, 3, 1, 0).toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L;
    assertEquals("2026-11-03", date(utcEvening), "20:00 Monday in New York: past the cut");
    final long utcAfternoon = LocalDateTime.of(2026, 11, 2, 21, 30).toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L;
    assertEquals("2026-11-02", date(utcAfternoon), "16:30 Monday in New York");
  }

  @Test
  void weekendsAndHolidaysGoToTheNextBusinessDay() {
    assertEquals("2026-11-09", date(newYork(2026, 11, 6, 17, 0)), "Friday evening");
    assertEquals("2026-11-09", date(newYork(2026, 11, 7, 12, 0)), "Saturday");
    assertEquals("2026-11-09", date(newYork(2026, 11, 8, 18, 0)), "Sunday evening");
    assertEquals("2026-11-12", date(newYork(2026, 11, 10, 17, 30)), "before Veterans Day");
    assertEquals("2026-11-27", date(newYork(2026, 11, 25, 17, 0)), "before Thanksgiving");
    assertEquals("2027-01-04", date(newYork(2026, 12, 31, 17, 0)), "New Year on a Friday");
  }

  @Test
  void theNextCutIsTheNextFiveOClockInNewYorkInWinterAndSummer() {
    // winter: 17:00 EST is 22:00 UTC
    final long winter = BusinessDate.nextCut(newYork(2026, 11, 2, 9, 30), 17);
    assertEquals(newYork(2026, 11, 2, 17, 0), winter);
    assertEquals(22, java.time.Instant.ofEpochSecond(winter / 1_000_000_000L).atZone(ZoneOffset.UTC).getHour());
    // summer: 17:00 EDT is 21:00 UTC
    final long summer = BusinessDate.nextCut(newYork(2026, 7, 1, 9, 30), 17);
    assertEquals(21, java.time.Instant.ofEpochSecond(summer / 1_000_000_000L).atZone(ZoneOffset.UTC).getHour());
    // at the cut itself the next cut is tomorrow's: strictly after
    assertEquals(newYork(2026, 11, 3, 17, 0), BusinessDate.nextCut(winter, 17));
    assertEquals(newYork(2026, 11, 3, 17, 0), BusinessDate.nextCut(winter + 1, 17));
    // across the end of daylight saving (1 November 2026) the day is 25 hours long
    final long saturday = BusinessDate.nextCut(newYork(2026, 10, 31, 17, 0), 17);
    assertEquals(newYork(2026, 11, 1, 17, 0), saturday);
    assertEquals(25L * 3600 * 1_000_000_000L, saturday - newYork(2026, 10, 31, 17, 0));
    // the date can only change at a cut: just before it the date is the old one
    assertTrue(BusinessDate.tradeDate(winter - 1, 17, Calendars.US_SOFR)
        < BusinessDate.tradeDate(winter, 17, Calendars.US_SOFR));
    assertEquals(BusinessDate.DEFAULT_CUT_HOUR, 17);
  }

  @Test
  void theCutHourMustBeInTheAfternoonOrEvening() {
    assertThrows(IllegalArgumentException.class, () -> BusinessDate.nextCut(0L, 2));
    assertThrows(IllegalArgumentException.class, () -> BusinessDate.nextCut(0L, 24));
    assertThrows(IllegalArgumentException.class,
        () -> BusinessDate.tradeDate(0L, 11, Calendars.US_SOFR));
    // a 15:00 cut, as for a futures settlement
    assertEquals("2026-11-03",
        Dates.format(BusinessDate.tradeDate(newYork(2026, 11, 2, 15, 0), 15, Calendars.US_SOFR)));
  }
}
