package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.refdata.FixtureCalendarSource;
import io.ratesalgo.refdata.RefData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One calendar (review 2026-11-23, A6). The venues, settlement and the securities master read the
 * holiday fixture; the pricing reads the rule-based calendars of {@code rates-analytics}. This
 * test walks every day of the years the fixture covers and fails on any day the two classify
 * differently, so a settlement date and an accrual date can never be computed on different
 * calendars without the build saying so. It lives here because {@code rates-app} is the first
 * module that sees both.
 *
 * <p>The disagreement that prompted it: Good Friday 2026-04-03 was a holiday in the fixture and a
 * business day in the analytics. SIFMA recommends an early close at 12:00 Eastern that day, not a
 * full close (sifma.org holiday schedule, read 2026-11-23), so the analytics rule was right and
 * the fixture row was removed.
 */
class CalendarConsistencyTest {

  /** Fixture calendar name to the analytics calendar that must agree with it. */
  private static final Map<String, HolidayCalendar> PAIRS =
      Map.of("US-GS", Calendars.US_SIFMA, "UK", Calendars.LONDON, "TARGET", Calendars.TARGET);

  @Test
  void theFixtureAndTheAnalyticsCalendarsAgreeOnEveryDayTheFixtureCovers() {
    final FixtureCalendarSource fixture = (FixtureCalendarSource) RefData.loadDefault().calendars();
    final List<String> differences = new ArrayList<>();
    int checked = 0;
    for (final Map.Entry<String, HolidayCalendar> pair : PAIRS.entrySet()) {
      assertTrue(fixture.knows(pair.getKey()), pair.getKey());
      LocalDate d = LocalDate.of(fixture.firstYear(), 1, 1);
      final LocalDate end = LocalDate.of(fixture.lastYear(), 12, 31);
      for (; !d.isAfter(end); d = d.plusDays(1)) {
        final boolean inFixture = fixture.isBusinessDay(pair.getKey(), d);
        final boolean inAnalytics = pair.getValue().isBusinessDay(Dates.fromLocalDate(d));
        if (inFixture != inAnalytics) {
          differences.add(pair.getKey() + " " + d + " " + d.getDayOfWeek() + ": fixture "
              + (inFixture ? "business day" : "holiday") + ", " + pair.getValue().name() + " "
              + (inAnalytics ? "business day" : "holiday"));
        }
        checked++;
      }
    }
    assertTrue(differences.isEmpty(),
        "holidays.csv and the rates-analytics calendars disagree; decide which is right from the"
            + " source named in data/refdata/README.md and correct the other:\n"
            + String.join("\n", differences));
    // three calendars over 2026 and 2027
    assertEquals(3 * (365 + 365), checked);
  }

  @Test
  void goodFriday2026IsABusinessDayOnBothAndSofrIsStillNotPublished() {
    final LocalDate goodFriday = LocalDate.of(2026, 4, 3);
    assertTrue(RefData.loadDefault().calendars().isBusinessDay("US-GS", goodFriday));
    assertTrue(Calendars.US_SIFMA.isBusinessDay(Dates.fromLocalDate(goodFriday)));
    // the fixing calendar is a different set: no SOFR on any Good Friday
    assertTrue(Calendars.US_SOFR.isHoliday(Dates.fromLocalDate(goodFriday)));
  }
}
