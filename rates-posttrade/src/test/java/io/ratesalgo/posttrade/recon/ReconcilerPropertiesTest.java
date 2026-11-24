package io.ratesalgo.posttrade.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation is symmetric (swapping the sides swaps the two missing classes and nothing else),
 * idempotent (a side against itself matches everything), and on any two lists names each break as
 * an independent per-key classifier does: missing on one side, else quantity, side or instrument,
 * else price beyond tolerance, else timing beyond tolerance, with identical repeats suppressed and
 * differing repeats reported against themselves.
 */
class ReconcilerPropertiesTest {

  private static List<ReconItem> randomSide(final Random rnd, final int keys) {
    final List<ReconItem> out = new ArrayList<>();
    for (int n = 0; n < keys; n++) {
      if (rnd.nextInt(5) == 0) {
        continue; // this side never saw the key
      }
      final ReconItem i = item(rnd, "T" + n);
      out.add(i);
      if (rnd.nextInt(6) == 0) {
        out.add(i); // an identical repeat
      }
      if (rnd.nextInt(12) == 0) {
        out.add(item(rnd, "T" + n)); // a repeat that differs, most likely
      }
    }
    Collections.shuffle(out, rnd);
    return out;
  }

  private static ReconItem item(final Random rnd, final String key) {
    return new ReconItem(key, 1 + rnd.nextInt(2), rnd.nextBoolean() ? Side.Buy : Side.Sell,
        100L * (1 + rnd.nextInt(2)), 1_000L + rnd.nextInt(4), 5_000L + rnd.nextInt(4) * 1_000L);
  }

  @Property(tries = 300, seed = "17")
  void breaksAreWhatAPerKeyClassifierSays(
      @ForAll final long seed,
      @ForAll @LongRange(min = 0, max = 3) final long priceTol,
      @ForAll @LongRange(min = 0, max = 3_000) final long timeTol) {
    final Random rnd = new Random(seed);
    final List<ReconItem> ours = randomSide(rnd, 12);
    final List<ReconItem> theirs = randomSide(rnd, 12);
    final Reconciler.Tolerance tol = new Reconciler.Tolerance(priceTol, timeTol);
    final ReconResult r = Reconciler.trades("t", ours, theirs, tol, BreakExplainer.NONE);

    // the oracle: first item per key on each side, plus a self-break per differing repeat
    final int[] counts = new int[2]; // identical repeats, differing repeats
    final Map<String, ReconItem> a = firsts(ours, counts);
    final Map<String, ReconItem> b = firsts(theirs, counts);
    final Map<String, Integer> expected = new TreeMap<>();
    int matched = 0;
    final Set<String> keys = new TreeSet<>(a.keySet());
    keys.addAll(b.keySet());
    for (final String k : keys) {
      final String cls = classify(a.get(k), b.get(k), tol);
      if (cls == null) {
        matched++;
      } else {
        expected.merge(cls, 1, Integer::sum);
      }
    }
    assertEquals(matched, r.matched(), "matched");
    assertEquals(a.size(), r.oursCount());
    assertEquals(b.size(), r.theirsCount());
    assertEquals(counts[0], r.duplicatesSuppressed(), "identical repeats");
    final int selfBreaks = counts[1];
    final Map<String, Integer> actual = new TreeMap<>();
    int explainedAsDuplicates = 0;
    for (final Break brk : r.breaks()) {
      if (brk.explanation() == Explanation.DUPLICATE_CONFLICT) {
        explainedAsDuplicates++;
        assertTrue(brk.ours().contains(" first ") && brk.theirs().contains(" repeat "), brk.toString());
      } else {
        actual.merge(brk.breakClass().name(), 1, Integer::sum);
        assertEquals(Explanation.NONE, brk.explanation());
      }
    }
    assertEquals(selfBreaks, explainedAsDuplicates, "differing repeats");
    assertEquals(expected, actual, "breaks by class");
  }

