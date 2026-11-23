package io.ratesalgo.router.sim;

import io.ratesalgo.router.BookView;
import io.ratesalgo.router.ExecutionListener;
import io.ratesalgo.router.OrderGateway;

/** An in-memory market a back-test steps: its book, its order entry and its accounting. */
public interface SimulatedMarket extends BookView, OrderGateway {

  /** Advances the market by one step to the clock's current time. */
  void step();

  /** Delivers queued executions, trade prints and book changes to the listener, in order. */
  void drain(ExecutionListener listener);

  /** The arrival reference of a parent, price mantissa. */
  long referenceMid();

  /** Quantity filled for a parent. */
  long parentFilled(long parentId);

  /** Sum of fill price (price points) times quantity for a parent. */
  double parentNotional(long parentId);

  /** Sum of fees (price points per unit) times quantity for a parent. */
  double parentFees(long parentId);

  /** Aggressive orders of ours that would have traded against one of our resting orders. */
  long selfCrosses();

  /** Our orders currently resting or in flight. */
  int liveOrders();

  /** Venue ids, in order. */
  int[] venueIds();
}
