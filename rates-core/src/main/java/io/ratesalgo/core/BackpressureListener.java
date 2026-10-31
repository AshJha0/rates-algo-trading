package io.ratesalgo.core;

/**
 * Told when outbound messages to a gateway were dropped because its ring buffer was full.
 *
 * <p>The quoting layer registers one and is expected to pull its live quotes on that gateway: a
 * quote the venue never received cannot be relied on, and one the gateway is slow to send is
 * stale. The listener runs on the loop thread at a fixed point (after the handler, timer poll or
 * agent that caused the drop returns), and the event is journaled first, so replay calls it at the
 * same point with the same values (API_SPEC.md Section 9.5).
 */
@FunctionalInterface
public interface BackpressureListener {

  /** The event object is reused; copy its values to keep them. */
  void onBackpressure(BackpressureEvent event);
}
