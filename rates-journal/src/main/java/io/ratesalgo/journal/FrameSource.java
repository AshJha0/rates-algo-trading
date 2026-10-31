package io.ratesalgo.journal;

/**
 * A verified stream of journal frames: a single file ({@link JournalReader}) or a run of segments
 * ({@link SegmentedJournalReader}). The {@link Replayer} drives either.
 */
public interface FrameSource extends AutoCloseable {

  /**
   * Delivers the next frame to {@code handler}.
   *
   * @return true if a frame was delivered, false at the end of the journal
   * @throws JournalCorruptionException on any format or chain error
   */
  boolean next(FrameHandler handler);

  /** The sequence the next frame must carry. */
  long expectedSequence();

  /** The chain hash after the last frame delivered; the genesis hash before the first. */
  long chainHash();

  @Override
  void close();
}
