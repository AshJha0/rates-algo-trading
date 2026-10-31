package io.ratesalgo.core;

import io.ratesalgo.common.time.Clock;

/**
 * A live clock for the loop thread that does not allocate: wall time is the monotonic reading plus
 * an offset taken from the source's wall clock when the clock is anchored.
 *
 * <p>{@code SystemClock.epochNanos()} goes through {@code Instant.now()}, which may allocate, so
 * the loop never calls it per message. This clock calls it only in {@link #reanchor()}, which the
 * start-of-day hook runs. Wall time and monotonic time therefore move together exactly, which is
 * what replay needs (the {@code ManualClock} moves them together too). The cost is drift against
 * the system wall clock between anchors, typically well under a millisecond a day on NTP-disciplined
 * hosts; a re-anchor never moves wall time backwards.
 */
public final class AnchoredClock implements Clock {

  private final Clock source;
  private long anchorNanoTime;
  private long anchorEpochNanos;

  public AnchoredClock(final Clock source) {
    this.source = source;
    this.anchorNanoTime = source.nanoTime();
    this.anchorEpochNanos = source.epochNanos();
  }

  /**
   * Re-reads the source wall clock. If that would move wall time backwards, the anchor keeps the
   * current wall time instead, so journal stamps never decrease.
   */
  public void reanchor() {
    final long nano = source.nanoTime();
    final long wall = source.epochNanos();
    final long current = epochAt(nano);
    anchorNanoTime = nano;
    anchorEpochNanos = Math.max(wall, current);
  }

  @Override
  public long nanoTime() {
    return source.nanoTime();
  }

  @Override
  public long epochNanos() {
    return epochAt(source.nanoTime());
  }

  /** Wall time corresponding to a monotonic reading from this clock. */
  public long epochAt(final long nanoTime) {
    return anchorEpochNanos + (nanoTime - anchorNanoTime);
  }
}
