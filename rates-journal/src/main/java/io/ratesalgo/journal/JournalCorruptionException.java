package io.ratesalgo.journal;

/** The journal's bytes do not describe a valid frame stream. Carries the sequence where it broke. */
public final class JournalCorruptionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final long sequence;

  public JournalCorruptionException(final long sequence, final String message) {
    super("journal corrupt at sequence " + sequence + ": " + message);
    this.sequence = sequence;
  }

  /** The sequence number of the frame that failed, or 0 for a file header problem. */
  public long sequence() {
    return sequence;
  }
}
