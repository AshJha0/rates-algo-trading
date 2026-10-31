package io.ratesalgo.fix.codec;

/**
 * A field value could not be read as the requested type, or a required field is missing. Only
 * thrown for malformed input, so its allocation is off the normal path.
 */
public final class FixFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int tag;

  /** Creates the exception for a tag with a description. */
  public FixFormatException(final int tag, final String message) {
    super("tag " + tag + ": " + message);
    this.tag = tag;
  }

  /** The tag whose value was wrong or missing. */
  public int tag() {
    return tag;
  }
}
