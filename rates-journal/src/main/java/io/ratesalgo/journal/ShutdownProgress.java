package io.ratesalgo.journal;

/**
 * The progress rule for a shutdown wait on a journal's background thread (DR-INT-10): keep waiting
 * while a monotonic progress count moves, give up only after a stall limit with no movement. The
 * count is whatever the thread advances while it works, for example the ring's consumed position
 * plus the pages the segment preparer has touched. A fixed limit would fail a close on a slow disk
 * that was about to succeed.
 */
final class ShutdownProgress {

  private final long stallNanos;
  private long lastCount = Long.MIN_VALUE;
  private long lastAdvanceNanos;
  private long advances;

  ShutdownProgress(final long stallNanos) {
    if (stallNanos <= 0) {
      throw new IllegalArgumentException("stall limit must be positive: " + stallNanos);
    }
    this.stallNanos = stallNanos;
  }

  /** Records {@code count} at {@code nowNanos}; true if it has not moved for the stall limit. */
  boolean stalled(final long count, final long nowNanos) {
    if (count != lastCount) {
      lastCount = count;
      lastAdvanceNanos = nowNanos;
      advances++;
      return false;
    }
    return nowNanos - lastAdvanceNanos >= stallNanos;
  }

  long advances() {
    return advances;
  }

  long stallNanos() {
    return stallNanos;
  }
}
