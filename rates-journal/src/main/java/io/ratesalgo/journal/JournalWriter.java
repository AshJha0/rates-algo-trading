package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.CAPACITY_OFFSET;
import static io.ratesalgo.journal.JournalFormat.CHAIN_HASH_OFFSET;
import static io.ratesalgo.journal.JournalFormat.CREATED_OFFSET;
import static io.ratesalgo.journal.JournalFormat.FILE_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.FORMAT_VERSION;
import static io.ratesalgo.journal.JournalFormat.FORMAT_VERSION_OFFSET;
import static io.ratesalgo.journal.JournalFormat.FRAME_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.FRAME_LENGTH_OFFSET;
import static io.ratesalgo.journal.JournalFormat.GENESIS_HASH;
import static io.ratesalgo.journal.JournalFormat.HEADER_LENGTH_OFFSET;
import static io.ratesalgo.journal.JournalFormat.MAGIC;
import static io.ratesalgo.journal.JournalFormat.MAGIC_OFFSET;
import static io.ratesalgo.journal.JournalFormat.SCHEMA_VERSION_OFFSET;
import static io.ratesalgo.journal.JournalFormat.SEQUENCE_OFFSET;
import static io.ratesalgo.journal.JournalFormat.TEMPLATE_ID_OFFSET;
import static io.ratesalgo.journal.JournalFormat.TIMESTAMP_OFFSET;
import static io.ratesalgo.journal.JournalFormat.alignedFrameLength;

