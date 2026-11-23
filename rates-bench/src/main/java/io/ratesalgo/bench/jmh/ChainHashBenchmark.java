package io.ratesalgo.bench.jmh;

import io.ratesalgo.journal.ChainHash;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmark: one chain-hash step over a payload of the sizes the loop journals (57 bytes is a
 * QuoteRequest, 120 an ExecutionReport, 512 a large snapshot frame), for the choice recorded in
 * DECISIONS.md DR-R6-L03 (review finding L8).
 *
 * <ul>
 *   <li>{@code fnv1a}: {@link ChainHash#next(long, org.agrona.DirectBuffer, int, int)}, one byte per
 *       multiply, the format-1 hash;
 *   <li>{@code word64}: {@link ChainHash#word64}, eight bytes per step, the format-2 hash;
 *   <li>{@code crc32c}: {@link CRC32C} over a direct buffer (a JDK intrinsic), measured for
 *       comparison only; its 32 bits are not used for the chain.
 * </ul>
 *
 * <p>Each call chains to the previous result, as a journal does, so the steps cannot overlap.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ChainHashBenchmark {

  @Param({"57", "120", "512"})
  public int length;

  private final ByteBuffer direct = ByteBuffer.allocateDirect(1024);
  private final UnsafeBuffer payload = new UnsafeBuffer(direct);
  private final CRC32C crc = new CRC32C();
  private long previous;

  /** A fixed, non-zero payload. */
  @Setup
  public void setUp() {
    for (int i = 0; i < 1024; i++) {
      payload.putByte(i, (byte) (i * 31 + 7));
    }
  }

  /** FNV-1a 64, one byte per step. */
  @Benchmark
  public long fnv1a() {
    previous = ChainHash.next(previous, payload, 0, length);
    return previous;
  }

  /** The eight-byte-stride hash. */
  @Benchmark
  public long word64() {
    previous = ChainHash.word64(previous, payload, 0, length);
    return previous;
  }

  /** CRC32C seeded with the previous value's low bits, for comparison. */
  @Benchmark
  public long crc32c() {
    crc.reset();
    crc.update((int) previous);
    direct.limit(length).position(0);
    crc.update(direct);
    previous = crc.getValue();
    return previous;
  }
}
