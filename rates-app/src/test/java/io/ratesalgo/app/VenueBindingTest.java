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
import java.util.function.BooleanSupplier;
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

  /**
   * Injects an RFQ at the D2C venue and steps until the desk has decided on it (a quote sent or a
   * pass). The venue sends the request from its own FIX thread over a socket, so the decision can
   * trail any fixed simulated step; see {@link #await}.
   */
  private static long rfqWithLiveQuote(final TradingProcess p) {
    final DeskGroup desk = p.group(0).desk();
    final long decided = desk.desk().quoting().quotesSent() + desk.desk().quoting().passes();
    final UsdUniverse.SwapSpec swap = p.universe().swaps().get(2);
    final long rfq =
        p.venues().d2c().injectRfq(new RfqSpec(swap.securityId(), true, 25_000_000L, 3, List.of(), 2001,
            ClientDecision.NONE, false));
    p.step(50_000_000L, STEP);
    assertTrue(
        await(p, () -> desk.desk().quoting().quotesSent() + desk.desk().quoting().passes() > decided),
        "the desk decided on the RFQ");
    return rfq;
  }

  private static boolean logged(final TradingProcess p, final String text) {
    return p.venues().d2c().eventLog().stream().anyMatch(l -> l.contains(text));
  }

  /**
   * Steps 10 ms of simulated time at a time until {@code condition} holds, for up to 10 s of
   * wall-clock time. Every condition that depends on a venue (its FIX threads, its sockets, its
   * event log and books) or on a session logon is read through this, never after a fixed simulated
   * step, because those threads run in wall-clock time (DR-INT-05, DR-INT-06).
   */
  private static boolean await(final TradingProcess p, final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return false;
      }
      p.step(10_000_000L, STEP);
    }
    return true;
  }

  private static boolean awaitLogged(final TradingProcess p, final String text) {
    return await(p, () -> logged(p, text));
  }

  private static boolean clobLogged(final TradingProcess p, final String text) {
    return p.venues().clob().eventLog().stream().anyMatch(l -> l.contains(text));
  }

  /**
   * Everything needed to tell a timing failure from a gateway defect: each engine-side session's
   * state, reconnect attempts, give-ups and pending retry (DR-INT-09), and the last 20 lines of each
   * venue's log.
   */
  private static String diagnostics(final TradingProcess p) {
    final List<String> d2c = p.venues().d2c().eventLog();
    final List<String> clob = p.venues().clob().eventLog();
    return "d2c session " + p.d2cSession().state() + ", reconnect attempts " + p.d2cSession().reconnectAttempts()
        + ", give-ups " + p.d2cSession().reconnectGiveUps() + ", retry pending " + p.d2cSession().reconnectPending()
        + "; clob session " + p.clobSession().state() + ", reconnect attempts " + p.clobSession().reconnectAttempts()
        + ", give-ups " + p.clobSession().reconnectGiveUps() + ", retry pending " + p.clobSession().reconnectPending()
        + "; d2c log " + d2c.subList(Math.max(0, d2c.size() - 20), d2c.size())
        + "; clob log " + clob.subList(Math.max(0, clob.size() - 20), clob.size());
  }

  @Test
  void aBookFeedGapPullsQuotesThroughTheLoopAndTheSnapshotRestoresQuoting() {
    try (TradingProcess p = TradingProcess.start(new TradingProcess.Options(dir, 3L, DeskSettings.defaults()))) {
      p.step(2_000_000_000L, STEP);
      final DeskGroup desk = p.group(0).desk();
      rfqWithLiveQuote(p);
      assertTrue(await(p, () -> desk.desk().quoting().liveQuotes() == 1), "a quote is live at the venue");
      final long pullsBefore = desk.desk().quoting().pulls();

      p.venues().feed().dropNext(2);
      p.step(1_100_000_000L, STEP);

      assertTrue(await(p, () -> p.feed().gaps() >= 1), "the binding saw the gap");
      assertEquals(1, p.feed().gaps(), "the binding saw one gap and rebuilt from a snapshot");
      assertTrue(desk.desk().quoting().pulls() > pullsBefore, "the loop pulled its quotes on the gap");
      assertTrue(await(p, () -> desk.desk().quoting().liveQuotes() == 0), "no quote live after the pull");
      assertTrue(awaitLogged(p, "quote cancelled " + AppIds.COMP_ID), "the withdrawal reached the venue");
      assertTrue(
          await(p, () -> desk.desk().health().healthy(p.clock().epochNanos())), "healthy again after the snapshot");

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
      assertTrue(await(p, () -> desk.desk().quoting().liveQuotes() == 1), "a quote is live at the venue");

      p.d2cSession().disconnect();
      p.clobSession().disconnect();
      p.step(200_000_000L, STEP);
      assertTrue(
          await(p, () -> desk.desk().quoting().liveQuotes() == 0), "pulled on the loop when the session went down");
      assertFalse(desk.desk().health().healthy(p.clock().epochNanos()));
      assertTrue(awaitLogged(p, "cancel-on-disconnect " + AppIds.COMP_ID), "the venue cancelled our quote");
      assertTrue(await(p, () -> p.venues().clob().liveOrders(AppIds.COMP_ID).isEmpty()), "nothing resting at the CLOB");
      assertEquals(1, p.d2cSession().disconnects());

      // Both venues should have torn the old session down before we log on again. An acceptor that
      // still holds the session refuses the new Logon; the session then retries under its
      // ReconnectPolicy (DR-INT-09), and this wait keeps the test from depending on that alone.
      assertTrue(
          await(p, () -> logged(p, "logout " + AppIds.COMP_ID) && clobLogged(p, "logout " + AppIds.COMP_ID)),
          "both venues logged the old session out: " + diagnostics(p));

      final long sent = desk.desk().quoting().quotesSent();
      p.d2cSession().connect();
      p.clobSession().connect();
      p.step(500_000_000L, STEP);
      assertTrue(await(p, () -> p.d2cSession().isActive() && p.clobSession().isActive()), "both sessions logged on again: " + diagnostics(p));
      assertTrue(await(p, () -> desk.desk().health().healthy(p.clock().epochNanos())), "both sessions up again");
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
