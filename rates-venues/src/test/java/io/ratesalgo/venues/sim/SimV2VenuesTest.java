package io.ratesalgo.venues.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.fix.codec.FixParser;
import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.MsgType;
import io.ratesalgo.fix.session.FixSession;
import io.ratesalgo.refdata.RefData;
import io.ratesalgo.sim.BookModel;
import io.ratesalgo.sim.DayType;
import io.ratesalgo.sim.MarketModel;
import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.RfqDecision;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.sim.SimInstrument;
import io.ratesalgo.sim.SimParams;
import io.ratesalgo.sim.SimRfq;
import io.ratesalgo.venues.Venues;
import io.ratesalgo.venues.clob.ClobBook;
import io.ratesalgo.venues.clob.ClobConfig;
import io.ratesalgo.venues.clob.ClobSimulator;
import io.ratesalgo.venues.d2c.D2cRfqSimulator;
import io.ratesalgo.venues.d2c.RfqOutcome;
import io.ratesalgo.venues.d2c.TradeRecord;
import io.ratesalgo.venues.feed.FeedChannel;
import io.ratesalgo.venues.feed.InProcessFeedChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The venue simulators under the {@code sim_v2} presets: the venue half of acceptance test A7 (one
 * specification), the venue part of A2 (a venue stepped at 10 ms and at 100 ms sees the same
 * RFQs, quotes and decisions), and the order books' link to the shared model (design section 8).
 * No FIX session is needed: with no engine logged on the desk never quotes, and the desk's orders
 * are entered through the simulator's internal entry point under another owner.
 */
class SimV2VenuesTest {

  /** 07:00 New York on the fixtures' trade date, 2026-11-02 (12:00 UTC). */
  private static final long OPEN = 1_793_620_800_000_000_000L + 3_600_000_000_000L;

  private static final long SECOND = 1_000_000_000L;

  private static MarketSpec spec;

  @BeforeAll
  static void load() {
    spec = new MarketSpec(SimParams.loadDefault());
  }

  private static List<FeedChannel> feeds() {
    return List.of(new InProcessFeedChannel(), new InProcessFeedChannel(), new InProcessFeedChannel());
  }

  private static Venues.SimV2 venues(final long seed, final ManualClock clock) {
    return Venues.simV2(SessionSeeds.session(spec, seed), clock, feeds());
  }

  /** Runs the venues from the open to {@code untilNanos} of session time, moving the clock by at most {@code step}. */
  private static void run(final Venues.SimV2 v, final ManualClock clock, final long untilNanos, final long step) {
    final long end = v.session().nanoTimeOf(untilNanos);
    while (clock.nanoTime() < end) {
      final long next = Math.min(Math.min(v.nextEventNanos(), end), clock.nanoTime() + step);
      clock.advance(Math.max(0, next - clock.nanoTime()));
      v.onTime();
    }
  }

