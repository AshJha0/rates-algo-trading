package io.ratesalgo.risk;

import io.ratesalgo.api.KeyRateTenors;
import java.util.Arrays;
import org.agrona.collections.Long2LongHashMap;

/**
 * The limit set the risk engine enforces (API_SPEC.md Section 13). PV01 limits are absolute values
 * in major units of each currency per basis point and apply to each currency separately; there is
 * no FX conversion in P3. Quantities are minor units. Rates are messages per second with a burst
 * allowance. Changes go through the control plane and are journaled; {@link #version()} says which
 * reviewed set is in force.
 */
public final class RiskLimits {

  private long version = 1;
  private long defaultMaxPosition = Long.MAX_VALUE;
  private final Long2LongHashMap maxPosition = new Long2LongHashMap(64, 0.5f, -1L);
  private final double[] bucketPv01 = new double[KeyRateTenors.COUNT];
  private double netPv01 = Double.MAX_VALUE;
  private double grossPv01 = Double.MAX_VALUE;
  private double dailyLoss = Double.MAX_VALUE;
  private double basisPv01 = Double.MAX_VALUE;
  private double swapSpreadPv01 = Double.MAX_VALUE;
  private double orderRatePerSecond = 1e9;
  private double quoteRatePerSecond = 1e9;
  private double messageRatePerSecond = 1e9;
  private double burstSeconds = 1.0;
  private long maxOrderSize = Long.MAX_VALUE;
  private double priceCollarBp = Double.MAX_VALUE;
  private long fatFingerQuantity = Long.MAX_VALUE;
  private double fatFingerFraction = 0.10;
  private boolean killOnDailyLoss = true;

  /** Limits that block nothing except fat-finger prices more than 10% from fair. */
  public RiskLimits() {
    Arrays.fill(bucketPv01, Double.MAX_VALUE);
  }

  public long version() {
    return version;
  }

  /** Sets the reviewed version number of this limit set. */
  public RiskLimits version(final long v) {
    version = v;
    return this;
  }

  /** Maximum absolute position, in minor units, for an instrument. */
  public long maxPosition(final int instrumentId) {
    final long v = maxPosition.get(instrumentId);
    return v < 0 ? defaultMaxPosition : v;
  }

  /** Default maximum absolute position for instruments without their own limit. */
  public RiskLimits defaultMaxPosition(final long minor) {
    defaultMaxPosition = positive(minor, "defaultMaxPosition");
    return this;
  }

  /** Maximum absolute position for one instrument. */
  public RiskLimits maxPosition(final int instrumentId, final long minor) {
    maxPosition.put(instrumentId, positive(minor, "maxPosition"));
    return this;
  }

  public double bucketPv01(final int k) {
    return bucketPv01[k];
  }

  /** The same absolute PV01 limit on every pillar. */
  public RiskLimits bucketPv01(final double limit) {
    Arrays.fill(bucketPv01, positive(limit, "bucketPv01"));
    return this;
  }

  /** Absolute PV01 limit on one pillar. */
  public RiskLimits bucketPv01(final int k, final double limit) {
    bucketPv01[k] = positive(limit, "bucketPv01");
    return this;
  }

  public double netPv01() {
    return netPv01;
  }

  public RiskLimits netPv01(final double limit) {
    netPv01 = positive(limit, "netPv01");
    return this;
  }

  public double grossPv01() {
    return grossPv01;
  }

  public RiskLimits grossPv01(final double limit) {
    grossPv01 = positive(limit, "grossPv01");
    return this;
  }

  public double dailyLoss() {
    return dailyLoss;
  }

  /** Loss since the start of day, per currency, beyond which new risk is refused. */
  public RiskLimits dailyLoss(final double limit) {
    dailyLoss = positive(limit, "dailyLoss");
    return this;
  }

  /** Absolute limit on futures-versus-cash basis exposure, per currency per basis point. */
  public double basisPv01() {
    return basisPv01;
  }

  public RiskLimits basisPv01(final double limit) {
    basisPv01 = positive(limit, "basisPv01");
    return this;
  }

  /** Absolute limit on swap-spread exposure, per currency per basis point. */
  public double swapSpreadPv01() {
    return swapSpreadPv01;
  }

  public RiskLimits swapSpreadPv01(final double limit) {
    swapSpreadPv01 = positive(limit, "swapSpreadPv01");
    return this;
  }

  public double orderRatePerSecond() {
    return orderRatePerSecond;
  }

  public RiskLimits orderRatePerSecond(final double rate) {
    orderRatePerSecond = positive(rate, "orderRate");
    return this;
  }

  public double quoteRatePerSecond() {
    return quoteRatePerSecond;
  }

  public RiskLimits quoteRatePerSecond(final double rate) {
    quoteRatePerSecond = positive(rate, "quoteRate");
    return this;
  }

  public double messageRatePerSecond() {
    return messageRatePerSecond;
  }

  public RiskLimits messageRatePerSecond(final double rate) {
    messageRatePerSecond = positive(rate, "messageRate");
    return this;
  }

  /** Bucket depth of every rate limiter, in seconds of the limit's rate. */
  public double burstSeconds() {
    return burstSeconds;
  }

  public RiskLimits burstSeconds(final double seconds) {
    burstSeconds = positive(seconds, "burstSeconds");
    return this;
  }

  public long maxOrderSize() {
    return maxOrderSize;
  }

  public RiskLimits maxOrderSize(final long minor) {
    maxOrderSize = positive(minor, "maxOrderSize");
    return this;
  }

  /** Maximum distance of any quoted or ordered price from fair value, in basis points of value. */
  public double priceCollarBp() {
    return priceCollarBp;
  }

  public RiskLimits priceCollarBp(final double bp) {
    priceCollarBp = positive(bp, "priceCollarBp");
    return this;
  }

  public long fatFingerQuantity() {
    return fatFingerQuantity;
  }

  /** Quantity above which an order is treated as a keying error. */
  public RiskLimits fatFingerQuantity(final long minor) {
    fatFingerQuantity = positive(minor, "fatFingerQuantity");
    return this;
  }

  public double fatFingerFraction() {
    return fatFingerFraction;
  }

  /** Relative price distance from fair beyond which a price is treated as a keying error. */
  public RiskLimits fatFingerFraction(final double fraction) {
    fatFingerFraction = positive(fraction, "fatFingerFraction");
    return this;
  }

  public boolean killOnDailyLoss() {
    return killOnDailyLoss;
  }

  /** Whether a post-trade daily-loss breach triggers the kill switch. */
  public RiskLimits killOnDailyLoss(final boolean value) {
    killOnDailyLoss = value;
    return this;
  }

  private static long positive(final long v, final String name) {
    if (v <= 0) {
      throw new IllegalArgumentException(name + " must be positive: " + v);
    }
    return v;
  }

  private static double positive(final double v, final String name) {
    if (!(v > 0.0)) {
      throw new IllegalArgumentException(name + " must be positive: " + v);
    }
    return v;
  }
}
