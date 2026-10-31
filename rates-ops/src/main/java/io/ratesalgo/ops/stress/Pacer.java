package io.ratesalgo.ops.stress;

import io.ratesalgo.common.time.Clock;
import java.util.concurrent.locks.LockSupport;

/** Waits until a monotonic time. Tests use a pacer that advances a ManualClock instead. */
@FunctionalInterface
public interface Pacer {

  /** Returns at or after {@code targetNanoTime} on the clock's {@code nanoTime()} scale. */
  void awaitNanoTime(long targetNanoTime);

  /** Parks for long waits and spins for the last 50 microseconds. */
  static Pacer spinning(final Clock clock) {
    return target -> {
      long now = clock.nanoTime();
      while (now < target) {
        final long left = target - now;
        if (left > 50_000) {
          LockSupport.parkNanos(left - 50_000);
        } else {
          Thread.onSpinWait();
        }
        now = clock.nanoTime();
      }
    };
  }
}
