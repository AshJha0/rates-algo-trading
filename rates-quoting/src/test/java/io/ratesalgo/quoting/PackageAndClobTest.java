package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.NewOrderDecoder;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.risk.RiskEngine;
import org.junit.jupiter.api.Test;

class PackageAndClobTest {

  private final TestMarket m = new TestMarket();
  private final RiskEngine risk = m.desk.risk();

  @Test
  void curvePackageCostsLessThanItsLegsByTheNettedHedgeSaving() {
    final PackageQuoter pq = m.desk.packages();
    final PackageQuoter.PackageRequest curve =
        pq.neutralPair(PackageQuoter.Type.CURVE, TestMarket.SW2, 100 * FACE_1M, TestMarket.SW10);
    assertTrue(curve.clientQuantity(1) < 0, "pay 2s, receive 10s: " + curve.clientQuantity(0) + " " + curve.clientQuantity(1));
    final PackageQuoter.Result r = pq.quote(curve, 2, 4, new PackageQuoter.Result());
    assertTrue(r.quoted());
    assertTrue(r.hedgeSaving() > 0.0);
    assertTrue(r.charge() < r.legChargeSum());
    assertEquals(r.spreadChargeSum() - r.hedgeSaving() + r.inventoryCharge(), r.charge(), 1e-6);
    final Dv01Vector net = new Dv01Vector(CurrencyCode.USD);
    r.netLadder(net);
    assertTrue(Math.abs(net.parallel()) < 0.02 * net.gross(), "DV01-neutral up to rounding");
    // the client pays fixed on 2s (buys the payer) above fair and receives on 10s below fair
    assertTrue(Decimal9.toDouble(r.legPrice(0)) > m.levelOf(TestMarket.SW2));
    assertTrue(Decimal9.toDouble(r.legPrice(1)) < m.levelOf(TestMarket.SW10));
  }

  @Test
  void everyStructuredTypeBuildsAndPrices() {
    final PackageQuoter pq = m.desk.packages();
    final PackageQuoter.Result r = new PackageQuoter.Result();
    assertTrue(pq.quote(pq.butterfly(TestMarket.SW2, TestMarket.SW5, 50 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
    assertEquals(PackageQuoter.Type.BUTTERFLY, pq.butterfly(TestMarket.SW2, TestMarket.SW5, FACE_1M, TestMarket.SW10).type());
    assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.SPREAD_OVER, TestMarket.UST10, 50 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
    assertTrue(r.hedgeSaving() > 0.0, "asset swap nets: long bond, pay fixed");
    assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.INVOICE_SPREAD, TestMarket.TY, 50 * FACE_1M, TestMarket.SW10), 1, 3, r).quoted());
    final PackageQuoter.PackageRequest roll = pq.neutralPair(PackageQuoter.Type.IMM_ROLL, TestMarket.TY, 50 * FACE_1M, TestMarket.TY);
    assertEquals(-50 * FACE_1M, roll.clientQuantity(1));
    assertTrue(pq.quote(pq.neutralPair(PackageQuoter.Type.SWITCH, TestMarket.UST5, 50 * FACE_1M, TestMarket.UST10), 1, 3, r).quoted());
    assertEquals(2, roll.legs());
    // mixed currencies are refused
    assertFalse(pq.quote(pq.neutralPair(PackageQuoter.Type.SWITCH, TestMarket.UST10, 50 * FACE_1M, TestMarket.BUND10), 1, 3, r).quoted());
    risk.limits().priceCollarBp(0.01);
    assertFalse(pq.quote(pq.neutralPair(PackageQuoter.Type.SWITCH, TestMarket.UST5, 50 * FACE_1M, TestMarket.UST10), 1, 3, r).quoted());
    assertNotEquals(0, r.rejection());
  }

