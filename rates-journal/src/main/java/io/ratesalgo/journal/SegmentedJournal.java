package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.FILE_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.GENESIS_HASH;
import static io.ratesalgo.journal.JournalFormat.alignedFrameLength;

import io.ratesalgo.common.time.Clock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.agrona.DirectBuffer;

/**
 * A journal made of equal-size segment files that rolls to the next segment when the current one
 * cannot hold the next frame (API_SPEC.md Section 1.5).
 *
 * <p>Segment {@code k} of journal {@code name} in directory {@code dir} is the file {@code
 * name.00000k.raj}, numbered from 1 with six digits. Each segment is an ordinary RAJ1 file. The
 * sequence and the chain hash continue across segments: the first frame of segment {@code k + 1}
 * carries the sequence after the last frame of segment {@code k} and chains to its hash. So a
 * segment read alone fails the chain check unless the reader is told where the previous segment
 * ended, which {@link SegmentedJournalReader} does by walking the segments in order.
 *
 * <p>Creating a segment writes {@code segmentCapacity} zero bytes, which takes hundreds of
 * milliseconds for a 512 MiB segment, and the first store to each page of a fresh mapping is a page
 * fault. With preallocation on ({@link #create(Path, String, int, Clock, boolean)}), the appending
 * thread does neither (DECISIONS.md DR-R2J-02):
 *
 * <ul>
 *   <li>{@code create} touches every page of the first segment before it returns;
 *   <li>a background thread ({@code journal-prepare-<name>}) creates the next segment as soon as
 *       the current one becomes current, under a temporary name ({@code name.00000k.raj.pending})
 *       that it renames atomically once the file is complete and forced, then maps it and touches
 *       every page;
 *   <li>a roll takes the prepared writer through a volatile field, hands the full segment back to
 *       the background thread, which forces and unmaps it, and allocates nothing;
 *   <li>if the next segment is not ready when the current one is full, the roll waits for it and
 *       counts the wait ({@link #rollWaits()}).
 * </ul>
 *
 * <p>The appending thread never calls {@code force}, never maps or unmaps a file and never takes a
 * lock. The header stamp of a prepared segment is the time it was created, not the time of the
 * roll. An unused prepared segment is deleted on {@link #close()}; one left behind by a crash is an
 * empty segment, which readers and {@link #open} accept.
 *
 * <p>Without preallocation, {@link #append} rolls by creating the next segment itself, which maps a
 * file and allocates; that mode is for tests and tools with small segments.
 */
public final class SegmentedJournal implements AutoCloseable {

  /** File suffix of every segment. */
  public static final String SUFFIX = ".raj";

  /** Largest segment index; six decimal digits. */
  public static final int MAX_SEGMENTS = 999_999;

  private static final long PREPARER_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

  private final Path dir;
  private final String name;
  private final int segmentCapacity;
  private final Clock clock;

  private final Preparer preparer;

  private JournalWriter current;
  private int segmentIndex;
  private boolean closed;

  private SegmentedJournal(
      final Path dir,
      final String name,
      final int segmentCapacity,
      final Clock clock,
      final JournalWriter current,
      final int segmentIndex,
      final boolean preallocate) {
    this.dir = dir;
    this.name = name;
    this.segmentCapacity = segmentCapacity;
    this.clock = clock;
    this.current = current;
    this.segmentIndex = segmentIndex;
    this.preparer = preallocate ? new Preparer(segmentIndex) : null;
    if (preparer != null) {
      current.pretouch();
      preparer.thread.start();
    }
  }

  /** The path of segment {@code index} (1-based) of journal {@code name} in {@code dir}. */
  public static Path segmentPath(final Path dir, final String name, final int index) {
    checkName(name);
    if (index < 1 || index > MAX_SEGMENTS) {
      throw new IllegalArgumentException("segment index out of range: " + index);
    }
    return dir.resolve(String.format(Locale.ROOT, "%s.%06d%s", name, index, SUFFIX));
  }

