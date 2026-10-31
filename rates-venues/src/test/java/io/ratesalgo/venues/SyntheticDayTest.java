package io.ratesalgo.venues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.gateway.InstrumentMap;
import io.ratesalgo.fix.gateway.VenueGateway;
import io.ratesalgo.fix.order.OrderStateTracker;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.NewOrderEncoder;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.RfqOutcome;
import io.ratesalgo.venues.feed.BookFeedConsumer;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import io.ratesalgo.venues.sim.EngineSession;
import io.ratesalgo.venues.sim.Instrument;
import io.ratesalgo.venues.sim.VenueHarness;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

/**
 * A compressed trading day through the FIX layer (P2 gate): the D2C venue generates RFQs from its
 * arrival model from 07:00 to 17:00 New York time; an auto-quoter on the engine side quotes every
 * one through the gateway; the CLOB receives an engine order every simulated quarter hour against
 * simulated liquidity, and its book feed is rebuilt by a consumer. The day must end with zero
 * session sequence errors on both sessions and a feed book equal to the venue's.
 */
class SyntheticDayTest {

  static final long START = 1_793_620_800_000_000_000L; // 2026-11-02T11:00:00Z = 06:00 New York (EST)
  static final long HOUR = 3_600_000_000_000L;

  private final List<long[]> rfqs = new ArrayList<>();

