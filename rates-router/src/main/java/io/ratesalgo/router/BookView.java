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

  /**
   * Price of {@code level} (0 is the touch) on {@code side}, the level whose size {@link #depth}
   * returns; 0 if the level is absent or the view does not know its price. The default knows only
   * the touch, so a view that does not implement it gives the ladder model one level per venue.
   */
  default long levelPrice(final int venueId, final Side side, final int level) {
    if (level != 0) {
      return 0L;
    }
    return side == Side.Buy ? bestBid(venueId) : bestAsk(venueId);
  }

  /** Recent traded quantity per second on the venue (both sides), for example an EWMA. */
  double tradeRatePerSecond(int venueId);

  /** {@link #lastUpdateNanos} of a view that does not track book age. */
  long AGE_UNKNOWN = Long.MIN_VALUE;

  /**
   * Epoch nanoseconds at which the venue's book last showed it was alive (an update, or a
   * heartbeat of its feed), or {@link #AGE_UNKNOWN} if the view does not track it. The router
   * compares it with {@link RouterTimeouts#maxBookAgeNanos()}.
   */
  default long lastUpdateNanos(final int venueId) {
    return AGE_UNKNOWN;
  }
}