  /**
   * The segment indices of journal {@code name} present in {@code dir}, ascending. A gap in the
   * numbering is not checked here; {@link SegmentedJournalReader} reports it as corruption.
   */
  public static int[] segmentIndices(final Path dir, final String name) {
    checkName(name);
    final Pattern pattern = Pattern.compile(Pattern.quote(name) + "\\.(\\d{6})" + Pattern.quote(SUFFIX));
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .map(p -> pattern.matcher(String.valueOf(p.getFileName())))
          .filter(Matcher::matches)
          .mapToInt(m -> Integer.parseInt(m.group(1)))
          .filter(i -> i >= 1)
          .sorted()
          .toArray();
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot list journal directory " + dir, e);
    }
  }

  /**
   * Creates a new segmented journal with its first segment.
   *
   * @throws IllegalArgumentException if any segment of {@code name} already exists in {@code dir}
   */
  public static SegmentedJournal create(
      final Path dir, final String name, final int segmentCapacity, final Clock clock) {
    return create(dir, name, segmentCapacity, clock, false);
  }

  /**
   * Creates a new segmented journal; with {@code preallocate} every page of the first segment is
   * touched before this returns and each next segment is prepared on a background thread before
   * it is needed.
   *
   * @throws IllegalArgumentException if any segment of {@code name} already exists in {@code dir}
   */
  public static SegmentedJournal create(
      final Path dir,
      final String name,
      final int segmentCapacity,
      final Clock clock,
      final boolean preallocate) {
    if (segmentIndices(dir, name).length != 0) {
      throw new IllegalArgumentException("journal " + name + " already exists in " + dir);
    }
    final JournalWriter first =
        JournalWriter.create(segmentPath(dir, name, 1), segmentCapacity, clock, 1L, GENESIS_HASH);
    return new SegmentedJournal(dir, name, segmentCapacity, clock, first, 1, preallocate);
  }

  /**
   * Opens an existing segmented journal for appending after its last frame, verifying every
   * segment's chain on the way. New segments get {@code segmentCapacity}.
   *
   * @throws IllegalArgumentException if no segment exists
   * @throws JournalCorruptionException if a segment is missing or a chain breaks
   */
  public static SegmentedJournal open(
      final Path dir, final String name, final int segmentCapacity, final Clock clock) {
    final int[] indices = segmentIndices(dir, name);
    if (indices.length == 0) {
      throw new IllegalArgumentException("journal " + name + " not found in " + dir);
    }
    final int last = indices[indices.length - 1];
    final long nextSequence;
    final long hash;
    try (SegmentedJournalReader reader = SegmentedJournalReader.open(dir, name, last - 1)) {
      while (reader.next((s, t, id, v, b, o, l) -> {})) {
        // verifying every earlier segment
      }
      nextSequence = reader.expectedSequence();
      hash = reader.chainHash();
    }
    final JournalWriter writer =
        JournalWriter.open(segmentPath(dir, name, last), clock, nextSequence, hash);
    return new SegmentedJournal(dir, name, segmentCapacity, clock, writer, last, false);
  }

  /**
   * Appends one frame stamped with the journal's clock, rolling to a new segment first if the
   * current one cannot hold it.
   *
   * @return the sequence number assigned to the frame
   * @throws JournalFullException if the frame is larger than an empty segment can hold
   */
  public long append(
      final int templateId,
      final int schemaVersion,
      final DirectBuffer payload,
      final int offset,
      final int length) {
    return append(templateId, schemaVersion, clock.epochNanos(), payload, offset, length);
  }

  /**
   * Appends one frame stamped with {@code timestampNanos}, rolling to a new segment first if the
   * current one cannot hold it.
   *
   * @return the sequence number assigned to the frame
   * @throws JournalFullException if the frame is larger than an empty segment can hold
   */
  public long append(
      final int templateId,
      final int schemaVersion,
      final long timestampNanos,
      final DirectBuffer payload,
      final int offset,
      final int length) {
    ensureOpen();
    final int frameLength = alignedFrameLength(Math.max(length, 0));
    if (frameLength > current.remaining()) {
      final int emptyRoom = segmentCapacity - FILE_HEADER_LENGTH - Integer.BYTES;
      if (frameLength > emptyRoom) {
        throw new JournalFullException(frameLength, emptyRoom);
      }
      roll();
    }
    return current.append(templateId, schemaVersion, timestampNanos, payload, offset, length);
  }

  private void roll() {
    if (segmentIndex == MAX_SEGMENTS) {
      throw new IllegalStateException("journal " + name + " has used every segment index");
    }
    final long nextSequence = current.nextSequence();
    final long hash = current.chainHash();
    if (preparer != null) {
      final JournalWriter next = preparer.take();
      next.continueChain(nextSequence, hash);
      preparer.retire(current);
      current = next;
      segmentIndex++;
      preparer.becameCurrent(segmentIndex);
      return;
    }
    current.close();
    segmentIndex++;
    current =
        JournalWriter.create(
            segmentPath(dir, name, segmentIndex), segmentCapacity, clock, nextSequence, hash);
  }

  /** The sequence the next appended frame will get. */
  public long nextSequence() {
    return current.nextSequence();
  }

  /** The chain hash after the last appended frame, across all segments. */
  public long chainHash() {
    return current.chainHash();
  }

  /** The 1-based index of the segment being written. */
  public int segmentIndex() {
    return segmentIndex;
  }

  /** Capacity of each new segment in bytes. */
  public int segmentCapacity() {
    return segmentCapacity;
  }

  /**
   * Rolls that found the next segment not yet prepared and waited for it; 0 without preallocation.
   * Readable from any thread.
   */
  public long rollWaits() {
    return preparer == null ? 0L : preparer.rollWaits.get();
  }

  /**
   * A count that rises while the background thread works (pages touched, segments created and
   * closed); 0 without preallocation. For shutdown paths that wait on this journal (DR-INT-10).
   */
  public long preparerProgress() {
    return preparer == null ? 0L : preparer.progress();
  }

  /** How long a shutdown wait tolerates no progress at all before it gives up (DR-INT-10). */
  static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(60);

  /** How often a shutdown wait checks for progress. */
  static final long JOIN_STEP_MILLIS = 100L;

  /** Forces the current segment to the file. Not called on the hot path. */
  public void sync() {
    ensureOpen();
    current.sync();
  }

  /**
   * Closes the journal: forces and unmaps the current segment on the calling thread, stops the
   * background thread after it has closed any full segment it holds, and deletes an unused
   * prepared segment.
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      current.close();
      if (preparer != null) {
        preparer.stop();
      }
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("segmented journal is closed");
    }
  }

  private static void checkName(final String name) {
    if (name == null
        || name.isEmpty()
        || name.chars().anyMatch(c -> c == '/' || c == '\\' || c == ':')
        || ".".equals(name)
        || "..".equals(name)) {
      throw new IllegalArgumentException("invalid journal name: " + name);
    }
  }

  /**
   * The background thread that prepares the next segment and retires full ones. The appending
   * thread and this thread exchange writers through three volatile fields, so neither side
   * allocates or locks on the exchange.
   */
  private final class Preparer implements Runnable {

    final Thread thread;

    /** The prepared next segment, mapped and touched; written here, taken by the appender. */
    private volatile JournalWriter ready;

    /** A full segment for this thread to force and unmap; written by the appender. */
    private volatile JournalWriter retiring;

    /** The index of the segment being appended to; written by the appender at each roll. */
    private volatile int currentIndex;

    private volatile boolean stopping;
    private volatile Throwable failure;
    /** Written by the appender only; read from any thread. */
    private final AtomicLong rollWaits = new AtomicLong();
    /** Pages this thread has touched and segments it has created or closed; read from any thread. */
    private final AtomicLong progress = new AtomicLong();

    Preparer(final int currentIndex) {
      this.currentIndex = currentIndex;
      this.thread = new Thread(this, "journal-prepare-" + name);
      this.thread.setDaemon(true);
    }

    @Override
    public void run() {
      int prepared = currentIndex;
      try {
        while (!stopping) {
          final JournalWriter full = retiring;
          if (full != null) {
            full.close();
            retiring = null;
            progress.lazySet(progress.get() + 1);
          }
          final int index = currentIndex;
          if (ready == null && prepared == index && index < MAX_SEGMENTS) {
            ready = prepare(index + 1);
            prepared = index + 1;
          } else if (retiring == null) {
            LockSupport.parkNanos(PREPARER_PARK_NANOS);
          }
        }
      } catch (final Throwable t) {
        failure = t;
      } finally {
        final JournalWriter full = retiring;
        if (full != null) {
          full.close();
          retiring = null;
        }
      }
    }

    private JournalWriter prepare(final int index) throws IOException {
      final Path target = segmentPath(dir, name, index);
      final Path temp = dir.resolve(target.getFileName() + ".pending");
      // a segment being prepared when the journal closes is never used: stop writing it (DR-INT-10)
      final JournalWriter created =
          JournalWriter.create(
              temp,
              segmentCapacity,
              clock,
              chunks -> {
                progress.lazySet(progress.get() + 1);
                return !stopping;
              });
      if (created == null) {
        return null;
      }
      try (JournalWriter w = created) {
        if (stopping) {
          w.discard();
          Files.deleteIfExists(temp);
          return null;
        }
        w.sync();
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      progress.lazySet(progress.get() + 1);
      final JournalWriter w = JournalWriter.open(target, clock);
      // a segment being prepared when the journal closes is never used: stop touching it
      w.pretouch(
          pagesSoFar -> {
            progress.lazySet(progress.get() + JournalWriter.PRETOUCH_PAGES_PER_CHECK);
            return !stopping;
          });
      return w;
    }

    /** Called by the appender at a roll; waits, counted, if the next segment is not ready. */
    JournalWriter take() {
      JournalWriter next = ready;
      if (next == null) {
        rollWaits.lazySet(rollWaits.get() + 1);
        while ((next = ready) == null) {
          checkFailure();
          LockSupport.unpark(thread);
          Thread.onSpinWait();
        }
      }
      ready = null;
      return next;
    }

    /** Called by the appender at a roll to hand over the full segment. */
    void retire(final JournalWriter full) {
      while (retiring != null) {
        checkFailure();
        Thread.onSpinWait();
      }
      retiring = full;
    }

    /** Called by the appender after a roll; the thread prepares the segment after this one. */
    void becameCurrent(final int index) {
      currentIndex = index;
      LockSupport.unpark(thread);
    }

    private void checkFailure() {
      final Throwable t = failure;
      if (t != null) {
        throw new IllegalStateException("journal " + name + ": could not prepare the next segment", t);
      }
    }

    long progress() {
      return progress.get();
    }

    void stop() {
      stopping = true;
      LockSupport.unpark(thread);
      // wait while the thread makes progress; give up only after STALL_NANOS without any (DR-INT-10)
      final ShutdownProgress watch = new ShutdownProgress(STALL_NANOS);
      while (thread.isAlive()) {
        try {
          thread.join(JOIN_STEP_MILLIS);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        if (thread.isAlive() && watch.stalled(progress.get(), System.nanoTime())) {
          break;
        }
      }
      if (thread.isAlive()) {
        throw new IllegalStateException(
            "journal " + name + ": background thread made no progress for "
                + TimeUnit.NANOSECONDS.toSeconds(STALL_NANOS) + " s and did not stop (progress " + progress.get()
                + ", roll waits " + rollWaits.get() + ")");
      }
      final JournalWriter unused = ready;
      if (unused != null) {
        ready = null;
        final Path path = segmentPath(dir, name, segmentIndex + 1);
        unused.discard();
        try {
          Files.deleteIfExists(path);
        } catch (final IOException e) {
          // an unused prepared segment that cannot be removed stays as an empty segment
        }
      }
      // a crash of the preparing thread mid-way can leave a .pending file; readers ignore it
      try {
        Files.deleteIfExists(dir.resolve(segmentPath(dir, name, segmentIndex + 1).getFileName() + ".pending"));
      } catch (final IOException e) {
        // left for an operator; readers ignore .pending files
      }
    }
  }
}
