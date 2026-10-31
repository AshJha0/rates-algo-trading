package io.ratesalgo.core;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;

/**
 * A gateway thread's handle on a loop's inbound ring. One instance per publishing thread; the
 * counters are plain fields owned by that thread.
 *
 * <p>{@link #offer} and {@link #tryClaim} never block. When the ring is full they return false or
 * {@link RingBuffer#INSUFFICIENT_CAPACITY} and count the failure; the caller then drops the message
 * (market data, which the next update supersedes) or retries (session messages it must not lose),
 * as API_SPEC.md Section 9.3 states per message kind.
 */
public final class InboundPublisher {

  private final RingBuffer ring;
  private long published;
  private long rejected;

  public InboundPublisher(final RingBuffer ring) {
    this.ring = ring;
  }

  /**
   * Copies one SBE message (header plus body) into the ring.
   *
   * @return true if published; false if the ring was full, counted in {@link #rejectedCount()}
   * @throws IllegalArgumentException if the length is outside the contract
   */
  public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
    checkLength(length);
    final int templateId = RingBufferContract.templateId(buffer, offset);
    if (ring.write(templateId, buffer, offset, length)) {
      published++;
      return true;
    }
    rejected++;
    return false;
  }

  /**
   * Claims space to encode a message in place. Encode at {@link #buffer()} from the returned index
   * and then {@link #commit(int)} or {@link #abort(int)}.
   *
   * @return the index to encode at, or {@link RingBuffer#INSUFFICIENT_CAPACITY} when full
   */
  public int tryClaim(final int templateId, final int length) {
    checkLength(length);
    final int index = ring.tryClaim(templateId, length);
    if (index < 0) {
      rejected++;
    }
    return index;
  }

  /** The ring's buffer, for encoding at a claimed index. */
  public AtomicBuffer buffer() {
    return ring.buffer();
  }

  /** Publishes a claimed record. */
  public void commit(final int index) {
    ring.commit(index);
    published++;
  }

  /** Releases a claimed record without publishing it. */
  public void abort(final int index) {
    ring.abort(index);
  }

  /** Messages published by this publisher. */
  public long publishedCount() {
    return published;
  }

  /** Offers and claims refused because the ring was full. */
  public long rejectedCount() {
    return rejected;
  }

  private static void checkLength(final int length) {
    if (length < RingBufferContract.SBE_HEADER_LENGTH
        || length > RingBufferContract.MAX_MESSAGE_LENGTH) {
      throw ContractBreach.length(length);
    }
  }
}
