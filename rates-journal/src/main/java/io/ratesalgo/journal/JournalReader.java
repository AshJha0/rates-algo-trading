package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.CAPACITY_OFFSET;
import static io.ratesalgo.journal.JournalFormat.CHAIN_HASH_OFFSET;
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

import java.nio.MappedByteBuffer;
import java.nio.file.Path;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Reads frames from a journal buffer in order, verifying the hash chain as it goes.
 *
 * <p>The reader is a cursor: {@link #next(FrameHandler)} delivers one frame and returns false at
 * the end. It never allocates after construction. Every departure from the format throws {@link
 * JournalCorruptionException} naming the sequence it expected.
 */
public final class JournalReader implements FrameSource {

  private final DirectBuffer buffer;
  private final MappedByteBuffer mapped;
  private final int capacity;

  private int position;
  private long expectedSequence;
  private long chainHash;

  /** Reads from a buffer that already holds a journal (a mapped file or an in-memory copy). */
  public JournalReader(final DirectBuffer buffer) {
    this(buffer, null, 1L, GENESIS_HASH);
  }

  /**
   * Reads a buffer whose first frame continues an earlier chain: a later segment of a {@link
   * SegmentedJournal} starts at the sequence and hash where the previous segment ended.
   */
  JournalReader(
      final DirectBuffer buffer,
      final MappedByteBuffer mapped,
      final long firstSequence,
      final long previousHash) {
    this.buffer = buffer;
    this.mapped = mapped;
    this.capacity = buffer.capacity();
    verifyFileHeader();
    this.position = FILE_HEADER_LENGTH;
    this.expectedSequence = firstSequence;
    this.chainHash = previousHash;
  }

  /** Maps an existing journal file read-only and positions the cursor at the first frame. */
  public static JournalReader open(final Path path) {
    return open(path, 1L, GENESIS_HASH);
  }

  /** Maps a journal segment whose chain continues from {@code previousHash}. */
  static JournalReader open(final Path path, final long firstSequence, final long previousHash) {
    final MappedByteBuffer mapped = IoUtil.mapExistingFile(path.toFile(), "journal");
    mapped.order(JournalFormat.BYTE_ORDER);
    try {
      return new JournalReader(new UnsafeBuffer(mapped), mapped, firstSequence, previousHash);
    } catch (final JournalCorruptionException e) {
      IoUtil.unmap(mapped);
      throw e;
    }
  }

  private void verifyFileHeader() {
    if (capacity < FILE_HEADER_LENGTH) {
      throw new JournalCorruptionException(0, "file shorter than its header");
    }
    final int magic = buffer.getInt(MAGIC_OFFSET);
    if (magic != MAGIC) {
      throw new JournalCorruptionException(0, "bad magic 0x" + Integer.toHexString(magic));
    }
    final int version = buffer.getShort(FORMAT_VERSION_OFFSET) & 0xFFFF;
    if (version != FORMAT_VERSION) {
      throw new JournalCorruptionException(0, "unsupported format version " + version);
    }
    final int headerLength = buffer.getShort(HEADER_LENGTH_OFFSET) & 0xFFFF;
    if (headerLength != FILE_HEADER_LENGTH) {
      throw new JournalCorruptionException(0, "unexpected header length " + headerLength);
    }
    final int declared = buffer.getInt(CAPACITY_OFFSET);
    if (declared != capacity) {
      throw new JournalCorruptionException(
          0, "declared capacity " + declared + " but buffer holds " + capacity);
    }
  }

  /**
   * Delivers the next frame to {@code handler}.
   *
   * @return true if a frame was delivered, false at the end of the journal
   * @throws JournalCorruptionException on any format or chain error
   */
  @Override
  public boolean next(final FrameHandler handler) {
    if (position + Integer.BYTES > capacity) {
      return false;
    }
    final int frameLength = buffer.getInt(position + FRAME_LENGTH_OFFSET);
    if (frameLength == 0) {
      return false;
    }
    final long sequence = expectedSequence;
    if (frameLength < FRAME_HEADER_LENGTH) {
      throw new JournalCorruptionException(sequence, "frame length " + frameLength + " too small");
    }
    final int payloadLength = frameLength - FRAME_HEADER_LENGTH;
    if (position + alignedFrameLength(payloadLength) > capacity) {
      throw new JournalCorruptionException(sequence, "frame runs past the end of the file");
    }
    final long storedSequence = buffer.getLong(position + SEQUENCE_OFFSET);
    if (storedSequence != sequence) {
      throw new JournalCorruptionException(
          sequence, "sequence " + storedSequence + " where " + sequence + " was expected");
    }
    final int payloadOffset = position + FRAME_HEADER_LENGTH;
    final long computed = ChainHash.next(chainHash, buffer, payloadOffset, payloadLength);
    final long stored = buffer.getLong(position + CHAIN_HASH_OFFSET);
    if (computed != stored) {
      throw new JournalCorruptionException(
          sequence,
          "chain hash "
              + Long.toHexString(stored)
              + " stored, "
              + Long.toHexString(computed)
              + " computed");
    }
    final long timestamp = buffer.getLong(position + TIMESTAMP_OFFSET);
    final int templateId = buffer.getShort(position + TEMPLATE_ID_OFFSET) & 0xFFFF;
    final int schemaVersion = buffer.getShort(position + SCHEMA_VERSION_OFFSET) & 0xFFFF;

    position += alignedFrameLength(payloadLength);
    expectedSequence = sequence + 1;
    chainHash = computed;
    handler.onFrame(
        sequence, timestamp, templateId, schemaVersion, buffer, payloadOffset, payloadLength);
    return true;
  }

  /** The sequence the next frame must carry. */
  @Override
  public long expectedSequence() {
    return expectedSequence;
  }

  /** The chain hash after the last frame read. */
  @Override
  public long chainHash() {
    return chainHash;
  }

  /** Byte position of the next frame header. */
  public int position() {
    return position;
  }

  /**
   * Reads and verifies every remaining frame without delivering it.
   *
   * @return {position after the last frame, last sequence (0 if none), chain hash}
   */
  long[] scanToEnd() {
    while (next(SKIP)) {
      // verification happens in next()
    }
    return new long[] {position, expectedSequence - 1, chainHash};
  }

  private static final FrameHandler SKIP = (s, t, id, v, b, o, l) -> {};

  @Override
  public void close() {
    if (mapped != null) {
      IoUtil.unmap(mapped);
    }
  }
}
