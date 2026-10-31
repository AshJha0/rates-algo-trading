package io.ratesalgo.common.time;

import java.time.Instant;

/**
 * The production clock: {@link System#nanoTime()} for intervals and {@link Instant#now()} for
 * stamps.
 *
 * <p>{@code Instant.now()} allocates one small object per call on current JDKs, which is
 * acceptable on the gateway thread where stamps are taken, but it must not be called inside the
 * event loop. The loop reads stamps from the inbound message and from {@link #nanoTime()}.
 */
public final class SystemClock implements Clock {

  public static final SystemClock INSTANCE = new SystemClock();

  private SystemClock() {}

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }

  @Override
  public long epochNanos() {
    final Instant now = Instant.now();
    return now.getEpochSecond() * 1_000_000_000L + now.getNano();
  }
}
