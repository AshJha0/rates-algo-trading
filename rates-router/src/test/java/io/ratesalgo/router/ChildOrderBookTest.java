package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.ChildOrderTransitionDecoder;
import io.ratesalgo.messages.sbe.ChildState;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TransitionCause;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/** Each transition of API_SPEC.md Section 15.4, and the audit record it writes. */
class ChildOrderBookTest {

  private final ManualClock clock = new ManualClock();
  private final List<byte[]> frames = new ArrayList<>();
  private final ChildOrderBook book =
      new ChildOrderBook(
          4,
          64,
          clock,
          (templateId, version, buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            frames.add(copy);
          });

  private int open(final long id, final long qty) {
    return book.open(id, 9, 1, Side.Buy, 100L, qty, true);
  }

  @Test
  void ackPartialFillAndFillAreAudited() {
    final int s = open(1, 300);
    book.onExecutionReport(1, 1, 10, OrdStatus.New, 0, 0, 0);
    assertEquals(ChildState.Acknowledged, book.state(s));
    final int f = book.onExecutionReport(1, 1, 11, OrdStatus.PartiallyFilled, 100, 100, 100);
    assertEquals(ChildOrderBook.FILL | ChildOrderBook.STATE, f);
    assertEquals(ChildState.PartiallyFilled, book.state(s));
    assertEquals(100, book.lastFill());
    assertEquals(100, book.lastFillPrice());
    book.onExecutionReport(1, 1, 12, OrdStatus.Filled, 101, 200, 300);
    assertEquals(ChildState.Filled, book.state(s));
    assertEquals(100.0 * 100 + 101.0 * 200, book.notional(s));
    assertEquals(4, frames.size());
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final ChildOrderTransitionDecoder d = new ChildOrderTransitionDecoder();
    d.wrapAndApplyHeader(new UnsafeBuffer(frames.get(3)), 0, header);
    assertEquals(ChildState.PartiallyFilled, d.fromState());
    assertEquals(ChildState.Filled, d.toState());
    assertEquals(TransitionCause.Fill, d.cause());
    assertEquals(300, d.cumQuantity());
    assertEquals(0, d.leavesQuantity());
    assertEquals(12, d.execId());
    assertTrue(book.release(s));
    assertEquals(-1, book.slotOf(1));
  }

  @Test
  void duplicateStaleUnknownAndAnomaliesAreCounted() {
    final int s = open(2, 200);
    book.onExecutionReport(1, 2, 20, OrdStatus.PartiallyFilled, 100, 100, 100);
    assertEquals(
        ChildOrderBook.DUPLICATE,
        book.onExecutionReport(1, 2, 20, OrdStatus.PartiallyFilled, 100, 100, 100));
    assertEquals(1, book.duplicates());
    assertEquals(
        ChildOrderBook.STALE, book.onExecutionReport(1, 2, 21, OrdStatus.New, 0, 0, 0));
    assertEquals(1, book.stale());
    assertEquals(
        ChildOrderBook.UNKNOWN, book.onExecutionReport(1, 99, 22, OrdStatus.New, 0, 0, 0));
    assertEquals(1, book.unknown());
    assertEquals(
        ChildOrderBook.ANOMALY, book.onExecutionReport(2, 2, 23, OrdStatus.New, 0, 0, 0));
    assertEquals(
        ChildOrderBook.ANOMALY,
        book.onExecutionReport(1, 2, 24, OrdStatus.PartiallyFilled, 100, 500, 600) & 32);
    assertEquals(
        ChildOrderBook.ANOMALY,
        book.onExecutionReport(1, 2, 1L << 50, OrdStatus.PartiallyFilled, 100, 10, 110) & 32);
    assertEquals(
        ChildOrderBook.ANOMALY, book.onExecutionReport(1, 2, 25, OrdStatus.NULL_VAL, 0, 0, 0));
    assertEquals(4, book.anomalies());
    assertEquals(100, book.cum(s));
    assertEquals(ChildOrderBook.STALE, book.onExecutionReport(1, 2, 26, OrdStatus.Rejected, 0, 0, 0));
  }

