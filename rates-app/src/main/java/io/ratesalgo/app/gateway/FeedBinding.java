package io.ratesalgo.app.gateway;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.core.ControlMessages;
import io.ratesalgo.core.HotPath;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.fix.gateway.MessageSource;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.venues.feed.SnapshotService;
import java.nio.ByteBuffer;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The SBE book feed into the loops (API_SPEC.md Section 18.5). Every BookUpdate frame is passed
 * to the {@link InboundRouter} as it arrives, a gapped one included, so the loop's input health
 * sees the gap and pulls quotes (the quote-pull-on-gap path runs through the loop). On a gap the
 * binding then asks the venue's {@link SnapshotService} for its book, forwards the snapshot frames
 * (a Clear and the levels, each stamped with the snapshot's sequence), drops buffered frames the
 * snapshot already covers, and publishes a GatewayStatus CONNECTED for the feed's status id: the
 * loop's signal that the book is whole again (DECISIONS.md DR-R2W-05).
 */
@HotPath
public final class FeedBinding {

  private final MessageSource channel;
  private final SnapshotService snapshots;
  private final MessageSink inbound;
  private final int statusId;
  private final Clock clock;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final BookUpdateDecoder update = new BookUpdateDecoder();
  private final MutableDirectBuffer status = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final MessageSink onFrame = this::onFrame;
  private final MessageSink onSnapshotFrame = this::onSnapshotFrame;
  private long lastSeq;
  private long frames;
  private long gaps;
  private long duplicates;

  /**
   * @param channel the feed (the in-process channel, or a UDP receiver)
   * @param snapshots the venue's snapshot service
   * @param inbound where frames go
   * @param statusId GatewayStatus id announcing a rebuilt book
   * @param clock stamps for the GatewayStatus
   */
  public FeedBinding(
      final MessageSource channel,
      final SnapshotService snapshots,
      final MessageSink inbound,
      final int statusId,
      final Clock clock) {
    this.channel = channel;
    this.snapshots = snapshots;
    this.inbound = inbound;
    this.statusId = statusId;
    this.clock = clock;
  }

  /** Delivers what the feed has; returns the frames read. */
  public int doWork() {
    return channel.poll(onFrame, 1024);
  }

  private boolean onFrame(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    if (header.templateId() != BookUpdateDecoder.TEMPLATE_ID) {
      return false;
    }
    update.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
    final long seq = update.venueSeqNo();
    if (seq <= lastSeq) {
      duplicates++;
      return true;
    }
    frames++;
    inbound.offer(buffer, offset, length);
    if (lastSeq != 0 && seq != lastSeq + 1) {
      gaps++;
      lastSeq = snapshots.snapshot(onSnapshotFrame);
      final int n =
          ControlMessages.encodeGatewayStatus(status, 0, clock.epochNanos(), statusId, ControlMessages.STATUS_CONNECTED);
      inbound.offer(status, 0, n);
      return true;
    }
    lastSeq = seq;
    return true;
  }

  private boolean onSnapshotFrame(final DirectBuffer buffer, final int offset, final int length) {
    return inbound.offer(buffer, offset, length);
  }

  /** Frames forwarded live (snapshot frames not counted). */
  public long frames() {
    return frames;
  }

  /** Gaps seen, each followed by a snapshot rebuild. */
  public long gaps() {
    return gaps;
  }

  /** Frames dropped because a snapshot or an earlier frame already covered them. */
  public long duplicates() {
    return duplicates;
  }
}
