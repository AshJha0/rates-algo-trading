package io.ratesalgo.ops.stress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.posttrade.chaos.SyntheticDay;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stress harness drives the busiest window at twice its recorded rate and charges a slow sink
 * for its stalls (coordinated omission corrected). Time is a ManualClock, so the run is exact and
 * instant.
 */
class StressReplayerTest {

  @TempDir Path dir;

  @Test
  void twiceThePeakAndCorrectedLatency() {
    final Path journal = dir.resolve("day.raj");
    SyntheticDay.generate(journal, 5L, SyntheticDay.Params.DEFAULT);
    final StressReplayer.Peak peak = StressReplayer.peak(journal, 1_000_000_000L);
    assertTrue(peak.peakCount() >= 3, () -> "peak " + peak);

    final ManualClock clock = new ManualClock(0);
    final Pacer pacer = target -> {
      if (target > clock.nanoTime()) {
        clock.advance(target - clock.nanoTime());
      }
    };
    final int[] seen = {0};
    // a sink that takes 1 ms per message and refuses every tenth
    final MessageSink slow = (id, b, o, l) -> {
      clock.advance(1_000_000L);
      return ++seen[0] % 10 != 0;
    };
    final StressResult r = new StressReplayer(clock, pacer).run(journal, slow, 2.0, 1_000_000_000L);
    assertEquals(peak.frames(), r.frames());
    assertEquals(r.frames() - r.frames() / 10, r.accepted());
    assertEquals(peak.perSecond() * 2, r.targetPeakPerSecond(), 1e-9);
    assertEquals(2.0, r.multiplier(), 0.0);
    // the burst frames are 1 ms apart in the journal, 0.5 ms apart at 2x: the 1 ms sink falls behind
    assertTrue(r.maxNanos() > 1_000_000L, r::summary);
    assertTrue(r.p50Nanos() >= 1_000_000L, r::summary);
    assertTrue(r.p99Nanos() <= r.p999Nanos() && r.p999Nanos() <= r.maxNanos());
    assertTrue(r.acceptanceRate() < 1.0 && r.acceptanceRate() > 0.85, r::summary);
    assertTrue(r.summary().startsWith("frames="));
    assertTrue(r.durationNanos() > 0);
  }

  @Test
  void fastSinkAcceptsEverythingOnSchedule() {
    final Path journal = dir.resolve("d.raj");
    SyntheticDay.generate(journal, 6L, SyntheticDay.Params.DEFAULT);
    final ManualClock clock = new ManualClock(0);
    final StressResult r = new StressReplayer(clock, t -> {
      if (t > clock.nanoTime()) {
        clock.advance(t - clock.nanoTime());
      }
    }).run(journal, (id, b, o, l) -> true, 2.0, 1_000_000_000L);
    assertEquals(1.0, r.acceptanceRate(), 0.0);
    assertEquals(0, r.maxNanos());
    assertThrows(IllegalArgumentException.class,
        () -> new StressReplayer(clock, t -> { }).run(journal, (id, b, o, l) -> true, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> StressReplayer.peak(journal, 0));
    assertEquals(1.0, new StressResult(0, 0, 0, 0, 2, 0, 0, 0, 0, 0).acceptanceRate(), 0.0);
  }

  @Test
  void spinningPacerWaitsForRealTime() {
    final io.ratesalgo.common.time.Clock clock = io.ratesalgo.common.time.SystemClock.INSTANCE;
    final long target = clock.nanoTime() + 2_000_000L;
    Pacer.spinning(clock).awaitNanoTime(target);
    assertTrue(clock.nanoTime() >= target);
  }
}
