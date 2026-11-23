package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import org.junit.jupiter.api.Test;

/**
 * The edges of execution-id dedupe: the bound, a cancelled child waiting for late fills, a resend
 * after a reconnect, and what is forgotten when a child leaves the book.
 */
class ChildOrderBookDedupeTest {

  private static final int V = 1;

  private final ManualClock clock = new ManualClock();

  private static int open(final ChildOrderBook book, final long id, final long qty) {
    return book.open(id, 9, V, Side.Buy, 100L, qty, true);
  }

  private static int fill(
      final ChildOrderBook book, final long id, final long execId, final long qty, final long cum) {
    return book.onExecutionReport(V, id, execId, OrdStatus.PartiallyFilled, 100L, qty, cum);
  }

  @Test
  void atTheBoundANewFillIsRefusedCountedAndBookedOnALaterResend() {
    final ChildOrderBook book = new ChildOrderBook(4, 3, clock, FrameSink.NONE);
    assertEquals(3, book.executionCapacity());
    final int a = open(book, 1, 200);
    final int b = open(book, 2, 1_000);
    assertEquals(ChildOrderBook.FILL, fill(book, 1, 10, 100, 100) & ChildOrderBook.FILL);
    book.onExecutionReport(V, 1, 11, OrdStatus.Filled, 100L, 100, 200);
    fill(book, 2, 12, 100, 100);
    assertEquals(3, book.heldExecutions());

    // full: a new fill is refused, never booked without its id
    final int refused = fill(book, 2, 13, 100, 200);
    assertEquals(ChildOrderBook.REFUSED | ChildOrderBook.ANOMALY, refused & 0x60);
    assertEquals(0, refused & ChildOrderBook.FILL);
    assertEquals(0, book.lastFill());
    assertEquals(1, book.refusedFills());
    assertEquals(1, book.anomalies());
    assertEquals(100, book.cum(b));
    assertFalse(book.hasExecution(V, 13));
    // ids already held still dedupe at the bound
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 1, 10, 100, 100));
    assertEquals(200, book.cum(a));

    // the refused quantity stays outstanding: a cancel does not free it
    book.requestCancel(2, false);
    book.onExecutionReport(V, 2, 14, OrdStatus.Cancelled, 0, 0, 200);
    assertEquals(ChildState.Cancelled, book.state(b));
    assertEquals(800, book.cancelled(b));
    assertEquals(100, book.leaves(b));
    assertFalse(book.release(b));

    // child 1 is done and leaves the book, which frees its two entries
    assertTrue(book.release(a));
    assertEquals(1, book.heldExecutions());
    // the venue resends after a reconnect: the refused fill is booked now, once
    assertEquals(ChildOrderBook.FILL, fill(book, 2, 13, 100, 200) & ChildOrderBook.FILL);
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 2, 13, 100, 200));
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 2, 12, 100, 100));
    assertEquals(200, book.cum(b));
    assertEquals(0, book.leaves(b));
    assertEquals(1, book.refusedFills());
    assertTrue(book.release(b));
    assertEquals(0, book.heldExecutions());
  }

  @Test
  void aCancelledChildWaitingForLateFillsStillDedupesThemUntilReleased() {
    final ChildOrderBook book = new ChildOrderBook(4, 16, clock, FrameSink.NONE);
    final int s = open(book, 3, 500);
    fill(book, 3, 30, 100, 100);
    book.requestCancel(3, false);
    // the venue had filled 300 before cancelling; the fill for the last 200 is still on the way
    book.onExecutionReport(V, 3, 33, OrdStatus.Cancelled, 0, 0, 300);
    assertEquals(ChildState.Cancelled, book.state(s));
    assertEquals(200, book.leaves(s));
    // resent while the child waits: still a duplicate
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 3, 30, 100, 100));
    assertEquals(ChildOrderBook.FILL, fill(book, 3, 31, 200, 300) & ChildOrderBook.FILL);
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 3, 31, 200, 300));
    assertEquals(300, book.cum(s));
    assertEquals(0, book.leaves(s));
    assertTrue(book.hasExecution(V, 31));
    assertTrue(book.release(s));
    // past release the child is unknown: the id is forgotten and the report books nothing
    assertFalse(book.hasExecution(V, 31));
    assertEquals(0, book.heldExecutions());
    assertEquals(ChildOrderBook.UNKNOWN, fill(book, 3, 31, 200, 300));
    assertEquals(ChildOrderBook.UNKNOWN, fill(book, 3, 99, 200, 500));
    assertEquals(2, book.unknown());
  }

  @Test
  void aResendOfEverythingAfterAReconnectBooksOnlyWhatWasMissed() {
    final ChildOrderBook book = new ChildOrderBook(4, 64, clock, FrameSink.NONE);
    final int s = open(book, 5, 1_000);
    book.onExecutionReport(V, 5, 50, OrdStatus.New, 0, 0, 0);
    fill(book, 5, 51, 100, 100);
    fill(book, 5, 52, 100, 200);
    // the session drops; the venue fills 53 and 54 meanwhile and resends 50..54 on logon
    book.onExecutionReport(V, 5, 50, OrdStatus.New, 0, 0, 0);
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 5, 51, 100, 100));
    assertEquals(ChildOrderBook.DUPLICATE, fill(book, 5, 52, 100, 200));
    assertEquals(ChildOrderBook.FILL, fill(book, 5, 53, 100, 300) & ChildOrderBook.FILL);
    assertEquals(ChildOrderBook.FILL, fill(book, 5, 54, 100, 400) & ChildOrderBook.FILL);
    assertEquals(400, book.cum(s));
    assertEquals(2, book.duplicates());
    assertEquals(4, book.heldExecutions());
    // the same id on another venue is a different execution
    assertFalse(book.hasExecution(V + 1, 53));
  }

  @Test
  void theSameIdOnAnotherChildIsADuplicateWhileTheFirstIsInTheBook() {
    final ChildOrderBook book = new ChildOrderBook(4, 8, clock, FrameSink.NONE);
    final int a = open(book, 1, 100);
    final int b = open(book, 2, 100);
    fill(book, 1, 7, 50, 50);
    // as before the bound: a venue repeating an id across orders is not booked twice
    final int r = fill(book, 2, 7, 50, 50);
    assertEquals(ChildOrderBook.DUPLICATE, r & (ChildOrderBook.DUPLICATE | ChildOrderBook.FILL));
    assertEquals(50, book.cum(a));
    assertEquals(0, book.cum(b));
  }

  @Test
  void sizesAreChecked() {
    assertThrows(IllegalArgumentException.class, () -> new ChildOrderBook(1, 0, clock, FrameSink.NONE));
    assertThrows(IllegalArgumentException.class, () -> new ChildExecIds(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new ChildExecIds(1, 0));
    assertThrows(IllegalArgumentException.class, () -> new ChildExecIds(1, (1 << 28) + 1));
    final ChildExecIds one = new ChildExecIds(1, 1);
    assertTrue(one.add(0, 5L));
    assertFalse(one.add(0, 6L));
    assertTrue(one.contains(5L));
    one.clear(0);
    assertFalse(one.contains(5L));
    assertEquals(0, one.size());
  }
}
