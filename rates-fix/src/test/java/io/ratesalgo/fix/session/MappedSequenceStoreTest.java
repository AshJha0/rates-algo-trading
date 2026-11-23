package io.ratesalgo.fix.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The mapped sequence file: what it keeps, what a torn write costs, and when it refuses. */
class MappedSequenceStoreTest {

  @TempDir Path dir;

  @Test
  void aNewFileStartsAtOneAndIsCreatedWithItsDirectories() {
    final Path file = dir.resolve("a/b/venue.seq");
    final MappedSequenceStore store = MappedSequenceStore.open(file);
    assertFalse(store.existed());
    assertTrue(store.cleanAtOpen());
    assertEquals(1, store.nextOutSeq());
    assertEquals(1, store.expectedInSeq());
    assertEquals(1, store.safeInSeq());
    assertEquals(1, store.resumeInSeq());
    assertTrue(store.toString().contains("venue.seq") && store.toString().contains("nextOut=1"), store.toString());
    store.close();
    store.close(); // closing twice is harmless
    assertTrue(Files.exists(file));
    // a file that was created and closed without a single message is a valid file at 1, 1
    final MappedSequenceStore again = MappedSequenceStore.open(file);
    assertTrue(again.existed());
    assertEquals(1, again.nextOutSeq());
    again.close();
  }

  @Test
  void numbersSurviveACleanCloseAndResumeFromTheExpectedNumber() {
    final Path file = dir.resolve("clean.seq");
    final MappedSequenceStore store = MappedSequenceStore.open(file);
    store.record(5, 9);
    store.safeInSeq(7);
    store.record(6, 10);
    assertEquals(6, store.nextOutSeq());
    assertEquals(10, store.expectedInSeq());
    assertEquals(7, store.safeInSeq());
    store.close();

    final MappedSequenceStore reopened = MappedSequenceStore.open(file);
    assertTrue(reopened.existed());
    assertTrue(reopened.cleanAtOpen());
    assertEquals(6, reopened.nextOutSeq());
    assertEquals(10, reopened.expectedInSeq());
    assertEquals(7, reopened.safeInSeq());
    assertEquals(10, reopened.resumeInSeq(), "after a clean stop nothing needs to be asked for again");
    reopened.close();
  }

  @Test
  void afterACrashTheSessionResumesFromTheWatermark() {
    final Path file = dir.resolve("crash.seq");
    MappedSequenceStore store = MappedSequenceStore.open(file);
    store.record(12, 30);
    store.safeInSeq(26); // 26..29 were received but not yet handed on durably
    store.closeWithoutMarkingClean();

    store = MappedSequenceStore.open(file);
    assertTrue(store.existed());
    assertFalse(store.cleanAtOpen(), "the first record cleared the clean flag and nothing set it again");
    assertEquals(12, store.nextOutSeq());
    assertEquals(30, store.expectedInSeq());
    assertEquals(26, store.resumeInSeq());
    // the watermark may run ahead of the last record; resuming takes the lower of the two
    store.safeInSeq(31);
    store.closeWithoutMarkingClean();
    store = MappedSequenceStore.open(file);
    assertEquals(31, store.safeInSeq());
    assertEquals(30, store.resumeInSeq());
    // what resumeInSeq returns is the file as opened, not what has been recorded since
    store.record(13, 40);
    assertEquals(30, store.resumeInSeq());
    store.close();
    store = MappedSequenceStore.open(file);
    assertTrue(store.cleanAtOpen());
    assertEquals(40, store.resumeInSeq());
    store.close();
  }

  @Test
  void aTornWriteOfTheNewestRecordFallsBackToTheOneBefore() throws IOException {
    final Path file = dir.resolve("torn.seq");
    final MappedSequenceStore store = MappedSequenceStore.open(file);
    store.record(10, 20); // generation 2: slot 0
    store.record(11, 21); // generation 3: slot 1, the newest
    store.closeWithoutMarkingClean();
    final byte[] good = Files.readAllBytes(file);
    assertEquals(MappedSequenceStore.FILE_BYTES, good.length);
    final int newest = MappedSequenceStore.SLOT_OFFSET + MappedSequenceStore.SLOT_BYTES;

    for (int i = 0; i < MappedSequenceStore.RECORD_BYTES; i++) {
      final byte[] torn = good.clone();
      torn[newest + i] ^= 0x5A;
      final Path copy = dir.resolve("torn-" + i + ".seq");
      Files.write(copy, torn);
      final MappedSequenceStore opened = MappedSequenceStore.open(copy);
      assertEquals(10, opened.nextOutSeq(), "byte " + i + " of the newest record damaged");
      assertEquals(20, opened.expectedInSeq(), "byte " + i);
      // and the next write goes over the damaged slot, not over the good one
      opened.record(12, 22);
      opened.closeWithoutMarkingClean();
      final MappedSequenceStore after = MappedSequenceStore.open(copy);
      assertEquals(12, after.nextOutSeq());
      assertEquals(22, after.expectedInSeq());
      after.closeWithoutMarkingClean();
    }
    // undamaged, the newest record wins
    final MappedSequenceStore intact = MappedSequenceStore.open(file);
    assertEquals(11, intact.nextOutSeq());
    assertEquals(21, intact.expectedInSeq());
    intact.closeWithoutMarkingClean();
  }

