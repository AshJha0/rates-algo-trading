package io.ratesalgo.quoting;

import static io.ratesalgo.quoting.TestMarket.FACE_1M;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.messages.sbe.KillReason;
import io.ratesalgo.messages.sbe.Side;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Quote invariants of the Round 5 desk ({@link StrategyConfig#allOn}): never below break-even,
 * our side of the reservation price, on the venue tick in our favour, monotone in inventory and
 * in size, and no quote when killed or unhealthy.
 */
class StrategyPropertyTest {

  private static final int[] USD_RFQ = {
    TestMarket.UST2, TestMarket.UST5, TestMarket.UST10, TestMarket.UST30,
    TestMarket.SW2, TestMarket.SW5, TestMarket.SW10, TestMarket.SW30
  };
  private static final double EPS = 1e-9;

  private long rfqId;

  private static TestMarket market() {
    return new TestMarket(
        TestMarket.standardLimits().defaultMaxPosition(100_000 * FACE_1M).bucketPv01(1e12).netPv01(1e12).grossPv01(1e12),
        new QuotingConfig().requoteThrottleNanos(0L).strategy(StrategyConfig.allOn()),
        Hedger.config());
  }

  private RfqOutcome ask(final TestMarket m, final int client, final int id, final boolean clientBuys, final long faceMm) {
    m.desk.quoting().onQuoteRequest(7, ++rfqId, client, id, clientBuys ? Side.Buy : Side.Sell, faceMm * FACE_1M, 4, 0L, rfqId);
    return m.desk.quoting().outcome();
  }

  @Property(tries = 150)
  void everyQuoteIsAboveBreakEvenOnOurSideOfTheReservationAndOnTheTick(
      @ForAll @IntRange(min = 0, max = 7) final int which,
      @ForAll @IntRange(min = -800, max = 800) final int inventoryMm,
      @ForAll @IntRange(min = 1, max = 300) final int sizeMm,
      @ForAll final boolean clientBuys,
      @ForAll @IntRange(min = 1, max = 3) final int client) {
    final TestMarket m = market();
    final int id = USD_RFQ[which];
    m.desk.risk().onFill(TestMarket.UST10, inventoryMm * FACE_1M, m.fair.fairMid(TestMarket.UST10));
    final RfqOutcome o = ask(m, client, id, clientBuys, sizeMm);
    assertTrue(o.decision() != RfqDecision.PASS, "the strategy desk widens, it does not pass on cost: " + o.reason());
    final boolean weBuy = !clientBuys;
    assertTrue(o.distanceBp() >= o.breakEvenBp() - EPS, "below break-even: " + o.distanceBp() + " < " + o.breakEvenBp());
    // bid <= reservation <= ask, the reservation price being fair - skew
    assertTrue(weBuy ? o.distanceBp() >= o.skewBp() - EPS : o.distanceBp() >= -o.skewBp() - EPS, "through the reservation price");
    final Instrument inst = m.directory.byId(id);
    final long tick = VenueTickTable.simulatorDefaults().tick(inst);
    assertEquals(0L, Math.floorMod(o.price(), tick), "off the tick");
    final double qpb = m.desk.risk().quotePerBp(id);
    final double atBreakEven = weBuy ? o.fair() - o.breakEvenBp() * qpb : o.fair() + o.breakEvenBp() * qpb;
    final double price = Decimal9.toDouble(o.price());
    assertTrue(weBuy ? price <= atBreakEven + 1e-9 : price >= atBreakEven - 1e-9, "rounded in the client's favour");
    assertTrue(Math.abs(o.distanceTicks() * Decimal9.toDouble(tick) - o.distanceBp() * qpb) < 1e-9);
  }

  @Property(tries = 40)
  void addingToTheRiskWeAlreadyHoldNeverTightensTheQuote(
      @ForAll @IntRange(min = 0, max = 7) final int which,
      @ForAll @IntRange(min = 1, max = 200) final int sizeMm,
      @ForAll @IntRange(min = 10, max = 300) final int stepMm) {
    final TestMarket m = market();
    final int id = USD_RFQ[which];
    double last = Double.NEGATIVE_INFINITY;
    for (int k = 0; k < 6; k++) {
      // we buy the instrument, and our holding of it grows step by step
      final double d = ask(m, 1, id, false, sizeMm).distanceBp();
      assertTrue(d >= last - EPS, "step " + k + ": " + d + " after " + last);
      last = d;
      m.desk.risk().onFill(id, stepMm * FACE_1M, m.fair.fairMid(id));
    }
  }

  @Property(tries = 40)
  void aLargerRequestIsNeverQuotedTighter(
      @ForAll @IntRange(min = 0, max = 7) final int which,
      @ForAll @IntRange(min = -500, max = 500) final int inventoryMm,
      @ForAll final boolean clientBuys) {
    final TestMarket m = market();
    final int id = USD_RFQ[which];
    m.desk.risk().onFill(TestMarket.UST10, inventoryMm * FACE_1M, m.fair.fairMid(TestMarket.UST10));
    double last = Double.NEGATIVE_INFINITY;
    for (final long mm : new long[] {1, 2, 5, 10, 25, 50, 100, 250, 500}) {
      final double d = ask(m, 1, id, clientBuys, mm).distanceBp();
      assertTrue(d >= last - EPS, mm + "mm: " + d + " after " + last);
      last = d;
    }
  }

  @Property(tries = 30)
  void killedOrUnhealthyNothingIsQuoted(
      @ForAll @IntRange(min = 0, max = 7) final int which, @ForAll final boolean kill, @ForAll final boolean clientBuys) {
    final TestMarket m = market();
    if (kill) {
      m.desk.killSwitch().trigger(KillReason.Manual, 0, 1);
    } else {
      m.desk.health().trackVenue(9);
      m.desk.health().onBookSequence(9, 1);
      m.desk.health().onBookSequence(9, 3);
    }
    final RfqOutcome o = ask(m, 1, USD_RFQ[which], clientBuys, 10);
    assertEquals(RfqDecision.PASS, o.decision());
    assertEquals(kill ? PassReason.KILLED : PassReason.UNHEALTHY, o.reason());
    assertEquals(0, m.desk.quoting().liveQuotes());
  }
}
