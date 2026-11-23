package io.ratesalgo.router.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.ExecutionListener;
import io.ratesalgo.router.RouterPresets;
import io.ratesalgo.router.VenueParams;
import io.ratesalgo.router.backtest.RouterBenchmarkScenario;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The benchmark market's mechanics: common random numbers, impact for everyone with resilience
 * below 100%, time priority, fills through a moving book, rejects, fill-or-kill and trade prints.
 */
class ImpactMarketTest {

  private static final long TICK = RouterPresets.TICK_64TH;
  private static final long MM = RouterPresets.ONE_MILLION;

  /** Records everything the market says. */
  static final class Tape implements ExecutionListener {
    final List<String> reports = new ArrayList<>();
    final List<long[]> prints = new ArrayList<>();
    int books;

    @Override
    public void onExecutionReport(
        final int venueId, final long clOrdId, final long execId, final OrdStatus st,
        final long px, final long last, final long leaves, final long cum, final long nanos) {
      reports.add(clOrdId + " " + st + " " + last / MM + "@" + px);
    }

    @Override
    public void onCancelReject(final int venueId, final long clOrdId) {
      reports.add(clOrdId + " CancelReject");
    }

    @Override
    public void onBookUpdate(final int venueId) {
      books++;
    }

    @Override
    public void onTrade(final int venueId, final Side aggressor, final long price, final long qty) {
      prints.add(new long[] {venueId, aggressor == Side.Buy ? 1 : -1, price, qty});
    }
  }

  /** One venue, no diffusion and no other flow unless asked. */
  static ImpactConfig quiet(final double flow, final double reject, final double kappa) {
    return new ImpactConfig(
        List.of(new ImpactVenueSpec(1, 1, 10.0, 5.0, flow, 1.0, 0.0, 0.5, 0L, 0.0005, 0.0002, reject)),
        5, TICK, MM, 6_304.6, 0.0, kappa, 100_000_000L);
  }

  private static void step(final ManualClock clock, final ImpactMarket m, final Tape tape, final int n) {
    for (int i = 0; i < n; i++) {
      clock.advance(100_000_000L);
      m.step();
      m.drain(tape);
    }
  }

  @Test
  void twoRunsOnOneSeedSeeTheSameExogenousMarket() {
    final RouterBenchmarkScenario sc = RouterBenchmarkScenario.ust10yImpactV1();
    final ManualClock c1 = new ManualClock();
    final ManualClock c2 = new ManualClock();
    final ImpactMarket a = new ImpactMarket(sc.market(), 7L, c1);
    final ImpactMarket b = new ImpactMarket(sc.market(), 7L, c2);
    final Tape t1 = new Tape();
    final Tape t2 = new Tape();
    step(c1, a, t1, 3_000);
    step(c2, b, t2, 3_000);
    assertEquals(a.efficientMidTicks(), b.efficientMidTicks());
    assertEquals(a.referenceMid(), b.referenceMid());
    assertEquals(a.othersLots(), b.othersLots());
    assertTrue(a.othersLots() > 1_000, "other participants trade: " + a.othersLots());
    for (int k = 0; k < 5; k++) {
      assertEquals(a.depth(1, Side.Buy, k), b.depth(1, Side.Buy, k));
    }
    assertEquals(t1.prints.size(), t2.prints.size());
  }

  @Test
  void ourSweepMovesThePriceForEveryoneAndTheBookRefillsOnlyItsDepth() {
    final ManualClock clock = new ManualClock();
    final ImpactMarket m = new ImpactMarket(quiet(0.0, 0.0, 0.02), 1L, clock);
    final Tape tape = new Tape();
    final double before = m.efficientMidTicks();
    final long ask = m.bestAsk(1);
    m.newOrder(1, 1, 1, 10, Side.Buy, OrdType.Limit, TimeInForce.ImmediateOrCancel, ask + TICK, 25 * MM);
    step(clock, m, tape, 1);
    assertEquals(25 * MM, m.parentFilled(1), "10 at the touch and 15 at the next level");
    assertEquals(10.0 * ask / 1e9 * MM + 15.0 * (ask + TICK) / 1e9 * MM, m.parentNotional(1), 1e-3);
    assertEquals(before + 25 * 0.02, m.efficientMidTicks(), 1e-9, "permanent impact");
    assertEquals(2, tape.prints.size());
    assertEquals(10 * MM, tape.prints.get(0)[3]);
    assertTrue(m.depth(1, Side.Sell, 0) < 10 * MM || m.bestAsk(1) > ask, "the touch was taken");
    step(clock, m, tape, 600);
    assertEquals(before + 0.5, m.efficientMidTicks(), 1e-9, "the move stays: resilience below 100%");
    assertTrue(m.depth(1, Side.Sell, 0) >= 10 * MM, "the depth comes back");
  }

