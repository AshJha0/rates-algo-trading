package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.messages.sbe.TimeInForce;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * The whole router against the in-memory venues, with every ExecutionReport and cancel reject
 * passed through a channel that delays (late fills), duplicates and reorders them. After every
 * step: the parent accounting balances ({@code quantity = filled + outstanding + unrouted}, each
 * part non-negative) and the venues have never filled more than the parent. At the end every
 * parent is complete, the router's filled quantity equals the venues', and nothing is left
 * resting.
 *
 * <p>The second property adds what a real venue also does: it goes silent (reports are lost for
 * good) and its book goes unhealthy and comes back. No parent may hang: each one ends exactly
 * once, Filled, Expired or Failed, within a bounded time; the venues never fill more than the
 * parent; and a parent that did not fail agrees with the venues to the unit.
 */
class RouterChaosPropertiesTest {

  private static final long MM = RouterPresets.ONE_MILLION;

  /** A delayed event. */
  private record Event(
      long due, boolean reject, int venue, long id, long exec, OrdStatus status, long px,
      long last, long leaves, long cum) {}

  /** Captures events from the venues and replays them with chaos. */
  private static final class Chaos implements ExecutionListener {
    final Random rnd;
    final SmartOrderRouter router;
    final List<Event> pending = new ArrayList<>();
    final int duplicatePercent;
    final int maxDelay;
    int lossPercent;
    long step;

    Chaos(final Random rnd, final SmartOrderRouter router, final int dup, final int delay) {
      this.rnd = rnd;
      this.router = router;
      this.duplicatePercent = dup;
      this.maxDelay = delay;
    }

    private void add(final Event e) {
      if (rnd.nextInt(100) < lossPercent) {
        return;
      }
      pending.add(e);
      if (rnd.nextInt(100) < duplicatePercent) {
        pending.add(
            new Event(
                step + rnd.nextInt(maxDelay + 1), e.reject(), e.venue(), e.id(), e.exec(),
                e.status(), e.px(), e.last(), e.leaves(), e.cum()));
      }
    }

    @Override
    public void onExecutionReport(
        final int venueId, final long clOrdId, final long execId, final OrdStatus st,
        final long px, final long last, final long leaves, final long cum, final long nanos) {
      add(new Event(step + rnd.nextInt(maxDelay + 1), false, venueId, clOrdId, execId, st, px,
          last, leaves, cum));
    }

    @Override
    public void onCancelReject(final int venueId, final long clOrdId) {
      add(new Event(step + rnd.nextInt(maxDelay + 1), true, venueId, clOrdId, 0, null, 0, 0, 0,
          0));
    }

    @Override
    public void onBookUpdate(final int venueId) {
      router.onBookUpdate(venueId);
    }

    @Override
    public void onTrade(final int venueId, final Side aggressor, final long price, final long qty) {
      router.onTrade(venueId, aggressor, price, qty);
    }

    void deliver(final boolean all) {
      final List<Event> due = new ArrayList<>();
      for (int i = pending.size() - 1; i >= 0; i--) {
        if (all || pending.get(i).due() <= step) {
          due.add(pending.remove(i));
        }
      }
      Collections.shuffle(due, rnd);
      for (final Event e : due) {
        if (e.reject()) {
          router.onCancelReject(e.venue(), e.id());
        } else {
          router.onExecutionReport(
              e.venue(), e.id(), e.exec(), e.status(), e.px(), e.last(), e.leaves(), e.cum(), 0L);
        }
      }
    }
  }

