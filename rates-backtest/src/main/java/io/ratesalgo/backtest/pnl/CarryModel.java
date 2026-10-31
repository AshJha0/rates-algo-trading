package io.ratesalgo.backtest.pnl;

import io.ratesalgo.backtest.Instrument;
import java.util.Map;

/**
 * Carry over an interval, booked by {@link PositionBook} on the carry line next to funding.
 *
 * <p>{@link #NONE} books nothing. {@link #bondCoupons} accrues bond coupons, so a long bond
 * position earns its coupon against the funding it pays and a short one pays it (PNL_AUDIT.md item
 * 17). Swap roll-down is not modelled.
 */
@FunctionalInterface
public interface CarryModel {

  CarryModel NONE = (instrument, position, mid, fromNanos, toNanos) -> 0.0;

  /** Nanoseconds in a 365-day year, the coupon accrual basis (ACT/365, close to ACT/ACT intraday). */
  double NANOS_PER_YEAR_365 = 365.0 * 86_400e9;

  /** Carry and roll in major currency earned by {@code position} from {@code fromNanos} to {@code toNanos}. */
  double carry(Instrument instrument, long position, double mid, long fromNanos, long toNanos);

  /**
   * Coupon accrual on bonds: face times annual coupon times elapsed ACT/365 years, where face is
   * {@code position * valuePerUnit * 100} (the value of the position at a price of 100). Bonds
   * without an entry and every other kind earn nothing.
   *
   * @param annualCouponByInstrument annual coupon rate per instrument id, as a fraction (0.04 for 4%)
   */
  static CarryModel bondCoupons(final Map<Integer, Double> annualCouponByInstrument) {
    final Map<Integer, Double> coupons = Map.copyOf(annualCouponByInstrument);
    for (final double c : coupons.values()) {
      if (!Double.isFinite(c)) {
        throw new IllegalArgumentException("coupon rate is not finite");
      }
    }
    return (instrument, position, mid, fromNanos, toNanos) -> {
      final Double coupon = coupons.get(instrument.id());
      if (coupon == null || instrument.kind() != Instrument.Kind.BOND || toNanos <= fromNanos) {
        return 0.0;
      }
      final double face = position * instrument.valuePerUnit() * 100.0;
      return face * coupon * ((toNanos - fromNanos) / NANOS_PER_YEAR_365);
    };
  }
}