  @Test
  void clobRestsBothSidesKeepsQueueAndRequotesOnLargeMoves() {
    final ClobQuoter clob = m.desk.clob();
    clob.enable(TestMarket.UST10);
    final long now = m.clock.epochNanos();
    clob.update(TestMarket.UST10, now);
    assertEquals(2, clob.ordersSent());
    final long bidId = clob.restingOrder(TestMarket.UST10, true);
    assertTrue(bidId != 0L && clob.restingOrder(TestMarket.UST10, false) != 0L);
    final NewOrderDecoder d = new NewOrderDecoder();
    d.wrap(m.gateway.message(0), 8, NewOrderDecoder.BLOCK_LENGTH, 0);
    assertTrue(d.quantity() <= (long) (0.25 * 50 * FACE_1M), "participation cap");
    assertEquals(d.quantity(), risk.pendingBuy(TestMarket.UST10));
    // a small move keeps the order and its queue position
    m.clock.advance(100_000_000L);
    m.shift(CurrencyCode.USD, 0.05);
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertEquals(bidId, clob.restingOrder(TestMarket.UST10, true));
    assertEquals(0, clob.cancelsSent());
    // a large move cancels; the venue confirms; the next update re-places
    m.clock.advance(100_000_000L);
    m.shift(CurrencyCode.USD, 0.4);
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertTrue(clob.cancelPending(TestMarket.UST10, true));
    clob.onExecution(bidId, 0L, 0L, OrdStatus.Cancelled);
    clob.onExecution(clob.restingOrder(TestMarket.UST10, false), 0L, 0L, OrdStatus.Cancelled);
    assertEquals(0L, risk.pendingBuy(TestMarket.UST10));
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertEquals(0L, clob.restingOrder(TestMarket.UST10, true), "re-quote throttled");
    m.clock.advance(60_000_000L);
    m.refreshAll(m.clock.epochNanos());
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertTrue(clob.restingOrder(TestMarket.UST10, true) != 0L);
    assertTrue(clob.throttled() > 0);
  }

  /**
   * DR-R2R-12: on a venue with a 1/256 tick the bid rounds down and the ask up to the tick, each
   * within one tick of the Decimal9 target, so the venue does not refuse the order as off-tick.
   */
  @Test
  void clobPricesAreOnTheVenueTick() {
    final long tick = Decimal9.round(1.0 / 256);
    final ClobQuoter fine = new TestMarket().desk.clob();
    final ClobQuoter coarse = m.desk.clob();
    fine.enable(TestMarket.UST5);
    coarse.enable(TestMarket.UST5, tick);
    fine.update(TestMarket.UST5, m.clock.epochNanos());
    coarse.update(TestMarket.UST5, m.clock.epochNanos());
    final long fineBid = fine.restingPrice(TestMarket.UST5, true);
    final long fineAsk = fine.restingPrice(TestMarket.UST5, false);
    final long bid = coarse.restingPrice(TestMarket.UST5, true);
    final long ask = coarse.restingPrice(TestMarket.UST5, false);
    assertTrue(fineBid % tick != 0 || fineAsk % tick != 0, "the Decimal9 targets are off the tick");
    assertEquals(0L, bid % tick);
    assertEquals(0L, ask % tick);
    assertTrue(bid <= fineBid && fineBid - bid < tick, bid + " vs " + fineBid);
    assertTrue(ask >= fineAsk && ask - fineAsk < tick, ask + " vs " + fineAsk);
    assertThrows(IllegalArgumentException.class, () -> coarse.enable(TestMarket.UST10, 0L));
  }

