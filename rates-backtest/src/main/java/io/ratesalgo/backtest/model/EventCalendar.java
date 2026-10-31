package io.ratesalgo.backtest.model;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * Scheduled events of one session (data releases, fixings, the futures close), as epoch
 * nanoseconds, with the half-width of the window around each in which flow clusters.
 *
 * <p>Times come from preset {@code usd_rfq_flow} as minutes after midnight New York time
 * ({@code event_minutes_et}) and are converted with the session's fixed UTC offset; the synthetic
 * days use February dates, when New York is on UTC-5.
 */
public final class EventCalendar {

  private final long[] eventNanos;
  private final long halfWidthNanos;

  public EventCalendar(final long[] eventNanos, final long halfWidthNanos) {
    this.eventNanos = eventNanos.clone();
    Arrays.sort(this.eventNanos);
    this.halfWidthNanos = halfWidthNanos;
  }

  public static EventCalendar forDay(
      final LocalDate date, final int utcOffsetHours, final java.util.List<Double> minutesEt, final double halfWidthMinutes) {
    final long midnight =
        date.atStartOfDay().toEpochSecond(ZoneOffset.ofHours(utcOffsetHours)) * 1_000_000_000L;
    final long[] events = new long[minutesEt.size()];
    for (int i = 0; i < events.length; i++) {
      events[i] = midnight + Math.round(minutesEt.get(i) * 60e9);
    }
    return new EventCalendar(events, Math.round(halfWidthMinutes * 60e9));
  }

  public boolean inWindow(final long epochNanos) {
    for (final long e : eventNanos) {
      if (Math.abs(epochNanos - e) <= halfWidthNanos) {
        return true;
      }
    }
    return false;
  }

  public long[] events() {
    return eventNanos.clone();
  }

  public long halfWidthNanos() {
    return halfWidthNanos;
  }
}
