package io.ratesalgo.core;

/**
 * The outcome of replaying an inbound journal through a loop and comparing what it produced with
 * the recorded outbound journal.
 *
 * @param inboundFrames inbound frames replayed (messages, clock ticks, backpressure, errors)
 * @param outboundProduced outbound messages the replayed loop produced
 * @param outboundRecorded outbound frames in the recorded journal
 * @param replayHash chain hash over the produced messages, computed as the journal computes it
 * @param recordedHash final chain hash of the recorded outbound journal
 * @param firstMismatch 1-based outbound sequence of the first produced message whose chain hash
 *     or template id differs from the recorded frame, or {@code outboundProduced + 1} when replay
 *     produced fewer messages than were recorded; 0 if none
 * @param stoppedOnError true if the inbound journal ended with a LoopError
 */
public record ReplayResult(
    long inboundFrames,
    long outboundProduced,
    long outboundRecorded,
    long replayHash,
    long recordedHash,
    long firstMismatch,
    boolean stoppedOnError) {

  /** True if replay produced exactly the recorded outbound stream. */
  public boolean matches() {
    return firstMismatch == 0 && outboundProduced == outboundRecorded && replayHash == recordedHash;
  }
}
