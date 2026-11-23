package io.ratesalgo.bench.jmh;

import io.ratesalgo.fix.codec.FixTags;
import io.ratesalgo.fix.codec.FixWriter;
import io.ratesalgo.fix.codec.MsgType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmark: encoding one FIX Quote (35=S) the way a session sends it, header fields first,
 * then the body, then BeginString, BodyLength and CheckSum (review finding L11).
 *
 * <ul>
 *   <li>{@code before}: the encoder as it was before round 6 ({@link LegacyFixWriter}): body in a
 *       scratch buffer, copied behind the header, CheckSum summed byte by byte, the date of every
 *       timestamp worked out from scratch;
 *   <li>{@code copy}: today's encoder finishing into the caller's buffer ({@link
 *       FixWriter#finish(org.agrona.MutableDirectBuffer, int)}), one copy;
 *   <li>{@code inPlace}: today's encoder as the session uses it ({@link FixWriter#finish()}): the
 *       header goes into the room reserved in front of the body, nothing is copied.
 * </ul>
 *
 * <p>All three produce the same bytes ({@code BenchmarksTest}). The socket write is not included;
 * the plain transport no longer copies either (TcpInitiator), which this does not measure.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FixEncodeBenchmark {

  private static final byte[] SENDER = "RATESALGO".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] TARGET = "D2CSIM".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SYMBOL = "USD-SOFR-5Y".getBytes(StandardCharsets.US_ASCII);

  private final FixWriter writer = new FixWriter(64 * 1024);
  private final LegacyFixWriter legacy = new LegacyFixWriter(64 * 1024);
  private final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(64 * 1024 + 64));
  private long seq;
  private long nanos = 1_793_712_300_000_000_000L;

  /** Fresh sequence numbers; nothing else to prepare. */
  @Setup
  public void setUp() {
    seq = 1;
  }

  private FixWriter quote() {
    nanos += 1_000_000L;
    return writer
        .begin(MsgType.QUOTE)
        .putAscii(FixTags.SENDER_COMP_ID, SENDER)
        .putAscii(FixTags.TARGET_COMP_ID, TARGET)
        .put(FixTags.MSG_SEQ_NUM, seq++)
        .putTimestamp(FixTags.SENDING_TIME, nanos)
        .put(FixTags.QUOTE_REQ_ID, 9_100_000L + seq)
        .put(FixTags.QUOTE_ID, 7_000_000L + seq)
        .putAscii(FixTags.SYMBOL, SYMBOL)
        .put(FixTags.SECURITY_ID, 2003)
        .putDecimal(FixTags.BID_PX, 3_871_250_000L, 9)
        .putDecimal(FixTags.OFFER_PX, 3_873_750_000L, 9)
        .put(FixTags.BID_SIZE, 50_000_000L)
        .put(FixTags.OFFER_SIZE, 50_000_000L)
        .putTimestamp(FixTags.VALID_UNTIL_TIME, nanos + 2_000_000_000L)
        .putTimestamp(FixTags.TRANSACT_TIME, nanos);
  }

  /** The encoder before round 6. */
  @Benchmark
  public int before() {
    nanos += 1_000_000L;
    return legacy
        .begin(MsgType.QUOTE)
        .putAscii(FixTags.SENDER_COMP_ID, SENDER)
        .putAscii(FixTags.TARGET_COMP_ID, TARGET)
        .put(FixTags.MSG_SEQ_NUM, seq++)
        .putTimestamp(FixTags.SENDING_TIME, nanos)
        .put(FixTags.QUOTE_REQ_ID, 9_100_000L + seq)
        .put(FixTags.QUOTE_ID, 7_000_000L + seq)
        .putAscii(FixTags.SYMBOL, SYMBOL)
        .put(FixTags.SECURITY_ID, 2003)
        .putDecimal(FixTags.BID_PX, 3_871_250_000L, 9)
        .putDecimal(FixTags.OFFER_PX, 3_873_750_000L, 9)
        .put(FixTags.BID_SIZE, 50_000_000L)
        .put(FixTags.OFFER_SIZE, 50_000_000L)
        .putTimestamp(FixTags.VALID_UNTIL_TIME, nanos + 2_000_000_000L)
        .putTimestamp(FixTags.TRANSACT_TIME, nanos)
        .finish(out, 0);
  }

  /** Encode, then copy the finished message into a second buffer. */
  @Benchmark
  public int copy() {
    return quote().finish(out, 0);
  }

  /** Encode with the header written into the reserved room in front of the body. */
  @Benchmark
  public int inPlace() {
    return quote().finish();
  }

  /** The writer, for tests that compare the two outputs. */
  FixWriter writer() {
    return writer;
  }

  /** The copy target, for tests. */
  UnsafeBuffer out() {
    return out;
  }

  /** Sets the sequence and the clock, so tests can encode the same message three ways. */
  void reset(final long sequence, final long epochNanos) {
    seq = sequence;
    nanos = epochNanos;
  }
}
