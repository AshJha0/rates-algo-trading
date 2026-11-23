package io.ratesalgo.fix.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;
import org.agrona.IoUtil;

/**
 * A session's sequence numbers in one small memory-mapped file, so that a restarted process
 * resumes the FIX session instead of resetting it and losing whatever the venue sent meanwhile.
 *
 * <p>Why a mapped file and not an append-only log: the state is three numbers, and it changes on
 * every message on the session thread. A mapped 4 KiB page takes a record as a handful of plain
 * memory stores: no system call, no allocation, no second thread. An append-only log would need a
 * write call per message (or a writer thread and a queue) and compaction, to keep three numbers.
 *
 * <p>Layout (little endian): a header with a magic number, a version and a clean-shutdown flag,
 * then two record slots of {@code nextOut, expectedIn, safeIn, generation, crc32c}. Each write
 * goes to the slot the previous write did not use, with the generation one higher, and the
 * checksum goes in last. A write cut short therefore damages only the newer slot: opening picks
 * the slot with a good checksum and the highest generation, which is then the record before it. If
 * the file holds something and neither slot verifies, opening refuses; an operator must decide,
 * because a guessed number is worse than a stopped session.
 *
 * <p>What survives: nothing is forced to disk on the message path. The mapping lives in the page
 * cache, so the numbers survive a crash or kill of this process. They do not necessarily survive a
 * power loss or a kernel crash; the file may then be up to the operating system's write-back
 * interval old. A stale inbound number is harmless: the venue is asked to resend from it and the
 * duplicates are dropped by ExecID. A stale outbound number is not: the venue sees a Logon below
 * the number it expects and refuses it, and an operator has to agree a sequence reset with the
 * venue. {@link #close()} forces the page and marks the file clean.
 *
 * <p>The inbound number has a second, lower watermark, {@link #safeInSeq(long)}: the owner moves
 * it up when everything received before it has been handed on durably (journalled). After an
 * unclean stop {@link #resumeInSeq()} returns the lower of the two, so anything received but
 * perhaps not processed is asked for again. An owner that never moves the watermark gets a full
 * resend after a crash, which is slow but safe.
 *
 * <p>One thread, the session's, calls {@link #record} and {@link #safeInSeq(long)}.
 */
public final class MappedSequenceStore implements SequenceStore, AutoCloseable {

  /** File size: one page. */
  static final int FILE_BYTES = 4096;

  /** Offset of the first record slot; the second follows at {@link #SLOT_BYTES}. */
  static final int SLOT_OFFSET = 64;

  /** Distance between the slots (one cache line each). */
  static final int SLOT_BYTES = 64;

  /** Bytes of a record that carry data and the checksum. */
  static final int RECORD_BYTES = 36;

  private static final long MAGIC = 0x5241_5445_5351_4E31L; // "RATESQN1"
  private static final int VERSION = 1;
  private static final int MAGIC_OFFSET = 0;
  private static final int VERSION_OFFSET = 8;
  private static final int CLEAN_OFFSET = 12;
  private static final int NEXT_OUT = 0;
  private static final int EXPECTED_IN = 8;
  private static final int SAFE_IN = 16;
  private static final int GENERATION = 24;
  private static final int CRC = 32;

  private final Path file;
  private final CRC32C crc = new CRC32C();
  private final byte[] scratch = new byte[CRC];
  private final boolean existed;
  private final boolean cleanAtOpen;
  private MappedByteBuffer map;
  private long nextOut = 1;
  private long expectedIn = 1;
  private long safeIn = 1;
  private long generation;
  private long loadedExpectedIn = 1;
  private long loadedSafeIn = 1;
  private boolean dirty;

  private MappedSequenceStore(final Path file, final MappedByteBuffer map, final boolean created) {
    this.file = file;
    this.map = map;
    if (created) {
      existed = false;
      cleanAtOpen = true;
      write();
      map.putInt(VERSION_OFFSET, VERSION);
      map.putInt(CLEAN_OFFSET, 1);
      // the magic goes in last: a file without it was never finished
      map.putLong(MAGIC_OFFSET, MAGIC);
      map.force();
      return;
    }
    if (map.getLong(MAGIC_OFFSET) != MAGIC || map.getInt(VERSION_OFFSET) != VERSION) {
      throw new IllegalStateException(
          "sequence store " + file + " is not a version " + VERSION + " sequence file; refusing to guess");
    }
    final int slot = newestValidSlot();
    if (slot < 0) {
      throw new IllegalStateException(
          "sequence store " + file + " is damaged: neither record has a good checksum. Agree the"
              + " sequence numbers with the venue, then delete the file or reset the session");
    }
    final int at = SLOT_OFFSET + slot * SLOT_BYTES;
    nextOut = map.getLong(at + NEXT_OUT);
    expectedIn = map.getLong(at + EXPECTED_IN);
    safeIn = map.getLong(at + SAFE_IN);
    generation = map.getLong(at + GENERATION);
    loadedExpectedIn = expectedIn;
    loadedSafeIn = safeIn;
    existed = true;
    cleanAtOpen = map.getInt(CLEAN_OFFSET) == 1;
  }

