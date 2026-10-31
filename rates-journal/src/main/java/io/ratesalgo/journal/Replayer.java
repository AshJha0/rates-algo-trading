package io.ratesalgo.journal;

import io.ratesalgo.common.time.ManualClock;
import java.nio.file.Path;
import org.agrona.DirectBuffer;

/**
 * Replays a journal into a handler, driving a {@link ManualClock} so that code under replay sees
 * the same timestamps it saw live.
 *
 * <p>Replay is the platform's test: the same binary that quoted live reads its own journal, and
 * the outbound events it produces are hashed and compared with the recorded ones (P2 wires that
 * comparison; P0 delivers the frames and the clock).
 */
public final class Replayer {

  private final ManualClock clock;

  public Replayer(final ManualClock clock) {
    this.clock = clock;
  }

  /**
   * Replays every frame of the journal at {@code path}.
   *
   * @return the number of frames replayed
   * @throws JournalCorruptionException if the chain breaks; frames before the break were delivered
   */
  public long replay(final Path path, final FrameHandler handler) {
    try (JournalReader reader = JournalReader.open(path)) {
      return replay(reader, handler);
    }
  }

  /** Replays every remaining frame of an open reader. */
  public long replay(final JournalReader reader, final FrameHandler handler) {
    final FrameHandler clocked =
        (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
          if (timestampNanos > clock.epochNanos()) {
            clock.setEpochNanos(timestampNanos);
          }
          handler.onFrame(
              sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length);
        };
    long count = 0;
    while (reader.next(clocked)) {
      count++;
    }
    return count;
  }

  /** Convenience for tests and tools: the final chain hash of a journal, verified end to end. */
  public static long finalHash(final Path path) {
    try (JournalReader reader = JournalReader.open(path)) {
      final FrameHandler skip =
          (long s, long t, int id, int v, DirectBuffer b, int o, int l) -> {};
      while (reader.next(skip)) {
        // verifying
      }
      return reader.chainHash();
    }
  }
}
