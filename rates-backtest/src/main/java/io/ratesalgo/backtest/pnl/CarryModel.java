package io.ratesalgo.backtest.pnl;

import io.ratesalgo.backtest.Instrument;

/**
 * Hook for coupon carry and roll-down over an interval. Returns zero until the P1 analytics
 * (curves, bond accrual, swap roll-down) are wired; the attribution line exists now so the report
 * schema does not change when they are.
 */
@FunctionalInterface
public interface CarryModel {

  CarryModel NONE = (instrument, position, mid, fromNanos, toNanos) -> 0.0;

  /** Carry and roll in major currency earned by {@code position} from {@code fromNanos} to {@code toNanos}. */
  double carry(Instrument instrument, long position, double mid, long fromNanos, long toNanos);
}
