package io.ratesalgo.strategy;

import io.ratesalgo.backtest.Instrument;

/**
 * Feature 6: repo funding net of coupon carry, plus a balance-sheet charge on gross inventory, over
 * an expected holding period, expressed in bp of the trade's DV01.
 *
 * <pre>
 *   bonds:  cost = (repo - coupon) * dPos * P * v * H / 360  +  bsBond * (|pos'| - |pos|) * P * v * H / 360
 *   swaps:  cost = bsSwap * (|pos'| - |pos|) * v_notional * H / 360
 *   penalty = cost / (quantity * |dv01PerUnit|)                 bp of the trade's DV01
 * </pre>
 *
 * where {@code dPos} is the signed position change, {@code pos'} the position after the trade,
 * {@code P} the price per 100 (taken as 100: the penalty is a skew, not a valuation),
 * {@code v} the value per unit, {@code H} the holding period in days, {@code repo} the
 * instrument's funding rate (the rate the back-tester's position book charges), {@code coupon}
 * its coupon rate and {@code bs*} balance-sheet charges per year. A trade that reduces inventory
 * gets a negative cost, so it is quoted tighter. Swaps carry no repo in the back-tester; their
 * balance-sheet charge (initial margin) applies to notional. Futures are hedges and not quoted.
 */
public final class FundingPenalty implements InventoryPenalty {

  private static final double SWAP_NOTIONAL_PER_UNIT = 1e-2;

  private final double[] couponByIndex;
  private final double bondBsPerYear;
  private final double swapBsPerYear;
  private final double holdingDays;

  /**
   * @param ladder the ladder whose indices {@code couponByIndex} follows
   * @param couponByIndex coupon rate per ladder index, decimal (zero for swaps and futures)
   * @param bondBsPerYear balance-sheet charge on bond inventory value, decimal per year
   * @param swapBsPerYear balance-sheet charge on swap notional, decimal per year
   * @param holdingDays expected holding period, days
   */
  public FundingPenalty(
      final RiskLadder ladder,
      final double[] couponByIndex,
      final double bondBsPerYear,
      final double swapBsPerYear,
      final double holdingDays) {
    if (couponByIndex.length != ladder.size() || bondBsPerYear < 0 || swapBsPerYear < 0 || !(holdingDays > 0)) {
      throw new IllegalArgumentException("invalid funding penalty parameters");
    }
    this.couponByIndex = couponByIndex.clone();
    this.bondBsPerYear = bondBsPerYear;
    this.swapBsPerYear = swapBsPerYear;
    this.holdingDays = holdingDays;
  }

  @Override
  public double penaltyBp(final RiskLadder ladder, final int index, final boolean weBuy, final long quantity) {
    final Instrument inst = ladder.instrument(index);
    final long pos = ladder.position(index);
    final long dPos = weBuy ? quantity : -quantity;
    final long after = pos + dPos;
    final double grossChange = Math.abs(after) - Math.abs(pos);
    final double years = holdingDays / 360.0;
    final double cost;
    if (inst.kind() == Instrument.Kind.BOND) {
      final double perUnit = 100.0 * inst.valuePerUnit();
      cost =
          (inst.fundingRate() - couponByIndex[index]) * dPos * perUnit * years
              + bondBsPerYear * grossChange * perUnit * years;
    } else if (inst.kind() == Instrument.Kind.SWAP) {
      cost = swapBsPerYear * grossChange * SWAP_NOTIONAL_PER_UNIT * years;
    } else {
      return 0.0;
    }
    final double tradeDv01 = quantity * Math.abs(ladder.dv01PerUnit(index));
    return tradeDv01 > 0 ? cost / tradeDv01 : 0.0;
  }
}
