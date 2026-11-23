package io.ratesalgo.sim;

/**
 * What the client of one RFQ did (design section 7, best-price rule).
 *
 * @param outcome traded, passed, traded elsewhere, or nobody quoted
 * @param winner competitor number of the winner, {@link #DESK} for the desk, {@link #NOBODY} if no trade
 * @param price winning price in quoted units, NaN if no trade
 * @param coverPrice second-best live price, NaN if there was none or no trade
 * @param winnerHalfSpreadBp the winner's distance from the mid at arrival before tick rounding, bp
 *     of yield; for the desk, the distance of its price; NaN if no trade
 * @param decisionDelayNanos when the client decided, nanoseconds after arrival
 */
public record RfqDecision(
    Outcome outcome, int winner, double price, double coverPrice, double winnerHalfSpreadBp, long decisionDelayNanos) {

  /** Winner value for the desk. */
  public static final int DESK = -1;

  /** Winner value when nobody traded. */
  public static final int NOBODY = -2;

  /** Outcomes of an RFQ. */
  public enum Outcome {
    /** The client traded with the best quote. */
    DONE,
    /** The best quote was outside the client's reserve. */
    PASSED,
    /** The client traded elsewhere. */
    DONE_AWAY,
    /** No live quote when the client decided. */
    EXPIRED
  }

  /** True if the client traded with one of the dealers asked. */
  public boolean traded() {
    return outcome == Outcome.DONE;
  }

  /** True if the desk won. */
  public boolean deskWon() {
    return winner == DESK;
  }
}
