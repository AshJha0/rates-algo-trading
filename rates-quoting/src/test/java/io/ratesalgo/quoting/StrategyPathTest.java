package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static io.ratesalgo.quoting.TestMarket.SW10;
import static io.ratesalgo.quoting.TestMarket.UST10;
import static io.ratesalgo.quoting.TestMarket.UST30;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.messages.sbe.Side;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's strategy path (Round 5): each capability on its own and in the preset, and the
 * default path left as it was.
 */
class StrategyPathTest {

  private static final long SECOND = 1_000_000_000L;

  private long rfqId;

  private static TestMarket market(final StrategyConfig sc) {
    return market(new QuotingConfig().requoteThrottleNanos(0L), sc);
  }

  private static TestMarket market(final QuotingConfig config, final StrategyConfig sc) {
    return new TestMarket(
        TestMarket.standardLimits().defaultMaxPosition(100_000 * FACE_1M).bucketPv01(1e12).netPv01(1e12).grossPv01(1e12),
        config.strategy(sc),
        Hedger.config());
  }

  /** Asks for a quote; {@code clientSide} is the client's. */
  private RfqOutcome ask(final TestMarket m, final int client, final int id, final Side clientSide, final long faceMm) {
    m.desk.quoting().onQuoteRequest(7, ++rfqId, client, id, clientSide, faceMm * FACE_1M, 4, 0L, rfqId);
    return m.desk.quoting().outcome();
  }

  private static void hold(final TestMarket m, final int id, final long faceMm) {
    m.desk.risk().onFill(id, faceMm * FACE_1M, m.fair.fairMid(id));
  }

  // ---------------------------------------------------------------- default path

  @Test
  void withEverySwitchOffTheStrategyPathIsNotUsed() {
    final TestMarket m = market(new StrategyConfig());
    final RfqOutcome o = ask(m, 1, SW10, Side.Buy, 10);
    assertEquals(RfqDecision.FIRM, o.decision());
    assertTrue(Double.isNaN(o.breakEvenBp()));
    assertTrue(Double.isNaN(o.predictedCoverBp()));
    assertTrue(Double.isNaN(o.distanceTicks()));
    assertNull(m.desk.quoting().cover());
    assertNull(m.desk.quoting().markouts());
    assertFalse(new StrategyConfig().pricingActive());
    assertTrue(StrategyConfig.allOn().pricingActive());
  }

  @Test
  void factorHedgingAloneChangesTheHedgerNotTheQuote() {
    final TestMarket plain = market(new StrategyConfig());
    final TestMarket factor = market(new StrategyConfig().factorHedging(true));
    hold(plain, UST30, 40);
    hold(factor, UST30, 40);
    final RfqOutcome a = ask(plain, 1, UST10, Side.Sell, 10);
    final long priceA = a.price();
    final RfqOutcome b = ask(factor, 1, UST10, Side.Sell, 10);
    assertEquals(priceA, b.price());
    // the factor planner hedges level with one instrument where the key-rate planner spreads it
    assertTrue(factor.desk.hedger(CurrencyCode.USD).onTimer(factor.clock.epochNanos()) > 0);
  }

  // ---------------------------------------------------------------- 1. break-even, cover-relative

  @Test
  void coverPricingQuotesAboveBreakEvenWithNoFixedClamp() {
    final QuotingConfig narrow = new QuotingConfig().requoteThrottleNanos(0L).minHalfSpreadBp(0.02).maxHalfSpreadBp(0.08);
    final TestMarket m = market(narrow, new StrategyConfig().coverPricing(true));
    final RfqOutcome o = ask(m, 1, SW10, Side.Buy, 10);
    assertEquals(RfqDecision.FIRM, o.decision());
    assertTrue(o.distanceBp() >= o.breakEvenBp());
    assertTrue(o.distanceBp() > 0.08, "the 0.02 to 0.08 bp clamp does not bind: " + o.distanceBp());
    assertTrue(o.distanceBp() < 3.0 * o.predictedCoverBp() + o.breakEvenBp(), "bounded by the cover, not the cap");
    assertEquals(o.distanceBp() - o.breakEvenBp(), o.halfSpreadBp(), 1e-12);
    assertTrue(o.winProbability() > 0.0 && o.winProbability() < 1.0);
    assertNotNull(m.desk.quoting().cover());
  }

