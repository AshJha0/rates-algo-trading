package io.ratesalgo.posttrade.position;

import io.ratesalgo.posttrade.trade.PositionKey;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A position computed from the live trades of one book, account and instrument.
 *
 * @param key book, account, instrument
 * @param netQuantity minor units, positive long
 * @param averagePrice weighted average cost of the open quantity, Decimal9 mantissa units (zero
 *     when flat)
 * @param realisedPnl realised P&amp;L in minor units, unrounded
 * @param liveTrades number of live trades (opening position excluded)
 */
public record Position(
    PositionKey key, long netQuantity, BigDecimal averagePrice, BigDecimal realisedPnl, int liveTrades) {

  /** Realised P&amp;L rounded to the minor unit, half-even (API_SPEC.md Section 17.3). */
  public long realisedMinor() {
    return roundMinor(realisedPnl);
  }

  /** Rounds a minor-unit amount to a whole minor unit, half-even. */
  public static long roundMinor(final BigDecimal amount) {
    return amount.setScale(0, RoundingMode.HALF_EVEN).longValueExact();
  }
}
