package io.ratesalgo.venues.md;

import org.agrona.collections.Int2ObjectHashMap;

/** A reference set by hand per instrument (the simple configurable band of the P2 scope). */
public final class BandReference implements ReferencePrice {

  private final Int2ObjectHashMap<long[]> mids = new Int2ObjectHashMap<>();

  /** Sets the reference mid of an instrument. */
  public BandReference set(final int instrumentId, final long mid) {
    mids.put(instrumentId, new long[] {mid});
    return this;
  }

  @Override
  public long reference(final int instrumentId) {
    final long[] m = mids.get(instrumentId);
    return m == null ? 0 : m[0];
  }
}
