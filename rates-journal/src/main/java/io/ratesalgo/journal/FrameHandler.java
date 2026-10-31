package io.ratesalgo.journal;

import org.agrona.DirectBuffer;

/** Receives one frame at a time from a {@link JournalReader} or {@link Replayer}. */
@FunctionalInterface
public interface FrameHandler {

  /**
   * Called for each frame in sequence order. The buffer is a view over the journal; copy what must
   * outlive the call.
   *
   * @param sequence the frame's sequence number
   * @param timestampNanos the writer's wall-clock stamp
   * @param templateId SBE template id of the payload
   * @param schemaVersion SBE schema version of the payload
   * @param buffer the journal buffer
   * @param offset the payload's offset in {@code buffer}
   * @param length the payload's length in bytes
   */
  void onFrame(
      long sequence,
      long timestampNanos,
      int templateId,
      int schemaVersion,
      DirectBuffer buffer,
      int offset,
      int length);
}
