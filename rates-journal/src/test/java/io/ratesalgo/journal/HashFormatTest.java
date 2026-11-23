package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chain hash named in the segment header (review finding L8, DECISIONS.md DR-R6-L03): a
 * default journal is still format 1 with FNV-1a, byte for byte; a WORD64 journal is format 2 and
 * verifies with its own hash; readers take either and refuse a mixture.
 */
class HashFormatTest {

  private static final int SEGMENT = 4096;

  @TempDir Path dir;

  private static UnsafeBuffer payload(final int seed, final int length) {
    final byte[] b = new byte[length];
    for (int i = 0; i < length; i++) {
      b[i] = (byte) (seed * 31 + i);
    }
    return new UnsafeBuffer(b);
  }

  private static ByteBuffer header(final Path file) throws IOException {
    try (FileChannel c = FileChannel.open(file)) {
      final ByteBuffer h = ByteBuffer.allocate(JournalFormat.FILE_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
      c.read(h, 0);
      return h;
    }
  }

  /** Appends {@code n} frames over several segments and returns the final chain hash. */
  private long fill(final String name, final int algorithm, final int n) {
    try (SegmentedJournal j = SegmentedJournal.create(dir, name, SEGMENT, new ManualClock(5L), false, algorithm)) {
      for (int i = 0; i < n; i++) {
        j.append(10, 0, payload(i, 1 + i % 90), 0, 1 + i % 90);
      }
      assertTrue(j.segmentIndex() > 2, "the test spans segments");
      assertEquals(algorithm, j.hashAlgorithm());
      return j.chainHash();
    }
  }

  private long readBack(final String name, final int expectedAlgorithm) {
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, name)) {
      assertEquals(expectedAlgorithm, r.hashAlgorithm(), "known before the first frame");
      while (r.next((s, t, id, v, b, o, l) -> {})) {
        // verifying
      }
      return r.chainHash();
    }
  }

  @Test
  void theDefaultIsFormatOneWithFnvAndAZeroAlgorithmField() throws IOException {
    final long fnv = fill("fnv", ChainHash.DEFAULT, 200);
    final ByteBuffer h = header(SegmentedJournal.segmentPath(dir, "fnv", 1));
    assertEquals(JournalFormat.FORMAT_VERSION, h.getShort(JournalFormat.FORMAT_VERSION_OFFSET));
    assertEquals(0, h.getShort(JournalFormat.HASH_ALGORITHM_OFFSET), "version 1 files are unchanged");
    assertEquals(fnv, readBack("fnv", ChainHash.FNV1A));

    // the same frames chained by hand with FNV-1a: what every journal before round 6 holds
    long expected = JournalFormat.GENESIS_HASH;
    for (int i = 0; i < 200; i++) {
      expected = ChainHash.next(expected, payload(i, 1 + i % 90), 0, 1 + i % 90);
    }
    assertEquals(expected, fnv);
  }

  @Test
  void aWord64JournalIsFormatTwoAndVerifiesAcrossSegmentsAndReopens() throws IOException {
    final long word = fill("word", ChainHash.WORD64, 200);
    final ByteBuffer h = header(SegmentedJournal.segmentPath(dir, "word", 2));
    assertEquals(JournalFormat.FORMAT_VERSION_NAMED_HASH, h.getShort(JournalFormat.FORMAT_VERSION_OFFSET));
    assertEquals(ChainHash.WORD64, h.getShort(JournalFormat.HASH_ALGORITHM_OFFSET));
    assertEquals(word, readBack("word", ChainHash.WORD64));
    long expected = JournalFormat.GENESIS_HASH;
    for (int i = 0; i < 200; i++) {
      expected = ChainHash.word64(expected, payload(i, 1 + i % 90), 0, 1 + i % 90);
    }
    assertEquals(expected, word);
    assertNotEquals(fill("fnv", ChainHash.FNV1A, 200), word);

    // reopened for appending, with options naming the other hash: the files decide
    try (SegmentedJournal j =
        SegmentedJournal.open(dir, "word", SEGMENT, new ManualClock(6L), true, SegmentedJournal.Options.DEFAULT)) {
      assertEquals(ChainHash.WORD64, j.hashAlgorithm());
      for (int i = 0; i < 100; i++) {
        j.append(11, 0, payload(i, 40), 0, 40);
      }
      expected = j.chainHash();
    }
    assertEquals(expected, readBack("word", ChainHash.WORD64));
    final JournalRecovery.Report report = JournalRecovery.repair(dir, "word");
    assertEquals(0, report.tornSegment());
  }

  @Test
  void aSingleFileWord64JournalRoundTripsThroughWriterAndReader() {
    final Path file = dir.resolve("one.raj");
    final long last;
    try (JournalWriter w = JournalWriter.create(file, SEGMENT, new ManualClock(1L), ChainHash.WORD64)) {
      assertEquals(ChainHash.WORD64, w.hashAlgorithm());
      w.append(3, 0, payload(1, 17), 0, 17);
      last = w.append(3, 0, payload(2, 9), 0, 9);
    }
    try (JournalWriter w = JournalWriter.open(file, new ManualClock(2L))) {
      assertEquals(ChainHash.WORD64, w.hashAlgorithm(), "reopening keeps the file's hash");
      assertEquals(last + 1, w.nextSequence());
    }
    try (JournalReader r = JournalReader.open(file)) {
      assertEquals(ChainHash.WORD64, r.hashAlgorithm());
      assertTrue(r.next((s, t, id, v, b, o, l) -> {}));
      assertTrue(r.next((s, t, id, v, b, o, l) -> {}));
      assertFalse(r.next((s, t, id, v, b, o, l) -> {}));
    }
    assertThrows(IllegalArgumentException.class, () -> JournalWriter.create(dir.resolve("bad.raj"), SEGMENT, new ManualClock(), 7));
    assertFalse(Files.exists(dir.resolve("bad.raj")));
  }

  @Test
  void readersRefuseAnUnknownAlgorithmAndAVersionTwoFileNamingFnv() throws IOException {
    final Path file = dir.resolve("x.raj");
    try (JournalWriter w = JournalWriter.create(file, SEGMENT, new ManualClock(1L), ChainHash.WORD64)) {
      w.append(3, 0, payload(1, 17), 0, 17);
    }
    for (final short id : new short[] {9, (short) ChainHash.FNV1A}) {
      try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
        final ByteBuffer b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0, id);
        c.write(b, JournalFormat.HASH_ALGORITHM_OFFSET);
      }
      final JournalCorruptionException e = assertThrows(JournalCorruptionException.class, () -> JournalReader.open(file));
      assertTrue(e.getMessage().contains("unsupported chain hash algorithm " + id), e.getMessage());
    }
    try (FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
      c.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) 3), JournalFormat.FORMAT_VERSION_OFFSET);
    }
    assertThrows(JournalCorruptionException.class, () -> JournalReader.open(file));
  }

  @Test
  void aJournalWhoseSegmentsMixAlgorithmsIsRefused() throws IOException {
    fill("mixed", ChainHash.FNV1A, 200);
    final Path second = SegmentedJournal.segmentPath(dir, "mixed", 2);
    final Path sub = Files.createDirectory(dir.resolve("sub"));
    // a WORD64 copy of segment 2's frames, so only the header's algorithm is wrong
    try (JournalWriter w = JournalWriter.create(sub.resolve("w.raj"), SEGMENT, new ManualClock(5L), ChainHash.WORD64)) {
      w.append(10, 0, payload(1, 10), 0, 10);
    }
    Files.copy(sub.resolve("w.raj"), second, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, "mixed")) {
      final JournalCorruptionException e =
          assertThrows(JournalCorruptionException.class, () -> {
            while (r.next((s, t, id, v, b, o, l) -> {})) {
              // until the bad segment
            }
          });
      assertTrue(e.getMessage().contains("uses another chain hash than segment 1"), e.getMessage());
    }
  }

  @Test
  void optionsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.Options.DEFAULT.withHashAlgorithm(0));
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.Options.DEFAULT.withRefault(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.Options.DEFAULT.withRefault(0, 0));
    assertEquals(ChainHash.FNV1A, SegmentedJournal.Options.DEFAULT.hashAlgorithm());
    assertTrue(SegmentedJournal.Options.DEFAULT.withLockPages(true).lockPages());
  }

  /**
   * The refault stores only zero over zero: run it in a tight loop beside an appender and every
   * frame still verifies, and the file reads the same as one written without it (review finding
   * L9; the write-protect fault itself cannot be observed from a unit test).
   */
  @Test
  void refaultingBesideTheAppenderChangesNoByte() throws Exception {
    final int capacity = 1 << 20;
    final Path with = dir.resolve("with.raj");
    final Path without = dir.resolve("without.raj");
    try (JournalWriter a = JournalWriter.create(with, capacity, new ManualClock(9L), ChainHash.WORD64);
        JournalWriter b = JournalWriter.create(without, capacity, new ManualClock(9L), ChainHash.WORD64)) {
      final AtomicBoolean stop = new AtomicBoolean();
      final long[] touched = new long[1];
      final Thread refaulter =
          new Thread(
              () -> {
                while (!stop.get()) {
                  touched[0] += a.refault(64 << 10);
                }
              },
              "refaulter");
      refaulter.setDaemon(true);
      refaulter.start();
      int i = 0;
      while (a.remaining() > 400) {
        final UnsafeBuffer p = payload(i, 1 + i % 300);
        a.append(4, 0, p, 0, p.capacity());
        b.append(4, 0, p, 0, p.capacity());
        i++;
      }
      stop.set(true);
      refaulter.join(60_000L);
      assertFalse(refaulter.isAlive());
      assertTrue(touched[0] > 0);
      assertEquals(b.chainHash(), a.chainHash());
      assertTrue(a.refault(64 << 10) <= 1, "at the end the window is cut at the end of the file");
    }
    assertEquals(-1L, Files.mismatch(with, without), "byte for byte the same file");
  }

  @Test
  void pagesAreLockedOnlyOnLinuxAndARefusalIsCountedNotFatal() {
    try (SegmentedJournal j =
        SegmentedJournal.create(dir, "locked", 1 << 16, new ManualClock(), true,
            SegmentedJournal.Options.DEFAULT.withLockPages(true))) {
      final long[] locks = j.pageLocks();
      assertEquals(1, locks[0] + locks[1], "the first segment was offered for locking once");
      if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
        assertEquals(0, locks[0], "nothing is locked off Linux");
      }
    }
    try (SegmentedJournal j = SegmentedJournal.create(dir, "unlocked", 1 << 16, new ManualClock(), true)) {
      assertEquals(0, j.pageLocks()[0] + j.pageLocks()[1]);
    }
    try (JournalWriter w = JournalWriter.create(dir.resolve("p.raj"), 1 << 16, new ManualClock())) {
      assertFalse(PageLock.lock(w.mapped(), "Windows 11"));
    }
  }
}
