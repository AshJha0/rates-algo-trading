package io.ratesalgo.venues.d2c;

/**
 * One RFQ trade with its markout.
 *
 * @param rfqId RFQ
 * @param winner dealer code
 * @param clientBuys client side
 * @param price traded price mantissa
 * @param quantity currency units
 * @param informed whether the client was informed
 * @param midAtTrade mid when traded
 * @param midAtHorizon mid at the markout horizon, 0 until measured
 */
public record TradeRecord(
    long rfqId,
    String winner,
    boolean clientBuys,
    long price,
    long quantity,
    boolean informed,
    long midAtTrade,
    long midAtHorizon) {

  /** Dealer markout per unit at the horizon: positive is a gain for the dealer. */
  public long dealerMarkout() {
    return clientBuys ? price - midAtHorizon : midAtHorizon - price;
  }
}
