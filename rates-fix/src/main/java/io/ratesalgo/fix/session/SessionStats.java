package io.ratesalgo.fix.session;

/** Counters kept by a {@link FixSession}; read on the session's thread or after it stops. */
public final class SessionStats {

  long received;
  long sent;
  long gapsDetected;
  long resendRequestsSent;
  long gapFillsSent;
  long duplicatesIgnored;
  long sequenceTooLow;
  long garbled;
  long rejectsReceived;
  long rejectsSent;
  long testRequestsSent;
  long heartbeatsSent;
  long timeouts;
  long sequenceResets;

  /** Messages accepted from the transport (well formed). */
  public long received() {
    return received;
  }

  /** Messages sent. */
  public long sent() {
    return sent;
  }

  /** Inbound sequence gaps detected (each triggered a ResendRequest). */
  public long gapsDetected() {
    return gapsDetected;
  }

  /** ResendRequests we sent. */
  public long resendRequestsSent() {
    return resendRequestsSent;
  }

  /** SequenceReset-GapFill messages we sent in answer to ResendRequests. */
  public long gapFillsSent() {
    return gapFillsSent;
  }

  /** PossDup messages below the expected sequence that were ignored. */
  public long duplicatesIgnored() {
    return duplicatesIgnored;
  }

  /** Messages below the expected sequence without PossDup (a fatal error). */
  public long sequenceTooLow() {
    return sequenceTooLow;
  }

  /** Messages that failed BodyLength, CheckSum or field syntax and were dropped. */
  public long garbled() {
    return garbled;
  }

  /** Session-level rejects (35=3) received. */
  public long rejectsReceived() {
    return rejectsReceived;
  }

  /** Session-level rejects we sent. */
  public long rejectsSent() {
    return rejectsSent;
  }

  /** TestRequests we sent after a silent interval. */
  public long testRequestsSent() {
    return testRequestsSent;
  }

  /** Heartbeats we sent (timer and test-request answers). */
  public long heartbeatsSent() {
    return heartbeatsSent;
  }

  /** Sessions ended because the counterparty went silent. */
  public long timeouts() {
    return timeouts;
  }

  /** SequenceReset messages (reset mode) applied. */
  public long sequenceResets() {
    return sequenceResets;
  }

  /** Gaps plus too-low sequence numbers: the figure the synthetic day requires to be zero. */
  public long sequenceErrors() {
    return gapsDetected + sequenceTooLow;
  }
}
