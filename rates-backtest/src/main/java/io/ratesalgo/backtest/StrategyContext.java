package io.ratesalgo.backtest;

import io.ratesalgo.backtest.model.EventCalendar;
import io.ratesalgo.backtest.model.HitRateModel;
import io.ratesalgo.messages.sbe.OrdType;
import io.ratesalgo.messages.sbe.Side;

/** What a {@link Strategy} can see and do during a back-test (API_SPEC.md Section 16.1). */
public interface StrategyContext {

  /** Current replay time, epoch nanoseconds. */
  long nowEpochNanos();

  InstrumentSet instruments();

  boolean hasMid(int instrumentId);

  /** Current mid in quoted units; throws if none has been seen. */
  double mid(int instrumentId);

  /** Current half bid-ask spread of the CLOB book in quoted units, 0 if the instrument has none. */
  double bookHalfSpread(int instrumentId);

  /** Our position in minor units; positive is long (for swaps, pay fixed). */
  long position(int instrumentId);

  /** Net DV01 of the book, major currency per 1 bp rise. */
  double netDv01();

  /** The hit-rate model at the run's central band point, for strategies that use one. */
  HitRateModel hitRateModel();

  /** The session's scheduled events. */
  EventCalendar calendar();

  /**
   * Sends a two-way quote for an RFQ. Prices are mantissas (exponent -9). Only the side the client
   * asked for is settled. A second quote for the same RFQ is refused.
   */
  void quote(long rfqId, long bidMantissa, long askMantissa);

  /**
   * Sends an order. Market orders are filled by the hedge cost model; limit orders rest on the
   * CLOB and fill by queue position.
   *
   * @return the client order id
   */
  long sendOrder(int instrumentId, Side side, OrdType type, long quantity, long priceMantissa, boolean hedge);

  /**
   * Cancels a limit order that has not filled. A resting order is reported {@code Cancelled} at
   * once; one still on its way to the venue is reported {@code Cancelled} when it arrives.
   *
   * @return false when the order has filled, was already cancelled, is a market order, or is unknown
   */
  default boolean cancelOrder(final long clOrdId) {
    return false;
  }

  /** Best price (mantissa) of the CLOB book on one side, 0 if that side is empty or there is no book. */
  default long touchPrice(final int instrumentId, final boolean bidSide) {
    return 0L;
  }

  /** Displayed quantity (minor units) at the best price on one side, 0 if none. */
  default long touchQuantity(final int instrumentId, final boolean bidSide) {
    return 0L;
  }

  /** Schedules {@link Strategy#onTimer} at an epoch time no earlier than now; returns the timer id. */
  long scheduleTimer(long epochNanos);
}
