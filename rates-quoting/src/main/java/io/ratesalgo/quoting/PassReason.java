package io.ratesalgo.quoting;

/** Why an RFQ was passed (API_SPEC.md Section 12.6 lists the order in which these are tested). */
public enum PassReason {
  NONE,
  DUPLICATE,
  KILLED,
  UNHEALTHY,
  /** Within the pull window around a scheduled release (QuotingConfig.releaseCalendar). */
  RELEASE_WINDOW,
  UNKNOWN_INSTRUMENT,
  EXPIRED_REQUEST,
  CLIENT_BLOCKED,
  THROTTLED,
  NO_FAIR_VALUE,
  STALE_INPUT,
  NO_RISK_CAPACITY,
  BOOK_FULL,
  /** The expected adverse cost is at or above the widest allowed half spread: no spread earns. */
  NEGATIVE_EDGE,
  RISK_REJECT
}
