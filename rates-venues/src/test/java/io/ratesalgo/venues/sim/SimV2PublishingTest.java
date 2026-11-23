package io.ratesalgo.venues.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TradePrintDecoder;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.feed.BookFeedPublisher;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the simulator v2 venues publish for a desk that reads several of them: a feed venue id
 * per venue, a trade print for every trade, and the curve quotes the model has no instrument for.
 */
class SimV2PublishingTest {

  private static final long OPEN = 1_793_624_400_000_000_000L;
  private static final long SECOND = 1_000_000_000L;

  private static MarketSpec spec;

  @BeforeAll
  static void load() {
    spec = new MarketSpec(SimParams.loadDefault());
  }

  private static void run(final Venues.SimV2 v, final ManualClock clock, final long seconds) {
    for (long s = 0; s < seconds; s++) {
      clock.advance(SECOND);
      v.onTime();
    }
  }

  /** Counts of one channel's frames by template, and the venue ids and print sequences seen. */
  private static final class Frames {
    final Set<Integer> venueIds = new HashSet<>();
    final List<Long> printSeqs = new ArrayList<>();
    long books;
    long printQuantity;
    long lastBookSeq;

    void drain(final InProcessFeedChannel channel) {
      final MessageHeaderDecoder header = new MessageHeaderDecoder();
      final BookUpdateDecoder book = new BookUpdateDecoder();
      final TradePrintDecoder print = new TradePrintDecoder();
      channel.poll(
          (buffer, offset, length) -> {
            header.wrap(buffer, offset);
            if (header.templateId() == TradePrintDecoder.TEMPLATE_ID) {
              print.wrapAndApplyHeader(buffer, offset, header);
              venueIds.add(print.venueId());
              printSeqs.add(print.venueSeqNo());
              printQuantity += print.quantity();
              assertTrue(print.price().mantissa() > 0 && print.quantity() > 0);
              assertTrue(print.aggressorSide() == Side.Buy || print.aggressorSide() == Side.Sell);
            } else {
              book.wrapAndApplyHeader(buffer, offset, header);
              venueIds.add(book.venueId());
              assertEquals(lastBookSeq + 1, book.venueSeqNo(), "a print must not move the book sequence");
              lastBookSeq = book.venueSeqNo();
              books++;
            }
            return true;
          },
          Integer.MAX_VALUE);
    }
  }

  @Test
  void everyVenueHasItsOwnFeedIdAndPrintsItsTrades() {
    final ManualClock clock = new ManualClock(OPEN);
    final List<InProcessFeedChannel> channels =
        List.of(new InProcessFeedChannel(), new InProcessFeedChannel(), new InProcessFeedChannel());
    final Venues.SimV2 v =
        Venues.simV2(
            SessionSeeds.session(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 92_001)), clock,
            new ArrayList<FeedChannel>(channels));
    assertEquals(ClobSimulator.VENUE_ID, v.treasuryBooks().get(0).venueId());
    assertEquals(ClobSimulator.VENUE_ID + 1, v.treasuryBooks().get(1).venueId());
    assertEquals(ClobSimulator.VENUE_ID + 2, v.futuresBook().venueId());
    assertEquals(Venues.simV2TreasuryVenueId(1), v.treasuryBooks().get(1).venueId());
    assertEquals(Venues.simV2FuturesVenueId(2), v.futuresBook().venueId());

    v.startFlow();
    run(v, clock, 600);