  @Test
  void whenCostRisesTheQuoteWidensWhereTheDefaultPasses() {
    final QuotingConfig dear = new QuotingConfig().requoteThrottleNanos(0L).winnersCurseBp(7.0);
    final TestMarket plain = market(dear, new StrategyConfig());
    assertEquals(PassReason.NEGATIVE_EDGE, ask(plain, 1, SW10, Side.Buy, 10).reason());
    final TestMarket cover =
        market(new QuotingConfig().requoteThrottleNanos(0L).winnersCurseBp(7.0), new StrategyConfig().coverPricing(true));
    final RfqOutcome o = ask(cover, 1, SW10, Side.Buy, 10);
    assertEquals(RfqDecision.FIRM, o.decision());
    assertTrue(o.breakEvenBp() > 10.0);
    assertTrue(o.distanceBp() >= o.breakEvenBp());
  }

  @Test
  void winsLossesAndCoversTeachTheCoverModel() {
    final TestMarket m = market(new StrategyConfig().coverPricing(true));
    final QuotingEngine e = m.desk.quoting();
    final CoverModel cover = e.cover();
    RfqOutcome o = ask(m, 1, SW10, Side.Buy, 10);
    final int cell = CoverModel.cell(m.directory.byId(SW10), 10.0 / 50.0, 4);
    // the client buys, so we sell: a cover worse for the client is a higher rate than ours
    final long qpb = Decimal9.round(m.desk.risk().quotePerBp(SW10));
    e.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), o.price() + qpb / 10);
    assertEquals(1L, cover.observations(cell));
    final double exact = cover.mu(cell);
    o = ask(m, 1, SW10, Side.Buy, 10);
    e.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), 0L);
    assertEquals(2L, cover.observations(cell), "a win without a cover: censored above");
    o = ask(m, 1, SW10, Side.Buy, 10);
    e.onQuoteResponse(o.quoteId(), QuoteStatus.TradedAway, 0L, 0L, 0L);
    assertEquals(3L, cover.observations(cell), "a loss without a cover: censored below");
    o = ask(m, 1, SW10, Side.Buy, 10);
    e.onQuoteResponse(o.quoteId(), QuoteStatus.TradedAway, 0L, 0L, o.price() - qpb / 20);
    assertEquals(4L, cover.observations(cell), "a loss to a better cover: exact");
    o = ask(m, 1, SW10, Side.Buy, 10);
    e.onQuoteResponse(o.quoteId(), QuoteStatus.TradedAway, 0L, 0L, o.price() + qpb / 20);
    assertEquals(4L, cover.observations(cell), "a losing cover worse than ours is inconsistent: ignored");
    o = ask(m, 1, SW10, Side.Buy, 10);
    e.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), o.price() - qpb / 20);
    assertEquals(4L, cover.observations(cell), "a winning cover better than ours is inconsistent: ignored");
    assertTrue(Double.isFinite(exact));
  }

  // ---------------------------------------------------------------- 2. reservation price

  @Test
  void withNoInventoryTheQuoteIsSymmetric() {
    final TestMarket m = market(new StrategyConfig().reservationPrice(true));
    final RfqOutcome bid = ask(m, 1, UST10, Side.Sell, 10);
    final double dBid = bid.distanceBp();
    assertEquals(0.0, bid.skewBp(), 0.0);
    final RfqOutcome offer = ask(m, 1, UST10, Side.Buy, 10);
    assertEquals(dBid, offer.distanceBp(), 1e-6);
    assertEquals(0.0, offer.axeBp(), 0.0);
  }

  @Test
  void withNoRiskAversionThereIsNoSkew() {
    final TestMarket m = market(new StrategyConfig().reservationPrice(true).riskAversionPerUsd(0.0));
    hold(m, UST10, 300);
    final RfqOutcome bid = ask(m, 1, UST10, Side.Sell, 10);
    final double dBid = bid.distanceBp();
    assertEquals(0.0, bid.skewBp(), 0.0);
    assertEquals(0.0, bid.sizeTermBp(), 0.0);
    assertEquals(dBid, ask(m, 1, UST10, Side.Buy, 10).distanceBp(), 1e-6);
  }

  @Test
  void withLargeInventoryTheSkewStopsAtTheCostOfHedging() {
    final TestMarket m = market(new StrategyConfig().reservationPrice(true));
    hold(m, UST10, 2_000);
    final RfqOutcome bid = ask(m, 1, UST10, Side.Sell, 10);
    final double skew = bid.skewBp();
    final double beBid = bid.breakEvenBp();
    final RfqOutcome offer = ask(m, 1, UST10, Side.Buy, 10);
    assertTrue(skew > 0.0, "long: the reservation price is below fair");
    assertEquals(skew, offer.skewBp(), 1e-12);
    // both sides at the cap: the bid pays the hedge cost, the offer saves it
    assertEquals(2.0 * skew, beBid - offer.breakEvenBp(), 1e-9);
    hold(m, UST10, 2_000);
    assertEquals(skew, ask(m, 1, UST10, Side.Sell, 10).skewBp(), 1e-12, "doubling the inventory adds nothing");
    final TestMarket legacy = market(new StrategyConfig());
    hold(legacy, UST10, 2_000);
    assertEquals(legacy.desk.quoting().config().maxSkewBp(), ask(legacy, 1, UST10, Side.Sell, 10).skewBp(), 1e-12,
        "the default path stops at its configured cap instead");
  }

  @Test
  void workingHedgesCountAsInventoryAndTheAxeIsNotAddedTwice() {
    final TestMarket m = market(new StrategyConfig().reservationPrice(true).riskAversionPerUsd(1e-9));
    hold(m, UST10, 200);
    final double before = ask(m, 1, UST10, Side.Sell, 10).skewBp();
    assertTrue(m.desk.hedger(CurrencyCode.USD).onTimer(m.clock.epochNanos()) > 0, "hedges go out, unfilled");
    final RfqOutcome after = ask(m, 1, UST10, Side.Sell, 10);
    assertTrue(after.skewBp() < 0.6 * before, "half the plan is working: " + before + " then " + after.skewBp());
    final RfqOutcome toward = ask(m, 1, UST10, Side.Buy, 10);
    assertEquals(0.0, toward.axeBp(), 0.0, "no axe on this path: the reservation price already leans");
    final TestMarket legacy = market(new StrategyConfig());
    hold(legacy, UST10, 200);
    legacy.desk.hedger(CurrencyCode.USD).onTimer(legacy.clock.epochNanos());
    assertTrue(ask(legacy, 1, UST10, Side.Buy, 10).axeBp() > 0.0, "the default path adds the hedger's axe");
  }

  @Test
  void theSizeTermGrowsWithDv01InsteadOfSaturating() {
    final TestMarket m = market(new StrategyConfig().reservationPrice(true));
    double last = 0.0;
    for (final long mm : new long[] {1, 5, 10, 25, 100, 400}) {
      final double size = ask(m, 1, UST10, Side.Sell, mm).sizeTermBp();
      assertTrue(size > last, mm + "mm: " + size + " after " + last);
      last = size;
    }
  }

  // ---------------------------------------------------------------- 3. factor covariance, tick hedge cost

  @Test
  void theFittedCovarianceSkewsTenYearsForThirtyYearRisk() {
    final TestMarket assumed = market(new StrategyConfig().reservationPrice(true).riskAversionPerUsd(1e-9));
    final TestMarket fitted =
        market(new StrategyConfig().reservationPrice(true).riskAversionPerUsd(1e-9).factorCovariance(true));
    hold(assumed, UST30, 100);
    hold(fitted, UST30, 100);
    final double a = ask(assumed, 1, UST10, Side.Sell, 10).skewBp();
    final double f = ask(fitted, 1, UST10, Side.Sell, 10).skewBp();
    assertTrue(f > 2.0 * a, "fitted 10s30s correlation 0.93 against 0.30: " + f + " against " + a);
  }

  @Test
  void theTickCostModelPricesTheHedgeInTicks() {
    final TestMarket table = market(new StrategyConfig());
    final TestMarket ticks = market(new StrategyConfig().tickHedgeCost(true));
    final double inst = table.desk.quoting().hedgeCostBp(table.directory.byId(UST10), 10_000.0);
    final double tick = ticks.desk.quoting().hedgeCostBp(ticks.directory.byId(UST10), 10_000.0);
    assertTrue(tick > 0.0 && tick != inst, "table " + inst + " ticks " + tick);
  }

  // ---------------------------------------------------------------- 5. markouts

  @Test
  void fillsAreMarkedOutAtEveryHorizonAndFeedTheAdverseCost(@TempDir final Path dir) throws IOException {
    final Path file = dir.resolve("markouts.txt");
    final TestMarket m = market(new StrategyConfig().markoutStore(true).markoutFile(file));
    final QuotingEngine e = m.desk.quoting();
    final RfqOutcome o = ask(m, 1, UST10, Side.Sell, 10);
    final double quietBreakEven = o.breakEvenBp();
    e.onQuoteResponse(o.quoteId(), QuoteStatus.Done, o.price(), o.quantity(), 0L);
    // the client sold to us and the price then fell 2 bp: informed
    final int i = m.directory.indexOf(UST10);
    m.level[i] -= 2.0 * m.desk.risk().quotePerBp(UST10);
    for (int h = 0; h < MarkoutStore.H; h++) {
      m.clock.advance(MarkoutStore.horizonNanos(h) - (h == 0 ? 0L : MarkoutStore.horizonNanos(h - 1)));
      m.refreshAll(m.clock.epochNanos());
      e.poll(m.clock.epochNanos());
      assertEquals(1L, e.markouts().count(1, false, h), "horizon " + h);
      assertEquals(2.0, e.markouts().mean(1, false, h), 1e-6);
    }
    final double again = ask(m, 1, UST10, Side.Sell, 10).breakEvenBp();
    final double other = ask(m, 2, UST10, Side.Sell, 10).breakEvenBp();
    // client 1 (tier A) now carries its own 2 bp markout, shrunk with weight 1/21; client 2 keeps
    // the tier B prior; the inventory term is the same for both
    assertTrue(other < again, "another client keeps its tier's prior: " + other + " against " + again);
    assertTrue(again > quietBreakEven, quietBreakEven + " then " + again);
    assertTrue(m.desk.saveMarkouts());
    assertTrue(Files.exists(file));
    final TestMarket next = market(new StrategyConfig().markoutStore(true).markoutFile(file));
    assertEquals(1L, next.desk.quoting().markouts().count(1, false, 4), "the next session starts where this one ended");
    assertFalse(market(new StrategyConfig().markoutStore(true)).desk.saveMarkouts(), "no file configured");
    assertFalse(market(new StrategyConfig()).desk.saveMarkouts(), "store off");
  }

  // ---------------------------------------------------------------- 6. ticks and time to live

  @Test
  void tickRoundingPutsThePriceOnTheVenueTickInOurFavour() {
    final TestMarket plain = market(new StrategyConfig());
    final TestMarket ticks = market(new StrategyConfig().tickRounding(true));
    for (final Side clientSide : new Side[] {Side.Buy, Side.Sell}) {
      for (final int id : new int[] {UST10, SW10}) {
        final long raw = ask(plain, 1, id, clientSide, 10).price();
        final RfqOutcome o = ask(ticks, 1, id, clientSide, 10);
        final long tick = id == SW10 ? VenueTickTable.SWAP_RATE_TICK : VenueTickTable.TREASURY_128THS;
        final boolean weBuy = clientSide == Side.Sell;
        assertEquals(VenueTickTable.roundForDealer(raw, tick, weBuy), o.price(), id + " " + clientSide);
        assertEquals(o.distanceBp() * plain.desk.risk().quotePerBp(id) / Decimal9.toDouble(tick), o.distanceTicks(), 1e-6);
      }
    }
  }

  @Test
  void timeToLiveShortensAsVolatilityRises() {
    final TestMarket m = market(new StrategyConfig().volatilityTtl(true));
    final QuotingEngine e = m.desk.quoting();
    long now = m.clock.epochNanos();
    assertEquals(now + 5 * SECOND, ask(m, 1, SW10, Side.Buy, 10).validUntilNanos(), "at the baseline: unchanged");
    e.realisedVolatility(CurrencyCode.USD, 12.0);
    now = m.clock.epochNanos();
    assertEquals(now + 1_250_000_000L, ask(m, 1, SW10, Side.Buy, 10).validUntilNanos(), "twice the volatility: a quarter");
    e.realisedVolatility(CurrencyCode.USD, 60.0);
    assertEquals(now + SECOND, ask(m, 1, SW10, Side.Buy, 10).validUntilNanos(), "floored at the minimum");
    e.realisedVolatility(CurrencyCode.USD, 3.0);
    assertEquals(now + 5 * SECOND, ask(m, 1, SW10, Side.Buy, 10).validUntilNanos(), "never longer than configured");
  }

  // ---------------------------------------------------------------- wiring and validation

  @Test
  void theEngineRefusesASwitchWithoutItsPart() {
    final TestMarket m = market(new StrategyConfig());
    for (final StrategyConfig sc : new StrategyConfig[] {new StrategyConfig().coverPricing(true), new StrategyConfig().markoutStore(true)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new QuotingEngine(
                  m.directory, m.desk.risk(), m.desk.fairValue(), m.desk.quoting().spread(), m.desk.tiers(),
                  new ToxicityTable(16, 0.1, 5.0), AxeSource.NONE, null, null, KeyRateCovariance.assumedDefault(),
                  m.desk.health(), m.gateway, m.clock, new QuotingConfig().strategy(sc)));
    }
  }

  @Test
  void theStrategyConfigValidatesItsValues() {
    final StrategyConfig sc = new StrategyConfig();
    assertThrows(IllegalArgumentException.class, () -> sc.riskAversionPerUsd(-1.0));
    assertThrows(IllegalArgumentException.class, () -> sc.hedgeKappaPerUsd(0.0));
    assertThrows(IllegalArgumentException.class, () -> sc.hedgeHalfSpreadTicks(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> sc.minTtlNanos(0L));
    assertThrows(IllegalArgumentException.class, () -> sc.adverseHorizonNanos(2L));
    assertThrows(IllegalArgumentException.class, () -> sc.markoutLearning(-1.0, 10.0, 1));
    assertThrows(IllegalArgumentException.class, () -> sc.markoutLearning(1.0, 0.5, 1));
    assertThrows(IllegalArgumentException.class, () -> sc.markoutLearning(1.0, 10.0, 0));
    sc.adverseHorizonNanos(60_000_000_000L).markoutLearning(5.0, 100.0, 8).hedgeAvailability(null).ticks(new VenueTickTable());
    assertNotNull(sc.hedgeAvailability());
    assertEquals(0L, sc.ticks().tick(TestMarketAccess.ust10()));
    final StrategyConfig all = StrategyConfig.allOn();
    assertTrue(all.coverPricing() && all.reservationPrice() && all.factorCovariance() && all.factorHedging()
        && all.tickHedgeCost() && all.markoutStore() && all.tickRounding() && all.volatilityTtl());
    assertEquals(4e-7, all.riskAversionPerUsd());
    assertEquals(1e-5, all.hedgeKappaPerUsd());
    assertEquals(0.5, all.hedgeHalfSpreadTicks());
    assertNull(all.markoutFile());
    assertNotNull(all.factorModelOrCalibrated());
    assertNotNull(all.tierPriorsOrCalibrated());
    final CoverModel mine = CoverModel.calibrated();
    assertEquals(mine, new StrategyConfig().coverModel(mine).coverModelOrCalibrated());
    assertNotNull(new StrategyConfig().factorModel(null).tierPriors(null).coverModelOrCalibrated());
    assertEquals(sc, new QuotingConfig().strategy(sc).strategy());
    assertNotNull(new QuotingConfig().strategy(null).strategy());
  }

  /** The test market's 10-year note, for checks that need an instrument and no market. */
  private static final class TestMarketAccess {
    static io.ratesalgo.api.Instrument ust10() {
      return io.ratesalgo.api.Instrument.bond(UST10, "UST10", CurrencyCode.USD, 10.0, 25 * FACE_1M);
    }
  }
}
