package io.ratesalgo.backtest;

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
    double[] markouts) {

  public FillRecord {
    markouts = markouts.clone();
  }

  @Override
  public double[] markouts() {
    return markouts.clone();
  }

  public double markout(final int horizonIndex) {
    return markouts[horizonIndex];
  }

  /** Edge against mid at fill time, major currency (spread capture or hedge cost). */
  public double edge(final Instrument instrument) {
    return signedQuantity * (mid - price) * instrument.valuePerUnit();
  }

  FillRecord withMarkouts(final double[] values) {
    return new FillRecord(
        fillId, timeNanos, instrumentId, signedQuantity, price, mid, fee, hedge, refId, clientId, tier, values);
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof FillRecord f
        && fillId == f.fillId
        && timeNanos == f.timeNanos
        && instrumentId == f.instrumentId
        && signedQuantity == f.signedQuantity
        && Double.compare(price, f.price) == 0
        && Double.compare(mid, f.mid) == 0
        && Double.compare(fee, f.fee) == 0
        && hedge == f.hedge
        && refId == f.refId
        && clientId == f.clientId
        && tier == f.tier
        && java.util.Arrays.equals(markouts, f.markouts);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(fillId, timeNanos, instrumentId, signedQuantity, refId)
        * 31
        + java.util.Arrays.hashCode(markouts);
  }

  @Override
  public String toString() {
    return "FillRecord[" + fillId + " " + instrumentId + " " + signedQuantity + " @ " + price + "]";
  }
}
