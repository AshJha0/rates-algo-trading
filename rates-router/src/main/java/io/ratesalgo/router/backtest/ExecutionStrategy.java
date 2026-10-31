package io.ratesalgo.router.backtest;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.ExecutionListener;
import io.ratesalgo.router.sim.SimMarket;

/** A way of working a parent order, run by {@link BacktestHarness} against a {@link SimMarket}. */
public interface ExecutionStrategy extends ExecutionListener {

  /** Name used in reports. */
  String name();

  /** Prepares for a new day on a fresh market. */
  void startDay(SimMarket market, Clock clock, long seed);

  /** Starts working a parent order. */
  void submit(long parentId, Side side, long quantity);

  /** Called once per simulation step after executions are delivered. */
  void poll();

  /** True once the parent is completely filled. */
  boolean isDone(long parentId);
}
