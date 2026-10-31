package io.ratesalgo.quoting;

/**
 * Quoting parameters (API_SPEC.md Section 12.1). Every default is a declared assumption named in
 * the table there with its unit. Changes go through the control plane.
 */
public final class QuotingConfig {

  private static final int MAX_EVENTS = 8;

  long firmTtlNanos = 5_000_000_000L;
  long maxInputAgeNanos = 2_000_000_000L;
  long heartbeatTimeoutNanos = 3_000_000_000L;
  long requoteThrottleNanos = 250_000_000L;
  double minHalfSpreadBp = 0.10;
  double maxHalfSpreadBp = 10.0;
  double kappa = 2e-6;
  double maxSkewBp = 2.0;
  double maxImproveBp = 0.25;
  double baselineVolBp = 6.0;
  double volWideningPerBp = 0.05;
  double winnersCurseBp = 0.10;
  double learningRate = 0.01;
  long minQuoteQuantity = 100_000_000L;
  int maxLiveQuotes = 1024;
  int duplicateWindow = 4096;
  long markoutHorizonNanos = 60_000_000_000L;
  private final long[] eventStart = new long[MAX_EVENTS];
  private final long[] eventEnd = new long[MAX_EVENTS];
  private final double[] eventMultiplier = new double[MAX_EVENTS];
  private int eventCount;

  /** Validity of a firm RFQ quote. */
  public QuotingConfig firmTtlNanos(final long v) {
    firmTtlNanos = positive(v, "firmTtlNanos");
    return this;
  }

  /** Oldest input a quote may be priced from; also the age that pulls live quotes. */
  public QuotingConfig maxInputAgeNanos(final long v) {
    maxInputAgeNanos = positive(v, "maxInputAgeNanos");
    return this;
  }

  /** Silence from a heartbeat source after which every quote is pulled. */
  public QuotingConfig heartbeatTimeoutNanos(final long v) {
    heartbeatTimeoutNanos = positive(v, "heartbeatTimeoutNanos");
    return this;
  }

  /** Minimum interval between quotes to the same client on the same instrument. */
  public QuotingConfig requoteThrottleNanos(final long v) {
    requoteThrottleNanos = Math.max(0L, v);
    return this;
  }

  public QuotingConfig minHalfSpreadBp(final double v) {
    minHalfSpreadBp = nonNegative(v, "minHalfSpreadBp");
    return this;
  }

  public QuotingConfig maxHalfSpreadBp(final double v) {
    maxHalfSpreadBp = nonNegative(v, "maxHalfSpreadBp");
    return this;
  }

  /** Inventory risk aversion per unit of currency (Section 12.4). */
  public QuotingConfig kappa(final double v) {
    kappa = nonNegative(v, "kappa");
    return this;
  }

  /** Bound on the inventory skew, bp of value. */
  public QuotingConfig maxSkewBp(final double v) {
    maxSkewBp = nonNegative(v, "maxSkewBp");
    return this;
  }

  /** Furthest a quote may sit on the client's side of fair, bp of value. */
  public QuotingConfig maxImproveBp(final double v) {
    maxImproveBp = nonNegative(v, "maxImproveBp");
    return this;
  }

  /** Daily realised volatility (bp) at which no volatility widening applies. */
  public QuotingConfig baselineVolBp(final double v) {
    baselineVolBp = nonNegative(v, "baselineVolBp");
    return this;
  }

  /** Half-spread added per bp of daily realised volatility above the baseline. */
  public QuotingConfig volWideningPerBp(final double v) {
    volWideningPerBp = nonNegative(v, "volWideningPerBp");
    return this;
  }

  /** Scale of the winner's-curse term, bp per unit of sqrt(2 ln n). */
  public QuotingConfig winnersCurseBp(final double v) {
    winnersCurseBp = nonNegative(v, "winnersCurseBp");
    return this;
  }

  /** Step size of the hit-rate model's feedback update. */
  public QuotingConfig learningRate(final double v) {
    learningRate = nonNegative(v, "learningRate");
    return this;
  }

  /** Smallest quantity worth quoting, minor units; sizes round down to a multiple of it. */
  public QuotingConfig minQuoteQuantity(final long v) {
    minQuoteQuantity = positive(v, "minQuoteQuantity");
    return this;
  }

  public QuotingConfig maxLiveQuotes(final int v) {
    maxLiveQuotes = (int) positive(v, "maxLiveQuotes");
    return this;
  }

  /** Number of recent RFQ ids remembered for duplicate detection. */
  public QuotingConfig duplicateWindow(final int v) {
    duplicateWindow = (int) positive(v, "duplicateWindow");
    return this;
  }

  /** Delay after a fill at which its markout is measured for the toxicity table. */
  public QuotingConfig markoutHorizonNanos(final long v) {
    markoutHorizonNanos = positive(v, "markoutHorizonNanos");
    return this;
  }

  /** A scheduled event window (epoch nanoseconds) during which half-spreads are multiplied. */
  public QuotingConfig addEvent(final long startNanos, final long endNanos, final double multiplier) {
    if (eventCount == MAX_EVENTS || endNanos <= startNanos || !(multiplier >= 1.0)) {
      throw new IllegalArgumentException(
          "event " + startNanos + ".." + endNanos + " x" + multiplier + " (" + eventCount + " held)");
    }
    eventStart[eventCount] = startNanos;
    eventEnd[eventCount] = endNanos;
    eventMultiplier[eventCount] = multiplier;
    eventCount++;
    return this;
  }

  /** Largest event multiplier in force at {@code nowNanos}, or 1. */
  public double eventMultiplier(final long nowNanos) {
    double m = 1.0;
    for (int i = 0; i < eventCount; i++) {
      if (nowNanos >= eventStart[i] && nowNanos < eventEnd[i]) {
        m = Math.max(m, eventMultiplier[i]);
      }
    }
    return m;
  }

  public long firmTtlNanos() {
    return firmTtlNanos;
  }

  public long maxInputAgeNanos() {
    return maxInputAgeNanos;
  }

  public long minQuoteQuantity() {
    return minQuoteQuantity;
  }

  public double kappa() {
    return kappa;
  }

  public double maxSkewBp() {
    return maxSkewBp;
  }

  public double maxImproveBp() {
    return maxImproveBp;
  }

  private static long positive(final long v, final String name) {
    if (v <= 0) {
      throw new IllegalArgumentException(name + " must be positive: " + v);
    }
    return v;
  }

  private static double nonNegative(final double v, final String name) {
    if (!(v >= 0.0)) {
      throw new IllegalArgumentException(name + " must be non-negative: " + v);
    }
    return v;
  }
}
