package io.ratesalgo.router;

/** How a parent order ended. */
public enum ParentOutcome {
  /** Every unit filled. */
  Filled,
  /** The owner cancelled it; what had filled by then stays filled. */
  Cancelled,
  /**
   * The deadline passed and the bounded retries after it could not place the rest (no quote, a
   * stale book, a limit price out of reach). Nothing is left working.
   */
  Expired,
  /**
   * A child got no answer from its venue to the order or to repeated cancels, so the router cannot
   * say whether that quantity traded. The parent stops and the child stays in the book, flagged
   * unresolved, until a report or a reconciliation settles it.
   */
  Failed
}
