package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.messages.sbe.VenueExclusion;
import io.ratesalgo.messages.sbe.VenueKind;
import io.ratesalgo.router.sim.SimConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ladder model on a hand-driven book (DECISIONS.md DR-R6-R01 to R04): the consolidated
 * sweep, its limits and its cost, own orders, minimum sizes, the risk term, time in force per
 * venue, throttles, outages, the scorecard, queue tracking and the learned cancellation rate.
 */
class LadderModelTest {

  private static final long MM = RouterPresets.ONE_MILLION;
  private static final long TICK = RouterPresets.TICK_64TH;
  private static final long BID = 98_500_000_000L;
  private static final double SCALE = 1e9;

  private final ManualClock clock = new ManualClock();
  private final StubVenues venues = new StubVenues(1, 2);
  private final List<CostTable> tables = new ArrayList<>();
  private final DecisionCodec codec = new DecisionCodec();
  private long execId = 1;

  static VenueParams venue(final int id, final double takerFee, final long minSize) {
    return new VenueParams(
        id, VenueKind.Clob, TICK, MM, minSize, takerFee, 0.0002, 250_000L, 0.2, 30_000L * MM,
        1.0, 5);
  }

  /** Ladder parameters with one slice, no message cost, and the given passive horizon. */
  static RouterParams params(final long passiveNanos) {
    final RouterParams g = RouterPresets.ladder();
    return new RouterParams(
        RouterPresets.LADDER_CONFIG_VERSION, g.sigmaDailyPoints(), g.tradingSecondsPerDay(),
        g.impactY(), g.impactAlpha(), g.adverseFactor(), g.latencyRiskFactor(), passiveNanos, 0L, 1,
        0L, g.sizeJitter(), g.intervalJitter(), 2, 2, 500_000_000L, 32, 0.75, 0.0,
        RouterParams.Volatility.NONE);
  }

  private SmartOrderRouter router(final RouterParams p, final ExecutionModel m, final VenueParams... vps) {
    return new SmartOrderRouter(
        p,
        vps,
        venues,
        venues,
        (templateId, version, buffer, offset, length) -> {
          if (codec.onFrame(templateId, buffer, offset, length)) {
            final CostTable copy = new CostTable();
            copy.copyFrom(codec.decoded());
            tables.add(copy);
          }
        },
        clock,
        RandomSource.seeded(1L),
        4,
        64,
        100L,
        RouterTimeouts.DEFAULT,
        m);
  }

  private SmartOrderRouter standard(final ExecutionModel m) {
    return router(params(0L), m, venue(1, 0.0005, MM), venue(2, 0.0002, MM));
  }

  @BeforeEach
  void quotes() {
    for (int i = 0; i < 2; i++) {
      venues.bid[i] = BID;
      venues.ask[i] = BID + TICK;
      venues.bidDepth[i] = 10 * MM;
      venues.askDepth[i] = 10 * MM;
      venues.rate[i] = 0.0;
    }
    venues.askDepth[1] = 5 * MM;
    venues.level(1, Side.Sell, 1, BID + 2 * TICK, 20 * MM);
    venues.level(2, Side.Sell, 1, BID + 2 * TICK, 20 * MM);
    venues.level(1, Side.Sell, 2, BID + 3 * TICK, 50 * MM);
    venues.level(2, Side.Sell, 2, BID + 3 * TICK, 50 * MM);
    venues.level(1, Side.Sell, 3, BID + 4 * TICK, 50 * MM);
    venues.level(1, Side.Buy, 1, BID - TICK, 20 * MM);
    venues.level(2, Side.Buy, 1, BID - TICK, 20 * MM);
  }

  private void report(
      final SmartOrderRouter r, final StubVenues.Order o, final OrdStatus st, final long last,
      final long cum) {
    r.onExecutionReport(o.venue(), o.id(), execId++, st, o.price(), last, o.qty() - cum, cum, 0L);
  }

