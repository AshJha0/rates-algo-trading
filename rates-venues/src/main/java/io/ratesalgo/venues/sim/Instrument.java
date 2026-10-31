package io.ratesalgo.venues.sim;

/**
 * An instrument as a simulator sees it.
 *
 * @param instrumentId wire id from the securities master
 * @param securityId ISIN (bonds) or the template code (swaps) carried in FIX SecurityID
 * @param kind {@code bond} (price per 100) or {@code swap} (rate in percent)
 * @param initialMid starting mid, mantissa with exponent -9
 * @param tick price increment, mantissa
 * @param currency ISO 4217
 * @param madeAvailableToTrade CFTC MAT flag (swaps)
 * @param blockThreshold block size in currency units (swaps), 0 if none
 */
public record Instrument(
    int instrumentId,
    String securityId,
    String kind,
    long initialMid,
    long tick,
    String currency,
    boolean madeAvailableToTrade,
    long blockThreshold) {

  /** True for bonds. */
  public boolean isBond() {
    return "bond".equals(kind);
  }
}
