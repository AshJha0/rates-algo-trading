package io.ratesalgo.common.time;

/** Callback for an expired timer. Runs on the thread that calls {@link TimerWheel#poll(long)}. */
@FunctionalInterface
public interface TimerHandler {

  /**
   * Called once when the timer fires.
   *
   * @param timerId the id returned by {@link TimerWheel#schedule(long, TimerHandler)}
   * @param nowNanos the {@code nowNanos} passed to the {@code poll} call that fired it
   */
  void onTimer(long timerId, long nowNanos);
}
