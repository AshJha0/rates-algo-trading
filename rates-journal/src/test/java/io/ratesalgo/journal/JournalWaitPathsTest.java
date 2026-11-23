package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.common.time.ManualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The branches of the journal that depend on which thread gets there first: a roll that waits
 * for the next segment, a full ring, a close while a segment is being prepared, and a background
 * thread that fails or hangs.
 *
 * <p>Left to chance these lines run on some machines and not on others, and the module's line
 * coverage moved across its floor with the runner's speed. Here each one is forced: the clock
 * passed to the journal holds (or fails) the segment-preparing thread at the one point where
 * that thread reads it, the header stamp of a new segment, and a latch releases it. No test here
 * sleeps or assumes a speed; the waits are on conditions, with a one-minute limit that only a
 * broken build reaches.
 */
class JournalWaitPathsTest {

  private static final int SEGMENT = 4096;
  private static final int PAYLOAD = 100;

  @TempDir Path dir;

  /** A clock that can hold or fail the thread that prepares segments; other threads read time. */
  private static final class GateClock implements Clock {
    private final ManualClock time = new ManualClock(1_000L);
    private final CountDownLatch gate = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicInteger held = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean hold;
    private volatile RuntimeException failure;

    static GateClock holding() {
      final GateClock c = new GateClock();
      c.hold = true;
      return c;
    }

    static GateClock failing(final RuntimeException e) {
      final GateClock c = new GateClock();
      c.failure = e;
      return c;
    }

    void release() {
      gate.countDown();
    }

    /**
     * Waits until {@code threads} preparing threads are held at the gate. A test that closes a
     * journal to see what a held preparer does must wait for this first: a preparer that has not
     * yet reached the gate sees the stop flag earlier and simply ends.
     */
    void awaitHeld(final int threads) {
      await("the preparing thread is held", () -> held.get() == threads);
    }

    @Override
    public long nanoTime() {
      return time.nanoTime();
    }

    @Override
    public long epochNanos() {
      if (Thread.currentThread().getName().startsWith("journal-prepare-")) {
        final RuntimeException e = failure;
        if (e != null) {
          throw e;
        }
        if (hold) {
          held.incrementAndGet();
          try {
            if (!gate.await(1, TimeUnit.MINUTES)) {
              throw new IllegalStateException("the test never released the preparing thread");
            }
          } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        }
      }
      return time.epochNanos();
    }
  }

  private static UnsafeBuffer payload() {
    return new UnsafeBuffer(new byte[PAYLOAD]);
  }