  @Property(tries = 25)
  void parentsCompleteWithoutOverfillUnderChaos(
      @ForAll @LongRange(min = 1, max = 1_000_000) final long seed,
      @ForAll @IntRange(min = 0, max = 40) final int duplicatePercent,
      @ForAll @IntRange(min = 0, max = 8) final int maxDelay,
      @ForAll @DoubleRange(min = 0.0, max = 1.0) final double urgency) {
    final Random rnd = new Random(seed);
    final RouterParams params =
        RouterPresets.global().withHorizons(20_000_000_000L, 8_000_000_000L);
    final Rig rig = new Rig(SimConfig.ust10yThreeVenues(), params, seed);
    final Chaos chaos = new Chaos(rnd, rig.router, duplicatePercent, maxDelay);
    final int parents = 3;
    final long[] qty = new long[parents];
    for (int p = 0; p < parents; p++) {
      qty[p] = (5 + rnd.nextInt(40)) * MM;
      rig.router.submit(p + 1, 10, rnd.nextBoolean() ? Side.Buy : Side.Sell, qty[p], urgency, 0L);
    }
    for (int i = 0; i < 6_000; i++) {
      rig.clock.advance(rig.config.stepNanos());
      rig.market.step();
      rig.market.drain(chaos);
      chaos.deliver(false);
      chaos.step++;
      rig.router.poll();
      boolean anyActive = false;
      for (int p = 0; p < parents; p++) {
        final long id = p + 1L;
        assertTrue(rig.market.parentFilled(id) <= qty[p], "venues overfilled parent " + id);
        final long[] a = rig.router.parentAccounting(id);
        if (a != null) {
          anyActive = true;
          assertEquals(a[0], a[1] + a[2] + a[3]);
          assertTrue(a[1] >= 0 && a[2] >= 0 && a[3] >= 0, "negative accounting");
        }
      }
      if (!anyActive && chaos.pending.isEmpty()) {
        break;
      }
    }
    chaos.deliver(true);
    for (int p = 0; p < parents; p++) {
      assertTrue(!rig.router.isActive(p + 1), "parent " + (p + 1) + " incomplete");
      assertEquals(qty[p], rig.market.parentFilled(p + 1));
    }
    assertEquals(0, rig.router.children().anomalies());
    assertEquals(0, rig.market.selfCrosses());
    assertEquals(0, rig.market.liveOrders());
    assertEquals(0, rig.router.parentsFailed());
    assertEquals(0, rig.router.parentsExpired());
  }

  @Property(tries = 40)
  void everyParentEndsOnceEvenWhenVenuesGoSilentOrUnhealthy(
      @ForAll @LongRange(min = 1, max = 1_000_000) final long seed,
      @ForAll @IntRange(min = 0, max = 30) final int lossPercent,
      @ForAll @IntRange(min = 0, max = 6) final int maxDelay,
      @ForAll @IntRange(min = 0, max = 5) final int unhealthyPerMille,
      @ForAll @DoubleRange(min = 0.0, max = 1.0) final double urgency) {
    final Random rnd = new Random(seed);
    final RouterParams params =
        RouterPresets.global().withHorizons(20_000_000_000L, 8_000_000_000L);
    final Rig rig = new Rig(SimConfig.ust10yThreeVenues(), params, seed);
    final Chaos chaos = new Chaos(rnd, rig.router, 10, maxDelay);
    chaos.lossPercent = lossPercent;
    final int parents = 3;
    final long[] qty = new long[parents];
    final ParentOutcome[] outcome = new ParentOutcome[parents];
    final long[] reportedFilled = new long[parents];
    final int[] endings = new int[parents];
    rig.router.parentListener(
        (parentId, how, quantity, filled, unresolved) -> {
          final int p = (int) parentId - 1;
          endings[p]++;
          outcome[p] = how;
          reportedFilled[p] = filled;
          assertEquals(qty[p], quantity);
          assertTrue(how == ParentOutcome.Failed || unresolved == 0, "unresolved without failing");
        });
    final VenueParams[] venues = rig.router.venues();
    for (int p = 0; p < parents; p++) {
      qty[p] = (5 + rnd.nextInt(40)) * MM;
      rig.router.submit(p + 1, 10, rnd.nextBoolean() ? Side.Buy : Side.Sell, qty[p], urgency, 0L);
    }
    // schedule 20 s, passive 8 s, then 20 retries of 0.5 s and the cancel chases: well inside
    final int bound = (int) (120_000_000_000L / rig.config.stepNanos());
    int steps = 0;
    while (rig.router.parentsLive() > 0 && steps < bound) {
      steps++;
      rig.clock.advance(rig.config.stepNanos());
      if (rnd.nextInt(1_000) < unhealthyPerMille) {
        rig.router.venueHealth(venues[rnd.nextInt(venues.length)].venueId(), rnd.nextBoolean());
      }
      rig.market.step();
      rig.market.drain(chaos);
      chaos.deliver(false);
      chaos.step++;
      rig.router.poll();
      for (int p = 0; p < parents; p++) {
        final long id = p + 1L;
        assertTrue(rig.market.parentFilled(id) <= qty[p], "venues overfilled parent " + id);
        final long[] a = rig.router.parentAccounting(id);
        if (a != null) {
          assertEquals(a[0], a[1] + a[2] + a[3]);
          assertTrue(a[1] >= 0 && a[2] >= 0 && a[3] >= 0, "negative accounting");
        }
      }
    }
    assertEquals(0, rig.router.parentsLive(), "a parent hung after " + steps + " steps");
    chaos.deliver(true);
    for (int p = 0; p < parents; p++) {
      assertEquals(1, endings[p], "parent " + (p + 1) + " ended " + endings[p] + " times");
      final long venueFilled = rig.market.parentFilled(p + 1);
      assertTrue(venueFilled <= qty[p]);
      assertTrue(reportedFilled[p] <= venueFilled, "router counted a fill the venues did not make");
      if (outcome[p] == ParentOutcome.Filled) {
        assertEquals(qty[p], reportedFilled[p]);
      }
      if (outcome[p] != ParentOutcome.Failed) {
        assertEquals(venueFilled, reportedFilled[p], "a clean ending must agree with the venues");
      }
    }
    if (lossPercent == 0) {
      assertEquals(0, rig.router.parentsFailed(), "nothing was lost, so nothing may fail");
    }
    assertEquals(0, rig.market.selfCrosses());
  }

