package io.ratesalgo.posttrade.marks;

import java.time.LocalDate;

/** No official close exists for a position that needs one; official P&amp;L is not produced. */
public final class MissingMarkException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int instrumentId;

  public MissingMarkException(final int instrumentId, final LocalDate date) {
    super("no official mark for instrument " + instrumentId + " on " + date);
    this.instrumentId = instrumentId;
  }

  /** The instrument without a mark. */
  public int instrumentId() {
    return instrumentId;
  }
}
