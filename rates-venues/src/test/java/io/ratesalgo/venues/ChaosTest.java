package io.ratesalgo.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.ExecutionReportDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.OrderCancelEncoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.TradeBustDecoder;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.ClientDecision;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.RfqSpec;
import io.ratesalgo.venues.feed.BookFeedConsumer;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.Px;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/** Disconnects, duplicated, reordered, late and busted executions, and feed gaps. */
class ChaosTest {

  private final List<UnsafeBuffer> sbe = new ArrayList<>();
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);
  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();

  private VenueGateway gateway(final ManualClock clock) {
    final InstrumentMap map = new InstrumentMap();
    for (final Instrument i : Venues.treasuries()) {
      map.register(i.instrumentId(), i.securityId());
    }
    return new VenueGateway(102, map, (b, o, l) -> {
      final byte[] copy = new byte[l];
      b.getBytes(o, copy);
      sbe.add(new UnsafeBuffer(copy));
      return true;
    }, clock, new OrderStateTracker(256, 1024));
  }

  private void order(final VenueGateway g, final long clOrdId, final Side side, final long price, final long qty) {
    final NewOrderEncoder e = new NewOrderEncoder();
    e.wrapAndApplyHeader(out, 0, headerEncoder).venueId(102).clOrdId(clOrdId).instrumentId(1007).side(side)
        .ordType(OrdType.Limit).timeInForce(TimeInForce.Day).quantity(qty * 100);
    e.price().mantissa(price);
    assertTrue(g.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength()));
  }

  private List<ExecutionReportDecoder> reports() {
    final List<ExecutionReportDecoder> list = new ArrayList<>();
    for (final UnsafeBuffer b : sbe) {
      header.wrap(b, 0);
      if (header.templateId() == ExecutionReportDecoder.TEMPLATE_ID) {
        final ExecutionReportDecoder d = new ExecutionReportDecoder();
        d.wrapAndApplyHeader(b, 0, header);
        list.add(d);
      }
    }
    return list;
  }

  @Test
  void duplicateReorderedLateAndBustedExecutions() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 3, new InProcessFeedChannel());
    final VenueGateway g = gateway(clock);
    try (VenueHarness h = new VenueHarness(clob, clock); EngineSession e = h.connect("RALGO", g)) {
      g.bind(e.session());
      final long px = Px.price("98");
      // duplicate fill
      order(g, 1, Side.Buy, px, 4_000_000);
      clob.duplicateNextFill();
      clob.submitInternal("DLR-A", 1007, false, px, 2_000_000, 0);
      e.quiesce();
      assertEquals(1, g.orders().duplicates());
      h.advance(10_000_000_000L);
      // reversed fills: one aggressor against two resting slices
      clob.submitInternal("DLR-B", 1007, false, px + 7_812_500L, 1_000_000, 0);
      clob.submitInternal("DLR-C", 1007, false, px + 7_812_500L, 1_000_000, 0);
      clob.reverseNextFills();
      order(g, 2, Side.Buy, px + 7_812_500L, 2_000_000);
      e.quiesce();
      final List<ExecutionReportDecoder> ers = reports();
      final ExecutionReportDecoder last = ers.get(ers.size() - 1);
      assertEquals(2, last.clOrdId());
      assertEquals(OrdStatus.Filled, last.ordStatus());
      assertEquals(200_000_000L, last.cumQuantity());
      h.advance(10_000_000_000L);
      // late fill after cancel
      clob.lateFillOnNextCancel();
      final OrderCancelEncoder c = new OrderCancelEncoder();
      c.wrapAndApplyHeader(out, 0, headerEncoder).venueId(102).clOrdId(3).origClOrdId(1).instrumentId(1007)
          .side(Side.Buy).quantity(400_000_000L);
      g.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + c.encodedLength());
      e.quiesce();
      assertEquals(1, g.orders().lateFills());
      // bust the first execution
      final long firstExec = clob.executionIds("RALGO").get(0);
      assertTrue(clob.bust(firstExec));
      assertFalse(clob.bust(firstExec));
      e.quiesce();
      header.wrap(sbe.get(sbe.size() - 1), 0);
      assertEquals(TradeBustDecoder.TEMPLATE_ID, header.templateId());
      assertEquals(1, g.orders().busts());
      assertEquals(0, e.session().stats().sequenceErrors());
    }
  }

  @Test
  void sessionGapIsRecoveredByResend() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final D2cRfqSimulator d2c = (D2cRfqSimulator) Venues.create("d2c", clock, 3, new InProcessFeedChannel());
    final List<String> rfqIds = new ArrayList<>();
    try (VenueHarness h = new VenueHarness(d2c, clock);
        EngineSession e = h.connect("RALGO", (s, m, d) -> rfqIds.add(m.getString(FixTags.QUOTE_REQ_ID) + (d ? "*" : "")))) {
      final int[] seen = {0};
      e.transport().filter((b, o, l) -> {
        final FixParser p = new FixParser();
        p.wrap(b, o, l);
        return !(p.msgType() == MsgType.QUOTE_REQUEST && ++seen[0] == 2);
      });
      for (int i = 0; i < 3; i++) {
        d2c.injectRfq(new RfqSpec("US91282CZG94", true, 1_000_000, 3, List.of(Px.price("98.03")), 2001, ClientDecision.NONE, false));
      }
      e.quiesce();
      assertEquals(List.of("1", "2*", "3*"), rfqIds);
      assertEquals(1, e.session().stats().resendRequestsSent());
      assertEquals(1, e.session().stats().gapsDetected());
    }
  }

  @Test
  void disconnectMidRfqRemovesQuoteAndCompetitorWins() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final D2cRfqSimulator d2c = (D2cRfqSimulator) Venues.create("d2c", clock, 3, new InProcessFeedChannel());
    try (VenueHarness h = new VenueHarness(d2c, clock); EngineSession e = h.connect("RALGO", (s, m, d) -> { })) {
      final long rfq = d2c.injectRfq(new RfqSpec("US91282CZG94", true, 1_000_000, 3, List.of(Px.price("98.05")), 2001,
          ClientDecision.BEST, false));
      e.quiesce();
      e.session().begin(MsgType.QUOTE).put(FixTags.QUOTE_REQ_ID, rfq).put(FixTags.QUOTE_ID, 1).putText(55, "US91282CZG94")
          .putText(48, "US91282CZG94").putChar(22, (byte) '4').putDecimal(FixTags.OFFER_PX, Px.price("98.01"), 9)
          .put(FixTags.OFFER_SIZE, 1_000_000);
      e.session().send();
      e.quiesce();
      e.disconnect();
      d2c.decide(rfq);
      assertEquals("DLR-A", d2c.trades().get(0).winner());
      assertTrue(d2c.eventLog().stream().anyMatch(l -> l.contains("cancel-on-disconnect RALGO:1")));
    }
  }

  @Test
  void feedGapsDuplicatesAndReorderingRecoverToTheVenueBook() {
    final ManualClock clock = new ManualClock(Venues.T0);
    final InProcessFeedChannel channel = new InProcessFeedChannel();
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 3, channel);
    final BookFeedConsumer consumer = new BookFeedConsumer(clob.feed());
    final long px = Px.price("98");
    for (int i = 0; i < 5; i++) {
      clob.submitInternal("DLR-A", 1007, true, px - i * 7_812_500L, 1_000_000L * (i + 1), 0);
    }
    channel.poll(consumer, 100);
    channel.dropNext(2);
    clob.submitInternal("DLR-B", 1007, false, px + 7_812_500L, 3_000_000, 0);
    clob.submitInternal("DLR-B", 1007, false, px + 15_625_000L, 3_000_000, 0);
    clob.submitInternal("DLR-B", 1007, false, px + 23_437_500L, 3_000_000, 0);
    channel.duplicateNext(1);
    clob.submitInternal("DLR-C", 1007, true, px, 2_000_000, 0);
    channel.swapNext();
    clob.submitInternal("DLR-C", 1007, true, px - 7_812_500L, 1_000_000, 0);
    clob.submitInternal("DLR-C", 1007, true, px - 15_625_000L, 1_000_000, 0);
    channel.poll(consumer, 100);
    assertEquals(2, channel.dropped());
    assertTrue(consumer.gaps() >= 1);
    assertTrue(consumer.recoveries() >= 1);
    assertTrue(consumer.duplicates() >= 1);
    assertFalse(consumer.isStale());
    assertEquals(clob.feed().lastSequence(), consumer.lastSequence());
    for (final boolean bid : new boolean[] {true, false}) {
      final var venue = clob.book(1007).levels(bid, 5);
      final var rebuilt = consumer.levels(1007, bid ? Side.Buy : Side.Sell);
      assertEquals(venue.size(), rebuilt.size());
      for (final long[] level : venue) {
        assertEquals(level[1], rebuilt.get(level[0]), "level " + level[0]);
      }
    }
    assertEquals(0, consumer.foreign());
    assertEquals(BookAction.Clear, BookAction.get((short) 4));
  }
}
