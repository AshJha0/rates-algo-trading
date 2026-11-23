package io.ratesalgo.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.junit.jupiter.api.Test;

/**
 * Core pinning through the foreign function API and the duty-cycle runner the process's gateway
 * threads use (DECISIONS.md DR-R6-L01, DR-R6-L02). The pinning assertions that need Linux run on
 * the CI runner; elsewhere the test checks that pinning is reported as not done.
 */
class ThreadPlacementTest {

  private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

  private static void await(final BooleanSupplier done, final String what) {
    final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() - deadline < 0, "not within a minute: " + what);
      Thread.onSpinWait();
    }
  }

  private static <T> T onNewThread(final java.util.function.Supplier<T> body) throws InterruptedException {
    final AtomicReference<T> out = new AtomicReference<>();
    final Thread t = new Thread(() -> out.set(body.get()), "placement-probe");
    t.start();
    t.join();
    return out.get();
  }

  @Test
  void onLinuxAThreadPinnedToACpuMayRunOnThatCpuAlone() throws InterruptedException {
    final int[] allowed = CoreAffinity.allowedCpus();
    if (!LINUX) {
      assertEquals(0, allowed.length, "no affinity off Linux");
      assertFalse(onNewThread(() -> CoreAffinity.pin(0)));
      return;
    }
    assertTrue(allowed.length > 0, "the process may run somewhere");
    final int target = allowed[allowed.length - 1];
    final int[] after = onNewThread(() -> CoreAffinity.pin(target) ? CoreAffinity.allowedCpus() : null);
    assertArrayEquals(new int[] {target}, after, "sched_getaffinity after sched_setaffinity");
    assertArrayEquals(allowed, CoreAffinity.allowedCpus(), "only the pinned thread moved");
  }

  @Test
  void pinningIsRefusedForCpusOutsideTheSetAndSkippedElsewhere() throws InterruptedException {
    assertFalse(CoreAffinity.pin(-1, "Linux"));
    assertFalse(CoreAffinity.pin(CoreAffinity.CPU_SET_BYTES * 8, "Linux"), "beyond a cpu_set_t");
    assertFalse(CoreAffinity.pin(0, "Mac OS X"));
    assertEquals(0, CoreAffinity.allowedCpus("Windows 11").length);
    if (LINUX) {
      // a CPU the machine does not have: the kernel refuses with EINVAL
      assertFalse(onNewThread(() -> CoreAffinity.pin(CoreAffinity.CPU_SET_BYTES * 8 - 1)));
    } else {
      assertFalse(onNewThread(() -> CoreAffinity.pin(0, "Linux")), "the symbol does not exist here");
    }
  }

  @Test
  void parkIsTheGatewayThreadsOldPause() {
    assertTrue(IdleStrategies.of("park") instanceof SleepingIdleStrategy);
    assertEquals(200_000L, IdleStrategies.PARK_NANOS);
    assertTrue(IdleStrategies.NAMES.contains("park"));
  }

  @Test
  void aRunnerCyclesBeatsAndStops() {
    final long[] calls = new long[1];
    final DutyCycleRunner r =
        DutyCycleRunner.builder("placement-runner", () -> {
          calls[0]++;
          return 0;
        }).idleStrategy(IdleStrategies.of("yield")).cpu(-1).daemon(true).closeTimeoutMillis(10_000L).build().start();
    await(() -> r.beats() > 100, "duty cycles");
    assertTrue(r.isRunning() && r.isAlive());
    assertFalse(r.isPinned());
    assertFalse(r.isCurrentThread());
    assertEquals("placement-runner", r.name());
    assertTrue(r.threadId() > 0);
    assertTrue(r.stop(), "the thread ends at the end of its cycle");
    assertFalse(r.isRunning());
    assertNull(r.failure());
    r.close();
    assertThrows(IllegalArgumentException.class, () -> DutyCycleRunner.builder("x", () -> 0).closeTimeoutMillis(0));
  }

  @Test
  void aFailureIsKeptAndHandedToTheHookOnTheRunnersOwnThread() throws InterruptedException {
    final IllegalStateException boom = new IllegalStateException("boom");
    final AtomicReference<Throwable> seen = new AtomicReference<>();
    final AtomicReference<Boolean> ownThread = new AtomicReference<>();
    final CountDownLatch hooked = new CountDownLatch(1);
    final DutyCycleRunner[] runner = new DutyCycleRunner[1];
    runner[0] =
        DutyCycleRunner.builder("placement-fail", () -> {
          throw boom;
        }).onFailure(t -> {
          seen.set(t);
          ownThread.set(runner[0].isCurrentThread());
          hooked.countDown();
          throw new IllegalArgumentException("hook failed too");
        }).build();
    runner[0].start();
    assertTrue(hooked.await(1, TimeUnit.MINUTES));
    await(() -> !runner[0].isAlive(), "the failed thread ends");
    assertSame(boom, runner[0].failure());
    assertSame(boom, seen.get());
    assertTrue(ownThread.get(), "the hook runs on the thread that owned the work");
    assertEquals(1, boom.getSuppressed().length, "a failing hook is kept with the failure");
    assertFalse(runner[0].isRunning());
  }
}
