package io.ratesalgo.core;

import io.ratesalgo.journal.ChainHash;
import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.journal.FrameSource;
import io.ratesalgo.journal.JournalFormat;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.journal.SegmentedJournalReader;
import java.nio.file.Path;
import org.agrona.DirectBuffer;

/**
 * Rebuilds a live loop's state after a restart by replaying its own journals through it, before it
 * reads a ring or writes one (review finding L6).
 *
 * <p>This is {@link LoopReplay} pointed at the loop that will go live instead of at a throwaway
 * one: the same {@link Replayer} sets the clock to each inbound frame's stamp, the same {@link
 * EventLoop#onReplayFrame} dispatches it, and what the handlers publish is chained and compared
 * with the recorded outbound journal frame by frame. There is no second way of restoring state, so
 * a recovered loop is in the state replay proves, or recovery fails:
 *
 * <ul>
 *   <li>A produced message that differs from the recorded frame means this binary does not
 *       reproduce what the stopped process did. Going live on that state would be trading on a
 *       guess; recovery throws.
 *   <li>Recorded frames that replay does not produce mean the outbound journal runs past what the
 *       inbound journal explains (its tail was lost). Recovery throws.
 *   <li>Messages replay produces after the recorded journal ends are the ones the process had not
 *       journaled when it stopped: it died between journaling an input and finishing the work
 *       that input caused. They are appended to the outbound journal, so the journals read as
 *       those of a process that never stopped, and counted. They are not sent: the sessions
 *       dropped with the process and the venues cancelled on disconnect; the caller tells the
 *       loop so with a journaled GatewayStatus before any session is back.
 *   <li>A journal that ends in a LoopError is the record of a loop that died of its own fault.
 *       Recovery throws: its state is whatever the fault left.
 * </ul>
 *
 * <p>Runs on the thread that builds the process, before the loop thread exists. Allocates.
 */
public final class LoopRecovery {

  private LoopRecovery() {}

  /** Sees each frame of a recovery: inbound frames as they are replayed, or produced outbound ones. */
  @FunctionalInterface
  public interface Observer {

    /** Ignores every frame. */
    Observer NONE = (templateId, buffer, offset, length) -> {};

    /** One SBE message (header plus body). */
    void onMessage(int templateId, DirectBuffer buffer, int offset, int length);
  }

