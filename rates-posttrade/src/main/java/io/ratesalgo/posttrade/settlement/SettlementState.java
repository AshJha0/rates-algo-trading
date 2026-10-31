package io.ratesalgo.posttrade.settlement;

/** Where a bond trade is in settlement (API_SPEC.md Section 17.7). */
public enum SettlementState {
  /** Before or on the settlement date, not yet settled. */
  PENDING,
  /** Delivery versus payment completed. Terminal. */
  SETTLED,
  /** Not delivered on the settlement date; carried forward day by day until cured or cancelled. */
  FAILED,
  /** Cancelled before settlement (bust) or after a fail (closed out). Terminal. */
  CANCELLED;

  /** True for states no event can leave. */
  public boolean terminal() {
    return this == SETTLED || this == CANCELLED;
  }
}
