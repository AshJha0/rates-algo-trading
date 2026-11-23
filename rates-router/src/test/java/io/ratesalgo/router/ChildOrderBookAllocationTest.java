package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * Execution-id dedupe allocates nothing however many fills a day brings: tens of thousands of
 * fills over children that open, fill, are resent, cancelled with late fills, and leave the book,
 * measured with {@code ThreadMXBean.getCurrentThreadAllocatedBytes} on the calling thread. The
 * measured stretch books more than twice the fills seen in the warm-up, so a set that grew with the
 * day would have to resize inside it.
 */
class ChildOrderBookAllocationTest {

  private static final com.sun.management.ThreadMXBean BEAN =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  private static final long LOT = 100L;
  private static final long PX = 99_000_000_000L;
  private static final int BATCH = 32;

  /** Bytes this thread has allocated; the no-argument form does not itself allocate. */
  private static long allocatedBytes() {
    return BEAN.getCurrentThreadAllocatedBytes();
  }

  /** Drives the book in batches of children that are all in the book at once. */
  private static final class Driver {
    final ChildOrderBook book = new ChildOrderBook(64, 64 * 16, new ManualClock(), FrameSink.NONE);
    final long[] id = new long[BATCH];
    final int[] venue = new int[BATCH];
    final long[] firstExec = new long[BATCH];
    final int[] lots = new int[BATCH];
    long nextId = 1;
    long nextExec = 1;
    long booked;
    int n;

    /** Runs batches until at least {@code target} more fills are booked; returns how many. */
    long run(final long target) {
      final long start = booked;
      while (booked - start < target) {
        batch();
      }
      return booked - start;
    }

    private void batch() {
      for (int b = 0; b < BATCH; b++) {
        final int i = n++;
        id[b] = nextId++;
        venue[b] = 1 + i % 3;
        lots[b] = 2 + i % 9;
        book.open(id[b], 7, venue[b], Side.Buy, PX, LOT * 10, (i & 1) == 0);
        book.onExecutionReport(venue[b], id[b], nextExec++, OrdStatus.New, 0, 0, 0);
      }
      // fills one lot at a time; a cancelled child holds its last fill back as a late fill
      for (int b = 0; b < BATCH; b++) {
        firstExec[b] = nextExec;
        final int shown = cancels(b) ? lots[b] - 1 : lots[b];
        for (int f = 1; f <= shown; f++) {
          report(b, nextExec++, LOT, LOT * f);
        }
      }
      // a reconnect: every fill so far comes again and is dropped
      for (int b = 0; b < BATCH; b++) {
        final int shown = cancels(b) ? lots[b] - 1 : lots[b];
        for (int f = 0; f < shown; f++) {
          report(b, firstExec[b] + f, LOT, LOT * (f + 1));
        }
      }
      for (int b = 0; b < BATCH; b++) {
        if (cancels(b)) {
          book.requestCancel(id[b], false);
          book.onExecutionReport(venue[b], id[b], nextExec++, OrdStatus.Cancelled, 0, 0, LOT * lots[b]);
          final long late = nextExec++;
          report(b, late, LOT, LOT * lots[b]);
          report(b, late, LOT, LOT * lots[b]);
        } else {
          // the rest of the order in one fill
          report(b, nextExec++, LOT * (10 - lots[b]), LOT * 10);
        }
        final int s = book.slotOf(id[b]);
        if (!book.release(s)) {
          throw new AssertionError("child did not finish");
        }
      }
    }

    private boolean cancels(final int b) {
      return (id[b] & 3) == 0;
    }

    private void report(final int b, final long execId, final long qty, final long cum) {
      final int f =
          book.onExecutionReport(venue[b], id[b], execId, OrdStatus.PartiallyFilled, PX, qty, cum);
      if ((f & ChildOrderBook.FILL) != 0) {
        booked++;
      }
    }
  }

  @Test
  void dedupeAllocatesNothingOverALongDay() {
    final Driver d = new Driver();
    // Warm up in short windows until five in a row allocate nothing (C2 may allocate a few bytes
    // while it compiles, as RouterAllocationTest records), then measure one long window in the
    // same loop and call site, so that it is not compiled afresh (DR-INT-08).
    int quiet = 0;
    int windows = 0;
    long warmFills = 0;
    long measuredFills = 0;
    long measuredBytes = -1;
    while (measuredBytes < 0) {
      if (++windows > 200) {
        fail("dedupe did not reach an allocation-free steady state in 200 windows");
      }
      final boolean measure = quiet >= 5 && windows > 10;
      final long target = measure ? Math.max(50_000L, 2 * warmFills + 1) : 1_000L;
      final long start = allocatedBytes();
      final long fills = d.run(target);
      final long bytes = allocatedBytes() - start;
      if (measure) {
        measuredFills = fills;
        measuredBytes = bytes;
      } else {
        warmFills += fills;
        quiet = bytes == 0 ? quiet + 1 : 0;
      }
    }
    assertEquals(0L, measuredBytes, "bytes allocated booking " + measuredFills + " fills");
    assertTrue(measuredFills >= 50_000L);
    assertTrue(measuredFills > 2 * warmFills, "the measured window must outgrow the warm-up");
    assertTrue(d.book.duplicates() > measuredFills / 2, "resends were dropped as duplicates");
    assertEquals(0L, d.book.refusedFills());
    assertEquals(0, d.book.heldExecutions());
    System.out.println(
        "ChildOrderBookAllocationTest: "
            + measuredFills
            + " fills booked with nothing allocated, after "
            + warmFills
            + " in "
            + (windows - 1)
            + " warm-up windows");
  }
}
