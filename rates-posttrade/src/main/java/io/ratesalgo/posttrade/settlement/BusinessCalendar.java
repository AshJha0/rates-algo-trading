package io.ratesalgo.posttrade.settlement;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Business days for settlement. P1 binds the real calendars (US SIFMA, UK, TARGET2); the fixture
 * {@link #WEEKENDS_ONLY} knows only weekends.
 */
@FunctionalInterface
public interface BusinessCalendar {

  /** Fixture calendar: every weekday is a business day. */
  BusinessCalendar WEEKENDS_ONLY =
      d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;

  /** True if {@code date} is a settlement business day. */
  boolean isBusinessDay(LocalDate date);

  /** {@code tradeDate} plus {@code lag} business days (T+1 for Treasuries and gilts, T+2 for Bunds). */
  default LocalDate addBusinessDays(final LocalDate tradeDate, final int lag) {
    if (lag < 0) {
      throw new IllegalArgumentException("negative settlement lag " + lag);
    }
    LocalDate d = tradeDate;
    int left = lag;
    while (left > 0) {
      d = d.plusDays(1);
      if (isBusinessDay(d)) {
        left--;
      }
    }
    return d;
  }
}