  @Test
  void clobFillsUpdateRiskAndQueueEstimate() {
    final ClobQuoter clob = m.desk.clob();
    clob.enable(TestMarket.UST5);
    clob.update(TestMarket.UST5, m.clock.epochNanos());
    final long bid = clob.restingOrder(TestMarket.UST5, true);
    final long px = clob.restingPrice(TestMarket.UST5, true);
    final long reserved = clob.reserved(TestMarket.UST5, true);
    clob.onExecution(bid, FACE_1M, px, OrdStatus.PartiallyFilled);
    assertEquals(FACE_1M, risk.position(TestMarket.UST5));
    assertEquals(reserved - FACE_1M, risk.pendingBuy(TestMarket.UST5));
    m.book.set(TestMarket.UST5, px, 2 * FACE_1M, px + Decimal9.round(0.02), 50 * FACE_1M, m.clock.epochNanos());
    clob.onBook(TestMarket.UST5);
    assertTrue(clob.queueAhead(TestMarket.UST5, true) <= 2 * FACE_1M);
    clob.onExecution(bid, reserved - FACE_1M, px, OrdStatus.Filled);
    assertEquals(0L, clob.restingOrder(TestMarket.UST5, true));
    assertEquals(0L, risk.pendingBuy(TestMarket.UST5));
    clob.onExecution(999L, FACE_1M, px, OrdStatus.Filled);
  }

  @Test
  void clobPullsOnFuturesJumpsAndImbalanceAndPreventsSelfMatch() {
    final ClobQuoter clob = m.desk.clob();
    clob.enable(TestMarket.SW10);
    clob.enable(TestMarket.UST10);
    final double sw = m.levelOf(TestMarket.SW10);
    m.book.set(TestMarket.SW10, Decimal9.round(sw - 0.002), 50 * FACE_1M, Decimal9.round(sw + 0.002), 50 * FACE_1M, m.clock.epochNanos());
    clob.update(TestMarket.SW10, m.clock.epochNanos());
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    final long cancels = clob.cancelsSent();
    // TY jumps by 1 bp of yield
    final double ty = m.levelOf(TestMarket.TY) + risk.quotePerBp(TestMarket.TY);
    m.book.set(TestMarket.TY, Decimal9.round(ty - 1.0 / 128), 50 * FACE_1M, Decimal9.round(ty + 1.0 / 128), 50 * FACE_1M, m.clock.epochNanos());
    clob.update(TestMarket.SW10, m.clock.epochNanos());
    assertEquals(cancels + 2, clob.cancelsSent());
    assertTrue(clob.adversePulls() >= 1);
    // heavy bid on UST10: the ask is about to be lifted, pull it
    final double lvl = m.levelOf(TestMarket.UST10);
    m.book.set(TestMarket.UST10, Decimal9.round(lvl - 1.0 / 128), 90 * FACE_1M, Decimal9.round(lvl + 1.0 / 128), 5 * FACE_1M, m.clock.epochNanos());
    clob.update(TestMarket.UST10, m.clock.epochNanos());
    assertTrue(clob.cancelPending(TestMarket.UST10, false));
    assertFalse(clob.cancelPending(TestMarket.UST10, true));
    // self-match: our ask rests low; a bid at or above it is never placed
    final ClobQuoter.Config cfg = ClobQuoter.config().halfSpreadBp(-1.0);
    final TestMarket m2 = new TestMarket();
    final ClobQuoter c2 =
        new ClobQuoter(m2.directory, m2.desk.risk(), m2.desk.fairValue(), m2.book, m2.gateway, m2.clock, cfg, 2_000_000_000L);
    c2.enable(TestMarket.UST2);
    c2.update(TestMarket.UST2, m2.clock.epochNanos());
    assertEquals(1, c2.ordersSent());
    assertEquals(1, c2.selfMatchesPrevented());
  }

  @Test
  void killSwitchCancelsRestingOrders() {
    final ClobQuoter clob = m.desk.clob();
    clob.enable(TestMarket.UST2);
    clob.update(TestMarket.UST2, m.clock.epochNanos());
    m.desk.killSwitch().trigger(KillReason.LimitBreach, 0, 0);
    assertTrue(clob.cancelPending(TestMarket.UST2, true));
    assertTrue(clob.cancelPending(TestMarket.UST2, false));
    final long sent = clob.ordersSent();
    clob.update(TestMarket.UST2, m.clock.epochNanos());
    assertEquals(sent, clob.ordersSent());
  }

