package io.ratesalgo.posttrade.recon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Trade-level and position-level reconciliation (API_SPEC.md Section 17.8).
 *
 * <p>Each side is first reduced to one item per match key: an identical repeat is suppressed and
 * counted; a repeat that differs is kept as the first and reported as a break against itself so
 * that it cannot vanish. Items are then matched by key; for each matched pair the classes are
 * tested in order QUANTITY, PRICE, TIMING and the first that fails names the break.
 */
public final class Reconciler {

  /**
   * Tolerances.
   *
   * @param priceMantissa largest price difference accepted, Decimal9 mantissa
   * @param timingNanos largest transaction-time difference accepted
   */
  public record Tolerance(long priceMantissa, long timingNanos) {
    /** Exact price, one second of timing. */
    public static final Tolerance DEFAULT = new Tolerance(0, 1_000_000_000L);
  }

  private Reconciler() {}

  /** Reconciles two lists of trades. */
  public static ReconResult trades(
      final String name,
      final List<ReconItem> ours,
      final List<ReconItem> theirs,
      final Tolerance tolerance,
      final BreakExplainer explainer) {
    final List<Break> breaks = new ArrayList<>();
    final int[] suppressed = new int[1];
    final Map<String, ReconItem> a = dedupe(name, "ours", ours, breaks, suppressed, explainer);
    final Map<String, ReconItem> b = dedupe(name, "theirs", theirs, breaks, suppressed, explainer);
    int matched = 0;
    final TreeSet<String> keys = new TreeSet<>(a.keySet());
    keys.addAll(b.keySet());
    for (final String k : keys) {
      final ReconItem x = a.get(k);
      final ReconItem y = b.get(k);
      final BreakClass cls;
      if (x == null) {
        cls = BreakClass.MISSING_OURS;
      } else if (y == null) {
        cls = BreakClass.MISSING_THEIRS;
      } else if (x.quantity() != y.quantity() || x.side() != y.side()
          || x.instrumentId() != y.instrumentId()) {
        cls = BreakClass.QUANTITY;
      } else if (Math.abs(x.price() - y.price()) > tolerance.priceMantissa()) {
        cls = BreakClass.PRICE;
      } else if (Math.abs(x.transactNanos() - y.transactNanos()) > tolerance.timingNanos()) {
        cls = BreakClass.TIMING;
      } else {
        matched++;
        continue;
      }
      breaks.add(new Break(name, k, cls, explainer.explain(k, cls),
          x == null ? "-" : x.describe(), y == null ? "-" : y.describe()));
    }
    return new ReconResult(name, a.size(), b.size(), matched, suppressed[0], breaks);
  }

  /** Reconciles net quantities keyed by position or instrument text. */
  public static ReconResult positions(
      final String name,
      final Map<String, Long> ours,
      final Map<String, Long> theirs,
      final BreakExplainer explainer) {
    final List<Break> breaks = new ArrayList<>();
    int matched = 0;
    final TreeSet<String> keys = new TreeSet<>(ours.keySet());
    keys.addAll(theirs.keySet());
    for (final String k : keys) {
      final long x = ours.getOrDefault(k, 0L);
      final long y = theirs.getOrDefault(k, 0L);
      if (x == y) {
        matched++;
        continue;
      }
      final BreakClass cls =
          !ours.containsKey(k) ? BreakClass.MISSING_OURS
              : !theirs.containsKey(k) ? BreakClass.MISSING_THEIRS : BreakClass.QUANTITY;
      breaks.add(new Break(name, k, cls, explainer.explain(k, cls), Long.toString(x), Long.toString(y)));
    }
    return new ReconResult(name, ours.size(), theirs.size(), matched, 0, breaks);
  }

  private static Map<String, ReconItem> dedupe(
      final String name,
      final String sideName,
      final List<ReconItem> items,
      final List<Break> breaks,
      final int[] suppressed,
      final BreakExplainer explainer) {
    final Map<String, ReconItem> out = new TreeMap<>();
    for (final ReconItem i : items) {
      final ReconItem first = out.putIfAbsent(i.matchKey(), i);
      if (first == null) {
        continue;
      }
      if (first.equals(i)) {
        suppressed[0]++;
      } else {
        final BreakClass cls =
            first.quantity() != i.quantity() ? BreakClass.QUANTITY
                : first.price() != i.price() ? BreakClass.PRICE : BreakClass.TIMING;
        final Explanation e = explainer.explain(i.matchKey(), cls);
        breaks.add(new Break(name, i.matchKey(), cls,
            e == Explanation.NONE ? Explanation.DUPLICATE_CONFLICT : e,
            sideName + " first " + first.describe(), sideName + " repeat " + i.describe()));
      }
    }
    return out;
  }
}
