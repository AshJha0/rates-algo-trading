package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalRecovery;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.journal.SegmentedJournalReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A loop that stops without closing anything and restarts on its journals ends up where a loop
 * that never stopped is: same handler state, same timers, same outbound journal.
 */
class LoopRecoveryTest {

  private static final int SEGMENT = 1 << 16;
  private static final long START = 1_767_225_600_000_000_000L;

  @TempDir Path root;

  /** A loop with its rings and journals, driven by hand on a clock the test moves. */
  private static final class Run implements AutoCloseable {
    final QuoteApp app = new QuoteApp(true);
    final ManualClock live;
    // large enough that the run never wraps it: a read stops at the wrap, and where the wrap falls
    // is ring state a restart resets, not loop state
    final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 17);
    final OneToOneRingBuffer[] outbound = {
      RingBufferContract.newOutbound(RingBufferContract.MIN_CAPACITY),
      RingBufferContract.newOutbound(RingBufferContract.DEFAULT_OUTBOUND_CAPACITY)
    };
    final InboundPublisher publisher = new InboundPublisher(inbound);
    final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    LoopJournals journals;
    EventLoop loop;
    RecoveryClock recoveryClock;
    LoopRecovery.Result recovered;

    /** A fresh loop on new journals. */
    Run(final Path dir, final JournalMode mode) {
      this.live = new ManualClock(START);
      final EventClock clock = new EventClock(new AnchoredClock(live));
      journals = LoopJournals.create(dir, "in", "out", SEGMENT, clock, mode, 64 << 10);
      loop = build(clock);
    }

    /** A loop restarted on the journals in {@code dir}, with the live clock reading {@code nowEpochNanos}. */
    Run(final Path dir, final JournalMode mode, final long nowEpochNanos, final QuoteApp replacement) {
      this.live = new ManualClock(nowEpochNanos);
      JournalRecovery.repair(dir, "in");
      JournalRecovery.repair(dir, "out");
      recoveryClock = new RecoveryClock(LoopRecovery.firstFrameNanos(dir, "in"));
      final EventClock clock = new EventClock(new AnchoredClock(recoveryClock));
      journals = LoopJournals.open(dir, "in", "out", SEGMENT, clock, mode, 64 << 10);
      try {
        loop = (replacement == null ? app : replacement).create(wire(EventLoop.builder(clock)));
        recovered =
            LoopRecovery.run(loop, recoveryClock, dir, "in", "out", journals.outbound(), LoopRecovery.Observer.NONE,
                LoopRecovery.Observer.NONE);
        recoveryClock.goLive(live);
      } catch (final RuntimeException e) {
        journals.close();
        throw e;
      }
    }

    private EventLoop build(final EventClock clock) {
      return app.create(wire(EventLoop.builder(clock)));
    }

    private EventLoop.Builder wire(final EventLoop.Builder b) {
      return b.inbound(inbound).outbound(outbound[0]).outbound(outbound[1]).journals(journals);
    }

    /** Rounds {@code from} to {@code to}: requests, timers, a slow gateway and backpressure. */
    void rounds(final int from, final int to) {
      for (int round = from; round < to; round++) {
        for (int i = 0; i < 40; i++) {
          publisher.offer(scratch, 0, Msgs.quoteRequest(scratch, 0, round * 100L + i + 1));
        }
        loop.doWork();
        live.advance(700_000L);
        loop.doWork();
        // A slow gateway up to the stop, so the journal holds backpressure; a prompt one after it.
        // How much a ring holds depends on where its tail stands, which a restart resets, so drops
        // after the stop would differ between the two runs for a reason that is not loop state.
        if (round % 3 == 0 || round >= 7) {
          outbound[0].read((t, b, i, l) -> {}, Integer.MAX_VALUE);
        }
        outbound[1].read((t, b, i, l) -> {}, Integer.MAX_VALUE);
      }
    }

    /**
     * Lets the loop read everything in its inbound ring. Inputs still in a ring when a process
     * stops were never journaled and are lost with it, so the stop the two runs are compared at is
     * one with nothing waiting.
     */
    void settle() {
      while (inbound.size() > 0) {
        loop.doWork();
      }
      outbound[0].read((t, b, i, l) -> {}, Integer.MAX_VALUE);
      outbound[1].read((t, b, i, l) -> {}, Integer.MAX_VALUE);
    }

