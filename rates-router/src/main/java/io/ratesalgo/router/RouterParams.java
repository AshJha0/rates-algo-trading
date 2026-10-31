package io.ratesalgo.router;

/**
 * Router-wide parameters, versioned. Units and ranges are pinned in API_SPEC.md Section 15.1 and
 * mirrored in {@code data/presets.yaml} under the {@code router} key; {@link RouterPresets} holds
 * the shipped defaults and a test checks that the two agree.
 *
 * @param configVersion version journaled with every decision so a decision can be re-evaluated
 * @param sigmaDailyPoints price volatility of the routed instrument, price points per trading day
 * @param tradingSecondsPerDay seconds in the trading day used to scale volatility and volume
 * @param impactY square-root impact coefficient (dimensionless)
 * @param impactAlpha impact exponent; 0.5 is the square-root law
 * @param adverseFactor expected adverse move of an unfilled passive order, in units of sigma
 *     over the passive horizon
 * @param latencyRiskFactor cost of latency, in units of sigma over the venue latency
 * @param passiveHorizonMaxNanos passive time limit at urgency 0
 * @param passiveHorizonMinNanos below this the passive leg is disabled
 * @param maxSlices number of schedule slices at urgency 0
 * @param scheduleHorizonNanos schedule length at urgency 0
 * @param sizeJitter half-width of the uniform relative jitter on slice sizes
 * @param intervalJitter half-width of the uniform relative jitter on slice intervals
 * @param bookMoveThresholdTicks a resting child this many ticks behind the touch is re-routed
 * @param aggressiveCollarTicks an aggressive child may walk this many ticks past the far touch
 * @param retryDelayNanos wait before re-routing quantity an IOC child could not fill
 * @param maxSteps upper bound on greedy allocation steps per decision
 */
public record RouterParams(
    int configVersion,
    double sigmaDailyPoints,
    double tradingSecondsPerDay,
    double impactY,
    double impactAlpha,
    double adverseFactor,
    double latencyRiskFactor,
    long passiveHorizonMaxNanos,
    long passiveHorizonMinNanos,
    int maxSlices,
    long scheduleHorizonNanos,
    double sizeJitter,
    double intervalJitter,
    int bookMoveThresholdTicks,
    int aggressiveCollarTicks,
    long retryDelayNanos,
    int maxSteps) {

  /** Validates every parameter against its pinned range. */
  public RouterParams {
    check(configVersion >= 1, "configVersion must be >= 1", configVersion);
    check(in(sigmaDailyPoints, 1e-6, 10.0), "sigmaDailyPoints in [1e-6, 10]", sigmaDailyPoints);
    check(in(tradingSecondsPerDay, 3_600, 86_400), "tradingSecondsPerDay", tradingSecondsPerDay);
    check(in(impactY, 0.0, 5.0), "impactY in [0, 5]", impactY);
    check(impactAlpha > 0.0 && impactAlpha <= 1.0, "impactAlpha in (0, 1]", impactAlpha);
    check(in(adverseFactor, 0.0, 5.0), "adverseFactor in [0, 5]", adverseFactor);
    check(in(latencyRiskFactor, 0.0, 10.0), "latencyRiskFactor in [0, 10]", latencyRiskFactor);
    check(
        passiveHorizonMinNanos >= 0 && passiveHorizonMinNanos <= passiveHorizonMaxNanos,
        "0 <= passiveHorizonMinNanos <= passiveHorizonMaxNanos",
        passiveHorizonMinNanos);
    check(
        passiveHorizonMaxNanos <= 600_000_000_000L,
        "passiveHorizonMaxNanos <= 600 s",
        passiveHorizonMaxNanos);
    check(maxSlices >= 1 && maxSlices <= 64, "maxSlices in [1, 64]", maxSlices);
    check(
        scheduleHorizonNanos >= 0 && scheduleHorizonNanos <= 3_600_000_000_000L,
        "scheduleHorizonNanos in [0, 3600 s]",
        scheduleHorizonNanos);
    check(in(sizeJitter, 0.0, 0.5), "sizeJitter in [0, 0.5]", sizeJitter);
    check(in(intervalJitter, 0.0, 0.5), "intervalJitter in [0, 0.5]", intervalJitter);
    check(
        bookMoveThresholdTicks >= 1 && bookMoveThresholdTicks <= 100,
        "bookMoveThresholdTicks in [1, 100]",
        bookMoveThresholdTicks);
    check(
        aggressiveCollarTicks >= 0 && aggressiveCollarTicks <= 100,
        "aggressiveCollarTicks in [0, 100]",
        aggressiveCollarTicks);
    check(
        retryDelayNanos > 0 && retryDelayNanos <= 60_000_000_000L,
        "retryDelayNanos in (0, 60 s]",
        retryDelayNanos);
    check(maxSteps >= 1 && maxSteps <= 1_024, "maxSteps in [1, 1024]", maxSteps);
  }

  /** Volatility per square root of a second, price points. */
  public double sigmaPerSqrtSecond() {
    return sigmaDailyPoints / StrictMath.sqrt(tradingSecondsPerDay);
  }

  /** The same parameters with another schedule horizon and passive limit (tests and recipes). */
  public RouterParams withHorizons(final long scheduleHorizon, final long passiveHorizonMax) {
    return new RouterParams(
        configVersion,
        sigmaDailyPoints,
        tradingSecondsPerDay,
        impactY,
        impactAlpha,
        adverseFactor,
        latencyRiskFactor,
        passiveHorizonMax,
        Math.min(passiveHorizonMinNanos, passiveHorizonMax),
        maxSlices,
        scheduleHorizon,
        sizeJitter,
        intervalJitter,
        bookMoveThresholdTicks,
        aggressiveCollarTicks,
        retryDelayNanos,
        maxSteps);
  }

  static boolean in(final double v, final double lo, final double hi) {
    return Double.isFinite(v) && v >= lo && v <= hi;
  }

  static void check(final boolean ok, final String what, final double value) {
    if (!ok) {
      throw new IllegalArgumentException(what + ", got " + value);
    }
  }

  static void check(final boolean ok, final String what, final long value) {
    if (!ok) {
      throw new IllegalArgumentException(what + ", got " + value);
    }
  }
}