  /** Checks every child the router sends, on the way to the venues. */
  private static final class Checked implements OrderGateway {
    final SimMarket market;
    final long tick;
    SmartOrderRouter router;
    final List<String> failures = new ArrayList<>();
    final long[] parentLimit = new long[8];
    final long[] ownSell = new long[8];
    final long[] ownBuy = new long[8];

    Checked(final SimMarket market, final long tick) {
      this.market = market;
      this.tick = tick;
    }

    @Override
    public void newOrder(
        final int venueId, final long clOrdId, final long parentId, final int instrumentId,
        final Side side, final OrdType ordType, final TimeInForce tif, final long price,
        final long quantity) {
      if (!router.venueUsable(venueId)) {
        failures.add("child " + clOrdId + " routed to unhealthy venue " + venueId);
      }
      final boolean buy = side == Side.Buy;
      final long limit = parentLimit[(int) parentId];
      if (limit > 0 && (buy ? price > limit : price < limit)) {
        failures.add("child " + clOrdId + " at " + price + " past its parent limit " + limit);
      }
      if (price % tick != 0) {
        failures.add("child " + clOrdId + " off the tick: " + price);
      }
      final long own = buy ? ownSell[venueId] : ownBuy[venueId];
      if (own > 0 && (buy ? price >= own : price <= own)) {
        failures.add("child " + clOrdId + " at " + price + " would cross our own order at " + own);
      }
      if (tif != TimeInForce.Day) {
        final long far = buy ? market.bestAsk(venueId) : market.bestBid(venueId);
        if (buy ? price > far + 2 * tick : price < far - 2 * tick) {
          failures.add("child " + clOrdId + " at " + price + " past the 2-tick collar from " + far);
        }
      }
      market.newOrder(venueId, clOrdId, parentId, instrumentId, side, ordType, tif, price, quantity);
    }

    @Override
    public void cancel(final int venueId, final long clOrdId) {
      market.cancel(venueId, clOrdId);
    }
  }

