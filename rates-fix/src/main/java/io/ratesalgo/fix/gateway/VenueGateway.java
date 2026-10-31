package io.ratesalgo.fix.gateway;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionHandler;
import io.ratesalgo.fix.session.SessionState;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelDecoder;
import io.ratesalgo.messages.sbe.OrderReplaceDecoder;
import io.ratesalgo.messages.sbe.QuoteCancelDecoder;
import io.ratesalgo.messages.sbe.QuoteDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.TradeBustEncoder;
import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Translates between one venue's FIX 4.4 session and the SBE messages of {@code rates-messages}.
 *
 * <p>Inbound (FIX to SBE, delivered to the engine sink): QuoteRequest R to QuoteRequest;
 * QuoteResponse AJ and rejecting QuoteStatusReport AI to QuoteResponse; ExecutionReport 8 to
 * ExecutionReport or TradeBust after {@link OrderStateTracker}; MarketDataSnapshotFullRefresh W
 * and MarketDataIncrementalRefresh X to BookUpdate. Outbound (SBE offered by the engine to FIX):
 * Quote to S, QuoteCancel to Z, NewOrder to D, OrderCancel to F, OrderReplace to G.
 *
 * <p>Units (API_SPEC.md Section 10.3): FIX prices are decimal text converted exactly to int64
 * mantissas with exponent -9; FIX quantities are face or notional in currency units, converted
 * exactly to minor units (scale 2). Ids (QuoteReqID, QuoteID, ClOrdID, OrderID, ExecID) are
 * numeric text. No step uses floating point and nothing is allocated per message.
 */
public final class VenueGateway implements FixSessionHandler, MessageSink {

  private static final int PRICE_SCALE = 9;
  private static final int QTY_SCALE = 2;

  private final int venueId;
  private final InstrumentMap instruments;
  private final MessageSink engine;
  private final Clock clock;
  private final OrderStateTracker orders;
  private final GatewayStats stats = new GatewayStats();
  private final UnsafeBuffer sbe = new UnsafeBuffer(ByteBuffer.allocateDirect(512));
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final QuoteRequestEncoder quoteRequest = new QuoteRequestEncoder();
  private final QuoteResponseEncoder quoteResponse = new QuoteResponseEncoder();
  private final ExecutionReportEncoder executionReport = new ExecutionReportEncoder();
  private final TradeBustEncoder tradeBust = new TradeBustEncoder();
  private final BookUpdateEncoder bookUpdate = new BookUpdateEncoder();
  private final QuoteDecoder quote = new QuoteDecoder();
  private final QuoteCancelDecoder quoteCancel = new QuoteCancelDecoder();
  private final NewOrderDecoder newOrder = new NewOrderDecoder();
  private final OrderCancelDecoder orderCancel = new OrderCancelDecoder();
  private final OrderReplaceDecoder orderReplace = new OrderReplaceDecoder();
  private FixSession session;

  // market data entry being accumulated
  private byte mdEntryType;
  private long mdPrice;
  private long mdSize;
  private int mdLevel;
  private int mdOrders;
  private byte mdAction;
  private int mdInstrument;
  private long mdRptSeq;
  private boolean mdOpen;
  private int bidLevels;
  private int askLevels;

  /** Creates a gateway for one venue session. */
  public VenueGateway(
      final int venueId,
      final InstrumentMap instruments,
      final MessageSink engine,
      final Clock clock,
      final OrderStateTracker orders) {
    this.venueId = venueId;
    this.instruments = instruments;
    this.engine = engine;
    this.clock = clock;
    this.orders = orders;
  }

  /** Binds the session this gateway writes to. */
  public void bind(final FixSession fixSession) {
    this.session = fixSession;
  }

  /** Counters. */
  public GatewayStats stats() {
    return stats;
  }

  /** The order state kept from execution reports. */
  public OrderStateTracker orders() {
    return orders;
  }

  // ---------------------------------------------------------------- inbound FIX to SBE

