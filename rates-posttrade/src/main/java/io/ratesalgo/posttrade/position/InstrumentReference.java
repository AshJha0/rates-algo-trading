package io.ratesalgo.posttrade.position;

import java.math.BigDecimal;

/**
 * Static data the post-trade side needs about one instrument. P1's securities master supplies
 * the real values at integration; tests use small fixtures.
 *
 * @param instrumentId internal id (API_SPEC.md Section 0)
 * @param identifier ISIN for bonds; a placeholder such as {@code USD-SOFR-10Y} for swaps until
 *     the securities master assigns one (order record field "financial instrument
 *     identification code")
 * @param currency ISO 4217 code
 * @param priceNotation Annex II price notation: {@code PERC} for price per 100 and for a rate in
 *     percent, {@code YIEL} for a yield, {@code BAPO} for basis points
 * @param multiplier money per minor unit of quantity per one unit of price per 100: 1 for bonds
 *     priced per 100 of face, the annuity in years for a swap quoted in percent
 */
public record InstrumentReference(
    int instrumentId, String identifier, String currency, String priceNotation, BigDecimal multiplier) {

  public InstrumentReference {
    if (instrumentId == 0) {
      throw new IllegalArgumentException("instrument id 0 is never valid");
    }
    if (currency == null || currency.length() != 3) {
      throw new IllegalArgumentException("ISO 4217 currency required for " + instrumentId);
    }
    if (multiplier == null || multiplier.signum() <= 0) {
      throw new IllegalArgumentException("positive multiplier required for " + instrumentId);
    }
  }
}
