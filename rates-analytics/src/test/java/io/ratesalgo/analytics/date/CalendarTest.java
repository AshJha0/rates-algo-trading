package io.ratesalgo.analytics.date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarTest {

  private static int d(final String iso) {
    return Dates.parse(iso);
  }

  @Test
  void ukDayOfMourningIsAnAdHocHolidayKnownFromItsAnnouncement() {
    final int funeral = d("2022-09-19");
    assertFalse(Calendars.LONDON.isBusinessDay(funeral));
    assertTrue(Calendars.LONDON.asOf(d("2022-09-09")).isBusinessDay(funeral),
        "before the announcement 2022-09-19 was a business day");
    assertFalse(Calendars.LONDON.asOf(d("2022-09-10")).isBusinessDay(funeral));
    assertEquals(d("2022-09-10"), Calendars.LONDON.asOf(d("2022-09-10")).knowledgeDate());
    assertTrue(Calendars.LONDON.toString().equals("LONDON"));
    assertTrue(Calendars.LONDON.asOf(d("2022-09-09")).toString().contains("as of 2022-09-09"));
    // a settlement computed before the announcement and after it differ
    final int trade = d("2022-09-16");
    assertEquals(d("2022-09-19"), Calendars.LONDON.asOf(d("2022-09-09")).advanceBusinessDays(
        trade, 1));
    assertEquals(d("2022-09-20"), Calendars.LONDON.advanceBusinessDays(trade, 1));
  }

  @Test
  void adHocChangesCanBeAddedWithoutRewritingHistory() {
    final VersionedCalendar.AdHoc closure =
        new VersionedCalendar.AdHoc(d("2030-06-12"), true, d("2030-05-01"), "test closure");
    final VersionedCalendar cal = Calendars.TARGET.withAdHoc(closure);
    assertFalse(cal.isBusinessDay(d("2030-06-12")));
    assertTrue(cal.asOf(d("2030-04-30")).isBusinessDay(d("2030-06-12")));
    assertTrue(Calendars.TARGET.isBusinessDay(d("2030-06-12")), "the original is unchanged");
    assertEquals(1, cal.adHocChanges().size());
    final VersionedCalendar opened = Calendars.TARGET.withAdHoc(
        new VersionedCalendar.AdHoc(d("2030-12-25"), false, d("2030-01-01"), "test opening"));
    assertTrue(opened.isBusinessDay(d("2030-12-25")));
    assertThrows(IllegalArgumentException.class,
        () -> new VersionedCalendar.AdHoc(d("2030-01-01"), true, d("2030-02-01"), "late"));
    // outside the precomputed range the rule is evaluated directly
    assertFalse(Calendars.TARGET.isBusinessDay(d("2200-12-25")));
    assertTrue(Calendars.TARGET.isBusinessDay(d("1900-01-02")));
  }

  @Test
  void sofrFixingCalendarDiffersFromPaymentCalendarOnEarlyCloseGoodFriday() {
    final int gf2023 = d("2023-04-07");
    assertTrue(Calendars.US_SIFMA.isBusinessDay(gf2023), "SIFMA early close, not a holiday");
    assertFalse(Calendars.US_SOFR.isBusinessDay(gf2023), "SOFR not published");
    assertTrue(Calendars.US_NY_SWAP.isBusinessDay(gf2023));
    assertFalse(Calendars.US_SIFMA.isBusinessDay(d("2024-03-29")));
    assertFalse(Calendars.US_FED.isBusinessDay(d("2024-06-19")));
    assertTrue(Calendars.US_FED.isBusinessDay(d("2024-03-29")));
  }

  @Test
  void jointCalendarAndLookup() {
    final HolidayCalendar joint = Calendars.byName("TARGET+LONDON");
    assertEquals("TARGET+LONDON", joint.name());
    assertEquals("TARGET+LONDON", joint.toString());
    assertFalse(joint.isBusinessDay(d("2024-05-27")), "UK spring bank holiday");
    assertFalse(joint.isBusinessDay(d("2024-05-01")), "TARGET labour day");
    assertTrue(joint.isBusinessDay(d("2024-05-02")));
    assertSame(Calendars.US_SOFR, Calendars.byName("US-SOFR"));
    assertSame(Calendars.US_FED, Calendars.byName("US-FED"));
    assertSame(Calendars.US_SIFMA, Calendars.byName("US-SIFMA"));
    assertSame(Calendars.NONE, Calendars.byName("NONE"));
    assertEquals("NONE", Calendars.NONE.name());
    assertThrows(IllegalArgumentException.class, () -> Calendars.byName("MARS"));
    assertThrows(IllegalArgumentException.class, JointCalendar::new);
  }

  @Test
  void businessDayArithmetic() {
    final HolidayCalendar c = Calendars.US_SIFMA;
    assertEquals(d("2024-12-31"), c.endOfMonth(d("2024-12-10")));
    assertTrue(c.isEndOfMonth(d("2024-11-29")));
    assertEquals(d("2024-11-27"), c.advanceBusinessDays(d("2024-12-02"), -2));
    assertEquals(d("2024-12-02"), c.advanceBusinessDays(d("2024-11-30"), 0,
        BusinessDayConvention.FOLLOWING));
    assertEquals(-3, c.businessDaysBetween(d("2024-12-05"), d("2024-12-02")));
    assertEquals(d("2025-02-28"), c.advanceMonths(d("2024-11-29"), 3,
        BusinessDayConvention.MODIFIED_FOLLOWING, true));
    assertEquals(d("2025-02-28"), Calendars.NONE.advanceMonths(d("2024-11-30"), 3,
        BusinessDayConvention.UNADJUSTED, true));
    assertEquals(d("2024-11-29"), c.advanceMonths(d("2024-11-29"), 0,
        BusinessDayConvention.FOLLOWING, false));
    assertTrue(c.isHoliday(d("2024-12-25")));
  }

  @Test
  void immDatesAndHelpers() {
    assertEquals(d("2025-03-19"), ImmDates.next(d("2025-01-17")));
    assertEquals(d("2025-06-18"), ImmDates.next(d("2025-03-19")));
    assertEquals(d("2025-03-19"), ImmDates.nextOrSame(d("2025-03-19")));
    assertEquals(d("2026-03-18"), ImmDates.next(d("2025-12-17")));
    assertTrue(ImmDates.isImm(d("2025-09-17")));
    assertFalse(ImmDates.isImm(d("2025-10-15")));
    assertThrows(IllegalArgumentException.class, () -> Dates.nthWeekday(5, Dates.MONDAY, 2025, 2));
    assertThrows(IllegalArgumentException.class, () -> Dates.nthWeekday(0, Dates.MONDAY, 2025, 2));
    assertThrows(IllegalArgumentException.class, () -> Dates.of(2025, 2, 29));
    assertEquals("2025-02-28", Dates.format(Dates.addYears(d("2024-02-29"), 1)));
    assertEquals(d("2024-02-29"), Dates.fromLocalDate(Dates.toLocalDate(d("2024-02-29"))));
    assertEquals(366, Dates.lengthOfYear(2024));
    assertTrue(Dates.isWeekend(d("2025-01-18")));
  }

  @Test
  void dayCountKnownValues() {
    // 30/360 US end-of-February rules
    assertEquals(30, DayCount.THIRTY_360_US.dayCount(d("2024-02-29"), d("2024-03-31")));
    assertEquals(360, DayCount.THIRTY_360_US.dayCount(d("2023-02-28"), d("2024-02-29")));
    assertEquals(30, DayCount.THIRTY_360_US.dayCount(d("2024-01-31"), d("2024-02-29")) + 1);
    assertEquals(30, DayCount.THIRTY_E_360.dayCount(d("2024-03-31"), d("2024-04-30")));
    assertEquals(0.5 * 60.0 / 182.0, DayCount.ACT_ACT_ICMA.yearFraction(
        d("2024-01-15"), d("2024-03-15"), d("2024-01-15"), d("2024-07-15")), 1e-15);
    // long first coupon: split at the notional coupon date
    final double longFirst = DayCount.ACT_ACT_ICMA.yearFraction(
        d("2024-01-02"), d("2024-08-15"), d("2024-02-15"), d("2024-08-15"));
    assertEquals(0.5 * 44.0 / 184.0 + 0.5, longFirst, 1e-14);
    // a date past the reference end adds whole periods
    final double past = DayCount.ACT_ACT_ICMA.yearFraction(
        d("2024-02-15"), d("2025-03-15"), d("2024-02-15"), d("2024-08-15"));
    assertEquals(1.0 + 0.5 * 28.0 / 181.0, past, 1e-12);
    assertEquals(0.0, DayCount.ACT_ACT_ICMA.yearFraction(d("2024-02-15"), d("2024-02-15")));
    assertTrue(DayCount.ACT_ACT_ICMA.yearFraction(d("2024-03-01"), d("2024-02-01"),
        d("2024-01-01"), d("2024-07-01")) < 0);
    assertThrows(IllegalArgumentException.class, () -> DayCount.ACT_ACT_ICMA.yearFraction(
        d("2024-03-01"), d("2024-03-05"), d("2024-02-01"), d("2024-01-01")));
    assertEquals(0.0, DayCount.ACT_ACT_ISDA.yearFraction(d("2024-03-01"), d("2024-03-01")));
  }

  @Test
  void longStubsMergeIntoTheirNeighbour() {
    final HolidayCalendar c = Calendars.TARGET;
    final BusinessDayConvention mf = BusinessDayConvention.MODIFIED_FOLLOWING;
    final Schedule shortFront =
        Schedule.generate(d("2025-01-20"), d("2027-04-20"), 12, c, mf, mf, StubRule.SHORT_FRONT,
            false);
    final Schedule longFront =
        Schedule.generate(d("2025-01-20"), d("2027-04-20"), 12, c, mf, mf, StubRule.LONG_FRONT,
            false);
    assertEquals(shortFront.periodCount() - 1, longFront.periodCount());
    assertFalse(longFront.isRegular(0));
    assertEquals(d("2025-04-22"), longFront.referenceStart(0), "notional coupon date");
    final Schedule longBack =
        Schedule.generate(d("2025-01-20"), d("2027-04-20"), 12, c, mf, mf, StubRule.LONG_BACK,
            false);
    assertFalse(longBack.isRegular(longBack.periodCount() - 1));
    assertTrue(longBack.referenceEnd(longBack.periodCount() - 1) > longBack.end(
        longBack.periodCount() - 2));
    assertEquals(12, longBack.periodMonths());
    assertEquals(longBack.size(), longBack.dates().length);
    assertThrows(IllegalArgumentException.class,
        () -> Schedule.generate(d("2025-01-20"), d("2025-01-20"), 12, c, mf, mf,
            StubRule.SHORT_FRONT, false));
    assertThrows(IllegalArgumentException.class,
        () -> Schedule.generate(d("2025-01-20"), d("2026-01-20"), 0, c, mf, mf,
            StubRule.SHORT_FRONT, false));
  }
}