  /** Recovery could not put the loop in a state the journals prove. The process must not start. */
  public static final class RecoveryException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    RecoveryException(final String message) {
      super(message);
    }
  }

  /**
   * What a recovery did.
   *
   * @param inboundFrames inbound frames replayed
   * @param outboundVerified produced messages that matched the recorded outbound journal
   * @param outboundCompleted produced messages appended to the outbound journal because the
   *     process stopped before journaling them
   * @param replayHash chain hash over everything produced; the outbound journal's hash now
   * @param lastFrameNanos stamp of the last inbound frame
   */
  public record Result(
      long inboundFrames, long outboundVerified, long outboundCompleted, long replayHash, long lastFrameNanos) {}

  /** Stamp of the first frame of journal {@code name} in {@code dir}, or -1 if it holds none. */
  public static long firstFrameNanos(final Path dir, final String name) {
    final long[] stamp = {-1L};
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name)) {
      reader.next((s, t, id, v, b, o, l) -> stamp[0] = t);
    }
    return stamp[0];
  }

  /** Replays segmented journals {@code inboundName} and {@code outboundName} in {@code dir} through {@code loop}. */
  public static Result run(
      final EventLoop loop,
      final RecoveryClock clock,
      final Path dir,
      final String inboundName,
      final String outboundName,
      final JournalSink liveOutbound,
      final Observer inboundObserver,
      final Observer outboundObserver) {
    try (SegmentedJournalReader in = SegmentedJournalReader.open(dir, inboundName);
        SegmentedJournalReader out = SegmentedJournalReader.open(dir, outboundName)) {
      return run(loop, clock, in, out, liveOutbound, inboundObserver, outboundObserver);
    }
  }

  /**
   * Replays {@code inbound} through {@code loop}, a live loop built on {@code clock} that has not
   * run yet, and checks what it publishes against {@code recordedOutbound}.
   *
   * @param liveOutbound the loop's outbound journal, open for appending after its last frame;
   *     takes the messages the stopped process had not journaled
   * @param inboundObserver shown every replayed inbound frame before the loop handles it
   * @param outboundObserver shown every message the loop produces
   * @throws RecoveryException if the journals do not prove the rebuilt state
   */
  public static Result run(
      final EventLoop loop,
      final RecoveryClock clock,
      final FrameSource inbound,
      final FrameSource recordedOutbound,
      final JournalSink liveOutbound,
      final Observer inboundObserver,
      final Observer outboundObserver) {
    if (clock.isLive()) {
      throw new IllegalStateException("recover before the clock goes live");
    }
    final Verifier verifier = new Verifier(recordedOutbound, liveOutbound, outboundObserver);
    final long[] last = new long[1];
    loop.beginRecovery(verifier);
    final long frames;
    try {
      frames =
          new Replayer(clock.replayClock())
              .replay(
                  inbound,
                  (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
                    if (loop.isStopped()) {
                      throw new RecoveryException(
                          "the inbound journal holds frames after a LoopError (frame " + sequence + ")");
                    }
                    last[0] = timestampNanos;
                    inboundObserver.onMessage(templateId, buffer, offset, length);
                    loop.onReplayFrame(templateId, buffer, offset, length);
                  });
    } finally {
      loop.endRecovery();
    }
    if (loop.isStopped()) {
      throw new RecoveryException(
          "the inbound journal ends with a LoopError: the loop died of a fault and its state is not one to resume."
              + " Reconcile at the venues, move the journals aside and start on an empty journal directory");
    }
    verifier.finish();
    return new Result(frames, verifier.verified, verifier.completed, verifier.hash, last[0]);
  }

  /** Chains produced messages; checks them against the recorded journal, then completes it. */
  private static final class Verifier implements JournalSink {

    private final FrameSource recorded;
    private final JournalSink liveOutbound;
    private final Observer observer;
    private final FrameHandler capture = (s, t, id, v, b, o, l) -> recordedTemplate = id;
    private long hash = JournalFormat.GENESIS_HASH;
    private long verified;
    private long completed;
    private int recordedTemplate;
    private boolean recordedEnded;

    Verifier(final FrameSource recorded, final JournalSink liveOutbound, final Observer observer) {
      this.recorded = recorded;
      this.liveOutbound = liveOutbound;
      this.observer = observer;
    }

    @Override
    public long append(
        final int templateId, final int schemaVersion, final DirectBuffer buffer, final int offset, final int length) {
      hash = ChainHash.next(hash, buffer, offset, length);
      observer.onMessage(templateId, buffer, offset, length);
      if (!recordedEnded && !recorded.next(capture)) {
        recordedEnded = true;
      }
      if (recordedEnded) {
        // the process stopped before it journaled this one: complete its journal
        completed++;
        return liveOutbound.append(templateId, schemaVersion, buffer, offset, length);
      }
      verified++;
      if (recorded.chainHash() != hash || recordedTemplate != templateId) {
        throw new RecoveryException(
            "replay diverged from the recorded outbound journal at outbound frame " + verified + " (template "
                + templateId + ", recorded template " + recordedTemplate + "): this build does not reproduce what the"
                + " stopped process did; refusing to go live on a state the journals do not prove");
      }
      return verified;
    }

    void finish() {
      if (!recordedEnded && recorded.next(capture)) {
        throw new RecoveryException(
            "the outbound journal holds frames from sequence " + (verified + 1) + " that replaying the inbound"
                + " journal does not produce: the end of the inbound journal was lost. Reconcile at the venues, move"
                + " the journals aside and start on an empty journal directory");
      }
    }
  }
}
