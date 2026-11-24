package io.ratesalgo.analytics.date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The meeting list is ordered, on Wednesdays, and its effective dates are the next business day. */
class FomcCalendarTest {

  @Test
  void decisionDaysAreWednesdaysInOrderAsPublished() {
    // eight a year in 2025 and 2026; seven policy meetings in 2027 (strategy meeting left out)
    assertEquals(23, FomcCalendar.size());
    assertEquals("2027-11-17", Dates.format(FomcCalendar.decisionDay(FomcCalendar.size() - 1)));
    for (int i = 0; i < FomcCalendar.size(); i++) {
      final int d = FomcCalendar.decisionDay(i);
      assertEquals(Dates.WEDNESDAY, Dates.dayOfWeek(d), Dates.format(d));
      if (i > 0) {
        // scheduled meetings are five to nine weeks apart (September to November 2027 is nine)
        final int gap = d - FomcCalendar.decisionDay(i - 1);
        assertTrue(gap >= 35 && gap <= 63, Dates.format(d) + " gap " + gap);
      }
    }
  }

  @Test
  void effectiveDatesAreTheBusinessDayAfterTheDecision() {
    final int[] out = new int[3];
    final int n = FomcCalendar.effectiveDatesAfter(Dates.of(2026, 11, 2), Calendars.US_SOFR, out);
    assertEquals(3, n);
    assertEquals("2026-12-10", Dates.format(out[0]));
    assertEquals("2027-01-28", Dates.format(out[1]));
    assertEquals("2027-03-18", Dates.format(out[2]));
    // on an effective date itself the next one is returned
    final int[] one = new int[1];
    assertEquals(1, FomcCalendar.effectiveDatesAfter(out[0], Calendars.US_SOFR, one));
    assertEquals(out[1], one[0]);
    // the list runs out at the end of 2027
    assertEquals(0, FomcCalendar.effectiveDatesAfter(Dates.of(2027, 11, 18), Calendars.US_SOFR, out));
    assertEquals(1, FomcCalendar.effectiveDatesAfter(Dates.of(2027, 11, 17), Calendars.US_SOFR, out));
    assertEquals("2027-11-18", Dates.format(out[0]));
  }
}
