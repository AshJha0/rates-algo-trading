package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.TradingProcess.GatewayThread;
import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.sim.V2Reconciliation;
import io.ratesalgo.core.DutyCycleRunner;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.ReconResult;
import io.ratesalgo.venues.clob.ClobSimulator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The v2 profile on its own threads: one per FIX session and per feed (DR-R6-L02), four and
 * three, and the supervisor's rules (DR-RV-S01 to S04) over all of them. Every wait is on a
 * condition another thread brings about, with a bound that only stops a broken build hanging.
 *
 * <p>The last test states what a threaded day is and is not. Its journals are not compared with
 * another run's: simulated time is paced by the wall clock and seven threads publish into one
 * inbound ring, so the order and the stamps of a loop's inputs differ from run to run by design.
 * What is promised, and asserted on each run alone: one thread owns the venues' shared market
 * model, nothing is lost or booked twice (the journals reconcile with every venue's register),
 * and the journals replay to the hash they recorded.
 */
class SupervisorV2Test {

  private static final long SECOND = 1_000_000_000L;
  private static final long MINUTE = 60 * SECOND;
  private static final long STEP = 20_000_000L;

  @TempDir Path dir;

  private TradingProcess start(final Path journals, final long killDeadline, final long stall) {
    return TradingProcess.start(
        TradingProcessV2Test.options(journals)
            .withTuning(TradingProcess.Tuning.DEFAULT.withSupervision(killDeadline, stall, 2_000_000L)));
  }

  private static void await(final BooleanSupplier done, final String what) {
    final long deadline = System.nanoTime() + 180 * SECOND;
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what);
      Thread.onSpinWait();
    }
  }

  private static boolean goneFromTheVenues(final TradingProcess p) {
    if (p.venuesV2().d2c().loggedOn().contains(AppIds.COMP_ID)) {
      return false;
    }
    for (final ClobSimulator book : p.venuesV2().books()) {
      if (book.loggedOn().contains(AppIds.COMP_ID) || !book.liveOrders(AppIds.COMP_ID).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  @Test
  void everySessionAndEveryFeedHasAThreadOfItsOwn() {
    final TradingProcess p = start(dir, 2 * SECOND, 60 * SECOND);
    try {
      p.step(MINUTE, STEP);
      p.startThreads(1.0);
      assertEquals(
          List.of(GatewayThread.D2C, GatewayThread.CLOB, GatewayThread.CLOB2, GatewayThread.FUTURES,
              GatewayThread.FEED, GatewayThread.FEED2, GatewayThread.FEED_FUTURES, GatewayThread.VENUES),
          p.gatewayThreads());
      for (final GatewayThread t : p.gatewayThreads()) {
        final DutyCycleRunner r = p.gatewayThread(t);
        assertEquals(t.threadName(), r.name());
        await(() -> r.beats() > 10, t + " cycling");
      }
      for (int i = 0; i < p.sessionCount(); i++) {
        assertNotNull(p.gatewayThread(p.sessionThread(i)));
      }
      assertThrows(IllegalStateException.class, () -> p.step(STEP, STEP), "a threaded process is not stepped");
      assertThrows(IllegalStateException.class, p::driveVenues);
      assertNull(p.haltReason());
    } finally {
      p.close();
    }
  }

  @Test
  void aDeadLoopIsWithdrawnAndLoggedOutOfAllFourVenuesFromTheGatewaySide() {
    final TradingProcess p = start(dir, 10 * SECOND, 60 * SECOND);
    try {
      p.step(8 * MINUTE, STEP);
      assertTrue(p.group(0).quotesSent() > 0 && p.group(0).hedgesRouted() > 0);
      p.startThreads(1.0);
      await(() -> p.group(0).loop().counters().get(io.ratesalgo.core.LoopCounters.DUTY_CYCLES) > 0, "the loop thread");
      assertNull(p.haltReason());

      // a record whose header does not match its ring type: the loop throws and its runner stops
      assertTrue(p.inboundRing(0).write(77, new UnsafeBuffer(new byte[16]), 0, 16));
      await(() -> p.haltReason() != null, "the supervisor seeing the dead loop");
      assertTrue(p.haltReason().startsWith("loop usd stopped"), p.haltReason());
      await(p::withdrawn, "every session logging out");
      await(() -> goneFromTheVenues(p), "the venues seeing the logouts");
      for (int i = 0; i < p.sessionCount(); i++) {
        assertEquals(VenueSession.WITHDRAWN, p.session(i).haltState(), "session " + i);
      }
      assertFalse(p.socketsClosed(), "orderly logouts, not dropped sockets");
      assertTrue(p.killSwitchEngaged());
      assertEquals(0, p.liveQuotes());
      // a kill now needs neither the loop nor room in its ring
      final TradingProcess.KillResult kill = assertTimeoutPreemptively(Duration.ofMinutes(2), () -> p.kill(9001));
      assertTrue(kill.confirmed(), String.valueOf(kill.reason()));
    } finally {
      p.close();
    }
  }

  /** DR-RV-S01 on a session the recorded market does not have: only the stuck session's socket is closed. */
  @Test
  void aStuckFuturesSessionThreadHasItsSocketClosedWhileTheOthersWithdrawTheirOwn() throws Exception {
    final TradingProcess p = start(dir, 2 * SECOND, SECOND / 2);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    try {
      p.step(8 * MINUTE, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(GatewayThread.FUTURES, () -> {
        entered.countDown();
        try {
          release.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      entered.await();
      await(() -> p.haltReason() != null, "the supervisor seeing the stall");
      assertTrue(p.haltReason().startsWith("gateway thread rates-fut made no duty cycle"), p.haltReason());
      await(p::socketsClosed, "the futures socket being closed");
      for (int i = 0; i < 3; i++) {
        final VenueSession session = p.session(i);
        await(() -> session.haltState() == VenueSession.WITHDRAWN, "session " + i + " withdrawing on its own thread");
        assertFalse(session.isActive());
        assertTrue(p.gatewayThread(p.sessionThread(i)).isRunning(), "thread of session " + i + " was not touched");
      }
      await(() -> goneFromTheVenues(p), "the venues seeing the logouts and the disconnect");
    } finally {
      release.countDown();
      p.close();
    }
  }

  @Test
  void aFeedThreadOfTheSecondBookThatDiesHaltsTheProcess() {
    final TradingProcess p = start(dir, 2 * SECOND, 60 * SECOND);
    try {
      p.step(MINUTE, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(GatewayThread.FEED2, () -> {
        throw new IllegalStateException("feed fault");
      });
      await(() -> p.haltReason() != null, "the halt");
      assertTrue(p.haltReason().startsWith("gateway thread rates-feed2 stopped"), p.haltReason());
      await(p::withdrawn, "every session withdrawing");
      await(() -> goneFromTheVenues(p), "the venues seeing the logouts");
      assertFalse(p.socketsClosed());
      assertFalse(p.gatewayThread(GatewayThread.FEED2).isAlive());
    } finally {
      p.close();
    }
  }

  /**
   * The simulator v2 venues answer only on the venues' thread. If it dies nothing answers a cancel
   * or a Logout, so the halt cannot be confirmed in order: the supervisor closes the sockets, as
   * it does for a venue that has stopped answering.
   */
  @Test
  void whenTheVenuesStopAnsweringTheSocketsAreClosed() {
    final TradingProcess p = start(dir, SECOND / 2, 60 * SECOND);
    try {
      p.step(MINUTE, STEP);
      p.startThreads(1.0);
      p.injectGatewayFault(GatewayThread.VENUES, () -> {
        throw new IllegalStateException("venue fault");
      });
      await(() -> p.haltReason() != null, "the halt");
      assertTrue(p.haltReason().startsWith("gateway thread rates-venues stopped"), p.haltReason());
      await(p::withdrawn, "every session down");
      for (int i = 0; i < p.sessionCount(); i++) {
        assertFalse(p.session(i).isActive(), "session " + i);
      }
    } finally {
      p.close();
    }
  }

  /** One threaded day: trades, is killed, and is checked against itself. Returns its inbound frames. */
  private long threadedDay(final Path journals) {
    final TradingProcess p = start(journals, 10 * SECOND, 60 * SECOND);
    final DeskGroup desk = p.group(0).desk();
    final ReconResult recon;
    try {
      p.step(MINUTE, STEP);
      p.startThreads(60.0);
      await(() -> p.failure() != null || (p.group(0).fills() > 0 && p.group(0).hedgesRouted() > 0),
          "an RFQ won and a hedge routed on the threaded process");
      assertNull(p.failure());
      assertNull(p.haltReason());
      final TradingProcess.KillResult kill = assertTimeoutPreemptively(Duration.ofMinutes(2), () -> p.kill(9001));
      assertTrue(kill.confirmed(), String.valueOf(kill.reason()));
      // a confirmed kill leaves the sessions up and nothing of ours working
      for (final ClobSimulator book : p.venuesV2().books()) {
        assertTrue(book.liveOrders(AppIds.COMP_ID).isEmpty(), "orders resting at " + book.compId() + " after the kill");
      }
      // everything the sessions published has been read, so it is in the journal
      await(() -> p.inboundRing(0).size() == 0, "the loop reading its ring to the end");
      // a second thread draining the venues beside their owner would have failed its runner here
      assertNull(p.failure(), "no thread failed: " + p.failure());
    } finally {
      p.close();
    }
    final PositionKeeper keeper = V2Reconciliation.keeper(p.group(0).dir(), desk.binding());
    recon = V2Reconciliation.run(keeper, p.venuesV2());
    assertTrue(recon.theirsCount() > 0, "trades to reconcile");
    assertEquals(0, recon.breaks().size(), "reconciliation breaks: " + recon.breaks());
    assertEquals(recon.theirsCount(), recon.matched());
    final ReplayResult replay =
        TradingProcessMain.replayGroup(
            journals.resolve("usd"), TradingProcess.journalProfile(journals, DeskProfile.v2()));
    assertTrue(replay.matches(), replay.toString());
    System.out.println("threaded v2 day: " + replay.inboundFrames() + " inbound frames, " + replay.outboundRecorded()
        + " outbound, reconciled " + recon.matched() + " of " + recon.theirsCount() + ", replay hash "
        + replay.replayHash());
    return replay.inboundFrames();
  }

  @Test
  void aThreadedDayReconcilesAndReplaysRunAfterRunAndIsNotClaimedToRepeat() {
    final long first = threadedDay(dir.resolve("a"));
    final long second = threadedDay(dir.resolve("b"));
    assertTrue(first > 0 && second > 0);
  }
}
