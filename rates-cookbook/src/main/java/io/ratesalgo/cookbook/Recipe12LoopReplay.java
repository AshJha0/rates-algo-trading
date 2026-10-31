package io.ratesalgo.cookbook;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.core.EventClock;
import io.ratesalgo.core.EventLoop;
import io.ratesalgo.core.InboundPublisher;
import io.ratesalgo.core.LoopFactory;
import io.ratesalgo.core.LoopReplay;
import io.ratesalgo.core.ReplayResult;
import io.ratesalgo.core.RingBufferContract;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.QuoteEncoder;
import io.ratesalgo.messages.sbe.QuoteRequestDecoder;
import io.ratesalgo.messages.sbe.QuoteRequestEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/** COOKBOOK.md recipe 12: run the event loop, replay its journal and compare outbound hashes. */
public final class Recipe12LoopReplay {

  private Recipe12LoopReplay() {}

  /** Quotes every request at a fixed half-spread around 100; the spread is the knob we change. */
  static LoopFactory quoter(final long halfSpread) {
    return builder -> {
      final EventLoop loop = builder.build();
      final MessageHeaderDecoder headerIn = new MessageHeaderDecoder();
      final QuoteRequestDecoder request = new QuoteRequestDecoder();
      final MessageHeaderEncoder headerOut = new MessageHeaderEncoder();
      final QuoteEncoder quote = new QuoteEncoder();
      final UnsafeBuffer out = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
      loop.register(
          QuoteRequestDecoder.TEMPLATE_ID,
          (templateId, buffer, offset, length) -> {
            request.wrapAndApplyHeader(buffer, offset, headerIn);
            final long now = loop.clock().epochNanos();
            quote.wrapAndApplyHeader(out, 0, headerOut).rfqId(request.rfqId()).quoteId(request.rfqId());
            quote.bidPrice().mantissa(100_000_000_000L - halfSpread);
            quote.askPrice().mantissa(100_000_000_000L + halfSpread);
            quote.sentNanos(now).validUntilNanos(now + 1_000_000_000L);
            loop.publish(0, out, 0, quote.limit());
          });
      return loop;
    };
  }

  public static void main(final String[] args) throws IOException {
    final Path dir = Files.createTempDirectory("recipe12");
    final ManualClock source = new ManualClock(1_700_000_000_000_000_000L);
    final EventClock clock = new EventClock(source);
    final ManyToOneRingBuffer inbound = RingBufferContract.newInbound(1 << 16);
    final OneToOneRingBuffer outbound = RingBufferContract.newOutbound(1 << 16);

    // live: journals named "in" and "out", 4 KiB segments so the journal rolls
    try (SegmentedJournal in = SegmentedJournal.create(dir, "in", 4096, clock);
        SegmentedJournal out = SegmentedJournal.create(dir, "out", 4096, clock)) {
      final EventLoop loop =
          quoter(2_000_000L)
              .create(
                  EventLoop.builder(clock)
                      .inbound(inbound)
                      .outbound(outbound)
                      .inboundJournal(in::append)
                      .outboundJournal(out::append));
      final InboundPublisher gateway = new InboundPublisher(inbound);
      final UnsafeBuffer msg = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
      final MessageHeaderEncoder header = new MessageHeaderEncoder();
      final QuoteRequestEncoder rfq = new QuoteRequestEncoder();
      for (long id = 1; id <= 100; id++) {
        rfq.wrapAndApplyHeader(msg, 0, header)
            .venueId(7)
            .rfqId(id)
            .instrumentId(1001)
            .side(Side.Buy)
            .quantity(10_000_000_000L)
            .currency("USD");
        gateway.offer(msg, 0, rfq.limit());
        if (id % 10 == 0) {
          source.advance(1_000_000L); // 1 ms between bursts of ten
          loop.doWork();
          outbound.read((t, b, i, l) -> {});
        }
      }
      System.out.println("live: inbound segments=" + SegmentedJournal.segmentIndices(dir, "in").length
          + " outbound hash=" + Long.toHexString(out.chainHash()));
    }

    final ReplayResult same = LoopReplay.run(dir, "in", "out", quoter(2_000_000L));
    System.out.println("replay, same handler: matches=" + same.matches()
        + " frames=" + same.inboundFrames()
        + " produced=" + same.outboundProduced()
        + " hash=" + Long.toHexString(same.replayHash()));

    final ReplayResult changed = LoopReplay.run(dir, "in", "out", quoter(2_500_000L));
    System.out.println("replay, wider spread: matches=" + changed.matches()
        + " firstMismatch=" + changed.firstMismatch());

    try (Stream<Path> files = Files.list(dir)) {
      for (final Path p : files.toList()) {
        Files.delete(p);
      }
    }
    Files.delete(dir);
  }
}
