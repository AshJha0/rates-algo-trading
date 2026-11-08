package io.ratesalgo.strategy;

/**
 * A limit on how long the desk carries client risk, on top of another budget.
 *
 * <pre>
 *   X = net DV01 of the ladder, t0 = when |X| last rose above floor
 *   inner budget asks for a hedge             hedge what it asks
 *   |X| &gt; floor and now - t0 &gt;= maxCarry
 *     and no client fill for quiet            hedge X - sign(X) * retain
 *   otherwise                                 hold
 * </pre>
 *
 * The inner budget (threshold or netting) still decides the large hedges. This rule catches the
 * exposure that sits below the inner trigger for a long time: without it, a book a little under
 * the threshold can stay unhedged for hours (the latency sweep, research/hedge/results). The clock
 * {@code t0} starts when the exposure first rises above {@code floor} and resets once it falls back
 * to {@code floor} or below, so steady small client flow does not keep restarting it the way the
 * netting budget's time since the latest fill does. Parameters: {@code floor} and {@code retain}
 * in DV01 with {@code 0 <= retain < floor}, {@code maxCarry} and {@code quiet} in nanoseconds.
 *
 * <p>{@code quiet} makes the limit aware of client flow (V1C-002): while client fills keep
 * arriving within {@code quiet} of each other, opposing flow may still net the exposure, so the
 * limit waits; once the flow goes quiet it hedges. With {@code quiet = 0} it is the V1C-001 rule.
 *
 * <p>Stateful (one instance per desk run) and allocation-free.
 */
public final class CarryLimitHedgeBudget implements HedgeBudget {

  private final HedgeBudget inner;
  private final double floorDv01;
  private final double retainDv01;
  private final long maxCarryNanos;
  private final long quietNanos;
  private long riskSinceNanos = -1L;
  private long lastClientFillNanos;

  public CarryLimitHedgeBudget(
      final HedgeBudget inner, final double floorDv01, final double retainDv01, final long maxCarryNanos) {
    this(inner, floorDv01, retainDv01, maxCarryNanos, 0L);
  }

  public CarryLimitHedgeBudget(
      final HedgeBudget inner, final double floorDv01, final double retainDv01, final long maxCarryNanos,
      final long quietNanos) {
    if (inner == null || !(floorDv01 > 0) || !(retainDv01 >= 0 && retainDv01 < floorDv01) || maxCarryNanos <= 0
        || quietNanos < 0) {
      throw new IllegalArgumentException(
          "carry limit needs an inner budget, 0 <= retain < floor, maxCarry > 0 and quiet >= 0: floor " + floorDv01
              + " retain " + retainDv01 + " maxCarry " + maxCarryNanos + " quiet " + quietNanos);
    }
    this.quietNanos = quietNanos;
    this.inner = inner;
    this.floorDv01 = floorDv01;
    this.retainDv01 = retainDv01;
    this.maxCarryNanos = maxCarryNanos;
  }

  /** Without the current time the carry clock cannot run: only the inner budget answers. */
  @Override
  public double excessDv01(final RiskLadder ladder, final long nanosSinceClientFill) {
    return inner.excessDv01(ladder, nanosSinceClientFill);
  }

  @Override
  public double excessDv01(final RiskLadder ladder, final long nanosSinceClientFill, final long nowNanos) {
    final double net = ladder.net();
    lastClientFillNanos = nowNanos - nanosSinceClientFill;
    if (!(Math.abs(net) > floorDv01)) {
      riskSinceNanos = -1L;
      return inner.excessDv01(ladder, nanosSinceClientFill, nowNanos);
    }
    if (riskSinceNanos < 0) {
      riskSinceNanos = nowNanos;
    }
    final double e = inner.excessDv01(ladder, nanosSinceClientFill, nowNanos);
    if (Math.abs(e) > 0) {
      return e;
    }
    if (nowNanos - riskSinceNanos >= maxCarryNanos && nanosSinceClientFill >= quietNanos) {
      return net - Math.signum(net) * retainDv01;
    }
    return 0.0;
  }

  @Override
  public long nextCheckNanos() {
    final long innerNext = inner.nextCheckNanos();
    if (riskSinceNanos < 0) {
      return innerNext;
    }
    final long own = Math.max(riskSinceNanos + maxCarryNanos, lastClientFillNanos + quietNanos);
    return innerNext > 0 ? Math.min(innerNext, own) : own;
  }

  @Override
  public long holdNanos() {
    return inner.holdNanos();
  }

  @Override
  public void observeMarket(final double halfSpreadBp, final double tickBp, final double touchDepthDv01) {
    inner.observeMarket(halfSpreadBp, tickBp, touchDepthDv01);
  }

  public long maxCarryNanos() {
    return maxCarryNanos;
  }

  public long quietNanos() {
    return quietNanos;
  }

  public double floorDv01() {
    return floorDv01;
  }
}
