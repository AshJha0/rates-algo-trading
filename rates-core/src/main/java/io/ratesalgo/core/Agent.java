package io.ratesalgo.core;

/**
 * A unit of work called once per duty cycle after the inbound rings and the timers.
 *
 * <p>Agent work is not journaled and does not run in replay. An agent therefore must not change
 * state that handlers read or publish outbound messages; work that should affect the loop's state
 * enters as a message on an inbound ring, where it is journaled (DECISIONS.md DR-P2C-04). Typical
 * agents poll something outside the loop and publish what they find into the loop's own inbound
 * ring, or update monitoring counters.
 */
@FunctionalInterface
public interface Agent {

  /**
   * Does a bounded amount of work.
   *
   * @return the amount of work done, 0 if none, so the idle strategy can back off
   */
  int doWork();
}
