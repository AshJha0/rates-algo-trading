package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.gateway.VenueSession;
import io.ratesalgo.app.loop.AppIds;
import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskProfile;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.app.posttrade.JournalFeed;
import io.ratesalgo.app.sim.V2Reconciliation;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.messages.sbe.HeartbeatEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.posttrade.position.PositionKeeper;
import io.ratesalgo.posttrade.recon.ReconResult;
import io.ratesalgo.sim.SessionSeeds;
import io.ratesalgo.venues.clob.ClobSimulator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The trading process under the v2 desk profile, stepped: the 1.1.0 safety rules hold on four
 * sessions and three feeds as they do on two and one. A day that quotes and hedges on both
 * Treasury venues is killed out of band with the withdrawal confirmed on every venue, reconciles
 * with all four venues and replays; a process restarted on its journals stands where the first
 * stopped and trades again after every order-book session reconciled; a message the loop cannot
 * take halts the process and logs every session out.
 *
 * <p>The window is the v2 smoke test's, from 09:00 New York, for the reason given there. No
 * P&amp;L is read: every assertion is about the wiring.
 */
class TradingProcessV2Test {

  private static final long SECOND = 1_000_000_000L;
  private static final long MINUTE = 60 * SECOND;
  private static final long STEP = 20_000_000L;
  private static final long SEED = SessionSeeds.seed(SessionSeeds.ACCEPTANCE, 91_007);
  private static final String REASON = "a session message could not be delivered to the loop";

  @TempDir Path root;

  static TradingProcess.Options options(final Path dir) {
    return new TradingProcess.Options(dir, SEED, DeskSettings.defaults(), TradingProcessMain.V2_START)
        .withProfile(DeskProfile.v2());
  }

