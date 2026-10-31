package io.ratesalgo.fix.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.ScriptedVenue;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.fix.session.FixSessionConfig;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.OrderReplaceEncoder;
import io.ratesalgo.messages.sbe.QuoteCancelEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteResponseDecoder;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VenueGatewayTest {

  static final String ISIN = "US91282CZH77";
  private ManualClock clock;
  private ScriptedVenue venue;
  private FixSession session;
  private VenueGateway gateway;
  private final List<UnsafeBuffer> received = new ArrayList<>();
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);

  @BeforeEach
  void setUp() {
    clock = new ManualClock(1_793_629_800_000_000_000L);
    venue = new ScriptedVenue(clock);
    final InstrumentMap map = new InstrumentMap().register(1008, ISIN);
    gateway =
        new VenueGateway(
            101,
            map,
            (b, o, l) -> {
              final byte[] copy = new byte[l];
              b.getBytes(o, copy);
              received.add(new UnsafeBuffer(copy));
              return true;
            },
            clock,
            new OrderStateTracker(64, 256));
    session = new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), clock, venue, gateway);
    gateway.bind(session);
    session.onConnected();
    venue.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    venue.deliver(session);
  }

  private int templateOf(final UnsafeBuffer b) {
    header.wrap(b, 0);
    return header.templateId();
  }

  private FixWriter instrument(final FixWriter w) {
    return w.putText(55, ISIN).putText(48, ISIN).putChar(22, (byte) '4');
  }

  @Test
  void quoteRequestBecomesSbeQuoteRequest() {
    final FixWriter w = venue.begin(MsgType.QUOTE_REQUEST);
    w.put(FixTags.QUOTE_REQ_ID, 42).put(FixTags.NO_RELATED_SYM, 1);
    instrument(w)
        .putChar(FixTags.SIDE, (byte) '2')
        .putDecimal(FixTags.ORDER_QTY, 2_500_000_000L, 2)
        .putText(FixTags.CURRENCY, "USD")
        .putTimestamp(FixTags.EXPIRE_TIME, clock.epochNanos() + 15_000_000_000L)
        .put(FixTags.NO_PARTY_IDS, 1)
        .put(FixTags.PARTY_ID, 2003)
        .put(FixTags.DEALER_COUNT, 4);
    venue.deliver(session);
    assertEquals(1, received.size());
    final QuoteRequestDecoder d = new QuoteRequestDecoder();
    d.wrapAndApplyHeader(received.get(0), 0, header);
    assertEquals(42, d.rfqId());
    assertEquals(1008, d.instrumentId());
    assertEquals(Side.Sell, d.side());
    assertEquals(2_500_000_000L, d.quantity());
    assertEquals("USD", d.currency());
    assertEquals(2003, d.clientId());
    assertEquals(4, d.dealerCount());
    assertEquals(clock.epochNanos() + 15_000_000_000L, d.expiryNanos());
    assertEquals(101, d.venueId());

    final FixWriter unknown = venue.begin(MsgType.QUOTE_REQUEST);
    unknown.put(FixTags.QUOTE_REQ_ID, 43).putText(48, "XS0000000000");
    venue.deliver(session);
    final FixWriter noCcy = venue.begin(MsgType.QUOTE_REQUEST);
    instrument(noCcy.put(FixTags.QUOTE_REQ_ID, 44)).putChar(54, (byte) '1').put(38, 1);
    venue.deliver(session);
    assertEquals(1, gateway.stats().unknownInstrument());
    assertEquals(2, gateway.stats().inboundTranslated());
  }

  private QuoteResponseDecoder lastResponse() {
    final QuoteResponseDecoder d = new QuoteResponseDecoder();
    d.wrapAndApplyHeader(received.get(received.size() - 1), 0, header);
    return d;
  }

  @Test
  void quoteResponsesMapToStatuses() {
    final FixWriter hit = venue.begin(MsgType.QUOTE_RESPONSE);
    hit.put(693, 1).put(131, 42).put(117, 7).put(694, 1).putDecimal(44, 99_500_000_000L, 9).put(38, 10_000_000)
        .putDecimal(9502, 99_515_625_000L, 9);
    venue.deliver(session);
    QuoteResponseDecoder d = lastResponse();
    assertEquals(QuoteStatus.Done, d.status());
    assertEquals(99_500_000_000L, d.tradedPrice().mantissa());
    assertEquals(1_000_000_000L, d.tradedQuantity());
    assertEquals(99_515_625_000L, d.coverPrice().mantissa());
    assertEquals(42, d.rfqId());
    assertEquals(7, d.quoteId());
    final int[] types = {3, 5, 5, 6};
    final QuoteStatus[] expected = {QuoteStatus.Expired, QuoteStatus.TradedAway, QuoteStatus.DoneAway, QuoteStatus.Passed};
    for (int i = 0; i < types.length; i++) {
      final FixWriter w = venue.begin(MsgType.QUOTE_RESPONSE).put(693, 2 + i).put(131, 42).put(117, 7).put(694, types[i]);
      if (i == 2) {
        w.putChars(9503, (byte) 'D', (byte) 'A');
      }
      venue.deliver(session);
      assertEquals(expected[i], lastResponse().status());
    }
    venue.begin(MsgType.QUOTE_RESPONSE).put(693, 9).put(131, 42).put(117, 7).put(694, 2);
    venue.deliver(session);
    assertEquals(1, gateway.stats().unsupportedInbound());
    venue.begin(MsgType.QUOTE_STATUS_REPORT).put(131, 42).put(117, 7).put(297, 0);
    venue.deliver(session);
    assertEquals(1, gateway.stats().quoteAcks());
    assertEquals(0, gateway.stats().lastQuoteStatus());
    venue.begin(MsgType.QUOTE_STATUS_REPORT).put(131, 42).put(117, 7).put(297, 5);
    venue.deliver(session);
    assertEquals(QuoteStatus.Rejected, lastResponse().status());
    for (final int t : new int[] {MsgType.ORDER_CANCEL_REJECT, MsgType.BUSINESS_MESSAGE_REJECT, MsgType.MARKET_DATA_REQUEST_REJECT, MsgType.NEW_ORDER_SINGLE}) {
      venue.begin(t).put(11, 1);
      venue.deliver(session);
    }
    assertEquals(1, gateway.stats().cancelRejects());
    assertEquals(1, gateway.stats().businessRejects());
    assertEquals(1, gateway.stats().marketDataRejects());
    assertEquals(2, gateway.stats().unsupportedInbound());
  }

  private void er(
      final char execType, final long clOrdId, final long execId, final char ordStatus, final long lastQty, final long orderQty) {
    final FixWriter w = venue.begin(MsgType.EXECUTION_REPORT);
    w.put(37, 900 + clOrdId).put(11, clOrdId);
    if (execType == 'H') {
      w.put(19, execId).put(17, execId + 10_000);
    } else {
      w.put(17, execId);
    }
    w.putChar(150, (byte) execType).putChar(39, (byte) ordStatus);
    instrument(w).putChar(54, (byte) '1').put(38, orderQty).put(32, lastQty).putDecimal(31, 99_500_000_000L, 9)
        .put(151, 0).put(14, 0).put(6, 0).putTimestamp(60, clock.epochNanos());
    venue.deliver(session);
  }

  private ExecutionReportDecoder lastEr() {
    final ExecutionReportDecoder d = new ExecutionReportDecoder();
    d.wrapAndApplyHeader(received.get(received.size() - 1), 0, header);
    return d;
  }

  @Test
  void executionReportsWithDuplicatesStaleLateFillsAndBusts() {
    er('0', 1, 1, '0', 0, 10);
    assertEquals(OrdStatus.New, lastEr().ordStatus());
    assertEquals(1_000L, lastEr().leavesQuantity());
    er('F', 1, 2, '1', 4, 10);
    assertEquals(OrdStatus.PartiallyFilled, lastEr().ordStatus());
    assertEquals(400, lastEr().cumQuantity());
    assertEquals(99_500_000_000L, lastEr().lastPrice().mantissa());
    er('F', 1, 2, '1', 4, 10); // duplicate ExecID
    assertEquals(1, gateway.orders().duplicates());
    final int before = received.size();
    er('0', 1, 3, '0', 0, 10); // stale? no: order still live, emits
    assertEquals(before + 1, received.size());
    er('4', 1, 4, '4', 0, 10); // cancelled
    assertEquals(OrdStatus.Cancelled, lastEr().ordStatus());
    assertEquals(0, lastEr().leavesQuantity());
    er('0', 1, 5, '0', 0, 10); // stale New after Cancelled
    assertEquals(1, gateway.orders().stale());
    er('F', 1, 6, '4', 2, 10); // late fill after cancel
    assertEquals(1, gateway.orders().lateFills());
    assertEquals(600, lastEr().cumQuantity());
    er('H', 1, 2, '4', 0, 10); // bust the first fill
    final TradeBustDecoder bust = new TradeBustDecoder();
    bust.wrapAndApplyHeader(received.get(received.size() - 1), 0, header);
    assertEquals(2, bust.execId());
    assertEquals(400, bust.quantity());
    assertEquals(99_500_000_000L, bust.price().mantissa());
    er('H', 1, 77, '4', 0, 10); // unknown bust
    assertEquals(1, gateway.orders().unknownBusts());
    er('6', 1, 8, '6', 0, 10); // pending cancel dropped
    assertEquals(1, gateway.orders().busts());
    er('F', 2, 9, '2', 10, 10);
    assertEquals(OrdStatus.Filled, lastEr().ordStatus());
    // replace moves state to the new ClOrdID
    er('0', 3, 10, '0', 0, 10);
    final FixWriter w = venue.begin(MsgType.EXECUTION_REPORT);
    w.put(37, 903).put(11, 4).put(41, 3).put(17, 11).putChar(150, (byte) '5').putChar(39, (byte) '0');
    instrument(w).putChar(54, (byte) '1').put(38, 20).put(151, 20).put(14, 0).put(6, 0);
    venue.deliver(session);
    assertEquals(4, lastEr().clOrdId());
    assertEquals(2_000, lastEr().leavesQuantity());
    // RFQ trade report keyed by QuoteID
    final FixWriter rfq = venue.begin(MsgType.EXECUTION_REPORT);
    rfq.put(37, 5).put(17, 12).put(117, 77).put(131, 42).putChar(150, (byte) 'F').putChar(39, (byte) '2');
    instrument(rfq).putChar(54, (byte) '2').put(38, 5).put(32, 5).put(31, 100).put(151, 0).put(14, 5).put(6, 100);
    venue.deliver(session);
    assertEquals(77, lastEr().clOrdId());
    assertEquals(OrdStatus.Filled, lastEr().ordStatus());
    assertEquals(Side.Sell, lastEr().side());
    venue.begin(MsgType.EXECUTION_REPORT).put(37, 0).put(584, 1).put(911, 0).putChar(150, (byte) 'I');
    venue.deliver(session);
    assertEquals(1, gateway.stats().massStatusEmpty());
    final FixWriter unknown = venue.begin(MsgType.EXECUTION_REPORT).put(11, 1).putText(48, "XX");
    assertTrue(unknown.bodyLength() > 0);
    venue.deliver(session);
    assertEquals(1, gateway.stats().unknownInstrument());
  }

  @Test
  void trackerFullPassesReportsThrough() {
    final OrderStateTracker small = new OrderStateTracker(1, 4);
    small.onNewOrder(1, 10);
    assertEquals(OrderStateTracker.FULL, small.apply((byte) '0', 2, 0, 1, (byte) '0', 0, 0, 10));
    assertEquals(1, small.full());
    assertEquals(1, small.size());
    small.clear();
    assertEquals(0, small.size());
    assertEquals(OrderStateTracker.EMIT, small.apply((byte) '0', 2, 0, 1, (byte) '0', 0, 0, 10));
    assertEquals(OrderStateTracker.EMIT, small.apply((byte) '8', 2, 0, 2, (byte) '8', 0, 0, 10));
    assertEquals('8', small.lastStatus());
  }

  @Test
  void marketDataSnapshotAndIncrementalBecomeBookUpdates() {
    final FixWriter snap = venue.begin(MsgType.MARKET_DATA_SNAPSHOT);
    snap.put(262, 1);
    instrument(snap).put(268, 3)
        .putChar(269, (byte) '0').putDecimal(270, 99_500_000_000L, 9).put(271, 5_000_000).put(346, 2)
        .putChar(269, (byte) '1').putDecimal(270, 99_515_625_000L, 9).put(271, 3_000_000).put(1023, 1)
        .putChar(269, (byte) '2').putDecimal(270, 99_5, 1).put(271, 1);
    venue.deliver(session);
    assertEquals(3, received.size());
    final BookUpdateDecoder d = new BookUpdateDecoder();
    d.wrapAndApplyHeader(received.get(0), 0, header);
    assertEquals(BookAction.Clear, d.action());
    d.wrapAndApplyHeader(received.get(1), 0, header);
    assertEquals(BookAction.Add, d.action());
    assertEquals(Side.Buy, d.side());
    assertEquals(1, d.level());
    assertEquals(500_000_000L, d.quantity());
    assertEquals(2, d.orderCount());
    d.wrapAndApplyHeader(received.get(2), 0, header);
    assertEquals(Side.Sell, d.side());
    assertEquals(99_515_625_000L, d.price().mantissa());

    final FixWriter inc = venue.begin(MsgType.MARKET_DATA_INCREMENTAL);
    inc.put(262, 1).put(268, 3)
        .putChar(279, (byte) '1').putChar(269, (byte) '0');
    instrument(inc).put(83, 17).putDecimal(270, 99_500_000_000L, 9).put(271, 6_000_000).put(1023, 1)
        .putChar(279, (byte) '2').putChar(269, (byte) '1');
    instrument(inc).putDecimal(270, 99_515_625_000L, 9).put(271, 0)
        .putChar(279, (byte) '0').putChar(269, (byte) '1').putText(48, "UNKNOWN").put(270, 100).put(271, 1);
    venue.deliver(session);
    assertEquals(5, received.size());
    d.wrapAndApplyHeader(received.get(3), 0, header);
    assertEquals(BookAction.Change, d.action());
    assertEquals(17, d.venueSeqNo());
    d.wrapAndApplyHeader(received.get(4), 0, header);
    assertEquals(BookAction.Delete, d.action());
    assertEquals(1, gateway.stats().unknownInstrument());
    venue.begin(MsgType.MARKET_DATA_SNAPSHOT).put(262, 1).putText(48, "NOPE").put(268, 0);
    venue.deliver(session);
    venue.begin(MsgType.MARKET_DATA_INCREMENTAL).put(262, 1);
    venue.deliver(session);
    assertEquals(2, gateway.stats().unknownInstrument());
  }

  @Test
  void outboundSbeBecomesFix() {
    final QuoteEncoder q = new QuoteEncoder();
    q.wrapAndApplyHeader(out, 0, headerEncoder).venueId(101).rfqId(42).quoteId(7).instrumentId(1008)
        .bidQuantity(1_000_000_000L).askQuantity(0).validUntilNanos(clock.epochNanos()).sentNanos(clock.epochNanos());
    q.bidPrice().mantissa(99_500_000_000L);
    q.askPrice().mantissa(0);
    assertTrue(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + q.encodedLength()));
    assertTrue(venue.last().contains("|35=S|") && venue.last().contains("|131=42|117=7|55=" + ISIN + "|48=" + ISIN + "|22=4|132=99.5|134=10000000|"), venue.last());
    assertFalse(venue.last().contains("|133="));
    q.askQuantity(500_000_000L).bidQuantity(0);
    q.askPrice().mantissa(99_515_625_000L);
    gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + q.encodedLength());
    assertTrue(venue.last().contains("|133=99.515625|135=5000000|"), venue.last());

    final QuoteCancelEncoder qc = new QuoteCancelEncoder();
    qc.wrapAndApplyHeader(out, 0, headerEncoder).venueId(101).rfqId(42).quoteId(7).instrumentId(1008);
    assertTrue(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + qc.encodedLength()));
    assertTrue(venue.last().contains("|35=Z|131=42|117=7|298=5|") || venue.last().contains("|131=42|117=7|298=5|"), venue.last());

    final NewOrderEncoder no = new NewOrderEncoder();
    no.wrapAndApplyHeader(out, 0, headerEncoder).venueId(102).clOrdId(5).instrumentId(1008).side(Side.Sell)
        .ordType(OrdType.Limit).timeInForce(TimeInForce.GoodTillDate).quantity(2_500_000_000L)
        .expireNanos(clock.epochNanos() + 1_000_000_000L).sentNanos(clock.epochNanos());
    no.price().mantissa(99_515_625_000L);
    assertTrue(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + no.encodedLength()));
    assertTrue(venue.last().contains("|35=D|") && venue.last().contains("|11=5|") && venue.last().contains("|54=2|"), venue.last());
    assertTrue(venue.last().contains("|38=25000000|40=2|44=99.515625|59=6|126="), venue.last());
    no.ordType(OrdType.Market).timeInForce(TimeInForce.ImmediateOrCancel);
    gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + no.encodedLength());
    assertTrue(venue.last().contains("|40=1|59=3|") && !venue.last().contains("|44="), venue.last());

    final OrderCancelEncoder oc = new OrderCancelEncoder();
    oc.wrapAndApplyHeader(out, 0, headerEncoder).venueId(102).clOrdId(6).origClOrdId(5).instrumentId(1008).side(Side.Sell)
        .quantity(2_500_000_000L).sentNanos(clock.epochNanos());
    gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + oc.encodedLength());
    assertTrue(venue.last().contains("|35=F|") && venue.last().contains("|41=5|11=6|"), venue.last());

    final OrderReplaceEncoder or = new OrderReplaceEncoder();
    or.wrapAndApplyHeader(out, 0, headerEncoder).venueId(102).clOrdId(7).origClOrdId(5).instrumentId(1008).side(Side.Buy)
        .quantity(100).sentNanos(clock.epochNanos());
    or.price().mantissa(99_000_000_000L);
    gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + or.encodedLength());
    assertTrue(venue.last().contains("|35=G|") && venue.last().contains("|40=2|44=99|"), venue.last());
    or.instrumentId(55);
    assertFalse(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + or.encodedLength()));
    oc.instrumentId(55);
    assertFalse(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + oc.encodedLength()));
    no.instrumentId(55);
    assertFalse(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + no.encodedLength()));
    q.instrumentId(55);
    assertFalse(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + q.encodedLength()));
    assertEquals(4, gateway.stats().unknownInstrument());
    assertEquals(7, gateway.stats().outboundTranslated());

    final QuoteResponseDecoder unsupportedTemplate = new QuoteResponseDecoder();
    new io.ratesalgo.messages.sbe.HeartbeatEncoder().wrapAndApplyHeader(out, 0, headerEncoder).sourceId(1);
    assertFalse(gateway.offer(out, 0, 20));
    assertEquals(1, gateway.stats().unsupportedOutbound());
    assertTrue(unsupportedTemplate.sbeTemplateId() > 0);

    assertTrue(gateway.requestQuoteStatus(1, 7, 1008));
    assertTrue(venue.last().contains("|35=a|") && venue.last().contains("|649=1|117=7|"), venue.last());
    assertTrue(gateway.requestOrderMassStatus(2));
    assertTrue(venue.last().contains("|35=AF|") && venue.last().contains("|584=2|585=7|"), venue.last());
    assertTrue(gateway.subscribeMarketData(3, 1008, 5));
    assertTrue(venue.last().contains("|262=3|263=1|264=5|265=1|267=2|269=0|269=1|146=1|55="), venue.last());
    assertFalse(gateway.subscribeMarketData(3, 99, 5));
    assertFalse(gateway.requestQuoteStatus(1, 7, 99));
    session.logout();
    assertFalse(gateway.requestOrderMassStatus(2));
    assertFalse(gateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + qc.encodedLength()));
    assertTrue(gateway.stats().droppedNotActive() >= 1);
    assertEquals(0, gateway.stats().sinkRejected());
    assertTrue(templateOf(out) > 0);
  }

  @Test
  void sinkBackPressureAndMapRules() {
    final InstrumentMap map = new InstrumentMap().register(1, "A");
    assertThrows(IllegalArgumentException.class, () -> map.register(2, "A"));
    assertThrows(IllegalArgumentException.class, () -> map.register(1, "B"));
    assertThrows(IllegalArgumentException.class, () -> map.register(0, "C"));
    assertEquals(1, map.size());
    final VenueGateway refusing = new VenueGateway(1, new InstrumentMap().register(1008, ISIN), (b, o, l) -> false, clock, new OrderStateTracker(4, 4));
    final ScriptedVenue v = new ScriptedVenue(clock);
    final FixSession s = new FixSession(new FixSessionConfig("ENGINE", "VENUE", 30, true), clock, v, refusing);
    refusing.bind(s);
    s.onConnected();
    v.begin(MsgType.LOGON).put(98, 0).put(108, 30).putBool(141, true);
    v.deliver(s);
    v.begin(MsgType.QUOTE_RESPONSE).put(131, 1).put(117, 1).put(694, 6);
    v.deliver(s);
    assertEquals(1, refusing.stats().sinkRejected());
    assertEquals(0, refusing.stats().outboundTranslated());
    assertEquals(0, refusing.stats().droppedNotActive());
  }
}
