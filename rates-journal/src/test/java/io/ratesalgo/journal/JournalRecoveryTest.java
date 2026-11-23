package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A journal left by a crash: a torn tail is cut back to the last whole frame and reported, and the
 * journal goes on from there; damage anywhere else refuses to open and changes nothing.
 */
class JournalRecoveryTest {

  private static final int CAPACITY = 4096;
  private static final String NAME = "in";

  @TempDir Path root;

  private final ManualClock clock = new ManualClock(1_000L);

  /** What a journal looked like when it was written: the hash after each frame and where frames start. */
  private static final class Written {
    private final long[] hashAfter;
    private final int[] frameStart;
    private final int[] frameLength;

    Written(final long[] hashAfter, final int[] frameStart, final int[] frameLength) {
      this.hashAfter = hashAfter;
      this.frameStart = frameStart;
      this.frameLength = frameLength;
    }

    long[] hashAfter() {
      return hashAfter;
    }

    int[] frameStart() {
      return frameStart;
    }

    int[] frameLength() {
      return frameLength;
    }
  }

  private static byte[] payload(final int i) {
    final byte[] bytes = new byte[5 + (i * 7) % 23];
    for (int b = 0; b < bytes.length; b++) {
      bytes[b] = (byte) (i * 31 + b + 1);
    }
    return bytes;
  }

  private Written write(final Path dir, final int frames) {
    final long[] hashAfter = new long[frames + 1];
    final int[] start = new int[frames + 1];
    final int[] length = new int[frames + 1];
    int position = JournalFormat.FILE_HEADER_LENGTH;
    try (SegmentedJournal j = SegmentedJournal.create(dir, NAME, CAPACITY, clock)) {
      for (int i = 1; i <= frames; i++) {
        final byte[] p = payload(i);
        clock.advance(10);
        assertEquals(i, j.append(40 + i, 1, new UnsafeBuffer(p), 0, p.length));
        hashAfter[i] = j.chainHash();
        final int aligned = JournalFormat.alignedFrameLength(p.length);
        if (j.segmentIndex() == 1) {
          start[i] = position;
          length[i] = aligned;
          position += aligned;
        }
      }
    }
    return new Written(hashAfter, start, length);
  }

  private Path dir(final String name) throws IOException {
    return Files.createDirectories(root.resolve(name));
  }

  private static Path copy(final Path from, final Path to) throws IOException {
    Files.createDirectories(to);
    try (Stream<Path> files = Files.list(from)) {
      for (final Path f : files.toList()) {
        Files.copy(f, to.resolve(f.getFileName()));
      }
    }
    return to;
  }

