package io.ratesalgo.core;

import io.ratesalgo.common.time.Clock;

/**
 * The loop's view of time: a reading of a source clock latched once per duty cycle (live) or once
 * per journal frame (replay).
 *
 * <p>Every message the loop journals in a cycle carries the latched wall time, and every handler
 * reads the same latched values. In replay the {@code Replayer} sets the source {@code ManualClock}
 * to each frame's stamp and the loop latches it, so handlers see exactly the time they saw live and
 * their outbound bytes match (API_SPEC.md Section 9.4). Build the inbound and outbound journals
 * with this clock so that their frame stamps are the latched time too.
 */
public final class EventClock implements Clock {

  private final Clock source;
  private final AnchoredClock anchored;
  private long nanoTime;
  private long epochNanos;

  public EventClock(final Clock source) {
    this.source = source;
    this.anchored = source instanceof AnchoredClock a ? a : null;
    latch();
  }

  /** Reads the source clock and holds the reading until the next latch. */
  public void latch() {
    if (anchored != null) {
      final long now = anchored.nanoTime();
      nanoTime = now;
      epochNanos = anchored.epochAt(now);
    } else {
      nanoTime = source.nanoTime();
      epochNanos = source.epochNanos();
    }
  }

  @Override
  public long nanoTime() {
    return nanoTime;
  }

  @Override
  public long epochNanos() {
    return epochNanos;
  }

  /** The clock latched from. */
  public Clock source() {
    return source;
  }
}
