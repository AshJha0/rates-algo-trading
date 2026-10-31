package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.pricing.UsdUniverse;
import io.ratesalgo.core.chaos.FaultInjector;
import io.ratesalgo.venues.d2c.ClientDecision;
import io.ratesalgo.venues.d2c.RfqSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The venue binding under faults (API_SPEC.md Section 18.5). The loop learns about a broken feed
 * or a lost session only through its inbound ring, so each test checks that the quote pull happens
 * on the loop and reaches the venue, or that the venue's own cancel-on-disconnect leaves nothing
 * working, and that the desk quotes again once the input is whole.
 */
class VenueBindingTest {

  private static final long STEP = 10_000_000L;

  @TempDir Path dir;

  private static long rfqWithLiveQuote(final TradingProcess p) {
    final UsdUniverse.SwapSpec swap = p.universe().swaps().get(2);
    final long rfq =
        p.venues().d2c().injectRfq(new RfqSpec(swap.securityId(), true, 25_000_000L, 3, List.of(), 2001,
            ClientDecision.NONE, false));
    p.step(50_000_000L, STEP);
    return rfq;
  }

  private static boolean logged(final TradingProcess p, final String text) {
    return p.venues().d2c().eventLog().stream().anyMatch(l -> l.contains(text));
  }

  @Test
  void aBookFeedGapPullsQuotesThroughTheLoopAndTheSnapshotRestoresQuoting() {
    try (TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 3L, DeskSettings.defaults()))) {
      p.step(2_000_000_000L, STEP);
      final DeskGroup desk = p.group(0).desk();
      rfqWithLiveQuote(p);
      assertEquals(1, desk.desk().quoting().liveQuotes(), "a quote is live at the venue");
      final long pullsBefore = desk.desk().quoting().pulls();

      p.venues().feed().dropNext(2);
      p.step(1_100_000_000L, STEP);

      assertEquals(1, p.feed().gaps(), "the binding saw the gap and rebuilt from a snapshot");
      assertTrue(desk.desk().quoting().pulls() > pullsBefore, "the loop pulled its quotes on the gap");
      assertEquals(0, desk.desk().quoting().liveQuotes());
      assertTrue(logged(p, "quote cancelled " + AppIds.COMP_ID), "the withdrawal reached the venue");
      assertTrue(desk.desk().health().healthy(p.clock().epochNanos()), "healthy again after the snapshot");

      final long sent = desk.desk().quoting().quotesSent();
      rfqWithLiveQuote(p);
      assertEquals(sent + 1, desk.desk().quoting().quotesSent(), "quoting resumed");
      assertNull(p.failure());
    }
  }

  @Test
  void aLostSessionIsPulledOnTheLoopCancelledAtTheVenueAndQuotingResumesAfterLogon() {
    try (TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 5L, DeskSettings.defaults()))) {
      p.step(2_000_000_000L, STEP);
      final DeskGroup desk = p.group(0).desk();
      rfqWithLiveQuote(p);
      assertEquals(1, desk.desk().quoting().liveQuotes());

      p.d2cSession().disconnect();
      p.clobSession().disconnect();
      p.step(200_000_000L, STEP);
      assertEquals(0, desk.desk().quoting().liveQuotes(), "pulled on the loop when the session went down");
      assertFalse(desk.desk().health().healthy(p.clock().epochNanos()));
      assertTrue(logged(p, "cancel-on-disconnect " + AppIds.COMP_ID), "the venue cancelled our quote");
      assertTrue(p.venues().clob().liveOrders(AppIds.COMP_ID).isEmpty(), "nothing resting at the CLOB");
      assertEquals(1, p.d2cSession().disconnects());

      final long sent = desk.desk().quoting().quotesSent();
      p.d2cSession().connect();
      p.clobSession().connect();
      p.step(500_000_000L, STEP);
      assertTrue(p.d2cSession().isActive() && p.clobSession().isActive());
      assertTrue(desk.desk().health().healthy(p.clock().epochNanos()), "both sessions up again");
      rfqWithLiveQuote(p);
      assertEquals(sent + 1, desk.desk().quoting().quotesSent(), "quoting resumed after logon");
      assertEquals(2, p.d2cSession().logons());
      assertNull(p.failure());
    }
  }

  @Test
  void injectedFaultsOnTheInboundPathReachTheLoopAsInputs() {
    try (TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 9L, DeskSettings.defaults()))) {
      p.step(2_000_000_000L, STEP);
      final DeskGroup desk = p.group(0).desk();
      final io.ratesalgo.app.gateway.InboundRouter router =
          new io.ratesalgo.app.gateway.InboundRouter(
              new org.agrona.concurrent.ringbuffer.RingBuffer[] {ring(p)}, new org.agrona.collections.Int2IntHashMap(-1));
      final FaultInjector chaos = new FaultInjector(FaultInjector.FaultPlan.none(), router::offer);
      assertTrue(chaos.gatewayStatus(AppIds.D2C_GATEWAY, false, p.clock().epochNanos()));
      p.step(STEP, STEP);
      assertFalse(desk.desk().health().healthy(p.clock().epochNanos()), "an injected session loss is a session loss");
      final long passes = desk.desk().quoting().passes();
      rfqWithLiveQuote(p);
      assertEquals(passes + 1, desk.desk().quoting().passes(), "no quote while a session is down");
      assertTrue(chaos.gatewayStatus(AppIds.D2C_GATEWAY, true, p.clock().epochNanos()));
      p.step(STEP, STEP);
      assertTrue(desk.desk().health().healthy(p.clock().epochNanos()));
      assertEquals(2, chaos.deliveredCount());
    }
  }

  private static org.agrona.concurrent.ringbuffer.RingBuffer ring(final TradingProcess p) {
    return p.inboundRing(0);
  }
}
