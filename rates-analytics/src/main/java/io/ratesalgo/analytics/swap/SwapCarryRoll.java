package io.ratesalgo.analytics.swap;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;

/**
 * Carry and roll-down of a spot-starting par swap over a horizon, in rate.
 *
 * <p>Plain idea. Hold a receiver swap for h days with the curve unchanged. Two things happen.
 * The swap earns its fixed rate against the overnight rate, which is the same as saying that the
 * forward swap starting in h days is struck at a different rate from today's: carry. And the swap
 * is now h days shorter, so it is marked at the curve's rate for the shorter tenor: roll-down.
 *
 * <p>Formal statement. With S(T) the spot par rate of tenor T and F(h, T) the par rate of the
 * swap starting h days after spot and ending on the same date:
 *
 * <pre>
 *   carry     = F(h, T) - S(T)
 *   roll-down = S(T) - S(T - h)
 * </pre>
 *
 * both positive for a receiver on an upward-sloping curve. Their sum F(h, T) - S(T - h) is the
 * rise in the (T - h) rate over the horizon that a receiver can absorb before losing money.
 *
 * <p>The three swaps are built once; {@link #compute} prices them and does not allocate.
 */
public final class SwapCarryRoll {

  private final OisSwap spot;
  private final OisSwap forward;
  private final OisSwap shorter;
  private final OisSwapPricer pricer = new OisSwapPricer();
  private final int horizonDays;
  private double spotRate;
  private double carry;
  private double rollDown;

  /**
   * Builds the swaps.
   *
   * @param convention swap conventions
   * @param tradeDate valuation date
   * @param tenorMonths tenor of the swap
   * @param horizonDays horizon in calendar days, positive and at least a month short of the tenor
   */
  public SwapCarryRoll(
      final SwapConvention convention, final int tradeDate, final int tenorMonths,
      final int horizonDays) {
    final int start = convention.spotDate(tradeDate);
    final int end = Dates.addMonths(start, tenorMonths);
    if (horizonDays < 1 || start + horizonDays > end - 28) {
      throw new IllegalArgumentException(
          "horizon of " + horizonDays + " days does not fit a " + tenorMonths + " month swap");
    }
    this.horizonDays = horizonDays;
    this.spot = OisSwap.of(convention, start, end, 1.0, 0.0, true);
    final int later =
        convention.scheduleCalendar().adjust(start + horizonDays, BusinessDayConvention.FOLLOWING);
    this.forward = OisSwap.of(convention, later, end, 1.0, 0.0, true);
    this.shorter = OisSwap.of(convention, start, end - horizonDays, 1.0, 0.0, true);
  }

  /** Prices the three swaps on the curve. */
  public void compute(final DiscountCurve curve, final FixingHistory fixings, final int today) {
    spotRate = pricer.parRate(spot, curve, fixings, today);
    carry = pricer.parRate(forward, curve, fixings, today) - spotRate;
    rollDown = spotRate - pricer.parRate(shorter, curve, fixings, today);
  }

  /** Horizon in calendar days. */
  public int horizonDays() {
    return horizonDays;
  }

  /** Spot par rate from the last {@link #compute}, decimal. */
  public double spotRate() {
    return spotRate;
  }

  /** Carry from the last {@link #compute}, decimal rate, positive for a receiver. */
  public double carry() {
    return carry;
  }

  /** Roll-down from the last {@link #compute}, decimal rate, positive for a receiver. */
  public double rollDown() {
    return rollDown;
  }
}
