package io.ratesalgo.posttrade.clearing;

/** Where a swap is in the clearing affirmation lifecycle (API_SPEC.md Section 17.6). */
public enum ClearingState {
  /** Booked, not yet sent to the clearing house. */
  NEW,
  /** Sent to the clearing house through the affirmation platform. */
  SUBMITTED,
  /** Accepted for clearing: both clearing members have taken the trade within their limits. */
  ACCEPTED,
  /** Novated to the clearing house; the bilateral trade no longer exists. Terminal. */
  CLEARED,
  /** Refused by the clearing house or a clearing member. Terminal; the trade is void. */
  REJECTED;

  /** True for states no event can leave. */
  public boolean terminal() {
    return this == CLEARED || this == REJECTED;
  }
}
