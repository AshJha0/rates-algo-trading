package io.ratesalgo.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.ratesalgo.common.time.ManualClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RingJournalTest {

  @TempDir Path dir;

  private static UnsafeBuffer payload(final int seed, final int length) {
    final byte[] b = new byte[length];
    for (int i = 0; i < length; i++) {
      b[i] = (byte) (seed * 31 + i);
    }
    return new UnsafeBuffer(b);
  }

  /** Every frame as (sequence, timestamp, template, version, hash, payload bytes). */
  private static List<Object[]> frames(final Path dir, final String name) {
    final List<Object[]> out = new ArrayList<>();
    try (SegmentedJournalReader r = SegmentedJournalReader.open(dir, name)) {
      while (r.next(
          (s, t, id, v, b, o, l) -> {
            final byte[] bytes = new byte[l];
            b.getBytes(o, bytes);
            out.add(new Object[] {new long[] {s, 0L}, t, id, v, bytes});
          })) {
        ((long[]) out.get(out.size() - 1)[0])[1] = r.chainHash();
      }
    }
    return out;
  }

  private static void assertSameFrames(final List<Object[]> expected, final List<Object[]> actual) {
    assertEquals(expected.size(), actual.size(), "frame count");
    for (int i = 0; i < expected.size(); i++) {
      final Object[] e = expected.get(i);
      final Object[] a = actual.get(i);
      assertArrayEquals((long[]) e[0], (long[]) a[0], "sequence and chain hash of frame " + i);
      assertEquals(e[1], a[1], "timestamp of frame " + i);
      assertEquals(e[2], a[2], "template id of frame " + i);
      assertEquals(e[3], a[3], "schema version of frame " + i);
      assertArrayEquals((byte[]) e[4], (byte[]) a[4], "payload of frame " + i);
    }
  }

  @Property(tries = 20)
  void ringModeWritesTheSameFramesAsTheDirectMode(
      @ForAll @IntRange(min = 1, max = 300) final int count,
      @ForAll @IntRange(min = 1024, max = 8192) final int segment,
      @ForAll @IntRange(min = 0, max = 200) final int maxPayload)
      throws Exception {
    // jqwik does not inject @TempDir into properties
    final Path d = Files.createTempDirectory("ringprop");
    try {
      final ManualClock directClock = new ManualClock(1_000L);
      final ManualClock ringClock = new ManualClock(1_000L);
      final int cap = Math.min(maxPayload, segment - 100);
      try (SegmentedJournal direct = SegmentedJournal.create(d, "direct", segment, directClock, true);
          RingJournal ring =
              RingJournal.start(
                  "ring", SegmentedJournal.create(d, "ring", segment, ringClock, true), ringClock, 64 << 10)) {
        for (int i = 0; i < count; i++) {
          directClock.advance(7L * (i % 3));
          ringClock.advance(7L * (i % 3));
          final int length = cap == 0 ? 0 : (i * 13) % (cap + 1);
          final UnsafeBuffer p = payload(i, length);
          final int template = i % 5 == 0 ? 65002 : i % 7;
          final long expected = direct.append(template, i % 2, p, 0, length);
          assertEquals(expected, ring.append(template, i % 2, p, 0, length));
        }
      }
      assertSameFrames(frames(d, "direct"), frames(d, "ring"));
    } finally {
      try (Stream<Path> files = Files.list(d)) {
        for (final Path p : files.toList()) {
          Files.delete(p);
        }
      }
      Files.delete(d);
    }
  }

  @Test
  void everyFrameArrivesThroughARingSmallerThanTheStream() {
    final ManualClock clock = new ManualClock();
    final int n = 20_000;
    try (RingJournal ring =
        RingJournal.start("small", SegmentedJournal.create(dir, "small", 1 << 20, clock, true), clock, 64 << 10)) {
      final UnsafeBuffer p = payload(3, 200);
      for (int i = 0; i < n; i++) {
        clock.advance(1L);
        assertEquals(i + 1L, ring.append(10, 0, p, 0, p.capacity()));
      }
      ring.awaitDrained(1, TimeUnit.MINUTES);
      assertEquals(0, ring.backlog());
    }
    // 20,000 frames of 248 bytes are 75 times the 64 KiB ring: whether or not the producer had to
    // wait (ring.producerWaits()), every frame arrives, in order
    final List<Object[]> written = frames(dir, "small");
    assertEquals(n, written.size());
    assertEquals(n, ((long[]) written.get(n - 1)[0])[0]);
  }

  @Test
  void aFrameLargerThanAnEmptySegmentIsRefusedOnTheProducerThread() {
    final ManualClock clock = new ManualClock();
    try (RingJournal ring =
        RingJournal.start("big", SegmentedJournal.create(dir, "big", 1024, clock, true), clock, 64 << 10)) {
      final UnsafeBuffer p = payload(1, 2000);
      assertThrows(JournalFullException.class, () -> ring.append(10, 0, p, 0, 2000));
      assertThrows(IllegalArgumentException.class, () -> ring.append(10, 0, p, 0, -1));
      assertEquals(1L, ring.append(10, 0, p, 0, 100));
    }
    assertEquals(1, frames(dir, "big").size());
  }

  @Test
  void appendAfterCloseIsRefusedAndTheRingSizeIsChecked() {
    final ManualClock clock = new ManualClock();
    final RingJournal ring =
        RingJournal.start("c", SegmentedJournal.create(dir, "c", 4096, clock, true), clock, 64 << 10);
    ring.close();
    ring.close();
    assertThrows(IllegalStateException.class, () -> ring.append(10, 0, payload(0, 8), 0, 8));
    try (SegmentedJournal d = SegmentedJournal.create(dir, "d", 4096, clock)) {
      assertThrows(IllegalArgumentException.class, () -> RingJournal.start("d", d, clock, 100_000));
      assertThrows(IllegalArgumentException.class, () -> RingJournal.start("d", d, clock, 1 << 15));
    }
  }
}