  @Test
  void fullDayHasZeroSequenceErrors() {
    final ManualClock clock = new ManualClock(START);
    final D2cRfqSimulator d2c = (D2cRfqSimulator) Venues.create("d2c", clock, 2026, new InProcessFeedChannel());
    final InProcessFeedChannel feedChannel = new InProcessFeedChannel();
    final ClobSimulator clob = (ClobSimulator) Venues.create("clob", clock, 2026, feedChannel);
    final InstrumentMap map = new InstrumentMap();
    for (final Instrument i : Venues.treasuries()) {
      map.register(i.instrumentId(), i.securityId());
    }
    for (final Instrument i : Venues.swaps()) {
      map.register(i.instrumentId(), i.securityId());
    }
    final MessageHeaderDecoder header = new MessageHeaderDecoder();
    final QuoteRequestDecoder rfq = new QuoteRequestDecoder();
    final VenueGateway d2cGateway =
        new VenueGateway(101, map, (DirectBuffer b, int o, int l) -> {
          header.wrap(b, o);
          if (header.templateId() == QuoteRequestDecoder.TEMPLATE_ID) {
            rfq.wrapAndApplyHeader(b, o, header);
            rfqs.add(new long[] {rfq.rfqId(), rfq.instrumentId(), rfq.side() == Side.Buy ? 1 : 2, rfq.quantity()});
          }
          return true;
        }, clock, new OrderStateTracker(1024, 4096));
    final VenueGateway clobGateway = new VenueGateway(102, map, (b, o, l) -> true, clock, new OrderStateTracker(1024, 4096));
    final BookFeedConsumer consumer = new BookFeedConsumer(clob.feed());
    try (VenueHarness hd = new VenueHarness(d2c, clock);
        VenueHarness hc = new VenueHarness(clob, clock);
        EngineSession ed = hd.connect("RALGO", d2cGateway);
        EngineSession ec = hc.connect("RALGO", clobGateway)) {
      d2cGateway.bind(ed.session());
      clobGateway.bind(ec.session());
      final Instrument tenYear = Venues.treasuries().get(2);
      clob.submitInternal("DLR-A", tenYear.instrumentId(), false, tenYear.initialMid() + 15_625_000L, 500_000_000L, 0);
      clob.submitInternal("DLR-B", tenYear.instrumentId(), true, tenYear.initialMid() - 15_625_000L, 500_000_000L, 0);
      d2c.startAutoFlow();
      final UnsafeBuffer out = new UnsafeBuffer(new byte[256]);
      final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
      final QuoteEncoder quote = new QuoteEncoder();
      final NewOrderEncoder order = new NewOrderEncoder();
      long quoteId = 0;
      long clOrdId = 0;
      long nextOrderAt = clock.nanoTime() + HOUR;
      final long end = clock.nanoTime() + 11 * HOUR;
      while (clock.nanoTime() < end) {
        hd.advanceToNextEvent(60_000_000_000L);
        hc.advance(0);
        ed.quiesce();
        while (!rfqs.isEmpty()) {
          final long[] r = rfqs.remove(0);
          final long mid = d2c.mid(securityOf(r[1]));
          final long half = r[1] < 2000 ? 7_812_500L : 2_500_000L;
          quote.wrapAndApplyHeader(out, 0, headerEncoder).venueId(101).rfqId(r[0]).quoteId(++quoteId)
              .instrumentId((int) r[1]).bidQuantity(r[3]).askQuantity(r[3])
              .validUntilNanos(clock.epochNanos() + 5_000_000_000L).sentNanos(clock.epochNanos());
          quote.bidPrice().mantissa(mid - half);
          quote.askPrice().mantissa(mid + half);
          d2cGateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + quote.encodedLength());
          ed.quiesce();
        }
        if (clock.nanoTime() >= nextOrderAt) {
          nextOrderAt += HOUR / 4;
          order.wrapAndApplyHeader(out, 0, headerEncoder).venueId(102).clOrdId(++clOrdId).instrumentId(tenYear.instrumentId())
              .side(clOrdId % 2 == 0 ? Side.Buy : Side.Sell).ordType(OrdType.Limit).timeInForce(TimeInForce.ImmediateOrCancel)
              .quantity(1_000_000_000L).sentNanos(clock.epochNanos());
          order.price().mantissa(clOrdId % 2 == 0 ? tenYear.initialMid() + 15_625_000L : tenYear.initialMid() - 15_625_000L);
          clobGateway.offer(out, 0, MessageHeaderEncoder.ENCODED_LENGTH + order.encodedLength());
          ec.quiesce();
          feedChannel.poll(consumer, Integer.MAX_VALUE);
        }
      }
      hd.advance(HOUR);
      ed.quiesce();
      ec.quiesce();
      feedChannel.poll(consumer, Integer.MAX_VALUE);

      final Map<RfqOutcome, Integer> outcomes = d2c.outcomes();
      final long total = d2c.rfqCount();
      assertTrue(total > 300, "RFQs in the day: " + total);
      assertEquals(total, outcomes.values().stream().mapToInt(Integer::intValue).sum());
      assertTrue(outcomes.getOrDefault(RfqOutcome.DONE, 0) > 0);
      assertEquals(0, ed.session().stats().sequenceErrors());
      assertEquals(0, ec.session().stats().sequenceErrors());
      assertEquals(0, ed.session().stats().garbled());
      assertEquals(0, d2c.quotesRejected());
      assertTrue(clOrdId >= 40, "engine orders: " + clOrdId);
      assertTrue(clob.trades() >= 40);
      assertEquals(0, consumer.gaps());
      assertTrue(clob.feed().lastSequence() >= 40, "feed frames " + clob.feed().lastSequence());
      assertEquals(clob.feed().lastSequence(), consumer.lastSequence());
      assertEquals(
          clob.book(tenYear.instrumentId()).levels(false, 5).get(0)[0],
          consumer.best(tenYear.instrumentId(), Side.Sell));
      System.out.println(
          "synthetic day: rfqs=" + total + " outcomes=" + outcomes + " engineMessages="
              + ed.session().stats().received() + "+" + ec.session().stats().received()
              + " feedFrames=" + clob.feed().lastSequence());
    }
  }

  private static String securityOf(final long instrumentId) {
    for (final Instrument i : Venues.treasuries()) {
      if (i.instrumentId() == instrumentId) {
        return i.securityId();
      }
    }
    for (final Instrument i : Venues.swaps()) {
      if (i.instrumentId() == instrumentId) {
        return i.securityId();
      }
    }
    throw new IllegalArgumentException("unknown " + instrumentId);
  }
}
