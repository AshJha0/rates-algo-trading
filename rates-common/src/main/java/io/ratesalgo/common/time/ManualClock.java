package io.ratesalgo.common.time;

/**
 * A clock that only moves when told to. Tests and the replayer drive it.
 *
 * <p>Both readings advance together, so an interval measured with {@link #nanoTime()} and a
 * stamp taken with {@link #epochNanos()} stay consistent. The two origins differ, as they do on a
 * real machine, which catches code that mixes the two clocks.
 */
public final class ManualClock implements Clock {

  private long nanoTime;
  private long epochNanos;

  /**
   * Creates a clock at the given wall time. Monotonic time starts at a fixed, unrelated origin so
   * that mixing the clocks is visible in tests.
   */
  public ManualClock(final long epochNanos) {
    this.nanoTime = 1_000_000_000L;
    this.epochNanos = epochNanos;
  }

  /** Creates a clock at 2026-01-01T00:00:00Z. */
  public ManualClock() {
    this(1_767_225_600_000_000_000L);
  }

  @Override
  public long nanoTime() {
    return nanoTime;
  }

  @Override
  public long epochNanos() {
    return epochNanos;
  }

  /**
   * Moves both readings forward.
   *
   * @throws IllegalArgumentException if {@code nanos} is negative, since a clock never runs back
   */
  public void advance(final long nanos) {
    if (nanos < 0) {
      throw new IllegalArgumentException("clock cannot run backwards: advance(" + nanos + ")");
    }
    nanoTime += nanos;
    epochNanos += nanos;
  }

  /** Sets wall time directly, for replay; monotonic time is moved by the same amount. */
  public void setEpochNanos(final long newEpochNanos) {
    advance(newEpochNanos - epochNanos);
  }
}