  @Test
  void cancelConfirmedAfterUnseenFillsKeepsThemPending() {
    final int s = open(3, 500);
    book.onExecutionReport(1, 3, 30, OrdStatus.New, 0, 0, 0);
    assertTrue(book.requestCancel(3, false));
    assertFalse(book.requestCancel(3, false));
    assertEquals(ChildState.CancelRequested, book.state(s));
    // the venue filled 200 before cancelling, but that fill report is late
    book.onExecutionReport(1, 3, 32, OrdStatus.Cancelled, 0, 0, 200);
    assertEquals(ChildState.Cancelled, book.state(s));
    assertEquals(300, book.cancelled(s));
    assertEquals(300, book.lastFreed());
    assertEquals(200, book.leaves(s));
    assertFalse(book.release(s));
    book.onExecutionReport(1, 3, 31, OrdStatus.PartiallyFilled, 100, 200, 200);
    assertEquals(200, book.cum(s));
    assertEquals(0, book.leaves(s));
    assertEquals(ChildState.Cancelled, book.state(s));
    assertEquals(ChildOrderBook.STALE, book.onExecutionReport(1, 3, 33, OrdStatus.Cancelled, 0, 0, 200));
    assertTrue(book.release(s));
  }

  @Test
  void replaceIntentEndsReplacedAndCancelRejectRestores() {
    final int s = open(4, 300);
    book.onExecutionReport(1, 4, 40, OrdStatus.PartiallyFilled, 100, 100, 100);
    assertTrue(book.requestCancel(4, true));
    assertTrue(book.replaceIntent(s));
    assertTrue(book.onCancelReject(1, 4));
    assertEquals(ChildState.PartiallyFilled, book.state(s));
    assertFalse(book.onCancelReject(1, 4));
    assertTrue(book.requestCancel(4, true));
    book.onExecutionReport(1, 4, 41, OrdStatus.Cancelled, 0, 0, 100);
    assertEquals(ChildState.Replaced, book.state(s));
    assertEquals(200, book.cancelled(s));

    final int t = open(5, 100);
    assertTrue(book.requestCancel(5, false));
    assertTrue(book.onCancelReject(1, 5));
    assertEquals(ChildState.New, book.state(t));
  }

  @Test
  void fillRacingACancelCompletesTheOrder() {
    final int s = open(6, 200);
    book.requestCancel(6, false);
    book.onExecutionReport(1, 6, 60, OrdStatus.Cancelled, 0, 0, 200);
    assertEquals(ChildState.CancelRequested, book.state(s));
    book.onExecutionReport(1, 6, 61, OrdStatus.Filled, 100, 200, 200);
    assertEquals(ChildState.Filled, book.state(s));
    assertEquals(
        ChildOrderBook.STALE, book.onExecutionReport(1, 6, 62, OrdStatus.Filled, 0, 0, 200));
  }

  @Test
  void rejectAndReconcile() {
    final int s = open(7, 100);
    book.onExecutionReport(1, 7, 70, OrdStatus.Rejected, 0, 0, 0);
    assertEquals(ChildState.Rejected, book.state(s));
    assertEquals(100, book.cancelled(s));
    book.release(s);

    final int a = open(8, 300);
    final int b = open(9, 300);
    final int c = open(10, 300);
    assertEquals(300, book.reconcile(8, false, OrdStatus.Cancelled, 0));
    assertEquals(ChildState.Cancelled, book.state(a));
    assertEquals(200, book.reconcile(9, true, OrdStatus.Cancelled, 100));
    assertEquals(100, book.leaves(b));
    assertEquals(0, book.reconcile(10, true, OrdStatus.New, 0));
    assertEquals(ChildState.Acknowledged, book.state(c));
    assertEquals(0, book.reconcile(10, true, OrdStatus.Filled, 300));
    assertEquals(300, book.leaves(c));
    assertEquals(0, book.reconcile(8, true, OrdStatus.New, 0));
    assertEquals(0, book.reconcile(12345, true, OrdStatus.New, 0));
    final int d = book.slotOf(9);
    assertEquals(ChildState.Cancelled, book.state(d));
  }

  @Test
  void rejectedReconcileAndCapacity() {
    open(11, 100);
    open(12, 100);
    open(13, 100);
    open(14, 100);
    assertThrows(IllegalStateException.class, () -> open(15, 100));
    assertThrows(IllegalArgumentException.class, () -> open(11, 100));
    assertEquals(100, book.reconcile(11, true, OrdStatus.Rejected, 0));
    assertEquals(ChildState.Rejected, book.state(book.slotOf(11)));
    assertThrows(
        IllegalArgumentException.class, () -> new ChildOrderBook(0, 1, clock, FrameSink.NONE));
    book.resetDay();
    assertEquals(4, book.highWater());
    assertTrue(book.inUse(0));
    assertEquals(9, book.parentId(0));
    assertEquals(1, book.venueId(0));
    assertEquals(Side.Buy, book.side(0));
    assertEquals(100L, book.price(0));
    assertEquals(100, book.ordered(0));
    assertTrue(book.passive(0));
    assertEquals(11, book.clOrdId(0));
  }

  @Test
  void zeroQuantityIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> open(20, 0));
  }
}
