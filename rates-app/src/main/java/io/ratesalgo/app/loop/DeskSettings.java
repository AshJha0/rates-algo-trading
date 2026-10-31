package io.ratesalgo.app.loop;

import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The tunable parameters of one instrument group's desk. Each call builds fresh mutable objects,
 * so the live loop and its replay never share state through the settings. The defaults are the
 * values the smoke test and the process run with; they are declared, not fitted (DR-R2W-09).
 *
 * @param housekeepingNanos period of the loop timer that polls quotes, routers and the hedger
 * @param hedgeIntervalNanos how often the hedger measures its residual
 * @param tradeRatePerSecond traded quantity per second the routers assume on the CLOB (minor units)
 * @param hedgeLotMinor hedge lot on the CLOB, minor units of face (the venue's minimum size)
 * @param hedgeMaxLots most lots per hedge instrument in one plan
 * @param seed seed of the routers' anti-gaming jitter
 * @param quoteRatePerSecond the risk engine's quote rate limit; orders get the same, messages five
 *     times it
 */
public record DeskSettings(
    long housekeepingNanos,
    long hedgeIntervalNanos,
    double tradeRatePerSecond,
    long hedgeLotMinor,
    double hedgeMaxLots,
    long seed,
    double quoteRatePerSecond) {

  /** The values the smoke test and the process run with. */
  public static DeskSettings defaults() {
    return new DeskSettings(10_000_000L, 250_000_000L, 2_000_000_000.0, 100_000_000L, 500.0, 2026L, 1_000.0);
  }

  /** The same settings with another quote rate limit (load tests send far faster than a venue). */
  public DeskSettings withQuoteRate(final double perSecond) {
    return new DeskSettings(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        perSecond);
  }

  /**
   * Quoting parameters: firm quotes live for the venue's response window, and the half spread is
   * held between 0.02 and 0.08 bp because the D2C simulator's dealers quote a median half spread
   * of 0.25 bp for swaps and 1/128 of a point for bonds; a desk wider than its competitors never
   * trades on the smoke day.
   */
  public QuotingConfig quoting() {
    return new QuotingConfig()
        .firmTtlNanos(5_000_000_000L)
        .maxInputAgeNanos(5_000_000_000L)
        .heartbeatTimeoutNanos(3_000_000_000L)
        .requoteThrottleNanos(0L)
        .minHalfSpreadBp(0.02)
        .maxHalfSpreadBp(0.08)
        // DR-INT-05: the engine passes an RFQ whose expected cost reaches the cap (NEGATIVE_EDGE,
        // DR-R2R); the default 0.10 bp winner's curse alone is 0.15 bp with three dealers
        .winnersCurseBp(0.02)
        .kappa(2e-6)
        .maxSkewBp(0.05);
  }

  /** Limits wide enough that the smoke day never breaches them by design. */
  public RiskLimits limits() {
    return new RiskLimits()
        .defaultMaxPosition(500_000_000_000L)
        .bucketPv01(2_000_000.0)
        .netPv01(5_000_000.0)
        .grossPv01(20_000_000.0)
        .dailyLoss(50_000_000.0)
        .orderRatePerSecond(quoteRatePerSecond)
        .quoteRatePerSecond(quoteRatePerSecond)
        .messageRatePerSecond(5.0 * quoteRatePerSecond)
        .maxOrderSize(100_000_000_000L);
  }

  /** One tier for every client: firm quotes up to 1 billion. */
  public TierTable tiers() {
    return TierTable.single(1, new ClientTier(1, 1.0, 100_000_000_000L, true, false));
  }

  /** The hedger: hedge once the residual's daily standard deviation exceeds 20,000 USD. */
  public Hedger.Config hedger() {
    return Hedger.config().triggerStdDev(20_000.0).firstTranche(0.5).halfLifeNanos(5_000_000_000L).limitBp(5.0);
  }

  /** The hit-rate preset shipped with rates-quoting. */
  public HitRatePreset hitRate() {
    try {
      return HitRatePreset.shippedDefault();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The CLOB quoter's configuration (built, not enabled on any instrument). */
  public ClobQuoter.Config clob() {
    return ClobQuoter.config().venueId(AppIds.CLOB_VENUE);
  }
}
