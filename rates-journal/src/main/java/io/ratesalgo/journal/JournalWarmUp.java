package io.ratesalgo.journal;

import io.ratesalgo.common.time.Clock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Runs the journals' wait paths before a loop starts, so that the JIT has seen them (DR-INT-06).
 *
 * <p>A roll that finds the next segment not yet prepared spins in {@code SegmentedJournal.Preparer.take};
 * an append to a full ring spins in {@link RingJournal#append}. In a loop that warms up on ordinary
 * traffic neither path runs, so C2 compiles them as never taken. The first real wait then hits an
 * uncommon trap and deoptimises the compiled frames on the loop thread, which allocated 176 bytes
 * in the measured windows of {@code LoopAllocationTest} (confirmed with {@code -Xlog:deoptimization}).
 * Branch profiles belong to the method, not the instance, so exercising both paths on scratch
 * journals is enough: the production journals are never written by the warm-up.
 *
 * <p>The scratch journals use 64 KiB segments and a 64 KiB ring and are appended to in a tight loop
 * until each path has waited {@link #WAITS} times (or a bound on appends is reached), then closed
 * and deleted. It takes well under a second and allocates; call it before the loop's own warm-up.
 */
public final class JournalWarmUp {

  /** Waits wanted on each path. */
  public static final int WAITS = 200;

  private static final int SEGMENT = 64 << 10;
  private static final int RING = 64 << 10;
  private static final int MAX_APPENDS = 4_000_000;
  private static final int PAYLOAD = 57;

  /** Waits seen on each path. */
  public record Result(long rollWaits, long producerWaits) {}

  private JournalWarmUp() {}

  /**
   * Exercises the waited roll and the full-ring wait on scratch journals under {@code scratchDir},
   * which is created and removed again.
   */
  public static Result exerciseWaitPaths(final Path scratchDir, final Clock clock) {
    final UnsafeBuffer payload = new UnsafeBuffer(ByteBuffer.allocateDirect(PAYLOAD));
    long rollWaits;
    long producerWaits;
    try {
      Files.createDirectories(scratchDir);
      try (SegmentedJournal direct = SegmentedJournal.create(scratchDir, "warmup-direct", SEGMENT, clock, true)) {
        for (int i = 0; i < MAX_APPENDS && direct.rollWaits() < WAITS; i++) {
          direct.append(1, 0, payload, 0, PAYLOAD);
        }
        rollWaits = direct.rollWaits();
      }
      try (RingJournal ring =
          RingJournal.start(
              "warmup-ring",
              SegmentedJournal.create(scratchDir, "warmup-ring", SEGMENT, clock, true),
              clock,
              RING)) {
        for (int i = 0; i < MAX_APPENDS && ring.producerWaits() < WAITS; i++) {
          ring.append(1, 0, payload, 0, PAYLOAD);
        }
        producerWaits = ring.producerWaits();
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      delete(scratchDir);
    }
    return new Result(rollWaits, producerWaits);
  }

  private static void delete(final Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      files.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
