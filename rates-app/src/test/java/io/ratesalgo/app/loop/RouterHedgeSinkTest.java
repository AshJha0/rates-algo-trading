package io.ratesalgo.app.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.FrameSink;
import io.ratesalgo.router.SmartOrderRouter;
import org.junit.jupiter.api.Test;

/** The hedger-to-router binding: urgency mapping, refusals and cancels (DR-R2W-07). */
class RouterHedgeSinkTest {

  private static final long LOT = 100_000_000L;

  @Test
  void refusesWhatARouterCannotTakeAndCancelsByParent() {
    final ManualClock source = new ManualClock(1_793_626_000_000_000_000L);
    final EventClock clock = new EventClock(source);
    final DeskLoopFactory factory = new DeskLoopFactory(UsdUniverse.standard(), DeskSettings.defaults(), FrameSink.NONE);
    factory.create(
        EventLoop.builder(clock)
            .inbound(RingBufferContract.newInbound(1 << 16))
            .outbound(RingBufferContract.newOutbound(1 << 20))
            .outbound(RingBufferContract.newOutbound(1 << 20))
            .outbound(RingBufferContract.newOutbound(1 << 20)));
    final DeskGroup group = factory.last();
    final SmartOrderRouter[] routers = {group.router(0)};
    final int bond = group.routerInstrument(0);
    final RouterHedgeSink sink = new RouterHedgeSink(routers, new int[] {bond}, clock, 1_000_000_000L);

    assertEquals(1.0, sink.urgency(0L));
    assertEquals(0.0, sink.urgency(clock.epochNanos() + 5_000_000_000L), "a deadline past the horizon is patient");
    assertEquals(0.5, sink.urgency(clock.epochNanos() + 500_000_000L), 1e-12);

    assertFalse(sink.submit(1, 55, Side.Buy, LOT, 0L, 0L, 0L), "no router for the instrument");
    assertFalse(sink.submit(1, bond, Side.Buy, LOT / 2, 0L, 0L, 0L), "off the lot grid");
    assertFalse(sink.submit(1, bond, Side.Buy, 0L, 0L, 0L, 0L), "nothing to trade");
    assertFalse(sink.submit(1, bond, Side.Buy, LOT, -1L, 0L, 0L), "a negative limit");
    // an empty book: the router still takes the parent and waits for liquidity
    assertTrue(sink.submit(1, bond, Side.Buy, LOT, 0L, 0L, 0L));
    assertFalse(sink.submit(1, bond, Side.Buy, LOT, 0L, 0L, 0L), "the same parent id twice");
    for (long id = 2; id <= routers[0].maxParents(); id++) {
      assertTrue(sink.submit(id, bond, Side.Sell, LOT, 0L, 0L, 0L));
    }
    assertFalse(sink.submit(99, bond, Side.Sell, LOT, 0L, 0L, 0L), "every parent slot is taken");
    assertEquals(routers[0].maxParents(), sink.accepted());
    assertEquals(6, sink.refused());

    sink.cancel(1);
    assertFalse(routers[0].isActive(1), "a parent with nothing outstanding finishes on its cancel");
    sink.cancel(1);
    sink.done(2);
    sink.cancelAll();
    assertEquals(0, routers[0].parentsLive());
  }
}
