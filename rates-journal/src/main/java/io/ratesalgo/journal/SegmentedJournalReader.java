package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.GENESIS_HASH;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the segments of a {@link SegmentedJournal} in index order as one frame stream, carrying
 * the sequence and chain hash from each segment into the next.
 *
 * <p>The set of segments is fixed when the reader opens: the highest index present then is the
 * last one read. A missing segment below that index is reported as {@link
 * JournalCorruptionException} with the sequence the missing segment should have started with,
 * after every frame before the gap was delivered. A segment whose first frame does not continue
 * the previous segment's sequence and hash fails the same way.
 */
public final class SegmentedJournalReader implements FrameSource {

  private final Path dir;
  private final String name;
  private final int lastIndex;

  private JournalReader current;
  private int nextIndex = 1;
  private int segmentsRead;
  private long expectedSequence = 1L;
  private long chainHash = GENESIS_HASH;

  private SegmentedJournalReader(final Path dir, final String name, final int lastIndex) {
    this.dir = dir;
    this.name = name;
    this.lastIndex = lastIndex;
  }

  /** Opens every segment of journal {@code name} present in {@code dir}. */
  public static SegmentedJournalReader open(final Path dir, final String name) {
    final int[] indices = SegmentedJournal.segmentIndices(dir, name);
    return new SegmentedJournalReader(dir, name, indices.length == 0 ? 0 : indices[indices.length - 1]);
  }

  /** Opens segments 1 to {@code lastIndex} only. */
  static SegmentedJournalReader open(final Path dir, final String name, final int lastIndex) {
    return new SegmentedJournalReader(dir, name, lastIndex);
  }

  @Override
  public boolean next(final FrameHandler handler) {
    while (true) {
      if (current == null) {
        if (nextIndex > lastIndex) {
          return false;
        }
        final Path path = SegmentedJournal.segmentPath(dir, name, nextIndex);
        if (!Files.isRegularFile(path)) {
          throw new JournalCorruptionException(
              expectedSequence, "segment " + nextIndex + " of journal " + name + " is missing");
        }
        current = JournalReader.open(path, expectedSequence, chainHash);
      }
      if (current.next(handler)) {
        expectedSequence = current.expectedSequence();
        chainHash = current.chainHash();
        return true;
      }
      current.close();
      current = null;
      segmentsRead++;
      nextIndex++;
    }
  }

  @Override
  public long expectedSequence() {
    return expectedSequence;
  }

  @Override
  public long chainHash() {
    return chainHash;
  }

  /** Segments read to their end so far. */
  public int segmentsRead() {
    return segmentsRead;
  }

  /** The highest segment index this reader will read. */
  public int lastIndex() {
    return lastIndex;
  }

  @Override
  public void close() {
    if (current != null) {
      current.close();
      current = null;
    }
  }
}