  private static double latencyCost() {
    final RouterParams p = params(0L);
    return p.latencyRiskFactor() * p.sigmaPerSqrtSecond() * Math.sqrt(250_000L / SCALE);
  }

  @Test
  void sweepTakesTheBestAllInPriceFirstAndLimitsEachChildAtItsLastLevel() {
    final SmartOrderRouter r = standard(ExecutionModel.defaults());
    r.submit(1, 10, Side.Buy, 30 * MM, 1.0, 0L);
    final CostTable t = tables.get(0);
    assertTrue(t.isLadder(), t.format());
    assertEquals(2, venues.orders.size(), t.format());
    // venue 2 is cheaper by its fee, so its touch goes first, then venue 1's touch, then venue
    // 2's second level before venue 1's
    final StubVenues.Order v1 = venues.orders.get(0);
    final StubVenues.Order v2 = venues.orders.get(1);
    assertEquals(1, v1.venue());
    assertEquals(10 * MM, v1.qty());
    assertEquals(BID + TICK, v1.price());
    assertEquals(TimeInForce.ImmediateOrCancel, v1.tif());
    assertEquals(2, v2.venue());
    assertEquals(20 * MM, v2.qty());
    assertEquals(BID + 2 * TICK, v2.price());
    assertEquals(BID + TICK + 2 * TICK, t.limitPrice(0), "the collar is a bound, 2 ticks");
    assertEquals(3, t.levelCount(0), "levels past the collar are not read");
    assertEquals(BID + 2 * TICK, t.childLimit(1));
    assertEquals(20 * MM, t.sweepQuantity(1));
  }

  @Test
  void expectedCostIsTheLadderWalkPlusPostTradeImpact() {
    final ExecutionModel m = ExecutionModel.defaults();
    final SmartOrderRouter r = standard(m);
    r.submit(1, 10, Side.Buy, 30 * MM, 1.0, 0L);
    final CostTable t = tables.get(0);
    final double mid = (BID + BID + TICK) * 0.5;
    final double lat = latencyCost();
    final double v2Touch = (BID + TICK - mid) / SCALE + 0.0002 + lat;
    final double v1Touch = (BID + TICK - mid) / SCALE + 0.0005 + lat;
    final double v2Next = (BID + 2 * TICK - mid) / SCALE + 0.0002 + lat;
    final double walk = (5 * v2Touch + 10 * v1Touch + 15 * v2Next) / 30.0;
    final RouterParams p = params(0L);
    final double impact =
        m.postTradeImpactShare() * p.impactY() * p.sigmaDailyPoints()
            * Math.sqrt(30.0 * MM / (2 * 30_000.0 * MM));
    assertEquals(walk + impact, t.expectedCost() / SCALE, 2e-9, t.format());
    assertTrue(Math.abs(Math.round(impact * SCALE) - t.impactCost(0)) <= 1, t.format());
  }

  @Test
  void whatTheCollarCannotReachIsRetriedNotChased() {
    final SmartOrderRouter r = router(params(0L), ExecutionModel.defaults(), venue(1, 0.0005, MM));
    r.submit(1, 10, Side.Buy, 100 * MM, 1.0, 0L);
    final StubVenues.Order o = venues.last();
    assertEquals(80 * MM, o.qty(), "touch, +1 and +2 ticks: 10 + 20 + 50");
    assertEquals(BID + 3 * TICK, o.price());
    assertEquals(20 * MM, r.parentAccounting(1)[3], "the rest waits for a retry");
  }

