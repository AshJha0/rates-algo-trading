package io.ratesalgo.strategy;

/**
 * Feature 5: a one-step look-ahead stopping rule for an open hedge (the myopic approximation of
 * the optimal stopping problem, stopping when one more step of waiting is expected to lose).
 *
 * <p>Plain idea: waiting a little lets offsetting client flow take the risk off for free, but the
 * open risk costs variance meanwhile. Wait while the expected saving beats the variance charge;
 * once it does not, hedge, in slices if the market impact saved by slicing beats the extra risk.
 *
 * <pre>
 *   X        excess DV01 to hedge, a = |X|
 *   dt       one waiting step, seconds
 *   lambda   rate of opposing client DV01 per second ({@link FlowRateEstimator})
 *   c        hedge cost per DV01 hedged (half the futures spread plus fees, per DV01)
 *   sigma    rate volatility, bp per sqrt(second)
 *   gamma    risk aversion, per unit of currency
 *
 *   saving(dt)   = c * min(a, lambda * dt)
 *   riskCost(dt) = gamma / 2 * sigma^2 * a^2 * dt
 *   WAIT         if saving &gt; riskCost and the time already waited plus dt is within maxWait
 *   STAGED       else if k * a^2 * (1 - 1/n) &gt; gamma / 2 * sigma^2 * a^2 * ds * sum_{j=1}^{n-1} ((n - j) / n)^2
 *   IMMEDIATE    otherwise
 * </pre>
 *
 * where {@code k * a^2} is the market impact of one hedge of {@code a} (the hedge cost model is
 * linear impact per lot, so impact grows with the square of size), {@code n} the number of slices
 * and {@code ds} the interval between them. The same {@code gamma} defines the experiment's
 * risk-adjusted hedge cost (DESK_FEATURES.md, feature 5), so the rule and its test agree on what
 * is being optimised.
 */
public final class OptimalStoppingTiming implements HedgeTiming {

  private final FlowRateEstimator flow;
  private final double gamma;
  private final double sigmaBpPerSqrtSecond;
  private final double hedgeCostPerDv01;
  private final double impactPerDv01Squared;
  private final long waitStepNanos;
  private final long maxWaitNanos;
  private final int slices;
  private final long sliceIntervalNanos;
  private long waitingSince = -1L;

  public OptimalStoppingTiming(
      final FlowRateEstimator flow,
      final double gamma,
      final double sigmaBpPerSqrtSecond,
      final double hedgeCostPerDv01,
      final double impactPerDv01Squared,
      final long waitStepNanos,
      final long maxWaitNanos,
      final int slices,
      final long sliceIntervalNanos) {
    if (!(gamma > 0) || !(sigmaBpPerSqrtSecond > 0) || hedgeCostPerDv01 < 0 || impactPerDv01Squared < 0
        || waitStepNanos <= 0 || maxWaitNanos < 0 || slices < 1 || sliceIntervalNanos < 0) {
      throw new IllegalArgumentException("invalid hedge timing parameters");
    }
    this.flow = flow;
    this.gamma = gamma;
    this.sigmaBpPerSqrtSecond = sigmaBpPerSqrtSecond;
    this.hedgeCostPerDv01 = hedgeCostPerDv01;
    this.impactPerDv01Squared = impactPerDv01Squared;
    this.waitStepNanos = waitStepNanos;
    this.maxWaitNanos = maxWaitNanos;
    this.slices = slices;
    this.sliceIntervalNanos = sliceIntervalNanos;
  }

  @Override
  public Action decide(final double excessDv01, final long nowNanos) {
    final double a = Math.abs(excessDv01);
    if (!(a > 0)) {
      return Action.IMMEDIATE;
    }
    if (waitingSince < 0) {
      waitingSince = nowNanos;
    }
    final double var = sigmaBpPerSqrtSecond * sigmaBpPerSqrtSecond * a * a;
    final double dt = waitStepNanos / 1e9;
    final double saving = hedgeCostPerDv01 * Math.min(a, flow.opposingRate(excessDv01, nowNanos) * dt);
    final double riskCost = 0.5 * gamma * var * dt;
    if (saving > riskCost && nowNanos - waitingSince + waitStepNanos <= maxWaitNanos) {
      return Action.WAIT;
    }
    if (slices > 1 && stagingGain(a) > 0) {
      return Action.STAGED;
    }
    return Action.IMMEDIATE;
  }

  /** Impact saved by slicing minus the variance charge of the slower hedge, currency units. */
  double stagingGain(final double a) {
    final double n = slices;
    double remaining = 0.0;
    for (int j = 1; j < slices; j++) {
      final double f = (n - j) / n;
      remaining += f * f;
    }
    final double impactSaved = impactPerDv01Squared * a * a * (1.0 - 1.0 / n);
    final double risk =
        0.5 * gamma * sigmaBpPerSqrtSecond * sigmaBpPerSqrtSecond * a * a * (sliceIntervalNanos / 1e9) * remaining;
    return impactSaved - risk;
  }

  @Override
  public void onClientFill(final double tradeDv01, final long nowNanos) {
    flow.onFill(tradeDv01, nowNanos);
  }

  @Override
  public void reset() {
    waitingSince = -1L;
  }

  @Override
  public long waitNanos() {
    return waitStepNanos;
  }

  @Override
  public int slices() {
    return slices;
  }

  @Override
  public long sliceIntervalNanos() {
    return sliceIntervalNanos;
  }

  public double gamma() {
    return gamma;
  }

  public double sigmaBpPerSqrtSecond() {
    return sigmaBpPerSqrtSecond;
  }
}
