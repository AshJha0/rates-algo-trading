package io.ratesalgo.posttrade.recon;

/** Classes of reconciliation break (API_SPEC.md Section 17.8). */
public enum BreakClass {
  /** The other side has it and we do not. */
  MISSING_OURS,
  /** We have it and the other side does not. */
  MISSING_THEIRS,
  /** Both have it, quantities differ. */
  QUANTITY,
  /** Both have it, quantities agree, prices differ by more than the tolerance. */
  PRICE,
  /** Both agree on economics; transaction times differ by more than the tolerance. */
  TIMING
}
