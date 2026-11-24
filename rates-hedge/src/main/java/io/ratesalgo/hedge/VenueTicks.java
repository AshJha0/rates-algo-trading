package io.ratesalgo.hedge;

import io.ratesalgo.api.Instrument;

/**
 * The price increment of an instrument on the venue it trades on, as a Decimal9 mantissa in the
 * instrument's quote convention (1/128 of a point is 7,812,500; 0.0001 percent of swap rate is
 * 100,000). 0 means unknown. Quoting rounds RFQ prices to it (review Q8) and the tick hedge cost
 * model prices a crossing in it (Q5). Calls must not allocate.
 */
@FunctionalInterface
public interface VenueTicks {

  /** No ticks known. */
  VenueTicks NONE = instrument -> 0L;

  /** Tick of {@code instrument}, Decimal9 mantissa; 0 when unknown. */
  long tick(Instrument instrument);
}
