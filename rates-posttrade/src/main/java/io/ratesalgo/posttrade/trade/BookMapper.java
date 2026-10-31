package io.ratesalgo.posttrade.trade;

/**
 * Assigns a trade to a book, an account and an algorithm id. The coordinator binds the real
 * mapping (the router's parent order and the quoting engine's book configuration) at integration;
 * {@link FixedBookMapper} is the fixture.
 */
@FunctionalInterface
public interface BookMapper {

  /**
   * Books one trade.
   *
   * @param venueId the venue the trade executed on
   * @param instrumentId the instrument traded
   * @param orderOrQuoteId our clOrdId for an order, our quoteId for an RFQ trade
   */
  BookAssignment assign(int venueId, int instrumentId, long orderOrQuoteId);
}
