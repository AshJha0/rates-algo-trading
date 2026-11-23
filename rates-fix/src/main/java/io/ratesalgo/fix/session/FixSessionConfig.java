package io.ratesalgo.fix.session;

import java.nio.charset.StandardCharsets;

/**
 * Static session settings.
 *
 * @param senderCompId our CompID
 * @param targetCompId the venue's CompID
 * @param heartbeatIntervalSeconds HeartBtInt we send in the logon
 * @param resetOnLogon send ResetSeqNumFlag=Y and start both sequences at 1
 * @param resendTimeoutNanos how long a ResendRequest may stay unanswered before it is sent again
 * @param maxResendRetries times an unanswered ResendRequest is sent again before the session logs
 *     out and disconnects
 */
public record FixSessionConfig(
    String senderCompId,
    String targetCompId,
    int heartbeatIntervalSeconds,
    boolean resetOnLogon,
    long resendTimeoutNanos,
    int maxResendRetries) {

  /** Default wait for a resend: five seconds. */
  public static final long DEFAULT_RESEND_TIMEOUT_NANOS = 5_000_000_000L;

  /** Default number of repeated ResendRequests. */
  public static final int DEFAULT_MAX_RESEND_RETRIES = 2;

  /** Settings with the default resend timeout and retries. */
  public FixSessionConfig(
      final String senderCompId,
      final String targetCompId,
      final int heartbeatIntervalSeconds,
      final boolean resetOnLogon) {
    this(
        senderCompId,
        targetCompId,
        heartbeatIntervalSeconds,
        resetOnLogon,
        DEFAULT_RESEND_TIMEOUT_NANOS,
        DEFAULT_MAX_RESEND_RETRIES);
  }

  /** Validates the settings. */
  public FixSessionConfig {
    if (resendTimeoutNanos <= 0 || maxResendRetries < 0) {
      throw new IllegalArgumentException("resend timeout must be positive and retries at least 0");
    }
    if (senderCompId.isEmpty() || targetCompId.isEmpty()) {
      throw new IllegalArgumentException("CompIDs must be non-empty");
    }
    if (heartbeatIntervalSeconds < 1) {
      throw new IllegalArgumentException("HeartBtInt must be at least 1 second");
    }
  }

  byte[] senderBytes() {
    return senderCompId.getBytes(StandardCharsets.US_ASCII);
  }

  byte[] targetBytes() {
    return targetCompId.getBytes(StandardCharsets.US_ASCII);
  }
}
