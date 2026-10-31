package io.ratesalgo.strategy;

/**
 * Feature 4, client analytics loop: which tier a client is in, learned from the markouts of our
 * fills with that client (API_SPEC.md Section 19.5). The tier picks the adverse-selection charge
 * the spread optimiser uses.
 *
 * <p>The default, {@link StaticTiering}, is today's engine: a fixed table. {@link #tier} and
 * {@link #onMarkout} are allocation-free; {@link #review} may build a new table version and
 * allocate, and runs at the review cadence, not per RFQ.
 */
public interface ClientTiering {

  /** Tier of a client, 1 best. */
  int tier(int clientId);

  /** Whether the strategy should measure markouts for this tiering. */
  default boolean wantsMarkouts() {
    return false;
  }

  /** Records the adverse markout of one fill, bp of value from our side (negative: the client was right). */
  default void onMarkout(final int clientId, final double markoutBp, final long nowNanos) {}

  /** Publishes a new table version if assignments changed and the review interval has passed. */
  default void review(final long nowNanos) {}
}
