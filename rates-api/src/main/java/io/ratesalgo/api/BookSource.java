package io.ratesalgo.api;

/**
 * Top of the composite book per instrument, maintained by P2's market state from BookUpdate
 * messages. Prices are Decimal9 mantissas in the instrument's quote convention, sizes are minor
 * units. Calls must not allocate. {@link InMemoryBookSource} is the stub.
 */
public interface BookSource {

  /** Best bid, or {@link FairValueSource#NO_VALUE} when the side is empty. */
  long bestBid(int instrumentId);

  /** Best ask, or {@link FairValueSource#NO_VALUE} when the side is empty. */
  long bestAsk(int instrumentId);

  /** Size at the best bid. */
  long bidSize(int instrumentId);

  /** Size at the best ask. */
  long askSize(int instrumentId);

  /** Epoch nanoseconds of the last update to this book, or {@link FairValueSource#NO_VALUE}. */
  long updateNanos(int instrumentId);
}