  private static void pumpUntil(final TradingProcess p, final BooleanSupplier done, final String what) {
    final long deadline = System.nanoTime() + 120 * SECOND;
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what + ": " + p.statusJson());
      p.pump();
    }
  }

  private static ReplayResult replay(final Path dir) {
    return TradingProcessMain.replayGroup(dir.resolve("usd"), TradingProcess.journalProfile(dir, DeskProfile.v2()));
  }

  private static boolean nothingRests(final TradingProcess p) {
    for (final ClobSimulator book : p.venuesV2().books()) {
      if (!book.liveOrders(AppIds.COMP_ID).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /** Frames and a digest of a directory's journals, inbound and outbound merged in journal order. */
  private static long[] digest(final Path dir) {
    final long[] d = {0L, 1469598103934665603L};
    JournalFeed.feed(
        dir.resolve("usd"), "in", "out",
        (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
          long h = d[1];
          h = (h ^ sequence) * 1099511628211L;
          h = (h ^ timestampNanos) * 1099511628211L;
          h = (h ^ templateId) * 1099511628211L;
          for (int i = 0; i < length; i++) {
            h = (h ^ buffer.getByte(offset + i)) * 1099511628211L;
          }
          d[1] = h;
          d[0]++;
        });
    return d;
  }

  /** The directory as a crash would leave it: every file as it is on disk now, nothing closed. */
  private static void crashImage(final Path from, final Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      for (final Path f : files.toList()) {
        final Path target = to.resolve(from.relativize(f).toString());
        if (Files.isDirectory(f)) {
          Files.createDirectories(target);
        } else {
          try {
            Files.copy(f, target);
          } catch (final NoSuchFileException renamed) {
            // a half-prepared next segment renamed between the listing and the copy: a crash just
            // before the preparation started leaves the same
          }
        }
      }
    }
  }

  /** One stepped day of twenty minutes, killed, reconciled; returns the journal digest. */
  private long[] dayKillReconcile(final Path dir) {
    final TradingProcess p = TradingProcess.start(options(dir));
    try {
      assertEquals(4, p.sessionCount());
      assertEquals(3, p.feedCount());
      assertNull(p.venues(), "the recorded market's venues are not started");
      assertNull(p.notReadyReason());
      p.step(20 * MINUTE, STEP);
      final DeskGroup desk = p.group(0).desk();
      assertTrue(desk.desk().quoting().quotesSent() > 0, "quotes sent");
      assertTrue(desk.hedgesRouted() > 0 && desk.hedgeFills() > 0, "hedges routed and filled");
      assertEquals(0, desk.feedGaps(), "every feed whole");
      assertNotNull(desk.pricingV2());
      assertEquals(0, desk.pricingV2().unfedInputs(), "the curve round feeds every input");
      assertEquals(DeskProfile.RouterModel.LADDER, p.profile().router());
      assertEquals(dir.resolve(TradingProcess.MARKOUTS_END), p.profile().markoutSave());

      // what an operator sees: the profile, each order-book venue, the spread ladder
      final String status = p.statusJson();
      assertTrue(status.contains("\"profile\":{\"market\":\"SIM_V2\",\"pricing\":\"V2\",\"router\":\"LADDER\"}"), status);
      assertTrue(status.contains("\"unfedCurveInputs\":0"), status);
      for (final int venue : new int[] {AppIds.CLOB_VENUE, AppIds.CLOB2_VENUE, AppIds.FUTURES_VENUE}) {
        assertTrue(status.contains("{\"venue\":" + venue + ",\"usable\":true,\"bookAgeMillis\":"), status);
      }
      assertTrue(status.contains("\"spreadPv01\":[") && status.contains("\"ready\":true"), status);

      // out-of-band kill: confirmed only when nothing of ours works on any of the four venues
      final TradingProcess.KillResult kill = p.kill(9001);
      assertTrue(kill.confirmed(), String.valueOf(kill.reason()));
      assertTrue(p.withdrawn() && p.killMessagePublished());
      for (int i = 0; i < p.sessionCount(); i++) {
        assertEquals(VenueSession.WITHDRAWN, p.session(i).haltState(), "session " + i);
      }
      assertTrue(nothingRests(p), "nothing of ours rests on any order book");
      assertEquals(0, p.liveQuotes());
      assertFalse(desk.desk().killSwitch().isActive(), "the loop pulled its own switch too");
      assertEquals(0, desk.liveChildren());
      assertTrue(p.killSwitchMessages() >= 1);
      assertEquals("halted: kill switch, requested by 9001", p.notReadyReason());

      // positions from the journals against the registers of all four venues
      final PositionKeeper keeper = V2Reconciliation.keeper(p.group(0).dir(), desk.binding());
      final ReconResult recon = V2Reconciliation.run(keeper, p.venuesV2());
      assertTrue(recon.theirsCount() > 0, "trades to reconcile");
      assertEquals(0, recon.breaks().size(), "reconciliation breaks: " + recon.breaks());
      assertEquals(recon.theirsCount(), recon.matched());
      System.out.println("process v2 day: quotes " + desk.desk().quoting().quotesSent() + ", fills "
          + desk.desk().quoting().fills() + ", hedges routed " + desk.hedgesRouted() + ", hedge fills "
          + desk.hedgeFills() + ", reconciled " + recon.matched() + " of " + recon.theirsCount());
    } finally {
      p.close();
    }
    assertTrue(Files.exists(dir.resolve(TradingProcess.MARKOUTS_END)), "the session's markout store was saved");
    final ReplayResult replay = replay(dir);
    assertTrue(replay.matches(), replay.toString());
    assertTrue(replay.outboundProduced() > 0);
    return digest(dir);
  }

  @Test
  void aSteppedDayIsKilledOnEveryVenueReconcilesReplaysAndRepeatsFrameForFrame() {
    final long[] first = dayKillReconcile(root.resolve("a"));
    final long[] second = dayKillReconcile(root.resolve("b"));
    assertTrue(first[0] > 10_000, "frames journaled: " + first[0]);
    // the kill and the logons included: the same seed is the same day
    assertEquals(java.util.Arrays.toString(first), java.util.Arrays.toString(second));
  }

  @Test
  void aProcessRestartedOnItsJournalsStandsWhereItStoppedReconcilesEverySessionAndTradesAgain() throws IOException {
    // sequence numbers resume: each venue keeps its store and its id generation under the journals
    final TradingProcess.Fix resume = TradingProcess.Fix.SIMULATOR.withResetOnLogon(false);
    final Path live = Files.createDirectories(root.resolve("live"));
    final Path image = root.resolve("image");
    final long digest;
    final long quotesSent;
    final long fills;
    final long stoppedAt;
    final long[] outSeq = new long[4];
    final TradingProcess first = TradingProcess.start(options(live).withFix(resume));
    try {
      assertFalse(first.recovered());
      first.step(20 * MINUTE, STEP);
      final DeskGroup desk = first.group(0).desk();
      assertTrue(desk.desk().quoting().fills() > 0 && desk.hedgeFills() > 0, "fills before the stop");
      digest = desk.stateDigest(first.universe().instrumentIds());
      quotesSent = desk.desk().quoting().quotesSent();
      fills = desk.desk().quoting().fills();
      stoppedAt = first.clock().epochNanos();
      for (int i = 0; i < 4; i++) {
        outSeq[i] = first.session(i).session().nextOutSeq();
      }
      crashImage(live, image);
    } finally {
      first.close();
    }
    assertFalse(Files.exists(image.resolve(TradingProcess.MARKOUTS_END)), "a crash saves no store");

    final TradingProcess p = TradingProcess.start(options(image).withFix(resume));
    try {
      final DeskGroup desk = p.group(0).desk();
      // the state at the last journaled frame, by replay alone; pricing v2 included
      assertTrue(p.recovered());
      assertEquals(digest, p.recoveredStateDigest(0), "desk state equals that of the process that never stopped");
      assertEquals(0, p.journalTornTails());
      assertTrue(p.clock().epochNanos() >= stoppedAt - STEP, "the market was taken to where the journals ended");

      pumpUntil(p, () -> p.notReadyReason() == null, "readiness after the restart");
      for (int i = 0; i < 4; i++) {
        assertTrue(p.session(i).session().nextOutSeq() > outSeq[i], "session " + i + " resumed its sequence");
        assertEquals(0, p.session(i).logonsRefused());
      }
      // every order-book session reconciled its orders before its venue was used again
      for (int i = 1; i < 4; i++) {
        assertEquals(1, p.session(i).reconciliations(), "session " + i + " reconciled after the restart");
        assertFalse(p.session(i).isReconciling());
        assertTrue(p.feed(i - 1).synced() && p.feed(i - 1).rebuilds() >= 1, "feed " + (i - 1) + " rebuilt");
      }
      assertEquals(0, desk.reconcileOrphans());
      assertTrue(nothingRests(p), "the venues cancelled on disconnect");
      assertNull(p.haltReason());
      assertFalse(p.killSwitchEngaged(), "a restart is not a kill");
      assertEquals(fills, desk.desk().quoting().fills(), "nothing booked twice on the way up");
      final String status = p.statusJson();
      assertTrue(status.contains("\"recovered\":true") && status.contains("\"ready\":true"), status);

      p.step(4 * MINUTE, STEP);
      assertTrue(desk.desk().quoting().quotesSent() > quotesSent, "quoting resumed: " + p.statusJson());
      assertEquals(0, desk.duplicateFills(), "a restarted venue's ids do not collide with ones already booked");
      // the restart made every feed rebuild, which the desk counts as a gap each; no other gap
      assertEquals(p.feedCount(), desk.feedGaps());
      // reconciliation across the restart: the trades the whole journal holds are those of the
      // stopped process plus exactly what the restarted venues' registers hold
      final int before = V2Reconciliation.keeper(live.resolve("usd"), desk.binding()).trades().size();
      final int whole = V2Reconciliation.keeper(p.group(0).dir(), desk.binding()).trades().size();
      final int since = V2Reconciliation.theirs(p.venuesV2(), AppIds.COMP_ID).size();
      assertTrue(before > 0, "trades before the stop");
      assertEquals(whole, before + since, "trades before the stop " + before + ", at the venues since " + since);
    } finally {
      p.close();
    }
    // the journals of the stop and of the life after it replay as one run
    final ReplayResult whole = replay(image);
    assertTrue(whole.matches(), whole.toString());
    final ReplayResult original = replay(live);
    assertTrue(original.matches(), original.toString());
    assertTrue(whole.outboundRecorded() > original.outboundRecorded());
  }

  @Test
  void aMessageTheLoopCannotTakeHaltsAndLogsEverySessionOut() {
    final TradingProcess p = TradingProcess.start(options(root));
    try {
      p.step(3 * MINUTE, STEP);
      assertNull(p.haltReason());

      // the loop stops reading and its ring fills: from here on only gateways and venues are driven
      final RingBuffer ring = p.inboundRing(0);
      final UnsafeBuffer beat = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
      final HeartbeatEncoder heartbeat = new HeartbeatEncoder();
      heartbeat.wrapAndApplyHeader(beat, 0, new MessageHeaderEncoder()).sourceId(12345).timestampNanos(1L);
      final int length = MessageHeaderEncoder.ENCODED_LENGTH + heartbeat.encodedLength();
      while (ring.write(HeartbeatEncoder.TEMPLATE_ID, beat, 0, length)) {
        // until it is full
      }
      // the market goes on: book frames and RFQs have nowhere to go
      final long deadline = System.nanoTime() + 120 * SECOND;
      while (p.haltReason() == null) {
        assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for the halt: " + p.statusJson());
        p.venuesV2().advance(p.clock(), SECOND);
        p.venuesV2().onTime();
        p.driveGateways();
        p.driveVenues();
      }
      assertEquals(REASON, p.haltReason());
      final BooleanSupplier gone =
          () -> p.withdrawn() && p.venuesV2().simulators().stream().noneMatch(v -> v.loggedOn().contains(AppIds.COMP_ID));
      while (!gone.getAsBoolean()) {
        assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for the logouts: " + p.statusJson());
        p.driveGateways();
        p.driveVenues();
      }
      assertTrue(nothingRests(p), "nothing of ours rests on any order book");
      for (int i = 0; i < p.sessionCount(); i++) {
        assertEquals(VenueSession.WITHDRAWN, p.session(i).haltState());
        assertFalse(p.session(i).isActive(), "logged out");
        assertFalse(p.session(i).reconnectPending(), "a halted process does not reconnect");
      }
      assertFalse(p.socketsClosed(), "orderly logouts, no dropped socket");
      assertEquals("halted: " + REASON, p.notReadyReason());
      assertTrue(p.killSwitchEngaged());
    } finally {
      p.close();
    }
  }

  @Test
  void optionsRefuseWhatASimulatorV2MarketCannotDo() {
    final TradingProcess.Options recorded = new TradingProcess.Options(root, 7L, DeskSettings.defaults());
    assertTrue(recorded.profile().isRecorded(), "the recorded profile unless one is given");
    assertEquals(recorded.settings(), recorded.profile().settings());
    // a v2 session opens at 07:00 New York: the recorded 08:25 is fine, an earlier start is not
    assertEquals(DeskProfile.Market.SIM_V2, recorded.withProfile(DeskProfile.v2()).profile().market());
    assertThrows(IllegalArgumentException.class,
        () -> new TradingProcess.Options(root, 7L, DeskSettings.defaults(), 0L).withProfile(DeskProfile.v2()));
    assertThrows(IllegalArgumentException.class,
        () -> recorded.withD2cEndpoint(new TradingProcess.Endpoint("127.0.0.1", 1, "X")).withProfile(DeskProfile.v2()));
    final DeskSettings other = DeskSettings.defaults();
    assertThrows(IllegalArgumentException.class,
        () -> new TradingProcess.Options(root, 7L, other, TradingProcess.DEFAULT_START, TradingProcess.Tuning.DEFAULT,
            TradingProcess.Fix.SIMULATOR, null, DeskProfile.v2()),
        "the profile's settings are the options' settings");
    // a profile that names its own markout files keeps them
    final DeskProfile named = DeskProfile.v2().withMarkouts(root.resolve("x"), root.resolve("y"));
    assertEquals(named, TradingProcess.journalProfile(root, named));
    assertEquals(DeskProfile.defaults(other), TradingProcess.journalProfile(root, DeskProfile.defaults(other)));
  }
}
