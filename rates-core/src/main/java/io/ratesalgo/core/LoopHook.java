package io.ratesalgo.core;

/**
 * Work the runner does on the loop thread at start of day (before the first duty cycle) or end of
 * day (after the last). Hooks are not journaled: a hook may re-anchor the clock, warm caches or
 * publish into the loop's inbound ring, but must not change state that handlers read, or replay
 * will not reproduce the day.
 */
@FunctionalInterface
public interface LoopHook {

  /** Does nothing. */
  LoopHook NONE = loop -> {};

  void run(EventLoop loop);
}
