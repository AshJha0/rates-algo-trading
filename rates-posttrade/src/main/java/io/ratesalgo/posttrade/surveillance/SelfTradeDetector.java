package io.ratesalgo.posttrade.surveillance;

import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;

/**
 * Possible self-trades (wash trades): a buy fill and a sell fill of ours in the same instrument on
 * the same venue at the same price within a short window. Our ExecutionReport does not name the
 * counterparty, so the pattern is inferred from price and time; the venue's self-match prevention
 * flag (Annex II Table 3 field 22) and the counterparty on the drop copy settle the question in
 * review.
 */
public final class SelfTradeDetector implements SurveillanceDetector {

  private record Fill(Side side, long price, long quantity, long nanos, long execId) {}

  private record Slot(int venueId, int instrumentId) {}

  private final long windowNanos;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final ExecutionReportDecoder er = new ExecutionReportDecoder();
  private final Map<Slot, ArrayDeque<Fill>> recent = new HashMap<>();
  private final List<SurveillanceAlert> alerts = new ArrayList<>();

  /** Creates a detector matching opposite fills within {@code windowNanos}. */
  public SelfTradeDetector(final long windowNanos) {
    if (windowNanos <= 0) {
      throw new IllegalArgumentException("window must be positive");
    }
    this.windowNanos = windowNanos;
  }

  @Override
  public String name() {
    return "self-trade";
  }

  @Override
  public void onFrame(
      final long sequence, final long timestampNanos, final int templateId, final int schemaVersion,
      final DirectBuffer buffer, final int offset, final int length) {
    if (templateId != ExecutionReportDecoder.TEMPLATE_ID) {
      return;
    }
    er.wrapAndApplyHeader(buffer, offset, header);
    final OrdStatus status = er.ordStatus();
    if (er.lastQuantity() <= 0 || (status != OrdStatus.Filled && status != OrdStatus.PartiallyFilled)) {
      return;
    }
    final Fill f = new Fill(er.side(), er.lastPrice().mantissa(), er.lastQuantity(), er.transactNanos(),
        er.execId());
    final Slot slot = new Slot(er.venueId(), er.instrumentId());
    final ArrayDeque<Fill> q = recent.computeIfAbsent(slot, s -> new ArrayDeque<>());
    for (final Iterator<Fill> it = q.iterator(); it.hasNext(); ) {
      final Fill g = it.next();
      if (g.nanos() < f.nanos() - windowNanos) {
        it.remove();
      } else if (g.side() != f.side() && g.price() == f.price()) {
        alerts.add(new SurveillanceAlert(name(), slot.instrumentId(), sequence, timestampNanos,
            "venue " + slot.venueId() + " exec " + g.execId() + " " + g.side() + " and exec "
                + f.execId() + " " + f.side() + " at " + f.price() + " within "
                + (f.nanos() - g.nanos()) + " ns"));
      }
    }
    q.addLast(f);
  }

  @Override
  public List<SurveillanceAlert> alerts() {
    return List.copyOf(alerts);
  }
}
