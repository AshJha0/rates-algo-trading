package io.ratesalgo.posttrade.position;

import java.math.BigDecimal;

/**
 * Turns a price move on a signed quantity into money. Prices are Decimal9 mantissas (exponent
 * -9, API_SPEC.md Section 0) carried as {@link BigDecimal} so that average costs stay exact;
 * the result is in the currency's minor units, unrounded.
 *
 * <p>The fixture {@link InstrumentCatalogue} is linear: bonds and futures are priced per 100 of
 * face, and swaps quoted as a par rate in percent use a fixed annuity as the multiplier. P1
 * analytics bind a full revaluation at integration (API_SPEC.md Section 17.3).
 */
@FunctionalInterface
public interface ValuationConvention {

  /**
   * Money made by moving {@code signedQuantity} from one price to another.
   *
   * @param instrumentId instrument
   * @param signedQuantity minor units of notional or face, positive long, negative short
   * @param fromPrice Decimal9 mantissa of the cost or previous mark
   * @param toPrice Decimal9 mantissa of the exit price or new mark
   * @return profit (positive) or loss in minor units, unrounded
   */
  BigDecimal pnl(int instrumentId, long signedQuantity, BigDecimal fromPrice, BigDecimal toPrice);
}
