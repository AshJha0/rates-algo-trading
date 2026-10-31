package io.ratesalgo.api;

import org.agrona.collections.Long2LongHashMap;

/** Stub fair-value source: values set by the test or recipe. Reads never allocate. */
public final class InMemoryFairValueSource implements FairValueSource {

  private final Long2LongHashMap mids = new Long2LongHashMap(64, 0.5f, NO_VALUE);
  private final Long2LongHashMap stamps = new Long2LongHashMap(64, 0.5f, NO_VALUE);

  /** Sets the fair mid (Decimal9 mantissa) and its reference stamp. */
  public void set(final int instrumentId, final long fairMid, final long referenceNanos) {
    mids.put(instrumentId, fairMid);
    stamps.put(instrumentId, referenceNanos);
  }

  /** Removes the value, as if pricing had none. */
  public void remove(final int instrumentId) {
    mids.remove(instrumentId);
    stamps.remove(instrumentId);
  }

  @Override
  public long fairMid(final int instrumentId) {
    return mids.get(instrumentId);
  }

  @Override
  public long referenceNanos(final int instrumentId) {
    return stamps.get(instrumentId);
  }
}
