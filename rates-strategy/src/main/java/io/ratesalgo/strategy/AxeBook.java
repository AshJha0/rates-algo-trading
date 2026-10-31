package io.ratesalgo.strategy;

import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.messages.sbe.Side;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature 2, axe management: axes derived from inventory and the hedger's needs, as an {@link
 * AxeSource} the quoting engine already reads (API_SPEC.md Section 19.3).
 *
 * <p>Plain idea: when the book is long risk in some tenor, a client who takes that risk off us is
 * worth more than one who adds to it, so we improve our price on the side that reduces the risk,
 * and we are willing to show more size there.
 *
 * <pre>
 *   e(i)      = (1 - w) * netDv01 + w * bucketDv01(bucket of i)        exposure the axe targets
 *   d(i, side) = dv01PerUnit(i) * (side == Buy ? 1 : -1)                DV01 we add per unit traded
 *   axeBp      = maxBp * min(1, |e| / budget)   if e * d &lt; 0 (the trade reduces e), else 0
 *   axeSize    = |e| / |dv01PerUnit(i)|          minor units, on the axed side only
 * </pre>
 *
 * The default is {@link AxeSource#NONE}: no axes. {@link #axeBp} and {@link #axeQuantity} are
 * allocation-free; {@link #publish} builds the axe list for display and allocates.
 */
public final class AxeBook implements AxeSource {

  /** One published axe: we would like to trade this side of this instrument. */
  public record Axe(int instrumentId, Side ourSide, double improveBp, long quantity) {}

  private final RiskLadder ladder;
  private final double maxBp;
  private final double budgetDv01;
  private final double bucketWeight;

  /**
   * @param ladder the desk's ladder, read at call time
   * @param maxBp largest improvement, bp
   * @param budgetDv01 exposure at which the improvement reaches {@code maxBp}
   * @param bucketWeight weight {@code w} of the instrument's own tenor bucket against net DV01
   */
  public AxeBook(final RiskLadder ladder, final double maxBp, final double budgetDv01, final double bucketWeight) {
    if (!(maxBp >= 0) || !(budgetDv01 > 0) || !(bucketWeight >= 0 && bucketWeight <= 1)) {
      throw new IllegalArgumentException("axe parameters: max " + maxBp + " budget " + budgetDv01 + " w " + bucketWeight);
    }
    this.ladder = ladder;
    this.maxBp = maxBp;
    this.budgetDv01 = budgetDv01;
    this.bucketWeight = bucketWeight;
  }

  private double exposure(final int index) {
    return (1.0 - bucketWeight) * ladder.net() + bucketWeight * ladder.bucket(ladder.bucketOfIndex(index));
  }

  @Override
  public double axeBp(final int instrumentId, final Side ourSide) {
    final int i = ladder.indexOf(instrumentId);
    if (i < 0) {
      return 0.0;
    }
    final double e = exposure(i);
    final double d = ourSide == Side.Buy ? ladder.dv01PerUnit(i) : -ladder.dv01PerUnit(i);
    if (e * d >= 0) {
      return 0.0;
    }
    return maxBp * Math.min(1.0, Math.abs(e) / budgetDv01);
  }

  /** Quantity in minor units that would bring the targeted exposure to zero; 0 if not axed. */
  public long axeQuantity(final int instrumentId, final Side ourSide) {
    if (!(axeBp(instrumentId, ourSide) > 0)) {
      return 0L;
    }
    final int i = ladder.indexOf(instrumentId);
    return (long) (Math.abs(exposure(i)) / Math.abs(ladder.dv01PerUnit(i)));
  }

  /** The current axe list, one entry per axed instrument and side, ascending instrument order. */
  public List<Axe> publish() {
    final List<Axe> out = new ArrayList<>();
    for (int i = 0; i < ladder.size(); i++) {
      final int id = ladder.instrumentId(i);
      for (final Side side : new Side[] {Side.Buy, Side.Sell}) {
        final double bp = axeBp(id, side);
        if (bp > 0) {
          out.add(new Axe(id, side, bp, axeQuantity(id, side)));
        }
      }
    }
    return out;
  }
}
