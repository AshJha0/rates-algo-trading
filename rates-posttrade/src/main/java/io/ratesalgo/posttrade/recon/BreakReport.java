package io.ratesalgo.posttrade.recon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily break report: one section per reconciliation, then every break (API_SPEC.md Section
 * 17.8). The text form is stable so that COOKBOOK.md recipe 21 can quote it.
 *
 * @param date business date
 * @param results reconciliations in the order they ran
 */
public record BreakReport(LocalDate date, List<ReconResult> results) {

  public BreakReport {
    results = List.copyOf(results);
  }

  /** Every break across all reconciliations. */
  public List<Break> breaks() {
    final List<Break> all = new ArrayList<>();
    for (final ReconResult r : results) {
      all.addAll(r.breaks());
    }
    return all;
  }

  /** Breaks that need operations. */
  public long unexplained() {
    return results.stream().mapToLong(ReconResult::unexplained).sum();
  }

  /** The report as text, one line per section and per break. */
  public String render() {
    final StringBuilder sb = new StringBuilder();
    sb.append("break report ").append(date).append('\n');
    for (final ReconResult r : results) {
      sb.append(String.format(java.util.Locale.ROOT,
          "  %-18s ours=%d theirs=%d matched=%d dup-suppressed=%d breaks=%d unexplained=%d%n",
          r.name(), r.oursCount(), r.theirsCount(), r.matched(), r.duplicatesSuppressed(),
          r.breaks().size(), r.unexplained()));
    }
    for (final Break b : breaks()) {
      sb.append(String.format(java.util.Locale.ROOT, "  %s %s %s %s ours[%s] theirs[%s]%n",
          b.reconciliation(), b.matchKey(), b.breakClass(), b.explanation(), b.ours(), b.theirs()));
    }
    sb.append("unexplained breaks: ").append(unexplained()).append('\n');
    return sb.toString();
  }
}
