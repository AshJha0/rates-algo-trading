package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentedJournalTest {

  private static final int SEGMENT = 512;

  @TempDir Path dir;

  private static UnsafeBuffer payload(final int seed, final int length) {
    final byte[] b = new byte[length];
    for (int i = 0; i < length; i++) {
      b[i] = (byte) (seed * 17 + i);
    }
    return new UnsafeBuffer(b);
  }

  private static long writeFrames(final Path dir, final int frames, final int segment) {
    final ManualClock clock = new ManualClock(5_000L);
    try (SegmentedJournal j = SegmentedJournal.create(dir, "in", segment, clock)) {
      for (int i = 0; i < frames; i++) {
        clock.advance(10L);
        final UnsafeBuffer p = payload(i, 8 + (i % 41));
        assertEquals(i + 1L, j.append(10, 0, p, 0, p.capacity()));
      }
      return j.chainHash();
    }
  }

  private static List<long[]> readAll(final Path dir) {
    final List<long[]> out = new ArrayList<>();
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, "in")) {
      while (r.next((s, t, id, v, b, o, l) -> out.add(new long[] {s, t, id, l, b.getByte(o)}))) {
        // collecting
      }
    }
    return out;
  }

  @Test
  void segmentNamesHaveSixDigitIndices() {
    assertEquals("usd.000001.raj", SegmentedJournal.segmentPath(dir, "usd", 1).getFileName().toString());
    assertEquals(
        "usd.012345.raj", SegmentedJournal.segmentPath(dir, "usd", 12_345).getFileName().toString());
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.segmentPath(dir, "usd", 0));
    assertThrows(
        IllegalArgumentException.class, () -> SegmentedJournal.segmentPath(dir, "usd", 1_000_000));
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.segmentPath(dir, "a/b", 1));
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.segmentPath(dir, "", 1));
    assertThrows(IllegalArgumentException.class, () -> SegmentedJournal.segmentPath(dir, "..", 1));
  }

  @Test
  void rollsAndReadsBackAcrossSegmentsWithOneChain() {
    final long hash = writeFrames(dir, 60, SEGMENT);
    final int[] indices = SegmentedJournal.segmentIndices(dir, "in");
    assertTrue(indices.length > 3, "expected several segments, got " + indices.length);
    for (int i = 0; i < indices.length; i++) {
      assertEquals(i + 1, indices[i]);
    }
    final List<long[]> frames = readAll(dir);
    assertEquals(60, frames.size());
    for (int i = 0; i < 60; i++) {
      assertEquals(i + 1L, frames.get(i)[0]);
      assertEquals(5_000L + 10L * (i + 1), frames.get(i)[1]);
      assertEquals(8 + (i % 41), frames.get(i)[3]);
      assertEquals((byte) (i * 17), (byte) frames.get(i)[4]);
    }
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, "in")) {
      assertEquals(Long.valueOf(indices.length), Long.valueOf(r.lastIndex()));
      while (r.next((s, t, id, v, b, o, l) -> {})) {
        // verifying
      }
      assertEquals(hash, r.chainHash());
      assertEquals(61L, r.expectedSequence());
      assertEquals(indices.length, r.segmentsRead());
    }
  }

  @Test
  void chainEqualsTheSingleFileChainOverTheSamePayloads() {
    final long segmentedHash = writeFrames(dir, 60, SEGMENT);
    final Path single = dir.resolve("single.raj");
    try (JournalWriter w = JournalWriter.create(single, 1 << 16, new ManualClock())) {
      for (int i = 0; i < 60; i++) {
        final UnsafeBuffer p = payload(i, 8 + (i % 41));
        w.append(10, 0, p, 0, p.capacity());
      }
      assertEquals(w.chainHash(), segmentedHash);
    }
  }

  @Test
  void replayerWalksSegmentsAndDrivesTheClock() {
    writeFrames(dir, 25, SEGMENT);
    final ManualClock clock = new ManualClock(0L);
    final List<Long> stamps = new ArrayList<>();
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, "in")) {
      final long n = new Replayer(clock).replay(r, (s, t, id, v, b, o, l) -> stamps.add(clock.epochNanos()));
      assertEquals(25L, n);
    }
    assertEquals(5_000L + 250L, clock.epochNanos());
    assertEquals(5_010L, stamps.get(0));
  }

  @Test
  void reopenContinuesSequenceAndChainIntoNewSegments() {
    final ManualClock clock = new ManualClock();
    try (SegmentedJournal j = SegmentedJournal.create(dir, "in", SEGMENT, clock)) {
      for (int i = 0; i < 20; i++) {
        final UnsafeBuffer p = payload(i, 30);
        j.append(1, 0, p, 0, 30);
      }
    }
    final long hash;
    try (SegmentedJournal j = SegmentedJournal.open(dir, "in", SEGMENT, clock)) {
      assertEquals(21L, j.nextSequence());
      final int before = j.segmentIndex();
      for (int i = 20; i < 40; i++) {
        final UnsafeBuffer p = payload(i, 30);
        j.append(1, 0, p, 0, 30);
      }
      assertTrue(j.segmentIndex() > before);
      assertEquals(SEGMENT, j.segmentCapacity());
      j.sync();
      hash = j.chainHash();
    }
    final Path single = dir.resolve("single.raj");
    try (JournalWriter w = JournalWriter.create(single, 1 << 16, clock)) {
      for (int i = 0; i < 40; i++) {
        w.append(1, 0, payload(i, 30), 0, 30);
      }
      assertEquals(w.chainHash(), hash);
    }
    assertEquals(40, readAll(dir).size());
  }

  @Test
  void createRefusesAnExistingJournalAndOpenRefusesAMissingOne() {
    writeFrames(dir, 2, SEGMENT);
    final ManualClock clock = new ManualClock();
    assertThrows(
        IllegalArgumentException.class, () -> SegmentedJournal.create(dir, "in", SEGMENT, clock));
    assertThrows(
        IllegalArgumentException.class, () -> SegmentedJournal.open(dir, "nope", SEGMENT, clock));
  }

  @Test
  void frameLargerThanAnEmptySegmentIsRejectedWithoutRolling() {
    try (SegmentedJournal j = SegmentedJournal.create(dir, "in", 256, new ManualClock())) {
      final UnsafeBuffer big = payload(1, 300);
      assertThrows(JournalFullException.class, () -> j.append(1, 0, big, 0, 300));
      assertEquals(1, j.segmentIndex());
      assertEquals(1L, j.append(1, 0, big, 0, 100));
    }
  }

  @Test
  void closedJournalRejectsAppendAndCloseIsIdempotent() {
    final SegmentedJournal j = SegmentedJournal.create(dir, "in", SEGMENT, new ManualClock());
    j.close();
    j.close();
    assertThrows(IllegalStateException.class, () -> j.append(1, 0, payload(1, 8), 0, 8));
    assertThrows(IllegalStateException.class, j::sync);
  }

  @Test
  void emptyDirectoryReadsAsAnEmptyJournal() {
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, "in")) {
      assertFalse(r.next((s, t, id, v, b, o, l) -> {}));
      assertEquals(0, r.lastIndex());
    }
  }

  @Test
  void flippedByteInALaterSegmentIsReportedAtItsSequence() throws IOException {
    writeFrames(dir, 60, SEGMENT);
    final Path second = SegmentedJournal.segmentPath(dir, "in", 2);
    final long firstSeqOfSecond = firstSequenceOf(second);
    final byte[] bytes = Files.readAllBytes(second);
    bytes[JournalFormat.FILE_HEADER_LENGTH + JournalFormat.FRAME_HEADER_LENGTH] ^= 0x01;
    Files.write(second, bytes);
    final List<long[]> delivered = new ArrayList<>();
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readInto(delivered));
    assertEquals(firstSeqOfSecond, e.sequence());
    assertEquals(firstSeqOfSecond - 1, delivered.size());
  }

  @Test
  void segmentSwappedForAValidChainOfItsOwnIsCaughtAtTheBoundary() throws IOException {
    writeFrames(dir, 60, SEGMENT);
    final Path second = SegmentedJournal.segmentPath(dir, "in", 2);
    final long firstSeqOfSecond = firstSequenceOf(second);
    // a self-consistent segment that does not continue segment 1's chain
    final Path other = dir.resolve("other");
    Files.createDirectory(other);
    final ManualClock clock = new ManualClock();
    try (JournalWriter w =
        JournalWriter.create(
            other.resolve("x.raj"), SEGMENT, clock, firstSeqOfSecond, 0x1234L)) {
      w.append(10, 0, payload(99, 16), 0, 16);
    }
    Files.copy(other.resolve("x.raj"), second, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readInto(new ArrayList<>()));
    assertEquals(firstSeqOfSecond, e.sequence());
    assertTrue(e.getMessage().contains("chain hash"));
  }

  @Test
  void missingMiddleSegmentIsReportedAfterDeliveringEverythingBeforeIt() throws IOException {
    writeFrames(dir, 60, SEGMENT);
    final Path second = SegmentedJournal.segmentPath(dir, "in", 2);
    final long firstSeqOfSecond = firstSequenceOf(second);
    Files.delete(second);
    final List<long[]> delivered = new ArrayList<>();
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readInto(delivered));
    assertEquals(firstSeqOfSecond, e.sequence());
    assertTrue(e.getMessage().contains("missing"));
    assertEquals(firstSeqOfSecond - 1, delivered.size());
    final ManualClock clock = new ManualClock();
    assertThrows(
        JournalCorruptionException.class, () -> SegmentedJournal.open(dir, "in", SEGMENT, clock));
  }

  @Test
  void truncatedEarlierSegmentBreaksTheSequenceAtTheNextSegment() throws IOException {
    writeFrames(dir, 60, SEGMENT);
    final Path first = SegmentedJournal.segmentPath(dir, "in", 1);
    final Path second = SegmentedJournal.segmentPath(dir, "in", 2);
    final long firstSeqOfSecond = firstSequenceOf(second);
    // zero the last frame of segment 1 as a crash before its length was written would
    final byte[] bytes = Files.readAllBytes(first);
    final int lastFrame = lastFrameOffset(bytes);
    for (int i = lastFrame; i < bytes.length; i++) {
      bytes[i] = 0;
    }
    Files.write(first, bytes);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> readInto(new ArrayList<>()));
    assertEquals(firstSeqOfSecond - 1, e.sequence());
  }

  private void readInto(final List<long[]> delivered) {
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, "in")) {
      while (r.next((s, t, id, v, b, o, l) -> delivered.add(new long[] {s}))) {
        // collecting until the break
      }
    }
  }

  private static long firstSequenceOf(final Path segment) throws IOException {
    final UnsafeBuffer b = new UnsafeBuffer(Files.readAllBytes(segment));
    return b.getLong(JournalFormat.FILE_HEADER_LENGTH + JournalFormat.SEQUENCE_OFFSET);
  }

  private static int lastFrameOffset(final byte[] bytes) {
    final UnsafeBuffer b = new UnsafeBuffer(bytes);
    int pos = JournalFormat.FILE_HEADER_LENGTH;
    int last = pos;
    while (pos + 4 <= bytes.length && b.getInt(pos) != 0) {
      last = pos;
      pos += JournalFormat.alignedFrameLength(b.getInt(pos) - JournalFormat.FRAME_HEADER_LENGTH);
    }
    return last;
  }

  @Property(tries = 50)
  void anySegmentSizeGivesTheSameChainAsOneFile(
      @ForAll @IntRange(min = 128, max = 2048) final int segment,
      @ForAll @IntRange(min = 1, max = 120) final int frames)
      throws IOException {
    final Path d = Files.createTempDirectory("segprop");
    try {
      final ManualClock clock = new ManualClock();
      final long hash;
      try (SegmentedJournal j = SegmentedJournal.create(d, "p", segment, clock)) {
        for (int i = 0; i < frames; i++) {
          final int len = (i * 7) % Math.max(1, segment - 80);
          j.append(3, 0, payload(i, len), 0, len);
        }
        hash = j.chainHash();
      }
      final List<byte[]> seen = new ArrayList<>();
      try (SegmentedJournalReader r = SegmentedJournalReader.open(d, "p")) {
        while (r.next(
            (s, t, id, v, b, o, l) -> {
              final byte[] copy = new byte[l];
              b.getBytes(o, copy);
              seen.add(copy);
            })) {
          // collecting
        }
        assertEquals(hash, r.chainHash());
      }
      assertEquals(frames, seen.size());
      for (int i = 0; i < frames; i++) {
        final int len = (i * 7) % Math.max(1, segment - 80);
        final byte[] expected = new byte[len];
        payload(i, len).getBytes(0, expected);
        assertArrayEquals(expected, seen.get(i));
      }
    } finally {
      try (var files = Files.list(d)) {
        for (final Path p : files.toList()) {
          Files.delete(p);
        }
      }
      Files.delete(d);
    }
  }
}
