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
 * The SBE book feed into the loops (API_SPEC.md Section 18.5).
 *
 * <p>The feed is a stream of deltas, so the loop's book is right only if every frame since the
 * last snapshot reached it, in order. The binding keeps that true or says so:
 *
 * <ul>
 *   <li>It starts unsynchronised and takes a snapshot from the venue's {@link SnapshotService}
 *       before it forwards any live frame; frames at or below the snapshot's sequence are dropped
 *       as already covered. No first sequence number is taken on trust.
 *   <li>A live frame is forwarded only if it is the next in sequence, and the sequence advances
 *       only when the inbound side took it. A gap on the wire and a frame the inbound ring refused
 *       are the same event: the loop's book is now wrong.
 *   <li>On that event the binding stops forwarding, publishes GatewayStatus DISCONNECTED for the
 *       feed's status id (the loop pulls its quotes and stops routing to the venue), delivers a
 *       whole snapshot (a Clear and the levels per instrument, each stamped with the snapshot's
 *       sequence), and then publishes GatewayStatus CONNECTED: the loop's signal that the book is
 *       whole again (DECISIONS.md DR-R2W-05). Any step the inbound side refuses leaves the binding
 *       unsynchronised, and the whole rebuild is tried again on the next {@link #doWork}; the book
 *       is declared whole only after every snapshot frame was taken.
 * </ul>
 */
@HotPath
public final class FeedBinding {

  private final MessageSource channel;
  private final SnapshotService snapshots;
  private final MessageSink inbound;
  private final InboundRouter router;
  private final int statusId;
  private final Clock clock;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final BookUpdateDecoder update = new BookUpdateDecoder();
  private final MutableDirectBuffer status = new UnsafeBuffer(ByteBuffer.allocateDirect(64));
  private final MessageSink onFrame = this::onFrame;
  private final MessageSink onSnapshotFrame = this::onSnapshotFrame;
  private boolean synced;
  /** The loop holds a book it must stop trusting before the rebuild starts. */
  private boolean tellDown;
  /** The loop has been told, and is waiting for CONNECTED. */
  private boolean downSent;
  private boolean snapshotWhole;
  private int snapshotTaken;
  private long lastSeq;
  private long frames;
  private long gaps;
  private long duplicates;
  private long undelivered;
  private long skipped;
  private long rebuilds;
  private long failedRebuilds;

  /**
   * A binding into any sink; a false return from the sink means the frame was not taken.
   *
   * @param channel the feed (the in-process channel, or a UDP receiver)
   * @param snapshots the venue's snapshot service
   * @param inbound where frames go
   * @param statusId GatewayStatus id announcing a book that is, or is no longer, whole
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
    this.router = null;
    this.statusId = statusId;
    this.clock = clock;
  }

  /**
   * A binding into the inbound router, which tells a full ring ({@link InboundRouter#FULL}) from
   * an instrument no group owns ({@link InboundRouter#UNROUTED}, not a loss).
   */
  public FeedBinding(
      final MessageSource channel,
      final SnapshotService snapshots,
      final InboundRouter inbound,
      final int statusId,
      final Clock clock) {
    this.channel = channel;
    this.snapshots = snapshots;
    this.inbound = inbound;
    this.router = inbound;
    this.statusId = statusId;
    this.clock = clock;
  }

  /** Rebuilds the book if it is not whole, then delivers what the feed has; returns the frames read. */
  public int doWork() {
    if (!synced && !rebuild()) {
      return 0;
    }
    final int read = channel.poll(onFrame, 1024);
    if (!synced) {
      // a loss during this poll: rebuild now rather than leave the loop without a book until the
      // next call
      rebuild();
    }
    return read;
  }

  private int deliver(final DirectBuffer buffer, final int offset, final int length) {
    if (router != null) {
      return router.route(buffer, offset, length);
    }
    return inbound.offer(buffer, offset, length) ? InboundRouter.DELIVERED : InboundRouter.FULL;
  }

  private boolean onFrame(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    if (header.templateId() != BookUpdateDecoder.TEMPLATE_ID) {
      return false;
    }
    update.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
    final long seq = update.venueSeqNo();
    if (!synced) {
      // lost sync earlier in this poll; the snapshot that follows covers this frame
      skipped++;
      return true;
    }
    if (seq <= lastSeq) {
      duplicates++;
      return true;
    }
    if (seq != lastSeq + 1) {
      gaps++;
      loseSync();
      return true;
    }
    final int result = deliver(buffer, offset, length);
    if (result == InboundRouter.FULL) {
      // the loop did not get this delta and nothing later repairs that: same as a gap
      undelivered++;
      loseSync();
      return true;
    }
    if (result == InboundRouter.DELIVERED) {
      frames++;
    }
    lastSeq = seq;
    return true;
  }

  private void loseSync() {
    synced = false;
    tellDown = true;
  }

  /** One attempt at DISCONNECTED, a whole snapshot and CONNECTED; false if any part was refused. */
  private boolean rebuild() {
    if (tellDown && !downSent) {
      if (!publishStatus(ControlMessages.STATUS_DISCONNECTED)) {
        failedRebuilds++;
        return false;
      }
      downSent = true;
    }
    snapshotWhole = true;
    snapshotTaken = 0;
    final long seq = snapshots.snapshot(onSnapshotFrame);
    if (!snapshotWhole) {
      // the loop may hold half a snapshot: it must be told before the next attempt
      tellDown = true;
      failedRebuilds++;
      return false;
    }
    if ((downSent || snapshotTaken > 0) && !publishStatus(ControlMessages.STATUS_CONNECTED)) {
      tellDown = true;
      failedRebuilds++;
      return false;
    }
    lastSeq = seq;
    synced = true;
    tellDown = false;
    downSent = false;
    rebuilds++;
    return true;
  }

  private boolean publishStatus(final byte value) {
    final int n = ControlMessages.encodeGatewayStatus(status, 0, clock.epochNanos(), statusId, value);
    return deliver(status, 0, n) != InboundRouter.FULL;
  }

  private boolean onSnapshotFrame(final DirectBuffer buffer, final int offset, final int length) {
    if (!snapshotWhole) {
      return false;
    }
    final int result = deliver(buffer, offset, length);
    if (result == InboundRouter.FULL) {
      snapshotWhole = false;
      return false;
    }
    if (result == InboundRouter.DELIVERED) {
      snapshotTaken++;
    }
    return true;
  }

  /** True while the loop's book is whole: a snapshot was delivered and every frame since. */
  public boolean synced() {
    return synced;
  }

  /** Frames forwarded live (snapshot frames not counted). */
  public long frames() {
    return frames;
  }

  /** Gaps seen on the wire, each followed by a snapshot rebuild. */
  public long gaps() {
    return gaps;
  }

  /** Frames dropped because a snapshot or an earlier frame already covered them. */
  public long duplicates() {
    return duplicates;
  }

  /** Live frames the inbound side refused, each followed by a snapshot rebuild. */
  public long undelivered() {
    return undelivered;
  }

  /** Live frames passed over while waiting for a rebuild; the snapshot covered them. */
  public long skipped() {
    return skipped;
  }

  /** Snapshot rebuilds completed, the initial one included. */
  public long rebuilds() {
    return rebuilds;
  }

  /** Rebuild attempts the inbound side refused part of; each was tried again. */
  public long failedRebuilds() {
    return failedRebuilds;
  }
}
