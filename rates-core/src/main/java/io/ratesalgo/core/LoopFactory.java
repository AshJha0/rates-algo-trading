package io.ratesalgo.core;

/**
 * Builds the application part of a loop: timer geometry, handlers, agents and the backpressure
 * listener. The same factory builds the live loop and the replay loop, which is what makes the
 * replay comparison meaningful. Rings and journals are set on the builder by the caller before the
 * factory sees it; in replay the builder refuses them.
 */
@FunctionalInterface
public interface LoopFactory {

  /** Configures {@code builder}, builds the loop, registers handlers on it and returns it. */
  EventLoop create(EventLoop.Builder builder);
}
