package io.ratesalgo.posttrade.clearing;

/** Inputs to the clearing lifecycle. */
public enum ClearingEvent {
  /** We send the trade for clearing. */
  SUBMIT,
  /** The clearing house reports the trade accepted by both clearing members. */
  ACCEPT,
  /** The clearing house reports novation complete. */
  CLEAR,
  /** The clearing house or a clearing member refuses the trade. */
  REJECT
}
