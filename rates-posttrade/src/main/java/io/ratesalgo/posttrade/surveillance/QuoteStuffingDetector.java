package io.ratesalgo.posttrade.surveillance;

import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;

/**
 * Quote stuffing: an unusually high rate of our own quotes and orders in one instrument. Counts
 * outbound Quote and NewOrder frames per instrument over a sliding window and alerts when the count
 * exceeds the threshold; one alert per episode, re-armed when the rate falls back to half the
 * threshold. Our own message-rate limit (RTS 6 Article 15) should stop this first; the detector
 * evidences that it did.
 */
public final class QuoteStuffingDetector implements SurveillanceDetector {

  private final long windowNanos;
  private final int threshold;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder order = new NewOrderDecoder();
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
    if (templateId == QuoteDecoder.TEMPLATE_ID) {
      quote.wrapAndApplyHeader(buffer, offset, header);
      instrument = quote.instrumentId();
    } else if (templateId == NewOrderDecoder.TEMPLATE_ID) {
      order.wrapAndApplyHeader(buffer, offset, header);
      instrument = order.instrumentId();
    } else {
      return;
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
          w.size() + " quotes and orders in " + windowNanos + " ns, threshold " + threshold));
    } else if (episode && w.size() <= threshold / 2) {
      inEpisode.put(instrument, false);
    }
  }

  @Override
  public List<SurveillanceAlert> alerts() {
    return List.copyOf(alerts);
  }
}
