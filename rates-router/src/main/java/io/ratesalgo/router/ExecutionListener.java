package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.OrdStatus;

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
}
