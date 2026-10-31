package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.Side;

/**
 * Entry point for parent orders. The P3 hedger's {@code HedgeOrderSink} (in {@code rates-api})
 * is bound to this by the coordinator; any other caller may use it directly.
 */
public interface ParentOrderSink {

  /**
   * Accepts a parent order.
   *
   * @param parentId caller's identifier, unique among live parents
   * @param instrumentId instrument to trade on every configured venue
   * @param side Buy or Sell
   * @param quantity total quantity, a positive multiple of the largest venue lot size
   * @param urgency 0 (patient) to 1 (cross now); shortens the schedule and the passive limit
   * @param limitPrice worst acceptable price as a mantissa, or 0 for none
   */
  void submit(
      long parentId, int instrumentId, Side side, long quantity, double urgency, long limitPrice);
}
