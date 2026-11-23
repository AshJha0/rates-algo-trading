package io.ratesalgo.hedge;

/**
 * Whether a hedge instrument can be traded now. Cash Treasuries are on the CLOB from the start;
 * futures (TU, FV, TY, US) become hedges only once a futures book is wired, so the desk hands the
 * {@link FactorHedgePlanner} this test instead of a fixed list. Calls must not allocate.
 */
@FunctionalInterface
public interface HedgeAvailability {

  /** Every instrument of the universe is available. */
  HedgeAvailability ALL = instrumentId -> true;

  /** True when {@code instrumentId} may be planned into a hedge. */
  boolean available(int instrumentId);
}
