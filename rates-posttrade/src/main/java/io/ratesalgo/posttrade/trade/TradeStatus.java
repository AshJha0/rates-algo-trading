package io.ratesalgo.posttrade.trade;

/** Booking status of a trade. */
public enum TradeStatus {
  /** Counts toward positions and P&amp;L. */
  LIVE,
  /** Cancelled after execution by a {@code PostTradeBust}; kept in the records, excluded from positions. */
  BUSTED
}
