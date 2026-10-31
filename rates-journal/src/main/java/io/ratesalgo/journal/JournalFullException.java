package io.ratesalgo.journal;

/**
 * The journal file has no room for the next frame. A {@link SegmentedJournal} rolls to a new
 * segment instead, and throws this only for a frame larger than an empty segment.
 */
public final class JournalFullException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JournalFullException(final int needed, final int available) {
    super("journal full: frame needs " + needed + " bytes, " + available + " available");
  }
}
