package io.ratesalgo.quoting;

/**
 * One row of the client tier table.
 *
 * @param tier tier number, 1 best
 * @param spreadMultiplier multiplies the optimal half-spread; below 1 tightens
 * @param maxQuantity largest quantity quoted to this tier, minor units
 * @param firm whether quotes to this tier are firm (otherwise marked subject)
 * @param blocked whether this tier is refused every quote
 */
public record ClientTier(
    int tier, double spreadMultiplier, long maxQuantity, boolean firm, boolean blocked) {

  public ClientTier {
    if (!(spreadMultiplier > 0.0) || maxQuantity <= 0) {
      throw new IllegalArgumentException(
          "tier " + tier + ": multiplier " + spreadMultiplier + ", max " + maxQuantity);
    }
  }
}
