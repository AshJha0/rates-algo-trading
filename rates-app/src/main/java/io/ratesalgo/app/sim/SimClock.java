package io.ratesalgo.app.sim;

import io.ratesalgo.common.time.Clock;

/**
 * Simulated time shared by the in-process venues, the curve feed and the loops of a simulated day.
 * Only the driver moves it; the venues' FIX threads read it. Both readings move together, as with
 * {@code ManualClock}: each move publishes one immutable reading through a volatile field, so a
 * reader on another thread never sees a monotonic time from one move and a wall time from another.
 */
public final class SimClock implements Clock {

  private record Reading(long nanoTime, long epochNanos) {}

  private volatile Reading reading;

  /** A clock at {@code epochNanos}. */
  public SimClock(final long epochNanos) {
    this.reading = new Reading(1_000_000_000L, epochNanos);
  }

  @Override
  public long nanoTime() {
    return reading.nanoTime();
  }

  @Override
  public long epochNanos() {
    return reading.epochNanos();
  }

  /** Moves both readings forward by {@code nanos}; one thread (the driver) calls this. */
  public synchronized void advance(final long nanos) {
    if (nanos < 0) {
      throw new IllegalArgumentException("clock cannot run backwards: advance(" + nanos + ")");
    }
    final Reading r = reading;
    reading = new Reading(r.nanoTime() + nanos, r.epochNanos() + nanos);
  }
}
