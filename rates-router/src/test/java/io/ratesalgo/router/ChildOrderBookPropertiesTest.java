package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The child order state machine against an in-memory venue whose reports are duplicated,
 * arbitrarily reordered and delivered late. Properties: no lost quantity (the final cumulative
 * quantity equals what the venue filled), {@code cum + leaves + cancelled == ordered} after every
 * report, cumulative never above ordered, no anomaly, and every child ends terminal.
 */
class ChildOrderBookPropertiesTest {

  private static final long LOT = 100L;

  /** One report as the venue sent it. */
  private record Report(
      int venue, long id, long execId, OrdStatus status, long px, long last, long cum) {}

  @Property(tries = 400)
  void quantityIsConservedUnderDuplicatesReordersAndLateFills(
      @ForAll @IntRange(min = 1, max = 12) final int children,
      @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) final long seed,
      @ForAll @IntRange(min = 0, max = 100) final int duplicatePercent) {
    final Random rnd = new Random(seed);
    final ManualClock clock = new ManualClock();
    final ChildOrderBook book = new ChildOrderBook(32, 1_024, clock, FrameSink.NONE);
    final List<Report> reports = new ArrayList<>();
    final long[] trueCum = new long[children];
    final long[] ordered = new long[children];
    final boolean[] cancelWanted = new boolean[children];
    long execId = 1;
    for (int c = 0; c < children; c++) {
      final long id = 1_000L + c;
      final int venue = 1 + rnd.nextInt(3);
      ordered[c] = LOT * (1 + rnd.nextInt(20));
      book.open(id, 7, venue, Side.Buy, 99_000_000_000L, ordered[c], rnd.nextBoolean());
      final boolean reject = rnd.nextInt(10) == 0;
      if (reject) {
        reports.add(new Report(venue, id, execId++, OrdStatus.Rejected, 0, 0, 0));
        continue;
      }
      reports.add(new Report(venue, id, execId++, OrdStatus.New, 0, 0, 0));
      cancelWanted[c] = rnd.nextBoolean();
      final long target =
          cancelWanted[c] ? LOT * rnd.nextInt((int) (ordered[c] / LOT) + 1) : ordered[c];
      long cum = 0;
      while (cum < target) {
        final long q = Math.min(target - cum, LOT * (1 + rnd.nextInt(5)));
        cum += q;
        final OrdStatus st = cum == ordered[c] ? OrdStatus.Filled : OrdStatus.PartiallyFilled;
        reports.add(new Report(venue, id, execId++, st, 99_000_000_000L + rnd.nextInt(5), q, cum));
      }
      trueCum[c] = cum;
      if (cum < ordered[c]) {
        reports.add(new Report(venue, id, execId++, OrdStatus.Cancelled, 0, 0, cum));
      }
    }
    // duplicates, then an arbitrary order: late fills after cancels, acks after fills
    final int n = reports.size();
    for (int i = 0; i < n; i++) {
      if (rnd.nextInt(100) < duplicatePercent) {
        reports.add(reports.get(rnd.nextInt(n)));
      }
    }
    Collections.shuffle(reports, rnd);

    int delivered = 0;
    for (final Report r : reports) {
      if (delivered == reports.size() / 3) {
        for (int c = 0; c < children; c++) {
          if (cancelWanted[c]) {
            book.requestCancel(1_000 + c, rnd.nextBoolean());
          }
        }
      }
      book.onExecutionReport(r.venue(), r.id(), r.execId(), r.status(), r.px(), r.last(), r.cum());
      delivered++;
      for (int c = 0; c < children; c++) {
        final int s = book.slotOf(1_000 + c);
        assertEquals(ordered[c], book.cum(s) + book.leaves(s) + book.cancelled(s));
        assertTrue(book.leaves(s) >= 0, "negative leaves");
        assertTrue(book.cum(s) <= ordered[c], "child exceeds its ordered quantity");
      }
    }
    assertEquals(0, book.anomalies());
    for (int c = 0; c < children; c++) {
      final int s = book.slotOf(1_000 + c);
      assertEquals(trueCum[c], book.cum(s), "lost or invented quantity");
      assertEquals(0, book.leaves(s));
      final ChildState st = book.state(s);
      assertTrue(ChildOrderBook.isTerminal(st), "not terminal: " + st);
      if (trueCum[c] == ordered[c]) {
        assertEquals(ChildState.Filled, st);
      }
      assertTrue(book.release(s));
    }
  }
}