  @Override
  public void onApplicationMessage(
      final FixSession fixSession, final FixParser message, final boolean possDup) {
    switch (message.msgType()) {
      case MsgType.QUOTE_REQUEST -> onQuoteRequest(message);
      case MsgType.QUOTE_RESPONSE -> onQuoteResponse(message);
      case MsgType.QUOTE_STATUS_REPORT -> onQuoteStatusReport(message);
      case MsgType.EXECUTION_REPORT -> onExecutionReport(message);
      case MsgType.MARKET_DATA_SNAPSHOT -> onMarketData(message, true);
      case MsgType.MARKET_DATA_INCREMENTAL -> onMarketData(message, false);
      case MsgType.ORDER_CANCEL_REJECT -> stats.cancelRejects++;
      case MsgType.BUSINESS_MESSAGE_REJECT -> stats.businessRejects++;
      case MsgType.MARKET_DATA_REQUEST_REJECT -> stats.marketDataRejects++;
      default -> stats.unsupportedInbound++;
    }
  }

  private int instrument(final FixParser p, final int from) {
    final int i = p.indexOf(FixTags.SECURITY_ID, from);
    if (i < 0) {
      return 0;
    }
    return instruments.idAt(p, i);
  }

  private static Side side(final byte fix) {
    return fix == '2' ? Side.Sell : Side.Buy;
  }

  private void deliver(final int length) {
    if (engine.offer(sbe, 0, length)) {
      stats.inboundTranslated++;
    } else {
      stats.sinkRejected++;
    }
  }

  private void onQuoteRequest(final FixParser p) {
    final int instrumentId = instrument(p, 0);
    if (instrumentId == 0) {
      stats.unknownInstrument++;
      return;
    }
    final int ccy = p.indexOf(FixTags.CURRENCY);
    final DirectBuffer b = p.buffer();
    quoteRequest
        .wrapAndApplyHeader(sbe, 0, headerEncoder)
        .venueId(venueId)
        .rfqId(p.getLong(FixTags.QUOTE_REQ_ID))
        .clientId((int) p.getLong(FixTags.PARTY_ID, 0))
        .instrumentId(instrumentId)
        .side(side(p.getChar(FixTags.SIDE)))
        .quantity(p.getDecimal(FixTags.ORDER_QTY, QTY_SCALE))
        .dealerCount((short) p.getLong(FixTags.DEALER_COUNT, 0))
        .receivedNanos(clock.epochNanos())
        .expiryNanos(p.getTimestamp(FixTags.EXPIRE_TIME, 0));
    if (ccy >= 0 && p.valueLength(ccy) == 3) {
      final int o = p.valueOffset(ccy);
      quoteRequest.putCurrency(b.getByte(o), b.getByte(o + 1), b.getByte(o + 2));
    } else {
      quoteRequest.putCurrency((byte) ' ', (byte) ' ', (byte) ' ');
    }
    deliver(MessageHeaderEncoder.ENCODED_LENGTH + quoteRequest.encodedLength());
  }

  private void encodeQuoteResponse(
      final FixParser p, final QuoteStatus status, final long price, final long qty, final long cover) {
    quoteResponse
        .wrapAndApplyHeader(sbe, 0, headerEncoder)
        .venueId(venueId)
        .rfqId(p.getLong(FixTags.QUOTE_REQ_ID, 0))
        .quoteId(p.getLong(FixTags.QUOTE_ID, 0))
        .status(status)
        .tradedQuantity(qty)
        .timestampNanos(p.getTimestamp(FixTags.TRANSACT_TIME, clock.epochNanos()));
    quoteResponse.tradedPrice().mantissa(price);
    quoteResponse.coverPrice().mantissa(cover);
    deliver(MessageHeaderEncoder.ENCODED_LENGTH + quoteResponse.encodedLength());
  }

  private void onQuoteResponse(final FixParser p) {
    final long type = p.getLong(FixTags.QUOTE_RESP_TYPE);
    if (type == 1) {
      encodeQuoteResponse(
          p,
          QuoteStatus.Done,
          p.getDecimal(FixTags.PRICE, PRICE_SCALE),
          p.getDecimal(FixTags.ORDER_QTY, QTY_SCALE),
          p.getDecimal(FixTags.COVER_PRICE, PRICE_SCALE, 0));
    } else if (type == 3) {
      encodeQuoteResponse(p, QuoteStatus.Expired, 0, 0, 0);
    } else if (type == 5) {
      final byte outcome = p.getChar(FixTags.RFQ_OUTCOME, (byte) 'T');
      encodeQuoteResponse(p, outcome == 'D' ? QuoteStatus.DoneAway : QuoteStatus.TradedAway, 0, 0, 0);
    } else if (type == 6) {
      encodeQuoteResponse(p, QuoteStatus.Passed, 0, 0, 0);
    } else {
      stats.unsupportedInbound++;
    }
  }