    final List<ClobSimulator> books = new ArrayList<>(v.treasuryBooks());
    books.add(v.futuresBook());
    for (int i = 0; i < 3; i++) {
      final Frames f = new Frames();
      f.drain(channels.get(i));
      final ClobSimulator book = books.get(i);
      assertEquals(Set.of(book.venueId()), f.venueIds, "frames of venue " + i + " carry its id only");
      assertTrue(f.books > 0);
      assertTrue(book.trades() > 0, "other takers traded on venue " + i);
      assertEquals(book.trades(), f.printSeqs.size(), "one print per trade");
      for (int k = 0; k < f.printSeqs.size(); k++) {
        assertEquals(k + 1L, f.printSeqs.get(k), "prints count from 1 on their own sequence");
      }
      assertEquals(f.printSeqs.size(), book.feed().lastPrintSequence());
      assertEquals(0, f.printQuantity % BookFeedPublisher.MINOR_UNITS);
    }
  }

  @Test
  void theVersionOneVenuePublishesNoPrints() {
    final ManualClock clock = new ManualClock(OPEN);
    final InProcessFeedChannel channel = new InProcessFeedChannel();
    final ClobSimulator clob =
        new ClobSimulator("CLOB", ClobConfig.defaults(), Venues.treasuries(), Venues.ENGINES, channel, clock, 3L);
    assertEquals(ClobSimulator.VENUE_ID, clob.venueId());
    final int id = Venues.treasuries().get(0).instrumentId();
    final long px = Venues.treasuries().get(0).initialMid();
    assertTrue(clob.submitInternal("A", id, false, px, 5_000_000, 0) > 0);
    assertTrue(clob.submitInternal("B", id, true, px, 2_000_000, 0) > 0);
    assertEquals(1, clob.trades());
    final Frames f = new Frames();
    f.drain(channel);
    assertTrue(f.books > 0);
    assertTrue(f.printSeqs.isEmpty(), "the recorded venue's feed stays books only");
    assertEquals(0, clob.feed().lastPrintSequence());
  }

  @Test
  void theCurveRoundFollowsTheModelsFrontNodeAndPublishesOnTheTick() {
    final ManualClock clock = new ManualClock(OPEN);
    final Venues.SimV2 v =
        Venues.simV2(
            SessionSeeds.session(spec, SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 92_002)), clock,
            List.of(new InProcessFeedChannel(), new InProcessFeedChannel(), new InProcessFeedChannel()));
    final SimV2Session session = v.session();
    final SimV2CurveRound round = new SimV2CurveRound(session);
    assertEquals(24, round.shortestSwapMonths());
    final int twoYear = spec.indexOf("SOFR-2Y");
    final int tenYear = spec.indexOf("SOFR-10Y");
    final int twentyYear = spec.indexOf("SOFR-20Y");
    final double open = spec.instrument(twoYear).initialMid();

    // at the open the front is flat at the two-year rate
    assertEquals(SimV2Session.mantissa(open), round.overnight());
    assertEquals(SimV2Session.mantissa(open), round.parSwap(12));
    assertEquals(SimV2Session.mantissa(100.0 - open), round.futurePrice());
    assertEquals(session.referencePrice(tenYear), round.parSwap(120));

    v.startFlow();
    run(v, clock, 1_800);

    final double moved = session.model().latentMid(twoYear, session.sessionNanos());
    assertNotEquals(open, moved, "the two-year rate moved in half an hour");
    assertEquals(SimV2Session.mantissa(open), round.overnight(), "the overnight rate holds through the day");
    assertEquals(open + (moved - open), round.frontRatePercent(), 1e-12);
    // model swaps are published as the venue's reference price; the 1-year moves with the 2-year
    assertEquals(session.referencePrice(twoYear), round.parSwap(24));
    assertEquals(session.referencePrice(tenYear), round.parSwap(120));
    assertEquals(session.referencePrice(spec.indexOf("SOFR-30Y")), round.parSwap(360));
    assertEquals(session.referencePrice(spec.indexOf("SOFR-30Y")), round.parSwap(480), "beyond the last node: the last");
    assertEquals(round.parSwap(24), round.parSwap(12), "below two years the two-year node applies");
    assertEquals(round.parSwap(12), round.parSwap(6));
    // between two model tenors: linear in maturity, on the swap tick
    final double ten = session.model().latentMid(tenYear, session.sessionNanos());
    final double twenty = session.model().latentMid(twentyYear, session.sessionNanos());
    final long twelve = round.parSwap(144);
    assertEquals((0.8 * ten + 0.2 * twenty) * 1e9, (double) twelve, 51_000.0, "within half a swap tick of the interpolation");
    assertEquals(0, twelve % 100_000L, "published on the swap tick");
    // futures: 100 less the front rate, on a quarter of a basis point
    final long future = round.futurePrice();
    assertEquals(0, future % 2_500_000L, "published on the futures tick");
    assertEquals((100.0 - round.frontRatePercent()) * 1e9, (double) future, 1_251_000.0);
    assertFalse(future == SimV2Session.mantissa(100.0 - open) && Math.abs(moved - open) > 0.0025);
  }
}
