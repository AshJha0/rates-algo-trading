package io.ratesalgo.router;

import static io.ratesalgo.router.RouterParams.check;
import static io.ratesalgo.router.RouterParams.in;

import java.util.List;

/**
 * Parameters of the ladder model (DECISIONS.md DR-R6-R01), the router's round-6 decision model:
 * a consolidated, fee-adjusted ladder sweep for the aggressive leg, a risk charge on the time an
 * order stays open, a fill probability learned from the router's own outcomes, and a venue
 * scorecard in the all-in price. A router built without one runs the touch model of {@link
 * RouterPresets#global()} unchanged, which keeps the ledger's recorded runs reproducible.
 *
 * <p>Every value is a declared assumption, not a fit. None of them is a parameter of any
 * simulator: queue cancellation and market order rates are learned online, starting from the
 * desk's declared prior in {@link VenueParams#cancelRatePerSecond()}.
 *
 * @param modelVersion journaled with every decision taken under these values
 * @param riskAversion risk charge per unit is {@code riskAversion * sigmaSec^2 * T * q / 1e8}
 *     price points for quantity {@code q} (minor units) open for {@code T} seconds: the variance
 *     of the mid over the time the order stays open, per million of face
 * @param postTradeImpactShare share of the square-root impact charged as post-trade impact; the
 *     immediate cost is the ladder walk itself
 * @param scorecardHalfLifeNanos half-life of every scorecard average
 * @param rejectPenalty price points per unit added at a reject rate of 1
 * @param cancelRejectPenalty price points per unit added at a cancel-reject rate of 1
 * @param fillShortfallPenalty price points per unit added when IOC children fill nothing
 * @param markoutPenaltyWeight times the adverse part of the 5 s markout, added per unit
 * @param priorExposureLotSeconds weight of the declared cancellation-rate prior, in lot-seconds
 *     of observed queue
 * @param calibrationPriorFills pseudo-fills at which observed and predicted fill counts are
 *     shrunk to the queue model
 * @param calibrationDecay weight kept by older outcomes at each new one, in (0, 1]
 * @param tradeRateHalfLifeNanos half-life of the trade-print rate per side
 * @param outageFailures consecutive rejects or unacknowledged orders that exclude a venue
 * @param outageCooldownNanos first exclusion; each repeat doubles it, up to 16 times
 * @param scheduleShape shape of the gamma distribution of slice gaps (mean preserved)
 * @param capabilities per venue; venues not listed get {@link VenueCapabilities#standard}
 */
public record ExecutionModel(
    int modelVersion,
    double riskAversion,
    double postTradeImpactShare,
    long scorecardHalfLifeNanos,
    double rejectPenalty,
    double cancelRejectPenalty,
    double fillShortfallPenalty,
    double markoutPenaltyWeight,
    double priorExposureLotSeconds,
    double calibrationPriorFills,
    double calibrationDecay,
    long tradeRateHalfLifeNanos,
    int outageFailures,
    long outageCooldownNanos,
    double scheduleShape,
    List<VenueCapabilities> capabilities) {

  /** Version of {@link #defaults()}. */
  public static final int DEFAULT_VERSION = 1;

  /** Validates every field and copies the capability list. */
  public ExecutionModel {
    check(modelVersion >= 1, "modelVersion >= 1", modelVersion);
    check(in(riskAversion, 0.0, 1e4), "riskAversion in [0, 1e4]", riskAversion);
    check(in(postTradeImpactShare, 0.0, 1.0), "postTradeImpactShare in [0, 1]", postTradeImpactShare);
    check(scorecardHalfLifeNanos > 0, "scorecardHalfLifeNanos > 0", scorecardHalfLifeNanos);
    check(in(rejectPenalty, 0.0, 1.0), "rejectPenalty in [0, 1]", rejectPenalty);
    check(in(cancelRejectPenalty, 0.0, 1.0), "cancelRejectPenalty in [0, 1]", cancelRejectPenalty);
    check(in(fillShortfallPenalty, 0.0, 1.0), "fillShortfallPenalty in [0, 1]", fillShortfallPenalty);
    check(in(markoutPenaltyWeight, 0.0, 10.0), "markoutPenaltyWeight in [0, 10]", markoutPenaltyWeight);
    check(in(priorExposureLotSeconds, 1e-3, 1e9), "priorExposureLotSeconds", priorExposureLotSeconds);
    check(in(calibrationPriorFills, 1e-3, 1e6), "calibrationPriorFills", calibrationPriorFills);
    check(calibrationDecay > 0.0 && calibrationDecay <= 1.0, "calibrationDecay in (0, 1]", calibrationDecay);
    check(tradeRateHalfLifeNanos > 0, "tradeRateHalfLifeNanos > 0", tradeRateHalfLifeNanos);
    check(outageFailures >= 1, "outageFailures >= 1", outageFailures);
    check(outageCooldownNanos > 0, "outageCooldownNanos > 0", outageCooldownNanos);
    check(in(scheduleShape, 0.5, 100.0), "scheduleShape in [0.5, 100]", scheduleShape);
    capabilities = List.copyOf(capabilities);
  }

  /**
   * The shipped assumptions, version 1: risk aversion 1 per price point per million (a 20 million
   * order open 15 s on a 10-year at 0.5 points a day is charged about 0.2 of a 1/64 tick), half
   * the square-root impact as post-trade, 5-minute scorecard half-life, a full reject rate worth
   * 0.05 points (3.2 ticks), a 30 s trade-rate half-life, three failures for an outage with a 5 s
   * first cool-down, and gamma(2) slice gaps. Every venue standard.
   */
  public static ExecutionModel defaults() {
    return new ExecutionModel(
        DEFAULT_VERSION,
        1.0,
        0.5,
        300_000_000_000L,
        0.05,
        0.01,
        0.01,
        0.5,
        200.0,
        5.0,
        0.98,
        30_000_000_000L,
        3,
        5_000_000_000L,
        2.0,
        List.of());
  }

  /** The same model with another risk aversion (tests and the benchmark). */
  public ExecutionModel withRiskAversion(final double lambda) {
    return new ExecutionModel(
        modelVersion, lambda, postTradeImpactShare, scorecardHalfLifeNanos, rejectPenalty,
        cancelRejectPenalty, fillShortfallPenalty, markoutPenaltyWeight, priorExposureLotSeconds,
        calibrationPriorFills, calibrationDecay, tradeRateHalfLifeNanos, outageFailures,
        outageCooldownNanos, scheduleShape, capabilities);
  }

  /** The same model with these venue capabilities. */
  public ExecutionModel withCapabilities(final List<VenueCapabilities> caps) {
    return new ExecutionModel(
        modelVersion, riskAversion, postTradeImpactShare, scorecardHalfLifeNanos, rejectPenalty,
        cancelRejectPenalty, fillShortfallPenalty, markoutPenaltyWeight, priorExposureLotSeconds,
        calibrationPriorFills, calibrationDecay, tradeRateHalfLifeNanos, outageFailures,
        outageCooldownNanos, scheduleShape, caps);
  }

  /** Capabilities of one venue. */
  public VenueCapabilities capabilities(final int venueId) {
    for (final VenueCapabilities c : capabilities) {
      if (c.venueId() == venueId) {
        return c;
      }
    }
    return VenueCapabilities.standard(venueId);
  }

  /** Risk charge per unit, price points, for {@code quantity} open {@code seconds}. */
  public double riskPerUnit(final double sigmaSec, final double seconds, final long quantity) {
    return riskAversion * sigmaSec * sigmaSec * Math.max(0.0, seconds)
        * (quantity / (double) RouterPresets.ONE_MILLION);
  }
}
