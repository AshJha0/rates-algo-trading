package io.ratesalgo.router;

import io.ratesalgo.messages.sbe.VenueKind;

/**
 * The shipped default parameters, config version 1. They are declared assumptions, not fits:
 * {@code data/presets.yaml} (key {@code router}) records the same values with units, ranges and
 * rationale, and {@code RouterPresetsTest} fails if the two disagree. Refitting from simulator
 * data is deferred until the P2 venue simulators exist (DECISIONS.md DR-P4-03).
 */
public final class RouterPresets {

  /** Config version of the values below. */
  public static final int CONFIG_VERSION = 1;

  /** One million USD of face in minor units (cents). */
  public static final long ONE_MILLION = 100_000_000L;

  /** One Treasury futures contract (100,000 USD face) in minor units. */
  public static final long ONE_CONTRACT = 10_000_000L;

  /** 1/64 of a price point as an int64 mantissa with exponent -9. */
  public static final long TICK_64TH = 15_625_000L;

  private RouterPresets() {}

  /** Router-wide defaults. */
  public static RouterParams global() {
    return new RouterParams(
        CONFIG_VERSION,
        0.5,
        23_400.0,
        0.6,
        0.5,
        0.5,
        1.0,
        30_000_000_000L,
        1_000_000_000L,
        8,
        120_000_000_000L,
        0.25,
        0.3,
        2,
        8,
        500_000_000L,
        32);
  }

  /** Defaults for a cash Treasury central limit order book. */
  public static VenueParams clob(final int venueId) {
    return new VenueParams(
        venueId,
        VenueKind.Clob,
        TICK_64TH,
        ONE_MILLION,
        ONE_MILLION,
        0.0005,
        0.0002,
        250_000L,
        0.05,
        30_000L * ONE_MILLION,
        0.25,
        5);
  }

  /** Defaults for a Treasury futures venue. */
  public static VenueParams futures(final int venueId) {
    return new VenueParams(
        venueId,
        VenueKind.Futures,
        TICK_64TH,
        ONE_CONTRACT,
        ONE_CONTRACT,
        0.0015,
        0.0015,
        500_000L,
        0.03,
        1_500_000L * ONE_CONTRACT,
        0.2,
        5);
  }
}
