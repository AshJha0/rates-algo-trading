package io.ratesalgo.venues.d2c;

/** How an RFQ ended (API_SPEC.md Section 11.2). */
public enum RfqOutcome {
  /** The client traded with a dealer on the venue. */
  DONE,
  /** The client traded elsewhere; quoting dealers are told done away. */
  DONE_AWAY,
  /** The client passed on every quote. */
  PASSED,
  /** No dealer quoted before the window closed, or the client never decided. */
  EXPIRED
}
