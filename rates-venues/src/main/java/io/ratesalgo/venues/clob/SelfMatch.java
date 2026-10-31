package io.ratesalgo.venues.clob;

/** What happens when an order would trade against a resting order of the same participant. */
public enum SelfMatch {
  /** Cancel the resting order and keep matching. */
  CANCEL_RESTING,
  /** Cancel the rest of the incoming order. */
  CANCEL_AGGRESSOR
}
