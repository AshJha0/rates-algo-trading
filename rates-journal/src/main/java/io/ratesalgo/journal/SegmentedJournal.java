package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.FILE_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.GENESIS_HASH;
import static io.ratesalgo.journal.JournalFormat.alignedFrameLength;

import io.ratesalgo.common.time.Clock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.agrona.DirectBuffer;

/**
 * A journal made of equal-size segment files that rolls to the next segment when the current one
 * cannot hold the next frame (API_SPEC.md Section 1.5).
 *
 * <p>Segment {@code k} of journal {@code name} in directory {@code dir} is the file {@code
 * name.00000k.raj}, numbered from 1 with six digits. Each segment is an ordinary RAJ1 file. The
 * sequence and the chain hash continue across segments: the first frame of segment {@code k + 1}
 * carries the sequence after the last frame of segment {@code k} and chains to its hash. So a
 * segment read alone fails the chain check unless the reader is told where the previous segment
 * ended, which {@link SegmentedJournalReader} does by walking the segments in order.
 *
 * <p>{@link #append} does not allocate except when it rolls, which maps a new file. Size segments
 * so that rolls are rare on the hot path (DECISIONS.md DR-P2C-03).
 */
public final class SegmentedJournal implements AutoCloseable {

  /** File suffix of every segment. */
  public static final String SUFFIX = ".raj";

  /** Largest segment index; six decimal digits. */
  public static final int MAX_SEGMENTS = 999_999;

  private final Path dir;
  private final String name;
  private final int segmentCapacity;
  private final Clock clock;

  private JournalWriter current;
  private int segmentIndex;
  private boolean closed;

  private SegmentedJournal(
      final Path dir,
      final String name,
      final int segmentCapacity,
      final Clock clock,
      final JournalWriter current,
      final int segmentIndex) {
    this.dir = dir;
    this.name = name;
    this.segmentCapacity = segmentCapacity;
    this.clock = clock;
    this.current = current;
    this.segmentIndex = segmentIndex;
  }

  /** The path of segment {@code index} (1-based) of journal {@code name} in {@code dir}. */
  public static Path segmentPath(final Path dir, final String name, final int index) {
    checkName(name);
    if (index < 1 || index > MAX_SEGMENTS) {
      throw new IllegalArgumentException("segment index out of range: " + index);
    }
    return dir.resolve(String.format(Locale.ROOT, "%s.%06d%s", name, index, SUFFIX));
  }

  /**
   * The segment indices of journal {@code name} present in {@code dir}, ascending. A gap in the
   * numbering is not checked here; {@link SegmentedJournalReader} reports it as corruption.
   */
  public static int[] segmentIndices(final Path dir, final String name) {
    checkName(name);
    final Pattern pattern = Pattern.compile(Pattern.quote(name) + "\\.(\\d{6})" + Pattern.quote(SUFFIX));
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .map(p -> pattern.matcher(String.valueOf(p.getFileName())))
          .filter(Matcher::matches)
          .mapToInt(m -> Integer.parseInt(m.group(1)))
          .filter(i -> i >= 1)
          .sorted()
          .toArray();
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot list journal directory " + dir, e);
    }
  }

  /**
   * Creates a new segmented journal with its first segment.
   *
   * @throws IllegalArgumentException if any segment of {@code name} already exists in {@code dir}
   */
  public static SegmentedJournal create(
      final Path dir, final String name, final int segmentCapacity, final Clock clock) {
    if (segmentIndices(dir, name).length != 0) {
      throw new IllegalArgumentException("journal " + name + " already exists in " + dir);
    }
    final JournalWriter first =
        JournalWriter.create(segmentPath(dir, name, 1), segmentCapacity, clock, 1L, GENESIS_HASH);
    return new SegmentedJournal(dir, name, segmentCapacity, clock, first, 1);
  }

  /**
   * Opens an existing segmented journal for appending after its last frame, verifying every
   * segment's chain on the way. New segments get {@code segmentCapacity}.
   *
   * @throws IllegalArgumentException if no segment exists
   * @throws JournalCorruptionException if a segment is missing or a chain breaks
   */
  public static SegmentedJournal open(
      final Path dir, final String name, final int segmentCapacity, final Clock clock) {
    final int[] indices = segmentIndices(dir, name);
    if (indices.length == 0) {
      throw new IllegalArgumentException("journal " + name + " not found in " + dir);
    }
    final int last = indices[indices.length - 1];
    final long nextSequence;
    final long hash;
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name, last - 1)) {
      while (reader.next((s, t, id, v, b, o, l) -> {})) {
        // verifying every earlier segment
      }
      nextSequence = reader.expectedSequence();
      hash = reader.chainHash();
    }
    final JournalWriter writer =
        JournalWriter.open(segmentPath(dir, name, last), clock, nextSequence, hash);
    return new SegmentedJournal(dir, name, segmentCapacity, clock, writer, last);
  }

  /**
   * Appends one frame, rolling to a new segment first if the current one cannot hold it.
   *
   * @return the sequence number assigned to the frame
   * @throws JournalFullException if the frame is larger than an empty segment can hold
   */
  public long append(
      final int templateId,
      final int schemaVersion,
      final DirectBuffer payload,
      final int offset,
      final int length) {
    ensureOpen();
    final int frameLength = alignedFrameLength(Math.max(length, 0));
    if (frameLength > current.remaining()) {
      final int emptyRoom = segmentCapacity - FILE_HEADER_LENGTH - Integer.BYTES;
      if (frameLength > emptyRoom) {
        throw new JournalFullException(frameLength, emptyRoom);
      }
      roll();
    }
    return current.append(templateId, schemaVersion, payload, offset, length);
  }

  private void roll() {
    if (segmentIndex == MAX_SEGMENTS) {
      throw new IllegalStateException("journal " + name + " has used every segment index");
    }
    final long nextSequence = current.nextSequence();
    final long hash = current.chainHash();
    current.close();
    segmentIndex++;
    current =
        JournalWriter.create(
            segmentPath(dir, name, segmentIndex), segmentCapacity, clock, nextSequence, hash);
  }

  /** The sequence the next appended frame will get. */
  public long nextSequence() {
    return current.nextSequence();
  }

  /** The chain hash after the last appended frame, across all segments. */
  public long chainHash() {
    return current.chainHash();
  }

  /** The 1-based index of the segment being written. */
  public int segmentIndex() {
    return segmentIndex;
  }

  /** Capacity of each new segment in bytes. */
  public int segmentCapacity() {
    return segmentCapacity;
  }

  /** Forces the current segment to the file. Not called on the hot path. */
  public void sync() {
    ensureOpen();
    current.sync();
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      current.close();
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("segmented journal is closed");
    }
  }

  private static void checkName(final String name) {
    if (name == null
        || name.isEmpty()
        || name.chars().anyMatch(c -> c == '/' || c == '\\' || c == ':')
        || ".".equals(name)
        || "..".equals(name)) {
      throw new IllegalArgumentException("invalid journal name: " + name);
    }
  }
}
