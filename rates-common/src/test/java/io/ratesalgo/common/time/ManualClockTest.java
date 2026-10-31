package io.ratesalgo.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManualClockTest {

  @Test
  void startsAtGivenWallTimeAndUnrelatedMonotonicOrigin() {
    final ManualClock clock = new ManualClock(123_456_789L);
    assertEquals(123_456_789L, clock.epochNanos());
    assertEquals(1_000_000_000L, clock.nanoTime());
  }

  @Test
  void defaultOriginIs2026() {
    final ManualClock clock = new ManualClock();
    assertEquals(1_767_225_600_000_000_000L, clock.epochNanos());
  }

  @Test
  void advanceMovesBothReadingsTogether() {
    final ManualClock clock = new ManualClock(10L);
    clock.advance(5L);
    assertEquals(15L, clock.epochNanos());
    assertEquals(1_000_000_005L, clock.nanoTime());
  }

  @Test
  void advanceByZeroIsAllowed() {
    final ManualClock clock = new ManualClock(10L);
    clock.advance(0L);
    assertEquals(10L, clock.epochNanos());
  }

  @Test
  void clockNeverRunsBackwards() {
    final ManualClock clock = new ManualClock(10L);
    assertThrows(IllegalArgumentException.class, () -> clock.advance(-1L));
    assertThrows(IllegalArgumentException.class, () -> clock.setEpochNanos(9L));
  }

  @Test
  void setEpochNanosMovesMonotonicBySameAmount() {
    final ManualClock clock = new ManualClock(100L);
    clock.setEpochNanos(170L);
    assertEquals(170L, clock.epochNanos());
    assertEquals(1_000_000_070L, clock.nanoTime());
  }

  @Test
  void systemClockReadsForward() {
    final long n1 = SystemClock.INSTANCE.nanoTime();
    final long e1 = SystemClock.INSTANCE.epochNanos();
    final long n2 = SystemClock.INSTANCE.nanoTime();
    final long e2 = SystemClock.INSTANCE.epochNanos();
    assertTrue(n2 >= n1);
    assertTrue(e2 >= e1);
    // 2026-01-01 as a sanity floor for wall time on any machine running this build
    assertTrue(e1 > 1_767_225_600_000_000_000L);
  }
}