import io.ratesalgo.common.time.Clock;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Appends frames to a memory-mapped journal file. One writer per file, on one thread.
 *
 * <p>{@link #append} copies the payload, writes the frame header with the length field last, and
 * never allocates. The length is written last so that a reader that races the writer (P2) or a
 * crash mid-frame leaves a zero length, which reads as end of journal rather than as a corrupt
 * frame.
 *
 * <p>Opening an existing file scans it to the end and continues the sequence and the hash chain,
 * verifying the chain on the way.
 */
public final class JournalWriter implements AutoCloseable {

  /** The page size {@link #pretouch()} steps by; 4 KiB on x86-64 Windows and Linux. */
  static final int PAGE_SIZE = 4096;

  /** Pages {@link #pretouch()} touches between two safepoint checks. */
  static final int PRETOUCH_PAGES_PER_CHECK = 16;

  /** Bytes written per channel write when a new file is zero-filled. */
  private static final int ZERO_FILL_CHUNK = 1 << 20;

  private final MappedByteBuffer mapped;
  private final UnsafeBuffer buffer;
  private final Clock clock;
  private final int capacity;

  private int position;
  private long nextSequence;
  private long chainHash;
  private boolean closed;

  private JournalWriter(
      final MappedByteBuffer mapped,
      final Clock clock,
      final int position,
      final long nextSequence,
      final long chainHash) {
    this.mapped = mapped;
    this.buffer = new UnsafeBuffer(mapped);
    this.clock = clock;
    this.capacity = mapped.capacity();
    this.position = position;
    this.nextSequence = nextSequence;
    this.chainHash = chainHash;
  }

  /**
   * Creates a new journal file of {@code capacityBytes} and opens it for appending.
   *
   * @throws IllegalArgumentException if the file exists or the capacity cannot hold a header
   */
  public static JournalWriter create(final Path path, final int capacityBytes, final Clock clock) {
    return create(path, capacityBytes, clock, 1L, GENESIS_HASH);
  }

  /** Creates a segment whose first frame continues the chain at {@code firstSequence}. */
  static JournalWriter create(
      final Path path,
      final int capacityBytes,
      final Clock clock,
      final long firstSequence,
      final long previousHash) {
    return create(path, capacityBytes, clock, firstSequence, previousHash, chunks -> true);
  }

  /**
   * {@link #create(Path, int, Clock)} that calls {@code keepGoing} after each zero-fill chunk of
   * {@link #ZERO_FILL_CHUNK} bytes with the chunks written so far. When it returns false the file is
   * removed and null is returned: the segment preparer uses it to report progress and to abandon a
   * segment nobody will use (DR-INT-10).
   */
  static JournalWriter create(
      final Path path,
      final int capacityBytes,
      final Clock clock,
      final java.util.function.IntPredicate keepGoing) {
    return create(path, capacityBytes, clock, 1L, GENESIS_HASH, keepGoing);
  }

  private static JournalWriter create(
      final Path path,
      final int capacityBytes,
      final Clock clock,
      final long firstSequence,
      final long previousHash,
      final java.util.function.IntPredicate keepGoing) {
    if (capacityBytes < FILE_HEADER_LENGTH + FRAME_HEADER_LENGTH) {
      throw new IllegalArgumentException("capacity too small: " + capacityBytes);
    }
    final File file = path.toFile();
    if (file.exists()) {
      throw new IllegalArgumentException("journal already exists: " + path);
    }
    final MappedByteBuffer mapped = mapNewZeroFilled(path, capacityBytes, keepGoing);
    if (mapped == null) {
      return null;
    }
    mapped.order(JournalFormat.BYTE_ORDER);
    final UnsafeBuffer header = new UnsafeBuffer(mapped);
    header.putInt(MAGIC_OFFSET, MAGIC);
    header.putShort(FORMAT_VERSION_OFFSET, (short) FORMAT_VERSION);
    header.putShort(HEADER_LENGTH_OFFSET, (short) FILE_HEADER_LENGTH);
    header.putInt(CAPACITY_OFFSET, capacityBytes);
    header.putLong(CREATED_OFFSET, clock.epochNanos());
    return new JournalWriter(mapped, clock, FILE_HEADER_LENGTH, firstSequence, previousHash);
  }

  /**
   * Creates a file of {@code capacityBytes} zero bytes and maps it. The zeros are written with
   * {@link FileChannel#write}, not stored through the mapping: a store through a fresh mapping
   * takes a page fault per page in Java code, and a long run of those in one compiled loop holds
   * every other thread of the process at the next safepoint until it ends (DECISIONS.md DR-R2J-01).
   * A channel write runs in native code, where the thread does not delay a safepoint.
   */
  private static MappedByteBuffer mapNewZeroFilled(
      final Path path, final int capacityBytes, final java.util.function.IntPredicate keepGoing) {
    boolean abandoned = false;
    try (FileChannel channel =
        FileChannel.open(
            path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      final ByteBuffer zeros = ByteBuffer.allocateDirect(Math.min(ZERO_FILL_CHUNK, capacityBytes));
      long written = 0;
      int chunks = 0;
      while (written < capacityBytes) {
        zeros.clear().limit((int) Math.min(zeros.capacity(), capacityBytes - written));
        while (zeros.hasRemaining()) {
          written += channel.write(zeros, written);
        }
        if (!keepGoing.test(++chunks)) {
          abandoned = true;
          break;
        }
      }
      if (!abandoned) {
        return channel.map(FileChannel.MapMode.READ_WRITE, 0, capacityBytes);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot create journal " + path, e);
    }
    try {
      java.nio.file.Files.deleteIfExists(path);
    } catch (final IOException e) {
      // a removed-later .pending file is ignored by readers
    }
    return null;
  }

  /** Opens an existing journal and positions the writer after its last frame. */
  public static JournalWriter open(final Path path, final Clock clock) {
    return open(path, clock, 1L, GENESIS_HASH);
  }

  /** Opens an existing segment whose chain starts at {@code firstSequence}. */
  static JournalWriter open(
      final Path path, final Clock clock, final long firstSequence, final long previousHash) {
    final MappedByteBuffer mapped = IoUtil.mapExistingFile(path.toFile(), "journal");
    mapped.order(JournalFormat.BYTE_ORDER);
    final UnsafeBuffer buffer = new UnsafeBuffer(mapped);
    final JournalReader reader;
    try {
      reader = new JournalReader(buffer, null, firstSequence, previousHash);
    } catch (final JournalCorruptionException e) {
      IoUtil.unmap(mapped);
      throw e;
    }
    final long[] tail = reader.scanToEnd();
    return new JournalWriter(mapped, clock, (int) tail[0], tail[1] + 1, tail[2]);
  }

  /**
   * Appends one frame.
   *
   * @return the sequence number assigned to the frame
   * @throws JournalFullException if the file has no room; nothing is written in that case
   */
  public long append(
      final int templateId,
      final int schemaVersion,
      final DirectBuffer payload,
      final int offset,
      final int length) {
    return append(templateId, schemaVersion, clock.epochNanos(), payload, offset, length);
  }

  /**
   * Appends one frame stamped with {@code timestampNanos} instead of a reading of the writer's
   * clock. A writer that drains frames produced on another thread uses this to keep the stamp the
   * producer saw (API_SPEC.md Section 9.7).
   *
   * @return the sequence number assigned to the frame
   * @throws JournalFullException if the file has no room; nothing is written in that case
   */
  public long append(
      final int templateId,
      final int schemaVersion,
      final long timestampNanos,
      final DirectBuffer payload,
      final int offset,
      final int length) {
    ensureOpen();
    if (length < 0) {
      throw new IllegalArgumentException("negative length: " + length);
    }
    final int frameLength = alignedFrameLength(length);
    // keep room for the terminating zero length after this frame
    final int available = capacity - position - Integer.BYTES;
    if (frameLength > available) {
      throw new JournalFullException(frameLength, Math.max(available, 0));
    }

    final int frame = position;
    final long sequence = nextSequence;
    final long hash = ChainHash.next(chainHash, payload, offset, length);

    buffer.putBytes(frame + FRAME_HEADER_LENGTH, payload, offset, length);
    final int padding = frameLength - FRAME_HEADER_LENGTH - length;
    if (padding > 0) {
      buffer.setMemory(frame + FRAME_HEADER_LENGTH + length, padding, (byte) 0);
    }
    buffer.putLong(frame + SEQUENCE_OFFSET, sequence);
    buffer.putLong(frame + TIMESTAMP_OFFSET, timestampNanos);
    buffer.putShort(frame + TEMPLATE_ID_OFFSET, (short) templateId);
    buffer.putShort(frame + SCHEMA_VERSION_OFFSET, (short) schemaVersion);
    buffer.putLong(frame + CHAIN_HASH_OFFSET, hash);
    buffer.putIntOrdered(frame + FRAME_LENGTH_OFFSET, FRAME_HEADER_LENGTH + length);

    position = frame + frameLength;
    nextSequence = sequence + 1;
    chainHash = hash;
    return sequence;
  }

  /**
   * Makes an empty segment continue a chain: its first frame will carry {@code firstSequence} and
   * chain to {@code previousHash}. Used when a segment prepared ahead of time becomes current.
   *
   * @throws IllegalStateException if a frame has been appended
   */
  void continueChain(final long firstSequence, final long previousHash) {
    if (position != FILE_HEADER_LENGTH) {
      throw new IllegalStateException("segment already holds frames");
    }
    nextSequence = firstSequence;
    chainHash = previousHash;
  }

  /**
   * Writes a zero byte into the last byte of every page from the one holding the write position to
   * the end of the file, so that the operating system maps each page now, for writing, rather than
   * on the appending thread's first store to it. Called before the writer is handed to the
   * appending thread. Every byte written lies after the last frame and is zero already, so the file
   * reads the same afterwards.
   *
   * <p>Each store can take a page fault in Java code, during which this thread cannot reach a
   * safepoint. The loop therefore yields every {@link #PRETOUCH_PAGES_PER_CHECK} pages: {@code
   * Thread.yield()} is a native call, and the return from native code is a safepoint check, so a
   * safepoint waits for at most that many faults (DR-R2J-01). A plain counted loop has no
   * safepoint check at all under the Parallel collector.
   *
   * @return the number of pages touched
   */
  // The yield is for its safepoint check on the return from native code, not for scheduling.
  @SuppressWarnings("ThreadPriorityCheck")
  int pretouch() {
    return pretouch(pagesSoFar -> true);
  }

  /**
   * {@link #pretouch()} that calls {@code keepGoing} every {@link #PRETOUCH_PAGES_PER_CHECK} pages
   * with the pages touched so far, and stops early when it returns false. The caller uses it to
   * report progress and to abandon a segment nobody will use. Stopping early leaves the file
   * correct: the untouched pages are zero and fault in on first use (DR-INT-10).
   */
  // The yield is for its safepoint check on the return from native code, not for scheduling.
  @SuppressWarnings("ThreadPriorityCheck")
  int pretouch(final java.util.function.IntPredicate keepGoing) {
    ensureOpen();
    int pages = 0;
    for (long page = position & -PAGE_SIZE; page < capacity; page += PAGE_SIZE) {
      buffer.putByte((int) (Math.min(page + PAGE_SIZE, capacity) - 1), (byte) 0);
      if (++pages % PRETOUCH_PAGES_PER_CHECK == 0) {
        if (!keepGoing.test(pages)) {
          return pages;
        }
        Thread.yield();
      }
    }
    return pages;
  }

  /**
   * Unmaps without forcing, for a segment that is deleted next (an unused prepared segment): its
   * only stores are pretouch zeros, so writing them back to disk is wasted (DR-INT-10).
   */
  void discard() {
    if (!closed) {
      closed = true;
      IoUtil.unmap(mapped);
    }
  }

  /** The sequence the next appended frame will get. */
  public long nextSequence() {
    return nextSequence;
  }

  /** The chain hash after the last appended frame; {@link JournalFormat#GENESIS_HASH} if none. */
  public long chainHash() {
    return chainHash;
  }

  /** Bytes used, file header included. */
  public int position() {
    return position;
  }

  /** Bytes still available for frames, less the terminating zero length. */
  public int remaining() {
    return Math.max(capacity - position - Integer.BYTES, 0);
  }

  /** Forces written frames to the file. Not called on the hot path. */
  public void sync() {
    ensureOpen();
    mapped.force();
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      mapped.force();
      IoUtil.unmap(mapped);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("journal writer is closed");
    }
  }
}
