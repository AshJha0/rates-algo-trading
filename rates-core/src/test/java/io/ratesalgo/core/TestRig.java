package io.ratesalgo.core;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.SegmentedJournal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * A live loop driven by hand on the test thread: a ManualClock, segmented inbound and outbound
 * journals named "in" and "out", one inbound ring and two outbound gateways.
 */
final class TestRig implements AutoCloseable {

  final Path dir;
  final ManualClock source = new ManualClock();
  final EventClock clock = new EventClock(source);
  final SegmentedJournal in;
  final SegmentedJournal out;
  final ManyToOneRingBuffer inbound;
  final OneToOneRingBuffer[] outbound;
  final InboundPublisher publisher;
  final EventLoop loop;
  final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
  long drained0;

  TestRig(final Path dir, final LoopFactory factory, final int segment, final int outbound0Capacity) {
    this.dir = dir;
    this.in = SegmentedJournal.create(dir, "in", segment, clock);
    this.out = SegmentedJournal.create(dir, "out", segment, clock);
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
                .inboundJournal(in::append)
                .outboundJournal(out::append));
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

  ReplayResult replay(final LoopFactory factory) {
    return LoopReplay.run(dir, "in", "out", factory);
  }

  @Override
  public void close() {
    in.close();
    out.close();
  }
}
