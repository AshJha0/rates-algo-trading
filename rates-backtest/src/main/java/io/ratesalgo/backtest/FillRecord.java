package io.ratesalgo.backtest;

import java.util.List;

/**
 * One fill booked by the back-tester.
 *
 * @param fillId 1-based, in booking order
 * @param timeNanos epoch nanoseconds of the fill
 * @param instrumentId instrument
 * @param signedQuantity our side: positive buy, negative sell, minor units
 * @param price fill price in quoted units
 * @param mid mid at the time of the fill
 * @param fee fee paid, major currency
 * @param hedge true for hedge orders
 * @param refId RFQ id for client fills, client order id for orders
 * @param clientId client for RFQ fills, 0 for orders
 * @param tier client tier for RFQ fills, 0 for orders
 * @param markouts dealer markouts at the configured horizons, major currency: signed quantity
 *     times (mid at fill time plus horizon minus fill price) times value per unit
 */
public record FillRecord(
    long fillId,
    long timeNanos,
    int instrumentId,
    long signedQuantity,
    double price,
    double mid,
    double fee,
    boolean hedge,
    long refId,
    int clientId,
    int tier,
    List<Double> markouts) {

  public FillRecord {
    markouts = List.copyOf(markouts);
  }

  public double markout(final int horizonIndex) {
    return markouts.get(horizonIndex);
  }

  /** Edge against mid at fill time, major currency (spread capture or hedge cost). */
  public double edge(final Instrument instrument) {
    return signedQuantity * (mid - price) * instrument.valuePerUnit();
  }

  FillRecord withMarkouts(final List<Double> values) {
    return new FillRecord(
        fillId, timeNanos, instrumentId, signedQuantity, price, mid, fee, hedge, refId, clientId, tier, values);
  }
}
