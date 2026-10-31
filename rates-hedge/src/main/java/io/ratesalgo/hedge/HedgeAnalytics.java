package io.ratesalgo.hedge;

import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Instrument;

/**
 * Hedge analytics: slippage of each fill against the mid at the time the parent was submitted
 * (arrival mid), the residual-risk series, and the basis attribution hook. Slippage is positive
 * when it cost us: buying above or selling below arrival. Allocation-free after construction.
 */
public final class HedgeAnalytics {

  private final BasisAttribution basis;
  private final long[] times;
  private final double[] variances;
  private final double[] nets;
  private int count;
  private int head;
  private long fills;
  private double slippageCost;
  private double slippageBpWeighted;
  private double filledPv01;

  /** Keeps the last {@code capacity} residual observations. */
  public HedgeAnalytics(final int capacity, final BasisAttribution basis) {
    this.basis = basis;
    times = new long[capacity];
    variances = new double[capacity];
    nets = new double[capacity];
  }

  /** Records one fill against its arrival mid; {@code quotePerBp} is quote units per bp. */
  public void onFill(
      final Instrument instrument,
      final long signedQuantityMinor,
      final long price,
      final long arrivalMid,
      final double quotePerBp,
      final double unitParallelPv01) {
    final double slip =
        (Decimal9.toDouble(price) - Decimal9.toDouble(arrivalMid)) * (signedQuantityMinor > 0 ? 1.0 : -1.0);
    final double qtyMajor = Math.abs(instrument.major(signedQuantityMinor));
    final double cost = slip * instrument.quoteValue() * qtyMajor;
    final double pv01 = Math.abs(unitParallelPv01) * qtyMajor;
    fills++;
    slippageCost += cost;
    if (quotePerBp > 0.0) {
      slippageBpWeighted += slip / quotePerBp * pv01;
      filledPv01 += pv01;
    }
    basis.onHedgeFill(instrument.id(), signedQuantityMinor, price, arrivalMid);
  }

  /** Appends a residual-risk observation: variance (currency squared) and net PV01. */
  public void recordResidual(final long epochNanos, final double variance, final double netPv01) {
    times[head] = epochNanos;
    variances[head] = variance;
    nets[head] = netPv01;
    head = (head + 1) % times.length;
    count = Math.min(count + 1, times.length);
  }

  /** Observations held. */
  public int residualCount() {
    return count;
  }

  /** The {@code i}th oldest held observation's time. */
  public long residualTime(final int i) {
    return times[slot(i)];
  }

  /** The {@code i}th oldest held observation's variance. */
  public double residualVariance(final int i) {
    return variances[slot(i)];
  }

  /** The {@code i}th oldest held observation's net PV01. */
  public double residualNet(final int i) {
    return nets[slot(i)];
  }

  public long fills() {
    return fills;
  }

  /** Total slippage cost in major currency units. */
  public double slippageCost() {
    return slippageCost;
  }

  /** PV01-weighted average slippage in basis points, or 0 with no fills. */
  public double averageSlippageBp() {
    return filledPv01 == 0.0 ? 0.0 : slippageBpWeighted / filledPv01;
  }

  private int slot(final int i) {
    if (i < 0 || i >= count) {
      throw new IndexOutOfBoundsException("residual " + i + " of " + count);
    }
    return (head - count + i + times.length) % times.length;
  }
}
