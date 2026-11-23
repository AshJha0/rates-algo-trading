package io.ratesalgo.sim;

/**
 * One instrument as the market model sees it (design section 3).
 *
 * @param index position in the universe; stream ids are derived from it
 * @param key stable name, such as {@code SOFR-10Y}, {@code UST-10Y}, {@code TY}
 * @param id wire instrument id used by the back-test journal
 * @param kind swap, Treasury or future
 * @param nodeYears maturity in years used for the factor loadings
 * @param initialMid latent mid at the session start: percent for swaps, price per 100 otherwise
 * @param priceChangePerBp change of the quoted mid for +1 bp of yield: 0.01 for swaps, negative
 *     points for Treasuries and futures
 * @param tick price increment in quoted units, applied only where a price leaves the model
 * @param unitFaceUsd USD face of one quantity unit (1 for swaps and Treasuries, 100,000 for TY)
 * @param minQuantity smallest order and size increment, in quantity units
 * @param annuity swap value per bp per unit notional times 1e4 (0 for others); valuation data
 */
public record SimInstrument(
    int index,
    String key,
    int id,
    Kind kind,
    double nodeYears,
    double initialMid,
    double priceChangePerBp,
    double tick,
    double unitFaceUsd,
    double minQuantity,
    double annuity) {

  /** Instrument kinds. */
  public enum Kind {
    SWAP,
    TREASURY,
    FUTURE
  }

  public SimInstrument {
    if (key == null || key.isEmpty() || kind == null) {
      throw new IllegalArgumentException("instrument needs a key and a kind");
    }
    if (!(tick > 0) || !(unitFaceUsd > 0) || !(minQuantity > 0) || priceChangePerBp == 0 || !(nodeYears > 0)) {
      throw new IllegalArgumentException("invalid instrument parameters for " + key);
    }
    if ((kind == Kind.SWAP) != (priceChangePerBp > 0)) {
      throw new IllegalArgumentException(key + ": a swap rate rises with yields, a price falls");
    }
  }

  /** True for instruments that trade on an order book (design section 8). */
  public boolean hasBook() {
    return kind != Kind.SWAP;
  }

  /** A distance in quoted units as bp of yield (non-negative for a non-negative distance). */
  public double toBp(final double quotedDistance) {
    return quotedDistance / Math.abs(priceChangePerBp);
  }

  /** {@code value} rounded to the tick, half away from zero (design section 4). */
  public double roundToTick(final double value) {
    return Math.signum(value) * Math.floor(Math.abs(value) / tick + 0.5) * tick;
  }

  /** The same instrument with another tick (acceptance test A6). */
  public SimInstrument withTick(final double newTick) {
    return new SimInstrument(
        index, key, id, kind, nodeYears, initialMid, priceChangePerBp, newTick, unitFaceUsd, minQuantity, annuity);
  }
}
