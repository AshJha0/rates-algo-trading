package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.sim.SimConfig;
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
    long step;

    Chaos(final Random rnd, final SmartOrderRouter router, final int dup, final int delay) {
      this.rnd = rnd;
      this.router = router;
      this.duplicatePercent = dup;
      this.maxDelay = delay;
    }

    private void add(final Event e) {
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
  }
}
