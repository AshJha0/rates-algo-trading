package io.ratesalgo.analytics.date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** Invariants of epoch-day arithmetic, day counts, conventions and schedules for random input. */
class DatePropertiesTest {

  private static final int LO = Dates.of(1960, 1, 1);
  private static final int HI = Dates.of(2140, 12, 31);

  @Provide
  Arbitrary<Integer> days() {
    return Arbitraries.integers().between(LO, HI);
  }

  @Provide
  Arbitrary<HolidayCalendar> calendars() {
    return Arbitraries.of(
        Calendars.US_SIFMA, Calendars.US_SOFR, Calendars.US_FED, Calendars.TARGET,
        Calendars.LONDON, Calendars.US_NY_SWAP);
  }

  @Property(tries = 2000)
  void civilFieldsAgreeWithJavaTime(@ForAll("days") final int d) {
    final LocalDate ld = LocalDate.ofEpochDay(d);
    assertEquals(ld.getYear(), Dates.year(d));
    assertEquals(ld.getMonthValue(), Dates.month(d));
    assertEquals(ld.getDayOfMonth(), Dates.dayOfMonth(d));
    assertEquals(ld.getDayOfWeek().getValue(), Dates.dayOfWeek(d));
    assertEquals(ld.getDayOfYear(), Dates.dayOfYear(d));
    assertEquals(d, Dates.of(ld.getYear(), ld.getMonthValue(), ld.getDayOfMonth()));
    assertEquals(ld.lengthOfMonth() == ld.getDayOfMonth(), Dates.isEndOfMonth(d));
  }

  @Property(tries = 2000)
  void addMonthsAgreesWithJavaTime(
      @ForAll("days") final int d, @ForAll @IntRange(min = -600, max = 600) final int months) {
    assertEquals(LocalDate.ofEpochDay(d).plusMonths(months).toEpochDay(),
        Dates.addMonths(d, months));
  }

  @Property(tries = 200)
  void easterMondayIsAMondayInMarchOrApril(@ForAll @IntRange(min = 1901, max = 2199) final int y) {
    final int em = Dates.of(y, 1, 1) + Dates.easterMondayDayOfYear(y) - 1;
    assertEquals(Dates.MONDAY, Dates.dayOfWeek(em));
    assertTrue(Dates.month(em) == 3 || Dates.month(em) == 4);
  }

  @Property(tries = 1000)
  void actualDayCountsAreAdditiveAndAntisymmetric(
      @ForAll("days") final int a, @ForAll("days") final int b, @ForAll("days") final int c) {
    for (final DayCount dc :
        new DayCount[] {DayCount.ACT_360, DayCount.ACT_365F, DayCount.ACT_ACT_ISDA}) {
      final double sum = dc.yearFraction(a, b) + dc.yearFraction(b, c);
      assertEquals(dc.yearFraction(a, c), sum, 1e-12, dc.name());
      assertEquals(-dc.yearFraction(a, b), dc.yearFraction(b, a), 1e-15, dc.name());
    }
  }

  @Property(tries = 1000)
  void actActIsdaOverAWholeCalendarYearIsOne(@ForAll @IntRange(min = 1960, max = 2140) final int y) {
    assertEquals(1.0, DayCount.ACT_ACT_ISDA.yearFraction(Dates.of(y, 1, 1), Dates.of(y + 1, 1,
        1)), 1e-15);
  }

  @Property(tries = 1000)
  void thirtyDayCountsAreWithinTwoDaysPerMonthOfActual(
      @ForAll("days") final int a, @ForAll @IntRange(min = 0, max = 4000) final int len) {
    final int b = a + len;
    final long months = ChronoUnit.MONTHS.between(LocalDate.ofEpochDay(a), LocalDate.ofEpochDay(b))
        + 1;
    for (final DayCount dc : new DayCount[] {DayCount.THIRTY_360_US, DayCount.THIRTY_E_360}) {
      final int n = dc.dayCount(a, b);
      assertTrue(Math.abs(n - len) <= 3 * months + 3, dc + " " + n + " vs " + len);
      assertEquals(n / 360.0, dc.yearFraction(a, b), 0.0);
    }
  }

