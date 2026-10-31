package io.ratesalgo.bench.jmh;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.JournalFormat;
import io.ratesalgo.journal.JournalWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Microbenchmark: append one 57-byte payload to a memory-mapped journal (copy, chain hash, header,
 * ordered length store). When the 256 MiB file fills, the method replaces it with a fresh one; that
 * happens once every 2.7 million appends and is included in the average, which the report says.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class JournalAppendBenchmark {

  static final int CAPACITY = 256 << 20;

  /** File size; tests lower it to exercise the replacement. */
  int capacity = CAPACITY;

  private Path dir;
  private Path file;
  private JournalWriter writer;
  private UnsafeBuffer payload;
  private final ManualClock clock = new ManualClock();

  @Setup
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("jmh-journal");
    payload = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    for (int i = 0; i < 57; i++) {
      payload.putByte(i, (byte) i);
    }
    file = dir.resolve("bench.raj");
    writer = JournalWriter.create(file, capacity, clock);
  }

  /** One append; replaces the file when full. */
  @Benchmark
  public long append() throws IOException {
    if (writer.remaining() < JournalFormat.alignedFrameLength(57)) {
      writer.close();
      Files.delete(file);
      writer = JournalWriter.create(file, capacity, clock);
    }
    return writer.append(10, 0, payload, 0, 57);
  }

  @TearDown
  public void tearDown() throws IOException {
    writer.close();
    Files.deleteIfExists(file);
    Files.deleteIfExists(dir);
  }

  /** Bytes remaining in the current file, for tests. */
  int remaining() {
    return writer.remaining();
  }
}