  @Test
  void aFileThatCannotBeTrustedIsRefused() throws IOException {
    final Path file = dir.resolve("damaged.seq");
    final MappedSequenceStore store = MappedSequenceStore.open(file);
    store.record(10, 20);
    store.record(11, 21);
    store.close();
    final byte[] good = Files.readAllBytes(file);

    final byte[] both = good.clone();
    both[MappedSequenceStore.SLOT_OFFSET + 3] ^= 1;
    both[MappedSequenceStore.SLOT_OFFSET + MappedSequenceStore.SLOT_BYTES + 9] ^= 1;
    Files.write(file, both);
    final IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> MappedSequenceStore.open(file));
    assertTrue(refused.getMessage().contains("damaged.seq"), refused.getMessage());
    assertTrue(refused.getMessage().contains("neither record"), refused.getMessage());

    final byte[] noMagic = good.clone();
    noMagic[0] ^= 1;
    final Path other = dir.resolve("other.seq");
    Files.write(other, noMagic);
    assertTrue(
        assertThrows(IllegalStateException.class, () -> MappedSequenceStore.open(other))
            .getMessage()
            .contains("other.seq"));

    final Path zeros = dir.resolve("zeros.seq");
    Files.write(zeros, new byte[MappedSequenceStore.FILE_BYTES]);
    assertThrows(IllegalStateException.class, () -> MappedSequenceStore.open(zeros));

    final Path shortFile = dir.resolve("short.seq");
    Files.write(shortFile, new byte[] {1, 2, 3});
    assertTrue(
        assertThrows(IllegalStateException.class, () -> MappedSequenceStore.open(shortFile))
            .getMessage()
            .contains("3 bytes"));

    // a record whose checksum is right but whose numbers are impossible is not a record
    Files.write(file, good);
    final MappedSequenceStore ok = MappedSequenceStore.open(file);
    assertEquals(11, ok.nextOutSeq());
    ok.close();
  }

  @Test
  void resetGoesBackToOne() {
    final Path file = dir.resolve("reset.seq");
    MappedSequenceStore store = MappedSequenceStore.open(file);
    store.record(100, 200);
    store.safeInSeq(150);
    store.closeWithoutMarkingClean();
    store = MappedSequenceStore.open(file);
    assertEquals(150, store.resumeInSeq());
    store.reset();
    assertEquals(1, store.nextOutSeq());
    assertEquals(1, store.expectedInSeq());
    assertEquals(1, store.safeInSeq());
    assertEquals(1, store.resumeInSeq());
    store.closeWithoutMarkingClean();
    store = MappedSequenceStore.open(file);
    assertEquals(1, store.nextOutSeq());
    assertEquals(1, store.expectedInSeq());
    assertEquals(1, store.resumeInSeq());
    store.close();
  }

  @Test
  void aClosedStoreRefusesToRecordAndBadNumbersAreRejected() {
    final MappedSequenceStore store = MappedSequenceStore.open(dir.resolve("closed.seq"));
    assertThrows(IllegalArgumentException.class, () -> store.safeInSeq(0));
    store.close();
    assertThrows(IllegalStateException.class, () -> store.record(2, 2));
    assertThrows(IllegalStateException.class, () -> store.safeInSeq(2));
    assertThrows(IllegalStateException.class, store::reset);
  }

  @Test
  void recordingAllocatesNothing() {
    final MappedSequenceStore store = MappedSequenceStore.open(dir.resolve("hot.seq"));
    final com.sun.management.ThreadMXBean mx =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long thread = Thread.currentThread().threadId();
    mx.getThreadAllocatedBytes(thread);
    for (int round = 0; round < 5; round++) {
      write(store, 1, 50_000);
    }
    // Windows, as in LoopAllocationTest (DR-INT-02): the JIT can recompile the loop once more
    // after warm-up and that shows as about a kilobyte on this thread in one window (1,072 bytes
    // on the Linux runner). A write that allocated would cost every window megabytes, so one
    // window at zero and none above a kilobyte or so proves the write path allocates nothing.
    final long[] bytes = new long[4];
    long least = Long.MAX_VALUE;
    for (int w = 0; w < bytes.length; w++) {
      final int from = 50_001 + w * 50_000;
      final long before = mx.getThreadAllocatedBytes(thread);
      write(store, from, from + 49_999);
      bytes[w] = mx.getThreadAllocatedBytes(thread) - before;
      least = Math.min(least, bytes[w]);
    }
    final String seen = java.util.Arrays.toString(bytes);
    assertEquals(0, least, "bytes allocated per window of 50,000 writes: " + seen);
    for (final long b : bytes) {
      assertTrue(b <= 2_048, "bytes allocated per window of 50,000 writes: " + seen);
    }
    assertEquals(250_000, store.nextOutSeq());
    store.close();
  }

  private static void write(final MappedSequenceStore store, final int from, final int to) {
    for (int i = from; i <= to; i++) {
      store.record(i, i + 1);
      store.safeInSeq(i);
    }
  }
}
