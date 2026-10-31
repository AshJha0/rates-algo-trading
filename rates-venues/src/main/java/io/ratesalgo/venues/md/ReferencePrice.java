package io.ratesalgo.venues.md;

/**
 * The fitted reference a quote is checked against. Until the P1 fitted curve is wired this is a
 * {@link BandReference}; a curve-backed implementation replaces it without changing the composite.
 */
@FunctionalInterface
public interface ReferencePrice {

  /** Reference mid for an instrument as a mantissa, or 0 if there is none (no outlier check). */
  long reference(int instrumentId);
}
