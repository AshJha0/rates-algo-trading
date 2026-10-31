package io.ratesalgo.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.router.sim.SimConfig;
import io.ratesalgo.router.sim.SimMarket;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * The per-order path allocates nothing once warmed up: submitting parents, cost evaluation and
 * allocation, decision encoding, child orders, ExecutionReports, book updates, timers,
 * escalation and completion, measured with {@code ThreadMXBean.getThreadAllocatedBytes} on the
 * thread that runs them. The in-memory venues run on the same thread and are allocation-free too.
 */
class RouterAllocationTest {

  private static final long MM = RouterPresets.ONE_MILLION;

  private static final com.sun.management.ThreadMXBean BEAN =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  /** Bytes this thread has allocated; the no-argument form does not itself allocate. */
  private static long allocatedBytes() {
    return BEAN.getCurrentThreadAllocatedBytes();
  }

  private static void runParents(
      final SmartOrderRouter router, final SimMarket market, final ManualClock clock,
      final SimConfig config, final long firstId, final int count) {
    for (long id = firstId; id < firstId + count; id++) {
      final Side side = (id & 1) == 0 ? Side.Buy : Side.Sell;
      final double urgency = (id % 3) * 0.5;
      router.submit(id, 10, side, (10 + id % 30) * MM, urgency, 0L);
      for (int i = 0; i < 20_000 && router.isActive(id); i++) {
        clock.advance(config.stepNanos());
        market.step();
        market.drain(router);
        router.poll();
      }
      if (router.isActive(id)) {
        fail("parent " + id + " incomplete");
      }
    }
  }

  @Test
  void perOrderPathIsAllocationFreeAfterWarmUp() {
    final ManualClock clock = new ManualClock();
    final SimConfig config = SimConfig.ust10yThreeVenues();
    final SimMarket market = new SimMarket(config, 99L, clock);
    final long[] frames = new long[1];
    final FrameSink counting = (templateId, version, buffer, offset, length) -> frames[0]++;
    final SmartOrderRouter router =
        new SmartOrderRouter(
            RouterPresets.global(),
            config.routerVenues(0.25, 5),
            market,
            market,
            counting,
            clock,
            RandomSource.seeded(17L),
            8,
            256,
            1L);
    // Warm-up: run at least 50 windows of 20 parents, and then until five in a row allocate
    // nothing. Measured on
    // 2026-10-31 (JDK 25.0.5, Windows 11): under -Xint and under C1 alone the path allocates
    // nothing after the first window; under C2 a few tens of bytes appear in isolated windows
    // during the first 400 or so parents, while methods are compiled and recompiled, and then
    // stop. Escape analysis off changes nothing, so these are not elided allocations in our code.
    // After the warm-up the measured windows must allocate exactly nothing. They run inside the
    // same loop and call site as the warm-up (DR-INT-08): a separate measurement loop is compiled
    // afresh, and its first pass (on-stack replacement) allocated 256 bytes on the Windows CI
    // runner while the per-order code allocated nothing, as DeskQuoterAllocationTest recorded.
    // The last MEASURED windows (60 parents) must each be exactly zero.
    final int measured = 3;
    long id = 1;
    int quiet = 0;
    int windows = 0;
    long lastBytes = 0;
    while (quiet < 5 + measured || windows < 50) {
      if (++windows > 150) {
        fail("the router did not reach an allocation-free steady state in 3000 parents");
      }
      final long start = allocatedBytes();
      runParents(router, market, clock, config, id, 20);
      id += 20;
      lastBytes = allocatedBytes() - start;
      quiet = lastBytes == 0 ? quiet + 1 : 0;
    }
    assertTrue(quiet >= 5 + measured, "bytes allocated on the per-order path after warm-up");
    assertEquals(0L, lastBytes, "bytes allocated on the per-order path after warm-up");
    System.out.println(
        "RouterAllocationTest: "
            + router.decisions()
            + " decisions, "
            + frames[0]
            + " frames, the last "
            + quiet
            + " windows of 20 parents allocated nothing, after "
            + windows
            + " windows");
  }
}
