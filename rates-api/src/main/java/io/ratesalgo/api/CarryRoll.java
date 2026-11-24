package io.ratesalgo.api;

/**
 * Carry and roll-down of one instrument over a horizon, filled by a {@link CarrySource}. Mutable
 * and reused: the caller owns one and passes it in, so reading carry does not allocate.
 *
 * <p>Both numbers are in basis points of the instrument's own yield or rate over the horizon and
 * are positive when a long position (a long bond, a receiver swap) earns them with the curves
 * unchanged. Their sum is the break-even: the rise in yield over the horizon that would cancel
 * them.
 */
public final class CarryRoll {

  private int horizonDays;
  private double carryBp;
  private double rollDownBp;

  /** An empty holder. */
  public CarryRoll() {}

  /** Sets every field; called by the source. */
  public void set(final int newHorizonDays, final double newCarryBp, final double newRollDownBp) {
    this.horizonDays = newHorizonDays;
    this.carryBp = newCarryBp;
    this.rollDownBp = newRollDownBp;
  }

  /** Zeroes every field. */
  public void clear() {
    set(0, 0.0, 0.0);
  }

  /** Horizon in calendar days. */
  public int horizonDays() {
    return horizonDays;
  }

  /**
   * Carry in basis points: for a bond, coupon income less repo financing; for a swap, the forward
   * par rate less the spot par rate.
   */
  public double carryBp() {
    return carryBp;
  }

  /** Roll-down in basis points: the fall in the curve's yield as the instrument shortens. */
  public double rollDownBp() {
    return rollDownBp;
  }

  /** Carry plus roll-down. */
  public double breakEvenBp() {
    return carryBp + rollDownBp;
  }
}