  @Test
  void othersMarketOrdersFillUsInTimePriority() {
    final ManualClock clock = new ManualClock();
    final ImpactMarket m = new ImpactMarket(quiet(3.0, 0.0, 0.0), 2L, clock);
    final Tape tape = new Tape();
    final long bid = m.bestBid(1);
    m.newOrder(1, 7, 1, 10, Side.Buy, OrdType.Limit, TimeInForce.Day, bid, 2 * MM);
    step(clock, m, tape, 1);
    assertTrue(m.depth(1, Side.Buy, 0) >= 11 * MM, "ours joins behind theirs");
    int steps = 0;
    while (m.parentFilled(1) < 2 * MM && steps++ < 5_000) {
      step(clock, m, tape, 1);
    }
    assertEquals(2 * MM, m.parentFilled(1), tape.reports.toString());
    long soldAtBid = 0;
    for (final long[] p : tape.prints) {
      if (p[1] < 0 && p[2] == bid) {
        soldAtBid += p[3];
      }
    }
    assertTrue(soldAtBid >= 12 * MM, "their 10 ahead went first, then our 2: " + soldAtBid / MM);
  }

  @Test
  void aRestingOrderTheBookMovesThroughIsFilledAtItsPrice() {
    final ManualClock clock = new ManualClock();
    final ImpactMarket m = new ImpactMarket(quiet(0.0, 0.0, 0.02), 3L, clock);
    final Tape tape = new Tape();
    final long bid = m.bestBid(1);
    m.newOrder(1, 8, 1, 10, Side.Sell, OrdType.Limit, TimeInForce.Day, bid + TICK, MM);
    step(clock, m, tape, 1);
    // our own buy sweep moves the price up through our resting sell; it is skipped, not traded
    m.newOrder(1, 9, 2, 10, Side.Buy, OrdType.Limit, TimeInForce.ImmediateOrCancel, bid + 4 * TICK, 60 * MM);
    step(clock, m, tape, 1);
    assertEquals(1, m.selfCrosses());
    assertEquals(MM, m.parentFilled(1), "filled through as the book moved: " + tape.reports);
  }

  @Test
  void rejectsFillOrKillAndCancels() {
    final ManualClock clock = new ManualClock();
    final ImpactMarket rejecting = new ImpactMarket(quiet(0.0, 0.999999, 0.02), 4L, clock);
    final Tape tape = new Tape();
    rejecting.newOrder(1, 1, 1, 10, Side.Buy, OrdType.Limit, TimeInForce.ImmediateOrCancel, rejecting.bestAsk(1), MM);
    step(clock, rejecting, tape, 1);
    assertEquals(1, rejecting.rejects());
    assertTrue(tape.reports.get(0).contains("Rejected"));

    final ImpactMarket m = new ImpactMarket(quiet(0.0, 0.0, 0.02), 5L, clock);
    final Tape t2 = new Tape();
    m.newOrder(1, 2, 2, 10, Side.Buy, OrdType.Limit, TimeInForce.FillOrKill, m.bestAsk(1), 11 * MM);
    step(clock, m, t2, 1);
    assertEquals(0, m.parentFilled(2), "11 cannot fill against 10: killed");
    m.newOrder(1, 3, 3, 10, Side.Buy, OrdType.Limit, TimeInForce.FillOrKill, m.bestAsk(1), 10 * MM);
    m.newOrder(1, 4, 4, 10, Side.Sell, OrdType.Limit, TimeInForce.Day, m.bestAsk(1) + 3 * TICK, MM);
    step(clock, m, t2, 1);
    assertEquals(10 * MM, m.parentFilled(3));
    m.cancel(1, 4);
    m.cancel(1, 999);
    step(clock, m, t2, 1);
    assertTrue(t2.reports.contains("4 Cancelled 0@0"), t2.reports.toString());
    assertTrue(t2.reports.contains("999 CancelReject"));
    assertEquals(0, m.liveOrders());
    assertEquals(1, m.venueIds().length);
    assertEquals(0L, m.depth(9, Side.Buy, 0));
    assertEquals(0.0, m.tradeRatePerSecond(9));
    assertThrows(IllegalArgumentException.class,
        () -> m.newOrder(1, 5, 5, 10, Side.Buy, OrdType.Limit, TimeInForce.Day, 1, MM + 1));
  }

  @Test
  void poissonInversionTakesOneUniformAndHasTheRightMean() {
    assertEquals(0, ImpactMarket.poisson(0.5, 0.0));
    double sum = 0.0;
    final int n = 20_000;
    for (int i = 0; i < n; i++) {
      sum += ImpactMarket.poisson((i + 0.5) / n, 2.5);
    }
    assertEquals(2.5, sum / n, 0.01);
  }

  @Test
  void declaredVenuesCarryNoGeneratingRate() {
    final ImpactConfig cfg = RouterBenchmarkScenario.ust10yImpactV1().market();
    final VenueParams[] v = cfg.declaredVenues(0.25, 5);
    for (int i = 0; i < v.length; i++) {
      assertEquals(SimConfig.DECLARED_CANCEL_RATE, v[i].cancelRatePerSecond());
      assertTrue(v[i].cancelRatePerSecond() != cfg.venue(i).cancelRatePerSecond());
      assertEquals(cfg.venue(i).takerFee(), v[i].takerFee());
    }
    assertThrows(IllegalArgumentException.class,
        () -> new ImpactVenueSpec(1, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new ImpactConfig(List.of(), 5, TICK, MM, 6_000, 0, 0, 1));
  }
}
