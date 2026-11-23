package io.ratesalgo.app.gateway;

import io.ratesalgo.core.HotPath;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.CancelReason;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrderReplaceDecoder;
import io.ratesalgo.messages.sbe.OrderWithdrawEncoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteWithdrawEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongLongConsumer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * What one venue session believes is working at its venue, kept on the gateway thread from the
 * messages that pass through it: a quote is live from the Quote we sent until the venue reports it
 * done, expired, rejected or cancelled; an order from the NewOrder until an execution report puts
 * it in a final state. The event loop keeps its own view; this one exists so that the gateway can
 * withdraw everything by itself when the loop cannot (review findings L1 and L2).
 *
 * <p>{@link #cancelAll} sends one QuoteCancel per live quote and one OrderCancelRequest per live
 * order through the gateway. Nothing is removed when a cancel is sent, only when the venue
 * answers, so a cancel that got no answer is sent again by the next call.
 *
 * <p>Capacity is fixed. If more than {@code capacity} quotes or orders are live at once the set
 * stops tracking new ones and says so ({@link #overflowed}); the session then cannot confirm a
 * withdrawal by acknowledgement and must log out instead. Single-threaded; no allocation after
 * construction.
 */
@HotPath
final class LiveSet {

  /** Set on the ClOrdID of a cancel request, as the loop's own cancels do (AppIds.CANCEL_FLAG). */
  static final long CANCEL_FLAG = 1L << 61;

  private static final long NONE = Long.MIN_VALUE;
  private static final int CXL_REJ_REASON = 102;
  private static final int HEADER = MessageHeaderEncoder.ENCODED_LENGTH;

  private final int venueId;
  private final int capacity;
  private final Long2LongHashMap quoteRfq;
  private final Long2LongHashMap quoteInstrument;
  private final Long2LongHashMap rfqQuote;
  /** ClOrdID to {@code instrumentId << 1 | (sell ? 1 : 0)}. */
  private final Long2LongHashMap orders;
  private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final QuoteWithdrawEncoder quoteWithdraw = new QuoteWithdrawEncoder();
  private final OrderWithdrawEncoder orderWithdraw = new OrderWithdrawEncoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final OrderReplaceDecoder orderReplace = new OrderReplaceDecoder();
  private final LongLongConsumer cancelQuote = this::cancelQuote;
  private final LongLongConsumer cancelOrder = this::cancelOrder;
  private MessageSink sink;
  private long sentNanos;
  private long cancelsSent;
  private boolean overflowed;

  LiveSet(final int venueId, final int capacity) {
    this.venueId = venueId;
    this.capacity = capacity;
    this.quoteRfq = new Long2LongHashMap(capacity * 2, 0.6f, NONE);
    this.quoteInstrument = new Long2LongHashMap(capacity * 2, 0.6f, NONE);
    this.rfqQuote = new Long2LongHashMap(capacity * 2, 0.6f, NONE);
    this.orders = new Long2LongHashMap(capacity * 2, 0.6f, NONE);
  }

  /** A Quote, NewOrder or OrderReplace the gateway has just written to the session. */
  void onSent(final int templateId, final DirectBuffer buffer, final int offset, final int blockLength, final int version) {
    switch (templateId) {
      case QuoteDecoder.TEMPLATE_ID -> {
        quote.wrap(buffer, offset + HEADER, blockLength, version);
        final long rfqId = quote.rfqId();
        final long earlier = rfqQuote.get(rfqId);
        if (earlier != NONE) {
          removeQuote(earlier); // a requote replaces our earlier quote on the same RFQ
        }
        if (quoteRfq.size() >= capacity) {
          overflowed = true;
          return;
        }
        quoteRfq.put(quote.quoteId(), rfqId);
        quoteInstrument.put(quote.quoteId(), quote.instrumentId());
        rfqQuote.put(rfqId, quote.quoteId());
      }
      case NewOrderDecoder.TEMPLATE_ID -> {
        newOrder.wrap(buffer, offset + HEADER, blockLength, version);
        addOrder(newOrder.clOrdId(), newOrder.instrumentId(), newOrder.side());
      }
      case OrderReplaceDecoder.TEMPLATE_ID -> {
        orderReplace.wrap(buffer, offset + HEADER, blockLength, version);
        orders.remove(orderReplace.origClOrdId());
        addOrder(orderReplace.clOrdId(), orderReplace.instrumentId(), orderReplace.side());
      }
      default -> {
        // cancels and withdrawals change nothing here: the venue's answer does
      }
    }
  }

  private void addOrder(final long clOrdId, final int instrumentId, final Side side) {
    if (orders.size() >= capacity) {
      overflowed = true;
      return;
    }
    orders.put(clOrdId, ((long) instrumentId << 1) | (side == Side.Sell ? 1L : 0L));
  }

  /** An application message from the venue: drops whatever it reports as no longer working. */
  void onVenueMessage(final FixParser m) {
    switch (m.msgType()) {
      case MsgType.EXECUTION_REPORT -> {
        if (m.has(FixTags.CL_ORD_ID) && isFinal(m.getChar(FixTags.ORD_STATUS, (byte) '0'))) {
          orders.remove(m.getLong(FixTags.CL_ORD_ID, 0));
          // a cancel acknowledgement carries the cancel request's id and names the order in 41
          orders.remove(m.getLong(FixTags.ORIG_CL_ORD_ID, 0));
        }
      }
      case MsgType.QUOTE_STATUS_REPORT -> {
        if (m.getLong(FixTags.QUOTE_STATUS, 0) != 0) {
          removeQuote(m.getLong(FixTags.QUOTE_ID, 0)); // anything but "accepted" means not live
        }
      }
      case MsgType.QUOTE_RESPONSE -> {
        // the RFQ's outcome: our quote on it is finished whether or not the venue names it
        final long named = m.getLong(FixTags.QUOTE_ID, 0);
        removeQuote(named != 0 ? named : rfqQuote.get(m.getLong(FixTags.QUOTE_REQ_ID, 0)));
      }
      case MsgType.ORDER_CANCEL_REJECT -> {
        final long reason = m.getLong(CXL_REJ_REASON, -1);
        if (reason == 0 || reason == 1) {
          orders.remove(m.getLong(FixTags.ORIG_CL_ORD_ID, 0)); // too late to cancel, or unknown
        }
      }
      default -> {
        // nothing else says a quote or an order has ended
      }
    }
  }

  private static boolean isFinal(final byte ordStatus) {
    return ordStatus == '2' || ordStatus == '4' || ordStatus == '8' || ordStatus == 'C';
  }

  private void removeQuote(final long quoteId) {
    final long rfqId = quoteRfq.remove(quoteId);
    if (rfqId != NONE) {
      quoteInstrument.remove(quoteId);
      if (rfqQuote.get(rfqId) == quoteId) {
        rfqQuote.remove(rfqId);
      }
    }
  }

  /** The venue cancels everything of ours when the session drops. */
  void onDisconnect() {
    quoteRfq.clear();
    quoteInstrument.clear();
    rfqQuote.clear();
    orders.clear();
    overflowed = false;
  }

  /**
   * Offers a QuoteWithdraw for every live quote and an OrderWithdraw for every live order to
   * {@code gateway}, which writes them as QuoteCancel and OrderCancelRequest.
   *
   * @return the number offered
   */
  int cancelAll(final MessageSink gateway, final long nowEpochNanos) {
    final long before = cancelsSent;
    sink = gateway;
    sentNanos = nowEpochNanos;
    quoteRfq.forEachLong(cancelQuote);
    orders.forEachLong(cancelOrder);
    return (int) (cancelsSent - before);
  }

  private void cancelQuote(final long quoteId, final long rfqId) {
    quoteWithdraw
        .wrapAndApplyHeader(scratch, 0, header)
        .venueId(venueId)
        .rfqId(rfqId)
        .quoteId(quoteId)
        .instrumentId((int) quoteInstrument.get(quoteId))
        .reason(CancelReason.KillSwitch)
        .sentNanos(sentNanos);
    sink.offer(scratch, 0, HEADER + quoteWithdraw.encodedLength());
    cancelsSent++;
  }

  private void cancelOrder(final long clOrdId, final long packed) {
    orderWithdraw
        .wrapAndApplyHeader(scratch, 0, header)
        .venueId(venueId)
        .clOrdId(clOrdId | CANCEL_FLAG)
        .origClOrdId(clOrdId)
        .instrumentId((int) (packed >>> 1))
        .side((packed & 1L) == 0 ? Side.Buy : Side.Sell)
        .reason(CancelReason.KillSwitch)
        .sentNanos(sentNanos);
    sink.offer(scratch, 0, HEADER + orderWithdraw.encodedLength());
    cancelsSent++;
  }

  int liveQuotes() {
    return quoteRfq.size();
  }

  int liveOrders() {
    return orders.size();
  }

  /** True once more than the capacity was live at once: the set is no longer complete. */
  boolean overflowed() {
    return overflowed;
  }

  /** Cancels offered by {@link #cancelAll} since construction. */
  long cancelsSent() {
    return cancelsSent;
  }
}
