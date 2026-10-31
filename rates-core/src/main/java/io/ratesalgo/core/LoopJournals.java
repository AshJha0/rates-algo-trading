package io.ratesalgo.core;

import io.ratesalgo.journal.RingJournal;
import io.ratesalgo.journal.SegmentedJournal;
import java.nio.file.Path;
import org.agrona.DirectBuffer;

/**
 * A live loop's inbound and outbound journals (DECISIONS.md DR-P2C-01) in one {@link JournalMode},
 * opened together and closed together. Give them to the loop with {@link
 * EventLoop.Builder#journals(LoopJournals)}; {@link LoopReplay#run(Path, String, String,
 * LoopFactory)} reads the files of either mode.
 *
 * <p>Both journals are {@link SegmentedJournal}s with preallocation on: every page of the current
 * segment is touched before the loop starts and the next segment is prepared on a background
 * thread (DR-R2J-02). In {@link JournalMode#RING} each journal also has a {@link RingJournal} and
 * its journal thread (DR-R2J-03).
 *
 * <p>Build with the loop's {@link EventClock}: frames carry its latched time in both modes.
 */
public final class LoopJournals implements AutoCloseable {

  /** Default segment size: 512 MiB, as the latency harness uses. */
  public static final int DEFAULT_SEGMENT_BYTES = 512 << 20;

  /**
   * The mode a loop uses unless it chooses: {@link JournalMode#DIRECT}, which met the P2 latency
   * target with journaling on (DECISIONS.md DR-R2J-04).
   */
  public static final JournalMode DEFAULT_MODE = JournalMode.DIRECT;

  private final JournalMode mode;
  private final Sink inbound;
  private final Sink outbound;

  private LoopJournals(final JournalMode mode, final Sink inbound, final Sink outbound) {
    this.mode = mode;
    this.inbound = inbound;
    this.outbound = outbound;
  }

  /**
   * Creates journals {@code inboundName} and {@code outboundName} in {@code dir}.
   *
   * @param ringCapacity bytes of each ring in {@link JournalMode#RING}; ignored in {@link
   *     JournalMode#DIRECT}
   * @throws IllegalArgumentException if either journal already exists
   */
  public static LoopJournals create(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock,
      final JournalMode mode,
      final int ringCapacity) {
    final Sink in = Sink.create(dir, inboundName, segmentBytes, clock, mode, ringCapacity);
    try {
      return new LoopJournals(
          mode, in, Sink.create(dir, outboundName, segmentBytes, clock, mode, ringCapacity));
    } catch (final RuntimeException e) {
      in.close();
      throw e;
    }
  }

  /** Creates journals in {@link #DEFAULT_MODE}. */
  public static LoopJournals create(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock) {
    return create(dir, inboundName, outboundName, segmentBytes, clock, DEFAULT_MODE);
  }

  /** Creates journals with {@link RingJournal#DEFAULT_RING_CAPACITY} rings. */
  public static LoopJournals create(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock,
      final JournalMode mode) {
    return create(dir, inboundName, outboundName, segmentBytes, clock, mode, RingJournal.DEFAULT_RING_CAPACITY);
  }

  /** The mode both journals run in. */
  public JournalMode mode() {
    return mode;
  }

  /** The inbound journal's sink. */
  public JournalSink inbound() {
    return inbound;
  }

  /** The outbound journal's sink. */
  public JournalSink outbound() {
    return outbound;
  }

  /** Rolls, over both journals, that waited for a prepared segment. Readable from any thread. */
  public long rollWaits() {
    return inbound.rollWaits() + outbound.rollWaits();
  }

  /** Appends, over both journals, that waited for room in a full ring; 0 in direct mode. */
  public long producerWaits() {
    return inbound.producerWaits() + outbound.producerWaits();
  }

  /**
   * Closes both journals: in ring mode each ring is drained first. Call after the loop thread has
   * stopped.
   */
  @Override
  public void close() {
    try {
      inbound.close();
    } finally {
      outbound.close();
    }
  }

  /** One journal in one mode. */
  private static final class Sink implements JournalSink, AutoCloseable {

    private final SegmentedJournal segmented;
    private final RingJournal ring;

    private Sink(final SegmentedJournal segmented, final RingJournal ring) {
      this.segmented = segmented;
      this.ring = ring;
    }

    static Sink create(
        final Path dir,
        final String name,
        final int segmentBytes,
        final EventClock clock,
        final JournalMode mode,
        final int ringCapacity) {
      final SegmentedJournal journal = SegmentedJournal.create(dir, name, segmentBytes, clock, true);
      if (mode == JournalMode.DIRECT) {
        return new Sink(journal, null);
      }
      try {
        return new Sink(journal, RingJournal.start(name, journal, clock, ringCapacity));
      } catch (final RuntimeException e) {
        journal.close();
        throw e;
      }
    }

    @Override
    public long append(
        final int templateId,
        final int schemaVersion,
        final DirectBuffer buffer,
        final int offset,
        final int length) {
      return ring == null
          ? segmented.append(templateId, schemaVersion, buffer, offset, length)
          : ring.append(templateId, schemaVersion, buffer, offset, length);
    }

    long rollWaits() {
      return segmented.rollWaits();
    }

    long producerWaits() {
      return ring == null ? 0L : ring.producerWaits();
    }

    @Override
    public void close() {
      if (ring == null) {
        segmented.close();
      } else {
        ring.close();
      }
    }
  }
}