  @Test
  void ownOrdersCapTheLimitAndAreNeverCrossed() {
    final SmartOrderRouter r = standard(ExecutionModel.defaults());
    // the quoter rests a sell at venue 2's touch: venue 2 is out for an aggressive buy
    assertTrue(r.registerOwnOrder(77, 2, Side.Sell, BID + TICK, 3 * MM));
    // and one at venue 1's second level: venue 1 stops one tick inside it
    assertTrue(r.registerOwnOrder(78, 1, Side.Sell, BID + 2 * TICK, 3 * MM));
    assertEquals(2, r.registeredOwnOrders());
    r.submit(1, 10, Side.Buy, 30 * MM, 1.0, 0L);
    final CostTable t = tables.get(0);
    assertEquals(1, venues.orders.size(), t.format());
    assertEquals(1, venues.last().venue());
    assertEquals(BID + TICK, venues.last().price());
    assertEquals(BID + TICK, t.limitPrice(0));
    assertEquals(BID + TICK, t.ownOpposite[1], t.format());
    assertEquals(VenueExclusion.SelfCross, t.exclusion(1));
    assertTrue(r.registerOwnOrder(77, 2, Side.Sell, BID + TICK, 0L), "zero quantity removes");
    assertFalse(r.removeOwnOrder(77));
    assertTrue(r.removeOwnOrder(78));
    assertFalse(r.registerOwnOrder(79, 9, Side.Sell, BID, MM), "unknown venue");
    assertEquals(0, r.registeredOwnOrders());
  }

  @Test
  void anOwnBuyAtTheBidKeepsASellOutOfThatVenue() {
    final SmartOrderRouter r = standard(ExecutionModel.defaults());
    assertTrue(r.registerOwnOrder(80, 1, Side.Buy, BID, 4 * MM));
    r.submit(2, 10, Side.Sell, 12 * MM, 1.0, 0L);
    final CostTable t = tables.get(0);
    assertEquals(BID, t.ownOpposite[0], t.format());
    // venue 1's bid touch is capped one tick above our own buy, so it is out; venue 2 takes it
    assertEquals(VenueExclusion.SelfCross, t.exclusion(0), t.format());
    assertEquals(2, venues.last().venue());
  }

  @Test
  void parentLimitIsRoundedInsideToTheTick() {
    final SmartOrderRouter r = standard(ExecutionModel.defaults());
    r.submit(1, 10, Side.Buy, 30 * MM, 1.0, BID + TICK + TICK / 2);
    for (final StubVenues.Order o : venues.orders) {
      assertEquals(BID + TICK, o.price(), "no child above the rounded limit");
    }
    assertEquals(15 * MM, r.parentAccounting(1)[2], "only the two touches");
    final SmartOrderRouter s = standard(ExecutionModel.defaults());
    venues.orders.clear();
    s.submit(2, 10, Side.Sell, 30 * MM, 1.0, BID - TICK / 2);
    for (final StubVenues.Order o : venues.orders) {
      assertEquals(BID, o.price(), "a sell limit rounds up");
    }
  }

  @Test
  void aVenueWhoseShareIsBelowItsMinimumIsDroppedFromTheSweep() {
    final SmartOrderRouter r =
        router(params(0L), ExecutionModel.defaults(), venue(1, 0.0005, MM), venue(2, 0.0002, 10 * MM));
    r.submit(1, 10, Side.Buy, 12 * MM, 1.0, 0L);
    assertEquals(1, venues.orders.size(), tables.get(0).format());
    assertEquals(1, venues.last().venue());
    assertEquals(12 * MM, venues.last().qty());
    assertEquals(BID + 2 * TICK, venues.last().price());
    assertEquals(VenueExclusion.BelowMinimum, tables.get(0).exclusion(1));
  }

  /** Venue 2 has an empty queue and steady flow, so posting is cheap on expected cost. */
  private SmartOrderRouter passiveCase(final double lambda) {
    venues.bidDepth[1] = 0;
    venues.rate[1] = 2.0 * MM;
    venues.bidDepth[0] = 200 * MM;
    return router(
        params(30_000_000_000L), ExecutionModel.defaults().withRiskAversion(lambda),
        venue(1, 0.0005, MM), venue(2, 0.0002, MM));
  }

