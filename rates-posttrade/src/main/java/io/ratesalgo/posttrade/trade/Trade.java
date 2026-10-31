package io.ratesalgo.posttrade.trade;

import io.ratesalgo.messages.sbe.Side;

/**
 * One version of a booked trade. Amendments create a new version; busts change the status. The
 * history of versions is kept by {@code PositionKeeper} with an audit entry per change.
 *
 * @param key trade identity
 * @param venueOrderId venue order id, 0 for an RFQ trade
 * @param orderOrQuoteId our clOrdId or quoteId
 * @param instrumentId instrument
 * @param side our side (for an RFQ trade, the opposite of the client's side)
 * @param price Decimal9 mantissa
 * @param quantity minor units of notional or face, positive
 * @param transactNanos venue transaction time, epoch nanoseconds
 * @param journalSequence journal sequence of the frame that created this version
 * @param booking book, account and algorithm
 * @param status live or busted
 * @param version 1 for the original, plus one per amendment
 * @param late true if the fill arrived after a fill with a later transaction time in the same
 *     position, or after the reconciliation cut
 */
public record Trade(
    TradeKey key,
    long venueOrderId,
    long orderOrQuoteId,
    int instrumentId,
    Side side,
    long price,
    long quantity,
    long transactNanos,
    long journalSequence,
    BookAssignment booking,
    TradeStatus status,
    int version,
    boolean late) {

  public Trade {
    if (quantity <= 0) {
      throw new IllegalArgumentException("trade " + key + " quantity must be positive: " + quantity);
    }
    if (side != Side.Buy && side != Side.Sell) {
      throw new IllegalArgumentException("trade " + key + " side must be Buy or Sell: " + side);
    }
  }

  /** Quantity signed from our point of view: positive bought, negative sold. */
  public long signedQuantity() {
    return side == Side.Buy ? quantity : -quantity;
  }

  /** True if the trade counts toward positions. */
  public boolean isLive() {
    return status == TradeStatus.LIVE;
  }

  /** The position this trade belongs to. */
  public PositionKey positionKey() {
    return new PositionKey(booking.book(), booking.account(), instrumentId);
  }

  Trade withStatus(final TradeStatus newStatus) {
    return new Trade(
        key, venueOrderId, orderOrQuoteId, instrumentId, side, price, quantity, transactNanos,
        journalSequence, booking, newStatus, version, late);
  }

  /** A new version with corrected economics. */
  public Trade amended(final long newPrice, final long newQuantity, final long sequence) {
    return new Trade(
        key, venueOrderId, orderOrQuoteId, instrumentId, side, newPrice, newQuantity, transactNanos,
        sequence, booking, status, version + 1, late);
  }

  /** The same trade marked busted. */
  public Trade busted() {
    return withStatus(TradeStatus.BUSTED);
  }
}
