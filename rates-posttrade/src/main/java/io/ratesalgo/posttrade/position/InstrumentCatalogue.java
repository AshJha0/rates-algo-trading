package io.ratesalgo.posttrade.position;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixture securities master and linear {@link ValuationConvention}: {@code pnl = q * (to - from)
 * * multiplier / 10^11}, where 10^9 undoes the Decimal9 mantissa and 100 converts a price per 100
 * (or a rate in percent) to a fraction.
 */
public final class InstrumentCatalogue implements ValuationConvention {

  /** 10^9 (mantissa) times 100 (per 100 or percent). */
  public static final BigDecimal SCALE = BigDecimal.valueOf(100_000_000_000L);

  /** Precision used for every intermediate P&amp;L value: 34 significant digits. */
  public static final MathContext PRECISION = MathContext.DECIMAL128;

  private final Map<Integer, InstrumentReference> byId = new HashMap<>();

  /** Adds or replaces an instrument. */
  public InstrumentCatalogue add(final InstrumentReference reference) {
    byId.put(reference.instrumentId(), reference);
    return this;
  }

  /**
   * Static data for one instrument.
   *
   * @throws IllegalArgumentException if the instrument is unknown; post-trade never guesses
   *     static data
   */
  public InstrumentReference get(final int instrumentId) {
    final InstrumentReference ref = byId.get(instrumentId);
    if (ref == null) {
      throw new IllegalArgumentException("instrument " + instrumentId + " not in the catalogue");
    }
    return ref;
  }

  @Override
  public BigDecimal pnl(
      final int instrumentId,
      final long signedQuantity,
      final BigDecimal fromPrice,
      final BigDecimal toPrice) {
    return BigDecimal.valueOf(signedQuantity)
        .multiply(toPrice.subtract(fromPrice))
        .multiply(get(instrumentId).multiplier())
        .divide(SCALE, PRECISION);
  }
}
