package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.Side;

/**
 * Displayed depth per venue for the instrument being routed. The coordinator binds this to the
 * market state the P2 book feed maintains; tests and the back-test bind it to the in-memory
 * venues in {@code io.ratesalgo.router.sim}. Calls must not allocate.
 *
 * <p>Prices are int64 mantissas with exponent -9, quantities are minor units of face. A venue
 * with no two-sided quote returns 0 for the missing side.
 */
public interface BookView {

  /** Best bid on the venue, or 0 if there is none. */
  long bestBid(int venueId);

  /** Best ask on the venue, or 0 if there is none. */
  long bestAsk(int venueId);

  /** Displayed quantity at {@code level} (0 is the touch) on {@code side}; 0 if absent. */
  long depth(int venueId, Side side, int level);

  /** Recent traded quantity per second on the venue (both sides), for example an EWMA. */
  double tradeRatePerSecond(int venueId);
}
