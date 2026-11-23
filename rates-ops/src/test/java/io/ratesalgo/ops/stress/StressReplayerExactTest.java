package io.ratesalgo.ops.stress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.posttrade.chaos.JournalScript;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stress harness on a four-frame journal whose every figure is worked out by hand: the peak
 * window, the paced send times at twice the rate, the payload each frame carries, and the latency
 * percentiles of a sink that takes 0.7 ms per message.
 */
class StressReplayerExactTest {

  private static final long T0 = 1_800_000_000_000_000_000L;
  private static final long MS = 1_000_000L;

  @TempDir Path dir;

  private Path journal() {
    final Path j = dir.resolve("four.raj");
    try (JournalScript s = new JournalScript(j, 1 << 16, T0)) {
      s.raw(T0, 7, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
      s.raw(T0 + MS, 8, new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
      s.raw(T0 + 2 * MS, 7, new byte[16]);
      s.raw(T0 + 10 * MS, 9, new byte[8]);
    }
    return j;
  }

  @Test
  void thePeakWindowExcludesAFrameExactlyOneWindowLater() {
    final Path j = journal();
    final StressReplayer.Peak three = StressReplayer.peak(j, 3 * MS);
    assertEquals(4L, three.frames());
    assertEquals(3, three.peakCount(), "0, 1 and 2 ms fall inside one 3 ms window");
    assertEquals(1_000.0, three.perSecond(), 1e-9, "3 frames in 3 ms");
    assertEquals(2, StressReplayer.peak(j, 2 * MS).peakCount(), "0 and 2 ms are a full window apart");
    assertEquals(1, StressReplayer.peak(j, 1).peakCount());
  }

  @Test
  void framesAreSentOnTheScaledScheduleWithTheirOwnBytesAndLatencyIsMeasuredFromTheSchedule() {
    final ManualClock clock = new ManualClock(0);
    final long start = clock.nanoTime();
    final Pacer pacer = target -> {
      if (target > clock.nanoTime()) {
        clock.advance(target - clock.nanoTime());
      }
    };
    final List<String> seen = new ArrayList<>();
    final MessageSink sink = (templateId, b, o, l) -> {
      final StringBuilder bytes = new StringBuilder();
      for (int i = 0; i < l; i++) {
        bytes.append(b.getByte(o + i));
      }
      seen.add(templateId + "@" + (clock.nanoTime() - start) + ":" + l + ":" + bytes);
      clock.advance(700_000L);
      return templateId != 8;
    };
    final StressResult r = new StressReplayer(clock, pacer).run(journal(), sink, 2.0, 3 * MS);
    assertEquals(List.of(
        "7@0:8:12345678",
        "8@700000:12:999999999999",
        "7@1400000:16:0000000000000000",
        "9@5000000:8:00000000"), seen);
    assertEquals(4L, r.frames());
    assertEquals(3L, r.accepted());
    assertEquals(1_000.0, r.sourcePeakPerSecond(), 1e-9);
    assertEquals(2_000.0, r.targetPeakPerSecond(), 1e-9);
    // latencies from the schedule 0, 0.5, 1 and 5 ms: 0.7, 0.9, 1.1 and 0.7 ms
    assertEquals(700_000L, r.p50Nanos());
    assertEquals(1_100_000L, r.p99Nanos());
    assertEquals(1_100_000L, r.p999Nanos());
    assertEquals(1_100_000L, r.maxNanos());
    assertEquals(5_700_000L, r.durationNanos());
  }

  @Test
  void percentilesOfManyLatenciesPickTheRankAtOrAboveTheQuantile() {
    final ManualClock clock = new ManualClock(0);
    final Path j = dir.resolve("many.raj");
    try (JournalScript s = new JournalScript(j, 1 << 20, T0)) {
      for (int i = 0; i < 1_000; i++) {
        s.raw(T0, 7, new byte[8]); // all scheduled at once: latency grows by 1 us per message
      }
    }
    final StressResult r = new StressReplayer(clock, t -> { })
        .run(j, (id, b, o, l) -> {
          clock.advance(1_000L);
          return true;
        }, 1.0, MS);
    assertEquals(500_000L, r.p50Nanos(), "the 500th of 1, 2, ... 1000 us");
    assertEquals(990_000L, r.p99Nanos());
    assertEquals(999_000L, r.p999Nanos());
    assertEquals(1_000_000L, r.maxNanos());
  }

  @Test
  void anEmptyJournalReportsZeros() {
    final Path j = dir.resolve("empty.raj");
    new JournalScript(j, 1 << 16, T0).close(); // nothing written
    final StressResult r = new StressReplayer(new ManualClock(0), t -> { }).run(j, (id, b, o, l) -> true, 2.0, MS);
    assertEquals(0L, r.frames());
    assertEquals(0L, r.p50Nanos());
    assertEquals(0L, r.maxNanos());
  }
}
