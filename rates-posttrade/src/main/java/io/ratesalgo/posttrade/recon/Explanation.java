package io.ratesalgo.posttrade.recon;

/**
 * Why a break exists, when the journal shows it (API_SPEC.md Section 17.8). A break is
 * explained only if its explanation {@link #clears()} it; anything else goes to operations.
 */
public enum Explanation {
  /** Nothing in our records accounts for the break. */
  NONE(false),
  /** The fill reached our journal after the reconciliation cut. */
  LATE_ARRIVAL(true),
  /** We received the bust after the cut; the other side's view already excludes the trade. */
  BUSTED_AFTER_CUT(true),
  /** The clearing house rejected the trade and we voided it; a venue drop copy still shows it. */
  CLEARING_REJECTED(true),
  /** We amended the trade with an audited correction that the other side has not applied. */
  AMENDED(true),
  /** The venue sent two different versions of one execution; operations must decide which stands. */
  DUPLICATE_CONFLICT(false);

  private final boolean clears;

  Explanation(final boolean clears) {
    this.clears = clears;
  }

  /** True if this explanation accounts for the break without further action. */
  public boolean clears() {
    return clears;
  }
}
