package io.ratesalgo.api;

/**
 * Fair mid per instrument from pricing (P1): for swaps the curve-implied par rate in percent, for
 * bonds and futures a model price per 100. Values are Decimal9 mantissas. The reference timestamp
 * is the epoch-nanosecond stamp of the newest market input the value was computed from; the
 * quoting engine refuses to price off a value older than its configured maximum age.
 *
 * <p>Calls must not allocate. Implemented by the coordinator over P1's pricing; {@link
 * InMemoryFairValueSource} is the stub.
 */
public interface FairValueSource {

  /** Returned by both methods when no value exists for the instrument. */
  long NO_VALUE = Long.MIN_VALUE;

  /** Fair mid as a Decimal9 mantissa, or {@link #NO_VALUE}. */
  long fairMid(int instrumentId);

  /** Epoch nanoseconds of the input the fair mid was computed from, or {@link #NO_VALUE}. */
  long referenceNanos(int instrumentId);
}
