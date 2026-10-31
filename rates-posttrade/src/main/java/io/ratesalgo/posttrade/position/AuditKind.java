package io.ratesalgo.posttrade.position;

/** Kinds of audit entry (API_SPEC.md Section 17.2). */
public enum AuditKind {
  /** A fill created a trade. */
  BOOKED,
  /** A fill with the key of a booked trade and identical economics was ignored. */
  DUPLICATE_IGNORED,
  /** A fill with the key of a booked trade but different economics was ignored and flagged. */
  DUPLICATE_CONFLICT,
  /** A fill arrived after a fill with a later transaction time in the same position. */
  LATE_FILL,
  /** A trade was busted. */
  BUSTED,
  /** A bust arrived before the trade it names; it is applied when the trade arrives. */
  BUST_PENDING,
  /** A second bust for an already busted trade was ignored. */
  DUPLICATE_BUST,
  /** A trade's price or quantity was corrected. */
  AMENDED,
  /** An amendment named an unknown or busted trade and was not applied. */
  AMEND_REJECTED,
  /** A start-of-day position was loaded. */
  OPENING_POSITION
}
