package io.ratesalgo.sim;

import io.ratesalgo.common.random.SplitMix64;

/**
 * The stream table of design section 2. Every random quantity of a session belongs to one stream;
 * stream {@code s} is {@code SplitMix64.forStream(seed, s)}. The identifiers are part of the
 * specification: changing one changes every session.
 */
public final class Streams {

  /** Curve factor j uses stream {@code FACTOR + j}, j = 0, 1, 2: one normal per step. */
  public static final long FACTOR = 1;

  /** Swap-spread factor: one normal per step. */
  public static final long SPREAD = 4;

  /** Futures contract c uses stream {@code BASIS + c}: one normal per step. */
  public static final long BASIS = 10;

  /** Tenor node n uses stream {@code IDIOSYNCRATIC + n}: one normal per step. */
  public static final long IDIOSYNCRATIC = 20;

  /** RFQ arrivals: per bin one Poisson count, then that many uniforms. */
  public static final long ARRIVALS = 100;

  /** Order-book instrument b uses stream {@code TAKERS + b}: per step a count, then per trade a sign and a size. */
  public static final long TAKERS = 200;

  /** RFQ number r uses stream {@code RFQ + r} for all of its attributes. */
  public static final long RFQ = 1_000_000;

  /** Most futures contracts and most order-book instruments the table leaves room for. */
  public static final int MAX_FUTURES = 10;

  private Streams() {}

  /** The generator of one stream of a session. */
  public static SplitMix64 of(final long seed, final long stream) {
    return SplitMix64.forStream(seed, stream);
  }
}
