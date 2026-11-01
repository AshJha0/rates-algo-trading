package io.ratesalgo.app.gateway;

/**
 * How a {@link VenueSession} retries a connection whose Logon was not accepted (DR-INT-09).
 *
 * <p>An attempt fails when the TCP connect is refused, when the venue closes the connection before
 * answering our Logon (a QuickFIX/J acceptor that still holds the previous session does this), or
 * when the Logon times out. After the n-th failed attempt the session waits {@code
 * min(initialBackoffNanos * 2^(n-1), maxBackoffNanos)} and tries again, at most {@code maxAttempts}
 * times after the first; then it stays down and counts the give-up. An accepted Logon resets the
 * count. A session that was active and then dropped is not retried here: the loop has pulled its
 * quotes, and reconnecting a live session is an operator decision ({@link VenueSession#connect}).
 *
 * @param maxAttempts retries after the first attempt, at least 0 (0 disables retries)
 * @param initialBackoffNanos wait before the first retry, positive
 * @param maxBackoffNanos cap on the wait, at least {@code initialBackoffNanos}
 */
public record ReconnectPolicy(int maxAttempts, long initialBackoffNanos, long maxBackoffNanos) {

  /** Five retries from 100 ms, doubling, capped at 2 s: about 3.1 s in all before giving up. */
  public static final ReconnectPolicy DEFAULT = new ReconnectPolicy(5, 100_000_000L, 2_000_000_000L);

  /** No retries: the behaviour before DR-INT-09. */
  public static final ReconnectPolicy NONE = new ReconnectPolicy(0, 1L, 1L);

  /** Validates the settings. */
  public ReconnectPolicy {
    if (maxAttempts < 0) {
      throw new IllegalArgumentException("maxAttempts must be at least 0: " + maxAttempts);
    }
    if (initialBackoffNanos <= 0 || maxBackoffNanos < initialBackoffNanos) {
      throw new IllegalArgumentException(
          "need 0 < initialBackoffNanos <= maxBackoffNanos: " + initialBackoffNanos + ", " + maxBackoffNanos);
    }
  }

  /** The wait before retry number {@code attempt} (1 for the first retry). */
  public long backoffNanos(final int attempt) {
    long wait = initialBackoffNanos;
    for (int i = 1; i < attempt && wait < maxBackoffNanos; i++) {
      wait = Math.min(maxBackoffNanos, wait * 2);
    }
    return Math.min(wait, maxBackoffNanos);
  }
}
