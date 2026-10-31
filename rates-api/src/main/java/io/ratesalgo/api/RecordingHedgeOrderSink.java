package io.ratesalgo.api;

import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test and recipe stub for the router: records every parent order and every cancel. Not for the
 * hot path; it allocates a record per order.
 */
public final class RecordingHedgeOrderSink implements HedgeOrderSink {

  /** One recorded parent order. */
  public record Parent(
      long parentId,
      int instrumentId,
      Side side,
      long quantityMinor,
      long limitPrice,
      long urgencyNanos,
      long sentNanos) {}

  private final List<Parent> parents = new ArrayList<>();
  private final List<Long> cancelled = new ArrayList<>();
  private int cancelAllCount;
  private boolean accepting = true;

  @Override
  public boolean submit(
      final long parentId,
      final int instrumentId,
      final Side side,
      final long quantityMinor,
      final long limitPrice,
      final long urgencyNanos,
      final long sentNanos) {
    if (!accepting) {
      return false;
    }
    parents.add(
        new Parent(parentId, instrumentId, side, quantityMinor, limitPrice, urgencyNanos, sentNanos));
    return true;
  }

  @Override
  public void cancel(final long parentId) {
    cancelled.add(parentId);
  }

  @Override
  public void cancelAll() {
    cancelAllCount++;
  }

  /** Makes later submits return false, as a disconnected router would. */
  public void accepting(final boolean value) {
    accepting = value;
  }

  /** Recorded parents, oldest first. */
  public List<Parent> parents() {
    return Collections.unmodifiableList(parents);
  }

  /** Parent ids cancelled one by one. */
  public List<Long> cancelled() {
    return Collections.unmodifiableList(cancelled);
  }

  /** Number of {@link #cancelAll()} calls. */
  public int cancelAllCount() {
    return cancelAllCount;
  }

  /** Forgets everything recorded. */
  public void clear() {
    parents.clear();
    cancelled.clear();
    cancelAllCount = 0;
  }
}
