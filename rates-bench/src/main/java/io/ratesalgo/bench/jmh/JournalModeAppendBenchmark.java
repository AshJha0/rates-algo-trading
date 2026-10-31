package io.ratesalgo.bench.jmh;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.RingJournal;
import io.ratesalgo.journal.SegmentedJournal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmark: the loop thread's cost of journaling one 57-byte QuoteRequest in each journal
 * mode (DECISIONS.md DR-R2J-03), as the loop calls it.
 *
 * <ul>
 *   <li>{@code direct}: {@link SegmentedJournal#append} into a preallocated segment whose pages
 *       were all touched before the iteration (payload copy, chain hash, header, ordered length);
 *   <li>{@code ring}: {@link RingJournal#append} (claim, stamp, copy, commit into the ring) while
 *       the journal thread drains into the same kind of segment.
 * </ul>
 *
 * <p>Each iteration gets fresh journals, created and touched in the iteration's set-up, so the
 * measured time holds no file creation and no page fault. Iterations are 20 ms so that a segment
 * of {@link #SEGMENT_BYTES} never fills; tear-down throws if one rolled, and in ring mode it
 * reports a ring that filled (the producer then waits for the journal thread, which the number
 * would include).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 20, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 20, timeUnit = TimeUnit.MILLISECONDS)
public class JournalModeAppendBenchmark {

  static final int SEGMENT_BYTES = 128 << 20;
  static final int RING_BYTES = 128 << 20;
  static final int PAYLOAD = 57;

  @Param({"direct", "ring"})
  public String mode;

  /** Segment size; tests lower it. */
  int segmentBytes = SEGMENT_BYTES;

  /** Ring size; tests lower it. */
  int ringBytes = RING_BYTES;

  private final ManualClock clock = new ManualClock();
  private Path dir;
  private SegmentedJournal journal;
  private RingJournal ring;
  private UnsafeBuffer payload;

  @Setup(Level.Trial)
  public void payload() {
    payload = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    for (int i = 0; i < PAYLOAD; i++) {
      payload.putByte(i, (byte) i);
    }
  }

  @Setup(Level.Iteration)
  public void openJournal() throws IOException {
    dir = Files.createTempDirectory("jmh-journal-mode");
    journal = SegmentedJournal.create(dir, "bench", segmentBytes, clock, true);
    ring = "ring".equals(mode) ? RingJournal.start("bench", journal, clock, ringBytes) : null;
  }

  /** One append on the producing thread. */
  @Benchmark
  public long append() {
    return ring == null
        ? journal.append(10, 0, payload, 0, PAYLOAD)
        : ring.append(10, 0, payload, 0, PAYLOAD);
  }

  @TearDown(Level.Iteration)
  public void closeJournal() throws IOException {
    final int segments = journal.segmentIndex();
    final long ringWaits = ring == null ? 0L : ring.producerWaits();
    if (ring != null) {
      ring.close();
    } else {
      journal.close();
    }
    try (Stream<Path> files = Files.list(dir)) {
      for (final Path p : files.toList()) {
        Files.delete(p);
      }
    }
    Files.delete(dir);
    if (segments != 1) {
      throw new IllegalStateException("iteration rolled to segment " + segments + "; shorten it");
    }
    if (ringWaits > 0) {
      System.out.println("ring filled " + ringWaits + " times in this iteration");
    }
  }

  /** The index of the segment being written, for tests. */
  int segmentIndex() {
    return journal.segmentIndex();
  }
}
