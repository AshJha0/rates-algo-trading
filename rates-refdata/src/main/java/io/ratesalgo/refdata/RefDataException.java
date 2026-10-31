package io.ratesalgo.refdata;

/** Reference data could not be loaded or a lookup broke a stated rule. Carries file and line. */
public final class RefDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with a message naming the file, line and offending value. */
  public RefDataException(final String message) {
    super(message);
  }

  /** Creates the exception wrapping an underlying cause. */
  public RefDataException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
