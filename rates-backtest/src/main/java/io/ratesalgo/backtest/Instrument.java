package io.ratesalgo.backtest;

/**
 * One tradable instrument as the back-tester values it.
 *
 * <p>Valuation is linear in the quoted price: a position of {@code q} minor units at quoted price
 * {@code p} is worth {@code q * p * valuePerUnit} in major currency units. For swaps the quoted
 * price is the fixed rate in percent and a long position is the payer swap (pay fixed), so the
 * position gains when the rate rises; {@code valuePerUnit} is the annuity times 1e-4. For bonds
 * and futures the quoted price is per 100 of face and {@code valuePerUnit} is 1e-4. The linear
 * approximation is stated in API_SPEC.md Section 16.4 and is exact for the attribution identity,
 * which only needs P&amp;L to be linear in price; P1 analytics replace the annuity with a pricer.
 *
 * @param id instrument id (API_SPEC.md Section 0; never zero)
 * @param name short name used in reports
 * @param kind swap, bond or future
 * @param tenorYears tenor in years, used by the hit-rate model and spread tables
 * @param valuePerUnit major currency per minor unit of quantity per 1.0 of quoted price
 * @param priceChangePerBp change in quoted price for a 1 bp rise in its yield or rate (signed)
 * @param tickMantissa minimum price increment as a mantissa with exponent -9
 * @param lotSize quantity increment in minor units (one futures contract, or 1 million notional)
 * @param fundingRate annual funding rate charged on the position's value (bonds only), decimal
 */
public record Instrument(
    int id,
    String name,
    Kind kind,
    double tenorYears,
    double valuePerUnit,
    double priceChangePerBp,
    long tickMantissa,
    long lotSize,
    double fundingRate) {

  /** Instrument kinds. */
  public enum Kind {
    SWAP,
    BOND,
    FUTURE
  }

  public Instrument {
    if (id == 0) {
      throw new IllegalArgumentException("instrument id 0 is never valid");
    }
    if (valuePerUnit <= 0 || tickMantissa <= 0 || lotSize <= 0 || priceChangePerBp == 0) {
      throw new IllegalArgumentException("invalid instrument parameters for " + name);
    }
  }

  /** P&amp;L in major currency for a 1 bp rise in rates, per minor unit of position. */
  public double dv01PerUnit() {
    return valuePerUnit * priceChangePerBp;
  }

  /** Converts a price distance to basis points of yield or rate (always non-negative input). */
  public double priceToBp(final double priceDistance) {
    return priceDistance / Math.abs(priceChangePerBp);
  }

  /** Converts basis points of yield or rate to a price distance. */
  public double bpToPrice(final double bp) {
    return bp * Math.abs(priceChangePerBp);
  }
}
