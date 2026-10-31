package io.ratesalgo.journal;

/** The journal file has no room for the next frame. Segment rolling arrives in P2. */
public final class JournalFullException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JournalFullException(final int needed, final int available) {
    super("journal full: frame needs " + needed + " bytes, " + available + " available");
  }
}
