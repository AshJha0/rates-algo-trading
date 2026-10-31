package io.ratesalgo.quoting;

/** Why an RFQ was passed (API_SPEC.md Section 12.6 lists the order in which these are tested). */
public enum PassReason {
  NONE,
  DUPLICATE,
  KILLED,
  UNHEALTHY,
  UNKNOWN_INSTRUMENT,
  EXPIRED_REQUEST,
  CLIENT_BLOCKED,
  THROTTLED,
  NO_FAIR_VALUE,
  STALE_INPUT,
  NO_RISK_CAPACITY,
  BOOK_FULL,
  RISK_REJECT
}