  @Test
  void withoutRiskAversionTheRouterPosts() {
    final SmartOrderRouter r = passiveCase(0.0);
    r.submit(1, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final CostTable t = tables.get(0);
    assertEquals(TimeInForce.Day, venues.last().tif(), t.format());
    assertEquals(2, venues.last().venue());
    assertEquals(0, t.riskCost(1));
  }

  @Test
  void theRiskOfStayingOpenMakesTheRouterCross() {
    final SmartOrderRouter r = passiveCase(10_000.0);
    r.submit(1, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final CostTable t = tables.get(0);
    assertEquals(TimeInForce.ImmediateOrCancel, venues.last().tif(), t.format());
    assertTrue(t.riskCost(1) > 0);
    assertTrue(t.passiveCost(1) > t.aggressiveCost(1));
  }

  @Test
  void timeInForceFollowsEachVenueCapabilities() {
    final ExecutionModel m =
        ExecutionModel.defaults()
            .withCapabilities(
                List.of(
                    new VenueCapabilities(1, false, true, 50.0, 20, 50.0, 50),
                    new VenueCapabilities(2, false, false, 50.0, 20, 50.0, 50)));
    final SmartOrderRouter r = standard(m);
    r.submit(1, 10, Side.Buy, 30 * MM, 1.0, 0L);
    final StubVenues.Order fok = venues.orders.get(0);
    final StubVenues.Order day = venues.orders.get(1);
    assertEquals(TimeInForce.FillOrKill, fok.tif());
    assertEquals(TimeInForce.Day, day.tif());
    assertEquals(0, venues.cancels.size());
    report(r, day, OrdStatus.New, 0, 0);
    assertEquals(1, venues.cancels.size(), "a day order standing in for an IOC is cancelled on its ack");
    report(r, day, OrdStatus.Cancelled, 0, 0);
    report(r, fok, OrdStatus.Filled, 10 * MM, 10 * MM);
    assertTrue(r.isActive(1));
    assertEquals(20 * MM, r.parentAccounting(1)[3]);
  }

  @Test
  void aVenueOverItsOrderRateIsThrottled() {
    final ExecutionModel m =
        ExecutionModel.defaults()
            .withCapabilities(List.of(new VenueCapabilities(2, true, true, 0.01, 1, 50.0, 50)));
    final SmartOrderRouter r = standard(m);
    r.submit(1, 10, Side.Buy, 2 * MM, 1.0, 0L);
    assertEquals(2, venues.last().venue());
    r.submit(2, 10, Side.Buy, 2 * MM, 1.0, 0L);
    final CostTable t = tables.get(1);
    assertEquals(VenueExclusion.Throttled, t.exclusion(1), t.format());
    assertEquals(1, venues.last().venue());
    assertTrue(r.scorecard().throttledRows() > 0);
    clock.advance(200_000_000_000L);
    r.submit(3, 10, Side.Buy, 2 * MM, 1.0, 0L);
    assertEquals(VenueExclusion.None, tables.get(2).exclusion(1), "the bucket refilled");
  }

  @Test
  void anOrderToTradeRatioOverTheLimitThrottles() {
    final ExecutionModel m =
        ExecutionModel.defaults()
            .withCapabilities(List.of(new VenueCapabilities(2, true, true, 1000.0, 100, 2.0, 3)));
    final SmartOrderRouter r = router(params(0L), m, venue(2, 0.0002, MM));
    for (int i = 1; i <= 4; i++) {
      r.submit(i, 10, Side.Buy, 2 * MM, 1.0, 0L);
      final StubVenues.Order o = venues.last();
      report(r, o, OrdStatus.Cancelled, 0, 0);
      r.cancel(i);
    }
    final CostTable last = tables.get(tables.size() - 1);
    assertEquals(VenueExclusion.Throttled, last.exclusion(0), last.format());
  }

  /** The defaults without the reject penalty, so rejects do not move flow by price alone. */
  private static ExecutionModel noRejectPenalty() {
    final ExecutionModel d = ExecutionModel.defaults();
    return new ExecutionModel(
        d.modelVersion(), d.riskAversion(), d.postTradeImpactShare(), d.scorecardHalfLifeNanos(),
        0.0, d.cancelRejectPenalty(), d.fillShortfallPenalty(), d.markoutPenaltyWeight(),
        d.priorExposureLotSeconds(), d.calibrationPriorFills(), d.calibrationDecay(),
        d.tradeRateHalfLifeNanos(), d.outageFailures(), d.outageCooldownNanos(), d.scheduleShape(),
        d.capabilities());
  }

  @Test
  void rejectsMakeAnOutageAndTheVenueComesBackAfterItsCoolDown() {
    final SmartOrderRouter r = router(params(0L), noRejectPenalty(), venue(1, 0.0005, MM), venue(2, 0.0002, MM));
    for (int i = 1; i <= 3; i++) {
      venues.orders.clear();
      r.submit(i, 10, Side.Buy, 2 * MM, 1.0, 0L);
      final StubVenues.Order o = venues.last();
      assertEquals(2, o.venue());
      report(r, o, OrdStatus.Rejected, 0, 0);
      r.cancel(i);
      clock.advance(600_000_000L);
      r.poll();
    }
    assertEquals(1, r.scorecard().outageEpisodes());
    r.submit(10, 10, Side.Buy, 2 * MM, 1.0, 0L);
    assertEquals(VenueExclusion.Outage, tables.get(tables.size() - 1).exclusion(1));
    assertEquals(1, venues.last().venue());
    clock.advance(5_000_000_000L);
    r.poll();
    r.submit(11, 10, Side.Buy, 2 * MM, 1.0, 0L);
    final CostTable back = tables.get(tables.size() - 1);
    assertTrue(back.exclusion(1) == VenueExclusion.None, back.format());
    assertTrue(back.rejectRate[1] > 0, "its rejects are still on its scorecard");
  }

  @Test
  void aRejectingVenueIsPenalisedInTheAllInPrice() {
    final SmartOrderRouter r = standard(ExecutionModel.defaults());
    r.submit(1, 10, Side.Buy, 2 * MM, 1.0, 0L);
    final StubVenues.Order o = venues.last();
    assertEquals(2, o.venue());
    report(r, o, OrdStatus.Rejected, 0, 0);
    r.cancel(1);
    venues.orders.clear();
    venues.askDepth[0] = 10 * MM;
    r.submit(2, 10, Side.Buy, 10 * MM, 1.0, 0L);
    final CostTable t = tables.get(tables.size() - 1);
    assertTrue(t.penalty(1) >= Math.round(0.5 * 0.05 * SCALE) - 1, t.format());
    // a 0.025 point penalty is more than a tick: venue 1's touch, then venue 1's next level,
    // come before venue 2's touch
    assertEquals(1, venues.orders.size(), t.format());
    assertEquals(1, venues.last().venue());
  }

  @Test
  void markoutsMeasureTheMidMoveAfterAFill() {
    final SmartOrderRouter r = standard(ExecutionModel.defaults());
    r.submit(1, 10, Side.Buy, 2 * MM, 1.0, 0L);
    final StubVenues.Order o = venues.last();
    report(r, o, OrdStatus.Filled, 2 * MM, 2 * MM);
    for (int i = 0; i < 2; i++) {
      venues.bid[i] = BID - TICK;
      venues.ask[i] = BID;
    }
    clock.advance(1_000_000_000L);
    r.poll();
    final VenueScorecard sc = r.scorecard();
    assertEquals(-TICK / SCALE, sc.markout(1, clock.nanoTime(), 0), 1e-12, "the mid fell after our buy");
    assertEquals(0.0, sc.markout(1, clock.nanoTime(), 1));
    clock.advance(30_000_000_000L);
    r.poll();
    assertEquals(-TICK / SCALE, sc.markout(1, clock.nanoTime(), 2), 1e-12);
    assertTrue(sc.fillRatio(1, clock.nanoTime()) > 0.99);
    r.submit(2, 10, Side.Buy, 2 * MM, 1.0, 0L);
    final CostTable t = tables.get(tables.size() - 1);
    assertTrue(t.penalty(1) > 0, "an adverse 5 s markout costs: " + t.format());
  }

  @Test
  void queuePositionFollowsTradePrintsAndUnexplainedDecrements() {
    venues.bidDepth[1] = 6 * MM;
    venues.rate[1] = 2.0 * MM;
    venues.bidDepth[0] = 200 * MM;
    final SmartOrderRouter r =
        router(params(30_000_000_000L), ExecutionModel.defaults().withRiskAversion(0.0),
            venue(1, 0.0005, MM), venue(2, 0.0002, MM));
    r.submit(1, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order o = venues.last();
    assertEquals(TimeInForce.Day, o.tif(), tables.get(0).format());
    assertEquals(6.0 * MM, r.queueAhead(o.id()));
    report(r, o, OrdStatus.New, 0, 0);
    // a 2 million print at our price: off the queue ahead of us
    r.onTrade(2, Side.Sell, BID, 2 * MM);
    venues.bidDepth[1] = 6 * MM;
    r.onBookUpdate(2);
    assertEquals(4.0 * MM, r.queueAhead(o.id()), 1.0);
    // a 1 million decrement no print explains: cancellation, a share of it ahead of us
    venues.bidDepth[1] = 5 * MM;
    r.onBookUpdate(2);
    assertEquals(3.0 * MM, r.queueAhead(o.id()), 1.0);
    // a print through our price: nothing left ahead
    r.onTrade(2, Side.Sell, BID - TICK, MM);
    assertEquals(0.0, r.queueAhead(o.id()));
    assertEquals(-1.0, r.queueAhead(999L));
  }

  @Test
  void theCancellationRateIsLearnedFromDecrementsThatNoPrintExplains() {
    final SmartOrderRouter cancels = standard(ExecutionModel.defaults());
    final SmartOrderRouter trades = standard(ExecutionModel.defaults());
    final VenueScorecard a = cancels.scorecard();
    final VenueScorecard b = trades.scorecard();
    final double prior = a.cancelRate(0, clock.nanoTime());
    assertEquals(0.2, prior, 1e-12, "the declared prior before anything is seen");
    for (int i = 0; i < 400; i++) {
      venues.bidDepth[0] = 10 * MM;
      cancels.onBookUpdate(1);
      trades.onBookUpdate(1);
      clock.advance(1_000_000_000L);
      trades.onTrade(1, Side.Sell, BID, 8 * MM);
      venues.bidDepth[0] = 2 * MM;
      cancels.onBookUpdate(1);
      trades.onBookUpdate(1);
    }
    final long now = clock.nanoTime();
    // 8 of the 20 lots queued on the two touches cancel each second
    assertTrue(a.cancelRate(0, now) > 0.3, "learned: " + a.cancelRate(0, now));
    assertTrue(b.cancelRate(0, now) < 0.1, "every decrement was a trade: " + b.cancelRate(0, now));
    assertTrue(b.marketOrderRate(0, now, Side.Buy) > 2.0 * MM, "prints measure the flow");
    assertEquals(-1.0, a.marketOrderRate(0, now, Side.Buy), "no print seen");
  }

  @Test
  void outcomesCalibrateTheFillProbability() {
    final FillCalibration c = new FillCalibration(2, 0.98);
    assertEquals(0.6, FillCalibration.calibrate(0.6, 0.0, 0.0, 5.0), 1e-12, "the prior is the model");
    for (int i = 0; i < 50; i++) {
      c.record(1, 2, 0.8, 0.0);
    }
    final double p = FillCalibration.calibrate(0.8, c.observed(1, 2), c.predicted(1, 2), 5.0);
    assertTrue(p < 0.2, "every child failed: " + p);
    assertEquals(0.0, c.observed(0, 2));
    assertEquals(50, c.outcomes());
    assertEquals(0, FillCalibration.bucket(0));
    assertEquals(1, FillCalibration.bucket(4));
    assertEquals(2, FillCalibration.bucket(5));
    assertEquals(3, FillCalibration.bucket(20));
    assertEquals(0.0, FillCalibration.calibrate(0.0, 1.0, 1.0, 5.0));
    assertEquals(1.0, FillCalibration.calibrate(0.9, 100.0, 1.0, 5.0));
  }

  @Test
  void aPassiveChildAtItsTimeLimitIsAnOutcomeAndOtherCancelsAreCensored() {
    venues.bidDepth[1] = 0;
    venues.rate[1] = 2.0 * MM;
    venues.bidDepth[0] = 200 * MM;
    final SmartOrderRouter r =
        router(params(5_000_000_000L), ExecutionModel.defaults().withRiskAversion(0.0),
            venue(1, 0.0005, MM), venue(2, 0.0002, MM));
    r.submit(1, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order passive = venues.last();
    report(r, passive, OrdStatus.New, 0, 0);
    clock.advance(5_001_000_000L);
    r.poll();
    report(r, passive, OrdStatus.Cancelled, 0, 0);
    assertEquals(1, r.calibration().outcomes(), "cancelled at its limit with nothing filled");
    assertEquals(0.0, r.calibration().observed(1, 0));
    assertTrue(r.calibration().predicted(1, 0) > 0.5);
    report(r, venues.last(), OrdStatus.Filled, 2 * MM, 2 * MM);
    assertFalse(r.isActive(1));
    // a parent cancel censors its resting child
    r.submit(2, 10, Side.Buy, 2 * MM, 0.0, 0L);
    final StubVenues.Order second = venues.last();
    report(r, second, OrdStatus.New, 0, 0);
    r.cancel(2);
    report(r, second, OrdStatus.Cancelled, 0, 0);
    assertEquals(1, r.calibration().outcomes());
  }

  @Test
  void declaredVenuesDoNotCarryTheSimulatorsRates() {
    final SimConfig cfg = SimConfig.ust10yThreeVenues();
    final VenueParams[] declared = cfg.declaredVenues(0.25, 5);
    final VenueParams[] leaked = cfg.routerVenues(0.25, 5);
    for (int i = 0; i < declared.length; i++) {
      assertEquals(SimConfig.DECLARED_CANCEL_RATE, declared[i].cancelRatePerSecond());
      assertTrue(declared[i].cancelRatePerSecond() != cfg.cancelRatePerSecond());
      assertEquals(RouterPresets.clob(1).adv(), declared[i].adv());
      assertTrue(declared[i].adv() != leaked[i].adv());
      assertEquals(leaked[i].takerFee(), declared[i].takerFee());
    }
  }

  @Test
  void theTouchModelIsUntouchedWithoutAnExecutionModel() {
    final SmartOrderRouter r =
        new SmartOrderRouter(
            RouterPresets.global(), new VenueParams[] {venue(1, 0.0005, MM)}, venues, venues,
            FrameSink.NONE, clock, RandomSource.seeded(1L), 2, 16, 1L);
    assertNull(r.model());
    assertNull(r.scorecard());
    assertNull(r.calibration());
    assertEquals(-1.0, r.queueAhead(1L));
    r.onTrade(1, Side.Buy, BID, MM);
    r.submit(1, 10, Side.Buy, 2 * MM, 1.0, 0L);
    assertEquals(BID + TICK + 8 * TICK, venues.last().price(), "the touch model's 8-tick collar");
    final CostTable t = r.lastDecision();
    assertFalse(t.isLadder());
    final Allocator a = new Allocator();
    final CostTable copy = new CostTable();
    copy.copyFrom(t);
    copy.ladder = true;
    assertThrows(IllegalArgumentException.class, () -> a.allocate(copy, RouterPresets.global(), r.venues()));
  }

  @Test
  void modelParametersAreValidated() {
    final ExecutionModel d = ExecutionModel.defaults();
    assertThrows(IllegalArgumentException.class, () -> d.withRiskAversion(-1.0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VenueCapabilities(1, true, true, 0.0, 1, 2.0, 0));
    assertEquals(VenueCapabilities.standard(5), d.capabilities(5));
    assertEquals(0.0, d.riskPerUnit(0.01, -1.0, MM));
    assertEquals(1e-4 * 2.0, d.riskPerUnit(0.01, 2.0, MM), 1e-15);
  }
}
