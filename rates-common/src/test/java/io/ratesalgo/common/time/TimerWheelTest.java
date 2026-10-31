package io.ratesalgo.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimerWheelTest {

  private static final long TICK = 1_000L;
  private static final long START = 50_000L;

  private final List<Long> fired = new ArrayList<>();
  private final TimerHandler record = (id, now) -> fired.add(id);

  private TimerWheel wheel(final int wheelSize, final int capacity) {
    return new TimerWheel(TICK, wheelSize, capacity, START);
  }

  @Test
  void rejectsBadArguments() {
    assertThrows(IllegalArgumentException.class, () -> new TimerWheel(0, 8, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> new TimerWheel(1, 0, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> new TimerWheel(1, 8, 0, 0));
    assertThrows(NullPointerException.class, () -> wheel(8, 8).schedule(1, null));
  }

  @Test
  void firesAtFirstPollAtOrAfterDeadlineAndNeverEarly() {
    final TimerWheel w = wheel(8, 4);
    final long id = w.schedule(START + 2_500L, record);
    assertEquals(0, w.poll(START + 2_499L));
    assertTrue(fired.isEmpty());
    assertEquals(1, w.poll(START + 2_500L));
    assertEquals(List.of(id), fired);
    assertEquals(0, w.liveCount());
    assertEquals(0, w.poll(START + 10_000L));
  }

  @Test
  void firesInDeadlineThenIdOrderRegardlessOfScheduleOrder() {
    final TimerWheel w = wheel(4, 2);
    final long late = w.schedule(START + 3_000L, record);
    final long early = w.schedule(START + 1_000L, record);
    final long sameAsEarly = w.schedule(START + 1_000L, record);
    final long middle = w.schedule(START + 2_000L, record);
    assertEquals(4, w.liveCount());
    assertEquals(4, w.poll(START + 100_000L));
    assertEquals(List.of(early, sameAsEarly, middle, late), fired);
  }

  @Test
  void orderDoesNotDependOnPollFrequency() {
    final long[] deadlines = {7_300, 1_200, 1_200, 64_000, 2_000, 9_999, 33_333, 70_001};
    final List<Long> coarse = new ArrayList<>();
    final List<Long> fine = new ArrayList<>();

    final TimerWheel a = wheel(8, 4);
    for (long d : deadlines) {
      a.schedule(START + d, (id, now) -> coarse.add(id));
    }
    a.poll(START + 200_000L);

    final TimerWheel b = wheel(8, 4);
    for (long d : deadlines) {
      b.schedule(START + d, (id, now) -> fine.add(id));
    }
    for (long t = START; t <= START + 200_000L; t += 137L) {
      b.poll(t);
    }
    assertEquals(coarse, fine);
    assertEquals(deadlines.length, coarse.size());
  }

  @Test
  void deadlinesBeyondOneRotationWaitTheirTurn() {
    final TimerWheel w = wheel(4, 2); // 4 slots of 1 us: one rotation is 4 us
    final long far = w.schedule(START + 10_500L, record); // two rotations ahead
    final long near = w.schedule(START + 2_500L, record); // same slot as far after wrap? no: 3 vs 11
    for (long t = START; t < START + 10_500L; t += TICK) {
      w.poll(t);
    }
    assertEquals(List.of(near), fired);
    w.poll(START + 10_500L);
    assertEquals(List.of(near, far), fired);
  }

  @Test
  void sameSlotDifferentRotationsFireOnTheRightRotation() {
    final TimerWheel w = wheel(4, 4);
    // ticks 3, 7, 11 all land in slot 3
    final long r0 = w.schedule(START + 3 * TICK, record);
    final long r1 = w.schedule(START + 7 * TICK, record);
    final long r2 = w.schedule(START + 11 * TICK, record);
    w.poll(START + 3 * TICK);
    assertEquals(List.of(r0), fired);
    w.poll(START + 7 * TICK);
    assertEquals(List.of(r0, r1), fired);
    w.poll(START + 10 * TICK);
    assertEquals(List.of(r0, r1), fired);
    w.poll(START + 11 * TICK);
    assertEquals(List.of(r0, r1, r2), fired);
  }

  @Test
  void cancelPreventsFiringAndReportsState() {
    final TimerWheel w = wheel(8, 2);
    final long keep = w.schedule(START + 1_000L, record);
    final long drop = w.schedule(START + 1_000L, record);
    assertTrue(w.cancel(drop));
    assertFalse(w.cancel(drop));
    assertFalse(w.cancel(999_999L));
    assertEquals(1, w.liveCount());
    w.poll(START + 5_000L);
    assertEquals(List.of(keep), fired);
    assertFalse(w.cancel(keep));
  }

  @Test
  void pastDeadlinesFireOnNextPoll() {
    final TimerWheel w = wheel(8, 2);
    w.poll(START + 20_000L);
    final long id = w.schedule(START + 5_000L, record);
    assertEquals(1, w.poll(START + 20_000L));
    assertEquals(List.of(id), fired);
  }

  @Test
  void deadlineBeforeStartFiresOnFirstPoll() {
    final TimerWheel w = wheel(8, 2);
    final long id = w.schedule(START - 1_000L, record);
    assertEquals(1, w.poll(START));
    assertEquals(List.of(id), fired);
  }

  @Test
  void handlersMayScheduleAndCancelWhileFiring() {
    final TimerWheel w = wheel(8, 2);
    final long[] rescheduled = new long[1];
    final long second = w.schedule(START + 2_000L, record);
    w.schedule(
        START + 1_000L,
        (id, now) -> {
          fired.add(id);
          assertTrue(w.cancel(second));
          rescheduled[0] = w.schedule(now + 500L, record);
        });
    assertEquals(1, w.poll(START + 1_000L));
    assertEquals(1, w.liveCount());
    assertEquals(1, w.poll(START + 1_500L));
    assertEquals(2, fired.size());
    assertEquals(rescheduled[0], fired.get(1));
  }

  @Test
  void growsPastInitialCapacity() {
    final TimerWheel w = wheel(16, 2);
    for (int i = 0; i < 100; i++) {
      w.schedule(START + 1_000L + i, record);
    }
    assertEquals(100, w.liveCount());
    assertEquals(100, w.poll(START + 2_000L));
    assertEquals(100, fired.size());
    for (int i = 1; i < fired.size(); i++) {
      assertTrue(fired.get(i - 1) < fired.get(i));
    }
  }

  @Test
  void longStallWalksEveryRotation() {
    final TimerWheel w = wheel(4, 4);
    final long a = w.schedule(START + 2 * TICK, record);
    final long b = w.schedule(START + 9 * TICK, record);
    final long c = w.schedule(START + 100 * TICK, record);
    final long d = w.schedule(START + 101 * TICK, record);
    assertEquals(4, w.poll(START + 1_000 * TICK));
    assertEquals(List.of(a, b, c, d), fired);
  }

  @Test
  void wheelSizeOneWorks() {
    final TimerWheel w = new TimerWheel(TICK, 1, 2, START);
    final long a = w.schedule(START + 5 * TICK, record);
    final long b = w.schedule(START + 2 * TICK, record);
    w.poll(START + 2 * TICK);
    assertEquals(List.of(b), fired);
    w.poll(START + 5 * TICK);
    assertEquals(List.of(b, a), fired);
    assertEquals(TICK, w.tickNanos());
  }

  @Test
  void throwingHandlerDoesNotStopTheOthersAndIsReportedAfterwards() {
    final TimerWheel w = wheel(8, 4);
    final long first = w.schedule(START + 1_000L, record);
    w.schedule(
        START + 1_000L,
        (id, now) -> {
          throw new IllegalStateException("boom-1");
        });
    final long third = w.schedule(START + 1_000L, record);
    w.schedule(
        START + 2_000L,
        (id, now) -> {
          throw new IllegalStateException("boom-2");
        });
    final IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> w.poll(START + 5_000L));
    assertEquals("boom-1", e.getMessage());
    assertEquals(1, e.getSuppressed().length);
    assertEquals("boom-2", e.getSuppressed()[0].getMessage());
    assertEquals(List.of(first, third), fired);
    assertEquals(0, w.liveCount());
  }
}
