package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.ops.http.OpsHttpServer;
import io.ratesalgo.ops.metrics.MetricsRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process withdrawing without its loop or its gateway thread (review findings L1 and L2). Each
 * test breaks one thread of a running process and checks what the venues see: no session of ours
 * logged on and no order of ours resting. Before the supervisor a dead loop left its quotes at the
 * venue behind a session that kept heartbeating, and a kill waited two seconds and reported
 * success whatever had happened.
 */
class SupervisorTest {

  private static final long SECOND = 1_000_000_000L;
  private static final long STEP = 10_000_000L;

  @TempDir Path dir;

  private TradingProcess start(final long killDeadline, final long stall) {
    return TradingProcess.start(
        new TradingProcess.Options(dir, 7L, DeskSettings.defaults())
            .withTuning(TradingProcess.Tuning.DEFAULT.withSupervision(killDeadline, stall, 2_000_000L)));
  }

  /** Waits on a condition another thread brings about; the bound only stops a broken build hanging. */
  private static void await(final BooleanSupplier done, final String what) {
    final long deadline = System.nanoTime() + 120 * SECOND;
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what);
      Thread.onSpinWait();
    }
  }

  private static boolean goneFromTheVenues(final TradingProcess p) {
    return !p.venues().d2c().loggedOn().contains(AppIds.COMP_ID)
        && !p.venues().clob().loggedOn().contains(AppIds.COMP_ID)
        && p.venues().clob().liveOrders(AppIds.COMP_ID).isEmpty();
  }

  @Test
  void aDeadLoopIsWithdrawnAndLoggedOutFromTheGatewaySide() {
    final TradingProcess p = start(10 * SECOND, 60 * SECOND);
    try {
      p.step(6 * 60 * SECOND, STEP); // across the 08:30 peak: quotes sent, hedges resting on the CLOB
      assertTrue(p.group(0).quotesSent() > 0);
      p.startThreads(1.0);
      await(() -> p.group(0).loop().counters().get(io.ratesalgo.core.LoopCounters.DUTY_CYCLES) > 0, "the loop thread");
      assertNull(p.haltReason());
      assertNull(p.notReadyReason());

      // a record whose header does not match its ring type: the loop throws and its runner stops
      final RingBuffer ring = p.inboundRing(0);
      assertTrue(ring.write(77, new UnsafeBuffer(new byte[16]), 0, 16));
      await(() -> p.haltReason() != null, "the supervisor seeing the dead loop");
      assertTrue(p.haltReason().startsWith("loop usd stopped"), p.haltReason());
      await(p::withdrawn, "the sessions logging out");
      await(() -> goneFromTheVenues(p), "the venues seeing the logout");
      assertEquals(VenueSession.WITHDRAWN, p.d2cSession().haltState());
      assertEquals(VenueSession.WITHDRAWN, p.clobSession().haltState());
      assertFalse(p.socketsClosed(), "an orderly logout, not a dropped socket");
      assertNotNull(p.failure());

      // the state is visible to operators and to the readiness probe
      assertTrue(p.notReadyReason().startsWith("halted: loop usd stopped"), p.notReadyReason());
      final String status = p.statusJson();
      assertTrue(status.contains("\"killSwitch\":true") && status.contains("\"quoting\":false"), status);
      assertTrue(status.contains("\"halted\":true") && status.contains("\"withdrawn\":true"), status);
      assertTrue(status.contains("\"liveQuotes\":0"), status);
      assertTrue(p.killSwitchEngaged());
      assertEquals(0, p.liveQuotes());

      // a kill now needs neither the loop nor room in its ring, and reports the truth
      final TradingProcess.KillResult kill =
          assertTimeoutPreemptively(Duration.ofMinutes(1), () -> p.kill(9001));
      assertTrue(kill.confirmed(), String.valueOf(kill.reason()));
      assertNull(kill.reason());
    } finally {
      p.close();
    }
  }

  @Test
  void aGatewayThreadThatDiesWithdrawsOnItsWayDown() {
    final TradingProcess p = start(2 * SECOND, 60 * SECOND);
    try {
      p.step(6 * 60 * SECOND, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(() -> {
        throw new IllegalStateException("gateway fault");
      });
      await(() -> p.failure() != null, "the gateway thread failing");
      await(p::withdrawn, "the withdrawal");
      await(() -> goneFromTheVenues(p), "the venues seeing the logout");
      assertTrue(p.haltReason().startsWith("gateway thread rates-d2c stopped"), p.haltReason());
      assertTrue(p.failure() instanceof IllegalStateException);
      assertTrue(p.notReadyReason().startsWith("halted: gateway thread rates-d2c stopped"), p.notReadyReason());
    } finally {
      p.close();
    }
  }

  @Test
  void aStuckGatewayThreadHasItsSocketsClosedAndTheKillAnswers503() throws Exception {
    final TradingProcess p = start(SECOND / 4, 60 * SECOND);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    try (OpsHttpServer http = TradingProcessMain.serve(p, new MetricsRegistry(), "127.0.0.1", 0, "token")) {
      p.step(6 * 60 * SECOND, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(() -> {
        entered.countDown();
        try {
          release.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      entered.await();

      // the thread that owns the sessions cannot act: the kill must say so, not answer 200
      final HttpResponse<String> kill =
          HttpClient.newHttpClient().send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + "/control/kill"))
                  .header("X-Control-Token", "token")
                  .header("X-Requested-By", "9001")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(503, kill.statusCode(), kill.body());
      assertTrue(kill.body().contains("\"confirmed\":false"), kill.body());
      assertTrue(kill.body().contains("halt not applied") && kill.body().contains("sockets closed"), kill.body());
      assertTrue(p.socketsClosed());
      // closing the sockets is what the venues act on: they cancel on disconnect
      await(() -> goneFromTheVenues(p), "the venues seeing the disconnect");
      assertFalse(p.withdrawn(), "no confirmation while the owner is stuck");

      release.countDown();
      await(p::withdrawn, "the gateway thread finding its sockets closed");
      assertTrue(p.kill(9001).confirmed(), "the same kill, once the owner is back, is confirmed");
    } finally {
      release.countDown();
      p.close();
    }
  }

  @Test
  void aStalledGatewayThreadIsFoundByTheSupervisor() throws Exception {
    final TradingProcess p = start(2 * SECOND, SECOND / 2);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    try {
      p.step(6 * 60 * SECOND, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(() -> {
        entered.countDown();
        try {
          release.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      entered.await();
      await(() -> p.haltReason() != null, "the supervisor seeing the stall");
      assertTrue(p.haltReason().startsWith("gateway thread rates-d2c made no duty cycle"), p.haltReason());
      await(p::socketsClosed, "the sockets being closed");
      await(() -> goneFromTheVenues(p), "the venues seeing the disconnect");
    } finally {
      release.countDown();
      p.close();
    }
  }

  /** Round 6 thread layout (DR-R6-L02): one thread per session, one for the feed, one for the venues. */
  @Test
  void eachSessionAndTheFeedHaveAThreadOfTheirOwnWithTheirTuning() {
    final TradingProcess p =
        TradingProcess.start(
            new TradingProcess.Options(dir, 7L, DeskSettings.defaults())
                .withTuning(TradingProcess.Tuning.DEFAULT.withGatewayThreads("yield", -1)));
    try {
      p.step(60 * SECOND, STEP);
      assertNull(p.gatewayThread(TradingProcess.GatewayThread.D2C), "no threads while stepped");
      p.startThreads(1.0);
      for (final TradingProcess.GatewayThread t : TradingProcess.GatewayThread.values()) {
        final io.ratesalgo.core.DutyCycleRunner r = p.gatewayThread(t);
        assertEquals(t.threadName(), r.name());
        await(() -> r.beats() > 10, t + " cycling");
        assertFalse(r.isPinned(), "no CPU given, nothing pinned");
      }
      assertEquals(1, p.loopRunners().size());
      assertThrows(IllegalStateException.class, () -> p.step(STEP, STEP), "a threaded process is not stepped");
      assertThrows(IllegalStateException.class, p::driveGateways);
      assertNull(p.haltReason());
    } finally {
      p.close();
    }
    assertEquals("park", TradingProcess.Tuning.DEFAULT.gatewayIdleStrategy(), "the old 200 us pause by default");
    assertEquals("busy-spin", TradingProcess.Tuning.LOW_LATENCY.gatewayIdleStrategy());
    assertTrue(TradingProcess.Tuning.LOW_LATENCY.conflateCurveInputs());
    assertFalse(TradingProcess.Tuning.DEFAULT.conflateCurveInputs());
    assertEquals(io.ratesalgo.core.JournalMode.DIRECT, TradingProcess.Tuning.DEFAULT.journals().mode());
    assertEquals(io.ratesalgo.core.JournalMode.RING, TradingProcess.Tuning.LOW_LATENCY.journals().mode());
    assertThrows(IllegalArgumentException.class, () -> TradingProcess.Tuning.DEFAULT.withGatewayThreads("sleep", -1));
    assertThrows(IllegalArgumentException.class, () -> TradingProcess.Tuning.DEFAULT.withJournals(null));
  }

  /**
   * A stuck CLOB thread: the supervisor closes the CLOB socket only, the one thing it may do to a
   * session it does not own, and the D2C thread withdraws its own session in order (DR-RV-S01).
   */
  @Test
  void aStuckClobThreadHasItsSocketClosedWhileTheD2cThreadWithdrawsItsOwnSession() throws Exception {
    final TradingProcess p = start(2 * SECOND, SECOND / 2);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    try {
      p.step(6 * 60 * SECOND, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(TradingProcess.GatewayThread.CLOB, () -> {
        entered.countDown();
        try {
          release.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      entered.await();
      await(() -> p.haltReason() != null, "the supervisor seeing the stall");
      assertTrue(p.haltReason().startsWith("gateway thread rates-clob made no duty cycle"), p.haltReason());
      await(p::socketsClosed, "the CLOB socket being closed");
      await(() -> p.d2cSession().haltState() == VenueSession.WITHDRAWN, "the D2C thread withdrawing");
      await(() -> goneFromTheVenues(p), "the venues seeing the logout and the disconnect");
      assertFalse(p.d2cSession().isActive(), "the D2C session logged out on its own thread");
      assertTrue(p.gatewayThread(TradingProcess.GatewayThread.D2C).isRunning(), "the D2C thread was not touched");
    } finally {
      release.countDown();
      p.close();
    }
  }

  /** A feed thread that dies halts the process; each session's own thread withdraws it. */
  @Test
  void aFeedThreadThatDiesHaltsAndEachSessionThreadWithdrawsItsSession() {
    final TradingProcess p = start(2 * SECOND, 60 * SECOND);
    try {
      p.step(6 * 60 * SECOND, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(TradingProcess.GatewayThread.FEED, () -> {
        throw new IllegalStateException("feed fault");
      });
      await(() -> p.haltReason() != null, "the halt");
      assertTrue(p.haltReason().startsWith("gateway thread rates-feed stopped"), p.haltReason());
      await(p::withdrawn, "both sessions withdrawing");
      await(() -> goneFromTheVenues(p), "the venues seeing the logouts");
      assertFalse(p.socketsClosed(), "orderly logouts, no socket closed from outside");
      assertTrue(p.failure() instanceof IllegalStateException);
      assertFalse(p.gatewayThread(TradingProcess.GatewayThread.FEED).isAlive());
    } finally {
      p.close();
    }
  }

  @Test
  void aSteppedKillIsConfirmedWithAFullInboundRingAndTheJournalsStillReplay() {
    final TradingProcess p = start(2 * SECOND, 60 * SECOND);
    final Path groupDir = p.group(0).dir();
    try {
      p.step(6 * 60 * SECOND, STEP);
      assertTrue(p.group(0).quotesSent() > 0);

      // idle duty cycles write no status: each field is stored only when it changes (L11)
      final long writes = p.group(0).statusWrites();
      assertTrue(writes > 0);
      for (int i = 0; i < 10_000; i++) {
        p.group(0).loop().doWork();
      }
      assertEquals(writes, p.group(0).statusWrites(), "volatile writes on idle duty cycles");

      // fill the loop's inbound ring: the old kill spun here for ever waiting for room
      final UnsafeBuffer b = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
      final HeartbeatEncoder hb = new HeartbeatEncoder();
      hb.wrapAndApplyHeader(b, 0, new MessageHeaderEncoder()).sourceId(4242).timestampNanos(1L);
      final int length = MessageHeaderEncoder.ENCODED_LENGTH + hb.encodedLength();
      final RingBuffer ring = p.inboundRing(0);
      int filled = 0;
      while (ring.write(HeartbeatEncoder.TEMPLATE_ID, b, 0, length)) {
        filled++;
      }
      assertTrue(filled > 1000);

      final TradingProcess.KillResult kill =
          assertTimeoutPreemptively(Duration.ofMinutes(2), () -> p.kill(9001));
      assertTrue(kill.confirmed(), String.valueOf(kill.reason()));
      assertTrue(p.killMessagePublished(), "the KillSwitch was journaled once the ring had room");
      assertTrue(p.withdrawn());
      p.step(2 * SECOND, STEP);
      assertTrue(p.group(0).killed(), "the loop took the journaled kill");
      assertEquals(0, p.group(0).liveQuotes());
      assertEquals(0, p.group(0).desk().liveChildren());
      assertTrue(p.venues().clob().liveOrders(AppIds.COMP_ID).isEmpty());
      assertTrue(p.d2cSession().isActive() && p.clobSession().isActive(), "a confirmed kill keeps the sessions");
      p.supervise(System.nanoTime()); // nothing to do when the process is stepped
      assertNull(p.failure());
    } finally {
      p.close();
    }
    final ReplayResult replay = TradingProcessMain.replayGroup(groupDir);
    assertTrue(replay.matches(), "replay: " + replay);
  }

  @Test
  void tuningIsValidatedAndTheDefaultsAreWhatTheProcessAlwaysRan() {
    assertEquals("backoff", TradingProcess.Tuning.DEFAULT.loopIdleStrategy());
    assertEquals(-1, TradingProcess.Tuning.DEFAULT.loopCpu());
    final TradingProcess.Tuning pinned = TradingProcess.Tuning.DEFAULT.withLoopThread("busy-spin", 3);
    assertEquals("busy-spin", pinned.loopIdleStrategy());
    assertEquals(3, pinned.loopCpu());
    assertThrows(IllegalArgumentException.class, () -> TradingProcess.Tuning.DEFAULT.withLoopThread("sleep", -1));
    assertThrows(IllegalArgumentException.class, () -> TradingProcess.Tuning.DEFAULT.withSupervision(0, 1, 1));
    assertTrue(TradingProcess.KillResult.CONFIRMED.confirmed());
  }
}
