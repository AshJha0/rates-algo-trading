package io.ratesalgo.posttrade.settlement;

/** Inputs to the settlement lifecycle. */
public enum SettlementEvent {
  /** The settlement system confirms delivery versus payment. */
  SETTLE,
  /** The settlement system reports the delivery did not happen today. */
  FAIL,
  /** The trade is cancelled: a bust before settlement, or a close-out of a fail. */
  CANCEL
}
