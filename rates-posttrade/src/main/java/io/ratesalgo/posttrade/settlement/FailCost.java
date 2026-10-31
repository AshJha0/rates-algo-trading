package io.ratesalgo.posttrade.settlement;

import io.ratesalgo.messages.sbe.Side;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The cost of a settlement fail (API_SPEC.md Section 17.7), from our point of view, in minor
 * units, unrounded. Rates are Decimal9 mantissas of a rate in percent; day count actual/360.
 *
 * <ul>
 *   <li>Funding: while a delivery fails, the deliverer is not paid and the receiver keeps its cash.
 *       If we sold and our delivery fails, we lose {@code proceeds * fundingRate * days / 360}; if
 *       we bought and the seller fails, we earn it.
 *   <li>Fails charge (US Treasuries, TMPG practice): the failing deliverer pays the receiver
 *       {@code proceeds * max(3% - referenceRate, 0) * days / 360}, where the reference rate is
 *       the TMPG reference rate supplied by the caller. Zero when rates are above 3 percent.
 * </ul>
 */
public final class FailCost {

  private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(360);
  private static final BigDecimal PERCENT_MANTISSA = BigDecimal.valueOf(100_000_000_000L);
  private static final long THREE_PERCENT = 3_000_000_000L;

  private FailCost() {}

  /** Funding effect of a fail on us: negative if we are the failing deliverer, positive if we receive. */
  public static BigDecimal fundingMinor(
      final Side ourSide, final long proceedsMinor, final long fundingRatePercent, final long days) {
    final BigDecimal amount = accrual(proceedsMinor, fundingRatePercent, days);
    return ourSide == Side.Sell ? amount.negate() : amount;
  }

  /** TMPG fails charge: negative if we fail to deliver, positive if we are failed to. */
  public static BigDecimal failsChargeMinor(
      final Side ourSide, final long proceedsMinor, final long referenceRatePercent, final long days) {
    final long rate = Math.max(THREE_PERCENT - referenceRatePercent, 0);
    final BigDecimal amount = accrual(proceedsMinor, rate, days);
    return ourSide == Side.Sell ? amount.negate() : amount;
  }

  private static BigDecimal accrual(final long proceedsMinor, final long ratePercent, final long days) {
    if (days < 0) {
      throw new IllegalArgumentException("negative fail days " + days);
    }
    return BigDecimal.valueOf(proceedsMinor)
        .multiply(BigDecimal.valueOf(ratePercent))
        .multiply(BigDecimal.valueOf(days))
        .divide(PERCENT_MANTISSA.multiply(DAYS_IN_YEAR), MathContext.DECIMAL128);
  }
}
