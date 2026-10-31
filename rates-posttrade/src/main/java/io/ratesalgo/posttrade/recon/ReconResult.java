package io.ratesalgo.posttrade.recon;

import java.util.List;

/**
 * The outcome of one reconciliation.
 *
 * @param name which reconciliation
 * @param oursCount items on our side after duplicate suppression
 * @param theirsCount items on their side after duplicate suppression
 * @param matched items that agree on every compared field
 * @param duplicatesSuppressed identical repeats removed from either side before matching
 * @param breaks every break, explained or not
 */
public record ReconResult(
    String name, int oursCount, int theirsCount, int matched, int duplicatesSuppressed, List<Break> breaks) {

  public ReconResult {
    breaks = List.copyOf(breaks);
  }

  /** Breaks whose explanation does not clear them. */
  public long unexplained() {
    return breaks.stream().filter(b -> !b.explained()).count();
  }

  /** Number of breaks of one class. */
  public long count(final BreakClass cls) {
    return breaks.stream().filter(b -> b.breakClass() == cls).count();
  }
}
