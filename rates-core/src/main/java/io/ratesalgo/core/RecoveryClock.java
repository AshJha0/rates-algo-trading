package io.ratesalgo.core;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.ManualClock;

/**
 * The source clock of a loop that restarts on its own journals: journal time first, live time
 * after.
 *
 * <p>While the loop replays its inbound journal ({@link LoopRecovery}) the clock is a {@link
 * ManualClock} that the replayer sets to each frame's stamp, so handlers and timers see the times
 * they saw before the stop. {@link #goLive} then switches to the live clock without a break in the
 * monotonic reading the timer wheel holds its deadlines in: live time carries on from the last
 * replayed frame, moved forward by however long the process was down. Both readings move by that
 * same amount, as they do everywhere else on the loop, so a later replay of the whole journal,
 * which moves a manual clock by the frames' stamps, sees the same intervals the restarted loop saw.
 * Wall time never goes back: a live clock that reads earlier than the last frame is carried on
 * from the frame instead.
 *
 * <p>Wrap it in an {@link AnchoredClock} for the loop's {@link EventClock}, as a live loop does
 * with any source. Single-threaded: the thread that recovers switches it before the loop thread
 * starts.
 */
public final class RecoveryClock implements Clock {

  private final ManualClock replay;
  private Clock live;
  private long nanoShift;
  private long epochShift;

  /** A clock standing at the stamp of the first frame to be replayed. */
  public RecoveryClock(final long firstFrameEpochNanos) {
    this.replay = new ManualClock(firstFrameEpochNanos);
  }

  /** The clock the replayer drives until {@link #goLive}. */
  ManualClock replayClock() {
    return replay;
  }

  /** True once {@link #goLive} has been called. */
  public boolean isLive() {
    return live != null;
  }

  /**
   * Switches to {@code liveClock}. Time carries on from the last replayed frame plus the time the
   * live wall clock says has passed since it, or from the frame itself if the live clock is behind.
   *
   * @return the nanoseconds between the last replayed frame and now on the live wall clock, at
   *     least 0: how long the process was down
   */
  public long goLive(final Clock liveClock) {
    if (live != null) {
      throw new IllegalStateException("the clock is live already");
    }
    final long gap = Math.max(0L, liveClock.epochNanos() - replay.epochNanos());
    nanoShift = replay.nanoTime() + gap - liveClock.nanoTime();
    epochShift = replay.epochNanos() + gap - liveClock.epochNanos();
    live = liveClock;
    return gap;
  }

  @Override
  public long nanoTime() {
    final Clock current = live;
    return current == null ? replay.nanoTime() : current.nanoTime() + nanoShift;
  }

  @Override
  public long epochNanos() {
    final Clock current = live;
    return current == null ? replay.epochNanos() : current.epochNanos() + epochShift;
  }
}
