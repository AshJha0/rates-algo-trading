package io.ratesalgo.api;

/**
 * Static data P3 needs about one instrument. P1's securities master is the source of truth; the
 * coordinator builds these from it.
 *
 * <p>{@code quoteValue} is the change in value, in major currency units, of one major unit of
 * quantity when the quote moves by 1.0 in its own convention, signed so that a buyer gains when
 * the fair quote rises above the traded quote: a buyer's P&amp;L is {@code (fair - traded) *
 * quoteValue * quantityMajor}. For a bond or future quoted per 100 of face it is 0.01. For a payer
 * swap quoted as a rate in percent it is the annuity divided by 100. The buyer of a swap pays
 * fixed; a higher quoted rate is worse for the buyer, as a higher price is for a bond.
 */
public final class Instrument {

  private final int id;
  private final String symbol;
  private final ProductType type;
  private final CurrencyCode currency;
  private final double tenorYears;
  private final double quoteValue;
  private final long minorPerMajor;
  private final int signalInstrumentId;
  private final double signalBeta;
  private final long typicalQuantity;

  private Instrument(
      final int id,
      final String symbol,
      final ProductType type,
      final CurrencyCode currency,
      final double tenorYears,
      final double quoteValue,
      final int signalInstrumentId,
      final double signalBeta,
      final long typicalQuantity) {
    if (id == 0) {
      throw new IllegalArgumentException("instrument id 0 is never valid (API_SPEC.md Section 0)");
    }
    if (!(tenorYears > 0.0) || !(quoteValue > 0.0) || typicalQuantity <= 0) {
      throw new IllegalArgumentException(
          "instrument "
              + id
              + ": tenor "
              + tenorYears
              + ", quoteValue "
              + quoteValue
              + ", typicalQuantity "
              + typicalQuantity);
    }
    this.id = id;
    this.symbol = symbol;
    this.type = type;
    this.currency = currency;
    this.tenorYears = tenorYears;
    this.quoteValue = quoteValue;
    this.minorPerMajor = 100L;
    this.signalInstrumentId = signalInstrumentId;
    this.signalBeta = signalBeta;
    this.typicalQuantity = typicalQuantity;
  }

  /** A cash bond quoted per 100 of face. */
  public static Instrument bond(
      final int id,
      final String symbol,
      final CurrencyCode ccy,
      final double tenorYears,
      final long typicalQuantity) {
    return new Instrument(id, symbol, ProductType.BOND, ccy, tenorYears, 0.01, 0, 0.0, typicalQuantity);
  }

  /** A bond future quoted per 100 of face; {@code tenorYears} is the cheapest-to-deliver's. */
  public static Instrument future(
      final int id,
      final String symbol,
      final CurrencyCode ccy,
      final double tenorYears,
      final long typicalQuantity) {
    return new Instrument(
        id, symbol, ProductType.FUTURE, ccy, tenorYears, 0.01, 0, 0.0, typicalQuantity);
  }

  /**
   * A payer swap quoted as a rate in percent. {@code annuity} is the PV of 1 per year on the fixed
   * leg; the futures signal, if any, is {@code signalInstrumentId} with sensitivity {@code
   * signalBeta} (basis points of swap rate per basis point of futures-implied yield).
   */
  public static Instrument swap(
      final int id,
      final String symbol,
      final CurrencyCode ccy,
      final double tenorYears,
      final double annuity,
      final int signalInstrumentId,
      final double signalBeta,
      final long typicalQuantity) {
    return new Instrument(
        id,
        symbol,
        ProductType.SWAP,
        ccy,
        tenorYears,
        annuity / 100.0,
        signalInstrumentId,
        signalBeta,
        typicalQuantity);
  }

  public int id() {
    return id;
  }

  public String symbol() {
    return symbol;
  }

  public ProductType type() {
    return type;
  }

  public CurrencyCode currency() {
    return currency;
  }

  public double tenorYears() {
    return tenorYears;
  }

  /** Value of one major unit of quantity per 1.0 of quote; see the class comment. */
  public double quoteValue() {
    return quoteValue;
  }

  /** Minor units per major unit of quantity (100 for every supported currency). */
  public long minorPerMajor() {
    return minorPerMajor;
  }

  /** Quantity in major units. */
  public double major(final long quantityMinor) {
    return (double) quantityMinor / minorPerMajor;
  }

  /** Futures instrument whose microprice adjusts this swap's fair rate, or 0. */
  public int signalInstrumentId() {
    return signalInstrumentId;
  }

  /** Basis points of this instrument's rate per basis point of the signal's implied yield. */
  public double signalBeta() {
    return signalBeta;
  }

  /** A normal clip size in minor units; the hit-rate model's size feature is relative to it. */
  public long typicalQuantity() {
    return typicalQuantity;
  }

  @Override
  public String toString() {
    return symbol + "(" + id + ", " + type + ", " + currency + ", " + tenorYears + "y)";
  }
}
