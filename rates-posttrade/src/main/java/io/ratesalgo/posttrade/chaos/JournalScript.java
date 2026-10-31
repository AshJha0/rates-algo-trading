package io.ratesalgo.posttrade.chaos;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalWriter;
import io.ratesalgo.messages.sbe.AmendReason;
import io.ratesalgo.messages.sbe.BustReason;
import io.ratesalgo.messages.sbe.ExecutionReportEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.PostTradeBustEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.QuoteResponseEncoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.TradeAmendEncoder;
import io.ratesalgo.messages.sbe.TradeBustEncoder;
import io.ratesalgo.messages.sbe.TradeSource;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Writes SBE messages to a journal at chosen arrival times. Used by the synthetic day, the
 * fixtures and the cookbook; production frames are written by the event loop (P2).
 */
public final class JournalScript implements AutoCloseable {

  private final ManualClock clock;
  private final JournalWriter writer;
  private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final NewOrderEncoder newOrder = new NewOrderEncoder();
  private final ExecutionReportEncoder er = new ExecutionReportEncoder();
  private final QuoteRequestEncoder rfq = new QuoteRequestEncoder();
  private final QuoteEncoder quote = new QuoteEncoder();
  private final QuoteResponseEncoder response = new QuoteResponseEncoder();
  private final PostTradeBustEncoder bust = new PostTradeBustEncoder();
  private final TradeBustEncoder venueBust = new TradeBustEncoder();
  private final TradeAmendEncoder amend = new TradeAmendEncoder();

  /** Creates a journal of {@code capacity} bytes whose first frame is no earlier than {@code startNanos}. */
  public JournalScript(final Path path, final int capacity, final long startNanos) {
    this.clock = new ManualClock(startNanos);
    this.writer = JournalWriter.create(path, capacity, clock);
  }

  private long append(final long arrivalNanos, final int templateId, final int encodedLength) {
    if (arrivalNanos > clock.epochNanos()) {
      clock.setEpochNanos(arrivalNanos);
    }
    return writer.append(templateId, MessageHeaderEncoder.SCHEMA_VERSION, buffer, 0, encodedLength);
  }

  /** Our order to a venue. */
  public long newOrder(final long at, final int venueId, final long clOrdId, final int instrumentId,
      final Side side, final long price, final long quantity) {
    newOrder.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).clOrdId(clOrdId).parentId(0)
        .instrumentId(instrumentId).side(side).ordType(OrdType.Limit).timeInForce(TimeInForce.Day)
        .quantity(quantity).expireNanos(0).sentNanos(at);
    newOrder.price().mantissa(price);
    return append(at, NewOrderEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + newOrder.encodedLength());
  }

  /** A venue execution report; a fill when {@code lastQuantity > 0}. */
  public long executionReport(final long at, final int venueId, final long clOrdId, final long venueOrderId,
      final long execId, final int instrumentId, final Side side, final OrdStatus status,
      final long lastPrice, final long lastQuantity, final long leaves, final long cum, final long transactNanos) {
    er.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).clOrdId(clOrdId).venueOrderId(venueOrderId)
        .execId(execId).instrumentId(instrumentId).side(side).ordStatus(status)
        .lastQuantity(lastQuantity).leavesQuantity(leaves).cumQuantity(cum).transactNanos(transactNanos);
    er.lastPrice().mantissa(lastPrice);
    return append(at, ExecutionReportEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + er.encodedLength());
  }

  /** An inbound RFQ. */
  public long quoteRequest(final long at, final int venueId, final long rfqId, final int clientId,
      final int instrumentId, final Side clientSide, final long quantity, final String currency) {
    rfq.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).rfqId(rfqId).clientId(clientId)
        .instrumentId(instrumentId).side(clientSide).quantity(quantity).currency(currency)
        .dealerCount((short) 4).receivedNanos(at).expiryNanos(at + 30_000_000_000L);
    return append(at, QuoteRequestEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + rfq.encodedLength());
  }

  /** Our quote in reply to an RFQ. */
  public long quote(final long at, final int venueId, final long rfqId, final long quoteId,
      final int instrumentId, final long bid, final long ask, final long quantity, final long validUntil) {
    quote.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).rfqId(rfqId).quoteId(quoteId)
        .instrumentId(instrumentId).bidQuantity(quantity).askQuantity(quantity).referenceNanos(at)
        .validUntilNanos(validUntil).sentNanos(at);
    quote.bidPrice().mantissa(bid);
    quote.askPrice().mantissa(ask);
    return append(at, QuoteEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + quote.encodedLength());
  }

  /** The venue's outcome for our quote. */
  public long quoteResponse(final long at, final int venueId, final long rfqId, final long quoteId,
      final QuoteStatus status, final long tradedPrice, final long tradedQuantity, final long when) {
    response.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).rfqId(rfqId).quoteId(quoteId)
        .status(status).tradedQuantity(tradedQuantity).timestampNanos(when);
    response.tradedPrice().mantissa(tradedPrice);
    response.coverPrice().mantissa(0);
    return append(at, QuoteResponseEncoder.TEMPLATE_ID,
        MessageHeaderEncoder.ENCODED_LENGTH + response.encodedLength());
  }

  /** A post-trade bust. */
  public long bust(final long at, final int venueId, final TradeSource source, final long tradeId,
      final long venueOrderId, final int instrumentId, final BustReason reason) {
    bust.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).tradeSource(source).tradeId(tradeId)
        .venueOrderId(venueOrderId).instrumentId(instrumentId).reason(reason).timestampNanos(at);
    return append(at, PostTradeBustEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + bust.encodedLength());
  }

  /** A venue's TradeBust (template 44): the gateway's form of FIX ExecType H. */
  public long venueBust(final long at, final int venueId, final long clOrdId, final long execId,
      final int instrumentId, final Side side, final long price, final long quantity) {
    venueBust.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).clOrdId(clOrdId).execId(execId)
        .instrumentId(instrumentId).side(side).quantity(quantity).timestampNanos(at);
    venueBust.price().mantissa(price);
    return append(at, TradeBustEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + venueBust.encodedLength());
  }

  /** A trade amendment by a named person. */
  public long amend(final long at, final int venueId, final TradeSource source, final long tradeId,
      final long newPrice, final long newQuantity, final int amendedBy, final AmendReason reason) {
    amend.wrapAndApplyHeader(buffer, 0, header).venueId(venueId).tradeSource(source).tradeId(tradeId)
        .newQuantity(newQuantity).amendedBy(amendedBy).reason(reason).timestampNanos(at);
    amend.newPrice().mantissa(newPrice);
    return append(at, TradeAmendEncoder.TEMPLATE_ID, MessageHeaderEncoder.ENCODED_LENGTH + amend.encodedLength());
  }

  /** Appends raw bytes as a frame of an arbitrary template (for unknown-template tests). */
  public long raw(final long at, final int templateId, final byte[] payload) {
    buffer.putBytes(0, payload);
    return append(at, templateId, payload.length);
  }

  /** Moves the journal clock forward to {@code arrivalNanos}, the time the next frame arrives. */
  public void arriveAt(final long arrivalNanos) {
    if (arrivalNanos > clock.epochNanos()) {
      clock.setEpochNanos(arrivalNanos);
    }
  }

  /** The sequence the next frame will get. */
  public long nextSequence() {
    return writer.nextSequence();
  }

  @Override
  public void close() {
    writer.close();
  }
}
