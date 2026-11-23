package io.ratesalgo.router;

/**
 * The router's bounds on waiting. They are safety limits, not cost-model inputs, so they sit
 * outside {@link RouterParams}: no decision's allocation depends on them, only whether a decision
 * is taken at all, and a venue they exclude is journaled as {@code VenueExclusion.Stale}.
 *
 * @param ackNanos a child the venue has not acknowledged for this long is cancelled
 * @param iocNanos an immediate-or-cancel child with no final report for this long is cancelled
 * @param cancelNanos a cancel with no answer for this long is sent again
 * @param maxCancelAttempts cancels sent for one child before it is flagged unresolved and its
 *     parent fails
 * @param maxDeadlineRetries retries after the parent's deadline before the parent expires
 * @param maxBookAgeNanos a venue whose book is older than this is not routed to; 0 turns the age
 *     check off (the health flag set through {@link SmartOrderRouter#venueHealth} still applies)
 */
public record RouterTimeouts(
    long ackNanos,
    long iocNanos,
    long cancelNanos,
    int maxCancelAttempts,
    int maxDeadlineRetries,
    long maxBookAgeNanos) {

  /** Two seconds for each wait, three cancels, twenty retries past the deadline, no age check. */
  public static final RouterTimeouts DEFAULT =
      new RouterTimeouts(2_000_000_000L, 2_000_000_000L, 2_000_000_000L, 3, 20, 0L);

  public RouterTimeouts {
    if (ackNanos <= 0 || iocNanos <= 0 || cancelNanos <= 0) {
      throw new IllegalArgumentException("timeouts must be > 0");
    }
    if (maxCancelAttempts < 1 || maxDeadlineRetries < 0 || maxBookAgeNanos < 0) {
      throw new IllegalArgumentException("attempt, retry and age bounds out of range");
    }
  }

  /** These timeouts with a book age limit. */
  public RouterTimeouts withMaxBookAgeNanos(final long nanos) {
    return new RouterTimeouts(ackNanos, iocNanos, cancelNanos, maxCancelAttempts, maxDeadlineRetries, nanos);
  }
}
