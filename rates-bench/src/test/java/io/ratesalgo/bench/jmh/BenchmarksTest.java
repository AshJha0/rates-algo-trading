package io.ratesalgo.bench.jmh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The benchmark bodies run as plain methods, so a broken benchmark fails the build, not the JMH run. */
class BenchmarksTest {

  @Test
  void ringPublishThenReadReadsOneRecord() {
    final RingBufferPublishBenchmark b = new RingBufferPublishBenchmark();
    b.setUp();
    for (int i = 0; i < 100_000; i++) {
      assertEquals(1, b.publishThenRead());
    }
  }

  @Test
  void journalAppendReplacesAFullFile() throws Exception {
    final JournalAppendBenchmark b = new JournalAppendBenchmark();
    b.capacity = 4096;
    b.setUp();
    long last = 0;
    for (int i = 0; i < 200; i++) {
      last = b.append();
    }
    assertTrue(last < 200, "sequence restarts in the replacement file");
    assertTrue(b.remaining() < 4096);
    b.tearDown();
  }

  @Test
  void journalModeAppendRunsInBothModesWithinOneSegment() throws Exception {
    for (final String mode : new String[] {"direct", "ring"}) {
      final JournalModeAppendBenchmark b = new JournalModeAppendBenchmark();
      b.mode = mode;
      b.segmentBytes = 1 << 20;
      b.ringBytes = 1 << 16;
      b.payload();
      for (int iteration = 0; iteration < 2; iteration++) {
        b.openJournal();
        for (int i = 1; i <= 1000; i++) {
          assertEquals(i, b.append());
        }
        assertEquals(1, b.segmentIndex());
        b.closeJournal();
      }
    }
  }

  @Test
  void journalModeAppendRefusesAnIterationThatRolled() throws Exception {
    final JournalModeAppendBenchmark b = new JournalModeAppendBenchmark();
    b.mode = "direct";
    b.segmentBytes = 4096;
    b.payload();
    b.openJournal();
    for (int i = 0; i < 100; i++) {
      b.append();
    }
    assertThrows(IllegalStateException.class, b::closeJournal);
  }

  @Test
  void theThreeFixEncodingsGiveTheSameBytes() {
    final FixEncodeBenchmark b = new FixEncodeBenchmark();
    b.setUp();
    b.reset(5, 1_793_712_300_000_000_000L);
    final int before = b.before();
    final String legacy = b.out().getStringWithoutLengthAscii(0, before);
    b.reset(5, 1_793_712_300_000_000_000L);
    assertEquals(before, b.copy());
    assertEquals(legacy, b.out().getStringWithoutLengthAscii(0, before));
    b.reset(5, 1_793_712_300_000_000_000L);
    assertEquals(before, b.inPlace());
    assertEquals(legacy, b.writer().buffer().getStringWithoutLengthAscii(b.writer().messageOffset(), before));
    assertTrue(legacy.startsWith("8=FIX.4.49=") && legacy.contains("10="));
  }

  @Test
  void theChainHashBenchmarkChainsEachAlgorithm() {
    final ChainHashBenchmark b = new ChainHashBenchmark();
    b.length = 57;
    b.setUp();
    final long a = b.fnv1a();
    assertTrue(a != b.fnv1a(), "each call chains to the last");
    final long w = b.word64();
    assertTrue(w != b.word64());
    final long c = b.crc32c();
    assertTrue(c != b.crc32c());
  }
}
