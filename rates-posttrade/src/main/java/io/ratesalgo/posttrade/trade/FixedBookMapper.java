package io.ratesalgo.posttrade.trade;

import java.util.HashMap;
import java.util.Map;

/** Fixture {@link BookMapper}: one assignment per instrument, with a default for the rest. */
public final class FixedBookMapper implements BookMapper {

  private final Map<Integer, BookAssignment> byInstrument = new HashMap<>();
  private final BookAssignment fallback;

  public FixedBookMapper(final BookAssignment fallback) {
    this.fallback = fallback;
  }

  /** Books every trade in {@code instrumentId} to {@code assignment}. */
  public FixedBookMapper with(final int instrumentId, final BookAssignment assignment) {
    byInstrument.put(instrumentId, assignment);
    return this;
  }

  @Override
  public BookAssignment assign(final int venueId, final int instrumentId, final long orderOrQuoteId) {
    return byInstrument.getOrDefault(instrumentId, fallback);
  }
}
