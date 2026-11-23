package io.ratesalgo.fix.session;

import io.ratesalgo.common.security.Secret;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Static session settings.
 *
 * @param senderCompId our CompID
 * @param targetCompId the venue's CompID
 * @param heartbeatIntervalSeconds HeartBtInt we send in the logon
 * @param resetOnLogon send ResetSeqNumFlag=Y and start both sequences at 1; false resumes the
 *     numbers the session holds, so that messages missed while disconnected are resent
 * @param resendTimeoutNanos how long a ResendRequest may stay unanswered before it is sent again
 * @param maxResendRetries times an unanswered ResendRequest is sent again before the session logs
 *     out and disconnects
 * @param username Username (553) sent on the Logon, or null for none
 * @param password Password (554) sent on the Logon, or null for none; set with the username or not
 *     at all
 */
public record FixSessionConfig(
    String senderCompId,
    String targetCompId,
    int heartbeatIntervalSeconds,
    boolean resetOnLogon,
    long resendTimeoutNanos,
    int maxResendRetries,
    String username,
    Secret password) {

  /** Default wait for a resend: five seconds. */
  public static final long DEFAULT_RESEND_TIMEOUT_NANOS = 5_000_000_000L;

  /** Default number of repeated ResendRequests. */
  public static final int DEFAULT_MAX_RESEND_RETRIES = 2;

  /** Settings with the default resend timeout and retries, and no credentials. */
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

  /** Settings without credentials. */
  public FixSessionConfig(
      final String senderCompId,
      final String targetCompId,
      final int heartbeatIntervalSeconds,
      final boolean resetOnLogon,
      final long resendTimeoutNanos,
      final int maxResendRetries) {
    this(
        senderCompId,
        targetCompId,
        heartbeatIntervalSeconds,
        resetOnLogon,
        resendTimeoutNanos,
        maxResendRetries,
        null,
        null);
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
    if ((username == null) != (password == null)) {
      throw new IllegalArgumentException("give a username and a password, or neither");
    }
    if (username != null) {
      if (username.isEmpty() || !fieldSafe(username.getBytes(StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("the username must be non-empty printable ASCII");
      }
      final byte[] value = password.bytes();
      final boolean ok = fieldSafe(value);
      Arrays.fill(value, (byte) 0);
      if (!ok) {
        // says nothing about the value beyond the rule it breaks
        throw new IllegalArgumentException("the password must be printable ASCII to travel in a FIX field");
      }
    }
  }

  /** These settings with logon credentials. */
  public FixSessionConfig withCredentials(final String user, final Secret secret) {
    return new FixSessionConfig(
        senderCompId, targetCompId, heartbeatIntervalSeconds, resetOnLogon, resendTimeoutNanos, maxResendRetries,
        user, secret);
  }

  /** These settings with ResetSeqNumFlag on or off. */
  public FixSessionConfig withResetOnLogon(final boolean reset) {
    return new FixSessionConfig(
        senderCompId, targetCompId, heartbeatIntervalSeconds, reset, resendTimeoutNanos, maxResendRetries,
        username, password);
  }

  /** True if every byte can sit inside a FIX field: printable ASCII, so no SOH and no control bytes. */
  private static boolean fieldSafe(final byte[] value) {
    boolean ok = true;
    for (final byte b : value) {
      ok &= b >= 0x20 && b < 0x7F;
    }
    return ok;
  }

  byte[] senderBytes() {
    return senderCompId.getBytes(StandardCharsets.US_ASCII);
  }

  byte[] targetBytes() {
    return targetCompId.getBytes(StandardCharsets.US_ASCII);
  }

  byte[] usernameBytes() {
    return username == null ? null : username.getBytes(StandardCharsets.US_ASCII);
  }
}
