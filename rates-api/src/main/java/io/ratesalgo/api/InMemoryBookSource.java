package io.ratesalgo.api;

import java.util.Arrays;
import org.agrona.collections.Int2IntHashMap;

/** Stub composite book with one level per side. Reads never allocate. */
public final class InMemoryBookSource implements BookSource {

  private static final long NONE = FairValueSource.NO_VALUE;

  private final Int2IntHashMap slots;
  private final long[] bid;
  private final long[] ask;
  private final long[] bidSize;
  private final long[] askSize;
  private final long[] stamp;

  /** A book for up to {@code capacity} instruments. */
  public InMemoryBookSource(final int capacity) {
    slots = new Int2IntHashMap(capacity * 2, 0.5f, -1);
    bid = filled(capacity);
    ask = filled(capacity);
    bidSize = new long[capacity];
    askSize = new long[capacity];
    stamp = filled(capacity);
  }

  private static long[] filled(final int n) {
    final long[] a = new long[n];
    Arrays.fill(a, NONE);
    return a;
  }

  /** Sets the top of book. */
  public void set(
      final int instrumentId,
      final long bestBid,
      final long bestBidSize,
      final long bestAsk,
      final long bestAskSize,
      final long nanos) {
    int s = slots.get(instrumentId);
    if (s < 0) {
      s = slots.size();
      if (s >= bid.length) {
        throw new IllegalStateException("book capacity " + bid.length + " exhausted");
      }
      slots.put(instrumentId, s);
    }
    bid[s] = bestBid;
    bidSize[s] = bestBidSize;
    ask[s] = bestAsk;
    askSize[s] = bestAskSize;
    stamp[s] = nanos;
  }

  @Override
  public long bestBid(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? NONE : bid[s];
  }

  @Override
  public long bestAsk(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? NONE : ask[s];
  }

  @Override
  public long bidSize(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? 0L : bidSize[s];
  }

  @Override
  public long askSize(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? 0L : askSize[s];
  }

  @Override
  public long updateNanos(final int instrumentId) {
    final int s = slots.get(instrumentId);
    return s < 0 ? NONE : stamp[s];
  }
}
