package io.ratesalgo.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.app.loop.DeskGroup;
import io.ratesalgo.app.loop.DeskSettings;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.journal.JournalCorruptionException;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.journal.SegmentedJournalReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restart recovery end to end (review finding L6). A process runs across the 08:30 peak, so that
 * it holds positions, quotes and hedge parents, and is then
 * stopped without any close: its journal directory is copied as it stands on disk while the
 * process is still up, which is what a crash leaves (the journals end wherever they ended, the
 * next segment is prepared and unused, nothing is forced). A second process starts on the copy.
 *
 * <p>It must come up in the state the first was in at its last journaled frame, proved two ways:
 * the desk's state digest equals the digest taken from the first process at the moment of the
 * copy, which is the state of a process that never stopped, and the outbound messages its replay
 * produced hash to the recorded outbound journal. It must then be withdrawn until its sessions
 * are back and reconciled, quote again afterwards, and leave journals that replay as one run.
 */
class RestartRecoveryTest {

  private static final long SECOND = 1_000_000_000L;
  private static final long MINUTE = 60 * SECOND;
  private static final long STEP = 10_000_000L;

  @TempDir Path root;

  private static TradingProcess.Options options(final Path dir) {
    return new TradingProcess.Options(dir, 7L, DeskSettings.defaults());
  }

