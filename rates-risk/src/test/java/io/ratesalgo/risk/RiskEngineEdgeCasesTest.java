package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.BOND10;
import static io.ratesalgo.risk.RiskFixture.BOND2;
import static io.ratesalgo.risk.RiskFixture.BUND;
import static io.ratesalgo.risk.RiskFixture.FUT10;
import static io.ratesalgo.risk.RiskFixture.M;
import static io.ratesalgo.risk.RiskFixture.SWAP10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.LimitKind;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.RiskLimitBreachDecoder;
import io.ratesalgo.messages.sbe.Side;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Boundaries, message contents and less-travelled paths of the risk engine, each pinned by a value
 * worked out by hand in the comment next to it. One million is {@code M}; the fixture's PV01 per
 * million is -820 for the 10Y bond, -650 for the future (7Y pillar), +820 for the payer swap and
 * -190 for the 2Y bond, all at their own pillar.
 */
class RiskEngineEdgeCasesTest {

  // ---------------------------------------------------------------- breach message contents

  @Test
  void aPreTradePositionBreachNamesTheInstrumentTheLimitAndTheFigureItWouldReach() {
    final RiskFixture f = new RiskFixture(new RiskLimits().maxPosition(BOND10, 100 * M));
    final long px = f.fairMid(BOND10);
    f.risk.onFill(BOND10, 50 * M, px);
    f.risk.reserve(BOND10, 20 * M, 0L);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, 30 * M, px, 1), "50 + 20 + 30 is at the limit");
    assertEquals(LimitKind.Position.value(), f.risk.checkOrder(BOND10, Side.Buy, 30 * M + 1, px, 2));
    // observed in major units: 70mm held or reserved plus 30mm and one cent
    assertBreach(f, LimitKind.Position, BOND10, 100e6, 100e6 + 0.01, 2);

    final RiskFixture s = new RiskFixture(new RiskLimits().maxPosition(BOND10, 100 * M));
    s.risk.onFill(BOND10, -50 * M, px);
    s.risk.reserve(BOND10, 0L, 20 * M);
    assertEquals(RiskEngine.OK, s.risk.checkOrder(BOND10, Side.Sell, 30 * M, px, 3), "short side at the limit");
    assertEquals(LimitKind.Position.value(), s.risk.checkOrder(BOND10, Side.Sell, 31 * M, px, 4));
    assertBreach(s, LimitKind.Position, BOND10, 100e6, -101e6, 4);
    assertEquals(30 * M, s.risk.headroom(BOND10, Side.Sell), "headroom stops at the same edge");
    assertEquals(150 * M, s.risk.headroom(BOND10, Side.Buy), "buying back the 50mm short, then 100mm long");
  }

  @Test
  void pillarNetAndGrossBreachesReportTheWorstCaseThatBroke() {
    final RiskFixture b = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    final long px = b.fairMid(BOND10);
    assertEquals(LimitKind.BucketDv01.value(), b.risk.checkOrder(BOND10, Side.Buy, 70 * M, px, 1));
    assertBreach(b, LimitKind.BucketDv01, 5, 50_000.0, -57_400.0, 1);
    assertEquals(LimitKind.BucketDv01.value(), b.risk.checkOrder(BOND10, Side.Sell, 70 * M, px, 2));
    assertBreach(b, LimitKind.BucketDv01, 5, 50_000.0, 57_400.0, 2);

    final RiskFixture n = new RiskFixture(new RiskLimits().netPv01(50_000.0));
    assertEquals(LimitKind.NetDv01.value(), n.risk.checkOrder(BOND10, Side.Buy, 70 * M, px, 3));
    assertBreach(n, LimitKind.NetDv01, CurrencyCode.USD.index(), 50_000.0, -57_400.0, 3);
    assertEquals(LimitKind.NetDv01.value(), n.risk.checkOrder(BOND10, Side.Sell, 70 * M, px, 4));
    assertBreach(n, LimitKind.NetDv01, CurrencyCode.USD.index(), 50_000.0, 57_400.0, 4);

    final RiskFixture g = new RiskFixture(new RiskLimits().grossPv01(50_000.0));
    assertEquals(LimitKind.GrossDv01.value(), g.risk.checkOrder(BOND10, Side.Sell, 70 * M, px, 5));
    assertBreach(g, LimitKind.GrossDv01, CurrencyCode.USD.index(), 50_000.0, 57_400.0, 5);
    assertEquals(2L, b.risk.breachCount(), "one message per refusal");
    assertEquals(1L, g.risk.breachCount());
  }

  @Test
  void otherReservationsAtThePillarCountAndTheInstrumentsOwnIsReplacedNotAdded() {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(45_000.0));
    final long px = f.fairMid(BOND10);
    f.risk.reserve(SWAP10, 20 * M, 0L); // +16.4k at 10Y if bought
    // selling 40mm of the bond is +32.8k: with the swap reservation +49.2k, past 45k
    assertEquals(LimitKind.BucketDv01.value(), f.risk.checkOrder(BOND10, Side.Sell, 40 * M, px, 1));
    assertBreach(f, LimitKind.BucketDv01, 5, 45_000.0, 49_200.0, 1);
    f.risk.release(SWAP10, 20 * M, 0L);

    final RiskFixture own = new RiskFixture(new RiskLimits().bucketPv01(52_000.0));
    own.risk.reserve(BOND10, 0L, 20 * M); // its own working sell, +16.4k
    // 20 + 40 = 60mm sold at worst is +49.2k: inside 52k (counting the 20mm twice would be 65.6k)
    assertEquals(RiskEngine.OK, own.risk.checkOrder(BOND10, Side.Sell, 40 * M, px, 2));
    assertEquals(LimitKind.BucketDv01.value(), own.risk.checkOrder(BOND10, Side.Sell, 44 * M, px, 3), "64mm is 52.48k");
    assertBreach(own, LimitKind.BucketDv01, 5, 52_000.0, 52_480.0, 3);
  }

  @Test
  void aBreachedPillarMayStayWhereItIsWithReservationsOnEitherSide() {
    // long bonds: 10Y at -82k against a 50k limit, and a working swap sell of 10mm (-8.2k) on top
    final RiskFixture lo = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    lo.risk.onFill(BOND10, 100 * M, lo.fairMid(BOND10));
    lo.risk.reserve(SWAP10, 0L, 10 * M);
    // a sell of the bond leaves the low extreme at -90.2k, where it already is: accepted
    assertEquals(RiskEngine.OK, lo.risk.checkOrder(BOND10, Side.Sell, 5 * M, lo.fairMid(BOND10), 1));
    assertEquals(LimitKind.BucketDv01.value(), lo.risk.checkOrder(BOND10, Side.Buy, M, lo.fairMid(BOND10), 2));
    assertBreach(lo, LimitKind.BucketDv01, 5, 50_000.0, -91_020.0, 2);

    final RiskFixture hi = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    hi.risk.onFill(BOND10, -100 * M, hi.fairMid(BOND10));
    hi.risk.reserve(SWAP10, 10 * M, 0L);
    assertEquals(RiskEngine.OK, hi.risk.checkOrder(BOND10, Side.Buy, 5 * M, hi.fairMid(BOND10), 3));
    assertEquals(LimitKind.BucketDv01.value(), hi.risk.checkOrder(BOND10, Side.Sell, M, hi.fairMid(BOND10), 4));
    assertBreach(hi, LimitKind.BucketDv01, 5, 50_000.0, 91_020.0, 4);
  }

  @Test
  void aBreachedNetFigureMayStayWhereItIsWithReservationsOnEitherSide() {
    final RiskFixture lo = new RiskFixture(new RiskLimits().netPv01(50_000.0));
    lo.risk.onFill(BOND10, 100 * M, lo.fairMid(BOND10)); // net -82k
    lo.risk.reserve(BOND2, 50 * M, 0L); // -9.5k if bought: net low extreme -91.5k
    lo.risk.reserve(BOND10, 0L, 10 * M); // its own working sell: +8.2k at the high extreme only
    assertEquals(RiskEngine.OK, lo.risk.checkOrder(BOND10, Side.Sell, 5 * M, lo.fairMid(BOND10), 1));
    assertEquals(LimitKind.NetDv01.value(), lo.risk.checkOrder(BOND2, Side.Buy, M, lo.fairMid(BOND2), 2));
    assertBreach(lo, LimitKind.NetDv01, CurrencyCode.USD.index(), 50_000.0, -91_690.0, 2);

    final RiskFixture hi = new RiskFixture(new RiskLimits().netPv01(50_000.0));
    hi.risk.onFill(BOND10, -100 * M, hi.fairMid(BOND10)); // net +82k
    hi.risk.reserve(BOND2, 0L, 50 * M); // +9.5k: high extreme +91.5k
    hi.risk.reserve(BOND10, 10 * M, 0L);
    assertEquals(RiskEngine.OK, hi.risk.checkOrder(BOND10, Side.Buy, 5 * M, hi.fairMid(BOND10), 3));
    assertEquals(LimitKind.NetDv01.value(), hi.risk.checkOrder(BOND2, Side.Sell, M, hi.fairMid(BOND2), 4));
    assertBreach(hi, LimitKind.NetDv01, CurrencyCode.USD.index(), 50_000.0, 91_690.0, 4);
  }

  @Test
  void growthInsideTheRoundingToleranceOfABreachedFigureIsAccepted() {
    // 10Y at -82k against 50k: one more cent of face adds 8.2e-6, inside the 1e-9 * 82k tolerance
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, 1L, f.fairMid(BOND10), 1));
    assertEquals(LimitKind.BucketDv01.value(), f.risk.checkOrder(BOND10, Side.Buy, 100L, f.fairMid(BOND10), 2), "a whole unit is not");
  }

  // ---------------------------------------------------------------- post-trade alerts

  @Test
  void postTradePositionAlertsFireOnTheWayPastTheLimitOnly() {
    final RiskFixture f = new RiskFixture(new RiskLimits().maxPosition(BOND10, 100 * M));
    final long px = f.fairMid(BOND10);
    f.risk.onFill(BOND10, 100 * M, px);
    assertEquals(0, f.alerts.size(), "at the limit is not past it");
    f.risk.onFill(BOND10, 1L, px);
    assertEquals(1, f.alerts.size());
    assertBreach(f, LimitKind.Position, BOND10, 100e6, 100e6 + 0.01, 0);
    f.risk.onFill(BOND10, M, px);
    assertEquals(1, f.alerts.size(), "still past: no second alert");
    f.risk.onFill(BOND10, -101 * M - 1, px);
    assertEquals(1, f.alerts.size(), "flat");
    f.risk.onFill(BOND10, -150 * M, px);
    assertEquals(2, f.alerts.size(), "back under then past on the short side re-arms");
    assertBreach(f, LimitKind.Position, BOND10, 100e6, -150e6, 0);
  }

  @Test
  void postTradeLadderAlertsCarryTheirFigures() {
    final RiskFixture n = new RiskFixture(new RiskLimits().netPv01(100_000.0));
    n.risk.onFill(BOND10, 150 * M, n.fairMid(BOND10));
    assertEquals(1, n.alerts.size());
    assertBreach(n, LimitKind.NetDv01, CurrencyCode.USD.index(), 100_000.0, -123_000.0, 0);

    final RiskFixture g = new RiskFixture(new RiskLimits().grossPv01(100_000.0));
    g.risk.onFill(BOND10, 100 * M, g.fairMid(BOND10));
    assertEquals(0, g.alerts.size());
    g.risk.onFill(BOND2, -150 * M, g.fairMid(BOND2)); // +28.5k at 2Y: net -53.5k, gross 110.5k
    assertEquals(1, g.alerts.size());
    assertBreach(g, LimitKind.GrossDv01, CurrencyCode.USD.index(), 100_000.0, 110_500.0, 0);
    assertEquals(1L, g.risk.breachCount());
  }

  @Test
  void thePostTradeBucketAlertNamesTheFirstPillarPastItsLimit() {
    final InMemoryInstrumentDirectory dir = new InMemoryInstrumentDirectory();
    dir.add(Instrument.bond(13, "BARBELL", CurrencyCode.USD, 10.0, 25 * M));
    final ParametricSensitivityProvider sens = new ParametricSensitivityProvider(dir);
    final double[] ladder = new double[KeyRateTenors.COUNT];
    ladder[0] = -1.0e-3;
    ladder[KeyRateTenors.COUNT - 1] = -1.0e-3;
    sens.defineLadder(13, ladder, 0.0);
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    final ManualClock clock = new ManualClock(1_000L);
    fair.set(13, Decimal9.round(100.0), 0L);
    final RecordingOutboundSink alerts = new RecordingOutboundSink();
    final RiskEngine risk =
        new RiskEngine(dir, sens, fair, new RiskLimits().bucketPv01(10_000.0),
            new KillSwitchController(new RecordingOutboundSink(), clock), alerts, clock);
    risk.onFill(13, 20 * M, Decimal9.round(100.0)); // -20k at 1Y and at 30Y in one fill
    assertEquals(1, alerts.size());
    final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder();
    d.wrap(alerts.message(0), MessageHeaderEncoder.ENCODED_LENGTH, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
    assertEquals(LimitKind.BucketDv01, d.limitKind());
    assertEquals(0, d.scopeId(), "the first pillar past its limit is named");
    assertEquals(10_000.0, Decimal9.toDouble(d.limitValue().mantissa()), 1e-9);
    assertEquals(-20_000.0, Decimal9.toDouble(d.observedValue().mantissa()), 1e-6);
  }

  @Test
  void dailyLossIsMeasuredFromTheStartOfDayAndReportedWithItsSign() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(4_000.0).killOnDailyLoss(false));
    f.risk.onFill(BOND10, M, Decimal9.round(97.25)); // bought 1 point under fair: +10k
    assertEquals(10_000.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6);
    f.risk.startOfDay();
    f.risk.onFairValue(BOND10, Decimal9.round(97.75)); // -5k on the day, +5k overall
    assertEquals(-5_000.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6);
    assertEquals(1, f.alerts.size());
    assertBreach(f, LimitKind.DailyLoss, CurrencyCode.USD.index(), -4_000.0, -5_000.0, 0);
    assertTrue(f.kill.isActive(), "kill on loss switched off");
    assertEquals(LimitKind.DailyLoss.value(), f.risk.checkOrder(BOND10, Side.Buy, M, f.fairMid(BOND10), 9));
    assertBreach(f, LimitKind.DailyLoss, CurrencyCode.USD.index(), -4_000.0, -5_000.0, 9);

    final RiskFixture inside = new RiskFixture(new RiskLimits().dailyLoss(6_000.0));
    inside.risk.onFill(BOND10, M, Decimal9.round(98.75)); // paid half a point over fair: -5k
    assertEquals(-5_000.0, inside.risk.dailyPnl(CurrencyCode.USD), 1e-6);
    assertEquals(RiskEngine.OK, inside.risk.checkOrder(BOND10, Side.Buy, M, inside.fairMid(BOND10), 1));
    assertEquals(RiskEngine.OK, inside.risk.checkQuote(BOND10, inside.fairMid(BOND10), M, 0L, 0L, 2));
    assertTrue(inside.kill.isActive());
    assertEquals(0, inside.alerts.size());
  }

  @Test
  void aFairValueBeforeAnyFillMarksTheBookFromThen() {
    final RiskFixture f = new RiskFixture();
    f.risk.onFairValue(BOND10, Decimal9.round(98.0));
    assertEquals(0.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-9, "nothing held");
    f.risk.onFill(BOND10, M, Decimal9.round(98.0));
    f.risk.onFairValue(BOND10, Decimal9.round(99.0));
    assertEquals(10_000.0, f.risk.dailyPnl(CurrencyCode.USD), 1e-6, "one point on a million at 0.01 a point");
  }

  @Test
  void basisAndSwapSpreadBreachesAreCountedOnTheEdge() {
    final RiskFixture b = new RiskFixture(new RiskLimits().basisPv01(10_000.0));
    b.risk.onFill(BOND10, 100 * M, b.fairMid(BOND10)); // -82k bonds
    assertEquals(0L, b.risk.basisBreaches());
    b.risk.onFill(FUT10, -50 * M, b.fairMid(FUT10)); // +32.5k futures: basis 32.5k
    assertEquals(32_500.0, b.risk.basisExposure(CurrencyCode.USD), 1e-6);
    assertEquals(1L, b.risk.basisBreaches());
    b.risk.onFill(FUT10, -M, b.fairMid(FUT10));
    assertEquals(1L, b.risk.basisBreaches(), "still past: not counted again");
    b.risk.onFill(FUT10, 51 * M, b.fairMid(FUT10));
    b.risk.onFill(FUT10, -20 * M, b.fairMid(FUT10)); // 13k: past again
    assertEquals(2L, b.risk.basisBreaches());

    final RiskFixture s = new RiskFixture(new RiskLimits().swapSpreadPv01(50_000.0));
    s.risk.onFill(BOND10, 100 * M, s.fairMid(BOND10));
    s.risk.onFill(SWAP10, 50 * M, s.fairMid(SWAP10)); // +41k swaps against -82k bonds
    assertEquals(0L, s.risk.basisBreaches());
    s.risk.onFill(SWAP10, 20 * M, s.fairMid(SWAP10)); // 57.4k
    assertEquals(1L, s.risk.basisBreaches());
  }

  // ---------------------------------------------------------------- risk-reducing exemption

  @Test
  void anOrderThatExactlyFlattensTheBookIsRiskReducing() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(1_000.0).killOnDailyLoss(false));
    f.risk.onFill(BOND10, 100 * M, Decimal9.round(98.35)); // 0.1 over fair on 100mm: -100k
    assertEquals(-f.risk.unitParallelPv01(BOND10) * 100e6, -f.risk.netPv01(CurrencyCode.USD), 0.0);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Sell, 100 * M, f.fairMid(BOND10), 1));
    assertEquals(LimitKind.DailyLoss.value(), f.risk.checkOrder(BOND10, Side.Sell, 100 * M + 1, f.fairMid(BOND10), 2), "overshoot");
  }

  @Test
  void otherWorkingReservationsDecideTheExemptionAndTheInstrumentsOwnDoesNot() {
    final RiskLimits l = new RiskLimits().dailyLoss(1_000.0).killOnDailyLoss(false);
    // long bonds in loss, net -82k; selling 50mm is +41k
    final RiskFixture longBook = new RiskFixture(l);
    longBook.risk.onFill(BOND10, 100 * M, Decimal9.round(98.35));
    final long px = longBook.fairMid(BOND10);
    longBook.risk.reserve(BOND10, 0L, 100 * M); // its own working sell, left out
    assertEquals(RiskEngine.OK, longBook.risk.checkOrder(BOND10, Side.Sell, 50 * M, px, 1));
    longBook.risk.reserve(FUT10, 0L, 100 * M); // +65k elsewhere: -82 + 65 + 41 overshoots
    assertEquals(LimitKind.DailyLoss.value(), longBook.risk.checkOrder(BOND10, Side.Sell, 50 * M, px, 2));

    // the mirror: short bonds in loss, net +82k; buying 50mm is -41k
    final RiskFixture shortBook = new RiskFixture(new RiskLimits().dailyLoss(1_000.0).killOnDailyLoss(false));
    shortBook.risk.onFill(BOND10, -100 * M, Decimal9.round(98.15));
    shortBook.risk.reserve(BOND10, 100 * M, 0L);
    assertEquals(RiskEngine.OK, shortBook.risk.checkOrder(BOND10, Side.Buy, 50 * M, px, 3));
    shortBook.risk.reserve(FUT10, 100 * M, 0L); // -65k elsewhere
    assertEquals(LimitKind.DailyLoss.value(), shortBook.risk.checkOrder(BOND10, Side.Buy, 50 * M, px, 4));
    shortBook.risk.release(FUT10, 100 * M, 0L);
    shortBook.risk.reserve(FUT10, 40 * M, 0L); // -26k: +82 - 26 - 41 stays above zero
    assertEquals(RiskEngine.OK, shortBook.risk.checkOrder(BOND10, Side.Buy, 50 * M, px, 5));
  }

  // ---------------------------------------------------------------- size, price and DV01 boundaries

  @Test
  void eachSizeAndPriceLimitIsInclusive() {
    final RiskFixture f =
        new RiskFixture(new RiskLimits().maxOrderSize(200 * M).fatFingerFraction(0.5).priceCollarBp(0.01 / 8.2e-4));
    f.fair.set(BOND10, Decimal9.round(100.0), 0L);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, 200 * M, Decimal9.round(100.0), 1));
    assertEquals(LimitKind.MaxOrderSize.value(), f.risk.checkOrder(BOND10, Side.Buy, 200 * M + 1, Decimal9.round(100.0), 2));
    assertEquals(LimitKind.MaxOrderSize.value(), f.risk.checkQuote(BOND10, Decimal9.round(100.0), -1L, 0L, 0L, 3), "negative size");
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, M, Decimal9.round(101.0), 4), "exactly one point is the collar");
    assertEquals(LimitKind.PriceCollar.value(), f.risk.checkOrder(BOND10, Side.Buy, M, Decimal9.round(101.001), 5));

    final RiskFixture ff = new RiskFixture(new RiskLimits().fatFingerFraction(0.5));
    ff.fair.set(BOND10, Decimal9.round(100.0), 0L);
    assertEquals(RiskEngine.OK, ff.risk.checkOrder(BOND10, Side.Buy, M, Decimal9.round(150.0), 6), "exactly 50% off");
    assertEquals(LimitKind.FatFinger.value(), ff.risk.checkOrder(BOND10, Side.Buy, M, Decimal9.round(150.01), 7));
    assertBreach(ff, LimitKind.FatFinger, BOND10, 0.5, 0.5001, 7);
  }

  @Test
  void theDv01LimitsAreInclusiveAndCountEveryRefusal() {
    final RiskFixture f = new RiskFixture();
    final double at48 = f.risk.dv01(BOND10, 48 * M);
    final RiskFixture r = new RiskFixture(new RiskLimits().rfqDv01(at48).clientDv01(at48 + 10_000.0));
    final long px = r.fairMid(BOND10);
    assertEquals(RiskEngine.OK, r.risk.checkQuote(3, BOND10, px, 48 * M, 0L, 0L, 1), "exactly the per-RFQ limit");
    r.risk.onClientFill(3, 10_000.0);
    assertEquals(RiskEngine.OK, r.risk.checkQuote(3, BOND10, px, 48 * M, 0L, 0L, 2), "exactly the per-client limit");
    r.risk.reserveClient(3, 1.0);
    assertEquals(RiskEngine.REJECT_CLIENT_DV01, r.risk.checkQuote(3, BOND10, px, 48 * M, 0L, 0L, 3));
    assertEquals(1L, r.risk.dv01Rejects());
    assertEquals(1L, r.risk.preTradeRejects());
    r.risk.countClientDv01Reject();
    assertEquals(2L, r.risk.dv01Rejects());
    assertEquals(2L, r.risk.preTradeRejects());
  }

  @Test
  void clientZeroLivesInSlotZeroAndIsTrackedLikeAnyOther() {
    final RiskFixture f = new RiskFixture(new RiskLimits().clientDv01(100_000.0));
    f.risk.reserveClient(0, 30_000.0);
    f.risk.onClientFill(0, 50_000.0);
    assertEquals(30_000.0, f.risk.clientOpenDv01(0), 0.0);
    assertEquals(50_000.0, f.risk.clientDealtDv01(0), 0.0);
    assertEquals(20_000.0, f.risk.clientRoomDv01(0), 1e-9);
    final long px = f.fairMid(BOND10);
    // 30mm of the 10Y is 24.6k: past the 20k of room left
    assertEquals(RiskEngine.REJECT_CLIENT_DV01, f.risk.checkQuote(0, BOND10, px, 30 * M, 0L, 0L, 1));
    assertEquals(20_000.0 / 820.0, (double) f.risk.quoteHeadroom(0, BOND10, Side.Buy) / M, 1e-6);
    f.risk.releaseClient(0, 10_000.0);
    assertEquals(20_000.0, f.risk.clientOpenDv01(0), 0.0);
    f.risk.releaseClient(0, 50_000.0);
    assertEquals(0.0, f.risk.clientOpenDv01(0), 0.0, "never below zero");
  }

  @Test
  void clientRoomIsTheLimitLessDealtAndLiveAndZeroForAClientTheFullTableRefuses() {
    assertEquals(Double.MAX_VALUE, new RiskFixture().risk.clientRoomDv01(7), 0.0, "no limit");
    final RiskFixture f = new RiskFixture(new RiskLimits().clientDv01(100_000.0));
    assertEquals(100_000.0, f.risk.clientRoomDv01(7), 0.0, "unknown client: the whole limit");
    f.risk.onClientFill(7, 30_000.0);
    f.risk.reserveClient(7, 25_000.0);
    assertEquals(45_000.0, f.risk.clientRoomDv01(7), 1e-9);
    f.risk.onClientFill(7, 80_000.0);
    assertEquals(0.0, f.risk.clientRoomDv01(7), 0.0, "past the limit: no room, not negative room");
    for (int c = 0; c < RiskEngine.MAX_CLIENTS; c++) {
      f.risk.onClientFill(10_000 + c, 1.0); // fills the rest of the table
    }
    assertEquals(0.0, f.risk.clientRoomDv01(5), 0.0, "no slot: no room");
    assertEquals(0.0, f.risk.clientRoomDv01(7), 0.0);
  }

  @Test
  void aFullTableStillTracksTheClientInSlotZero() {
    final RiskFixture f = new RiskFixture(new RiskLimits().clientDv01(100_000.0));
    for (int c = 0; c < RiskEngine.MAX_CLIENTS; c++) {
      f.risk.onClientFill(c, c);
    }
    for (int c = 0; c < RiskEngine.MAX_CLIENTS; c += 97) {
      assertEquals(c, f.risk.clientDealtDv01(c), 0.0, "client " + c);
    }
    assertEquals(RiskEngine.OK, f.risk.checkQuote(0, BOND10, f.fairMid(BOND10), M, 0L, 0L, 1));
    assertEquals(RiskEngine.REJECT_CLIENT_DV01, f.risk.checkQuote(RiskEngine.MAX_CLIENTS, BOND10, f.fairMid(BOND10), M, 0L, 0L, 2));
  }

  @Test
  void quoteHeadroomUsesDealtPlusLiveAndIsPlainHeadroomWithoutDv01Limits() {
    final RiskFixture plain = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    assertEquals(plain.risk.headroom(BOND10, Side.Buy), plain.risk.quoteHeadroom(4, BOND10, Side.Buy));
    final RiskFixture f = new RiskFixture(new RiskLimits().clientDv01(100_000.0));
    f.risk.onClientFill(4, 30_000.0);
    f.risk.reserveClient(4, 29_000.0);
    // 41k of room is 50mm of the 10Y
    assertEquals(50.0, (double) f.risk.quoteHeadroom(4, BOND10, Side.Buy) / M, 1e-6);
    assertEquals(Long.MAX_VALUE, f.risk.quoteHeadroom(RiskEngine.NO_CLIENT, BOND10, Side.Buy), "no client: no client limit");
  }

  // ---------------------------------------------------------------- rates, unknowns, reads

  @Test
  void limitsChangedRefillsEveryBucketAtItsNewRate() {
    final RiskLimits l =
        new RiskLimits().orderRatePerSecond(1.0).quoteRatePerSecond(1.0).messageRatePerSecond(1_000.0).burstSeconds(1.0);
    final RiskFixture f = new RiskFixture(l);
    final long px = f.fairMid(BUND);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BUND, Side.Buy, M, px, 1));
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, px, M, 0L, 0L, 2));
    l.orderRatePerSecond(3.0).quoteRatePerSecond(4.0);
    f.risk.limitsChanged();
    for (int n = 0; n < 3; n++) {
      assertEquals(RiskEngine.OK, f.risk.checkOrder(BUND, Side.Buy, M, px, 3));
    }
    assertEquals(LimitKind.OrderRate.value(), f.risk.checkOrder(BUND, Side.Buy, M, px, 4));
    assertBreach(f, LimitKind.OrderRate, 0, 3.0, 3.0, 4);
    for (int n = 0; n < 4; n++) {
      assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, px, M, 0L, 0L, 5));
    }
    assertEquals(LimitKind.QuoteRate.value(), f.risk.checkQuote(BUND, px, M, 0L, 0L, 6));

    final RiskLimits m = new RiskLimits().messageRatePerSecond(2.0).burstSeconds(1.0);
    final RiskFixture g = new RiskFixture(m);
    g.risk.checkOrder(BUND, Side.Buy, M, px, 1);
    g.risk.checkOrder(BUND, Side.Buy, M, px, 2);
    assertEquals(LimitKind.MessageRate.value(), g.risk.checkOrder(BUND, Side.Buy, M, px, 3));
    m.messageRatePerSecond(5.0);
    g.risk.limitsChanged();
    for (int n = 0; n < 5; n++) {
      assertEquals(RiskEngine.OK, g.risk.checkOrder(BUND, Side.Buy, M, px, 4));
    }
    assertEquals(LimitKind.MessageRate.value(), g.risk.checkQuote(BUND, px, M, 0L, 0L, 5));
  }

  @Test
  void exemptMessagesUseUpTheMessageRate() {
    final RiskFixture f = new RiskFixture(new RiskLimits().messageRatePerSecond(3.0).burstSeconds(1.0));
    f.risk.countExemptMessage();
    f.risk.countExemptMessage();
    final long px = f.fairMid(BUND);
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BUND, px, M, 0L, 0L, 1));
    assertEquals(LimitKind.MessageRate.value(), f.risk.checkQuote(BUND, px, M, 0L, 0L, 2));
  }

  @Test
  void anExemptTakeOnAFullBucketRefillsFirst() {
    final TokenBucket b = new TokenBucket(1.0, 2.0, 0L);
    b.takeExempt(5_000_000_000L);
    assertEquals(1.0, b.tokens(5_000_000_000L), 1e-12, "full at 2, less one");
  }

  @Test
  void anUnknownInstrumentIsRefusedAndCountedByEveryCheck() {
    final RiskFixture f = new RiskFixture();
    assertEquals(RiskEngine.REJECT_UNKNOWN, f.risk.checkOrder(99, Side.Buy, M, Decimal9.round(100.0), 1));
    assertEquals(1L, f.risk.preTradeRejects());
    assertEquals(0L, f.risk.headroom(99, Side.Buy));
    assertEquals(0, f.alerts.size());
  }

  @Test
  void readsReturnWhatTheEngineHolds() {
    final RiskFixture f = new RiskFixture();
    f.risk.onFill(BOND10, -7 * M, f.fairMid(BOND10));
    f.risk.reserve(BOND10, 3 * M, 5 * M);
    assertEquals(-7 * M, f.risk.position(BOND10));
    assertEquals(3 * M, f.risk.pendingBuy(BOND10));
    assertEquals(5 * M, f.risk.pendingSell(BOND10));
    assertEquals(-8.2e-4, f.risk.unitPv01(BOND10, 5), 1e-15);
    assertEquals(0.0, f.risk.unitPv01(BOND10, 4), 0.0);
    assertSame(f.limits, f.risk.limits());
    assertSame(f.kill, f.risk.killSwitch());
    assertSame(f.directory, f.risk.directory());
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.EUR, v);
    assertEquals(CurrencyCode.EUR, v.currency());
    assertFalse(f.risk.flattenOnly());
  }

  @Test
  void curveSpreadExposureWeighsTwoYearsDownAndTenYearsUpWithSevenYearsInterpolated() {
    final RiskFixture f = new RiskFixture();
    f.risk.onFill(BOND2, 100 * M, f.fairMid(BOND2)); // -19k at 2Y, weight -0.5
    f.risk.onFill(FUT10, 100 * M, f.fairMid(FUT10)); // -65k at 7Y, weight -0.5 + 5/8 = 0.125
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10)); // -82k at 10Y, weight 0.5
    assertEquals(9_500.0 - 8_125.0 - 41_000.0, f.risk.curveSpreadExposure(CurrencyCode.USD), 1e-6);
  }

  @Test
  void aCurveMoveRevaluesReservationsAsWellAsPositions() {
    final RiskFixture moved = new RiskFixture(new RiskLimits().bucketPv01(100_000.0));
    moved.risk.reserve(BOND10, 50 * M, 0L);
    moved.sensitivities.shiftCurve(CurrencyCode.USD, -50.0); // PV01 x 1.1
    moved.risk.onCurveMove(CurrencyCode.USD);
    assertEquals(-8.2e-4 * 1.1, moved.risk.unitParallelPv01(BOND10), 1e-15);
    // the bond reservation is now -45.1k at 10Y; selling the swap adds -902 per million there:
    // (100k - 45.1k) / 902 = 100k / 902 - 50 millions
    // with no position or order-size limit the bisection stops 2^15 minor units short at most
    final double room = (double) moved.risk.headroom(SWAP10, Side.Sell) / M;
    assertTrue(room <= 100_000.0 / 902.0 - 50.0 && room > 100_000.0 / 902.0 - 50.0 - 32_768.0 / M, "room " + room);
  }

  // ---------------------------------------------------------------- exactly at a limit

  @Test
  void aPreTradeFigureExactlyAtItsLimitMayStayThereButNotGrowByAnyAmount() {
    // short 100mm of the 10Y: +82k at the pillar, net and gross; every limit set to that figure
    final RiskFixture s = new RiskFixture();
    s.risk.onFill(BOND10, -100 * M, s.fairMid(BOND10));
    final double at = s.risk.netPv01(CurrencyCode.USD);
    s.limits.bucketPv01(at).netPv01(at).grossPv01(at);
    assertEquals(RiskEngine.OK, s.risk.checkOrder(BOND10, Side.Buy, 10 * M, s.fairMid(BOND10), 1), "reducing");
    assertEquals(LimitKind.BucketDv01.value(), s.risk.checkOrder(BOND10, Side.Sell, 1L, s.fairMid(BOND10), 2),
        "one cent more is past the limit: no rounding allowance below it");

    final RiskFixture l = new RiskFixture();
    l.risk.onFill(BOND10, 100 * M, l.fairMid(BOND10));
    final double lat = -l.risk.netPv01(CurrencyCode.USD);
    l.limits.bucketPv01(lat).netPv01(lat).grossPv01(lat);
    assertEquals(RiskEngine.OK, l.risk.checkOrder(BOND10, Side.Sell, 10 * M, l.fairMid(BOND10), 3));

    final RiskFixture n = new RiskFixture();
    n.risk.onFill(BOND10, -100 * M, n.fairMid(BOND10));
    n.limits.netPv01(n.risk.netPv01(CurrencyCode.USD));
    assertEquals(RiskEngine.OK, n.risk.checkOrder(BOND10, Side.Buy, 10 * M, n.fairMid(BOND10), 4));
    final RiskFixture nl = new RiskFixture();
    nl.risk.onFill(BOND10, 100 * M, nl.fairMid(BOND10));
    nl.limits.netPv01(-nl.risk.netPv01(CurrencyCode.USD));
    assertEquals(RiskEngine.OK, nl.risk.checkOrder(BOND10, Side.Sell, 10 * M, nl.fairMid(BOND10), 5));
    final RiskFixture g = new RiskFixture();
    g.risk.onFill(BOND10, -100 * M, g.fairMid(BOND10));
    g.limits.grossPv01(g.risk.grossPv01(CurrencyCode.USD));
    assertEquals(RiskEngine.OK, g.risk.checkOrder(BOND10, Side.Buy, 10 * M, g.fairMid(BOND10), 6));
  }

  @Test
  void postTradeFiguresExactlyAtTheirLimitsAreNotBreaches() {
    final RiskFixture f = new RiskFixture(new RiskLimits().killOnDailyLoss(false));
    f.risk.onFill(BOND10, 100 * M, Decimal9.round(98.35)); // -82k, and a loss of 100k
    f.risk.onFill(FUT10, -50 * M, f.fairMid(FUT10)); // basis 32.5k
    f.risk.onFill(SWAP10, 50 * M, f.fairMid(SWAP10)); // swap spread: +41k against -49.5k of cash
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, v);
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      if (v.get(k) != 0.0) {
        f.limits.bucketPv01(k, Math.abs(v.get(k)));
      }
    }
    f.limits
        .netPv01(Math.abs(f.risk.netPv01(CurrencyCode.USD)))
        .grossPv01(f.risk.grossPv01(CurrencyCode.USD))
        .dailyLoss(-f.risk.dailyPnl(CurrencyCode.USD))
        .basisPv01(Math.abs(f.risk.basisExposure(CurrencyCode.USD)))
        .swapSpreadPv01(Math.abs(f.risk.swapSpreadExposure(CurrencyCode.USD)));
    final int before = f.alerts.size();
    f.risk.onFairValue(BOND10, f.fairMid(BOND10)); // the mark it already has: re-runs the checks
    assertEquals(before, f.alerts.size(), "nothing past its limit");
    assertEquals(0L, f.risk.basisBreaches());
  }

  @Test
  void aDailyLossExactlyAtTheLimitStillTrades() {
    final RiskFixture f = new RiskFixture(new RiskLimits().killOnDailyLoss(false));
    f.risk.onFill(BOND10, M, Decimal9.round(98.75));
    f.limits.dailyLoss(-f.risk.dailyPnl(CurrencyCode.USD));
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, M, f.fairMid(BOND10), 1));
  }

  @Test
  void basisAndSwapSpreadChecksExactlyAtTheLimitPass() {
    final RiskFixture probe = new RiskFixture();
    probe.risk.onFill(FUT10, -50 * M, probe.fairMid(FUT10));
    probe.risk.onFill(BOND10, 100 * M, probe.fairMid(BOND10));
    final RiskFixture b = new RiskFixture(new RiskLimits().basisPv01(Math.abs(probe.risk.basisExposure(CurrencyCode.USD))));
    b.risk.onFill(FUT10, -50 * M, b.fairMid(FUT10));
    assertEquals(RiskEngine.OK, b.risk.checkOrder(BOND10, Side.Buy, 100 * M, b.fairMid(BOND10), 1));

    final RiskFixture sprobe = new RiskFixture();
    sprobe.risk.onFill(BOND10, 100 * M, sprobe.fairMid(BOND10));
    sprobe.risk.onFill(SWAP10, 50 * M, sprobe.fairMid(SWAP10));
    final RiskFixture s =
        new RiskFixture(new RiskLimits().swapSpreadPv01(Math.abs(sprobe.risk.swapSpreadExposure(CurrencyCode.USD))));
    s.risk.onFill(BOND10, 100 * M, s.fairMid(BOND10));
    assertEquals(RiskEngine.OK, s.risk.checkOrder(SWAP10, Side.Buy, 50 * M, s.fairMid(SWAP10), 2));
  }

  @Test
  void ordersInEachProductAreCheckedForTheBasisTheyOpen() {
    // a bond bought against short futures: +32.5k futures against -41k bonds
    final RiskFixture b = new RiskFixture(new RiskLimits().basisPv01(20_000.0));
    b.risk.onFill(FUT10, -50 * M, b.fairMid(FUT10));
    assertEquals(RiskEngine.REJECT_BASIS, b.risk.checkOrder(BOND10, Side.Buy, 50 * M, b.fairMid(BOND10), 1));
    assertEquals(RiskEngine.OK, b.risk.checkOrder(BOND10, Side.Buy, 20 * M, b.fairMid(BOND10), 2), "16.4k");

    // a payer swap bought against long bonds: +41k swaps against -82k cash
    final RiskFixture s = new RiskFixture(new RiskLimits().swapSpreadPv01(30_000.0));
    s.risk.onFill(BOND10, 100 * M, s.fairMid(BOND10));
    assertEquals(RiskEngine.REJECT_SWAP_SPREAD, s.risk.checkOrder(SWAP10, Side.Buy, 50 * M, s.fairMid(SWAP10), 3));
    assertEquals(RiskEngine.OK, s.risk.checkOrder(SWAP10, Side.Buy, 30 * M, s.fairMid(SWAP10), 4), "24.6k");

    // cash is bonds plus futures: +82k swaps against -13k futures and a -41k bond order is 54k
    final RiskFixture c = new RiskFixture(new RiskLimits().swapSpreadPv01(40_000.0));
    c.risk.onFill(SWAP10, 100 * M, c.fairMid(SWAP10));
    c.risk.onFill(FUT10, 20 * M, c.fairMid(FUT10));
    assertEquals(RiskEngine.REJECT_SWAP_SPREAD, c.risk.checkOrder(BOND10, Side.Buy, 50 * M, c.fairMid(BOND10), 5));
    assertEquals(RiskEngine.OK, c.risk.checkOrder(BOND10, Side.Buy, 30 * M, c.fairMid(BOND10), 6), "37.6k");
  }

  @Test
  void aShortBookBoughtExactlyFlatIsRiskReducingAndAZeroOrderIsNot() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(1_000.0).killOnDailyLoss(false));
    f.risk.onFill(BOND10, -100 * M, Decimal9.round(98.15)); // sold 0.1 under fair: -100k
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, 100 * M, f.fairMid(BOND10), 1));
    assertEquals(LimitKind.DailyLoss.value(), f.risk.checkOrder(BOND10, Side.Sell, 0L, f.fairMid(BOND10), 2),
        "an order of nothing reduces nothing");
  }

  @Test
  void anAbsentClientReadsAsZeroWhateverIsInSlotZero() {
    final RiskFixture f = new RiskFixture();
    f.risk.onClientFill(0, 5.0);
    f.risk.reserveClient(0, 7.0);
    assertEquals(0.0, f.risk.clientDealtDv01(12_345), 0.0);
    assertEquals(0.0, f.risk.clientOpenDv01(12_345), 0.0);
  }

  @Test
  void headroomCountsReservationsOnBothSidesAgainstThePositionLimit() {
    final RiskFixture f = new RiskFixture(new RiskLimits().defaultMaxPosition(100 * M));
    f.risk.onFill(BOND10, 30 * M, f.fairMid(BOND10));
    f.risk.reserve(BOND10, 20 * M, 10 * M);
    assertEquals(50 * M, f.risk.headroom(BOND10, Side.Buy), "100 - 30 held - 20 bid");
    assertEquals(120 * M, f.risk.headroom(BOND10, Side.Sell), "100 + 30 held - 10 offered");
  }

  /**
   * Headroom is documented as the largest additional quantity that passes. With no position and no
   * order-size limit the bisection runs over [0, Long.MAX_VALUE] and stops after 48 halvings, so it
   * can land up to 2^15 minor units (about 328 of face) short of the edge. The error is on the safe
   * side, but the quoting engine sizes from it.
   */
  @Test
  @Disabled(
      "defect, round 7: RiskEngine.headroom (RiskEngine.java:529) stops the bisection after 48"
          + " iterations; with no position or order-size limit the range is 2^63 wide, so the result"
          + " can be 2^15 minor units below the edge (measured: 6,097,534,975 where one more unit"
          + " still passes). Fix: iterate until hi - lo <= 1 (at most 64 halvings) or bisect over"
          + " min(room, the PV01-implied bound). Enable this test with the fix.")
  void headroomWithOnlyAPv01LimitIsTheExactEdge() {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(50_000.0));
    final long h = f.risk.headroom(BOND10, Side.Buy);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Buy, h, f.fairMid(BOND10), 1));
    assertEquals(LimitKind.BucketDv01.value(), f.risk.checkOrder(BOND10, Side.Buy, h + 1, f.fairMid(BOND10), 2), "h = " + h);
  }

  private static void assertBreach(
      final RiskFixture f, final LimitKind kind, final int scope, final double limit, final double observed, final long seq) {
    final RiskLimitBreachDecoder d = new RiskLimitBreachDecoder();
    d.wrap(f.alerts.message(f.alerts.size() - 1), MessageHeaderEncoder.ENCODED_LENGTH, RiskLimitBreachDecoder.BLOCK_LENGTH, 0);
    assertEquals(kind, d.limitKind());
    assertEquals(scope, d.scopeId(), "scope");
    assertEquals(seq, d.rejectedMessageSeqNo(), "sequence number");
    assertEquals(limit, Decimal9.toDouble(d.limitValue().mantissa()), 1e-6, "limit");
    assertEquals(observed, Decimal9.toDouble(d.observedValue().mantissa()), 1e-6, "observed");
  }
}