  @Test
  void presetParsingAndSpreadOptimum() throws Exception {
    final HitRatePreset p = HitRatePreset.shippedDefault();
    assertEquals("assumption", p.fittedFrom());
    assertEquals(HitRatePreset.DEFAULT_NAME, p.name());
    assertEquals(-1.6, p.value(HitRateModel.SPREAD_BP), 0.0);
    assertEquals("spread_bp", HitRatePreset.key(HitRateModel.SPREAD_BP));
    final HitRatePreset fromMissing = HitRatePreset.load(java.nio.file.Path.of("no-such-file.yaml"), "x");
    assertEquals(p.value(0), fromMissing.value(0), 0.0);
    final String yaml =
        """
        presets:
          - name: other
            parameters:
              intercept: { value: 9, band_low: 0, band_high: 9 }
          - name: mine # comment
            fitted_from: data/manifests/x.json
            parameters:
              intercept: { value: 0.5, standard_error: 0.1, band_low: 0.0, band_high: 1.0 }
              spread_bp: { value: -2.0, standard_error: null, band_low: -3.0, band_high: -1.0 }
              log_size: { value: 0, band_low: 0, band_high: 0 }
              tenor_years: { value: 0, band_low: 0, band_high: 0 }
              liquid_session: { value: 0, band_low: 0, band_high: 0 }
              dealers_minus_one: { value: -0.3, band_low: -0.3, band_high: -0.3 }
            notes: x
        """;
    final HitRatePreset mine = HitRatePreset.parse(new java.io.StringReader(yaml), "mine");
    assertEquals(0.5, mine.value(0), 0.0);
    assertEquals("data/manifests/x.json", mine.fittedFrom());
    assertEquals(null, HitRatePreset.parse(new java.io.StringReader(yaml), "absent"));
    final java.nio.file.Path tmp = java.nio.file.Files.createTempFile("presets", ".yaml");
    java.nio.file.Files.writeString(tmp, yaml);
    assertEquals(-2.0, HitRatePreset.load(tmp, "mine").value(1), 0.0);
    java.nio.file.Files.delete(tmp);
    org.junit.jupiter.api.Assertions.assertThrows(
        java.io.IOException.class,
        () -> HitRatePreset.parse(new java.io.StringReader("presets:\n  - name: bad\n    parameters:\n      intercept: { value: 5, band_low: 0, band_high: 1 }\n"), "bad"));
    org.junit.jupiter.api.Assertions.assertThrows(
        java.io.IOException.class,
        () -> HitRatePreset.parse(new java.io.StringReader("presets:\n  - name: bad\n    parameters:\n      intercept: { value: x, band_low: 0, band_high: 1 }\n"), "bad"));

    final HitRateModel model = new HitRateModel(p, 0.0);
    final SpreadModel s = new SpreadModel(model);
    final double base = model.base(1.0, 10.0, 1.0, 4);
    assertEquals(0.321, model.winProbability(base, 0.5), 0.001);
    final double c = 0.3;
    final double opt = s.optimal(base, c, 10.0);
    final double pw = model.winProbability(base, opt);
    // first-order condition s = c + 1 / (-b1 (1 - P))
    assertEquals(c + 1.0 / (1.6 * (1.0 - pw)), opt, 1e-6);
    assertTrue(s.expected(base, c, opt) >= s.expected(base, c, opt + 0.1));
    assertTrue(s.expected(base, c, opt) >= s.expected(base, c, opt - 0.1));
    for (int i = 0; i < 10_000; i++) {
      model.update(base, 0.5, true);
    }
    final HitRateModel learning = new HitRateModel(p, 0.5);
    for (int i = 0; i < 10_000; i++) {
      learning.update(base, 0.5, true);
    }
    assertEquals(2.0, learning.coefficient(HitRateModel.INTERCEPT), 0.0, "clamped to band");
    assertTrue(learning.coefficient(HitRateModel.SPREAD_BP) <= -1.0);
    assertEquals(0.5, HitRateModel.logistic(0.0), 0.0);
    assertTrue(HitRateModel.logistic(-800.0) >= 0.0);
  }
}
