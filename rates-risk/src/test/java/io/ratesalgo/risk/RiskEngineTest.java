package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.BOND10;
import static io.ratesalgo.risk.RiskFixture.BOND2;
import static io.ratesalgo.risk.RiskFixture.BUND;
import static io.ratesalgo.risk.RiskFixture.FUT10;
import static io.ratesalgo.risk.RiskFixture.M;
import static io.ratesalgo.risk.RiskFixture.SWAP10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.LimitKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.messages.sbe.Side;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

  /** One event: a fill (kind 0) or a curve move (kind 1). */
  record Event(int kind, int instrument, long quantity, double bp, boolean eur) {}

  @Provide
  Arbitrary<List<Event>> events() {
    final Arbitrary<Event> e =
        Combinators.combine(
                Arbitraries.integers().between(0, 3),
                Arbitraries.of(BOND10, FUT10, SWAP10, BOND2, BUND),
                Arbitraries.longs().between(-300, 300),
                Arbitraries.doubles().between(-25.0, 25.0),
                Arbitraries.of(true, false))
            .as((k, i, q, bp, eur) -> new Event(k == 3 ? 1 : 0, i, q * M, bp, eur));
    return e.list().ofMinSize(1).ofMaxSize(60);
  }

  @Property(tries = 300)
  void incrementalLadderEqualsFullRevaluation(@ForAll("events") final List<Event> events) {
    final RiskFixture f = new RiskFixture();
    double notional = 0.0;
    for (final Event e : events) {
      if (e.kind() == 0) {
        f.risk.onFill(e.instrument(), e.quantity(), f.fairMid(e.instrument()));
        notional += Math.abs(e.quantity()) / 100.0;
      } else {
        final CurrencyCode c = e.eur() ? CurrencyCode.EUR : CurrencyCode.USD;
        f.sensitivities.shiftCurve(c, e.bp());
        f.risk.onCurveMove(c);
      }
    }
    final Dv01Vector inc = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector full = new Dv01Vector(CurrencyCode.USD);
    for (final CurrencyCode c : new CurrencyCode[] {CurrencyCode.USD, CurrencyCode.EUR}) {
      f.risk.ladder(c, inc);
      f.risk.fullLadder(c, full);
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        // tolerance: 1e-6 of gross traded notional, with a floor of 1e-6 currency units
        assertEquals(full.get(k), inc.get(k), 1e-6 * Math.max(1.0, notional), c + " pillar " + k);
      }
    }
  }

  @Test
  void ladderSignsAndExposures() {
    final RiskFixture f = new RiskFixture();
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    assertEquals(-82_000.0, f.risk.netPv01(CurrencyCode.USD), 1e-6);
    assertEquals(82_000.0, f.risk.grossPv01(CurrencyCode.USD), 1e-6);
    f.risk.onFill(FUT10, -100 * M, f.fairMid(FUT10));
    assertEquals(65_000.0, f.risk.basisExposure(CurrencyCode.USD), 1e-6, "futures short offsets cash long");
    f.risk.onFill(SWAP10, 100 * M, f.fairMid(SWAP10));
    assertEquals(82_000.0, f.risk.productPv01(CurrencyCode.USD, ProductType.SWAP), 1e-6);
    assertEquals(17_000.0, f.risk.swapSpreadExposure(CurrencyCode.USD), 1e-6, "bond plus future is long 17k of risk");
    f.risk.onFill(BOND2, 200 * M, f.fairMid(BOND2));
    assertTrue(f.risk.swapSpreadExposure(CurrencyCode.USD) > 0.0);
    final double steepener = f.risk.curveSpreadExposure(CurrencyCode.USD);
    assertTrue(Math.abs(steepener) > 0.0);
    assertEquals(0.0, f.risk.netPv01(CurrencyCode.EUR), 0.0);
  }

  @Test
  void dailyPnlFollowsMarksAndKillsOnLoss() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(50_000.0));
    f.risk.startOfDay();
    f.risk.onFill(BOND10, 100 * M, Decimal9.round(98.0)); // bought below fair 98.25
    assertEquals(250_000.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6);
    f.risk.onFairValue(BOND10, Decimal9.round(97.0));
    assertEquals(-1_000_000.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6);
    assertEquals(KillState.KILLED, f.kill.state(), "post-trade daily loss fires the kill switch");
    assertEquals(KillReason.LimitBreach, f.kill.lastReason());
    f.risk.startOfDay();
    assertEquals(0.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6);
  }

  @Test
  void everyLimitRefusesAndSendsABreachWithItsKind() {
    final RiskLimits limits =
        new RiskLimits()
            .maxOrderSize(200 * M)
            .fatFingerQuantity(150 * M)
            .fatFingerFraction(0.02)
            .priceCollarBp(10.0)
            .maxPosition(BOND10, 120 * M)
            .defaultMaxPosition(10_000 * M)
            .bucketPv01(5, 90_000.0)
            .netPv01(150_000.0)
            .grossPv01(200_000.0)
            .dailyLoss(1e12)
            .quoteRatePerSecond(3.0)
            .burstSeconds(1.0)
            .orderRatePerSecond(1e6)
            .messageRatePerSecond(1e6);
    final RiskFixture f = new RiskFixture(limits);
    final long px = f.fairMid(BOND10);
    expect(f, LimitKind.MaxOrderSize, f.risk.checkOrder(BOND10, Side.Buy, 201 * M, px, 11));
    expect(f, LimitKind.FatFinger, f.risk.checkOrder(BOND10, Side.Buy, 160 * M, px, 12));
    expect(f, LimitKind.FatFinger, f.risk.checkOrder(BOND10, Side.Buy, M, Decimal9.round(90.0), 13));
    expect(f, LimitKind.PriceCollar, f.risk.checkOrder(BOND10, Side.Buy, M, Decimal9.round(98.25 + 11 * 0.082), 14));
    expect(f, LimitKind.Position, f.risk.checkOrder(BOND10, Side.Buy, 121 * M, px, 15));
    expect(f, LimitKind.BucketDv01, f.risk.checkOrder(BOND10, Side.Sell, 110 * M, px, 16));
    f.risk.onFill(BOND2, -700 * M, f.fairMid(BOND2)); // +133k PV01 at 2Y
    expect(f, LimitKind.NetDv01, f.risk.checkOrder(SWAP10, Side.Buy, 30 * M, f.fairMid(SWAP10), 17));
    f.risk.onFill(SWAP10, -80 * M, f.fairMid(SWAP10)); // -65.6k at 10Y: net 67k, gross 199k
    expect(f, LimitKind.GrossDv01, f.risk.checkQuote(BOND10, px, 2 * M, 0L, 0L, 18));
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, f.fairMid(BUND), M, 0L, 0L, 19));
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, f.fairMid(BUND), M, 0L, 0L, 20));
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, f.fairMid(BUND), M, 0L, 0L, 21));
    expect(f, LimitKind.QuoteRate, f.risk.checkQuote(BUND, f.fairMid(BUND), M, 0L, 0L, 22));
    f.clock.advance(1_000_000_000L);
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, f.fairMid(BUND), M, 0L, 0L, 23));
    assertEquals(RiskEngine.REJECT_UNKNOWN, f.risk.checkQuote(99, px, M, 0L, 0L, 24));
    f.fair.remove(BOND2);
    assertEquals(RiskEngine.REJECT_UNKNOWN, f.risk.checkOrder(BOND2, Side.Buy, M, px, 25));
    f.kill.trigger(KillReason.Manual, 0, 1);
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkQuote(BUND, f.fairMid(BUND), M, 0L, 0L, 26));
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkOrder(BUND, Side.Buy, M, f.fairMid(BUND), 27));
    assertEquals(0L, f.risk.headroom(BUND, Side.Buy));
    assertTrue(f.risk.preTradeRejects() >= 13);
  }

  @Test
  void messageAndOrderRatesAndExemptions() {
    final RiskFixture f =
        new RiskFixture(new RiskLimits().orderRatePerSecond(2.0).messageRatePerSecond(3.0).burstSeconds(1.0));
    final long px = f.fairMid(BUND);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BUND, Side.Buy, M, px, 1));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BUND, Side.Buy, M, px, 2));
    expect(f, LimitKind.OrderRate, f.risk.checkOrder(BUND, Side.Buy, M, px, 3));
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, px, M, 0L, 0L, 4));
    expect(f, LimitKind.MessageRate, f.risk.checkQuote(BUND, px, M, 0L, 0L, 5));
    for (int i = 0; i < 10; i++) {
      f.risk.countExemptMessage(); // cancels are never refused
    }
    f.clock.advance(2_000_000_000L);
    f.limits.messageRatePerSecond(100.0);
    f.risk.limitsChanged();
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, px, M, 0L, 0L, 6));
  }

  @Test
  void reservationsCountInTheEnvelopeAndHeadroom() {
    final RiskFixture f = new RiskFixture(new RiskLimits().defaultMaxPosition(100 * M).bucketPv01(50_000.0));
    assertEquals(60 * M, round(f.risk.headroom(BOND10, Side.Buy)), "10Y pillar binds: 50k / 820 per million");
    f.risk.reserve(BOND10, 40 * M, 0L);
    assertEquals(20 * M, round(f.risk.headroom(BOND10, Side.Buy)));
    assertEquals(40 * M, f.risk.pendingBuy(BOND10));
    f.risk.release(BOND10, 40 * M, 0L);
    assertThrows(IllegalStateException.class, () -> f.risk.release(BOND10, 1L, 0L));
    f.risk.onFill(BOND2, 95 * M, f.fairMid(BOND2));
    assertEquals(5 * M, f.risk.headroom(BOND2, Side.Buy), "position binds");
    assertEquals(195 * M, round(f.risk.headroom(BOND2, Side.Sell)));
  }

  @Test
  void riskReducingHedgesPassWhenTheBookIsInBreach() {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(50_000.0).netPv01(60_000.0));
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10)); // -82k: post-trade breach
    assertTrue(f.alerts.count(RiskLimitBreachDecoder.TEMPLATE_ID) >= 2);
    final int alerts = f.alerts.count(RiskLimitBreachDecoder.TEMPLATE_ID);
    f.risk.onFill(BOND10, M, f.fairMid(BOND10));
    assertEquals(alerts, f.alerts.count(RiskLimitBreachDecoder.TEMPLATE_ID), "alerts are edge-triggered");
    expect(f, LimitKind.BucketDv01, f.risk.checkQuote(BOND10, f.fairMid(BOND10), 0L, f.fairMid(BOND10), M, 1));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(FUT10, Side.Sell, 100 * M, f.fairMid(FUT10), 2));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Sell, 50 * M, f.fairMid(BOND10), 3));
    expect(f, LimitKind.BucketDv01, f.risk.checkOrder(FUT10, Side.Sell, 200 * M, f.fairMid(FUT10), 4));
  }

  @Test
  void curveMovesRevalueAndAlert() {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(83_000.0));
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    final int before = f.alerts.count(RiskLimitBreachDecoder.TEMPLATE_ID);
    f.sensitivities.shiftCurve(CurrencyCode.USD, -10.0); // PV01 grows by 2%
    f.risk.onCurveMove(CurrencyCode.USD);
    assertEquals(-82_000.0 * 1.02, f.risk.netPv01(CurrencyCode.USD), 1e-6);
    assertEquals(before + 1, f.alerts.count(RiskLimitBreachDecoder.TEMPLATE_ID));
    final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder();
    d.wrap(f.alerts.message(f.alerts.size() - 1), MessageHeaderEncoder.ENCODED_LENGTH, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
    assertEquals(LimitKind.BucketDv01, d.limitKind());
    assertEquals(0L, d.rejectedMessageSeqNo(), "post-trade");
    assertEquals(5, d.scopeId(), "10Y pillar");
    assertEquals(-83_640.0, Decimal9.toDouble(d.observedValue().mantissa()), 1e-6);
  }

  @Test
  void limitValidation() {
    final RiskLimits l = new RiskLimits();
    assertThrows(IllegalArgumentException.class, () -> l.netPv01(0.0));
    assertThrows(IllegalArgumentException.class, () -> l.maxOrderSize(0L));
    assertThrows(IllegalArgumentException.class, () -> l.priceCollarBp(Double.NaN));
    assertEquals(3L, l.version(3).version());
    assertEquals(Long.MAX_VALUE, l.maxPosition(77));
    assertTrue(l.killOnDailyLoss());
    assertTrue(!l.killOnDailyLoss(false).killOnDailyLoss());
    assertThrows(IllegalArgumentException.class, () -> new RiskFixture().risk.position(99));
  }

  private static long round(final long v) {
    return (v / M) * M;
  }

  private static void expect(final RiskFixture f, final LimitKind kind, final int code) {
    assertEquals(kind.value(), code, "expected " + kind);
    final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder();
    d.wrap(f.alerts.message(f.alerts.size() - 1), MessageHeaderEncoder.ENCODED_LENGTH, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
    assertEquals(kind, d.limitKind());
    assertEquals(f.clock.epochNanos(), d.timestampNanos());
  }
}
