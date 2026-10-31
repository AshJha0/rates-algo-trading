package io.ratesalgo.analytics.futures;

/**
 * CBOT Treasury futures and their deliverable baskets (CME Group contract specifications, CBOT
 * Rulebook Chapters 18 to 22 and 40 to 42). Remaining terms are whole months from the first day of
 * the delivery month; {@code maxRemainingMonths} of -1 means unbounded.
 */
public enum TreasuryFutureContract {
  /** 2-Year T-Note: original term at most 5y3m, remaining 1y9m to 2y; face 200,000. */
  TU("2-Year T-Note", 21, 24, 63, 1, 200_000),
  /** 3-Year T-Note: original term at most 7y, remaining 2y9m to 3y; face 200,000. */
  Z3N("3-Year T-Note", 33, 36, 84, 1, 200_000),
  /** 5-Year T-Note: original term at most 5y3m, remaining at least 4y2m; face 100,000. */
  FV("5-Year T-Note", 50, -1, 63, 1, 100_000),
  /** 10-Year T-Note: original at most 10y, remaining 6y6m to 8y; face 100,000. */
  TY("10-Year T-Note", 78, 96, 120, 3, 100_000),
  /** Ultra 10-Year T-Note: original 10y, remaining 9y5m to 10y; face 100,000. */
  TN("Ultra 10-Year T-Note", 113, 120, 120, 3, 100_000),
  /** 20-Year T-Bond: original 20y, remaining 19y2m to 19y11m; face 100,000. */
  TWE("20-Year T-Bond", 230, 239, 240, 3, 100_000),
  /** T-Bond: remaining 15y to less than 25y; face 100,000. */
  US("T-Bond", 180, 299, 360, 3, 100_000),
  /** Ultra T-Bond: remaining at least 25y; face 100,000. */
  UB("Ultra T-Bond", 300, -1, 360, 3, 100_000);

  private final String description;
  private final int minRemainingMonths;
  private final int maxRemainingMonths;
  private final int maxOriginalMonths;
  private final int roundingMonths;
  private final double face;

  TreasuryFutureContract(
      final String description,
      final int minRemainingMonths,
      final int maxRemainingMonths,
      final int maxOriginalMonths,
      final int roundingMonths,
      final double face) {
    this.description = description;
    this.minRemainingMonths = minRemainingMonths;
    this.maxRemainingMonths = maxRemainingMonths;
    this.maxOriginalMonths = maxOriginalMonths;
    this.roundingMonths = roundingMonths;
    this.face = face;
  }

  public String description() {
    return description;
  }

  /** Months to which the conversion factor rounds the remaining term down: 1 or 3. */
  public int roundingMonths() {
    return roundingMonths;
  }

  /** Face value of one contract in dollars. */
  public double face() {
    return face;
  }

  /**
   * True if a bond with the given whole months remaining (from the first day of the delivery
   * month) and original term in months is deliverable.
   */
  public boolean isDeliverable(final int remainingMonths, final int originalMonths) {
    return remainingMonths >= minRemainingMonths
        && (maxRemainingMonths < 0 || remainingMonths <= maxRemainingMonths)
        && originalMonths <= maxOriginalMonths;
  }
}