  /** The directory as a crash would leave it: every file as it is on disk now, nothing closed. */
  private static Path crashImage(final Path from, final Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      for (final Path f : files.toList()) {
        final Path target = to.resolve(from.relativize(f).toString());
        if (Files.isDirectory(f)) {
          Files.createDirectories(target);
        } else {
          try {
            Files.copy(f, target);
          } catch (final NoSuchFileException renamed) {
            // The preparing thread renamed a half-prepared next segment (.pending) between the
            // listing and the copy. The image then holds neither name, which is what a crash just
            // before the preparation started leaves; recovery treats a missing next segment as
            // not yet prepared.
          }
        }
      }
    }
    return to;
  }

  private static long[] journalEnd(final Path groupDir, final String name) {
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(groupDir, name)) {
      while (reader.next((s, t, id, v, b, o, l) -> {})) {
        // reading to the end
      }
      return new long[] {reader.expectedSequence() - 1, reader.chainHash()};
    }
  }

  private static void pumpUntil(final TradingProcess p, final BooleanSupplier done, final String what) {
    final long deadline = System.nanoTime() + 60 * SECOND;
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "timed out waiting for " + what + ": " + p.statusJson());
      p.pump();
    }
  }

  /** What the first process looked like when its journals were copied. */
  private record Stopped(long digest, long outFrames, long outHash, long inFrames, long quotesSent, long fills,
      int liveChildren, long simulatedNanos) {}

  /** Runs a process across the peak in {@code live}, copies its directory to {@code image} and closes it. */
  private Stopped runAndStop(final Path live, final Path image, final TradingProcess.Options options)
      throws IOException {
    final TradingProcess p = TradingProcess.start(options);
    try {
      assertFalse(p.recovered());
      assertTrue(p.recoveryLog().isEmpty());
      p.step(6 * MINUTE, STEP);
      final DeskGroup desk = p.group(0).desk();
      assertTrue(desk.desk().quoting().quotesSent() > 0, "quotes sent before the stop");
      assertTrue(desk.desk().quoting().fills() > 0, "fills before the stop");
      assertTrue(desk.hedgesRouted() > 0, "hedges routed before the stop");
      // in ring mode the files trail the loop by the rings' backlog; direct mode returns at once
      p.group(0).journals().awaitDrained(1, java.util.concurrent.TimeUnit.MINUTES);
      final long[] out = journalEnd(p.group(0).dir(), "out");
      final long[] in = journalEnd(p.group(0).dir(), "in");
      final Stopped stopped =
          new Stopped(desk.stateDigest(p.universe().instrumentIds()), out[0], out[1], in[0],
              desk.desk().quoting().quotesSent(), desk.desk().quoting().fills(), desk.liveChildren(),
              p.clock().epochNanos());
      crashImage(live, image);
      return stopped;
    } finally {
      p.close();
    }
  }

  @Test
  void aProcessRestartedOnItsJournalsIsWhereItStoppedAndTradesAgainAfterReconciling() throws IOException {
    final Path live = Files.createDirectories(root.resolve("live"));
    final Path image = root.resolve("image");
    final Stopped stopped = runAndStop(live, image, options(live));
    assertTrue(SegmentedJournal.segmentIndices(image.resolve("usd"), "in").length >= 2,
        "the crash image holds the prepared, unused next segment");

    final TradingProcess p = TradingProcess.start(options(image));
    try {
      final DeskGroup desk = p.group(0).desk();
      // the state at the last journaled frame, by replay alone
      assertTrue(p.recovered());
      assertEquals(stopped.digest(), p.recoveredStateDigest(0), "desk state equals that of the process that never stopped");
      assertEquals(stopped.outHash(), p.recoveredReplayHash(0), "replay reproduced the recorded outbound journal");
      assertEquals(stopped.inFrames(), p.recoveredFrames());
      assertEquals(0, p.outboundCompleted());
      assertEquals(0, p.journalTornTails());
      assertFalse(p.recoveryLog().isEmpty());
      assertTrue(p.recoveryLog().stream().anyMatch(l -> l.contains("replayed " + stopped.inFrames() + " inbound frames")),
          p.recoveryLog().toString());
      // time carried on from the journal, not from the start of the day
      assertTrue(p.clock().epochNanos() >= stopped.simulatedNanos() - STEP, "simulated time resumes at the stop");

      // back in the market only through the sessions: reconciled with the CLOB, book rebuilt
      pumpUntil(p, () -> p.notReadyReason() == null, "readiness after the restart");
      assertEquals(1, p.clobSession().reconciliations(), "the CLOB session reconciled its orders after the restart");
      assertEquals(1, desk.reconciliations());
      assertFalse(p.clobSession().isReconciling());
      // the venue cancelled on disconnect, so it lists none of the children the routers still held
      assertEquals(desk.reconcileChecked(), desk.reconcileMissing());
      assertEquals(0, desk.reconcileOrphans());
      assertFalse(desk.reconciling());
      assertEquals(0, p.venues().clob().liveOrders(io.ratesalgo.app.loop.AppIds.COMP_ID).size());
      assertTrue(p.feed().synced());
      assertTrue(p.feed().rebuilds() >= 1);
      final String status = p.statusJson();
      assertTrue(status.contains("\"recovered\":true") && status.contains("\"ready\":true"), status);
      assertTrue(status.contains("\"recoveredFrames\":" + stopped.inFrames()), status);
      assertNull(p.haltReason());
      assertFalse(p.killSwitchEngaged(), "a restart is not a kill: the desk may quote again");
      // nothing was booked twice on the way back up
      assertEquals(stopped.fills(), desk.desk().quoting().fills());
      assertEquals(stopped.quotesSent(), desk.desk().quoting().quotesSent());

      // and it trades again
      p.step(3 * MINUTE, STEP);
      assertTrue(desk.desk().quoting().quotesSent() > stopped.quotesSent(), "quoting resumed after the restart");
      assertNull(p.notReadyReason());
    } finally {
      p.close();
    }
    // the journals of the stop and of the life after it replay as one run
    final ReplayResult replay = TradingProcessMain.replayGroup(image.resolve("usd"));
    assertTrue(replay.matches(), replay.toString());
    assertTrue(replay.outboundRecorded() > stopped.outFrames());
    // and the process that was copied replays to the hash the restart reproduced
    final ReplayResult original = TradingProcessMain.replayGroup(live.resolve("usd"));
    assertTrue(original.matches(), original.toString());
    assertEquals(stopped.outHash(), original.replayHash());
  }

  @Test
  void aTornInboundTailIsCutCountedAndReportedAndACorruptChainRefusesToStart() throws IOException {
    final Path live = Files.createDirectories(root.resolve("live"));
    final Path image = root.resolve("image");
    final Stopped stopped = runAndStop(live, image, options(live));

    // a frame whose length reached the disk and whose bytes did not, after the last whole frame
    final Path torn = crashImage(image, root.resolve("torn"));
    final Path segment = SegmentedJournal.segmentPath(torn.resolve("usd"), "in", 1);
    final long tail = endOfData(segment);
    try (RandomAccessFile f = new RandomAccessFile(segment.toFile(), "rw")) {
      f.seek(tail);
      f.write(new byte[] {96, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7});
    }
    final TradingProcess p = TradingProcess.start(options(torn));
    try {
      assertTrue(p.recovered());
      assertEquals(1, p.journalTornTails());
      assertTrue(p.recoveryLog().stream().anyMatch(l -> l.contains("TORN TAIL")), p.recoveryLog().toString());
      assertTrue(p.statusJson().contains("\"journalTornTails\":1"), p.statusJson());
      assertEquals(stopped.digest(), p.recoveredStateDigest(0), "the cut frame was never whole: nothing is lost");
      assertEquals(stopped.inFrames(), p.recoveredFrames());
    } finally {
      p.close();
    }

    // a flipped bit in the middle of the chain: no cut repairs that
    final Path corrupt = crashImage(image, root.resolve("corrupt"));
    final Path first = SegmentedJournal.segmentPath(corrupt.resolve("usd"), "in", 1);
    try (RandomAccessFile f = new RandomAccessFile(first.toFile(), "rw")) {
      f.seek(32 + 32 + 4);
      final int b = f.read();
      f.seek(32 + 32 + 4);
      f.write(b ^ 0x20);
    }
    final JournalCorruptionException refused =
        assertThrows(JournalCorruptionException.class, () -> TradingProcess.start(options(corrupt)).close());
    assertTrue(refused.getMessage().contains("refusing to start"), refused.getMessage());

    // an inbound journal with no outbound journal is not a state to guess at
    final Path half = crashImage(image, root.resolve("half"));
    try (Stream<Path> files = Files.list(half.resolve("usd"))) {
      for (final Path f : files.filter(x -> x.getFileName().toString().startsWith("out.")).toList()) {
        Files.delete(f);
      }
    }
    assertTrue(assertThrows(IllegalStateException.class, () -> TradingProcess.start(options(half)).close())
        .getMessage().contains("no outbound journal"));
  }

  @Test
  void withSequenceNumbersResumedARestartBooksNothingTwice() throws IOException {
    final TradingProcess.Fix resume = TradingProcess.Fix.SIMULATOR.withResetOnLogon(false);
    final Path live = Files.createDirectories(root.resolve("live"));
    final Path image = root.resolve("image");
    final long[] positions;
    final long d2cOut;
    final Stopped stopped;
    final TradingProcess first = TradingProcess.start(options(live).withFix(resume));
    try {
      first.step(6 * MINUTE, STEP);
      final DeskGroup desk = first.group(0).desk();
      assertTrue(desk.desk().quoting().fills() > 0 && desk.hedgeFills() > 0);
      positions = positions(first);
      d2cOut = first.d2cSession().session().nextOutSeq();
      assertTrue(d2cOut > 2, "the session has sent more than its Logon");
      assertEquals(first.d2cSession().session().expectedInSeq(), first.d2cSession().safeInSeq(),
          "with the loops idle everything received has been journaled");
      final long[] out = journalEnd(first.group(0).dir(), "out");
      stopped = new Stopped(desk.stateDigest(first.universe().instrumentIds()), out[0], out[1],
          journalEnd(first.group(0).dir(), "in")[0], desk.desk().quoting().quotesSent(), desk.desk().quoting().fills(),
          desk.liveChildren(), first.clock().epochNanos());
      crashImage(live, image);
    } finally {
      first.close();
    }

    final TradingProcess p = TradingProcess.start(options(image).withFix(resume));
    try {
      assertTrue(p.recovered());
      assertEquals(stopped.digest(), p.recoveredStateDigest(0));
      pumpUntil(p, () -> p.notReadyReason() == null, "readiness after the restart");
      // the sessions carried their numbers on instead of starting at 1
      assertTrue(p.d2cSession().session().nextOutSeq() > d2cOut, "outbound sequence resumed: "
          + p.d2cSession().session().nextOutSeq() + " after " + d2cOut);
      assertTrue(p.clobSession().session().expectedInSeq() > 2);
      assertEquals(0, p.d2cSession().logonsRefused() + p.clobSession().logonsRefused());
      // whatever the venues resent, every fill is booked once
      final DeskGroup desk = p.group(0).desk();
      assertEquals(stopped.fills(), desk.desk().quoting().fills());
      org.junit.jupiter.api.Assertions.assertArrayEquals(positions, positions(p), "positions unchanged by the restart");
      p.step(2 * MINUTE, STEP);
      assertTrue(desk.desk().quoting().quotesSent() > stopped.quotesSent(), "quoting resumed: " + p.statusJson()
          + " d2c " + p.d2cSession().state() + " clob " + p.clobSession().state() + " rfqs " + desk.desk().quoting().rfqs()
          + " venue rfqs " + p.venues().d2c().rfqCount() + " passes " + desk.desk().quoting().passes());
      // ids the restarted venue hands out do not collide with ones the desk has booked
      assertEquals(0, desk.duplicateFills());
    } finally {
      p.close();
    }
    assertTrue(TradingProcessMain.replayGroup(image.resolve("usd")).matches());
  }

  /**
   * Restart recovery in ring mode (DR-R6-L04): a crash image of a process whose journals were
   * written through rings, with the WORD64 hash, restarts in ring mode and, on a second copy, in
   * direct mode. The files are the same in both modes, and the outbound journal never runs ahead of
   * the inbound one, so either restart replays to the recorded hash.
   */
  @Test
  void aRingModeCrashImageRestartsInEitherMode() throws IOException {
    final TradingProcess.Tuning ring =
        TradingProcess.Tuning.DEFAULT.withJournals(
            io.ratesalgo.core.LoopJournals.Settings.DEFAULT
                .withMode(io.ratesalgo.core.JournalMode.RING)
                .withHashAlgorithm(io.ratesalgo.journal.ChainHash.WORD64));
    final Path live = Files.createDirectories(root.resolve("ring-live"));
    final Path image = root.resolve("ring-image");
    final Stopped stopped = runAndStop(live, image, options(live).withTuning(ring));
    final Path second = crashImage(image, root.resolve("ring-image-direct"));
    for (final Path dir : new Path[] {image, second}) {
      final TradingProcess.Tuning tuning = dir.equals(image) ? ring : TradingProcess.Tuning.DEFAULT;
      final TradingProcess p = TradingProcess.start(options(dir).withTuning(tuning));
      try {
        assertTrue(p.recovered());
        assertEquals(stopped.digest(), p.recoveredStateDigest(0), dir + ": state by replay alone");
        assertEquals(stopped.outHash(), p.recoveredReplayHash(0), dir + ": replay reproduced the recorded outbound");
        assertEquals(io.ratesalgo.journal.ChainHash.WORD64, p.group(0).journals().hashAlgorithm(),
            "an existing journal keeps the hash its files name, whatever the tuning says");
        pumpUntil(p, () -> p.notReadyReason() == null, "readiness after the restart");
        p.step(MINUTE, STEP);
        assertNull(p.haltReason());
      } finally {
        p.close();
      }
      final ReplayResult replay = TradingProcessMain.replayGroup(dir.resolve("usd"));
      assertTrue(replay.matches(), dir + ": " + replay);
    }
  }

  @Test
  void aCleanStopRestartsTheSameWay() throws IOException {
    final Path dir = Files.createDirectories(root.resolve("clean"));
    final long digest;
    final TradingProcess first = TradingProcess.start(options(dir));
    try {
      first.step(3 * MINUTE, STEP);
      digest = first.group(0).desk().stateDigest(first.universe().instrumentIds());
    } finally {
      first.close();
    }
    final TradingProcess p = TradingProcess.start(options(dir));
    try {
      assertTrue(p.recovered());
      assertEquals(digest, p.recoveredStateDigest(0));
      assertNotEquals(0L, p.recoveredReplayHash(0));
      pumpUntil(p, () -> p.notReadyReason() == null, "readiness after the restart");
      assertThrows(IllegalStateException.class, () -> {
        p.startThreads(1.0);
        p.driveGateways();
      });
    } finally {
      p.close();
    }
  }

  private static long[] positions(final TradingProcess p) {
    final int[] ids = p.universe().instrumentIds();
    final long[] positions = new long[ids.length];
    for (int i = 0; i < ids.length; i++) {
      positions[i] = p.group(0).desk().desk().risk().position(ids[i]);
    }
    return positions;
  }

  /** Offset just past the last non-zero byte of a segment, rounded up to the frame alignment. */
  private static long endOfData(final Path segment) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(segment.toFile(), "r")) {
      final byte[] chunk = new byte[1 << 20];
      long last = 0;
      long position = 0;
      int read;
      while ((read = f.read(chunk)) > 0) {
        for (int i = 0; i < read; i++) {
          if (chunk[i] != 0) {
            last = position + i;
          }
        }
        position += read;
      }
      return (last + 8) & ~7L;
    }
  }
}
