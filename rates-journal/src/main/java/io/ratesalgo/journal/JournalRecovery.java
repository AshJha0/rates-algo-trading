package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.CAPACITY_OFFSET;
import static io.ratesalgo.journal.JournalFormat.FILE_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.FRAME_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.FRAME_LENGTH_OFFSET;
import static io.ratesalgo.journal.JournalFormat.GENESIS_HASH;
import static io.ratesalgo.journal.JournalFormat.MAGIC;
import static io.ratesalgo.journal.JournalFormat.MAGIC_OFFSET;
import static io.ratesalgo.journal.JournalFormat.alignedFrameLength;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Checks a segmented journal left by a process that stopped without closing it, and repairs the
 * one kind of damage a crash can cause, so that {@link SegmentedJournal#open} can append to it.
 *
 * <p>The writer publishes a frame's length last, so a process that dies mid-append leaves a zero
 * length and the journal simply ends one frame early. A machine that loses power, or a copy cut
 * short, can leave more: a last frame whose length reached the disk but whose bytes did not, or a
 * last segment shorter than its header says. That is a torn tail. It is the last frame of the
 * journal and nothing follows it, so cutting it off loses only a frame that was never completely
 * written. {@link #repair} does that and says so in its {@link Report}; it never does it silently.
 *
 * <p>Anything else is corruption and is refused with a {@link JournalCorruptionException}: a frame
 * that fails its checks with data after it, in the same segment or in a later one, means frames in
 * the middle of the chain are wrong or missing, and no cut makes the journal whole again.
 *
 * <p>How the two are told apart: a failed frame is a torn tail only if every byte after it, to the
 * end of its segment, is zero and no later segment holds a frame. Segments are zero-filled when
 * they are created, so data after a failed frame can only be frames written after it. A flipped
 * bit in the very last frame is indistinguishable from a tear and is cut off the same way.
 *
 * <p>Not for the hot path: maps and scans every segment. Run it before the journal is opened.
 */
public final class JournalRecovery {

  private JournalRecovery() {}

  /**
   * What {@link #repair} found and did.
   *
   * @param segments segment files present
   * @param frames whole frames in the journal after the repair
   * @param lastTimestampNanos stamp of the last whole frame, or 0 if there is none
   * @param tornSegment 1-based index of the segment whose tail was cut, or 0 if none was
   * @param tornSequence sequence the cut frame would have had, or 0
   * @param tornBytes bytes of the incomplete frame that were cleared
   * @param extendedBytes bytes added to a last segment shorter than its declared size
   * @param pendingRemoved half-prepared segment files ({@code .pending}) removed
   */
  public record Report(
      int segments,
      long frames,
      long lastTimestampNanos,
      int tornSegment,
      long tornSequence,
      long tornBytes,
      long extendedBytes,
      int pendingRemoved) {

    /** True if the journal's tail was cut or its last segment was extended. */
    public boolean repaired() {
      return tornSegment != 0 || extendedBytes != 0;
    }

    /** One line for the start-up log. */
    public String describe(final String name) {
      final StringBuilder b = new StringBuilder(96);
      b.append("journal ").append(name).append(": ").append(frames).append(" frames in ").append(segments)
          .append(" segment(s)");
      if (tornSegment != 0) {
        b.append("; TORN TAIL cut in segment ").append(tornSegment).append(" at sequence ").append(tornSequence)
            .append(" (").append(tornBytes).append(" bytes cleared)");
      }
      if (extendedBytes != 0) {
        b.append("; last segment was ").append(extendedBytes).append(" bytes short and was extended");
      }
      if (pendingRemoved != 0) {
        b.append("; ").append(pendingRemoved).append(" half-prepared segment file(s) removed");
      }
      return b.toString();
    }
  }

  /** True if journal {@code name} has at least one segment in {@code dir}. */
  public static boolean exists(final Path dir, final String name) {
    return Files.isDirectory(dir) && SegmentedJournal.segmentIndices(dir, name).length != 0;
  }

  /**
   * Verifies every segment of journal {@code name} in {@code dir} and cuts off a torn tail.
   *
   * @throws IllegalArgumentException if the journal has no segment
   * @throws JournalCorruptionException if a segment is missing, or a frame fails its checks and is
   *     not the journal's last; no frame is changed in that case
   */
  public static Report repair(final Path dir, final String name) {
    final int[] indices = SegmentedJournal.segmentIndices(dir, name);
    if (indices.length == 0) {
      throw new IllegalArgumentException("journal " + name + " not found in " + dir);
    }
    for (int i = 0; i < indices.length; i++) {
      if (indices[i] != i + 1) {
        throw new JournalCorruptionException(
            0, "segment " + (i + 1) + " of journal " + name + " is missing; refusing to start on a broken chain");
      }
    }
    final int pendingRemoved = removePending(dir, name);
    long sequence = 1L;
    long hash = GENESIS_HASH;
    long lastStamp = 0L;
    long extended = 0L;
    int tornSegment = 0;
    long tornSequence = 0L;
    long tornBytes = 0L;
    final long[] stamp = new long[1];
    final FrameHandler keepStamp = (s, t, id, v, b, o, l) -> stamp[0] = t;
    for (int i = 0; i < indices.length; i++) {
      final int index = indices[i];
      final Path path = SegmentedJournal.segmentPath(dir, name, index);
      final boolean last = i == indices.length - 1;
      if (tornSegment != 0) {
        break; // the later segments were checked before the tail was cut: they hold no frame
      }
      extended += extendIfShort(path, name, index, last);
      final MappedByteBuffer mapped = map(path);
      try {
        final UnsafeBuffer buffer = new UnsafeBuffer(mapped);
        final JournalReader reader = new JournalReader(buffer, null, sequence, hash);
        JournalCorruptionException bad = null;
        try {
          while (reader.next(keepStamp)) {
            lastStamp = stamp[0];
          }
        } catch (final JournalCorruptionException e) {
          bad = e;
        }
        sequence = reader.expectedSequence();
        hash = reader.chainHash();
        if (bad != null) {
          final int at = reader.position();
          final int cleared = tornLength(buffer, at);
          if (cleared < 0) {
            throw new JournalCorruptionException(
                sequence,
                "journal " + name + " segment " + index + ": frame " + sequence
                    + " is corrupt and data follows it (" + bad.getMessage() + "); refusing to start");
          }
          for (int later = i + 1; later < indices.length; later++) {
            // it is the journal's tail only if no later segment holds a frame
            if (holdsFrames(SegmentedJournal.segmentPath(dir, name, indices[later]))) {
              throw new JournalCorruptionException(
                  sequence,
                  "journal " + name + ": frame " + sequence + " in segment " + index + " is corrupt and segment "
                      + indices[later] + " holds frames after it; refusing to start");
            }
          }
          // the length first, so that a crash during the repair still leaves a clean end
          buffer.putIntOrdered(at + FRAME_LENGTH_OFFSET, 0);
          buffer.setMemory(at, cleared, (byte) 0);
          mapped.force();
          tornSegment = index;
          tornSequence = sequence;
          tornBytes = cleared;
        }
      } finally {
        IoUtil.unmap(mapped);
      }
    }
    return new Report(indices.length, sequence - 1, lastStamp, tornSegment, tornSequence, tornBytes, extended,
        pendingRemoved);
  }

  /**
   * Bytes to clear for a failed frame at {@code at} if it is a torn tail, or -1 if data follows
   * it. The frame's own extent is taken from its length when that is plausible, else it is just
   * the header; everything after the extent must be zero.
   */
  private static int tornLength(final UnsafeBuffer buffer, final int at) {
    final int capacity = buffer.capacity();
    final int length = buffer.getInt(at + FRAME_LENGTH_OFFSET);
    int extentEnd = Math.min(capacity, at + FRAME_HEADER_LENGTH);
    if (length >= FRAME_HEADER_LENGTH && length <= capacity - at) {
      extentEnd = Math.min(capacity, at + alignedFrameLength(length - FRAME_HEADER_LENGTH));
    }
    for (int i = extentEnd; i < capacity; i++) {
      if (buffer.getByte(i) != 0) {
        return -1;
      }
    }
    return extentEnd - at;
  }

  /** True if the segment's first frame has a length: it holds at least the start of a frame. */
  private static boolean holdsFrames(final Path path) {
    try (FileChannel channel = FileChannel.open(path)) {
      final ByteBuffer word = ByteBuffer.allocate(Integer.BYTES).order(JournalFormat.BYTE_ORDER);
      final int read = channel.read(word, FILE_HEADER_LENGTH + FRAME_LENGTH_OFFSET);
      return read == Integer.BYTES && word.getInt(0) != 0;
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read journal segment " + path, e);
    }
  }

  /**
   * A last segment shorter than its header declares is a file cut short: it is extended with zeros
   * to its declared size, which turns the cut into an ordinary torn tail. A shorter segment
   * anywhere else, or one too short to hold its header, is corruption.
   */
  private static long extendIfShort(final Path path, final String name, final int index, final boolean last) {
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
      final long size = file.length();
      if (size < FILE_HEADER_LENGTH) {
        throw new JournalCorruptionException(
            0, "journal " + name + " segment " + index + " is shorter than its header; refusing to start");
      }
      final ByteBuffer header = ByteBuffer.allocate(FILE_HEADER_LENGTH).order(JournalFormat.BYTE_ORDER);
      file.getChannel().read(header, 0);
      if (header.getInt(MAGIC_OFFSET) != MAGIC) {
        throw new JournalCorruptionException(
            0, "journal " + name + " segment " + index + " does not start with a journal header; refusing to start");
      }
      final int declared = header.getInt(CAPACITY_OFFSET);
      if (size >= declared) {
        return 0L;
      }
      if (!last) {
        throw new JournalCorruptionException(
            0,
            "journal " + name + " segment " + index + " holds " + size + " of its " + declared
                + " bytes and is not the last segment; refusing to start");
      }
      file.setLength(declared);
      return declared - size;
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot check journal segment " + path, e);
    }
  }

  private static MappedByteBuffer map(final Path path) {
    try (FileChannel channel =
        FileChannel.open(path, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
      final MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
      mapped.order(JournalFormat.BYTE_ORDER);
      return mapped;
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot map journal segment " + path, e);
    }
  }

  /** Removes segment files a preparer was still writing when the process stopped. */
  private static int removePending(final Path dir, final String name) {
    int removed = 0;
    try (DirectoryStream<Path> pending = Files.newDirectoryStream(dir, name + ".*" + SegmentedJournal.SUFFIX + ".pending")) {
      for (final Path p : pending) {
        Files.deleteIfExists(p);
        removed++;
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot clear half-prepared segments of journal " + name + " in " + dir, e);
    }
    return removed;
  }
}