  @Property(tries = 1000)
  void icmaRegularPeriodIsOnePeriodAndSplitsAdditively(
      @ForAll("days") final int start,
      @ForAll("periodMonths") final int months,
      @ForAll @IntRange(min = 0, max = 400) final int offset) {
    final int end = Dates.addMonths(start, months);
    final DayCount dc = DayCount.ACT_ACT_ICMA;
    assertEquals(months / 12.0, dc.yearFraction(start, end, start, end), 1e-14);
    final int mid = start + Math.min(offset, end - start);
    assertEquals(dc.yearFraction(start, end, start, end),
        dc.yearFraction(start, mid, start, end) + dc.yearFraction(mid, end, start, end), 1e-14);
  }

  @Provide
  Arbitrary<Integer> periodMonths() {
    return Arbitraries.of(1, 3, 6, 12);
  }

  @Property(tries = 2000)
  void conventionsLandOnBusinessDaysInTheRightDirection(
      @ForAll("days") final int d, @ForAll("calendars") final HolidayCalendar cal) {
    final int f = BusinessDayConvention.FOLLOWING.adjust(d, cal);
    final int p = BusinessDayConvention.PRECEDING.adjust(d, cal);
    final int mf = BusinessDayConvention.MODIFIED_FOLLOWING.adjust(d, cal);
    final int mp = BusinessDayConvention.MODIFIED_PRECEDING.adjust(d, cal);
    assertTrue(cal.isBusinessDay(f) && cal.isBusinessDay(p) && cal.isBusinessDay(mf)
        && cal.isBusinessDay(mp));
    assertTrue(f >= d && p <= d);
    assertEquals(Dates.month(d), Dates.month(mf), "modified following stays in the month");
    assertEquals(Dates.month(d), Dates.month(mp), "modified preceding stays in the month");
    assertEquals(d, BusinessDayConvention.UNADJUSTED.adjust(d, cal));
    if (cal.isBusinessDay(d)) {
      assertEquals(d, f);
      assertEquals(d, mp);
    }
    final int n = cal.advanceBusinessDays(d, 5);
    assertEquals(5, cal.businessDaysBetween(d + 1, n + 1));
  }

  @Property(tries = 1500)
  void schedulesAreOrderedOnBusinessDaysWithOneStubAtTheDeclaredEnd(
      @ForAll("days") final int effective,
      @ForAll @IntRange(min = 1, max = 12_000) final int length,
      @ForAll("periodMonths") final int months,
      @ForAll("calendars") final HolidayCalendar cal,
      @ForAll final StubRule stub,
      @ForAll final boolean eom) {
    final int start = cal.adjust(effective, BusinessDayConvention.FOLLOWING);
    final int termination = start + Math.max(length, 2 * 31 * months / 12 + 7);
    final BusinessDayConvention mf = BusinessDayConvention.MODIFIED_FOLLOWING;
    final Schedule s = Schedule.generate(start, termination, months, cal, mf, mf, stub, eom);
    assertEquals(start, s.date(0));
    assertEquals(mf.adjust(termination, cal), s.date(s.size() - 1));
    for (int i = 0; i < s.size(); i++) {
      assertTrue(cal.isBusinessDay(s.date(i)), "date " + i + " on a holiday");
      if (i > 0) {
        assertTrue(s.date(i) > s.date(i - 1), "not increasing at " + i);
      }
    }
    int irregular = 0;
    for (int p = 0; p < s.periodCount(); p++) {
      final int days = s.end(p) - s.start(p);
      assertTrue(days <= 31 * months * 2 + 8, "period too long: " + days);
      if (!s.isRegular(p)) {
        irregular++;
        final boolean front = p == 0;
        final boolean back = p == s.periodCount() - 1;
        final boolean frontRule = stub == StubRule.SHORT_FRONT || stub == StubRule.LONG_FRONT;
        assertTrue(frontRule ? front : back, "stub in the wrong place for " + stub);
      } else {
        assertTrue(days <= 31 * months + 4, "regular period too long: " + days);
      }
      assertTrue(s.referenceStart(p) < s.referenceEnd(p));
    }
    assertTrue(irregular <= 1, "more than one stub");
    assertFalse(s.periodCount() == 0);
  }
}
