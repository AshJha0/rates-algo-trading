package io.ratesalgo.sim;

/**
 * The scheduled event of a session's day (design section 3). The day type is part of the session
 * configuration, not random: {@link #ofSession} assigns it from the session number so that 4 days
 * in 21 carry a release, as in the calibration sample (section 12).
 */
public enum DayType {
  /** No scheduled event. */
  NONE,
  /** Data release, 08:30 to 08:35 New York. */
  RELEASE,
  /** FOMC decision, 14:00 to 14:30 New York. */
  FOMC,
  /** Treasury auction result, 13:00 to 13:05 New York. */
  AUCTION;

  /** Sessions in one cycle of day types; also the block length of the bootstrap (section 12). */
  public static final int CYCLE = 21;

  /** Day type of session {@code n} of a data set: n modulo 21, residues 0-3 release, 4 FOMC, 5-10 auction. */
  public static DayType ofSession(final long n) {
    final int r = (int) Math.floorMod(n, (long) CYCLE);
    if (r <= 3) {
      return RELEASE;
    }
    if (r == 4) {
      return FOMC;
    }
    return r <= 10 ? AUCTION : NONE;
  }
}
