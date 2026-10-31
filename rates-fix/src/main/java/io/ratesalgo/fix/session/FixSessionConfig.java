package io.ratesalgo.fix.session;

import java.nio.charset.StandardCharsets;

/**
 * Static session settings.
 *
 * @param senderCompId our CompID
 * @param targetCompId the venue's CompID
 * @param heartbeatIntervalSeconds HeartBtInt we send in the logon
 * @param resetOnLogon send ResetSeqNumFlag=Y and start both sequences at 1
 */
public record FixSessionConfig(
    String senderCompId, String targetCompId, int heartbeatIntervalSeconds, boolean resetOnLogon) {

  /** Validates the settings. */
  public FixSessionConfig {
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
