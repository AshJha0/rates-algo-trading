package io.ratesalgo.sim;

/**
 * Seed bases of design section 12. Session n of a data set has seed base + n and day type
 * {@link DayType#ofSession}; the label of a session is its seed.
 *
 * <p>Spent and never to be reused: every session under bases 5,000,000 and 8,000,000 (the old
 * simulators' recorded sessions and the April 2026 "holdout").
 */
public final class SessionSeeds {

  /** Acceptance tests of section 11. */
  public static final long ACCEPTANCE = 28_000_000L;

  /** Pilot: measures the session variance, then is discarded for inference. */
  public static final long PILOT = 29_000_000L;

  /** Exploration set E: may be read freely to design a change. */
  public static final long EXPLORATION = 30_000_000L;

  /** Confirmation set C: read once per pre-registration. */
  public static final long CONFIRMATION = 31_000_000L;

  /** First reserved base; each new confirmation set takes the next million. */
  public static final long RESERVED = 32_000_000L;

  /** Sessions one base has room for. */
  public static final long SPAN = 1_000_000L;

  private SessionSeeds() {}

  /** Seed of session {@code n} of the data set with this base. */
  public static long seed(final long base, final long n) {
    if (base < ACCEPTANCE || base % SPAN != 0) {
      throw new IllegalArgumentException("not a simulator v2 seed base: " + base);
    }
    if (n < 0 || n >= SPAN) {
      throw new IllegalArgumentException("session number outside [0, " + SPAN + "): " + n);
    }
    return base + n;
  }

  /** Day type of the session with this seed. */
  public static DayType dayType(final long seed) {
    return DayType.ofSession(seed % SPAN);
  }

  /** The session with this seed: seed and day type together, as section 12 assigns them. */
  public static MarketModel session(final MarketSpec spec, final long seed) {
    return new MarketModel(spec, seed, dayType(seed));
  }
}