  /**
   * The ladder model under the same chaos (lost, late, duplicated and reordered reports, venues
   * going unhealthy), with parent limits and orders of another strategy registered as ours. Every
   * child is checked as it is sent: never to an unhealthy venue, never past its parent limit or
   * off the tick, never at or through one of our own orders, never past the 2-tick collar. Every
   * parent ends exactly once, the venues never overfill, and a clean ending agrees with them.
   */
  @Property(tries = 30)
  void ladderParentsEndOnceInsideEveryLimit(
      @ForAll @LongRange(min = 1, max = 1_000_000) final long seed,
      @ForAll @IntRange(min = 0, max = 20) final int lossPercent,
      @ForAll @IntRange(min = 0, max = 5) final int maxDelay,
      @ForAll @IntRange(min = 0, max = 5) final int unhealthyPerMille,
      @ForAll @IntRange(min = 0, max = 3) final int limitTicks,
      @ForAll @DoubleRange(min = 0.0, max = 1.0) final double urgency) {
    final Random rnd = new Random(seed);
    final SimConfig config = SimConfig.ust10yThreeVenues();
    final ManualClock clock = new ManualClock();
    final SimMarket market = new SimMarket(config, seed, clock);
    final long tick = config.tickMantissa();
    final Checked checked = new Checked(market, tick);
    final VenueParams[] venues = config.declaredVenues(0.25, 5);
    final SmartOrderRouter router =
        new SmartOrderRouter(
            RouterPresets.ladder().withHorizons(20_000_000_000L, 8_000_000_000L), venues, market,
            checked, FrameSink.NONE, clock, RandomSource.seeded(seed), 8, 256, 1_000L,
            RouterTimeouts.DEFAULT, ExecutionModel.defaults());
    checked.router = router;
    // the quoter rests one tick outside the touch on venues 1 and 2
    checked.ownSell[1] = market.bestAsk(1) + tick;
    checked.ownBuy[2] = market.bestBid(2) - tick;
    router.registerOwnOrder(901, 1, Side.Sell, checked.ownSell[1], 3 * MM);
    router.registerOwnOrder(902, 2, Side.Buy, checked.ownBuy[2], 3 * MM);
    final Chaos chaos = new Chaos(rnd, router, 10, maxDelay);
    chaos.lossPercent = lossPercent;
    final int parents = 3;
    final long[] qty = new long[parents];
    final int[] endings = new int[parents];
    final ParentOutcome[] outcome = new ParentOutcome[parents];
    final long[] reported = new long[parents];
    router.parentListener(
        (parentId, how, quantity, filled, unresolved) -> {
          final int p = (int) parentId - 1;
          endings[p]++;
          outcome[p] = how;
          reported[p] = filled;
        });
    for (int p = 0; p < parents; p++) {
      qty[p] = (5 + rnd.nextInt(40)) * MM;
      final Side side = rnd.nextBoolean() ? Side.Buy : Side.Sell;
      final long limit =
          limitTicks == 0
              ? 0L
              : side == Side.Buy
                  ? market.bestAsk(1) + limitTicks * tick - tick / 3
                  : market.bestBid(1) - limitTicks * tick + tick / 3;
      // the router rounds an off-tick limit inwards; the check holds it to the rounded one
      checked.parentLimit[p + 1] =
          limit == 0
              ? 0
              : side == Side.Buy ? limit - Math.floorMod(limit, tick) : limit + Math.floorMod(-limit, tick);
      router.submit(p + 1, 10, side, qty[p], urgency, limit);
    }
    final int bound = (int) (300_000_000_000L / config.stepNanos());
    int steps = 0;
    while (router.parentsLive() > 0 && steps < bound) {
      steps++;
      clock.advance(config.stepNanos());
      if (rnd.nextInt(1_000) < unhealthyPerMille) {
        router.venueHealth(venues[rnd.nextInt(venues.length)].venueId(), rnd.nextBoolean());
      }
      if (steps == bound / 2) {
        // a limited parent can be unplaceable while the market is away from its limit: it is
        // cancelled, and must then end once like any other
        for (int p = 0; p < parents; p++) {
          if (checked.parentLimit[p + 1] > 0) {
            router.cancel(p + 1);
          }
        }
      }
      market.step();
      market.drain(chaos);
      chaos.deliver(false);
      chaos.step++;
      router.poll();
      for (int p = 0; p < parents; p++) {
        assertTrue(market.parentFilled(p + 1) <= qty[p], "venues overfilled parent " + (p + 1));
        final long[] a = router.parentAccounting(p + 1);
        if (a != null) {
          assertEquals(a[0], a[1] + a[2] + a[3]);
        }
      }
    }
    assertEquals(0, router.parentsLive(), "a parent hung after " + steps + " steps");
    chaos.deliver(true);
    assertTrue(checked.failures.isEmpty(), checked.failures.toString());
    for (int p = 0; p < parents; p++) {
      assertEquals(1, endings[p], "parent " + (p + 1) + " ended " + endings[p] + " times");
      final long venueFilled = market.parentFilled(p + 1);
      assertTrue(reported[p] <= venueFilled);
      if (outcome[p] != ParentOutcome.Failed) {
        assertEquals(venueFilled, reported[p], "a clean ending must agree with the venues");
      }
    }
    assertEquals(0, market.selfCrosses());
  }
}
