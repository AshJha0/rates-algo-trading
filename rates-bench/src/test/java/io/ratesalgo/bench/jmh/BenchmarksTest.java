package io.ratesalgo.bench.jmh;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
