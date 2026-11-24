package io.ratesalgo.journal;

import java.nio.ByteOrder;

/**
 * Byte layout of a journal file, pinned in API_SPEC.md Section 1. Everything is little-endian.
 *
 * <pre>
 * file header (32 bytes)
 *   0  int32   magic            'R','A','J','1' read as little-endian int32
 *   4  uint16  formatVersion    1 (FNV-1a chain) or 2 (chain named at offset 20)
 *   6  uint16  headerLength     32
 *   8  int32   capacityBytes    total mapped size of the file, header included
 *  12  int64   createdEpochNanos
 *  20  uint16  hashAlgorithm    version 2 only: ChainHash id (2 = WORD64); zero in version 1
 *  22  10 bytes reserved, zero
 *
 * frame (32-byte header, then payload)
 *   0  int32   frameLength      header + payload bytes; 0 marks the end of the journal
 *   4  int64   sequence         1-based, strictly increasing by one
 *  12  int64   timestampNanos   epoch nanoseconds from the writer's clock
 *  20  uint16  templateId       SBE template id of the payload
 *  22  uint16  schemaVersion    SBE schema version of the payload
 *  24  int64   chainHash        the journal's ChainHash of (previous chainHash, payload)
 *  32  payload
 * </pre>
 *
 * <p>A journal created with the default {@link ChainHash#FNV1A} is written as version 1, byte for
 * byte as before round 6, so existing tools and recorded hashes keep working. Version 2 exists to
 * name another algorithm; readers accept both and verify each frame with the algorithm its file
 * names. Every segment of one journal uses the same algorithm.
 */
public final class JournalFormat {

  public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

  /** 'R' 'A' 'J' '1' in little-endian byte order. */
  public static final int MAGIC = 0x314A4152;

  /** Format of a journal chained with {@link ChainHash#FNV1A}. */
  public static final int FORMAT_VERSION = 1;

  /** Format of a journal whose header names its chain hash at {@link #HASH_ALGORITHM_OFFSET}. */
  public static final int FORMAT_VERSION_NAMED_HASH = 2;
  public static final int FILE_HEADER_LENGTH = 32;

  public static final int MAGIC_OFFSET = 0;
  public static final int FORMAT_VERSION_OFFSET = 4;
  public static final int HEADER_LENGTH_OFFSET = 6;
  public static final int CAPACITY_OFFSET = 8;
  public static final int CREATED_OFFSET = 12;
  public static final int HASH_ALGORITHM_OFFSET = 20;

  public static final int FRAME_HEADER_LENGTH = 32;
  public static final int FRAME_LENGTH_OFFSET = 0;
  public static final int SEQUENCE_OFFSET = 4;
  public static final int TIMESTAMP_OFFSET = 12;
  public static final int TEMPLATE_ID_OFFSET = 20;
  public static final int SCHEMA_VERSION_OFFSET = 22;
  public static final int CHAIN_HASH_OFFSET = 24;

  /** Frames are padded to this alignment so that every header starts on an 8-byte boundary. */
  public static final int FRAME_ALIGNMENT = 8;

  /** The chain hash before the first frame. */
  public static final long GENESIS_HASH = 0L;

  private JournalFormat() {}

  /** The format version a file chained with {@code algorithm} is written as. */
  public static int formatVersionFor(final int algorithm) {
    return algorithm == ChainHash.FNV1A ? FORMAT_VERSION : FORMAT_VERSION_NAMED_HASH;
  }

  /** Total bytes a frame with the given payload occupies, padding included. */
  public static int alignedFrameLength(final int payloadLength) {
    final int raw = FRAME_HEADER_LENGTH + payloadLength;
    return (raw + FRAME_ALIGNMENT - 1) & -FRAME_ALIGNMENT;
  }
}
