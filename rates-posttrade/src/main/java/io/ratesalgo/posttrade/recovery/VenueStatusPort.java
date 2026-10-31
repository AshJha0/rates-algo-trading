package io.ratesalgo.posttrade.recovery;

import java.util.List;

/**
 * The venue gateway's status queries and cancels, as the restart procedure needs them. P2's FIX
 * gateway binds this at integration: {@link #orderMassStatus} sends FIX OrderMassStatusRequest
 * (35=AF) and collects the ExecutionReports with ExecType I; {@link #quoteStatus} sends
 * QuoteStatusRequest (35=a) and collects the QuoteStatusReports.
 */
public interface VenueStatusPort {

  /** The venue's view of our working orders. */
  record OrderStatus(long clOrdId, long venueOrderId, int instrumentId, long leavesQuantity, long cumQuantity) {}

  /** The venue's view of one of our live quotes. */
  record QuoteStatus(long quoteId, int instrumentId, long validUntilNanos) {}

  /** Every order of ours the venue holds as working. */
  List<OrderStatus> orderMassStatus(int venueId);

  /** Every quote of ours the venue holds as live. */
  List<QuoteStatus> quoteStatus(int venueId);

  /** Cancels an order the venue holds and we do not want. */
  void cancelOrder(int venueId, long clOrdId, long venueOrderId);

  /** Withdraws a live quote. */
  void cancelQuote(int venueId, long quoteId);
}
