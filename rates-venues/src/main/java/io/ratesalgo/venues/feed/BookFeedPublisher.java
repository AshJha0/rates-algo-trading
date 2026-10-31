package io.ratesalgo.venues.feed;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.fix.gateway.MessageSink;
import io.ratesalgo.messages.sbe.BookAction;
import io.ratesalgo.messages.sbe.BookUpdateEncoder;
import io.ratesalgo.messages.sbe.MessageHeaderEncoder;
import io.ratesalgo.messages.sbe.Side;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Publishes price-level book changes as SBE {@code BookUpdate} frames with one sequence number per
 * venue, starting at 1 and increasing by 1 per frame (API_SPEC.md Section 10.5). Quantities on the
 * feed are minor units. It keeps a copy
 * of what it published so that it can serve snapshots consistent with the sequence.
 */
public final class BookFeedPublisher implements SnapshotService {

  /**
   * Minor units per currency unit. Venue books hold face in currency units; the feed carries minor
   * units as API_SPEC.md Section 0 requires (USD, EUR and GBP all have two decimals).
   */
  public static final long MINOR_UNITS = 100;

  private final int venueId;
  private final FeedChannel channel;
  private final Clock clock;
  private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final BookUpdateEncoder encoder = new BookUpdateEncoder();
  private final Map<Integer, TreeMap<Long, long[]>> bids = new TreeMap<>();
  private final Map<Integer, TreeMap<Long, long[]>> asks = new TreeMap<>();
  private long seq;

  /** Creates a publisher. */
  public BookFeedPublisher(final int venueId, final FeedChannel channel, final Clock clock) {
    this.venueId = venueId;
    this.channel = channel;
    this.clock = clock;
  }

  /** Sequence number of the last frame published. */
  public synchronized long lastSequence() {
    return seq;
  }

  private java.util.NavigableMap<Long, long[]> side(final int instrumentId, final Side side) {
    final Map<Integer, TreeMap<Long, long[]>> book = side == Side.Buy ? bids : asks;
    return book.computeIfAbsent(
        instrumentId,
        k -> new TreeMap<>(side == Side.Buy ? Comparator.<Long>reverseOrder() : Comparator.<Long>naturalOrder()));
  }

  /**
   * Sets the quantity at a price: quantity 0 deletes the level. Publishes Add, Change or Delete;
   * publishes nothing if the level is unchanged.
   */
  public synchronized void level(
      final int instrumentId, final Side side, final long price, final long quantity, final int orders) {
    final java.util.NavigableMap<Long, long[]> levels = side(instrumentId, side);
    final long[] old = levels.get(price);
    final BookAction action;
    if (quantity == 0) {
      if (old == null) {
        return;
      }
      levels.remove(price);
      action = BookAction.Delete;
    } else if (old == null) {
      levels.put(price, new long[] {quantity, orders});
      action = BookAction.Add;
    } else {
      if (old[0] == quantity && old[1] == orders) {
        return;
      }
      old[0] = quantity;
      old[1] = orders;
      action = BookAction.Change;
    }
    final int rank = quantity == 0 ? 0 : levels.headMap(price, true).size();
    channel.publish(buffer, 0, encode(instrumentId, ++seq, side, action, rank, price, quantity, orders));
  }

  private int encode(final int instrumentId, final long sequence, final Side side, final BookAction action,
      final int rank, final long price, final long quantity, final long orders) {
    encoder
        .wrapAndApplyHeader(buffer, 0, header)
        .venueId(venueId)
        .instrumentId(instrumentId)
        .venueSeqNo(sequence)
        .side(side)
        .action(action)
        .level((short) Math.min(rank, 255))
        .quantity(quantity * MINOR_UNITS)
        .orderCount((int) orders)
        .timestampNanos(clock.epochNanos());
    encoder.price().mantissa(price);
    return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
  }

  @Override
  public synchronized long snapshot(final MessageSink sink) {
    final java.util.Set<Integer> instruments = new java.util.TreeSet<>(bids.keySet());
    instruments.addAll(asks.keySet());
    final UnsafeBuffer snap = new UnsafeBuffer(new byte[128]);
    for (final int instrumentId : instruments) {
      int n = encode(instrumentId, seq, Side.Buy, BookAction.Clear, 0, 0, 0, 0);
      snap.putBytes(0, buffer, 0, n);
      sink.offer(snap, 0, n);
      for (final Side side : new Side[] {Side.Buy, Side.Sell}) {
        int rank = 0;
        for (final Map.Entry<Long, long[]> e : side(instrumentId, side).entrySet()) {
          n = encode(instrumentId, seq, side, BookAction.Add, ++rank, e.getKey(), e.getValue()[0], e.getValue()[1]);
          snap.putBytes(0, buffer, 0, n);
          sink.offer(snap, 0, n);
        }
      }
    }
    return seq;
  }
}
