package io.ratesalgo.common.time;

/**
 * Source of time for everything in the platform.
 *
 * <p>Two clocks are deliberately kept apart. {@link #nanoTime()} is monotonic and is the only
 * clock used for intervals, timeouts and timers. {@link #epochNanos()} is wall time and is the
 * only clock used for stamps that leave the process (journal frames, outbound messages). Replay
 * supplies both from the journal, so code that reads time through this interface behaves the same
 * live and in replay.
 *
 * <p>Implementations must not allocate on either call.
 */
public interface Clock {

  /** Monotonic nanoseconds from an arbitrary origin; only differences are meaningful. */
  long nanoTime();

  /** Nanoseconds since 1970-01-01T00:00:00Z. */
  long epochNanos();
}
