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
import java.nio.MappedByteBuffer;
import java.nio.file.Path;
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
    if (capacityBytes < FILE_HEADER_LENGTH + FRAME_HEADER_LENGTH) {
      throw new IllegalArgumentException("capacity too small: " + capacityBytes);
    }
    final File file = path.toFile();
    if (file.exists()) {
      throw new IllegalArgumentException("journal already exists: " + path);
    }
    final MappedByteBuffer mapped = IoUtil.mapNewFile(file, capacityBytes, true);
    mapped.order(JournalFormat.BYTE_ORDER);
    final UnsafeBuffer header = new UnsafeBuffer(mapped);
    header.putInt(MAGIC_OFFSET, MAGIC);
    header.putShort(FORMAT_VERSION_OFFSET, (short) FORMAT_VERSION);
    header.putShort(HEADER_LENGTH_OFFSET, (short) FILE_HEADER_LENGTH);
    header.putInt(CAPACITY_OFFSET, capacityBytes);
    header.putLong(CREATED_OFFSET, clock.epochNanos());
    return new JournalWriter(mapped, clock, FILE_HEADER_LENGTH, 1L, GENESIS_HASH);
  }

  /** Opens an existing journal and positions the writer after its last frame. */
  public static JournalWriter open(final Path path, final Clock clock) {
    final MappedByteBuffer mapped = IoUtil.mapExistingFile(path.toFile(), "journal");
    mapped.order(JournalFormat.BYTE_ORDER);
    final UnsafeBuffer buffer = new UnsafeBuffer(mapped);
    final JournalReader reader = new JournalReader(buffer);
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
    buffer.putLong(frame + TIMESTAMP_OFFSET, clock.epochNanos());
    buffer.putShort(frame + TEMPLATE_ID_OFFSET, (short) templateId);
    buffer.putShort(frame + SCHEMA_VERSION_OFFSET, (short) schemaVersion);
    buffer.putLong(frame + CHAIN_HASH_OFFSET, hash);
    buffer.putIntOrdered(frame + FRAME_LENGTH_OFFSET, FRAME_HEADER_LENGTH + length);

    position = frame + frameLength;
    nextSequence = sequence + 1;
    chainHash = hash;
    return sequence;
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
