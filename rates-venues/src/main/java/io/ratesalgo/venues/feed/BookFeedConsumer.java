package io.ratesalgo.venues.feed;

import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateDecoder;
import io.ratesalgo.messages.sbe.MessageHeaderDecoder;
import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Rebuilds price-level books from the SBE feed (API_SPEC.md Section 10.5).
 *
 * <p>Rules: a frame whose sequence is the last applied plus one is applied. A frame at or below
 * the last applied is a duplicate and ignored. A frame above the last applied plus one is a gap:
 * the consumer marks every book stale, buffers the frame, asks the {@link SnapshotService} for a
 * snapshot, applies it (it carries sequence S), then replays buffered frames above S in order. A
 * gap inside the buffer starts another recovery.
 */
public final class BookFeedConsumer implements MessageSink {

  private final SnapshotService snapshots;
  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final BookUpdateDecoder decoder = new BookUpdateDecoder();
  private final Map<Integer, TreeMap<Long, Long>> bids = new HashMap<>();
  private final Map<Integer, TreeMap<Long, Long>> asks = new HashMap<>();
  private final List<byte[]> buffered = new ArrayList<>();
  private long lastSeq;
  private boolean stale;
  private long applied;
  private long duplicates;
  private long gaps;
  private long recoveries;
  private long foreign;

  /** Creates a consumer that recovers from the given service. */
  public BookFeedConsumer(final SnapshotService snapshots) {
    this.snapshots = snapshots;
  }

  @Override
  public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    if (header.templateId() != BookUpdateDecoder.TEMPLATE_ID) {
      foreign++;
      return false;
    }
    decoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
    final long seq = decoder.venueSeqNo();
    if (seq <= lastSeq) {
      duplicates++;
      return true;
    }
    if (seq != lastSeq + 1) {
      gaps++;
      final byte[] copy = new byte[length];
      buffer.getBytes(offset, copy);
      buffered.add(copy);
      recover();
      return true;
    }
    apply();
    lastSeq = seq;
    return true;
  }

  private void recover() {
    stale = true;
    recoveries++;
    final long snapSeq = snapshots.snapshot(this::onSnapshotFrame);
    lastSeq = snapSeq;
    stale = false;
    final List<byte[]> pending = new ArrayList<>(buffered);
    buffered.clear();
    pending.sort(Comparator.comparingLong(BookFeedConsumer::sequenceOf));
    for (final byte[] frame : pending) {
      offer(new UnsafeBuffer(frame), 0, frame.length);
    }
  }

  private static long sequenceOf(final byte[] frame) {
    final UnsafeBuffer b = new UnsafeBuffer(frame);
    final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(b, 0);
    return new BookUpdateDecoder().wrap(b, MessageHeaderDecoder.ENCODED_LENGTH, h.blockLength(), h.version()).venueSeqNo();
  }

  private boolean onSnapshotFrame(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    decoder.wrap(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH, header.blockLength(), header.version());
    apply();
    return true;
  }

  private NavigableMap<Long, Long> book(final int instrumentId, final Side side) {
    final Map<Integer, TreeMap<Long, Long>> m = side == Side.Buy ? bids : asks;
    return m.computeIfAbsent(
        instrumentId,
        k -> new TreeMap<>(side == Side.Buy ? Comparator.<Long>reverseOrder() : Comparator.<Long>naturalOrder()));
  }

  private void apply() {
    final int instrumentId = decoder.instrumentId();
    final BookAction action = decoder.action();
    if (action == BookAction.Clear) {
      book(instrumentId, Side.Buy).clear();
      book(instrumentId, Side.Sell).clear();
      return;
    }
    final NavigableMap<Long, Long> levels = book(instrumentId, decoder.side());
    final long price = decoder.price().mantissa();
    if (action == BookAction.Delete) {
      levels.remove(price);
    } else {
      levels.put(price, decoder.quantity());
    }
    applied++;
  }

  /** Price to quantity for one side, best first. */
  public NavigableMap<Long, Long> levels(final int instrumentId, final Side side) {
    return Collections.unmodifiableNavigableMap(new TreeMap<>(book(instrumentId, side)));
  }

  /** Best price on a side, or 0 if the side is empty. */
  public long best(final int instrumentId, final Side side) {
    final NavigableMap<Long, Long> levels = book(instrumentId, side);
    return levels.isEmpty() ? 0 : levels.firstKey();
  }

  /** Sequence of the last frame applied (or of the last snapshot). */
  public long lastSequence() {
    return lastSeq;
  }

  /** True while a recovery is in progress. */
  public boolean isStale() {
    return stale;
  }

  /** Incremental frames applied. */
  public long applied() {
    return applied;
  }

  /** Frames ignored as duplicates. */
  public long duplicates() {
    return duplicates;
  }

  /** Gaps detected. */
  public long gaps() {
    return gaps;
  }

  /** Snapshot recoveries run. */
  public long recoveries() {
    return recoveries;
  }

  /** Frames that were not BookUpdate messages. */
  public long foreign() {
    return foreign;
  }
}
