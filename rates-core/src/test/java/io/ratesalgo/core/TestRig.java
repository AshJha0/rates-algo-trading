package io.ratesalgo.core;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.journal.SegmentedJournalReader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * A live loop driven by hand on the test thread: a ManualClock, segmented inbound and outbound
 * journals named "in" and "out", one inbound ring and two outbound gateways. The journals are plain
 * {@link SegmentedJournal}s without preallocation, or {@link LoopJournals} in a given mode.
 */
final class TestRig implements AutoCloseable {

  final Path dir;
  final ManualClock source = new ManualClock();
  final EventClock clock = new EventClock(source);
  private final AutoCloseable journals;
  final ManyToOneRingBuffer inbound;
  final OneToOneRingBuffer[] outbound;
  final InboundPublisher publisher;
  final EventLoop loop;
  final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  long drained0;

  TestRig(final Path dir, final LoopFactory factory, final int segment, final int outbound0Capacity) {
    this(dir, factory, segment, outbound0Capacity, null);
  }

  /** With {@code mode} null the journals are plain segmented journals without preallocation. */
  TestRig(
      final Path dir,
      final LoopFactory factory,
      final int segment,
      final int outbound0Capacity,
      final JournalMode mode) {
    this.dir = dir;
    final JournalSink inSink;
    final JournalSink outSink;
    if (mode == null) {
      final SegmentedJournal in = SegmentedJournal.create(dir, "in", segment, clock);
      final SegmentedJournal out = SegmentedJournal.create(dir, "out", segment, clock);
      inSink = in::append;
      outSink = out::append;
      journals =
          () -> {
            in.close();
            out.close();
          };
    } else {
      final LoopJournals j = LoopJournals.create(dir, "in", "out", segment, clock, mode, 64 << 10);
      inSink = j.inbound();
      outSink = j.outbound();
      journals = j;
    }
    this.inbound = RingBufferContract.newInbound(RingBufferContract.MIN_CAPACITY * 2);
    this.outbound =
        new OneToOneRingBuffer[] {
          RingBufferContract.newOutbound(outbound0Capacity),
          RingBufferContract.newOutbound(RingBufferContract.DEFAULT_OUTBOUND_CAPACITY)
        };
    this.publisher = new InboundPublisher(inbound);
    this.loop =
        factory.create(
            EventLoop.builder(clock)
                .inbound(inbound)
                .outbound(outbound[0])
                .outbound(outbound[1])
                .inboundJournal(inSink)
                .outboundJournal(outSink));
  }

  boolean offerRequest(final long rfqId) {
    final int length = Msgs.quoteRequest(scratch, 0, rfqId);
    return publisher.offer(scratch, 0, length);
  }

  int cycle() {
    return loop.doWork();
  }

  void advance(final long nanos) {
    source.advance(nanos);
  }

  int drain(final int gateway) {
    final int n = outbound[gateway].read((t, b, i, l) -> {}, Integer.MAX_VALUE);
    if (gateway == 0) {
      drained0 += n;
    }
    return n;
  }

  /** The chain hash of the outbound journal as written, read back from its files after close. */
  long recordedOutboundHash() {
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, "out")) {
      while (reader.next((s, t, id, v, b, o, l) -> {})) {
        // reading to the end
      }
      return reader.chainHash();
    }
  }

  ReplayResult replay(final LoopFactory factory) {
    return LoopReplay.run(dir, "in", "out", factory);
  }

  @Override
  public void close() {
    try {
      journals.close();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