  private static long frames(final Path dir) {
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, NAME)) {
      long n = 0;
      while (r.next((s, t, id, v, b, o, l) -> {})) {
        n++;
      }
      return n;
    }
  }

  private void assertOpensAfter(final Path dir, final Written w, final int wholeFrames) {
    try (SegmentedJournal j = SegmentedJournal.open(dir, NAME, CAPACITY, clock)) {
      assertEquals(wholeFrames + 1, j.nextSequence());
      assertEquals(w.hashAfter()[wholeFrames], j.chainHash());
      final byte[] p = payload(99);
      assertEquals(wholeFrames + 1, j.append(7, 1, new UnsafeBuffer(p), 0, p.length));
    }
    assertEquals(wholeFrames + 1, frames(dir));
  }

  @Test
  void aFileCutAtEveryByteOfItsLastFrameOpensCleanlyAtThePreviousFrame() throws IOException {
    final int frames = 9;
    final Path source = dir("source");
    final Written w = write(source, frames);
    final int start = w.frameStart()[frames];
    final int length = w.frameLength()[frames];
    assertTrue(start > 0 && length > JournalFormat.FRAME_HEADER_LENGTH);
    for (int cut = 0; cut < length; cut++) {
      final Path d = copy(source, root.resolve("cut-" + cut));
      try (RandomAccessFile f = new RandomAccessFile(SegmentedJournal.segmentPath(d, NAME, 1).toFile(), "rw")) {
        f.setLength(start + cut);
      }
      // the file as it stands does not open: its size is not the one its header declares
      assertThrows(JournalCorruptionException.class, () -> SegmentedJournal.open(d, NAME, CAPACITY, clock).close());
      final JournalRecovery.Report report = JournalRecovery.repair(d, NAME);
      // a cut inside the zero padding after the payload removes nothing of the frame
      final boolean whole = cut >= JournalFormat.FRAME_HEADER_LENGTH + payload(frames).length;
      final int kept = whole ? frames : frames - 1;
      assertEquals(kept, report.frames(), "cut at byte " + cut);
      assertEquals(CAPACITY - start - cut, report.extendedBytes());
      assertTrue(report.repaired());
      assertEquals(1_000L + 10L * kept, report.lastTimestampNanos());
      // every cut that left part of the frame behind is counted; a cut that left none of it (or
      // only zero bytes of its length) reads as a clean end
      final boolean torn = !whole && cut > 0 && lengthByte(frames, cut);
      assertEquals(torn ? 1 : 0, report.tornSegment(), "cut at byte " + cut);
      assertEquals(torn ? frames : 0, report.tornSequence());
      assertEquals(torn, report.tornBytes() > 0);
      assertOpensAfter(d, w, kept);
      // a second pass finds nothing left to repair
      final JournalRecovery.Report again = JournalRecovery.repair(d, NAME);
      assertFalse(again.repaired());
      assertEquals(kept + 1, again.frames());
    }
  }

  /** True if the first {@code cut} bytes of the frame hold a non-zero byte of its length field. */
  private static boolean lengthByte(final int frame, final int cut) {
    final int stored = JournalFormat.FRAME_HEADER_LENGTH + payload(frame).length;
    final int kept = Math.min(cut, Integer.BYTES);
    final int mask = kept == Integer.BYTES ? -1 : (1 << (8 * kept)) - 1;
    return (stored & mask) != 0;
  }

  @Test
  void aLastFrameWhoseBytesNeverReachedTheDiskIsCutAndCounted() throws IOException {
    final int frames = 6;
    final Path source = dir("source");
    final Written w = write(source, frames);
    final int start = w.frameStart()[frames];
    final int length = w.frameLength()[frames];
    // the length is on disk; from byte k on, the frame is still the zeros the segment was made of
    for (int k = Integer.BYTES; k < length - 3; k++) {
      final Path d = copy(source, root.resolve("zero-" + k));
      final Path segment = SegmentedJournal.segmentPath(d, NAME, 1);
      final byte[] bytes = Files.readAllBytes(segment);
      boolean changed = false;
      for (int i = start + k; i < start + length; i++) {
        changed |= bytes[i] != 0;
        bytes[i] = 0;
      }
      Files.write(segment, bytes);
      if (!changed) {
        continue; // only padding was zeroed: the frame is whole
      }
      final JournalRecovery.Report report = JournalRecovery.repair(d, NAME);
      assertEquals(1, report.tornSegment(), "zeroed from byte " + k);
      assertEquals(frames, report.tornSequence());
      assertEquals(length, report.tornBytes());
      assertEquals(0, report.extendedBytes());
      assertTrue(report.describe(NAME).contains("TORN TAIL"), report.describe(NAME));
      assertOpensAfter(d, w, frames - 1);
    }
  }

  @Test
  void aTornLengthThatIsNotAFrameLengthIsStillATail() throws IOException {
    final Path d = dir("garbage-length");
    final Written w = write(d, 4);
    final Path segment = SegmentedJournal.segmentPath(d, NAME, 1);
    final byte[] bytes = Files.readAllBytes(segment);
    final int tail = w.frameStart()[4] + w.frameLength()[4];
    bytes[tail] = 7; // shorter than a frame header
    Files.write(segment, bytes);
    assertEquals(5, JournalRecovery.repair(d, NAME).tornSequence());
    assertOpensAfter(d, w, 4);

    bytes[tail] = (byte) 0xFF; // runs past the end of the segment
    bytes[tail + 1] = (byte) 0xFF;
    bytes[tail + 2] = (byte) 0x7F;
    Files.write(segment, bytes);
    final JournalRecovery.Report report = JournalRecovery.repair(d, NAME);
    assertEquals(5, report.tornSequence());
    assertEquals(JournalFormat.FRAME_HEADER_LENGTH, report.tornBytes());
  }

  @Test
  void damageInTheMiddleOfTheChainRefusesToOpenAndChangesNothing() throws IOException {
    final Path d = dir("middle");
    final Written w = write(d, 8);
    final Path segment = SegmentedJournal.segmentPath(d, NAME, 1);
    final byte[] good = Files.readAllBytes(segment);
    for (final int frame : new int[] {1, 4, 7}) {
      final byte[] bad = good.clone();
      bad[w.frameStart()[frame] + JournalFormat.FRAME_HEADER_LENGTH + 2] ^= 0x10;
      Files.write(segment, bad);
      final JournalCorruptionException e =
          assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME));
      assertTrue(e.getMessage().contains("refusing to start"), e.getMessage());
      assertTrue(e.getMessage().contains("frame " + frame), e.getMessage());
      assertArrayEquals(bad, Files.readAllBytes(segment), "a refused journal is left as it was found");
    }
    // a damaged length in the middle is refused too, whatever it claims
    final byte[] bad = good.clone();
    bad[w.frameStart()[3]] = 3;
    Files.write(segment, bad);
    assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME));
  }

  @Test
  void aTailThatIsNotTheJournalsLastFrameIsRefused() throws IOException {
    final Path d = dir("segments");
    final int frames = 400; // several 4 KiB segments
    write(d, frames);
    final int[] indices = SegmentedJournal.segmentIndices(d, NAME);
    assertTrue(indices.length >= 3);
    final Path first = SegmentedJournal.segmentPath(d, NAME, 1);
    final byte[] good = Files.readAllBytes(first);
    int end = good.length;
    while (good[end - 1] == 0) {
      end--;
    }
    final byte[] bad = good.clone();
    bad[end - 1] ^= 0x01; // inside the last frame of segment 1: nothing follows it in that segment
    Files.write(first, bad);
    final JournalCorruptionException e =
        assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME));
    assertTrue(e.getMessage().contains("segment 2 holds frames after it"), e.getMessage());
    assertArrayEquals(bad, Files.readAllBytes(first));

    // a short segment that is not the last cannot be a torn tail either
    Files.write(first, good);
    try (RandomAccessFile f = new RandomAccessFile(first.toFile(), "rw")) {
      f.setLength(CAPACITY - 8);
    }
    assertTrue(assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME)).getMessage()
        .contains("not the last segment"));

    // and a missing segment breaks the chain
    Files.write(first, good);
    Files.delete(SegmentedJournal.segmentPath(d, NAME, 2));
    assertTrue(assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME)).getMessage()
        .contains("segment 2 of journal in is missing"));
  }

  @Test
  void aTornTailInALaterSegmentIsCutThere() throws IOException {
    final Path d = dir("later");
    final int frames = 400;
    final Written w = write(d, frames);
    final int[] indices = SegmentedJournal.segmentIndices(d, NAME);
    final int lastIndex = indices[indices.length - 1];
    final Path last = SegmentedJournal.segmentPath(d, NAME, lastIndex);
    final byte[] bytes = Files.readAllBytes(last);
    int end = bytes.length;
    while (bytes[end - 1] == 0) {
      end--;
    }
    bytes[end - 1] ^= 0x01;
    Files.write(last, bytes);
    final JournalRecovery.Report report = JournalRecovery.repair(d, NAME);
    assertEquals(lastIndex, report.tornSegment());
    assertEquals(frames, report.tornSequence());
    assertEquals(frames - 1, report.frames());
    assertEquals(indices.length, report.segments());
    try (SegmentedJournal j = SegmentedJournal.open(d, NAME, CAPACITY, clock)) {
      assertEquals(frames, j.nextSequence());
      assertEquals(w.hashAfter()[frames - 1], j.chainHash());
    }
  }

  @Test
  void headersThatAreNotJournalHeadersAreRefused() throws IOException {
    final Path d = dir("headers");
    write(d, 3);
    final Path segment = SegmentedJournal.segmentPath(d, NAME, 1);
    final byte[] good = Files.readAllBytes(segment);
    final byte[] bad = good.clone();
    bad[0] ^= 0x55;
    Files.write(segment, bad);
    assertTrue(assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME)).getMessage()
        .contains("journal header"));
    Files.write(segment, java.util.Arrays.copyOf(good, 20));
    assertTrue(assertThrows(JournalCorruptionException.class, () -> JournalRecovery.repair(d, NAME)).getMessage()
        .contains("shorter than its header"));
    assertThrows(IllegalArgumentException.class, () -> JournalRecovery.repair(d, "none"));
    assertFalse(JournalRecovery.exists(d, "none"));
    assertFalse(JournalRecovery.exists(root.resolve("no-such-dir"), NAME));
    assertTrue(JournalRecovery.exists(d, NAME));
  }

  @Test
  void aCrashedPreallocatingJournalReopensWithPreallocationAndGoesOn() throws IOException {
    final Path live = dir("live");
    final Path crashed = root.resolve("crashed");
    final long hash;
    try (SegmentedJournal j = SegmentedJournal.create(live, NAME, CAPACITY, clock, true)) {
      for (int i = 1; i <= 5; i++) {
        final byte[] p = payload(i);
        j.append(i, 1, new UnsafeBuffer(p), 0, p.length);
      }
      await(j::nextSegmentReady);
      hash = j.chainHash();
      // what a crash leaves: the segments as they are now, with the prepared one, and no close
      copy(live, crashed);
    }
    Files.write(crashed.resolve(NAME + ".000003" + SegmentedJournal.SUFFIX + ".pending"), new byte[100]);
    final JournalRecovery.Report report = JournalRecovery.repair(crashed, NAME);
    assertEquals(5, report.frames());
    assertEquals(2, report.segments());
    assertEquals(1, report.pendingRemoved());
    assertFalse(report.repaired());
    assertTrue(report.describe(NAME).contains("5 frames in 2 segment(s)"), report.describe(NAME));
    assertTrue(report.describe(NAME).contains("half-prepared"));
    try (SegmentedJournal j = SegmentedJournal.open(crashed, NAME, CAPACITY, clock, true)) {
      assertEquals(6, j.nextSequence());
      assertEquals(hash, j.chainHash());
      for (int i = 6; i <= 200; i++) {
        final byte[] p = payload(i);
        assertEquals(i, j.append(i, 1, new UnsafeBuffer(p), 0, p.length));
      }
      assertTrue(j.segmentIndex() > 2);
    }
    assertEquals(200, frames(crashed));
  }

  private static void await(final BooleanSupplier done) {
    final long deadline = System.nanoTime() + 60_000_000_000L;
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "timed out");
      Thread.onSpinWait();
    }
  }
}
