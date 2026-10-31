package io.ratesalgo.strategy;

/**
 * Exponentially decaying estimate of the rate at which client fills add DV01 to the book, one
 * rate for fills that raise net DV01 and one for fills that lower it, in DV01 per second.
 *
 * <pre>
 *   rate(t) = rate(t0) * exp(-(t - t0) / tau) + sum over fills since t0 of |dv01| / tau * exp(-(t - t_i) / tau)
 * </pre>
 *
 * Starts at a declared prior rate on each side. Allocation-free.
 */
public final class FlowRateEstimator {

  private final double tauSeconds;
  private double up;
  private double down;
  private long lastNanos = Long.MIN_VALUE;

  /**
   * @param tauSeconds decay time constant, seconds
   * @param priorDv01PerSecond starting rate on each side
   */
  public FlowRateEstimator(final double tauSeconds, final double priorDv01PerSecond) {
    if (!(tauSeconds > 0) || !(priorDv01PerSecond >= 0)) {
      throw new IllegalArgumentException("tau " + tauSeconds + ", prior " + priorDv01PerSecond);
    }
    this.tauSeconds = tauSeconds;
    this.up = priorDv01PerSecond;
    this.down = priorDv01PerSecond;
  }

  private void decayTo(final long nowNanos) {
    if (lastNanos != Long.MIN_VALUE && nowNanos > lastNanos) {
      final double f = Math.exp(-(nowNanos - lastNanos) / 1e9 / tauSeconds);
      up *= f;
      down *= f;
    }
    if (lastNanos == Long.MIN_VALUE || nowNanos > lastNanos) {
      lastNanos = nowNanos;
    }
  }

  /** Records a client fill that changed net DV01 by {@code tradeDv01}. */
  public void onFill(final double tradeDv01, final long nowNanos) {
    decayTo(nowNanos);
    if (tradeDv01 > 0) {
      up += tradeDv01 / tauSeconds;
    } else {
      down += -tradeDv01 / tauSeconds;
    }
  }

  /** Rate of fills whose DV01 has the opposite sign to {@code exposureDv01}, DV01 per second. */
  public double opposingRate(final double exposureDv01, final long nowNanos) {
    decayTo(nowNanos);
    return exposureDv01 > 0 ? down : up;
  }
}
