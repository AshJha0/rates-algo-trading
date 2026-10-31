package io.ratesalgo.api;

import org.agrona.DirectBuffer;

/**
 * Where P3 writes SBE messages: the gateway's outbound ring buffer (P2) for Quote, QuoteCancel,
 * NewOrder and OrderCancel, and the control plane's ring for RiskLimitBreach and KillSwitch. Each
 * call carries one complete message including its 8-byte SBE header. The callee copies the bytes
 * before returning; the caller reuses its buffer. Must not allocate. {@link RecordingOutboundSink}
 * and {@link CountingOutboundSink} are the stubs.
 */
@FunctionalInterface
public interface OutboundSink {

  /** Sends one encoded message. */
  void send(DirectBuffer buffer, int offset, int length);
}
