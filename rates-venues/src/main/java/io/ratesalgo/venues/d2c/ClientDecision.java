package io.ratesalgo.venues.d2c;

/** What a scripted client does when the response window closes or {@code decide} is called. */
public enum ClientDecision {
  /** Use the simulated client model (tolerance, done-away probability). */
  MODEL,
  /** Trade with the best quote. */
  BEST,
  /** Pass on every quote. */
  PASS,
  /** Trade elsewhere. */
  DONE_AWAY,
  /** Never decide; the RFQ expires. */
  NONE
}
