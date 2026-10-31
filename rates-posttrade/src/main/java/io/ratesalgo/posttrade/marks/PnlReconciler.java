package io.ratesalgo.posttrade.marks;

import io.ratesalgo.posttrade.trade.PositionKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconciles official P&amp;L to the back-tester's attribution, component by component, to the
 * minor unit: the tolerance is zero. A component present on one side only is a difference unless
 * its amount is zero.
 */
public final class PnlReconciler {

  private PnlReconciler() {}

  /** One component that does not agree. */
  public record Difference(
      LocalDate date, PositionKey key, PnlComponent component, long oursMinor, long theirsMinor) {

    /** Ours minus theirs. */
    public long deltaMinor() {
      return Math.subtractExact(oursMinor, theirsMinor);
    }
  }

  /** The outcome: components compared and those that differ. */
  public record Result(int componentsCompared, List<Difference> differences) {

    public Result {
      differences = List.copyOf(differences);
    }

    /** True when every component agrees to the minor unit. */
    public boolean reconciled() {
      return differences.isEmpty();
    }
  }

  /** Compares official lines with attribution lines. */
  public static Result reconcile(final List<PnlLine> official, final List<AttributionLine> attribution) {
    final Map<Slot, long[]> slots = new TreeMap<>();
    for (final PnlLine l : official) {
      for (final PnlComponent c : PnlComponent.values()) {
        slots.computeIfAbsent(new Slot(l.date(), l.key(), c), s -> new long[2])[0] += l.amount(c);
      }
    }
    for (final AttributionLine a : attribution) {
      slots.computeIfAbsent(new Slot(a.date(), a.key(), a.component()), s -> new long[2])[1] +=
          a.amountMinor();
    }
    final List<Difference> diffs = new ArrayList<>();
    for (final Map.Entry<Slot, long[]> e : slots.entrySet()) {
      final long[] v = e.getValue();
      if (v[0] != v[1]) {
        final Slot s = e.getKey();
        diffs.add(new Difference(s.date(), s.key(), s.component(), v[0], v[1]));
      }
    }
    return new Result(slots.size(), diffs);
  }

  private record Slot(LocalDate date, PositionKey key, PnlComponent component)
      implements Comparable<Slot> {
    @Override
    public int compareTo(final Slot o) {
      int c = date.compareTo(o.date);
      if (c == 0) {
        c = key.compareTo(o.key);
      }
      return c != 0 ? c : component.compareTo(o.component);
    }
  }
}