  /**
   * A7, venue half. For 20 seeds and a desk that never quotes, the D2C venue's RFQs, winning
   * prices and outcomes are the shared model's: same count, same order, every trade at the
   * model's winning competitor quote, every outcome the model's decision. With the back-test
   * half ({@code SimV2BacktestTest}) both users are equal to the model, so equal to each other.
   */
  @Test
  void a7_d2cVenueIsTheSharedModelsDay() {
    for (int n = 0; n < 20; n++) {
      final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 70_000 + n);
      final ManualClock clock = new ManualClock(OPEN);
      final Venues.SimV2 v = venues(seed, clock);
      v.d2c().startAutoFlow();
      run(v, clock, spec.sessionNanos() + 400 * SECOND, 3600 * SECOND);

      final MarketModel twin = SessionSeeds.session(spec, seed);
      assertEquals(twin.rfqs().size(), v.d2c().rfqCount());
      final Map<RfqOutcome, Integer> expected = new EnumMap<>(RfqOutcome.class);
      // trades are recorded when the client decides, which is not arrival order
      final Map<Long, TradeRecord> trades = new java.util.HashMap<>();
      for (final TradeRecord trade : v.d2c().trades()) {
        trades.put(trade.rfqId(), trade);
      }
      int t = 0;
      for (final SimRfq r : twin.rfqs()) {
        final RfqDecision d = twin.decide(r, Double.NaN, 0L);
        expected.merge(RfqOutcome.valueOf(d.outcome().name()), 1, Integer::sum);
        if (!d.traded()) {
          continue;
        }
        final TradeRecord trade = trades.get(r.number() + 1L);
        t++;
        assertEquals(r.number() + 1, trade.rfqId());
        assertEquals(SimV2Session.mantissa(d.price()), trade.price());
        assertEquals(r.clientBuys(), trade.clientBuys());
        assertEquals((long) r.quantityUnits(), trade.quantity());
        assertEquals(r.informed(), trade.informed());
        assertEquals(v.session().instrument(r.instrument()).instrumentId(), trade.instrumentId());
        // marked at the unrounded latent mid when the client decided, and five minutes later. With
        // no dealer session logged on, the client decides when the last competitor has answered.
        long last = 0;
        for (int c = 0; c < r.competitors(); c++) {
          last = r.answers(c) ? Math.max(last, r.delayNanos(c)) : last;
        }
        final long decided = r.arrivalNanos() + last;
        assertEquals(SimV2Session.mantissa(twin.latentMid(r.instrument(), decided)), trade.midAtTrade());
        assertEquals(SimV2Session.mantissa(twin.latentMid(r.instrument(), decided + 300 * SECOND)), trade.midAtHorizon());
      }
      assertEquals(t, trades.size());
      assertEquals(expected, v.d2c().outcomes());
      v.d2c().close();
    }
  }

  /** What a run produced, without timestamps: every D2C trade and outcome and the trade count of every book. */
  private static List<String> content(final Venues.SimV2 v) {
    final List<String> out = new ArrayList<>();
    for (final TradeRecord t : v.d2c().trades()) {
      out.add(t.rfqId() + " " + t.winner() + " " + t.price() + " " + t.quantity() + " " + t.clientBuys());
    }
    out.add(new java.util.TreeMap<>(v.d2c().outcomes()).toString());
    out.add("rfqs " + v.d2c().rfqCount());
    for (final ClobSimulator c : v.treasuryBooks()) {
      out.add(c.compId() + " trades " + c.trades());
    }
    out.add("FUT trades " + v.futuresBook().trades());
    return out;
  }

  /**
   * A2, venue part: the same session polled every 10 ms and every 100 ms (the first 20 minutes),
   * without ever landing on an event time, sees the same RFQs, the same competitor quotes and
   * client decisions, and the same taker trades on every book. Only the time at which a poll
   * notices an event differs.
   */
  @Test
  void a2_venuesDoNotDependOnTheStep() {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 70_100);
    final List<List<String>> runs = new ArrayList<>();
    for (final long step : new long[] {10_000_000L, 100_000_000L, 700_000_000L}) {
      final ManualClock clock = new ManualClock(OPEN);
      final Venues.SimV2 v = venues(seed, clock);
      v.startFlow();
      final long end = v.session().nanoTimeOf(1200 * SECOND);
      while (clock.nanoTime() < end) {
        clock.advance(step);
        v.onTime();
      }
      // let the last RFQs of the stretch decide, on whole seconds for every step size
      for (int n = 0; n < 10; n++) {
        clock.advance(v.session().nanoTimeOf(1210 * SECOND + n * SECOND) - clock.nanoTime());
        v.onTime();
      }
      runs.add(content(v));
    }
    assertTrue(runs.get(0).size() > 8, runs.get(0).toString());
    assertEquals(runs.get(0), runs.get(1));
    assertEquals(runs.get(0), runs.get(2));
  }

  /**
   * The two Treasury venues show their shares of one provider book around the displaced mid, the
   * futures venue shows its own, each on its instrument's tick; the published reference price is
   * the latent mid rounded to the tick.
   */
  @Test
  void booksShowTheModelsLiquidityByVenueShare() {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 70_200);
    final ManualClock clock = new ManualClock(OPEN);
    final Venues.SimV2 v = venues(seed, clock);
    assertEquals(2, v.treasuryBooks().size());
    v.startFlow();
    run(v, clock, 3 * 3600 * SECOND, 3600 * SECOND);
    final MarketModel model = v.session().model();
    final long t = v.session().sessionNanos();
    final SimParams p = spec.params();
    for (final Instrument inst : v.session().instruments(SimInstrument.Kind.TREASURY)) {
      final int index = v.session().index(inst.securityId());
      final BookModel book = model.book(index);
      long consolidatedBid = 0;
      for (int venue = 0; venue < 2; venue++) {
        final ClobBook venueBook = v.treasuryBooks().get(venue).book(inst.instrumentId());
        final List<long[]> bids = venueBook.levels(true, 5);
        final List<long[]> asks = venueBook.levels(false, 5);
        assertFalse(bids.isEmpty() || asks.isEmpty());
        assertTrue(bids.get(0)[0] < asks.get(0)[0]);
        assertEquals(0, bids.get(0)[0] % inst.tick());
        // quotes sit around the displaced mid, not the latent mid
        assertTrue(bids.get(0)[0] <= SimV2Session.mantissa(book.displacedMid()));
        assertTrue(asks.get(0)[0] >= SimV2Session.mantissa(book.displacedMid()));
        for (final long[] level : bids) {
          int l = 0;
          while (l < 4 && SimV2Session.mantissa(book.levelPrice(true, l)) != level[0]) {
            l++;
          }
          final long share = (long) Math.floor(p.treasuryVenueShare(venue) * book.displayedUnits(true, l) / 1e6) * 1_000_000L;
          assertEquals(share, level[1], inst.securityId() + " venue " + venue + " level " + l);
          consolidatedBid += level[1];
        }
      }
      // consolidated depth is the model's, less at most one lot per level and venue lost to rounding
      double modelBid = 0;
      for (int l = 0; l < 5; l++) {
        modelBid += book.displayedUnits(true, l);
      }
      assertTrue(consolidatedBid <= modelBid && consolidatedBid >= modelBid - 10e6, consolidatedBid + " of " + modelBid);
      assertEquals(SimV2Session.mantissa(model.referencePrice(index, t)), v.d2c().mid(inst.securityId()));
      assertEquals(v.session().referencePrice(index), v.d2c().mid(inst.securityId()));
      assertEquals(0, v.d2c().mid(inst.securityId()) % inst.tick());
      assertNotEquals(v.session().latentMid(index), v.session().referencePrice(index));
    }
    final Instrument ty = v.session().instruments(SimInstrument.Kind.FUTURE).get(0);
    assertEquals("TYZ6", ty.securityId());
    final List<long[]> tyBids = v.futuresBook().book(ty.instrumentId()).levels(true, 5);
    assertEquals(15_625_000L, ty.tick());
    assertEquals(0, tyBids.get(0)[0] % ty.tick());
    assertTrue(tyBids.get(0)[1] > 100 && tyBids.get(0)[1] <= 1125);
    // other takers traded on every venue
    assertTrue(v.treasuryBooks().get(0).trades() > 100 && v.treasuryBooks().get(1).trades() > 100);
    assertTrue(v.futuresBook().trades() > 1000);
    assertEquals(-1, v.session().index("XS0000000000"));
    assertEquals(11, v.session().rfqInstruments().size());
    assertEquals(SimV2Session.SYNTHETIC_ID_BASE + 105, v.session().instrument(spec.indexOf("SOFR-3Y")).instrumentId());
    assertEquals("USD-SOFR-10Y", v.session().instrument(spec.indexOf("SOFR-10Y")).securityId());
  }

  /**
   * The desk's marketable order on one venue: it takes that venue's share of the provider size,
   * the depth is gone from the other venue too, the book and the level factor move by the model's
   * law, and the providers requote around the new displaced mid. Other takers' trades move
   * nothing permanently.
   */
  @Test
  void deskOrderOnOneVenueMovesTheOneMarket() {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 70_300);
    final ManualClock clock = new ManualClock(OPEN);
    final Venues.SimV2 v = venues(seed, clock);
    v.startFlow();
    run(v, clock, 3 * 3600 * SECOND, 3600 * SECOND);
    final MarketModel model = v.session().model();
    assertEquals(0.0, model.levelOffset(v.session().sessionNanos()), 0.0);
    final ClobSimulator first = v.treasuryBooks().get(0);
    final ClobSimulator second = v.treasuryBooks().get(1);

    // the Treasury whose touch will still be at the same price after the order's impact, so that
    // the depth taken can be followed at that price (a price that leaves the five levels is re-keyed)
    int note = -1;
    for (int k = 0; k < spec.treasuryCount() && note < 0; k++) {
      final int i = spec.treasury(k);
      final Instrument candidate = v.session().instrument(i);
      final long size = first.book(candidate.instrumentId()).levels(false, 1).get(0)[1];
      final double tickSize = spec.instrument(i).tick();
      final double before = model.book(i).displacedMid();
      final double after = before + spec.peakImpact(spec.bookOf(i), (double) size);
      if (size >= 5_000_000L && Math.floor(after / tickSize) == Math.floor(before / tickSize)) {
        note = i;
      }
    }
    assertTrue(note >= 0, "no Treasury keeps its touch through the order on this seed");
    final Instrument inst = v.session().instrument(note);
    final BookModel book = model.book(note);
    final long ask = first.book(inst.instrumentId()).levels(false, 1).get(0)[0];
    final long shown = first.book(inst.instrumentId()).levels(false, 1).get(0)[1];
    final long otherBefore = second.book(inst.instrumentId()).levels(false, 1).get(0)[1];
    final double modelBefore = book.displayedUnits(false, 0);
    final double displacedBefore = book.displacedMid();
    final double latentBefore = model.latentMid(note, v.session().sessionNanos());
    final long tradesBefore = first.trades();

    // a buy for everything shown at the touch on the first venue
    assertTrue(first.submitInternal("DESK", inst.instrumentId(), true, ask, shown, shown) > 0);
    assertTrue(first.trades() > tradesBefore);
    final double impact = spec.peakImpact(spec.bookOf(note), (double) shown);
    assertEquals(impact, book.displacedMid() - displacedBefore, 1e-12);
    final double permanent = spec.params().get(SimParams.Scalar.IMPACT_PERMANENT_SHARE) * impact;
    assertEquals(permanent, model.latentMid(note, v.session().sessionNanos()) - latentBefore, 1e-12);
    assertTrue(model.levelOffset(v.session().sessionNanos()) < 0.0);
    assertEquals((double) shown, book.ownDayVolume(), 0.0);
    // every instrument's reference moves through the level loading: the 10-year swap rate falls
    assertTrue(model.latentMid(spec.indexOf("SOFR-10Y"), v.session().sessionNanos())
        < SessionSeeds.session(spec, seed).latentMid(spec.indexOf("SOFR-10Y"), v.session().sessionNanos()));

    // the depth is gone from the model's book, so the first venue has nothing left at that price
    // and the second, which was not touched, shows its share of what is left, not of what was
    assertEquals(SimV2Session.mantissa(book.bestAsk()), ask);
    assertEquals(modelBefore - shown, book.displayedUnits(false, 0), 1e-6);
    long firstAtAsk = 0;
    for (final long[] level : first.book(inst.instrumentId()).levels(false, 5)) {
      firstAtAsk += level[0] == ask ? level[1] : 0;
    }
    assertEquals((long) Math.floor(0.6 * book.displayedUnits(false, 0) / 1e6) * 1_000_000L, firstAtAsk);
    assertTrue(firstAtAsk < shown);
    assertTrue((long) Math.floor(0.4 * book.displayedUnits(false, 0) / 1e6) * 1_000_000L < otherBefore);

    // a resting order of the desk does not move the market
    final double offset = model.levelOffset(v.session().sessionNanos());
    final long bid = first.book(inst.instrumentId()).levels(true, 1).get(0)[0];
    final long resting = first.submitInternal("DESK", inst.instrumentId(), true, bid - 2 * inst.tick(), 5_000_000L, 5_000_000L);
    assertTrue(resting > 0);
    assertEquals(offset, model.levelOffset(v.session().sessionNanos()), 0.0);
    // other takers keep trading and never move the level factor
    run(v, clock, v.session().sessionNanos() + 600 * SECOND, 3600 * SECOND);
    assertEquals(offset, model.levelOffset(v.session().sessionNanos()), 0.0);
    assertTrue(first.trades() > tradesBefore + 10);
    first.cancelInternal(resting);
    for (final ClobSimulator c : v.treasuryBooks()) {
      c.close();
    }
  }

  /**
   * A7 with a desk: an engine session quotes the first RFQs over FIX one tick from the published
   * reference price. Each is decided as the shared model decides it for that quote: the desk wins
   * when it is the best price inside the client's reserve and the client does not trade away.
   */
  @Test
  void a7_deskQuoteOverFixIsDecidedByTheSharedModel() {
    final long seed = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 70_400);
    final ManualClock clock = new ManualClock(OPEN);
    final MarketModel model = SessionSeeds.session(spec, seed);
    final MarketModel twin = SessionSeeds.session(spec, seed);
    final SimV2Session session =
        new SimV2Session(model, SimV2Session.instruments(spec, RefData.loadDefault(), Venues.TRADE_DATE), clock);
    final D2cRfqSimulator sim = new D2cRfqSimulator(session, List.of("RALGO"), clock);
    final List<String> inbound = new ArrayList<>();
    int won = 0;
    try (VenueHarness h = new VenueHarness(sim, clock);
        EngineSession e = h.connect("RALGO", (FixSession s, FixParser m, boolean d) -> inbound.add(m.toString()))) {
      sim.startAutoFlow();
      int quoted = 0;
      for (int n = 1; quoted < 12; n++) {
        final SimRfq r = model.rfqs().get(n);
        // only RFQs with six clear seconds either side, so one response window is open at a time
        if (r.arrivalNanos() - model.rfqs().get(n - 1).arrivalNanos() < 6 * SECOND
            || model.rfqs().get(n + 1).arrivalNanos() - r.arrivalNanos() < 6 * SECOND) {
          continue;
        }
        quoted++;
        final Instrument inst = session.instrument(r.instrument());
        h.advance(Math.max(0, session.nanoTimeOf(r.arrivalNanos()) - clock.nanoTime()));
        e.quiesce();
        final String id = "|131=" + (n + 1) + "|";
        final String request = inbound.stream().filter(m -> m.contains("|35=R|") && m.contains(id)).findFirst().orElseThrow();
        assertTrue(request.contains("|48=" + inst.securityId() + "|"), request);
        assertTrue(request.contains("|54=" + (r.clientBuys() ? "1" : "2") + "|"), request);
        assertTrue(request.contains("|38=" + (long) r.quantityUnits() + "|"), request);
        assertTrue(request.contains("|9501=" + r.dealersAsked() + "|"), request);
        assertTrue(request.contains("|448=" + (2001 + r.tier()) + "|"), request);

        final long reference = sim.mid(inst.securityId());
        final long price = r.clientBuys() ? reference + inst.tick() : reference - inst.tick();
        final long bid = r.clientBuys() ? price - 20 * inst.tick() : price;
        final long offer = r.clientBuys() ? price : price + 20 * inst.tick();
        e.session().begin(MsgType.QUOTE).put(FixTags.QUOTE_REQ_ID, n + 1).put(FixTags.QUOTE_ID, 100 + n)
            .putText(55, inst.securityId()).putText(48, inst.securityId()).putChar(22, (byte) '4')
            .putDecimal(FixTags.BID_PX, bid, 9).putDecimal(FixTags.OFFER_PX, offer, 9)
            .put(FixTags.BID_SIZE, (long) r.quantityUnits()).put(FixTags.OFFER_SIZE, (long) r.quantityUnits());
        e.session().send();
        e.quiesce();
        // through the response window, one event at a time
        final long windowEnd = clock.nanoTime() + 5_100_000_000L;
        while (clock.nanoTime() < windowEnd) {
          h.advance(Math.min(windowEnd, Math.max(clock.nanoTime() + 1, sim.nextEventNanos())) - clock.nanoTime());
          e.quiesce();
        }
        final RfqDecision expected = twin.decide(twin.rfqs().get(n), price / 1e9, 0L);
        final String response = inbound.stream().filter(m -> m.contains("|35=AJ|") && m.contains(id)).findFirst().orElseThrow();
        if (expected.deskWon()) {
          won++;
          assertTrue(response.contains("|694=1|") && response.contains("|44=" + Px.text(price) + "|"), response);
          assertTrue(inbound.stream().anyMatch(m -> m.contains("|35=8|") && m.contains(id) && m.contains("|150=F|")));
        } else {
          assertFalse(response.contains("|694=1|"), response);
          final String type =
              switch (expected.outcome()) {
                case DONE -> "|694=5|";
                case DONE_AWAY -> "|9503=DA|";
                case PASSED -> "|694=6|";
                case EXPIRED -> "|694=3|";
              };
          assertTrue(response.contains(type), response + " for " + expected);
        }
      }
      assertEquals(0, e.session().stats().sequenceErrors());
    }
    assertTrue(won >= 3, "desk wins " + won);
    assertEquals(won, sim.trades().stream().filter(t -> "RALGO".equals(t.winner())).count());
  }

  @Test
  void constructionRules() {
    final ManualClock clock = new ManualClock(OPEN);
    final MarketModel model = new MarketModel(spec, 5L, DayType.NONE);
    assertThrows(IllegalArgumentException.class, () -> Venues.simV2(model, clock, List.of(new InProcessFeedChannel())));
    final List<Instrument> instruments = SimV2Session.instruments(spec, io.ratesalgo.refdata.RefData.loadDefault(), Venues.TRADE_DATE);
    assertEquals(12, instruments.size());
    assertThrows(IllegalArgumentException.class, () -> new SimV2Session(model, instruments.subList(0, 3), clock));
    final SimV2Session session = new SimV2Session(model, instruments, clock);
    final ClobConfig config = ClobConfig.preset("sim_v2");
    assertEquals(0L, config.workupNanos());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClobSimulator("X", config, session, session.instruments(SimInstrument.Kind.SWAP), 1.0, Venues.ENGINES,
            new InProcessFeedChannel(), clock));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ClobSimulator("X", config, session, session.instruments(SimInstrument.Kind.FUTURE), 0.0, Venues.ENGINES,
            new InProcessFeedChannel(), clock));
    // a version 1 order book has no model liquidity to start
    final ClobSimulator v1 = (ClobSimulator) Venues.create("clob", clock, 1L, new InProcessFeedChannel());
    assertThrows(IllegalStateException.class, v1::startLiquidity);
    assertEquals(session.nanoTimeOf(5L), clock.nanoTime() + 5L);
    assertEquals(model, session.model());
  }
}
