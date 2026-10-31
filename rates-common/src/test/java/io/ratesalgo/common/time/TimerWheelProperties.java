package io.ratesalgo.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Properties the wheel must hold for every schedule and every polling pattern. */
class TimerWheelProperties {

  private static final long START = 1_000_000L;

  @Provide
  Arbitrary<List<Long>> deadlines() {
    return Arbitraries.longs().between(-5_000L, 500_000L).list().ofMinSize(0).ofMaxSize(200);
  }

  @Provide
  Arbitrary<Long> tick() {
    return Arbitraries.of(1L, 7L, 64L, 1_000L, 4_096L);
  }

  @Provide
  Arbitrary<Integer> wheelSize() {
    return Arbitraries.of(1, 2, 3, 8, 64, 1_000);
  }

  @Property(tries = 300)
  void everyTimerFiresExactlyOnceInDeadlineThenIdOrderAndNeverEarly(
      @ForAll("deadlines") final List<Long> deadlines,
      @ForAll("tick") final long tick,
      @ForAll("wheelSize") final int wheelSize,
      @ForAll("pollStep") final long pollStep) {
    final TimerWheel wheel = new TimerWheel(tick, wheelSize, 4, START);
    final List<long[]> firedAt = new ArrayList<>();
    final long[] ids = new long[deadlines.size()];
    for (int i = 0; i < ids.length; i++) {
      final long deadline = START + deadlines.get(i);
      ids[i] = wheel.schedule(deadline, (id, now) -> firedAt.add(new long[] {id, now, deadline}));
    }
    final long horizon = START + 600_000L;
    int total = 0;
    for (long now = START; now <= horizon; now += pollStep) {
      total += wheel.poll(now);
    }
    assertEquals(deadlines.size(), total);
    assertEquals(deadlines.size(), firedAt.size());
    assertEquals(0, wheel.liveCount());
    for (int i = 0; i < firedAt.size(); i++) {
      final long[] f = firedAt.get(i);
      assertTrue(f[1] >= f[2], "fired early: now=" + f[1] + " deadline=" + f[2]);
      if (i > 0) {
        final long[] p = firedAt.get(i - 1);
        final boolean ordered = p[2] < f[2] || (p[2] == f[2] && p[0] < f[0]);
        assertTrue(ordered, "out of order at " + i);
      }
    }
  }

  @Provide
  Arbitrary<Long> pollStep() {
    return Arbitraries.of(1L, 3L, 100L, 999L, 50_000L, 10_000_000L);
  }
}
