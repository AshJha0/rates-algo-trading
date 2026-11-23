package io.ratesalgo.journal;

import static io.ratesalgo.journal.JournalFormat.FILE_HEADER_LENGTH;
import static io.ratesalgo.journal.JournalFormat.alignedFrameLength;

import io.ratesalgo.common.time.Clock;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * A segmented journal written by its own thread. {@link #append} copies the payload, the template
 * id, the schema version and a stamp from the producer's clock into an off-heap single-producer
 * ring; a journal thread ({@code journal-drain-<name>}) drains the ring into a {@link
 * SegmentedJournal} with {@link SegmentedJournal#append(int, int, long, DirectBuffer, int, int)}.
 *
 * <p>The files are the same as those of the direct mode, frame for frame: the sequence and the
 * chain hash are assigned by the same {@code JournalWriter} code from the same payload bytes, in the
 * order the producer appended them, and each frame carries the stamp the producer's clock gave at
 * {@code append}, which for a loop is the latched cycle time (API_SPEC.md Section 9.7, DECISIONS.md
 * DR-R2J-03). Only the moment a frame reaches the mapped file differs: it is behind the producer
 * by the ring's backlog, normally microseconds.
 *
 * <p>{@link #append} is called on one thread only, does not allocate, and returns the sequence the
 * frame will get. A frame larger than an empty segment can hold is refused on the producer's thread
 * ({@link JournalFullException}), so the journal thread never meets that error. If the journal
 * thread fails, the next {@code append} throws {@link IllegalStateException} with the cause.
 *
 * <p>Back-pressure (DECISIONS.md DR-R6-L04). The ring is bounded. When it is full the producer
 * waits for the journal thread to make room, counted in {@link #producerWaits()}; it never drops a
 * frame, because a journal with a hole cannot be replayed. The wait is bounded too: as long as the
 * journal thread moves (its consumer position, or the segment preparer a roll waits for) the
 * producer keeps waiting; after {@link #stallNanos()} with neither, or as soon as the journal thread
 * has died, {@code append} throws {@link IllegalStateException}. For a loop that is a loop failure:
 * its runner stops and the process supervisor withdraws from the gateway side. A loop that waits
 * makes no duty cycle, so the supervisor's own stall limit acts first when it is the shorter.
 *
 * <p>Crash semantics. A process crash loses the frames still in the ring, at most {@link
 * #backlog()} bytes, which the journal thread keeps to microseconds at the benchmark rates ({@link
 * #backlogHighWater()} records the most seen); the direct mode loses none that reached the mapping.
 * Both modes lose what the operating system had not written back if the machine itself fails. A
 * restart repairs whatever tail is left ({@link JournalRecovery}) and replays it the same way in
 * either mode, because the files are the same. {@link #close()} drains the ring, then closes the
 * segmented journal.
 */
public final class RingJournal implements AutoCloseable {

  /** Default ring size: 64 MiB, about 0.5 million QuoteRequest frames. */
  public static final int DEFAULT_RING_CAPACITY = 64 << 20;

  /** Ring record: timestamp (int64), schema version (int32), four zero bytes, then the payload. */
  private static final int RECORD_TIMESTAMP_OFFSET = 0;

  private static final int RECORD_VERSION_OFFSET = 8;
  private static final int RECORD_HEADER_LENGTH = 16;

  /** Frames drained per read of the ring. */
  private static final int DRAIN_LIMIT = 1024;

  /** How long {@link #close()} waits with no progress before it gives up (DR-INT-10). */
  static final long CLOSE_STALL_NANOS = SegmentedJournal.STALL_NANOS;

  private final SegmentedJournal journal;
  private final Clock clock;
  private final OneToOneRingBuffer ring;
  private final MutableDirectBuffer ringBuffer;
  private final int maxPayload;
  private final int maxFrame;
  private final Thread drainer;
  private final long stallNanos;

  /** Read and written on the producing thread only. */
  private long nextSequence;

  /** Written by {@link #close()}, which may run on another thread once the producer has stopped. */
  private volatile boolean closed;

  /** True once the segmented journal has been closed; a close that gave up before that can be retried. */
  private volatile boolean journalClosed;

  private volatile boolean stopping;
  private volatile Throwable failure;
  /** Written by the producer only; read from any thread. */
  private final AtomicLong producerWaits = new AtomicLong();
  /** Written by the journal thread only; read from any thread. */
  private final AtomicLong backlogHighWater = new AtomicLong();

  private RingJournal(
      final String name,
      final SegmentedJournal journal,
      final Clock clock,
      final int ringCapacity,
      final IdleStrategy drainIdle,
      final long stallNanos) {
    this.journal = journal;
    this.clock = clock;
    this.stallNanos = stallNanos;
    final int bytes = ringCapacity + RingBufferDescriptor.TRAILER_LENGTH;
    this.ring = new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(bytes)));
    this.ringBuffer = ring.buffer();
    this.maxPayload = ring.maxMsgLength() - RECORD_HEADER_LENGTH;
    this.maxFrame = journal.segmentCapacity() - FILE_HEADER_LENGTH - Integer.BYTES;
    this.nextSequence = journal.nextSequence();
    final Drain drain = new Drain(journal, drainIdle);
    this.drainer = new Thread(drain, "journal-drain-" + name);
    this.drainer.setDaemon(true);
    this.drainer.start();
  }

  /**
   * Starts a journal thread that drains a ring of {@code ringCapacity} bytes into {@code journal}.
   * The returned object owns {@code journal} and closes it. Frames are stamped with {@code clock},
   * read on the appending thread.
   *
   * @param name names the journal thread
   * @param ringCapacity a power of two of at least 64 KiB
   */
  public static RingJournal start(
      final String name, final SegmentedJournal journal, final Clock clock, final int ringCapacity) {
    return start(name, journal, clock, ringCapacity, defaultDrainIdle(), STALL_NANOS);
  }

  /**
   * {@link #start(String, SegmentedJournal, Clock, int)} with the journal thread's idle strategy
   * (a busy spin keeps the backlog shortest and burns a core) and the producer's stall limit.
   *
   * @param drainIdle what the journal thread does when the ring is empty
   * @param stallNanos how long a producer waiting on a full ring tolerates no progress at all
   */
  public static RingJournal start(
      final String name,
      final SegmentedJournal journal,
      final Clock clock,
      final int ringCapacity,
      final IdleStrategy drainIdle,
      final long stallNanos) {
    if (!BitUtil.isPowerOfTwo(ringCapacity) || ringCapacity < (64 << 10)) {
      throw new IllegalArgumentException("ring capacity must be a power of two >= 64 KiB: " + ringCapacity);
    }
    if (stallNanos <= 0) {
      throw new IllegalArgumentException("stall limit must be positive: " + stallNanos);
    }
    return new RingJournal(name, journal, clock, ringCapacity, drainIdle, stallNanos);
  }

  /** How long a producer on a full ring waits with no progress before {@code append} fails, by default. */
  public static final long STALL_NANOS = SegmentedJournal.STALL_NANOS;

  /** The journal thread's idle strategy unless one is given: spin, yield, then park up to 100 us. */
  public static IdleStrategy defaultDrainIdle() {
    return new BackoffIdleStrategy(100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100));
  }

  /**
   * Queues one frame for the journal thread.
   *
   * @return the sequence number the frame will carry
   * @throws JournalFullException if the frame is larger than an empty segment can hold; nothing is
   *     queued in that case
   * @throws IllegalArgumentException if the payload is negative or larger than the ring takes
   * @throws IllegalStateException if the journal is closed or its thread has failed
   */
  public long append(
      final int templateId,
      final int schemaVersion,
      final DirectBuffer payload,
      final int offset,
      final int length) {
    if (closed) {
      throw new IllegalStateException("ring journal is closed");
    }
    checkFailure();
    if (length < 0 || length > maxPayload) {
      throw new IllegalArgumentException("payload length out of range: " + length);
    }
    final int frameLength = alignedFrameLength(length);
    if (frameLength > maxFrame) {
      throw new JournalFullException(frameLength, maxFrame);
    }
    final int recordLength = RECORD_HEADER_LENGTH + length;
    // the ring refuses type id 0, and template ids start at 0
    final int typeId = templateId + 1;
    int index = ring.tryClaim(typeId, recordLength);
    if (index == RingBuffer.INSUFFICIENT_CAPACITY) {
      index = awaitRoom(typeId, recordLength);
    }
    ringBuffer.putLong(index + RECORD_TIMESTAMP_OFFSET, clock.epochNanos(), JournalFormat.BYTE_ORDER);
    ringBuffer.putLong(index + RECORD_VERSION_OFFSET, schemaVersion & 0xFFFFL, JournalFormat.BYTE_ORDER);
    ringBuffer.putBytes(index + RECORD_HEADER_LENGTH, payload, offset, length);
    ring.commit(index);
    return nextSequence++;
  }

  /**
   * The full-ring wait, out of line so that {@code append} stays small. Waits while the journal
   * thread makes progress; throws once it has died or made none for the stall limit. Reading the
   * time and the positions allocates nothing.
   */
  private int awaitRoom(final int typeId, final int recordLength) {
    producerWaits.lazySet(producerWaits.get() + 1);
    // both counts only rise, so their sum moves exactly when either does
    long seen = ring.consumerPosition() + journal.preparerProgress();
    long since = System.nanoTime();
    int index;
    do {
      checkFailure();
      if (!drainer.isAlive()) {
        checkFailure(); // a failure recorded just before the thread ended is the better message
        throw new IllegalStateException("journal thread " + drainer.getName() + " has stopped; the ring is full");
      }
      Thread.onSpinWait();
      index = ring.tryClaim(typeId, recordLength);
      if (index == RingBuffer.INSUFFICIENT_CAPACITY) {
        final long count = ring.consumerPosition() + journal.preparerProgress();
        final long now = System.nanoTime();
        if (count != seen) {
          seen = count;
          since = now;
        } else if (now - since >= stallNanos) {
          throw new IllegalStateException(
              "journal thread " + drainer.getName() + " made no progress for "
                  + TimeUnit.NANOSECONDS.toMillis(stallNanos) + " ms with the ring full: backlog " + ring.size()
                  + " bytes, roll waits " + journal.rollWaits());
        }
      }
    } while (index == RingBuffer.INSUFFICIENT_CAPACITY);
    return index;
  }

  /** The producer's stall limit on a full ring. */
  public long stallNanos() {
    return stallNanos;
  }

  /** The largest backlog the journal thread has found before a read, in bytes. Readable from any thread. */
  public long backlogHighWater() {
    return backlogHighWater.get();
  }

  /** The sequence the next queued frame will get. Call on the appending thread. */
  public long nextSequence() {
    return nextSequence;
  }

  /** Bytes queued and not yet written to the journal. Readable from any thread. */
  public int backlog() {
    return ring.size();
  }

  /** Appends that found the ring full and waited. Readable from any thread. */
  public long producerWaits() {
    return producerWaits.get();
  }

  /** Rolls of the underlying journal that waited for the next segment. */
  public long rollWaits() {
    return journal.rollWaits();
  }

  /**
   * Waits until every queued frame has been written to the mapped file, for tests and shutdown
   * paths; never call on a latency-sensitive thread.
   *
   * @throws IllegalStateException if the journal thread has failed or the wait times out
   */
  public void awaitDrained(final long timeout, final TimeUnit unit) {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (ring.size() > 0 || ring.consumerPosition() != ring.producerPosition()) {
      checkFailure();
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("ring journal not drained within " + timeout + " " + unit);
      }
      Thread.onSpinWait();
    }
    checkFailure();
  }

  /**
   * Drains the ring, stops the journal thread and closes the segmented journal. Waits as long as
   * there is progress: the journal thread's consumed position advances, or the segmented journal's
   * background thread works on the segment a waiting roll needs (pages touched, segments created).
   * Gives up only after {@link #CLOSE_STALL_NANOS} with neither (DR-INT-10).
   *
   * <p>A close that gave up has not closed the files. Calling close again waits again, so a caller
   * can retry once whatever held the journal thread has cleared; appends stay refused meanwhile.
   *
   * @throws IllegalStateException if the journal thread failed, made no progress for the stall
   *     limit (the message gives the backlog, both ring positions, roll waits and preparer
   *     progress), or the closing thread was
   *     interrupted before the journal thread stopped
   */
  @Override
  public void close() {
    closeWithin(CLOSE_STALL_NANOS);
  }

  /** {@link #close()} with a given stall limit; package-private for tests. */
  void closeWithin(final long stallNanos) {
    if (journalClosed) {
      return;
    }
    closed = true;
    stopping = true;
    final ShutdownProgress progress = new ShutdownProgress(stallNanos);
    boolean interrupted = false;
    try {
      while (drainer.isAlive()) {
        try {
          drainer.join(SegmentedJournal.JOIN_STEP_MILLIS);
        } catch (final InterruptedException e) {
          interrupted = true;
          break;
        }
        // both counts only rise, so their sum moves exactly when either does
        final long count = ring.consumerPosition() + journal.preparerProgress();
        if (drainer.isAlive() && progress.stalled(count, System.nanoTime())) {
          throw new IllegalStateException(
              "journal thread " + drainer.getName() + " made no progress for "
                  + TimeUnit.NANOSECONDS.toMillis(stallNanos) + " ms while closing: backlog " + ring.size()
                  + " bytes, consumer position " + ring.consumerPosition() + ", producer position "
                  + ring.producerPosition() + ", roll waits " + journal.rollWaits() + ", preparer progress "
                  + journal.preparerProgress() + ", " + progress.advances() + " advances seen");
        }
      }
      if (interrupted && drainer.isAlive()) {
        throw new IllegalStateException(
            "interrupted while closing journal thread " + drainer.getName() + ": backlog " + ring.size() + " bytes");
      }
    } finally {
      // the segmented journal is closed only once its single writer, the journal thread, has stopped
      if (!drainer.isAlive()) {
        journalClosed = true;
        journal.close();
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    checkFailure();
  }

  private void checkFailure() {
    final Throwable t = failure;
    if (t != null) {
      throw new IllegalStateException("journal thread failed", t);
    }
  }

  /** The journal thread: drains the ring until stopped and empty. */
  private final class Drain implements Runnable, MessageHandler {

    private final SegmentedJournal target;
    private final IdleStrategy idle;

    Drain(final SegmentedJournal target, final IdleStrategy idle) {
      this.target = target;
      this.idle = idle;
    }

    @Override
    public void run() {
      try {
        while (true) {
          final long backlog = ring.producerPosition() - ring.consumerPosition();
          if (backlog > backlogHighWater.get()) {
            backlogHighWater.lazySet(backlog);
          }
          final int n = ring.read(this, DRAIN_LIMIT);
          if (n == 0) {
            if (stopping && ring.consumerPosition() == ring.producerPosition()) {
              return;
            }
            idle.idle();
          } else {
            idle.reset();
          }
        }
      } catch (final Throwable t) {
        failure = t;
      }
    }

    @Override
    public void onMessage(
        final int typeId, final MutableDirectBuffer buffer, final int index, final int length) {
      target.append(
          typeId - 1,
          (int) buffer.getLong(index + RECORD_VERSION_OFFSET, JournalFormat.BYTE_ORDER),
          buffer.getLong(index + RECORD_TIMESTAMP_OFFSET, JournalFormat.BYTE_ORDER),
          buffer,
          index + RECORD_HEADER_LENGTH,
          length - RECORD_HEADER_LENGTH);
    }
  }
}
