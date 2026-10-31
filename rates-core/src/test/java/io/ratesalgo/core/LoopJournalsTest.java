package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.journal.SegmentedJournal;
import io.ratesalgo.journal.SegmentedJournalReader;
import java.nio.file.Path;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LoopJournalsTest {

  @TempDir Path dir;

  private static long count(final Path dir, final String name) {
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, name)) {
      long n = 0;
      while (r.next((s, t, id, v, b, o, l) -> {})) {
        n++;
      }
      return n;
    }
  }

  @ParameterizedTest
  @EnumSource(JournalMode.class)
  void bothJournalsTakeFramesInEitherMode(final JournalMode mode) {
    final EventClock clock = new EventClock(new ManualClock());
    final UnsafeBuffer p = new UnsafeBuffer(new byte[40]);
    try (LoopJournals j = LoopJournals.create(dir, "in", "out", 4096, clock, mode, 1 << 16)) {
      assertEquals(mode, j.mode());
      for (int i = 1; i <= 300; i++) {
        assertEquals(i, j.inbound().append(1, 0, p, 0, 40));
        assertEquals(i, j.outbound().append(2, 0, p, 0, 40));
      }
      assertTrue(j.rollWaits() >= 0);
      if (mode == JournalMode.DIRECT) {
        assertEquals(0L, j.producerWaits());
      }
    }
    assertEquals(300, count(dir, "in"));
    assertEquals(300, count(dir, "out"));
  }

  @Test
  void theDefaultModeIsDirect() {
    assertEquals(JournalMode.DIRECT, LoopJournals.DEFAULT_MODE);
    try (LoopJournals j = LoopJournals.create(dir, "in", "out", 1 << 16, new EventClock(new ManualClock()))) {
      assertEquals(JournalMode.DIRECT, j.mode());
    }
  }

  @Test
  void aJournalThatFailsToOpenLeavesNothingOpen() {
    final EventClock clock = new EventClock(new ManualClock());
    // a ring smaller than 64 KiB is refused; the segmented journal under it is closed again
    assertThrows(
        IllegalArgumentException.class,
        () -> LoopJournals.create(dir, "in", "out", 4096, clock, JournalMode.RING, 1024));
    assertEquals(1, SegmentedJournal.segmentIndices(dir, "in").length);
    // the outbound name is taken by the inbound journal just opened, which is closed again
    assertThrows(
        IllegalArgumentException.class,
        () -> LoopJournals.create(dir, "a", "a", 4096, clock, JournalMode.DIRECT));
  }
}