  private static Map<String, ReconItem> firsts(final List<ReconItem> side, final int[] counts) {
    final Map<String, ReconItem> first = new HashMap<>();
    for (final ReconItem i : side) {
      final ReconItem f = first.putIfAbsent(i.matchKey(), i);
      if (f != null) {
        counts[f.equals(i) ? 0 : 1]++;
      }
    }
    return first;
  }

  /** The specification of a pair, written from the class comment. */
  private static String classify(final ReconItem x, final ReconItem y, final Reconciler.Tolerance tol) {
    if (x == null) {
      return "MISSING_OURS";
    }
    if (y == null) {
      return "MISSING_THEIRS";
    }
    if (x.quantity() != y.quantity() || x.side() != y.side() || x.instrumentId() != y.instrumentId()) {
      return "QUANTITY";
    }
    if (Math.abs(x.price() - y.price()) > tol.priceMantissa()) {
      return "PRICE";
    }
    if (Math.abs(x.transactNanos() - y.transactNanos()) > tol.timingNanos()) {
      return "TIMING";
    }
    return null;
  }

  @Property(tries = 200, seed = "23")
  void swappingTheSidesSwapsTheMissingClassesOnly(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final List<ReconItem> ours = randomSide(rnd, 10);
    final List<ReconItem> theirs = randomSide(rnd, 10);
    final ReconResult ab = Reconciler.trades("t", ours, theirs, Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE);
    final ReconResult ba = Reconciler.trades("t", theirs, ours, Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE);
    assertEquals(ab.matched(), ba.matched());
    assertEquals(ab.oursCount(), ba.theirsCount());
    assertEquals(ab.theirsCount(), ba.oursCount());
    assertEquals(ab.duplicatesSuppressed(), ba.duplicatesSuppressed());
    assertEquals(ab.count(BreakClass.MISSING_OURS), ba.count(BreakClass.MISSING_THEIRS));
    assertEquals(ab.count(BreakClass.MISSING_THEIRS), ba.count(BreakClass.MISSING_OURS));
    for (final BreakClass c : new BreakClass[] {BreakClass.QUANTITY, BreakClass.PRICE, BreakClass.TIMING}) {
      assertEquals(ab.count(c), ba.count(c), c.name());
    }
  }

  @Property(tries = 200, seed = "29")
  void aSideAgainstItselfMatchesEveryKeyAndReconcilingTwiceGivesTheSameResult(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final List<ReconItem> side = randomSide(rnd, 15);
    final ReconResult r = Reconciler.trades("t", side, side, Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE);
    final long distinct = side.stream().map(ReconItem::matchKey).distinct().count();
    assertEquals(distinct, r.matched());
    assertEquals(0L, r.count(BreakClass.MISSING_OURS) + r.count(BreakClass.MISSING_THEIRS));
    for (final Break b : r.breaks()) {
      assertEquals(Explanation.DUPLICATE_CONFLICT, b.explanation(), "only differing repeats break");
    }
    assertEquals(r, Reconciler.trades("t", side, side, Reconciler.Tolerance.DEFAULT, BreakExplainer.NONE));
  }