  /**
   * Opens the store, creating the file and its directories if missing.
   *
   * @throws IllegalStateException if the file exists and cannot be trusted: wrong size or header,
   *     or no record with a good checksum
   */
  public static MappedSequenceStore open(final Path file) {
    MappedByteBuffer map = null;
    try {
      final Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (FileChannel channel =
          FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        final long size = channel.size();
        if (size != 0 && size != FILE_BYTES) {
          throw new IllegalStateException(
              "sequence store " + file + " has " + size + " bytes, not " + FILE_BYTES + "; refusing to guess");
        }
        map = channel.map(FileChannel.MapMode.READ_WRITE, 0, FILE_BYTES);
        map.order(ByteOrder.LITTLE_ENDIAN);
        return new MappedSequenceStore(file, map, size == 0);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot open the sequence store " + file, e);
    } catch (final IllegalStateException e) {
      if (map != null) {
        IoUtil.unmap(map);
      }
      throw e;
    }
  }

  /** The valid slot with the highest generation, or -1. */
  private int newestValidSlot() {
    int best = -1;
    long bestGeneration = 0;
    for (int slot = 0; slot < 2; slot++) {
      final int at = SLOT_OFFSET + slot * SLOT_BYTES;
      map.get(at, scratch, 0, CRC);
      crc.reset();
      crc.update(scratch, 0, CRC);
      final long g = map.getLong(at + GENERATION);
      final boolean plausible =
          g >= 1
              && map.getLong(at + NEXT_OUT) >= 1
              && map.getLong(at + EXPECTED_IN) >= 1
              && map.getLong(at + SAFE_IN) >= 1;
      if (plausible && (int) crc.getValue() == map.getInt(at + CRC) && g > bestGeneration) {
        best = slot;
        bestGeneration = g;
      }
    }
    return best;
  }

  /** Writes the current numbers to the slot the last write did not use. */
  private void write() {
    final MappedByteBuffer m = map;
    if (m == null) {
      throw new IllegalStateException("sequence store " + file + " is closed");
    }
    generation++;
    final int at = SLOT_OFFSET + (int) (generation & 1) * SLOT_BYTES;
    putLong(scratch, NEXT_OUT, nextOut);
    putLong(scratch, EXPECTED_IN, expectedIn);
    putLong(scratch, SAFE_IN, safeIn);
    putLong(scratch, GENERATION, generation);
    crc.reset();
    crc.update(scratch, 0, CRC);
    m.putLong(at + NEXT_OUT, nextOut);
    m.putLong(at + EXPECTED_IN, expectedIn);
    m.putLong(at + SAFE_IN, safeIn);
    m.putLong(at + GENERATION, generation);
    m.putInt(at + CRC, (int) crc.getValue());
  }

  private static void putLong(final byte[] bytes, final int offset, final long value) {
    for (int i = 0; i < Long.BYTES; i++) {
      bytes[offset + i] = (byte) (value >>> (8 * i));
    }
  }

  private void markInUse() {
    if (!dirty) {
      dirty = true;
      map.putInt(CLEAN_OFFSET, 0);
    }
  }

  /** True if the file held a valid record when it was opened (false for a file just created). */
  public boolean existed() {
    return existed;
  }

  /** True if the last user closed the file properly, or the file is new. */
  public boolean cleanAtOpen() {
    return cleanAtOpen;
  }

  /** Sequence number our next outbound message will carry. */
  public long nextOutSeq() {
    return nextOut;
  }

  /** Sequence number expected on the next inbound message. */
  public long expectedInSeq() {
    return expectedIn;
  }

  /** The inbound watermark: everything below it has been handed on durably. */
  public long safeInSeq() {
    return safeIn;
  }

  /** Records both numbers. No allocation, no system call. */
  @Override
  public void record(final long nextOutSeq, final long expectedInSeq) {
    this.nextOut = nextOutSeq;
    this.expectedIn = expectedInSeq;
    write();
    markInUse();
  }

  /**
   * Moves the inbound watermark: the owner calls it with the next inbound number once every
   * message below it has been handed on durably. It may run ahead of the last {@link #record}
   * (the session records after its handler returns); {@link #resumeInSeq()} takes the lower.
   */
  public void safeInSeq(final long seq) {
    if (seq < 1) {
      throw new IllegalArgumentException("sequence numbers start at 1");
    }
    this.safeIn = seq;
    write();
    markInUse();
  }

  /**
   * The inbound number to resume from, as the file stood when it was opened: the expected number
   * after a clean stop, otherwise the lower of the expected number and the watermark, so that the
   * venue resends anything that may not have been processed.
   */
  public long resumeInSeq() {
    return cleanAtOpen ? loadedExpectedIn : Math.min(loadedExpectedIn, loadedSafeIn);
  }

  /** Sets every number to 1, for a session whose reset has been agreed with the venue. */
  public void reset() {
    nextOut = 1;
    expectedIn = 1;
    safeIn = 1;
    loadedExpectedIn = 1;
    loadedSafeIn = 1;
    write();
    markInUse();
  }

  /** Marks the file clean, forces it to disk and unmaps it. */
  @Override
  public void close() {
    final MappedByteBuffer m = map;
    if (m != null) {
      m.putInt(CLEAN_OFFSET, 1);
      m.force();
      closeWithoutMarkingClean();
    }
  }

  /** Unmaps and nothing else: what a crash leaves behind. Tests use it to stand in for one. */
  public void closeWithoutMarkingClean() {
    final MappedByteBuffer m = map;
    map = null;
    if (m != null) {
      IoUtil.unmap(m);
    }
  }

  @Override
  public String toString() {
    return "MappedSequenceStore[" + file + ", nextOut=" + nextOut + ", expectedIn=" + expectedIn + ", safeIn="
        + safeIn + "]";
  }
}
