package io.ratesalgo.core;

import io.ratesalgo.journal.ChainHash;
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

  /**
   * How a loop's journals are written (API_SPEC.md Section 9.7).
   *
   * @param mode direct or ring
   * @param ringCapacity bytes of each ring in {@link JournalMode#RING}; ignored in direct mode
   * @param hashAlgorithm the {@link ChainHash} of new journals; opened journals keep theirs
   * @param journalIdle the journal threads' idle strategy in ring mode, a name {@link
   *     IdleStrategies#of} accepts
   * @param journalStallNanos how long the loop waits on a full ring with no progress before it
   *     fails (DECISIONS.md DR-R6-L04)
   * @param segmentOptions page locking and the refault window of every segment
   */
  public record Settings(
      JournalMode mode,
      int ringCapacity,
      int hashAlgorithm,
      String journalIdle,
      long journalStallNanos,
      SegmentedJournal.Options segmentOptions) {

    /**
     * What a loop always had: direct mode, FNV-1a, 64 MiB rings and a backoff journal thread if
     * ring mode is chosen, a minute's stall limit, the default segment options.
     */
    public static final Settings DEFAULT =
        new Settings(DEFAULT_MODE, RingJournal.DEFAULT_RING_CAPACITY, ChainHash.DEFAULT, "backoff",
            RingJournal.STALL_NANOS, SegmentedJournal.Options.DEFAULT);

    /** Validates the settings. */
    public Settings {
      IdleStrategies.of(journalIdle);
      if (!ChainHash.isKnown(hashAlgorithm)) {
        throw new IllegalArgumentException("unknown chain hash algorithm " + hashAlgorithm);
      }
      if (journalStallNanos <= 0) {
        throw new IllegalArgumentException("journal stall limit must be positive");
      }
    }

    /** These settings in another mode. */
    public Settings withMode(final JournalMode other) {
      return new Settings(other, ringCapacity, hashAlgorithm, journalIdle, journalStallNanos, segmentOptions);
    }

    /** These settings with another chain hash for new journals. */
    public Settings withHashAlgorithm(final int algorithm) {
      return new Settings(mode, ringCapacity, algorithm, journalIdle, journalStallNanos, segmentOptions);
    }

    /** These settings with another ring size and journal-thread idle strategy. */
    public Settings withRing(final int capacity, final String idle) {
      return new Settings(mode, capacity, hashAlgorithm, idle, journalStallNanos, segmentOptions);
    }

    /** These settings with another stall limit on a full ring. */
    public Settings withStallNanos(final long nanos) {
      return new Settings(mode, ringCapacity, hashAlgorithm, journalIdle, nanos, segmentOptions);
    }

    /** These settings with other segment options. */
    public Settings withSegmentOptions(final SegmentedJournal.Options options) {
      return new Settings(mode, ringCapacity, hashAlgorithm, journalIdle, journalStallNanos, options);
    }

    SegmentedJournal.Options segments() {
      return segmentOptions.withHashAlgorithm(hashAlgorithm);
    }
  }

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
    return create(dir, inboundName, outboundName, segmentBytes, clock,
        Settings.DEFAULT.withMode(mode).withRing(ringCapacity, Settings.DEFAULT.journalIdle()));
  }

  /**
   * Creates journals {@code inboundName} and {@code outboundName} in {@code dir} with {@code
   * settings}.
   *
   * @throws IllegalArgumentException if either journal already exists
   */
  public static LoopJournals create(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock,
      final Settings settings) {
    final Sink in = Sink.create(dir, inboundName, segmentBytes, clock, settings, null);
    boolean opened = false;
    try {
      final LoopJournals journals =
          new LoopJournals(
              settings.mode(), in, Sink.create(dir, outboundName, segmentBytes, clock, settings, in.ring));
      opened = true;
      return journals;
    } finally {
      if (!opened) {
        in.close();
      }
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

  /**
   * Opens journals {@code inboundName} and {@code outboundName} that already exist in {@code dir}
   * and continues them after their last frames, for a process that restarts where it stopped. Both
   * chains are verified on the way. A journal left by a crash is repaired first with {@code
   * JournalRecovery.repair}; this method refuses a torn tail.
   *
   * @throws IllegalArgumentException if either journal does not exist
   * @throws io.ratesalgo.journal.JournalCorruptionException if a chain is broken
   */
  public static LoopJournals open(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock,
      final JournalMode mode,
      final int ringCapacity) {
    return open(dir, inboundName, outboundName, segmentBytes, clock,
        Settings.DEFAULT.withMode(mode).withRing(ringCapacity, Settings.DEFAULT.journalIdle()));
  }

  /**
   * Opens existing journals with {@code settings}; their chain hash is the one their files name.
   *
   * @throws IllegalArgumentException if either journal does not exist
   * @throws io.ratesalgo.journal.JournalCorruptionException if a chain is broken
   */
  public static LoopJournals open(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock,
      final Settings settings) {
    final Sink in = Sink.open(dir, inboundName, segmentBytes, clock, settings, null);
    boolean opened = false;
    try {
      final LoopJournals journals =
          new LoopJournals(settings.mode(), in, Sink.open(dir, outboundName, segmentBytes, clock, settings, in.ring));
      opened = true;
      return journals;
    } finally {
      if (!opened) {
        in.close();
      }
    }
  }

  /** Opens existing journals in {@link #DEFAULT_MODE}. */
  public static LoopJournals open(
      final Path dir,
      final String inboundName,
      final String outboundName,
      final int segmentBytes,
      final EventClock clock) {
    return open(dir, inboundName, outboundName, segmentBytes, clock, DEFAULT_MODE, RingJournal.DEFAULT_RING_CAPACITY);
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
   * Waits until both rings are written to their files, for tests and tools that read the files of a
   * live loop; returns at once in direct mode. Never call on the loop thread.
   *
   * @throws IllegalStateException if a journal thread failed or the wait timed out
   */
  public void awaitDrained(final long timeout, final java.util.concurrent.TimeUnit unit) {
    if (inbound.ring != null) {
      inbound.ring.awaitDrained(timeout, unit);
    }
    if (outbound.ring != null) {
      outbound.ring.awaitDrained(timeout, unit);
    }
  }

  /** The larger of the two rings' backlog high-water marks, bytes; 0 in direct mode. */
  public long backlogHighWater() {
    return Math.max(inbound.backlogHighWater(), outbound.backlogHighWater());
  }

  /** The chain hash both journals are written with. */
  public int hashAlgorithm() {
    return inbound.segmented.hashAlgorithm();
  }

  /** Segments locked in memory and segments whose lock was refused, over both journals. */
  public long[] pageLocks() {
    final long[] in = inbound.segmented.pageLocks();
    final long[] out = outbound.segmented.pageLocks();
    return new long[] {in[0] + out[0], in[1] + out[1]};
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

    // the outbound sink follows the inbound ring: an outbound frame never reaches its file before
    // the inbound frame that caused it, so a crash in ring mode leaves journals a restart can
    // replay (RingJournal, DR-R6-L04)
    static Sink create(final Path dir, final String name, final int segmentBytes, final EventClock clock,
        final Settings settings, final RingJournal leader) {
      return wrap(name, SegmentedJournal.create(dir, name, segmentBytes, clock, true, settings.segments()), clock,
          settings, leader);
    }

    static Sink open(final Path dir, final String name, final int segmentBytes, final EventClock clock,
        final Settings settings, final RingJournal leader) {
      return wrap(name, SegmentedJournal.open(dir, name, segmentBytes, clock, true, settings.segments()), clock,
          settings, leader);
    }

    private static Sink wrap(final String name, final SegmentedJournal journal, final EventClock clock,
        final Settings settings, final RingJournal leader) {
      if (settings.mode() == JournalMode.DIRECT) {
        return new Sink(journal, null);
      }
      boolean started = false;
      try {
        final Sink sink =
            new Sink(journal, RingJournal.start(name, journal, clock, settings.ringCapacity(),
                IdleStrategies.of(settings.journalIdle()), settings.journalStallNanos(), leader));
        started = true;
        return sink;
      } finally {
        if (!started) {
          journal.close();
        }
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

    long backlogHighWater() {
      return ring == null ? 0L : ring.backlogHighWater();
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
