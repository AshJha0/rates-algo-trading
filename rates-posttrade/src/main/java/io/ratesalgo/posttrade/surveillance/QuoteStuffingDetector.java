package io.ratesalgo.posttrade.surveillance;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrderCancelDecoder;
import io.ratesalgo.messages.sbe.OrderReplaceDecoder;
import io.ratesalgo.messages.sbe.OrderWithdrawDecoder;
import io.ratesalgo.messages.sbe.QuoteCancelDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;

/**
 * Quote stuffing: an unusually high rate of our own messages in one instrument. Counts outbound
 * Quote and NewOrder frames and the cancels and replaces that go with them (OrderCancel,
 * OrderReplace, QuoteCancel, QuoteWithdraw, OrderWithdraw) per instrument over a sliding window,
 * and alerts when the count exceeds the threshold; one alert per episode, re-armed when the rate
 * falls back to half the threshold. Cancels count because venue order-to-trade policies count them
 * (PNL_AUDIT.md item 18); {@link #messages} and {@link #cancels} keep the day's totals. Our own message-rate limit (RTS 6 Article 15) should stop this first; the detector
 * evidences that it did.
 */
public final class QuoteStuffingDetector implements SurveillanceDetector {

  private final long windowNanos;
  private final int threshold;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder order = new NewOrderDecoder();
  private final OrderCancelDecoder orderCancel = new OrderCancelDecoder();
  private final OrderReplaceDecoder orderReplace = new OrderReplaceDecoder();
  private final QuoteCancelDecoder quoteCancel = new QuoteCancelDecoder();
  private final QuoteWithdrawDecoder quoteWithdraw = new QuoteWithdrawDecoder();
  private final OrderWithdrawDecoder orderWithdraw = new OrderWithdrawDecoder();
  private final Map<Integer, long[]> totals = new HashMap<>();
  private final Map<Integer, ArrayDeque<Long>> windows = new HashMap<>();
  private final Map<Integer, Boolean> inEpisode = new HashMap<>();
  private final List<SurveillanceAlert> alerts = new ArrayList<>();

  /**
   * Creates a detector.
   *
   * @param windowNanos sliding window length
   * @param threshold messages per window per instrument above which an alert is raised
   */
  public QuoteStuffingDetector(final long windowNanos, final int threshold) {
    if (windowNanos <= 0 || threshold <= 0) {
      throw new IllegalArgumentException("window and threshold must be positive");
    }
    this.windowNanos = windowNanos;
    this.threshold = threshold;
  }

  @Override
  public String name() {
    return "quote-stuffing";
  }

  @Override
  public void onFrame(
      final long sequence, final long timestampNanos, final int templateId, final int schemaVersion,
      final DirectBuffer buffer, final int offset, final int length) {
    final int instrument;
    final boolean cancel;
    switch (templateId) {
      case QuoteDecoder.TEMPLATE_ID -> {
        quote.wrapAndApplyHeader(buffer, offset, header);
        instrument = quote.instrumentId();
        cancel = false;
      }
      case NewOrderDecoder.TEMPLATE_ID -> {
        order.wrapAndApplyHeader(buffer, offset, header);
        instrument = order.instrumentId();
        cancel = false;
      }
      case OrderCancelDecoder.TEMPLATE_ID -> {
        orderCancel.wrapAndApplyHeader(buffer, offset, header);
        instrument = orderCancel.instrumentId();
        cancel = true;
      }
      case OrderReplaceDecoder.TEMPLATE_ID -> {
        orderReplace.wrapAndApplyHeader(buffer, offset, header);
        instrument = orderReplace.instrumentId();
        cancel = true;
      }
      case QuoteCancelDecoder.TEMPLATE_ID -> {
        quoteCancel.wrapAndApplyHeader(buffer, offset, header);
        instrument = quoteCancel.instrumentId();
        cancel = true;
      }
      case QuoteWithdrawDecoder.TEMPLATE_ID -> {
        quoteWithdraw.wrapAndApplyHeader(buffer, offset, header);
        instrument = quoteWithdraw.instrumentId();
        cancel = true;
      }
      case OrderWithdrawDecoder.TEMPLATE_ID -> {
        orderWithdraw.wrapAndApplyHeader(buffer, offset, header);
        instrument = orderWithdraw.instrumentId();
        cancel = true;
      }
      default -> {
        return;
      }
    }
    final long[] total = totals.computeIfAbsent(instrument, i -> new long[2]);
    total[0]++;
    if (cancel) {
      total[1]++;
    }
    final ArrayDeque<Long> w = windows.computeIfAbsent(instrument, i -> new ArrayDeque<>());
    w.addLast(timestampNanos);
    while (!w.isEmpty() && w.peekFirst() <= timestampNanos - windowNanos) {
      w.removeFirst();
    }
    final boolean episode = inEpisode.getOrDefault(instrument, false);
    if (!episode && w.size() > threshold) {
      inEpisode.put(instrument, true);
      alerts.add(new SurveillanceAlert(name(), instrument, sequence, timestampNanos,
          w.size() + " quotes, orders and cancels in " + windowNanos + " ns, threshold " + threshold));
    } else if (episode && w.size() <= threshold / 2) {
      inEpisode.put(instrument, false);
    }
  }

  /** Messages counted for an instrument so far, cancels and replaces included. */
  public long messages(final int instrumentId) {
    final long[] t = totals.get(instrumentId);
    return t == null ? 0L : t[0];
  }

  /** Cancels, replaces and withdrawals counted for an instrument so far. */
  public long cancels(final int instrumentId) {
    final long[] t = totals.get(instrumentId);
    return t == null ? 0L : t[1];
  }

  /** Instruments with at least one counted message, ascending. */
  public List<Integer> instruments() {
    return totals.keySet().stream().sorted().toList();
  }

  @Override
  public List<SurveillanceAlert> alerts() {
    return List.copyOf(alerts);
  }
}