    @Override
    public void close() {
      journals.close();
    }
  }

  private static long hash(final Path dir, final String name) {
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name)) {
      while (reader.next((s, t, id, v, b, o, l) -> {})) {
        // reading to the end
      }
      return reader.chainHash() ^ reader.expectedSequence();
    }
  }

  /** Frame by frame, so that a difference names the first frame that differs. */
  private static void assertSameJournal(final Path expected, final Path actual, final String name) {
    final List<String> a = frames(expected, name);
    final List<String> b = frames(actual, name);
    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
      assertEquals(a.get(i), b.get(i), name + " frame " + (i + 1));
    }
    assertEquals(a.size(), b.size(), name + " frames");
    assertEquals(hash(expected, name), hash(actual, name));
  }

  private static List<String> frames(final Path dir, final String name) {
    final List<String> frames = new ArrayList<>();
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name)) {
      while (reader.next(
          (s, t, id, v, b, o, l) -> {
            final byte[] bytes = new byte[l];
            b.getBytes(o, bytes);
            frames.add("t=" + (t - START) + " template=" + id + " " + java.util.HexFormat.of().formatHex(bytes));
          })) {
        // collecting
      }
    }
    return frames;
  }

  private Path dir(final String name) throws IOException {
    return Files.createDirectories(root.resolve(name));
  }

  /**
   * Runs rounds 0 to 6 in {@code dir} and stops; returns the wall time it stopped at. Round 6 ends
   * with both gateway rings drained, so the stop leaves nothing in them: a restart starts with empty
   * rings, and the run it is compared with must be at the same point.
   */
  private static long firstHalf(final Path dir, final JournalMode mode) {
    try (Run r = new Run(dir, mode)) {
      r.rounds(0, 7);
      r.settle();
      assertTrue(r.app.backpressureEvents > 0 && r.app.timersFired > 0, "the run should have timers and backpressure");
      return r.live.epochNanos();
    }
  }

  @ParameterizedTest
  @EnumSource(JournalMode.class)
  void aRestartedLoopIsWhereALoopThatNeverStoppedIs(final JournalMode mode) throws IOException {
    final Path straight = dir("straight");
    final QuoteApp whole;
    final long quotesAtStop;
    final long timersAtStop;
    try (Run r = new Run(straight, mode)) {
      r.rounds(0, 7);
      r.settle();
      quotesAtStop = r.app.quotes;
      timersAtStop = r.app.timersFired;
      r.rounds(7, 12);
      whole = r.app;
    }

    final Path restarted = dir("restarted");
    final long stoppedAt = firstHalf(restarted, mode);
    try (Run r = new Run(restarted, mode, stoppedAt, null)) {
      // the state at the stop, rebuilt by replay alone
      assertEquals(quotesAtStop, r.app.quotes);
      assertEquals(timersAtStop, r.app.timersFired);
      assertTrue(r.recovered.inboundFrames() > 0);
      assertTrue(r.recovered.outboundVerified() > 0);
      assertEquals(0, r.recovered.outboundCompleted());
      assertFalse(r.loop.isRecovering());
      assertEquals(0, r.outbound[0].size() + r.outbound[1].size(), "replay sends nothing");
      r.rounds(7, 12);
      assertEquals(whole.quotes, r.app.quotes);
      assertEquals(whole.timersFired, r.app.timersFired);
      assertEquals(whole.backpressureEvents, r.app.backpressureEvents);
    }
    // the journals of the restarted run are the journals of the run that never stopped
    assertSameJournal(straight, restarted, "in");
    assertSameJournal(straight, restarted, "out");
    // and they replay as one run
    final ReplayResult replay = LoopReplay.run(restarted, "in", "out", new QuoteApp(true));
    assertTrue(replay.matches(), replay.toString());
  }

  @Test
  void workTheStoppedProcessHadNotJournaledIsCompletedNotSent() throws IOException {
    final Path d = dir("cut");
    final long stoppedAt = firstHalf(d, JournalMode.DIRECT);
    final long whole = hash(d, "out");
    // the process died after journaling its last inputs and before journaling what they caused
    final int cut = cutLastFrames(d, "out", 3);
    assertTrue(hash(d, "out") != whole);
    final List<Integer> produced = new ArrayList<>();
    try (Run r = new Run(d, JournalMode.DIRECT, stoppedAt, null)) {
      assertEquals(cut, r.recovered.outboundCompleted());
      assertEquals(0, r.outbound[0].size() + r.outbound[1].size(), "completed frames are journaled, not sent");
      produced.add((int) r.recovered.outboundVerified());
    }
    assertEquals(whole, hash(d, "out"));
    assertTrue(produced.get(0) > 0);
    assertTrue(LoopReplay.run(d, "in", "out", new QuoteApp(true)).matches());
  }

  @Test
  void aBuildThatDoesNotReproduceTheJournalRefusesToGoLive() throws IOException {
    final Path d = dir("diverged");
    final long stoppedAt = firstHalf(d, JournalMode.DIRECT);
    final LoopRecovery.RecoveryException e =
        assertThrows(
            LoopRecovery.RecoveryException.class,
            () -> new Run(d, JournalMode.DIRECT, stoppedAt, new QuoteApp(true, 3_000_000L)).close());
    assertTrue(e.getMessage().contains("diverged") && e.getMessage().contains("outbound frame 1"), e.getMessage());
  }

  @Test
  void anOutboundJournalThatRunsPastTheInboundOneRefusesToGoLive() throws IOException {
    final Path d = dir("lost-input");
    final long stoppedAt = firstHalf(d, JournalMode.DIRECT);
    cutLastFrames(d, "in", 60);
    final LoopRecovery.RecoveryException e =
        assertThrows(LoopRecovery.RecoveryException.class, () -> new Run(d, JournalMode.DIRECT, stoppedAt, null).close());
    assertTrue(e.getMessage().contains("does not produce"), e.getMessage());
  }

  @Test
  void aJournalThatEndsInALoopErrorIsNotResumed() throws IOException {
    final Path d = dir("fault");
    final long stoppedAt;
    try (Run r = new Run(d, JournalMode.DIRECT)) {
      r.rounds(0, 2);
      r.loop.recordError(new IllegalStateException("boom"));
      stoppedAt = r.live.epochNanos();
    }
    final LoopRecovery.RecoveryException e =
        assertThrows(LoopRecovery.RecoveryException.class, () -> new Run(d, JournalMode.DIRECT, stoppedAt, null).close());
    assertTrue(e.getMessage().contains("LoopError"), e.getMessage());

    // frames after the error cannot be explained either
    final Path later = dir("fault-then-more");
    try (Run r = new Run(later, JournalMode.DIRECT)) {
      r.rounds(0, 1);
      r.loop.recordError(new IllegalStateException("boom"));
      r.rounds(1, 2);
    }
    assertTrue(
        assertThrows(LoopRecovery.RecoveryException.class, () -> new Run(later, JournalMode.DIRECT, stoppedAt, null).close())
            .getMessage().contains("after a LoopError"));
  }

  @Test
  void timeCarriesOnFromTheLastFrameByTheTimeTheProcessWasDown() throws IOException {
    final Path d = dir("downtime");
    final long stoppedAt = firstHalf(d, JournalMode.DIRECT);
    final long down = 90_000_000_000L;
    try (Run r = new Run(d, JournalMode.DIRECT, stoppedAt + down, null)) {
      final long lastFrame = r.recovered.lastFrameNanos();
      assertTrue(r.recoveryClock.isLive());
      final long timersBefore = r.app.timersFired;
      r.loop.doWork();
      // wall time is the live clock's, and monotonic time moved by the same amount
      assertEquals(stoppedAt + down, r.loop.clock().epochNanos());
      assertTrue(r.loop.clock().epochNanos() - lastFrame >= down);
      assertTrue(r.app.timersFired >= timersBefore, "timers due while the process was down fire once it is live");
      r.rounds(7, 9);
    }
    assertTrue(LoopReplay.run(d, "in", "out", new QuoteApp(true)).matches(), "the downtime replays as it happened");

    // a live clock behind the journal does not take time back
    final Path behind = dir("behind");
    final long stopped = firstHalf(behind, JournalMode.DIRECT);
    try (Run r = new Run(behind, JournalMode.DIRECT, stopped - 5_000_000_000L, null)) {
      r.loop.doWork();
      assertEquals(r.recovered.lastFrameNanos(), r.loop.clock().epochNanos());
      r.live.advance(1_000L);
      r.loop.doWork();
      assertEquals(r.recovered.lastFrameNanos() + 1_000L, r.loop.clock().epochNanos());
    }
  }

  @Test
  void recoveryIsOncePerLoopAndBeforeTheClockIsLive() throws IOException {
    final Path d = dir("misuse");
    final long stoppedAt = firstHalf(d, JournalMode.DIRECT);
    try (Run r = new Run(d, JournalMode.DIRECT, stoppedAt, null)) {
      assertThrows(IllegalStateException.class, r.loop::endRecovery);
      assertThrows(IllegalStateException.class, () -> r.recoveryClock.goLive(r.live));
      assertThrows(
          IllegalStateException.class,
          () -> LoopRecovery.run(r.loop, r.recoveryClock, d, "in", "out", r.journals.outbound(),
              LoopRecovery.Observer.NONE, LoopRecovery.Observer.NONE));
      r.loop.beginRecovery(JournalSink.NONE);
      assertTrue(r.loop.isRecovering());
      assertThrows(IllegalStateException.class, () -> r.loop.beginRecovery(JournalSink.NONE));
      r.loop.endRecovery();
    }
    assertEquals(-1L, LoopRecovery.firstFrameNanos(dir("empty-journal-dir"), "in"));
    assertThrows(IllegalArgumentException.class,
        () -> LoopJournals.open(root.resolve("empty-journal-dir"), "in", "out", SEGMENT,
            new EventClock(new ManualClock())));
  }

  /** Clears the last {@code frames} frames of a single-segment journal, as if never written. */
  private static int cutLastFrames(final Path dir, final String name, final int frames) throws IOException {
    assertEquals(1, SegmentedJournal.segmentIndices(dir, name).length);
    final List<int[]> extents = new ArrayList<>();
    final Path segment = SegmentedJournal.segmentPath(dir, name, 1);
    final byte[] bytes = Files.readAllBytes(segment);
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name)) {
      while (reader.next((s, t, id, v, b, o, l) -> extents.add(new int[] {o - 32, l}))) {
        // collecting where frames start
      }
    }
    assertTrue(extents.size() > frames);
    final int from = extents.get(extents.size() - frames)[0];
    java.util.Arrays.fill(bytes, from, bytes.length, (byte) 0);
    Files.write(segment, bytes);
    return frames;
  }
}
