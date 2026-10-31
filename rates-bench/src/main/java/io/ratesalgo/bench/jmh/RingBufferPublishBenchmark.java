package io.ratesalgo.bench.jmh;

import io.ratesalgo.core.RingBufferContract;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Microbenchmark: publish one 57-byte QuoteRequest-sized record into the inbound ring and read it
 * back, on one thread. It isolates the cost of the ring operations from cross-core cache traffic,
 * which the latency harness measures; it is labelled as a microbenchmark wherever it is reported.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RingBufferPublishBenchmark {

  private ManyToOneRingBuffer ring;
  private UnsafeBuffer message;
  private final MessageHandler sink = (msgTypeId, buffer, index, length) -> {};

  @Setup
  public void setUp() {
    ring = RingBufferContract.newInbound(RingBufferContract.DEFAULT_INBOUND_CAPACITY);
    message = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
    message.putShort(2, (short) 10);
  }

  /**
   * One write and one read of a 57-byte record. A read that lands on the wrap padding consumes only
   * the padding and returns 0, so the read is repeated once then.
   */
  @Benchmark
  public int publishThenRead() {
    ring.write(10, message, 0, 57);
    final int n = ring.read(sink, 1);
    return n == 0 ? ring.read(sink, 1) : n;
  }
}