  private void onQuoteStatusReport(final FixParser p) {
    final long status = p.getLong(FixTags.QUOTE_STATUS);
    stats.lastQuoteStatus = status;
    if (status == 5) {
      encodeQuoteResponse(p, QuoteStatus.Rejected, 0, 0, 0);
    } else {
      stats.quoteAcks++;
    }
  }

  private static OrdStatus ordStatus(final byte fix) {
    return switch (fix) {
      case '1' -> OrdStatus.PartiallyFilled;
      case '2' -> OrdStatus.Filled;
      case '4' -> OrdStatus.Cancelled;
      case '8' -> OrdStatus.Rejected;
      default -> OrdStatus.New;
    };
  }

  private void onExecutionReport(final FixParser p) {
    final int instrumentId = instrument(p, 0);
    if (instrumentId == 0) {
      stats.unknownInstrument++;
      return;
    }
    final byte execType = p.getChar(FixTags.EXEC_TYPE);
    final boolean rfqTrade = !p.has(FixTags.CL_ORD_ID);
    final long clOrdId = rfqTrade ? p.getLong(FixTags.QUOTE_ID) : p.getLong(FixTags.CL_ORD_ID);
    final long execId =
        execType == 'H' ? p.getLong(FixTags.EXEC_REF_ID) : p.getLong(FixTags.EXEC_ID, 0);
    final long lastQty = p.getDecimal(FixTags.LAST_QTY, QTY_SCALE, 0);
    final long lastPx = p.getDecimal(FixTags.LAST_PX, PRICE_SCALE, 0);
    final int result =
        orders.apply(
            execType,
            clOrdId,
            p.getLong(FixTags.ORIG_CL_ORD_ID, 0),
            execId,
            p.getChar(FixTags.ORD_STATUS),
            execType == 'F' ? lastQty : 0,
            lastPx,
            p.getDecimal(FixTags.ORDER_QTY, QTY_SCALE, 0));
    final Side side = side(p.getChar(FixTags.SIDE));
    final long transact = p.getTimestamp(FixTags.TRANSACT_TIME, clock.epochNanos());
    if (result == OrderStateTracker.BUST) {
      tradeBust
          .wrapAndApplyHeader(sbe, 0, headerEncoder)
          .venueId(venueId)
          .clOrdId(clOrdId)
          .execId(execId)
          .instrumentId(instrumentId)
          .side(side)
          .quantity(orders.lastBustQty())
          .timestampNanos(transact);
      tradeBust.price().mantissa(orders.lastBustPrice());
      deliver(MessageHeaderEncoder.ENCODED_LENGTH + tradeBust.encodedLength());
      return;
    }
    if (result != OrderStateTracker.EMIT && result != OrderStateTracker.FULL) {
      return;
    }
    final boolean full = result == OrderStateTracker.FULL;
    executionReport
        .wrapAndApplyHeader(sbe, 0, headerEncoder)
        .venueId(venueId)
        .clOrdId(clOrdId)
        .venueOrderId(p.getLong(FixTags.ORDER_ID, 0))
        .execId(execId)
        .instrumentId(instrumentId)
        .side(side)
        .ordStatus(ordStatus(full ? p.getChar(FixTags.ORD_STATUS) : orders.lastStatus()))
        .lastQuantity(execType == 'F' ? lastQty : 0)
        .leavesQuantity(full ? p.getDecimal(FixTags.LEAVES_QTY, QTY_SCALE, 0) : orders.lastLeaves())
        .cumQuantity(full ? p.getDecimal(FixTags.CUM_QTY, QTY_SCALE, 0) : orders.lastCum())
        .transactNanos(transact);
    executionReport.lastPrice().mantissa(execType == 'F' ? lastPx : 0);
    deliver(MessageHeaderEncoder.ENCODED_LENGTH + executionReport.encodedLength());
  }

