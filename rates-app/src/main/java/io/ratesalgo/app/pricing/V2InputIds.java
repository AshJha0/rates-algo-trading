package io.ratesalgo.app.pricing;

/**
 * Instrument ids of the curve inputs of pricing v2 that are not tradable instruments of the
 * universe: the overnight fixing estimate, the SOFR futures, and par swaps at tenors without a
 * template. A CurveInput message (template 3) carries one of these in {@code instrumentId}; the
 * kind and value convention are listed per method. A swap that has a template may be sent under
 * its template id or under {@link #parSwap}; both reach the same input.
 *
 * <p>The ids sit above 4,000,000, clear of the reference data (bonds from 1001, swap templates
 * from 2001, futures from 3001).
 */
public final class V2InputIds {

  /**
   * The overnight SOFR estimate for today: kind Fixing, value the rate in percent as a Decimal9
   * mantissa (3.90 percent is 3_900_000_000).
   */
  public static final int OVERNIGHT = 4_000_001;

  private static final int PAR_SWAP_BASE = 4_100_000;
  private static final int SR1_BASE = 4_200_000;
  private static final int SR3_BASE = 4_300_000;

  private V2InputIds() {}

  /**
   * A spot-starting SOFR par swap of a tenor in months: kind ParSwap, value the par rate in
   * percent as a Decimal9 mantissa.
   */
  public static int parSwap(final int tenorMonths) {
    return PAR_SWAP_BASE + tenorMonths;
  }

  /**
   * The one-month SOFR future of a contract month given as yyyymm: kind Future, value the price
   * as a Decimal9 mantissa (96.25 is 96_250_000_000).
   */
  public static int sr1(final int contractMonth) {
    return SR1_BASE + contractMonth % 10_000;
  }

  /**
   * The three-month SOFR future whose reference quarter starts in the contract month given as
   * yyyymm: kind Future, value the price as a Decimal9 mantissa.
   */
  public static int sr3(final int contractMonth) {
    return SR3_BASE + contractMonth % 10_000;
  }
}
