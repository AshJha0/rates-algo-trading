package io.ratesalgo.sim;

/**
 * One RFQ of a session with every attribute the design draws for it (section 5): who asks, for
 * what, whether the client is informed, which competitors answer, when and how wide, and the
 * client's reserve. All of it is fixed by the session seed before any dealer acts, so every arm of
 * a paired experiment faces the same RFQ.
 */
public final class SimRfq {

  private final int number;
  private final long arrivalNanos;
  private final int instrument;
  private final boolean clientBuys;
  private final double sizeDrawMm;
  private final double quantityUnits;
  private final int tier;
  private final boolean informed;
  private final int dealersAsked;
  private final boolean[] answers;
  private final long[] delayNanos;
  private final double[] halfSpreadBp;
  private final double reserveBp;
  private final boolean doneAway;

  SimRfq(
      final int number,
      final long arrivalNanos,
      final int instrument,
      final boolean clientBuys,
      final double sizeDrawMm,
      final double quantityUnits,
      final int tier,
      final boolean informed,
      final int dealersAsked,
      final boolean[] answers,
      final long[] delayNanos,
      final double[] halfSpreadBp,
      final double reserveBp,
      final boolean doneAway) {
    this.number = number;
    this.arrivalNanos = arrivalNanos;
    this.instrument = instrument;
    this.clientBuys = clientBuys;
    this.sizeDrawMm = sizeDrawMm;
    this.quantityUnits = quantityUnits;
    this.tier = tier;
    this.informed = informed;
    this.dealersAsked = dealersAsked;
    this.answers = answers;
    this.delayNanos = delayNanos;
    this.halfSpreadBp = halfSpreadBp;
    this.reserveBp = reserveBp;
    this.doneAway = doneAway;
  }

  /** 0-based number in arrival order; the RFQ's stream is {@code Streams.RFQ + number}. */
  public int number() {
    return number;
  }

  /** Arrival time, nanoseconds after the session start (whole milliseconds). */
  public long arrivalNanos() {
    return arrivalNanos;
  }

  /** Index of the instrument in the universe. */
  public int instrument() {
    return instrument;
  }

  /** True if the client buys: pays fixed on a swap, buys a bond. The dealer quotes above the mid. */
  public boolean clientBuys() {
    return clientBuys;
  }

  /** The size as drawn, USD millions, before rounding to whole millions (swaps: lognormal draw). */
  public double sizeDrawMm() {
    return sizeDrawMm;
  }

  /** Quantity in the instrument's units: USD notional or face. */
  public double quantityUnits() {
    return quantityUnits;
  }

  /** Client tier: 0 is A, 1 is B, 2 is C. */
  public int tier() {
    return tier;
  }

  /** True if the client knows which way the level factor will drift. */
  public boolean informed() {
    return informed;
  }

  /** Dealers asked, the desk included. */
  public int dealersAsked() {
    return dealersAsked;
  }

  /** Competing dealers asked: {@link #dealersAsked} minus the desk. */
  public int competitors() {
    return dealersAsked - 1;
  }

  /** True if competitor {@code d} answers inside the response window. */
  public boolean answers(final int d) {
    return d < competitors() && answers[d];
  }

  /** Competitor {@code d}'s response time, nanoseconds after arrival. */
  public long delayNanos(final int d) {
    return delayNanos[d];
  }

  /** Competitor {@code d}'s half-spread before any tick rounding, bp of yield. */
  public double halfSpreadBp(final int d) {
    return halfSpreadBp[d];
  }

  /** Largest distance from the mid at which the client still trades, bp of yield. */
  public double reserveBp() {
    return reserveBp;
  }

  /** True if the client trades elsewhere whatever is quoted. */
  public boolean doneAway() {
    return doneAway;
  }

  /** Folds every attribute into a running hash (replay test A1). */
  long hash(final long seed) {
    long h = fold(seed, number);
    h = fold(h, arrivalNanos);
    h = fold(h, instrument);
    h = fold(h, clientBuys ? 1 : 0);
    h = fold(h, Double.doubleToRawLongBits(sizeDrawMm));
    h = fold(h, Double.doubleToRawLongBits(quantityUnits));
    h = fold(h, tier * 4L + (informed ? 2 : 0) + (doneAway ? 1 : 0));
    h = fold(h, dealersAsked);
    for (int d = 0; d < answers.length; d++) {
      h = fold(h, answers[d] ? 1 : 0);
      h = fold(h, delayNanos[d]);
      h = fold(h, Double.doubleToRawLongBits(halfSpreadBp[d]));
    }
    return fold(h, Double.doubleToRawLongBits(reserveBp));
  }

  static long fold(final long h, final long v) {
    return io.ratesalgo.common.random.SplitMix64.mix(h * 31 + v);
  }
}
