package io.ratesalgo.quoting;

/** What the engine did with an RFQ. */
public enum RfqDecision {
  /** Firm quote with a time to live: the client may trade on it until it expires (no last look). */
  FIRM,
  /** Quote marked subject (validUntilNanos zero on the wire): indicative, not tradable as sent. */
  SUBJECT,
  /** No quote. */
  PASS
}
