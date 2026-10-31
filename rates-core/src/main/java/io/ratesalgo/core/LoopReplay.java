package io.ratesalgo.core;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.ChainHash;
import io.ratesalgo.journal.FrameHandler;
import io.ratesalgo.journal.FrameSource;
import io.ratesalgo.journal.JournalFormat;
import io.ratesalgo.journal.Replayer;
import io.ratesalgo.journal.SegmentedJournalReader;
import java.nio.file.Path;
import org.agrona.DirectBuffer;

/**
 * Replays a loop's inbound journal through a freshly built loop and compares the outbound messages
 * it produces with the loop's recorded outbound journal (API_SPEC.md Section 9.4).
 *
 * <p>The replay loop is built on the first frame, after the {@link Replayer} has set the {@link
 * ManualClock} to that frame's stamp, so its timer wheel starts at the replayed time. Each produced
 * outbound message is chained with {@link ChainHash} exactly as the journal writer chains it, and
 * compared frame by frame with the recorded journal, so a divergence is reported at the first
 * outbound message that differs.
 */
public final class LoopReplay {

  private LoopReplay() {}

  /** Replays segmented journals {@code inboundName} and {@code outboundName} in {@code dir}. */
  public static ReplayResult run(
      final Path dir, final String inboundName, final String outboundName, final LoopFactory factory) {
    try (SegmentedJournalReader in = SegmentedJournalReader.open(dir, inboundName);
        SegmentedJournalReader out = SegmentedJournalReader.open(dir, outboundName)) {
      return run(in, out, factory);
    }
  }

  /** Replays {@code inbound} through a loop from {@code factory} and checks it against {@code recordedOutbound}. */
  public static ReplayResult run(
      final FrameSource inbound, final FrameSource recordedOutbound, final LoopFactory factory) {
    final ManualClock source = new ManualClock(0L);
    final EventClock clock = new EventClock(source);
    final OutboundVerifier verifier = new OutboundVerifier(recordedOutbound);
    final EventLoop[] loop = new EventLoop[1];
    final long frames =
        new Replayer(source)
            .replay(
                inbound,
                (sequence, timestampNanos, templateId, schemaVersion, buffer, offset, length) -> {
                  if (loop[0] == null) {
                    clock.latch();
                    final EventLoop built =
                        factory.create(new EventLoop.Builder(clock, true).replayOutbound(verifier));
                    if (!built.isReplay()) {
                      throw new IllegalStateException(
                          "LoopFactory must build from the builder it is given");
                    }
                    loop[0] = built;
                  }
                  loop[0].onReplayFrame(templateId, buffer, offset, length);
                });
    return verifier.finish(frames, loop[0] != null && loop[0].isStopped());
  }

  /** Chains produced outbound messages and compares each with the next recorded frame. */
  static final class OutboundVerifier implements JournalSink {

    private final FrameSource recorded;
    private final FrameHandler capture = this::capture;
    private long hash = JournalFormat.GENESIS_HASH;
    private long produced;
    private long firstMismatch;
    private int recordedTemplate;

    OutboundVerifier(final FrameSource recorded) {
      this.recorded = recorded;
    }

    private void capture(
        final long sequence,
        final long timestampNanos,
        final int templateId,
        final int schemaVersion,
        final DirectBuffer buffer,
        final int offset,
        final int length) {
      recordedTemplate = templateId;
    }

    @Override
    public long append(
        final int templateId,
        final int schemaVersion,
        final DirectBuffer buffer,
        final int offset,
        final int length) {
      hash = ChainHash.next(hash, buffer, offset, length);
      produced++;
      if (firstMismatch == 0) {
        final boolean present = recorded.next(capture);
        if (!present || recorded.chainHash() != hash || recordedTemplate != templateId) {
          firstMismatch = produced;
        }
      }
      return produced;
    }

    ReplayResult finish(final long inboundFrames, final boolean stoppedOnError) {
      while (recorded.next(capture)) {
        // counting the recorded frames replay did not reach
      }
      final long recordedCount = recorded.expectedSequence() - 1;
      if (firstMismatch == 0 && recordedCount > produced) {
        firstMismatch = produced + 1;
      }
      return new ReplayResult(
          inboundFrames,
          produced,
          recordedCount,
          hash,
          recorded.chainHash(),
          firstMismatch,
          stoppedOnError);
    }
  }
}
