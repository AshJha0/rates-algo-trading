package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.B2;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.RiskLimits;
import org.junit.jupiter.api.Test;

class HedgerTest {

  private static final long HALF_LIFE = 30_000_000_000L;

  @Test
  void firstTrancheThenDecayTowardThePlan() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().firstTranche(0.5).halfLifeNanos(HALF_LIFE).triggerStdDev(1_000.0));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10)); // -82k at 10Y
    final long t0 = f.clock.epochNanos();
    assertTrue(h.onTimer(t0) > 0);
    assertTrue(h.inEpisode());
    final RecordingHedgeOrderSink.Parent first = f.sink.parents().get(0);
    assertEquals(0L, first.urgencyNanos(), "first tranche is immediate");
    assertEquals(0.5, sentPv01(f) / 82_000.0, 0.03, "half of the risk in the first tranche");
    assertTrue(h.residualVariance() > 0.0);
    // fill everything that was sent at arrival mid
    fillAll(f, h);
    assertEquals(0, h.workingParents());
    f.clock.advance(HALF_LIFE);
    final int before = f.sink.parents().size();
    h.onTimer(f.clock.epochNanos());
    final RecordingHedgeOrderSink.Parent later = f.sink.parents().get(before);
    assertEquals(f.clock.epochNanos() + HALF_LIFE, later.urgencyNanos());
    fillAll(f, h);
    final Dv01Vector r = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, r);
    assertEquals(0.25, Math.abs(r.parallel()) / 82_000.0, 0.05, "residual halves again after one half-life");
    assertTrue(h.analytics().residualCount() >= 2);
    assertTrue(h.analytics().residualVariance(1) < h.analytics().residualVariance(0));
    assertEquals(0.0, h.analytics().averageSlippageBp(), 1e-9, "filled at arrival mid");
    assertTrue(h.analytics().fills() > 0);
    assertTrue(h.parentsSent() >= 2);
  }

  /**
   * DR-R2R-02: a hedge fill that breaches the daily loss limit fires the kill switch from inside
   * onHedgeFill. The kill must complete: no exception, one KillSwitch message, no parent left
   * working and no reservation left behind.
   */
  @Test
  void hedgeFillThatTripsTheLossLimitKillsCleanly() {
    final HedgeFixture f = new HedgeFixture(new RiskLimits().dailyLoss(50_000.0));
    final Hedger h = f.hedger(Hedger.config());
    f.risk.onFill(B10, 100 * M * 100, Decimal9.round(98.25));
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    final RecordingHedgeOrderSink.Parent p = f.sink.parents().get(0);
    final long five = Decimal9.round(5.0);
    final long bad = p.side() == Side.Sell ? p.limitPrice() - five : p.limitPrice() + five;
    h.onHedgeFill(p.parentId(), bad, p.quantityMinor());
    assertEquals(io.ratesalgo.risk.KillState.KILLED, f.kill.state());
    assertEquals(1, f.control.count(io.ratesalgo.messages.sbe.KillSwitchDecoder.TEMPLATE_ID));
    assertEquals(0L, f.kill.listenerFailures());
    assertEquals(0, h.workingParents());
    for (final RecordingHedgeOrderSink.Parent q : f.sink.parents()) {
      assertEquals(0L, f.risk.pendingBuy(q.instrumentId()) + f.risk.pendingSell(q.instrumentId()));
    }
  }

  /**
   * Staged execution against the same plan executed at once: with quadratic impact, trading in
   * tranches costs less in the model.
   */
  @Test
  void stagedHedgingCostsLessThanImmediateExecution() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().firstTranche(0.4).halfLifeNanos(HALF_LIFE).triggerStdDev(5_000.0));
    f.risk.onFill(B10, 300 * M, f.fair.fairMid(B10));
    final Dv01Vector r0 = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, r0);
    final HedgePlanner p = f.planner(1e-5);
    p.plan(r0);
    final double[] plan = new double[f.universe.size()];
    p.copyLots(plan);
    final double immediate = p.cost(plan);
    double staged = 0.0;
    for (int step = 0; step < 40 && h.onTimer(f.clock.epochNanos()) >= 0; step++) {
      for (final RecordingHedgeOrderSink.Parent par : f.sink.parents()) {
        final int j = f.universe.indexOf(par.instrumentId());
        staged += f.costs.cost(par.instrumentId(), (double) par.quantityMinor() / f.universe.lotMinor(j));
      }
      fillAll(f, h);
      f.sink.clear();
      f.clock.advance(HALF_LIFE / 4);
    }
    final Dv01Vector r = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, r);
    System.out.printf("HedgerTest: immediate cost %.0f, staged cost %.0f, residual sd %.0f%n", immediate, staged, Math.sqrt(p.variance(r)));
    assertTrue(staged < immediate, "staged " + staged + " immediate " + immediate);
    assertTrue(Math.sqrt(p.variance(r)) < 5_000.0 * 1.5);
  }

  @Test
  void killCancelsAndReleasesAndAxesHelpTheHedge() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0).axeMaxBp(0.5));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    assertEquals(0.0, h.axeBp(B10, Side.Sell), 0.0, "no episode yet");
    h.onTimer(f.clock.epochNanos());
    assertTrue(h.axeBp(B10, Side.Sell) > 0.0, "selling bonds helps a long-duration book");
    assertEquals(0.0, h.axeBp(B10, Side.Buy), 0.0);
    assertEquals(0.0, h.axeBp(HedgeFixture.RX, Side.Sell), 0.0, "other currency");
    assertEquals(0.0, h.axeBp(999, Side.Sell), 0.0);
    assertTrue(h.axeBp(B10, Side.Sell) <= 0.5);
    final Dv01Vector res = new Dv01Vector(CurrencyCode.USD);
    h.residual(res);
    assertEquals(-82_000.0, res.parallel(), 1e-6, "as measured at the timer, before sending");
    h.onTimer(f.clock.epochNanos());
    h.residual(res);
    assertTrue(Math.abs(res.parallel()) < 50_000.0, "working parents count toward the residual");
    assertTrue(h.workingParents() > 0);
    f.kill.trigger(KillReason.Manual, 0, 1);
    assertEquals(1, f.sink.cancelAllCount());
    assertEquals(0, h.workingParents());
    assertFalse(h.inEpisode());
    for (final int id : new int[] {HedgeFixture.TY, HedgeFixture.S10, HedgeFixture.TU}) {
      assertEquals(0L, f.risk.pendingBuy(id) + f.risk.pendingSell(id));
    }
    assertEquals(0, h.onTimer(f.clock.epochNanos()), "nothing while killed");
  }

  /** DR-R2R-13: a fill that raced the kill's cancel-all is booked, not refused as unknown. */
  @Test
  void aFillThatRacedTheKillIsBooked() {
    final HedgeFixture f = new HedgeFixture();
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0));
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    assertTrue(h.onTimer(f.clock.epochNanos()) > 0);
    final RecordingHedgeOrderSink.Parent p = f.sink.parents().get(0);
    final long before = f.risk.position(p.instrumentId());
    f.kill.trigger(KillReason.Manual, 0, 1);
    assertEquals(0, h.workingParents());
    h.onHedgeFill(p.parentId(), p.limitPrice(), p.quantityMinor());
    final long signed = p.side() == Side.Buy ? p.quantityMinor() : -p.quantityMinor();
    assertEquals(before + signed, f.risk.position(p.instrumentId()));
    assertEquals(1, h.lateFills());
    assertEquals(0L, f.risk.pendingBuy(p.instrumentId()) + f.risk.pendingSell(p.instrumentId()));
    // the parent is used up: a further fill on it is an error
    assertThrows(IllegalArgumentException.class,
        () -> h.onHedgeFill(p.parentId(), p.limitPrice(), p.quantityMinor()));
  }

  @Test
  void refusalsAndPartialFills() {
    final HedgeFixture f = new HedgeFixture(new RiskLimits().maxOrderSize(10 * HedgeFixture.CONTRACT));
    final Hedger h = f.hedger(Hedger.config().triggerStdDev(1_000.0));
    f.risk.onFill(B2, 400 * M, f.fair.fairMid(B2));
    h.onTimer(f.clock.epochNanos());
    assertTrue(h.parentsRefused() > 0, "orders above the size limit are refused");
    final HedgeFixture g = new HedgeFixture();
    final Hedger h2 = g.hedger(Hedger.config().triggerStdDev(1_000.0));
    g.risk.onFill(B10, 50 * M, g.fair.fairMid(B10));
    g.sink.accepting(false);
    h2.onTimer(g.clock.epochNanos());
    assertEquals(0, h2.workingParents());
    assertEquals(0L, g.risk.pendingSell(HedgeFixture.TY), "refused by the router: reservation released");
    g.sink.accepting(true);
    g.clock.advance(1_000_000_000L);
    h2.onTimer(g.clock.epochNanos());
    final RecordingHedgeOrderSink.Parent p = g.sink.parents().get(0);
    h2.onHedgeFill(p.parentId(), p.limitPrice(), p.quantityMinor() / 2);
    assertEquals(p.side() == Side.Buy ? p.quantityMinor() - p.quantityMinor() / 2 : -(p.quantityMinor() - p.quantityMinor() / 2), h2.remaining(p.parentId()));
    assertTrue(h2.analytics().averageSlippageBp() > 0.0, "filled at the limit, worse than arrival");
    assertTrue(h2.analytics().slippageCost() > 0.0);
    h2.onParentDone(p.parentId());
    assertEquals(0L, h2.remaining(p.parentId()));
    h2.onParentDone(p.parentId());
    assertThrows(IllegalArgumentException.class, () -> h2.onHedgeFill(12345L, 0L, 1L));
    g.fair.remove(HedgeFixture.TY);
    g.fair.remove(HedgeFixture.S10);
    g.fair.remove(HedgeFixture.US);
    g.fair.remove(HedgeFixture.TU);
    g.fair.remove(HedgeFixture.S2);
    g.fair.remove(HedgeFixture.S30);
    final long refused = h2.parentsRefused();
    h2.onTimer(g.clock.epochNanos());
    assertTrue(h2.parentsRefused() > refused, "no arrival mid, no order");
    assertEquals(CurrencyCode.USD, h2.currency());
  }

  @Test
  void configValidationAndAnalyticsRing() {
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().firstTranche(0.0));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().halfLifeNanos(0));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().triggerStdDev(-1));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().axeMaxBp(-1));
    assertThrows(IllegalArgumentException.class, () -> Hedger.config().limitBp(0));
    final HedgeAnalytics a = new HedgeAnalytics(2, BasisAttribution.NONE);
    a.recordResidual(1, 10.0, 1.0);
    a.recordResidual(2, 20.0, 2.0);
    a.recordResidual(3, 30.0, 3.0);
    assertEquals(2, a.residualCount());
    assertEquals(2L, a.residualTime(0));
    assertEquals(30.0, a.residualVariance(1), 0.0);
    assertEquals(3.0, a.residualNet(1), 0.0);
    assertThrows(IndexOutOfBoundsException.class, () -> a.residualTime(2));
    assertEquals(0.0, a.averageSlippageBp(), 0.0);
    final long[] seen = new long[1];
    final HedgeAnalytics b = new HedgeAnalytics(2, (id, q, px, mid) -> seen[0] = q);
    final HedgeFixture f = new HedgeFixture();
    b.onFill(f.directory.byId(B10), -5L, Decimal9.round(98.0), Decimal9.round(98.1), 0.0, -8.2e-4);
    assertEquals(-5L, seen[0]);
    assertEquals(0.0, b.averageSlippageBp(), 0.0, "no quote-per-bp, no bp figure");
  }

  private static double sentPv01(final HedgeFixture f) {
    double s = 0.0;
    for (final RecordingHedgeOrderSink.Parent p : f.sink.parents()) {
      s += Math.abs(f.risk.unitParallelPv01(p.instrumentId()) * f.directory.byId(p.instrumentId()).major(p.quantityMinor()));
    }
    return s;
  }

  private static void fillAll(final HedgeFixture f, final Hedger h) {
    for (int p = h.workingParents() - 1; p >= 0; p--) {
      final long id = h.workingParentId(p);
      int instrument = 0;
      for (final RecordingHedgeOrderSink.Parent par : f.sink.parents()) {
        if (par.parentId() == id) {
          instrument = par.instrumentId();
        }
      }
      h.onHedgeFill(id, f.fair.fairMid(instrument), Math.abs(h.remaining(id)));
    }
  }
}