  private void onMarketData(final FixParser p, final boolean snapshot) {
    final int groupStart = p.indexOf(FixTags.NO_MD_ENTRIES);
    if (groupStart < 0) {
      stats.unsupportedInbound++;
      return;
    }
    final long seq = p.getLong(FixTags.MSG_SEQ_NUM);
    final long now = clock.epochNanos();
    mdOpen = false;
    bidLevels = 0;
    askLevels = 0;
    mdInstrument = snapshot ? instrument(p, 0) : 0;
    if (snapshot) {
      if (mdInstrument == 0) {
        stats.unknownInstrument++;
        return;
      }
      encodeBook(mdInstrument, seq, Side.Buy, BookAction.Clear, 0, 0, 0, 0, now);
    }
    final int delimiter = snapshot ? FixTags.MD_ENTRY_TYPE : FixTags.MD_UPDATE_ACTION;
    for (int f = groupStart + 1; f < p.fieldCount(); f++) {
      final int tag = p.tagAt(f);
      if (tag == delimiter) {
        flushEntry(seq, now, snapshot);
        mdOpen = true;
        mdEntryType = '0';
        mdPrice = 0;
        mdSize = 0;
        mdLevel = 0;
        mdOrders = 0;
        mdAction = '0';
        mdRptSeq = seq;
        if (!snapshot) {
          mdInstrument = 0;
        }
      }
      switch (tag) {
        case FixTags.MD_UPDATE_ACTION -> mdAction = p.getCharAt(f);
        case FixTags.MD_ENTRY_TYPE -> mdEntryType = p.getCharAt(f);
        case FixTags.MD_ENTRY_PX -> mdPrice = p.getDecimalAt(f, PRICE_SCALE);
        case FixTags.MD_ENTRY_SIZE -> mdSize = p.getDecimalAt(f, QTY_SCALE);
        case FixTags.MD_PRICE_LEVEL -> mdLevel = (int) p.getLongAt(f);
        case FixTags.NUMBER_OF_ORDERS -> mdOrders = (int) p.getLongAt(f);
        case FixTags.RPT_SEQ -> mdRptSeq = p.getLongAt(f);
        case FixTags.SECURITY_ID -> {
          if (!snapshot) {
            mdInstrument = instruments.idAt(p, f);
          }
        }
        default -> {
          // other entry fields are not carried on BookUpdate
        }
      }
    }
    flushEntry(seq, now, snapshot);
  }

  private void flushEntry(final long seq, final long now, final boolean snapshot) {
    if (!mdOpen) {
      return;
    }
    mdOpen = false;
    if (mdEntryType != '0' && mdEntryType != '1') {
      return; // trades and other entry types are not book levels
    }
    if (mdInstrument == 0) {
      stats.unknownInstrument++;
      return;
    }
    final Side side = mdEntryType == '0' ? Side.Buy : Side.Sell;
    int level = mdLevel;
    if (level == 0) {
      level = side == Side.Buy ? ++bidLevels : ++askLevels;
    }
    final BookAction action =
        snapshot
            ? BookAction.Add
            : switch (mdAction) {
              case '1' -> BookAction.Change;
              case '2' -> BookAction.Delete;
              default -> BookAction.Add;
            };
    encodeBook(mdInstrument, mdRptSeq == 0 ? seq : mdRptSeq, side, action, level, mdPrice, mdSize, mdOrders, now);
  }

  private void encodeBook(
      final int instrumentId,
      final long seq,
      final Side side,
      final BookAction action,
      final int level,
      final long price,
      final long quantity,
      final int orderCount,
      final long now) {
    bookUpdate
        .wrapAndApplyHeader(sbe, 0, headerEncoder)
        .venueId(venueId)
        .instrumentId(instrumentId)
        .venueSeqNo(seq)
        .side(side)
        .action(action)
        .level((short) level)
        .quantity(quantity)
        .orderCount(orderCount)
        .timestampNanos(now);
    bookUpdate.price().mantissa(price);
    deliver(MessageHeaderEncoder.ENCODED_LENGTH + bookUpdate.encodedLength());
  }

  // ---------------------------------------------------------------- outbound SBE to FIX

  private boolean active() {
    if (session == null || session.state() != SessionState.ACTIVE) {
      stats.droppedNotActive++;
      return false;
    }
    return true;
  }

  private boolean putInstrument(final FixWriter w, final int instrumentId) {
    final byte[] id = instruments.securityId(instrumentId);
    if (id == null) {
      return false;
    }
    w.putAscii(FixTags.SYMBOL, id).putAscii(FixTags.SECURITY_ID, id).putChar(FixTags.SECURITY_ID_SOURCE, (byte) '4');
    return true;
  }

  private static byte fixSide(final Side side) {
    return side == Side.Sell ? (byte) '2' : (byte) '1';
  }

