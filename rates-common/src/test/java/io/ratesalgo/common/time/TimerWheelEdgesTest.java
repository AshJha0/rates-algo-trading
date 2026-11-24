package io.ratesalgo.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * Edges of the timer wheel the main property does not reach: ties on the deadline, polls before
 * the start, the allocation-free promise for timers up to the initial capacity, and the system
 * clock's two readings.
 */
class TimerWheelEdgesTest {

  private static final long START = 5_000L;

  /** Many timers on few deadlines: every tie is broken by timer id, whatever the schedule order. */
  @Property(tries = 200, seed = "41")
  void timersOnTheSameDeadlineFireInIdOrder(
      @ForAll @Size(min = 1, max = 60) final List<@IntRange(min = 0, max = 6) Integer> slots,
      @ForAll @IntRange(min = 1, max = 9) final int tick) {
    final TimerWheel wheel = new TimerWheel(tick, 4, 2, START);
    final List<long[]> fired = new ArrayList<>();
    for (final int s : slots) {
      final long deadline = START + s * 3L;
      wheel.schedule(deadline, (id, now) -> fired.add(new long[] {deadline, id}));
    }
    for (long now = START; now <= START + 30; now += 7) {
      wheel.poll(now);
    }
    assertEquals(slots.size(), fired.size());
    for (int i = 1; i < fired.size(); i++) {
      final long[] a = fired.get(i - 1);
      final long[] b = fired.get(i);
      assertTrue(a[0] < b[0] || (a[0] == b[0] && a[1] < b[1]), "order at " + i);
    }
  }

  @Test
  void aPollBeforeTheStartFiresOnlyWhatIsAlreadyDue() {
    final TimerWheel wheel = new TimerWheel(10, 8, 4, START);
    final List<Long> fired = new ArrayList<>();
    wheel.schedule(START - 20, (id, now) -> fired.add(id));
    final long later = wheel.schedule(START + 15, (id, now) -> fired.add(id));
    assertEquals(1, wheel.poll(START - 5));
    assertEquals(List.of(1L), fired);
    assertEquals(0, wheel.poll(START + 14));
    assertEquals(1, wheel.poll(START + 15));
    assertEquals(List.of(1L, later), fired);
  }

  /**
   * Scheduling, cancelling and firing up to the initial capacity allocates nothing. Measured with
   * the thread's allocation counter after a warm-up on another wheel, so it does not depend on time.
   */
  @Test
  void timersUpToTheInitialCapacityAllocateNothing() {
    final com.sun.management.ThreadMXBean mx =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final TimerHandler h = (id, now) -> { };
    exercise(new TimerWheel(10, 8, 4, START), h); // warm-up: class loading and first calls
    final TimerWheel wheel = new TimerWheel(10, 8, 4, START);
    final long before = mx.getCurrentThreadAllocatedBytes();
    final long fired = exercise(wheel, h);
    final long allocated = mx.getCurrentThreadAllocatedBytes() - before;
    assertEquals(1_500L, fired);
    assertTrue(allocated < 256, "allocated " + allocated + " bytes");
  }

  private static long exercise(final TimerWheel wheel, final TimerHandler h) {
    long fired = 0;
    long now = START;
    for (int round = 0; round < 500; round++) {
      for (int i = 0; i < 3; i++) {
        wheel.schedule(now + 10 + i, h);
      }
      final long cancelled = wheel.schedule(now + 100, h);
      wheel.cancel(cancelled); // four live at most: the capacity
      now += 20;
      fired += wheel.poll(now);
    }
    return fired;
  }

  @Test
  void theSystemClockReadsTheWallClockAndAMovingMonotonicTime() {
    final SystemClock clock = SystemClock.INSTANCE;
    final long a = clock.nanoTime();
    final Instant before = Instant.now();
    final long wall = clock.epochNanos();
    final Instant after = Instant.now();
    final long b = clock.nanoTime();
    assertTrue(b >= a && a != 0, "monotonic");
    final long lo = before.getEpochSecond() * 1_000_000_000L + before.getNano();
    final long hi = after.getEpochSecond() * 1_000_000_000L + after.getNano();
    assertTrue(lo <= wall && wall <= hi, lo + " <= " + wall + " <= " + hi);
  }
}
