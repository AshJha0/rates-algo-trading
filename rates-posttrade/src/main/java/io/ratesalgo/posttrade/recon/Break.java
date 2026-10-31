package io.ratesalgo.posttrade.recon;

/**
 * One reconciliation break.
 *
 * @param reconciliation which reconciliation found it (for example {@code drop-copy})
 * @param matchKey trade key or position key
 * @param breakClass what disagrees
 * @param explanation what the journal says about it
 * @param ours our side, or {@code -}
 * @param theirs their side, or {@code -}
 */
public record Break(
    String reconciliation,
    String matchKey,
    BreakClass breakClass,
    Explanation explanation,
    String ours,
    String theirs) {

  /** True if the explanation clears the break. */
  public boolean explained() {
    return explanation.clears();
  }
}
