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
 * frame will get. When the ring is full it spins until the journal thread makes room and counts the
 * wait ({@link #producerWaits()}); it never drops a frame, because a journal with a hole cannot be
 * replayed. A frame larger than an empty segment can hold is refused on the producer's thread
 * ({@link JournalFullException}), so the journal thread never meets that error. If the journal
 * thread fails, the next {@code append} throws {@link IllegalStateException} with the cause.
 *
 * <p>A process crash loses the frames still in the ring; the direct mode loses none that reached
 * the mapping. {@link #close()} drains the ring, then closes the segmented journal.
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

  private final SegmentedJournal journal;
  private final Clock clock;
  private final OneToOneRingBuffer ring;
  private final MutableDirectBuffer ringBuffer;
  private final int maxPayload;
  private final int maxFrame;
  private final Thread drainer;

  /** Read and written on the producing thread only. */
  private long nextSequence;

  /** Written by {@link #close()}, which may run on another thread once the producer has stopped. */
  private volatile boolean closed;

  private volatile boolean stopping;
  private volatile Throwable failure;
  /** Written by the producer only; read from any thread. */
  private final AtomicLong producerWaits = new AtomicLong();

  private RingJournal(
      final String name, final SegmentedJournal journal, final Clock clock, final int ringCapacity) {
    this.journal = journal;
    this.clock = clock;
    final int bytes = ringCapacity + RingBufferDescriptor.TRAILER_LENGTH;
    this.ring = new OneToOneRingBuffer(new UnsafeBuffer(ByteBuffer.allocateDirect(bytes)));
    this.ringBuffer = ring.buffer();
    this.maxPayload = ring.maxMsgLength() - RECORD_HEADER_LENGTH;
    this.maxFrame = journal.segmentCapacity() - FILE_HEADER_LENGTH - Integer.BYTES;
    this.nextSequence = journal.nextSequence();
    final Drain drain = new Drain(journal);
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
    if (!BitUtil.isPowerOfTwo(ringCapacity) || ringCapacity < (64 << 10)) {
      throw new IllegalArgumentException("ring capacity must be a power of two >= 64 KiB: " + ringCapacity);
    }
    return new RingJournal(name, journal, clock, ringCapacity);
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
      producerWaits.lazySet(producerWaits.get() + 1);
      do {
        checkFailure();
        Thread.onSpinWait();
        index = ring.tryClaim(typeId, recordLength);
      } while (index == RingBuffer.INSUFFICIENT_CAPACITY);
    }
    ringBuffer.putLong(index + RECORD_TIMESTAMP_OFFSET, clock.epochNanos(), JournalFormat.BYTE_ORDER);
    ringBuffer.putLong(index + RECORD_VERSION_OFFSET, schemaVersion & 0xFFFFL, JournalFormat.BYTE_ORDER);
    ringBuffer.putBytes(index + RECORD_HEADER_LENGTH, payload, offset, length);
    ring.commit(index);
    return nextSequence++;
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

  /** Drains the ring, stops the journal thread and closes the segmented journal. */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    stopping = true;
    try {
      drainer.join(TimeUnit.MINUTES.toMillis(1));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (drainer.isAlive()) {
      throw new IllegalStateException("journal thread did not stop within one minute");
    }
    journal.close();
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
    private final IdleStrategy idle =
        new BackoffIdleStrategy(100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100));

    Drain(final SegmentedJournal target) {
      this.target = target;
    }

    @Override
    public void run() {
      try {
        while (true) {
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
