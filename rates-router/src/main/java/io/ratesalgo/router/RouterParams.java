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
 * @param filledAdverseFactor expected adverse move after a passive child fills, in units of sigma
 *     over its expected queue wait (capped at the passive horizon): a resting order is filled more
 *     often when the price is about to move through it (PNL_AUDIT.md item 15); 0 turns it off
 * @param messageCostPoints cost of one order message (new order or cancel), in price points on one
 *     lot of the venue, standing for fees and the venue's order-to-trade policy (item 18); 0 turns
 *     it off
 * @param volatility daily volatility per instrument; instruments it does not list use {@code
 *     sigmaDailyPoints}
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
    int maxSteps,
    double filledAdverseFactor,
    double messageCostPoints,
    Volatility volatility) {

  /**
   * Daily price volatility per instrument, in price points. Immutable; looked up by a linear scan,
   * which for the handful of instruments a desk hedges in is faster than hashing and allocates
   * nothing.
   */
  public static final class Volatility {

    /** No per-instrument values. */
    public static final Volatility NONE = new Volatility(new int[0], new double[0]);

    private final int[] ids;
    private final double[] sigmas;

    private Volatility(final int[] ids, final double[] sigmas) {
      this.ids = ids;
      this.sigmas = sigmas;
    }

    /** {@code instrumentIds[i]} has daily volatility {@code sigmaDailyPoints[i]}. */
    public static Volatility of(final int[] instrumentIds, final double[] sigmaDailyPoints) {
      if (instrumentIds.length != sigmaDailyPoints.length) {
        throw new IllegalArgumentException("ids and sigmas differ in length");
      }
      for (int i = 0; i < sigmaDailyPoints.length; i++) {
        check(in(sigmaDailyPoints[i], 1e-6, 10.0), "instrument sigma in [1e-6, 10]", sigmaDailyPoints[i]);
        for (int j = 0; j < i; j++) {
          check(instrumentIds[i] != instrumentIds[j], "instrument listed twice", instrumentIds[i]);
        }
      }
      return new Volatility(instrumentIds.clone(), sigmaDailyPoints.clone());
    }

    /** The instrument's daily volatility, or {@code fallback} when it is not listed. */
    public double sigmaDailyPoints(final int instrumentId, final double fallback) {
      for (int i = 0; i < ids.length; i++) {
        if (ids[i] == instrumentId) {
          return sigmas[i];
        }
      }
      return fallback;
    }

    /** Instruments listed. */
    public int size() {
      return ids.length;
    }

    @Override
    public boolean equals(final Object o) {
      return o instanceof Volatility v
          && java.util.Arrays.equals(ids, v.ids)
          && java.util.Arrays.equals(sigmas, v.sigmas);
    }

    @Override
    public int hashCode() {
      return 31 * java.util.Arrays.hashCode(ids) + java.util.Arrays.hashCode(sigmas);
    }
  }

  /** Parameters without the round-2 controls: no filled-child adverse term, no message cost. */
  public RouterParams(
      final int configVersion,
      final double sigmaDailyPoints,
      final double tradingSecondsPerDay,
      final double impactY,
      final double impactAlpha,
      final double adverseFactor,
      final double latencyRiskFactor,
      final long passiveHorizonMaxNanos,
      final long passiveHorizonMinNanos,
      final int maxSlices,
      final long scheduleHorizonNanos,
      final double sizeJitter,
      final double intervalJitter,
      final int bookMoveThresholdTicks,
      final int aggressiveCollarTicks,
      final long retryDelayNanos,
      final int maxSteps) {
    this(
        configVersion,
        sigmaDailyPoints,
        tradingSecondsPerDay,
        impactY,
        impactAlpha,
        adverseFactor,
        latencyRiskFactor,
        passiveHorizonMaxNanos,
        passiveHorizonMinNanos,
        maxSlices,
        scheduleHorizonNanos,
        sizeJitter,
        intervalJitter,
        bookMoveThresholdTicks,
        aggressiveCollarTicks,
        retryDelayNanos,
        maxSteps,
        0.0,
        0.0,
        Volatility.NONE);
  }

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
    check(in(filledAdverseFactor, 0.0, 5.0), "filledAdverseFactor in [0, 5]", filledAdverseFactor);
    check(in(messageCostPoints, 0.0, 1.0), "messageCostPoints in [0, 1]", messageCostPoints);
    if (volatility == null) {
      throw new IllegalArgumentException("volatility must not be null; use Volatility.NONE");
    }
  }

  /** Volatility per square root of a second, price points. */
  public double sigmaPerSqrtSecond() {
    return sigmaDailyPoints / StrictMath.sqrt(tradingSecondsPerDay);
  }

  /** Daily volatility of one instrument, price points. */
  public double sigmaDailyPoints(final int instrumentId) {
    return volatility.sigmaDailyPoints(instrumentId, sigmaDailyPoints);
  }

  /** Volatility of one instrument per square root of a second, price points. */
  public double sigmaPerSqrtSecond(final int instrumentId) {
    return sigmaDailyPoints(instrumentId) / StrictMath.sqrt(tradingSecondsPerDay);
  }

  /** The same parameters with the round-2 cost controls set (config version bumped by the caller). */
  public RouterParams withControls(
      final int version, final double filledAdverse, final double messageCost, final Volatility vol) {
    return new RouterParams(
        version,
        sigmaDailyPoints,
        tradingSecondsPerDay,
        impactY,
        impactAlpha,
        adverseFactor,
        latencyRiskFactor,
        passiveHorizonMaxNanos,
        passiveHorizonMinNanos,
        maxSlices,
        scheduleHorizonNanos,
        sizeJitter,
        intervalJitter,
        bookMoveThresholdTicks,
        aggressiveCollarTicks,
        retryDelayNanos,
        maxSteps,
        filledAdverse,
        messageCost,
        vol);
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
        maxSteps,
        filledAdverseFactor,
        messageCostPoints,
        volatility);
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
