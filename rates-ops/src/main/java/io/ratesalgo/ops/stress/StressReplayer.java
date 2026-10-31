package io.ratesalgo.ops.stress;

import io.ratesalgo.common.time.Clock;
import io.ratesalgo.journal.JournalReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * The RTS 6 Article 10 stress test (API_SPEC.md Section 17.13): replay a journal into a sink with
 * its timeline compressed so that the busiest window runs at {@code multiplier} times its recorded
 * rate (twice by default), and record the sink's acceptance rate and latency.
 *
 * <p>Each frame has an intended send time {@code start + (ts - ts0) / multiplier}. Latency is
 * measured from the intended time, not from the moment the harness got round to sending, so a
 * sink that stalls is charged for the whole stall (coordinated omission corrected, as in
 * docs/BENCHMARKS.md). Frames are copied into memory before the run so that reading the file does
 * not disturb the schedule.
 */
public final class StressReplayer {

  /** One frame held in memory. */
  private static final class Frame {
    private final long timestampNanos;
    private final int templateId;
    private final byte[] payload;

    Frame(final long timestampNanos, final int templateId, final byte[] payload) {
      this.timestampNanos = timestampNanos;
      this.templateId = templateId;
      this.payload = payload;
    }

    long timestampNanos() {
      return timestampNanos;
    }

    int templateId() {
      return templateId;
    }

    byte[] payload() {
      return payload;
    }
  }

  /**
   * The busiest window of a journal.
   *
   * @param frames frames in the journal
   * @param peakCount most frames in any window of {@code windowNanos}
   * @param windowNanos the window
   */
  public record Peak(long frames, int peakCount, long windowNanos) {
    /** Peak rate in messages per second. */
    public double perSecond() {
      return peakCount * 1e9 / windowNanos;
    }
  }

  private final Clock clock;
  private final Pacer pacer;

  public StressReplayer(final Clock clock, final Pacer pacer) {
    this.clock = clock;
    this.pacer = pacer;
  }

  private static List<Frame> load(final Path journal) {
    final List<Frame> frames = new ArrayList<>();
    try (JournalReader r = JournalReader.open(journal)) {
      while (r.next((s, t, id, v, b, o, l) -> {
        final byte[] copy = new byte[l];
        b.getBytes(o, copy);
        frames.add(new Frame(t, id, copy));
      })) {
        // loading
      }
    }
    return frames;
  }

  /** The busiest window of {@code windowNanos} in a journal, by frame timestamps. */
  public static Peak peak(final Path journal, final long windowNanos) {
    return peak(load(journal), windowNanos);
  }

  private static Peak peak(final List<Frame> frames, final long windowNanos) {
    if (windowNanos <= 0) {
      throw new IllegalArgumentException("window must be positive");
    }
    int best = 0;
    int lo = 0;
    for (int hi = 0; hi < frames.size(); hi++) {
      while (frames.get(hi).timestampNanos() - frames.get(lo).timestampNanos() >= windowNanos) {
        lo++;
      }
      best = Math.max(best, hi - lo + 1);
    }
    return new Peak(frames.size(), best, windowNanos);
  }

  /** Runs the stress test. */
  public StressResult run(final Path journal, final MessageSink sink, final double multiplier, final long windowNanos) {
    if (!(multiplier > 0)) {
      throw new IllegalArgumentException("multiplier must be positive: " + multiplier);
    }
    final List<Frame> frames = load(journal);
    final Peak source = peak(frames, windowNanos);
    final long[] latencies = new long[frames.size()];
    final UnsafeBuffer buffer = new UnsafeBuffer(new byte[0]);
    long accepted = 0;
    final long start = clock.nanoTime();
    final long ts0 = frames.isEmpty() ? 0 : frames.get(0).timestampNanos();
    long end = start;
    for (int i = 0; i < frames.size(); i++) {
      final Frame f = frames.get(i);
      final long intended = start + (long) ((f.timestampNanos() - ts0) / multiplier);
      pacer.awaitNanoTime(intended);
      buffer.wrap(f.payload());
      if (sink.offer(f.templateId(), buffer, 0, f.payload().length)) {
        accepted++;
      }
      end = clock.nanoTime();
      latencies[i] = end - intended;
    }
    Arrays.sort(latencies);
    return new StressResult(frames.size(), accepted, source.perSecond(), source.perSecond() * multiplier,
        multiplier, percentile(latencies, 0.50), percentile(latencies, 0.99), percentile(latencies, 0.999),
        latencies.length == 0 ? 0 : latencies[latencies.length - 1], end - start);
  }

  private static long percentile(final long[] sorted, final double q) {
    if (sorted.length == 0) {
      return 0;
    }
    final int index = (int) Math.ceil(q * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
  }
}
