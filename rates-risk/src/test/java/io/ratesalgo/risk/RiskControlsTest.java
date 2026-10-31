package io.ratesalgo.risk;

import static io.ratesalgo.risk.RiskFixture.BOND10;
import static io.ratesalgo.risk.RiskFixture.BOND2;
import static io.ratesalgo.risk.RiskFixture.FUT10;
import static io.ratesalgo.risk.RiskFixture.M;
import static io.ratesalgo.risk.RiskFixture.SWAP10;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.LimitKind;
import io.ratesalgo.messages.sbe.Side;
import org.junit.jupiter.api.Test;

/**
 * PNL_AUDIT.md items 14 and 16: risk-reducing flow passes a breached limit, the daily-loss kill
 * still lets the book be flattened, and basis and swap-spread exposure have limits.
 */
class RiskControlsTest {

  /**
   * The breach scenario of item 14: the 10Y and 2Y pillars are both past their limits and net PV01
   * is almost flat, so the old net-only exemption never applied and headroom was zero on both
   * sides. A client buying the 10Y bond from us reduces the breach and is now quoted and accepted;
   * a client selling it to us would add to it and is refused.
   */
  @Test
  void clientFlowThatReducesABucketBreachIsAcceptedWithNetFlat() {
    final RiskFixture f = new RiskFixture(new RiskLimits().bucketPv01(50_000.0).netPv01(20_000.0));
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10)); // -82k at 10Y
    f.risk.onFill(BOND2, -400 * M, f.fairMid(BOND2)); // +76k at 2Y, net -6k
    assertEquals(-6_000.0, f.risk.netPv01(CurrencyCode.USD), 1e-6);
    final long px = f.fairMid(BOND10);

    final long sellRoom = f.risk.headroom(BOND10, Side.Sell);
    assertTrue(sellRoom > 0, "selling 10Y reduces the 10Y breach");
    // stops where net PV01 reaches its +20k limit: (20k + 6k) / 820 per million
    assertEquals(26_000.0 / 820.0, (double) sellRoom / M, 0.01);
    assertEquals(0L, f.risk.headroom(BOND10, Side.Buy), "buying 10Y adds to the breach");

    // RFQ: the client buys 10m from us, so we quote an offer and sell
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BOND10, 0L, 0L, px, 10 * M, 1));
    f.risk.reserve(BOND10, 0L, 10 * M);
    f.risk.release(BOND10, 0L, 10 * M);
    f.risk.onFill(BOND10, -10 * M, px);
    assertEquals(2_200.0, f.risk.netPv01(CurrencyCode.USD), 1e-6, "-6k plus 8.2k");

    // the client sells to us: refused, and the breach message names the 10Y pillar
    assertEquals(LimitKind.BucketDv01.value(), f.risk.checkQuote(BOND10, px, M, 0L, 0L, 2));
    // overshooting through zero into a breach on the other side is refused too
    assertEquals(LimitKind.NetDv01.value(), f.risk.checkQuote(BOND10, 0L, 0L, px, 60 * M, 3));
    // a hedge in the 2Y that reduces the 2Y breach passes although net PV01 moves away from zero
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND2, Side.Buy, 50 * M, f.fairMid(BOND2), 4));
  }

  @Test
  void aBreachMayShrinkButNotGrowGrossOrNet() {
    final RiskFixture f = new RiskFixture(new RiskLimits().grossPv01(50_000.0));
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10)); // gross 82k
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BOND10, 0L, 0L, f.fairMid(BOND10), 20 * M, 1));
    assertEquals(LimitKind.GrossDv01.value(), f.risk.checkQuote(BOND2, f.fairMid(BOND2), M, 0L, 0L, 2));
    final RiskFixture g = new RiskFixture(new RiskLimits().netPv01(50_000.0));
    g.risk.onFill(BOND10, 100 * M, g.fairMid(BOND10)); // net -82k
    assertEquals(RiskEngine.OK, g.risk.checkQuote(BOND10, 0L, 0L, g.fairMid(BOND10), 20 * M, 1));
    assertEquals(LimitKind.NetDv01.value(), g.risk.checkQuote(BOND10, g.fairMid(BOND10), M, 0L, 0L, 2));
  }

  /**
   * With the kill on daily loss turned off, the loss limit still blocks new risk, but a one-sided
   * quote that reduces net PV01 is not new risk and passes.
   */
  @Test
  void dailyLossBlocksAddingQuotesButNotReducingOnes() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(50_000.0).killOnDailyLoss(false));
    f.risk.startOfDay();
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    f.fair.set(BOND10, Decimal9.round(97.0), f.clock.epochNanos());
    f.risk.onFairValue(BOND10, Decimal9.round(97.0));
    assertTrue(f.risk.dailyPnl(CurrencyCode.USD) < -50_000.0);
    assertTrue(f.kill.isActive());
    final long px = f.fairMid(BOND10);
    assertEquals(LimitKind.DailyLoss.value(), f.risk.checkQuote(BOND10, px, M, 0L, 0L, 1));
    assertEquals(LimitKind.DailyLoss.value(), f.risk.checkQuote(BOND10, px, M, px, M, 2), "two-way is not reducing");
    assertEquals(RiskEngine.OK, f.risk.checkQuote(BOND10, 0L, 0L, px, M, 3));
  }

  /**
   * Item 14, third point: the daily-loss kill pulls quotes and refuses new risk, but orders that
   * reduce the position still pass so the hedgers can flatten. Any other kill refuses everything.
   */
  @Test
  void theDailyLossKillStillAcceptsRiskReducingOrders() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(50_000.0));
    f.risk.startOfDay();
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    assertFalse(f.risk.flattenOnly());
    f.fair.set(BOND10, Decimal9.round(97.0), f.clock.epochNanos());
    f.risk.onFairValue(BOND10, Decimal9.round(97.0));
    assertEquals(KillState.KILLED, f.kill.state());
    assertTrue(f.risk.flattenOnly());
    final long px = f.fairMid(BOND10);
    assertEquals(RiskEngine.OK, f.risk.checkOrder(BOND10, Side.Sell, 50 * M, px, 1), "reduces: passes");
    assertEquals(RiskEngine.OK, f.risk.checkOrder(FUT10, Side.Sell, 50 * M, f.fairMid(FUT10), 2), "hedge in futures");
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkOrder(BOND10, Side.Buy, M, px, 3), "adds: refused");
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkOrder(BOND10, Side.Sell, 200 * M, px, 4), "overshoots");
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkQuote(BOND10, 0L, 0L, px, M, 5), "quotes stay pulled");
    assertEquals(0L, f.risk.headroom(BOND10, Side.Sell));
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkOrder(99, Side.Sell, M, px, 6), "kill is checked first");

    // reset, then a manual kill: nothing passes, not even a reducing order
    f.kill.requestReset(1);
    assertTrue(f.kill.approveReset(2, true));
    assertFalse(f.risk.flattenOnly());
    f.kill.trigger(KillReason.Manual, 0, 1);
    assertFalse(f.risk.flattenOnly());
    assertEquals(RiskEngine.REJECT_KILLED, f.risk.checkOrder(BOND10, Side.Sell, 50 * M, px, 7));
  }

  @Test
  void aDailyLossWhileAlreadyKilledDoesNotTurnTheKillIntoFlattening() {
    final RiskFixture f = new RiskFixture(new RiskLimits().dailyLoss(50_000.0));
    f.risk.startOfDay();
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10));
    f.kill.trigger(KillReason.Manual, 0, 1);
    f.risk.onFairValue(BOND10, Decimal9.round(97.0));
    assertEquals(KillReason.Manual, f.kill.lastReason());
    assertFalse(f.risk.flattenOnly());
  }

  /**
   * Item 16: hedging 10Y cash with futures opens futures-versus-cash basis, and hedging a swap
   * with bonds opens swap-spread exposure. Both are limited, even for orders that reduce net PV01;
   * an exposure already past its limit may shrink.
   */
  @Test
  void basisAndSwapSpreadLimitsRefuseHedgesThatOpenTooMuchBasis() {
    final RiskFixture f = new RiskFixture(new RiskLimits().basisPv01(10_000.0).swapSpreadPv01(10_000.0));
    f.risk.onFill(BOND10, 100 * M, f.fairMid(BOND10)); // bonds -82k
    final long fut = f.fairMid(FUT10);
    assertEquals(RiskEngine.REJECT_BASIS, f.risk.checkOrder(FUT10, Side.Sell, 100 * M, fut, 1), "65k of basis");
    assertEquals(RiskEngine.OK, f.risk.checkOrder(FUT10, Side.Sell, 10 * M, fut, 2), "6.5k of basis");
    assertEquals(RiskEngine.REJECT_BASIS, f.risk.checkQuote(FUT10, 0L, 0L, fut, 100 * M, 3));
    assertEquals(0L, f.risk.basisBreaches());

    final RiskFixture g = new RiskFixture(new RiskLimits().swapSpreadPv01(10_000.0));
    g.risk.onFill(SWAP10, 100 * M, g.fairMid(SWAP10)); // swaps +82k
    final long bond = g.fairMid(BOND10);
    assertEquals(RiskEngine.REJECT_SWAP_SPREAD, g.risk.checkOrder(BOND10, Side.Buy, 100 * M, bond, 1));
    assertEquals(RiskEngine.OK, g.risk.checkOrder(BOND10, Side.Buy, 10 * M, bond, 2));
    g.risk.onFill(BOND10, 50 * M, bond); // a client trade: post-trade breach, counted once
    assertEquals(1L, g.risk.basisBreaches());
    g.risk.onFill(BOND10, M, bond);
    assertEquals(1L, g.risk.basisBreaches(), "edge-triggered");
    // past the limit, an order that shrinks the spread passes and one that grows it does not
    assertEquals(RiskEngine.OK, g.risk.checkOrder(BOND10, Side.Sell, 20 * M, bond, 3));
    assertEquals(RiskEngine.REJECT_SWAP_SPREAD, g.risk.checkOrder(BOND10, Side.Buy, 20 * M, bond, 4));
    // a product with no basis is not checked
    assertEquals(RiskEngine.OK, g.risk.checkOrder(RiskFixture.BUND, Side.Buy, M, g.fairMid(RiskFixture.BUND), 5));
  }

  @Test
  void basisLimitsValidate() {
    final RiskLimits l = new RiskLimits();
    assertEquals(Double.MAX_VALUE, l.basisPv01());
    assertEquals(Double.MAX_VALUE, l.swapSpreadPv01());
    assertEquals(5.0, l.basisPv01(5.0).basisPv01());
    assertEquals(6.0, l.swapSpreadPv01(6.0).swapSpreadPv01());
    assertThrows(IllegalArgumentException.class, () -> l.basisPv01(0.0));
    assertThrows(IllegalArgumentException.class, () -> l.swapSpreadPv01(-1.0));
  }
}
