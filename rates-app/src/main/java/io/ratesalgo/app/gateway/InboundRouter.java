package io.ratesalgo.app.gateway;

import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.CurveInputDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.HeartbeatDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.OrderCancelRejectDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * A gateway thread's sink into the instrument groups' inbound rings (API_SPEC.md Sections 9.3
 * and 18.5). One instance per publishing thread.
 *
 * <ul>
 *   <li>Partitioning: a message naming an instrument goes to that instrument's group; a
 *       QuoteResponse names none and goes to the group that received its QuoteRequest (remembered
 *       by venue and RFQ id until a final, non-Rejected response passes); Heartbeat, GatewayStatus
 *       and other messages go to every group.
 *   <li>A full ring, by what losing the message would cost:
 *       <ul>
 *         <li>CurveInput and Heartbeat are level messages: the next one supersedes a lost one, so
 *             they are dropped and counted ({@link #dropped()}).
 *         <li>BookUpdate is a delta: a lost one leaves a wrong book that no later delta repairs.
 *             It is not retried here (the book feed is faster than a stalled loop), but the
 *             refusal is reported to the caller ({@link #route} returns {@link #FULL}), and the
 *             {@link FeedBinding} then stops the feed for that venue, tells the loop its book is
 *             not to be trusted and rebuilds it from a snapshot. Counted in {@link #dropped()}.
 *         <li>Session messages (QuoteRequest, QuoteResponse, ExecutionReport, OrderCancelReject,
 *             TradeBust, GatewayStatus, KillSwitch) are retried for a bounded number of spins.
 *             If the loop still has not made room, the message is refused, counted in {@link
 *             #undelivered()} and reported to the {@link UndeliveredHandler}: the loop's view of
 *             orders and quotes is then wrong, and the process must act on it (halt, cancel and
 *             reconcile), which a gateway thread spinning for ever would never let it do.
 *       </ul>
 * </ul>
 */
@HotPath
public final class InboundRouter implements MessageSink {

  /** {@link #route}: the message is in every ring it was meant for. */
  public static final int DELIVERED = 0;

  /** {@link #route}: no group owns the instrument or the RFQ; nothing was lost. */
  public static final int UNROUTED = 1;

  /** {@link #route}: a ring was full and the message is not in it. */
  public static final int FULL = 2;

  /**
   * Spins a session message waits for room by default: tens of milliseconds on a free core,
   * thousands of times what a running loop needs to drain a ring.
   */
  public static final int DEFAULT_SPIN_LIMIT = 1 << 20;

  /** Told of each session message refused after the bounded wait. */
  @FunctionalInterface
  public interface UndeliveredHandler {

    /** Ignores it; the counter still moves. */
    UndeliveredHandler NONE = (templateId, group) -> {};

    /**
     * A session message could not be delivered.
     *
     * @param templateId its SBE template
     * @param group the group whose ring stayed full
     */
    void onUndelivered(int templateId, int group);
  }

  private static final int HEADER = RingBufferContract.SBE_HEADER_LENGTH;

  private final InboundPublisher[] publishers;
  private final Int2IntHashMap groupOf;
  private final Long2LongHashMap rfqGroup = new Long2LongHashMap(4096, 0.6f, -1L);
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteRequestDecoder quoteRequest = new QuoteRequestDecoder();
  private final QuoteResponseDecoder quoteResponse = new QuoteResponseDecoder();
  private final int spinLimit;
  private final Runnable onSpin;
  private UndeliveredHandler undeliveredHandler = UndeliveredHandler.NONE;
  private long dropped;
  private long retries;
  private long unrouted;
  private long undelivered;

  /**
   * A router over one inbound ring per group.
   *
   * @param rings inbound ring of each group, by group index
   * @param groupOf instrument id to group index
   */
  public InboundRouter(final RingBuffer[] rings, final Int2IntHashMap groupOf) {
    this(rings, groupOf, DEFAULT_SPIN_LIMIT, Thread::onSpinWait);
  }

  /**
   * A router with its own bound on the wait for room.
   *
   * @param rings inbound ring of each group, by group index
   * @param groupOf instrument id to group index
   * @param spinLimit spins a session message waits for a full ring before it is refused
   * @param onSpin what one spin does (tests drain the ring here)
   */
  InboundRouter(final RingBuffer[] rings, final Int2IntHashMap groupOf, final int spinLimit, final Runnable onSpin) {
    if (spinLimit < 0) {
      throw new IllegalArgumentException("spinLimit must be >= 0: " + spinLimit);
    }
    this.spinLimit = spinLimit;
    this.onSpin = onSpin;
    this.publishers = new InboundPublisher[rings.length];
    for (int g = 0; g < rings.length; g++) {
      publishers[g] = new InboundPublisher(rings[g]);
    }
    this.groupOf = groupOf;
  }

  /** Sets who is told of a session message refused after the bounded wait. */
  public void undeliveredHandler(final UndeliveredHandler handler) {
    this.undeliveredHandler = handler;
  }

  @Override
  public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
    return route(buffer, offset, length) == DELIVERED;
  }

  /**
   * Offers one message, saying why if it was not delivered: {@link #DELIVERED}, {@link #UNROUTED}
   * or {@link #FULL}. A caller that must not lose the message (the book feed) acts on FULL.
   */
  public int route(final DirectBuffer buffer, final int offset, final int length) {
    final int templateId = RingBufferContract.templateId(buffer, offset);
    if (RingBufferContract.schemaId(buffer, offset) == ControlMessages.SCHEMA_ID) {
      return toAll(buffer, offset, length, false);
    }
    final int instrumentId = instrumentOf(templateId, buffer, offset);
    final boolean marketData =
        templateId == BookUpdateDecoder.TEMPLATE_ID
            || templateId == CurveInputDecoder.TEMPLATE_ID
            || templateId == HeartbeatDecoder.TEMPLATE_ID;
    if (templateId == QuoteResponseDecoder.TEMPLATE_ID) {
      quoteResponse.wrapAndApplyHeader(buffer, offset, header);
      final long key = rfqKey(quoteResponse.venueId(), quoteResponse.rfqId());
      final long g = quoteResponse.status() == QuoteStatus.Rejected ? rfqGroup.get(key) : rfqGroup.remove(key);
      if (g < 0) {
        unrouted++;
        return UNROUTED;
      }
      return publish((int) g, buffer, offset, length, false);
    }
    if (instrumentId == 0) {
      return toAll(buffer, offset, length, marketData);
    }
    final int g = groupOf.get(instrumentId);
    if (g < 0) {
      unrouted++;
      return UNROUTED;
    }
    if (templateId == QuoteRequestDecoder.TEMPLATE_ID) {
      quoteRequest.wrapAndApplyHeader(buffer, offset, header);
      rfqGroup.put(rfqKey(quoteRequest.venueId(), quoteRequest.rfqId()), g);
    }
    return publish(g, buffer, offset, length, marketData);
  }

  private static long rfqKey(final int venueId, final long rfqId) {
    return ((long) venueId << 48) ^ rfqId;
  }

  /** Instrument id named by a message, 0 if none. */
  private static int instrumentOf(final int templateId, final DirectBuffer buffer, final int offset) {
    final int body = offset + HEADER;
    return switch (templateId) {
      case BookUpdateDecoder.TEMPLATE_ID -> buffer.getInt(body + BookUpdateDecoder.instrumentIdEncodingOffset(), ByteOrder.LITTLE_ENDIAN);
      case CurveInputDecoder.TEMPLATE_ID -> buffer.getInt(body + CurveInputDecoder.instrumentIdEncodingOffset(), ByteOrder.LITTLE_ENDIAN);
      case QuoteRequestDecoder.TEMPLATE_ID -> buffer.getInt(body + QuoteRequestDecoder.instrumentIdEncodingOffset(), ByteOrder.LITTLE_ENDIAN);
      case ExecutionReportDecoder.TEMPLATE_ID -> buffer.getInt(body + ExecutionReportDecoder.instrumentIdEncodingOffset(), ByteOrder.LITTLE_ENDIAN);
      case TradeBustDecoder.TEMPLATE_ID -> buffer.getInt(body + TradeBustDecoder.instrumentIdEncodingOffset(), ByteOrder.LITTLE_ENDIAN);
      case OrderCancelRejectDecoder.TEMPLATE_ID -> buffer.getInt(body + OrderCancelRejectDecoder.instrumentIdEncodingOffset(), ByteOrder.LITTLE_ENDIAN);
      default -> 0;
    };
  }

  private int toAll(final DirectBuffer buffer, final int offset, final int length, final boolean marketData) {
    int result = DELIVERED;
    for (int g = 0; g < publishers.length; g++) {
      if (publish(g, buffer, offset, length, marketData) == FULL) {
        result = FULL;
      }
    }
    return result;
  }

  private int publish(final int g, final DirectBuffer buffer, final int offset, final int length, final boolean marketData) {
    final InboundPublisher p = publishers[g];
    if (p.offer(buffer, offset, length)) {
      return DELIVERED;
    }
    if (marketData) {
      dropped++;
      return FULL;
    }
    for (int spin = 0; spin < spinLimit; spin++) {
      retries++;
      onSpin.run();
      if (p.offer(buffer, offset, length)) {
        return DELIVERED;
      }
    }
    // The loop has not read for the whole wait. Blocking for ever would also stop this thread's
    // heartbeats, logons and cancels, so the message is refused and the process is told.
    undelivered++;
    undeliveredHandler.onUndelivered(RingBufferContract.templateId(buffer, offset), g);
    return FULL;
  }

  /** Market data refused on a full ring: superseded levels, and book deltas the feed recovers. */
  public long dropped() {
    return dropped;
  }

  /** Retries of session messages on a full ring. */
  public long retries() {
    return retries;
  }

  /** Session messages refused because a ring stayed full for the whole bounded wait. */
  public long undelivered() {
    return undelivered;
  }

  /** Messages for an instrument or RFQ no group owns. */
  public long unrouted() {
    return unrouted;
  }

  /** Messages published, over every group. */
  public long published() {
    long n = 0;
    for (final InboundPublisher p : publishers) {
      n += p.publishedCount();
    }
    return n;
  }
}
