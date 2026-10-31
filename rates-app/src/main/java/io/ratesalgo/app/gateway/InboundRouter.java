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
 *   <li>A full ring: market data (BookUpdate, CurveInput, Heartbeat) is dropped and counted, the
 *       next update supersedes it; session messages (QuoteRequest, QuoteResponse,
 *       ExecutionReport, TradeBust, GatewayStatus, KillSwitch) are retried until the loop takes
 *       them, because losing one would leave the loop's view of orders and quotes wrong.
 * </ul>
 */
@HotPath
public final class InboundRouter implements MessageSink {

  private static final int HEADER = RingBufferContract.SBE_HEADER_LENGTH;

  private final InboundPublisher[] publishers;
  private final Int2IntHashMap groupOf;
  private final Long2LongHashMap rfqGroup = new Long2LongHashMap(4096, 0.6f, -1L);
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final QuoteRequestDecoder quoteRequest = new QuoteRequestDecoder();
  private final QuoteResponseDecoder quoteResponse = new QuoteResponseDecoder();
  private long dropped;
  private long retries;
  private long unrouted;

  /**
   * A router over one inbound ring per group.
   *
   * @param rings inbound ring of each group, by group index
   * @param groupOf instrument id to group index
   */
  public InboundRouter(final RingBuffer[] rings, final Int2IntHashMap groupOf) {
    this.publishers = new InboundPublisher[rings.length];
    for (int g = 0; g < rings.length; g++) {
      publishers[g] = new InboundPublisher(rings[g]);
    }
    this.groupOf = groupOf;
  }

  @Override
  public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
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
        return false;
      }
      return publish((int) g, buffer, offset, length, false);
    }
    if (instrumentId == 0) {
      return toAll(buffer, offset, length, marketData);
    }
    final int g = groupOf.get(instrumentId);
    if (g < 0) {
      unrouted++;
      return false;
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
      default -> 0;
    };
  }

  private boolean toAll(final DirectBuffer buffer, final int offset, final int length, final boolean marketData) {
    boolean all = true;
    for (int g = 0; g < publishers.length; g++) {
      all &= publish(g, buffer, offset, length, marketData);
    }
    return all;
  }

  private boolean publish(final int g, final DirectBuffer buffer, final int offset, final int length, final boolean marketData) {
    final InboundPublisher p = publishers[g];
    if (p.offer(buffer, offset, length)) {
      return true;
    }
    if (marketData) {
      dropped++;
      return false;
    }
    do {
      retries++;
      Thread.onSpinWait();
    } while (!p.offer(buffer, offset, length));
    return true;
  }

  /** Market data dropped on a full ring. */
  public long dropped() {
    return dropped;
  }

  /** Retries of session messages on a full ring. */
  public long retries() {
    return retries;
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
