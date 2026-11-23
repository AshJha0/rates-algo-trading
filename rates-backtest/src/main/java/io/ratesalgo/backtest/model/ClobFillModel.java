package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.backtest.util.Prices;

/**
 * Queue-position fill model for one resting limit order on a central limit order book.
 *
 * <p>Plain idea: an order that joins a price level waits behind the quantity already displayed
 * there. Each later decrease of that level's displayed quantity is split into trades, which
 * consume the queue from the front, and cancellations, which are assumed to come from behind us.
 * The order fills once the quantity ahead of it is gone, and fills completely if the opposite side
 * trades through its price.
 *
 * <pre>
 *   on join:      ahead = displayed quantity at our price (0 if we improve the best)
 *   on decrease:  ahead -= tradeShare * decrease
 *   fill when:    ahead &lt;= 0 (our full quantity, at our price), or the opposite best crosses us
 * </pre>
 *
 * <p>{@code tradeShare} is a declared assumption (preset {@code usd_hedge_cost},
 * {@code queue_trade_share}). Partial fills are not modelled in this version: the order fills in
 * one execution once the queue ahead is exhausted. This is optimistic for large orders and is
 * stated in API_SPEC.md Section 16.3.
 *
 * <p>Simulator v2 ({@link #simV2}): the order rests on the shared market model's book, behind the
 * provider size displayed at its price, and is filled by the model's other takers or when the
 * market comes through it (docs/research/SIM_V2_DESIGN.md section 8). The back-tester still books
 * one execution per order, when the whole quantity has traded; a partial fill is booked when the
 * order is cancelled.
 */
public final class ClobFillModel {

  private final double tradeShare;
  private final SimDay simDay;

  public ClobFillModel(final double tradeShare) {
    if (!(tradeShare > 0 && tradeShare <= 1)) {
      throw new IllegalArgumentException("trade share must be in (0, 1]: " + tradeShare);
    }
    this.tradeShare = tradeShare;
    this.simDay = null;
  }

  private ClobFillModel(final SimDay simDay) {
    this.tradeShare = 1.0;
    this.simDay = simDay;
  }

  /** The simulator v2 queue model of one session. */
  public static ClobFillModel simV2(final SimDay day) {
    return new ClobFillModel(day);
  }

  /** The simulator v2 session orders rest in, or null for version 1. */
  public SimDay simDay() {
    return simDay;
  }

  /**
   * Places a limit order on the simulator v2 book. Its {@link Resting#onBook} ignores its
   * arguments and reports whether the whole quantity has traded; the caller advances the session
   * first.
   */
  public Resting joinSimV2(
      final int instrumentId, final boolean buy, final long priceMantissa, final long quantity, final long epochNanos) {
    final int handle = simDay.rest(instrumentId, buy, quantity, Prices.toDouble(priceMantissa), epochNanos);
    return new Resting(buy, priceMantissa, handle);
  }

  /** Mutable state of one resting order. */
  public final class Resting {
    private final boolean buy;
    private final long price;
    private final int handle;
    private double ahead;
    private long lastDisplayed;

    Resting(final boolean buy, final long price, final long displayedAtPrice) {
      this.buy = buy;
      this.price = price;
      this.ahead = displayedAtPrice;
      this.lastDisplayed = displayedAtPrice;
      this.handle = 0;
    }

    Resting(final boolean buy, final long price, final int handle) {
      this.buy = buy;
      this.price = price;
      this.handle = handle;
    }

    /**
     * Cancels a simulator v2 order and returns the quantity it had filled, minor units; 0 for a
     * version 1 order, which fills whole or not at all.
     */
    public long cancel() {
      return handle == 0 ? 0L : Math.round(simDay.cancel(handle));
    }

    /**
     * Applies a book observation.
     *
     * @param sameSideBest best price on our side
     * @param sameSideQty displayed quantity at that price
     * @param oppositeBest best price on the opposite side (0 if none)
     * @return true if the order is now filled
     */
    public boolean onBook(final long sameSideBest, final long sameSideQty, final long oppositeBest) {
      if (handle != 0) {
        return simDay.complete(handle);
      }
      if (oppositeBest != 0 && (buy ? oppositeBest <= price : oppositeBest >= price)) {
        ahead = 0;
        return true;
      }
      final boolean levelGoneThrough = buy ? sameSideBest < price : sameSideBest > price;
      if (levelGoneThrough) {
        // our level emptied and the best moved behind us: the queue ahead traded
        ahead = 0;
        return true;
      }
      if (sameSideBest == price) {
        final long decrease = lastDisplayed - sameSideQty;
        if (decrease > 0) {
          ahead -= tradeShare * decrease;
        }
        lastDisplayed = sameSideQty;
      }
      return ahead <= 0;
    }

    public double ahead() {
      return ahead;
    }

    public long price() {
      return price;
    }

    public boolean buy() {
      return buy;
    }
  }

  /**
   * Joins the queue.
   *
   * @param sameSideBest current best price on our side (0 if empty)
   * @param sameSideQty displayed quantity at that best
   */
  public Resting join(final boolean buy, final long price, final long sameSideBest, final long sameSideQty) {
    final long ahead;
    if (sameSideBest == price) {
      ahead = sameSideQty;
    } else {
      ahead = 0; // improving the best, or joining a level we cannot see: nothing ahead
    }
    return new Resting(buy, price, ahead);
  }

  public double tradeShare() {
    return tradeShare;
  }
}