  private static void await(final String what, final BooleanSupplier condition) {
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("not within one minute: " + what);
      }
      Thread.onSpinWait();
    }
  }

  private static Thread start(final String name, final Runnable body) {
    final Thread t = new Thread(body, name);
    t.setDaemon(true);
    t.start();
    return t;
  }

  private static void join(final Thread t) {
    try {
      t.join(TimeUnit.MINUTES.toMillis(1));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertFalse(t.isAlive(), t.getName() + " ended");
  }

  private long frames(final String name) {
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, name)) {
      long n = 0;
      while (r.next((s, t, id, v, b, o, l) -> {})) {
        n++;
      }
      return n;
    }
  }

  private boolean noPendingFile() {
    try (var files = Files.list(dir)) {
      return files.noneMatch(f -> f.getFileName().toString().endsWith(".pending"));
    } catch (final IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** Appends until the journal is on {@code segment}; returns the frames appended. */
  private static int appendUntilSegment(final SegmentedJournal j, final int segment) {
    final UnsafeBuffer p = payload();
    int n = 0;
    while (j.segmentIndex() < segment) {
      j.append(1, 0, p, 0, PAYLOAD);
      n++;
    }
    return n;
  }

  @Test
  void aRollWaitsForAHeldSegmentAndGoesOnWhenItIsReady() {
    final GateClock clock = GateClock.holding();
    final int appended;
    try (SegmentedJournal j = SegmentedJournal.create(dir, "held", SEGMENT, clock, true)) {
      assertFalse(j.nextSegmentReady());
      final Thread releaser = start("releaser", () -> {
        await("the roll waits", () -> j.rollWaits() == 1);
        clock.release();
      });
      appended = appendUntilSegment(j, 2);
      join(releaser);
      assertEquals(1, j.rollWaits(), "the one roll found no segment ready and waited");
      await("the segment after is prepared", j::nextSegmentReady);
      assertTrue(j.preparerProgress() > 0);
    }
    assertEquals(appended, frames("held"));
    assertFalse(Files.exists(SegmentedJournal.segmentPath(dir, "held", 3)), "the unused prepared segment is removed");
    assertTrue(noPendingFile());
  }

  @Test
  void aRollFailsWhenThePreparingThreadFailed() {
    final IllegalStateException cause = new IllegalStateException("disk gone");
    final SegmentedJournal j = SegmentedJournal.create(dir, "failed", SEGMENT, GateClock.failing(cause), true);
    final IllegalStateException e = assertThrows(IllegalStateException.class, () -> appendUntilSegment(j, 2));
    assertTrue(e.getMessage().contains("could not prepare the next segment"), e.getMessage());
    assertSame(cause, e.getCause());
    assertEquals(1, j.segmentIndex(), "the full segment stays current");
    j.close();
    assertTrue(frames("failed") > 0, "what was appended before the failure is in the file");
    assertTrue(noPendingFile(), "the half-made segment was removed, so its mapping was released");
  }

  /** Before the bound, this append never returned: the appender spun in take() for ever. */
  @Test
  void aRollGivesUpWhenThePreparingThreadMakesNoProgress() {
    final GateClock clock = GateClock.holding();
    final SegmentedJournal j =
        SegmentedJournal.create(dir, "stalled", SEGMENT, clock, true, TimeUnit.MILLISECONDS.toNanos(200));
    final IllegalStateException e = assertThrows(IllegalStateException.class, () -> appendUntilSegment(j, 2));
    assertTrue(e.getMessage().contains("made no progress for 200 ms"), e.getMessage());
    clock.release();
    await("the held segment is prepared after the release", j::nextSegmentReady);
    final int more = appendUntilSegment(j, 2);
    j.close();
    assertTrue(more > 0 && frames("stalled") > more, "the journal is usable once the preparer recovers");
  }

  /** Before the bound, the appender spun for ever when the journal was closed under a waiting roll. */
  @Test
  void aRollWaitingWhenTheJournalIsClosedEndsInsteadOfSpinning() {
    final GateClock clock = GateClock.holding();
    final SegmentedJournal j = SegmentedJournal.create(dir, "closed", SEGMENT, clock, true);
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final Thread appender = start("appender", () -> {
      try {
        appendUntilSegment(j, 2);
      } catch (final Throwable t) {
        thrown.set(t);
      }
    });
    await("the roll waits", () -> j.rollWaits() == 1);
    clock.awaitHeld(1);
    final Thread closer = start("closer", j::close);
    // the closer sets the stop flag and then joins the preparing thread in timed steps
    await("the close is waiting for the preparing thread", () -> closer.getState() == Thread.State.TIMED_WAITING);
    clock.release();
    join(closer);
    join(appender);
    assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
    assertTrue(thrown.get().getMessage().contains("the background thread has stopped"), thrown.get().getMessage());
    assertFalse(Files.exists(SegmentedJournal.segmentPath(dir, "closed", 2)), "the abandoned segment is removed");
    assertTrue(noPendingFile());
  }

  @Test
  void closeAfterTheNextSegmentIsReadyRemovesItAndCloseDuringPreparationAbandonsIt() {
    final SegmentedJournal ready = SegmentedJournal.create(dir, "ready", SEGMENT, new ManualClock(), true);
    await("the next segment is prepared", ready::nextSegmentReady);
    assertTrue(Files.exists(SegmentedJournal.segmentPath(dir, "ready", 2)));
    ready.close();
    assertFalse(Files.exists(SegmentedJournal.segmentPath(dir, "ready", 2)));

    final GateClock clock = GateClock.holding();
    final SegmentedJournal held = SegmentedJournal.create(dir, "abandon", SEGMENT, clock, true);
    clock.awaitHeld(1);
    final Thread closer = start("closer", held::close);
    await("the close is waiting for the preparing thread", () -> closer.getState() == Thread.State.TIMED_WAITING);
    clock.release();
    join(closer);
    assertFalse(Files.exists(SegmentedJournal.segmentPath(dir, "abandon", 2)));
    assertTrue(noPendingFile());
    assertEquals(0, frames("abandon"));
  }

  @Test
  void closeGivesUpWhenThePreparingThreadIsStuckAndKeepsAnInterrupt() {
    final GateClock clock = GateClock.holding();
    final SegmentedJournal stuck =
        SegmentedJournal.create(dir, "stuck", SEGMENT, clock, true, TimeUnit.MILLISECONDS.toNanos(200));
    clock.awaitHeld(1);
    final IllegalStateException e = assertThrows(IllegalStateException.class, stuck::close);
    assertTrue(e.getMessage().contains("made no progress for 200 ms and did not stop"), e.getMessage());

    final SegmentedJournal interrupted = SegmentedJournal.create(dir, "interrupted", SEGMENT, clock, true);
    clock.awaitHeld(2);
    Thread.currentThread().interrupt();
    assertThrows(IllegalStateException.class, interrupted::close);
    assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");

    clock.release();
    await("both preparing threads abandon their segments", this::noPendingFile);
  }

  @Test
  void aFullRingMakesTheProducerWaitUntilTheJournalThreadDrains() {
    final GateClock clock = GateClock.holding();
    final RingJournal ring =
        RingJournal.start("full", SegmentedJournal.create(dir, "full", SEGMENT, clock, true), clock, 64 << 10);
    // the journal thread stops at its first roll (the next segment is held), so the ring fills
    final Thread releaser = start("releaser", () -> {
      await("the producer waits on a full ring", () -> ring.producerWaits() == 1);
      clock.release();
    });
    final UnsafeBuffer p = payload();
    long appended = 0;
    while (ring.producerWaits() == 0) {
      ring.append(1, 0, p, 0, PAYLOAD);
      appended++;
    }
    join(releaser);
    assertEquals(appended + 1, ring.nextSequence());
    ring.awaitDrained(1, TimeUnit.MINUTES);
    assertTrue(ring.rollWaits() >= 1, "the journal thread's first roll waited for the held segment");
    ring.close();
    assertEquals(appended, frames("full"));
  }

  /** Before the retry, a close that gave up left the files mapped with no way to close them. */
  @Test
  void aCloseThatGaveUpCanBeRetriedOnceTheJournalThreadMoves() {
    final GateClock clock = GateClock.holding();
    final RingJournal ring =
        RingJournal.start("retry", SegmentedJournal.create(dir, "retry", SEGMENT, clock, true), clock, 64 << 10);
    final UnsafeBuffer p = payload();
    final int n = 200; // five segments' worth: the journal thread stops at its first roll
    for (int i = 0; i < n; i++) {
      ring.append(1, 0, p, 0, PAYLOAD);
    }
    await("the journal thread waits at its first roll", () -> ring.rollWaits() == 1);
    final IllegalStateException timeout =
        assertThrows(IllegalStateException.class, () -> ring.awaitDrained(50, TimeUnit.MILLISECONDS));
    assertTrue(timeout.getMessage().contains("not drained within 50 MILLISECONDS"), timeout.getMessage());

    Thread.currentThread().interrupt();
    final IllegalStateException interrupted =
        assertThrows(IllegalStateException.class, () -> ring.closeWithin(TimeUnit.SECONDS.toNanos(30)));
    assertTrue(interrupted.getMessage().contains("interrupted while closing"), interrupted.getMessage());
    assertTrue(Thread.interrupted(), "the interrupt is kept for the caller");

    final IllegalStateException stalled =
        assertThrows(IllegalStateException.class, () -> ring.closeWithin(TimeUnit.MILLISECONDS.toNanos(200)));
    assertTrue(stalled.getMessage().contains("made no progress for 200 ms while closing: backlog "), stalled.getMessage());
    assertThrows(IllegalStateException.class, () -> ring.append(1, 0, p, 0, PAYLOAD), "appends stay refused");

    clock.release();
    ring.close();
    ring.close();
    assertEquals(n, frames("retry"), "every frame queued before the first close is in the file");
  }

  @Test
  void aFailureOfTheJournalThreadReachesTheProducerAndTheClose() {
    final IllegalStateException cause = new IllegalStateException("disk gone");
    final GateClock clock = GateClock.failing(cause);
    final RingJournal ring =
        RingJournal.start("dead", SegmentedJournal.create(dir, "dead", SEGMENT, clock, true), clock, 64 << 10);
    final UnsafeBuffer p = payload();
    final IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
      for (int i = 0; i < 10_000_000; i++) {
        ring.append(1, 0, p, 0, PAYLOAD);
      }
    });
    assertEquals("journal thread failed", e.getMessage());
    assertSame(cause, e.getCause().getCause(), "the roll's failure carries the preparing thread's");
    assertThrows(IllegalStateException.class, () -> ring.awaitDrained(1, TimeUnit.SECONDS));
    assertThrows(IllegalStateException.class, ring::close, "the close reports the failure after closing the files");
    assertTrue(frames("dead") > 0);
  }

  @Test
  void anAbandonedOrFailedCreateLeavesNoFileAndNoMapping() throws IOException {
    final Path abandoned = dir.resolve("abandoned.raj");
    assertNull(JournalWriter.create(abandoned, 3 << 20, new ManualClock(), chunks -> chunks < 2));
    assertFalse(Files.exists(abandoned), "a create stopped after two of three chunks removes the file");

    final Path failed = dir.resolve("failed.raj");
    final Clock broken = new Clock() {
      @Override
      public long nanoTime() {
        return 0;
      }

      @Override
      public long epochNanos() {
        throw new IllegalStateException("no time");
      }
    };
    assertThrows(IllegalStateException.class, () -> JournalWriter.create(failed, 1024, broken));
    assertFalse(Files.exists(failed), "deleting it would fail on Windows if the mapping were still held");
  }

  @Test
  void theWarmUpExercisesBothWaitPathsAndRemovesItsScratchFiles() {
    final Path scratch = dir.resolve("warm-up");
    final JournalWarmUp.Result r = JournalWarmUp.exerciseWaitPaths(scratch, new ManualClock());
    assertTrue(r.rollWaits() > 0, "waited rolls: " + r.rollWaits());
    assertTrue(r.producerWaits() > 0, "full-ring waits: " + r.producerWaits());
    assertFalse(Files.exists(scratch));
  }
}
