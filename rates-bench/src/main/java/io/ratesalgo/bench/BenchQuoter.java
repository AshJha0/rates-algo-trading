package io.ratesalgo.bench;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.MessageHandler;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import java.nio.ByteBuffer;
import org.HdrHistogram.Histogram;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The handler under measurement: decodes a QuoteRequest, prices a fixed two-way quote, encodes a
 * Quote and publishes it to gateway 0. It records two intervals on the loop thread, both from
 * {@code nowClock.nanoTime()} (the harness passes {@code SystemClock}):
 *
 * <ul>
 *   <li>decode-to-encode, T3 minus T2: handler entry, before the SBE decode, to the Quote published
 *       on the outbound ring (and journaled); recorded with {@code recordValueWithExpectedInterval}
 *       at the generator's interval;
 *   <li>scheduled-to-encode, T3 minus T0: the generator's scheduled send time to the same point;
 *       this includes generator lateness, ring queueing and journaling of the inbound message, and
 *       is coordinated-omission free by construction because it starts from the schedule.
 * </ul>
 *
 * <p>Only requests with {@code rfqId >= warmupMessages} are recorded.
 */
public final class BenchQuoter implements MessageHandler {

  /** Highest value a histogram tracks, 10 seconds in nanoseconds (docs/BENCHMARKS.md). */
  public static final long HIGHEST_NANOS = 10_000_000_000L;

  private final EventLoop loop;
  private final Clock nowClock;
  private final long[] scheduled;
  private final int mask;
  private final long warmupMessages;
  private final long intervalNanos;
  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final QuoteRequestDecoder request = new QuoteRequestDecoder();
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final QuoteEncoder quote = new QuoteEncoder();
  private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final Histogram decodeToEncode = new Histogram(HIGHEST_NANOS, 3);
  private final Histogram scheduledToEncode = new Histogram(HIGHEST_NANOS, 3);
  private long quoteId;

  /**
   * @param scheduled the generator's scheduled send times, indexed by {@code rfqId & (length - 1)};
   *     the length is a power of two
   */
  public BenchQuoter(
      final EventLoop loop,
      final Clock nowClock,
      final long[] scheduled,
      final long warmupMessages,
      final long intervalNanos) {
    if (Integer.bitCount(scheduled.length) != 1) {
      throw new IllegalArgumentException("schedule length must be a power of two");
    }
    this.loop = loop;
    this.nowClock = nowClock;
    this.scheduled = scheduled;
    this.mask = scheduled.length - 1;
    this.warmupMessages = warmupMessages;
    this.intervalNanos = intervalNanos;
  }

  @Override
  public void onMessage(final int templateId, final DirectBuffer buffer, final int offset, final int length) {
    final long t2 = nowClock.nanoTime();
    request.wrapAndApplyHeader(buffer, offset, headerDecoder);
    final long rfqId = request.rfqId();
    final long mid = 100_000_000_000L + request.instrumentId() * 1_000L;
    quote
        .wrapAndApplyHeader(out, 0, headerEncoder)
        .venueId(request.venueId())
        .rfqId(rfqId)
        .quoteId(++quoteId)
        .instrumentId(request.instrumentId());
    quote.bidPrice().mantissa(mid - 15_625_000L);
    quote.askPrice().mantissa(mid + 15_625_000L);
    final long now = loop.clock().epochNanos();
    quote
        .bidQuantity(request.quantity())
        .askQuantity(request.quantity())
        .referenceNanos(now)
        .validUntilNanos(now + 2_000_000_000L)
        .sentNanos(now);
    loop.publish(0, out, 0, quote.limit());
    final long t3 = nowClock.nanoTime();
    if (rfqId >= warmupMessages) {
      decodeToEncode.recordValueWithExpectedInterval(Math.min(t3 - t2, HIGHEST_NANOS), intervalNanos);
      scheduledToEncode.recordValue(Math.min(Math.max(t3 - scheduled[(int) (rfqId & mask)], 0L), HIGHEST_NANOS));
    }
  }

  /** Read after the loop thread has stopped. */
  public Histogram decodeToEncode() {
    return decodeToEncode;
  }

  /** Read after the loop thread has stopped. */
  public Histogram scheduledToEncode() {
    return scheduledToEncode;
  }
}