  @Property(tries = 200, seed = "31")
  void positionsBreakExactlyWhereTheNetsDiffer(@ForAll final long seed) {
    final Random rnd = new Random(seed);
    final Map<String, Long> ours = new HashMap<>();
    final Map<String, Long> theirs = new HashMap<>();
    for (int n = 0; n < 12; n++) {
      final long q = rnd.nextInt(5) - 2L;
      final int shape = rnd.nextInt(4);
      if (shape != 1) {
        ours.put("P" + n, q);
      }
      if (shape != 2) {
        theirs.put("P" + n, shape == 3 ? q + rnd.nextInt(3) - 1 : q);
      }
    }
    final ReconResult r = Reconciler.positions("p", ours, theirs, BreakExplainer.NONE);
    int matched = 0;
    final Map<String, BreakClass> expected = new TreeMap<>();
    for (int n = 0; n < 12; n++) {
      final String k = "P" + n;
      if (!ours.containsKey(k) && !theirs.containsKey(k)) {
        continue;
      }
      final long x = ours.getOrDefault(k, 0L);
      final long y = theirs.getOrDefault(k, 0L);
      if (x == y) {
        matched++; // an absent key counts as flat
      } else {
        expected.put(k, !ours.containsKey(k) ? BreakClass.MISSING_OURS
            : !theirs.containsKey(k) ? BreakClass.MISSING_THEIRS : BreakClass.QUANTITY);
      }
    }
    final Map<String, BreakClass> actual = new TreeMap<>();
    for (final Break b : r.breaks()) {
      actual.put(b.matchKey(), b.breakClass());
      assertEquals(Long.toString(ours.getOrDefault(b.matchKey(), 0L)), b.ours());
      assertEquals(Long.toString(theirs.getOrDefault(b.matchKey(), 0L)), b.theirs());
    }
    assertEquals(expected, actual);
    assertEquals(matched, r.matched());
    assertEquals(ours.size(), r.oursCount());
    assertEquals(theirs.size(), r.theirsCount());
  }

  @Test
  void toleranceBoundariesAreInclusive() {
    final ReconItem x = new ReconItem("K", 1, Side.Buy, 100, 1_000, 10_000L);
    final Reconciler.Tolerance tol = new Reconciler.Tolerance(2, 500);
    final ReconItem atBoth = new ReconItem("K", 1, Side.Buy, 100, 998, 10_500L);
    assertEquals(1, Reconciler.trades("t", List.of(x), List.of(atBoth), tol, BreakExplainer.NONE).matched());
    final ReconItem pastPrice = new ReconItem("K", 1, Side.Buy, 100, 1_003, 10_000L);
    assertEquals(1L, Reconciler.trades("t", List.of(x), List.of(pastPrice), tol, BreakExplainer.NONE).count(BreakClass.PRICE));
    final ReconItem pastTime = new ReconItem("K", 1, Side.Buy, 100, 1_000, 9_499L);
    assertEquals(1L, Reconciler.trades("t", List.of(x), List.of(pastTime), tol, BreakExplainer.NONE).count(BreakClass.TIMING));
    final ReconItem otherInstrument = new ReconItem("K", 2, Side.Buy, 100, 1_000, 10_000L);
    assertEquals(1L, Reconciler.trades("t", List.of(x), List.of(otherInstrument), tol, BreakExplainer.NONE).count(BreakClass.QUANTITY));
  }

  @Test
  void aDifferingRepeatIsNamedByItsFirstDifferenceAndKeepsAnExplanationGiven() {
    final ReconItem first = new ReconItem("K", 1, Side.Buy, 100, 1_000, 10_000L);
    final BreakExplainer late = (k, c) -> c == BreakClass.TIMING ? Explanation.LATE_ARRIVAL : Explanation.NONE;
    final ReconResult r =
        Reconciler.trades("t", List.of(first, new ReconItem("K", 1, Side.Buy, 100, 1_000, 11_000L),
            new ReconItem("K", 1, Side.Buy, 100, 1_001, 10_000L), new ReconItem("K", 1, Side.Buy, 200, 1_000, 10_000L)),
            List.of(first), Reconciler.Tolerance.DEFAULT, late);
    assertEquals(List.of(BreakClass.TIMING, BreakClass.PRICE, BreakClass.QUANTITY),
        r.breaks().stream().map(Break::breakClass).toList());
    assertEquals(Explanation.LATE_ARRIVAL, r.breaks().get(0).explanation(), "an explanation that applies is kept");
    assertEquals(Explanation.DUPLICATE_CONFLICT, r.breaks().get(1).explanation());
    assertEquals(1, r.matched());
  }
}
