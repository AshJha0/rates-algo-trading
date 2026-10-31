package io.ratesalgo.strategy;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.pnl.CarryModel;
import java.util.Map;
import java.util.TreeMap;

/**
 * Coupon accrual of bond positions, Act/360 on face, through the back-tester's {@link CarryModel}
 * hook: {@code carry = position * 100 * valuePerUnit * coupon * dt / 360 days}. With the position
 * book's repo funding this makes bond inventory carry-positive or carry-negative by the sign of
 * coupon minus repo, which is what the funding penalty (feature 6) trades against. Coupons are
 * declared in the experiment config, not fitted.
 */
public final class CouponCarry implements CarryModel {

  private static final double NANOS_PER_YEAR_360 = 360.0 * 86_400e9;

  private final Map<Integer, Double> couponById;

  /** @param couponById coupon rate per bond instrument id, decimal */
  public CouponCarry(final Map<Integer, Double> couponById) {
    this.couponById = new TreeMap<>(couponById);
  }

  @Override
  public double carry(
      final Instrument instrument, final long position, final double mid, final long fromNanos, final long toNanos) {
    if (instrument.kind() != Instrument.Kind.BOND) {
      return 0.0;
    }
    final Double c = couponById.get(instrument.id());
    if (c == null) {
      return 0.0;
    }
    return position * 100.0 * instrument.valuePerUnit() * c * ((toNanos - fromNanos) / NANOS_PER_YEAR_360);
  }

  /** Coupon of an instrument, decimal, zero if none is declared. */
  public double coupon(final int instrumentId) {
    return couponById.getOrDefault(instrumentId, 0.0);
  }
}
