package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every way the bytes can be wrong is reported with the sequence where it was found. */
class JournalCorruptionTest {

  @TempDir Path dir;

  private static final FrameHandler SKIP = (s, t, id, v, b, o, l) -> {};

  private byte[] journalWithThreeFrames() throws IOException {
    final Path path = dir.resolve("src.raj");
    try (JournalWriter w = JournalWriter.create(path, 1024, new ManualClock())) {
      for (int i = 1; i <= 3; i++) {
        final byte[] p = new byte[16];
        p[0] = (byte) i;
        w.append(2, 0, new UnsafeBuffer(p), 0, p.length);
      }
    }
    return Files.readAllBytes(path);
  }

  private static long readAll(final byte[] bytes) {
    final JournalReader r = new JournalReader(new UnsafeBuffer(bytes));
    while (r.next(SKIP)) {
      // verifying
    }
    return r.expectedSequence() - 1;
  }

  private static int frameOffset(final int index) {
    // every frame here is 32 header + 16 payload = 48 bytes, already 8-aligned
    return JournalFormat.FILE_HEADER_LENGTH + index * 48;
  }

  @Test
  void intactJournalReadsThreeFrames() throws IOException {
    assertEquals(3L, readAll(journalWithThreeFrames()));
  }

  @Test
  void flippedPayloadByteBreaksTheChainAtThatFrame() throws IOException {
    final byte[] bytes = journalWithThreeFrames();
    bytes[frameOffset(1) + JournalFormat.FRAME_HEADER_LENGTH + 5] ^= 0x01;
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readAll(bytes));
    assertEquals(2L, e.sequence());
    assertTrue(e.getMessage().contains("chain hash"));
  }

  @Test
  void tamperedEarlierFrameIsCaughtEvenIfItsOwnHashIsRecomputed() throws IOException {
    final byte[] bytes = journalWithThreeFrames();
    final UnsafeBuffer b = new UnsafeBuffer(bytes);
    final int f0 = frameOffset(0);
    bytes[f0 + JournalFormat.FRAME_HEADER_LENGTH + 3] ^= 0x7f;
    // recompute frame 0's hash as an attacker who forgets the chain would
    final long fixed = ChainHash.next(JournalFormat.GENESIS_HASH, b, f0 + 32, 16);
    b.putLong(f0 + JournalFormat.CHAIN_HASH_OFFSET, fixed);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readAll(bytes));
    assertEquals(2L, e.sequence());
  }

  @Test
  void wrongSequenceIsReported() throws IOException {
    final byte[] bytes = journalWithThreeFrames();
    new UnsafeBuffer(bytes).putLong(frameOffset(2) + JournalFormat.SEQUENCE_OFFSET, 7L);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readAll(bytes));
    assertEquals(3L, e.sequence());
    assertTrue(e.getMessage().contains("sequence 7"));
  }

  @Test
  void frameLengthSmallerThanHeaderIsReported() throws IOException {
    final byte[] bytes = journalWithThreeFrames();
    new UnsafeBuffer(bytes).putInt(frameOffset(1) + JournalFormat.FRAME_LENGTH_OFFSET, 5);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readAll(bytes));
    assertEquals(2L, e.sequence());
  }

  @Test
  void frameRunningPastTheFileIsReported() throws IOException {
    final byte[] bytes = journalWithThreeFrames();
    new UnsafeBuffer(bytes).putInt(frameOffset(2) + JournalFormat.FRAME_LENGTH_OFFSET, 100_000);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readAll(bytes));
    assertEquals(3L, e.sequence());
  }

  @Test
  void truncatedFileEndsCleanlyAtTheLastWholeFrame() throws IOException {
    // A crash that zeroes a partial frame reads as end of journal, not corruption.
    final byte[] bytes = journalWithThreeFrames();
    final UnsafeBuffer b = new UnsafeBuffer(bytes);
    b.putInt(frameOffset(2) + JournalFormat.FRAME_LENGTH_OFFSET, 0);
    assertEquals(2L, readAll(bytes));
  }

  @Test
  void badMagicVersionHeaderLengthAndCapacityAreReportedAsSequenceZero() throws IOException {
    final byte[] good = journalWithThreeFrames();

    final byte[] magic = good.clone();
    new UnsafeBuffer(magic).putInt(JournalFormat.MAGIC_OFFSET, 0x11223344);
    assertEquals(0L, assertThrows(JournalCorruptionException.class, () -> readAll(magic)).sequence());

    final byte[] version = good.clone();
    new UnsafeBuffer(version).putShort(JournalFormat.FORMAT_VERSION_OFFSET, (short) 9);
    assertEquals(
        0L, assertThrows(JournalCorruptionException.class, () -> readAll(version)).sequence());

    final byte[] headerLength = good.clone();
    new UnsafeBuffer(headerLength).putShort(JournalFormat.HEADER_LENGTH_OFFSET, (short) 16);
    assertEquals(
        0L,
        assertThrows(JournalCorruptionException.class, () -> readAll(headerLength)).sequence());

    final byte[] capacity = good.clone();
    new UnsafeBuffer(capacity).putInt(JournalFormat.CAPACITY_OFFSET, 999);
    assertEquals(
        0L, assertThrows(JournalCorruptionException.class, () -> readAll(capacity)).sequence());

    final byte[] tooShort = new byte[8];
    assertEquals(
        0L, assertThrows(JournalCorruptionException.class, () -> readAll(tooShort)).sequence());
  }

  @Test
  void openingACorruptJournalForAppendFails() throws IOException {
    final byte[] bytes = journalWithThreeFrames();
    bytes[frameOffset(0) + JournalFormat.FRAME_HEADER_LENGTH] ^= 0x10;
    final Path bad = dir.resolve("bad.raj");
    Files.write(bad, bytes);
    assertThrows(
        JournalCorruptionException.class, () -> JournalWriter.open(bad, new ManualClock()));
  }

  @Test
  void alignedFrameLengthPadsToEight() {
    assertEquals(32, JournalFormat.alignedFrameLength(0));
    assertEquals(40, JournalFormat.alignedFrameLength(1));
    assertEquals(40, JournalFormat.alignedFrameLength(8));
    assertEquals(48, JournalFormat.alignedFrameLength(9));
  }
}