  @Override
  public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
    headerDecoder.wrap(buffer, offset);
    final int templateId = headerDecoder.templateId();
    final int bodyOffset = offset + MessageHeaderDecoder.ENCODED_LENGTH;
    final int blockLength = headerDecoder.blockLength();
    final int version = headerDecoder.version();
    if (templateId != QuoteDecoder.TEMPLATE_ID
        && templateId != QuoteCancelDecoder.TEMPLATE_ID
        && templateId != NewOrderDecoder.TEMPLATE_ID
        && templateId != OrderCancelDecoder.TEMPLATE_ID
        && templateId != OrderReplaceDecoder.TEMPLATE_ID) {
      stats.unsupportedOutbound++;
      return false;
    }
    if (!active()) {
      return false;
    }
    final boolean sent =
        switch (templateId) {
          case QuoteDecoder.TEMPLATE_ID -> sendQuote(buffer, bodyOffset, blockLength, version);
          case QuoteCancelDecoder.TEMPLATE_ID -> sendQuoteCancel(buffer, bodyOffset, blockLength, version);
          case NewOrderDecoder.TEMPLATE_ID -> sendNewOrder(buffer, bodyOffset, blockLength, version);
          case OrderCancelDecoder.TEMPLATE_ID -> sendCancel(buffer, bodyOffset, blockLength, version);
          default -> sendReplace(buffer, bodyOffset, blockLength, version);
        };
    if (sent) {
      stats.outboundTranslated++;
    } else {
      stats.unknownInstrument++;
    }
    return sent;
  }

  private boolean sendQuote(final DirectBuffer b, final int o, final int bl, final int v) {
    quote.wrap(b, o, bl, v);
    if (instruments.securityId(quote.instrumentId()) == null) {
      return false;
    }
    final FixWriter w = session.begin(MsgType.QUOTE);
    w.put(FixTags.QUOTE_REQ_ID, quote.rfqId()).put(FixTags.QUOTE_ID, quote.quoteId());
    putInstrument(w, quote.instrumentId());
    if (quote.bidQuantity() > 0) {
      w.putDecimal(FixTags.BID_PX, quote.bidPrice().mantissa(), PRICE_SCALE);
    }
    if (quote.askQuantity() > 0) {
      w.putDecimal(FixTags.OFFER_PX, quote.askPrice().mantissa(), PRICE_SCALE);
    }
    if (quote.bidQuantity() > 0) {
      w.putDecimal(FixTags.BID_SIZE, quote.bidQuantity(), QTY_SCALE);
    }
    if (quote.askQuantity() > 0) {
      w.putDecimal(FixTags.OFFER_SIZE, quote.askQuantity(), QTY_SCALE);
    }
    w.putTimestamp(FixTags.VALID_UNTIL_TIME, quote.validUntilNanos())
        .putTimestamp(FixTags.TRANSACT_TIME, quote.sentNanos());
    session.send();
    return true;
  }

  private boolean sendQuoteCancel(final DirectBuffer b, final int o, final int bl, final int v) {
    quoteCancel.wrap(b, o, bl, v);
    final FixWriter w = session.begin(MsgType.QUOTE_CANCEL);
    w.put(FixTags.QUOTE_REQ_ID, quoteCancel.rfqId())
        .put(FixTags.QUOTE_ID, quoteCancel.quoteId())
        .put(FixTags.QUOTE_CANCEL_TYPE, 5);
    session.send();
    return true;
  }

  private boolean sendNewOrder(final DirectBuffer b, final int o, final int bl, final int v) {
    newOrder.wrap(b, o, bl, v);
    if (instruments.securityId(newOrder.instrumentId()) == null) {
      return false;
    }
    orders.onNewOrder(newOrder.clOrdId(), newOrder.quantity());
    final FixWriter w = session.begin(MsgType.NEW_ORDER_SINGLE);
    w.put(FixTags.CL_ORD_ID, newOrder.clOrdId());
    putInstrument(w, newOrder.instrumentId());
    w.putChar(FixTags.SIDE, fixSide(newOrder.side()))
        .putTimestamp(FixTags.TRANSACT_TIME, newOrder.sentNanos())
        .putDecimal(FixTags.ORDER_QTY, newOrder.quantity(), QTY_SCALE)
        .putChar(FixTags.ORD_TYPE, newOrder.ordType() == OrdType.Market ? (byte) '1' : (byte) '2');
    if (newOrder.ordType() != OrdType.Market) {
      w.putDecimal(FixTags.PRICE, newOrder.price().mantissa(), PRICE_SCALE);
    }
    final TimeInForce tif = newOrder.timeInForce();
    w.putChar(FixTags.TIME_IN_FORCE, (byte) ('0' + tif.value()));
    if (tif == TimeInForce.GoodTillDate) {
      w.putTimestamp(FixTags.EXPIRE_TIME, newOrder.expireNanos());
    }
    session.send();
    return true;
  }

  private boolean sendCancel(final DirectBuffer b, final int o, final int bl, final int v) {
    orderCancel.wrap(b, o, bl, v);
    if (instruments.securityId(orderCancel.instrumentId()) == null) {
      return false;
    }
    final FixWriter w = session.begin(MsgType.ORDER_CANCEL_REQUEST);
    w.put(FixTags.ORIG_CL_ORD_ID, orderCancel.origClOrdId()).put(FixTags.CL_ORD_ID, orderCancel.clOrdId());
    putInstrument(w, orderCancel.instrumentId());
    w.putChar(FixTags.SIDE, fixSide(orderCancel.side()))
        .putTimestamp(FixTags.TRANSACT_TIME, orderCancel.sentNanos())
        .putDecimal(FixTags.ORDER_QTY, orderCancel.quantity(), QTY_SCALE);
    session.send();
    return true;
  }

  private boolean sendReplace(final DirectBuffer b, final int o, final int bl, final int v) {
    orderReplace.wrap(b, o, bl, v);
    if (instruments.securityId(orderReplace.instrumentId()) == null) {
      return false;
    }
    final FixWriter w = session.begin(MsgType.ORDER_CANCEL_REPLACE_REQUEST);
    w.put(FixTags.ORIG_CL_ORD_ID, orderReplace.origClOrdId()).put(FixTags.CL_ORD_ID, orderReplace.clOrdId());
    putInstrument(w, orderReplace.instrumentId());
    w.putChar(FixTags.SIDE, fixSide(orderReplace.side()))
        .putTimestamp(FixTags.TRANSACT_TIME, orderReplace.sentNanos())
        .putDecimal(FixTags.ORDER_QTY, orderReplace.quantity(), QTY_SCALE)
        .putChar(FixTags.ORD_TYPE, (byte) '2')
        .putDecimal(FixTags.PRICE, orderReplace.price().mantissa(), PRICE_SCALE);
    session.send();
    return true;
  }

  /** Sends QuoteStatusRequest (35=a) for one quote. */
  public boolean requestQuoteStatus(final long requestId, final long quoteId, final int instrumentId) {
    if (!active() || instruments.securityId(instrumentId) == null) {
      return false;
    }
    final FixWriter w = session.begin(MsgType.QUOTE_STATUS_REQUEST);
    w.put(FixTags.QUOTE_STATUS_REQ_ID, requestId).put(FixTags.QUOTE_ID, quoteId);
    putInstrument(w, instrumentId);
    session.send();
    return true;
  }

  /** Sends OrderMassStatusRequest (35=AF) for all our orders. */
  public boolean requestOrderMassStatus(final long requestId) {
    if (!active()) {
      return false;
    }
    session.begin(MsgType.ORDER_MASS_STATUS_REQUEST).put(FixTags.MASS_STATUS_REQ_ID, requestId).put(FixTags.MASS_STATUS_REQ_TYPE, 7);
    session.send();
    return true;
  }

  /** Sends MarketDataRequest (35=V): snapshot plus incremental updates to {@code depth} levels. */
  public boolean subscribeMarketData(final long requestId, final int instrumentId, final int depth) {
    if (!active() || instruments.securityId(instrumentId) == null) {
      return false;
    }
    final FixWriter w = session.begin(MsgType.MARKET_DATA_REQUEST);
    w.put(FixTags.MD_REQ_ID, requestId)
        .putChar(FixTags.SUBSCRIPTION_REQUEST_TYPE, (byte) '1')
        .put(FixTags.MARKET_DEPTH, depth)
        .put(FixTags.MD_UPDATE_TYPE, 1)
        .put(FixTags.NO_MD_ENTRY_TYPES, 2)
        .putChar(FixTags.MD_ENTRY_TYPE, (byte) '0')
        .putChar(FixTags.MD_ENTRY_TYPE, (byte) '1')
        .put(FixTags.NO_RELATED_SYM, 1);
    putInstrument(w, instrumentId);
    session.send();
    return true;
  }
}
