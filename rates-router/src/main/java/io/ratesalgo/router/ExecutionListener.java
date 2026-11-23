package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.OrdStatus;
import io.ratesalgo.messages.sbe.Side;

/**
 * Receives what venues say about child orders, and book changes. The router implements it; the
 * gateway (or the in-memory venues) call it on the router's thread.
 */
public interface ExecutionListener {

  /** One ExecutionReport, fields as SBE template 21. */
  void onExecutionReport(
      int venueId,
      long clOrdId,
      long execId,
      OrdStatus ordStatus,
      long lastPrice,
      long lastQuantity,
      long leavesQuantity,
      long cumQuantity,
      long transactNanos);

  /** The venue refused a cancel, usually because the order had already finished. */
  void onCancelReject(int venueId, long clOrdId);

  /** The displayed book on the venue changed. */
  void onBookUpdate(int venueId);

  /**
   * A trade the venue's market data published (TradePrint, template 4): {@code aggressorSide} is
   * the side that removed liquidity, so a Sell aggressor traded against resting bids. Ours are
   * included, as on a real feed. Ignored by default.
   */
  default void onTrade(
      final int venueId, final Side aggressorSide, final long price, final long quantity) {}
}
