package io.ratesalgo.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

/**
 * The constants and constructors of the ring-buffer contract between gateways and the loop
 * (API_SPEC.md Section 9.2). A gateway needs only this class, {@link InboundPublisher} and the SBE
 * codecs.
 *
 * <ul>
 *   <li>A record's message type id is the SBE template id of its payload, and the payload is the
 *       SBE message header followed by the body, exactly as it is journaled.
 *   <li>A payload is at most {@link #MAX_MESSAGE_LENGTH} bytes.
 *   <li>Inbound rings are {@link ManyToOneRingBuffer}s (any number of gateway threads, one loop);
 *       outbound rings are {@link OneToOneRingBuffer}s (the loop to one gateway thread).
 *   <li>Capacities are powers of two, fixed at start-up.
 * </ul>
 */
public final class RingBufferContract {

  /** Largest payload (SBE header plus body) a ring accepts under this contract. */
  public static final int MAX_MESSAGE_LENGTH = 1024;

  /** SBE message header length. */
  public static final int SBE_HEADER_LENGTH = 8;

  /** Default inbound ring capacity, 1 MiB: about 11,000 QuoteRequests. */
  public static final int DEFAULT_INBOUND_CAPACITY = 1 << 20;

  /** Default outbound ring capacity, 256 KiB. */
  public static final int DEFAULT_OUTBOUND_CAPACITY = 1 << 18;

  /** Smallest capacity that admits a maximum-length message (Agrona allows capacity / 8). */
  public static final int MIN_CAPACITY = MAX_MESSAGE_LENGTH * 8;

  private RingBufferContract() {}

  /** A new inbound ring on direct memory; {@code capacity} must be a power of two. */
  public static ManyToOneRingBuffer newInbound(final int capacity) {
    return new ManyToOneRingBuffer(buffer(capacity));
  }

  /** A new outbound ring on direct memory; {@code capacity} must be a power of two. */
  public static OneToOneRingBuffer newOutbound(final int capacity) {
    return new OneToOneRingBuffer(buffer(capacity));
  }

  private static UnsafeBuffer buffer(final int capacity) {
    if (capacity < MIN_CAPACITY || Integer.bitCount(capacity) != 1) {
      throw new IllegalArgumentException(
          "ring capacity must be a power of two of at least " + MIN_CAPACITY + ": " + capacity);
    }
    return new UnsafeBuffer(
        ByteBuffer.allocateDirect(capacity + RingBufferDescriptor.TRAILER_LENGTH));
  }

  /** The template id in the SBE header at {@code offset}. */
  public static int templateId(final DirectBuffer buffer, final int offset) {
    return buffer.getShort(offset + 2, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
  }

  /** The schema id in the SBE header at {@code offset}. */
  public static int schemaId(final DirectBuffer buffer, final int offset) {
    return buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
  }

  /** The schema version in the SBE header at {@code offset}. */
  public static int version(final DirectBuffer buffer, final int offset) {
    return buffer.getShort(offset + 6, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
  }
}
